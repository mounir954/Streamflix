package com.streamflixreborn.streamflix.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Version Android du résolveur de navigateur "headless" : même contrat public que la version desktop
 * (qui pilotait Chromium via Playwright), mais appuyé sur une WebView invisible.
 *
 * Utilisé par les providers/extracteurs qui doivent exécuter le JavaScript d'une page (défis Cloudflare,
 * liens générés côté client...). Toutes les opérations sur la WebView se font sur le thread principal.
 *
 * Limites connues par rapport à Playwright :
 *  - waitForResponseBody ne peut relire que les requêtes GET (le corps d'une réponse WebView n'est pas
 *    accessible : on refait la requête côté natif pour la capturer et on la renvoie telle quelle à la page)
 *  - showImmediately (résolution manuelle d'un CAPTCHA) n'ouvre pas de fenêtre : la WebView reste cachée
 */
class HeadlessBrowserResolver {

    data class Result(
        val html: String,
        val evaluatedValue: String? = null,
        val finalUrl: String? = null,
    )

    private val mutex = Mutex()

    private val challengeKeywords = listOf(
        "Just a moment...", "cf-browser-verification", "challenge-running", "Checking your browser", "cloudflare"
    )

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        completion: ((currentUrl: String, html: String, cookies: String) -> Boolean)? = null,
        shouldAllowNavigation: ((url: String, isMainFrame: Boolean) -> Boolean)? = null,
        pageReadyScriptProvider: ((currentUrl: String, html: String, cookies: String) -> String?)? = null,
        showImmediately: Boolean = false,
    ): String = getResult(url, headers, completion, shouldAllowNavigation, null, pageReadyScriptProvider, showImmediately).html

    suspend fun getResult(
        url: String,
        headers: Map<String, String> = emptyMap(),
        completion: ((currentUrl: String, html: String, cookies: String) -> Boolean)? = null,
        shouldAllowNavigation: ((url: String, isMainFrame: Boolean) -> Boolean)? = null,
        valueScript: String? = null,
        pageReadyScriptProvider: ((currentUrl: String, html: String, cookies: String) -> String?)? = null,
        showImmediately: Boolean = false,
    ): Result = mutex.withLock {
        val timeoutResult = Result(html = "<html><body>Timeout</body></html>", finalUrl = url)
        val holder = AtomicReference<WebView?>(null)
        try {
            withTimeoutOrNull(120_000L) {
                runResolution(holder, url, headers, completion, shouldAllowNavigation, valueScript, pageReadyScriptProvider)
            } ?: timeoutResult
        } finally {
            destroy(holder.get())
        }
    }

    private suspend fun runResolution(
        holder: AtomicReference<WebView?>,
        url: String,
        headers: Map<String, String>,
        completion: ((String, String, String) -> Boolean)?,
        shouldAllowNavigation: ((String, Boolean) -> Boolean)?,
        valueScript: String?,
        pageReadyScriptProvider: ((String, String, String) -> String?)?,
    ): Result {
        val web = createWebView(holder, shouldAllowNavigation = shouldAllowNavigation)
        onMain { web.loadUrl(url, headers) }

        var polling = 0
        while (polling < 80) {
            val currentUrl = onMain { web.url } ?: url
            val html = evalString(web, "document.documentElement.outerHTML") ?: ""
            val cookies = CookieManager.getInstance().getCookie(currentUrl).orEmpty()
            val hasClearance = cookies.contains("cf_clearance")
            val isChallenge = challengeKeywords.any { html.contains(it, ignoreCase = true) }
            val hasContent = html.contains("article") || html.contains("iframe") ||
                html.contains("TPost") || html.contains("grid-item") || html.contains("optnslst")
            val success = completion?.invoke(currentUrl, html, cookies)
                ?: ((!isChallenge && hasContent && html.length > 1000) || hasClearance)

            val readyScript = pageReadyScriptProvider?.invoke(currentUrl, html, cookies)
            if (!readyScript.isNullOrBlank()) evalString(web, readyScript)

            if (success) return finish(web, html, currentUrl, valueScript)
            polling++
            delay(2000)
        }
        val finalUrl = onMain { web.url } ?: url
        return finish(web, evalString(web, "document.documentElement.outerHTML") ?: "", finalUrl, valueScript)
    }

    private suspend fun finish(web: WebView, html: String, finalUrl: String, valueScript: String?): Result {
        val evaluated = valueScript?.let { evalString(web, it) }
        return Result(html = "<html>$html</html>", evaluatedValue = evaluated?.trim(), finalUrl = finalUrl)
    }

    /** Charge la page, suit les redirections (JS compris) et retourne l'URL finale dès qu'elle contient l'un des fragments. */
    suspend fun resolveNavigation(
        url: String,
        headers: Map<String, String> = emptyMap(),
        matchSubstrings: List<String>,
        timeoutMs: Long = 30_000L,
    ): String = mutex.withLock {
        val holder = AtomicReference<WebView?>(null)
        try {
            withTimeoutOrNull(timeoutMs) {
                val web = createWebView(holder)
                onMain { web.loadUrl(url, headers) }
                if (matchSubstrings.isEmpty()) {
                    delay(3000)
                    return@withTimeoutOrNull onMain { web.url } ?: url
                }
                repeat(timeoutMs.toInt() / 500) {
                    val current = onMain { web.url } ?: url
                    if (matchSubstrings.any { current.contains(it) }) return@withTimeoutOrNull current
                    delay(500)
                }
                onMain { web.url } ?: url
            } ?: url
        } finally {
            destroy(holder.get())
        }
    }

    /** Charge la page et retourne l'URL de la première requête sortante qui satisfait [predicate] (ex: un .m3u8). */
    suspend fun waitForRequestUrl(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 30_000L,
        predicate: (String) -> Boolean,
    ): String? = mutex.withLock {
        val holder = AtomicReference<WebView?>(null)
        val found = CompletableDeferred<String>()
        try {
            withTimeoutOrNull(timeoutMs) {
                val web = createWebView(holder, onRequest = { requestUrl, _ ->
                    if (!found.isCompleted && predicate(requestUrl)) found.complete(requestUrl)
                    null
                })
                onMain { web.loadUrl(url, headers) }
                found.await()
            }
        } finally {
            destroy(holder.get())
        }
    }

    /** Charge la page, laisse son JS tourner et retourne le corps de la première réponse GET qui satisfait [predicate]. */
    suspend fun waitForResponseBody(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 30_000L,
        predicate: (String) -> Boolean,
    ): String? = mutex.withLock {
        val holder = AtomicReference<WebView?>(null)
        val found = CompletableDeferred<String>()
        try {
            withTimeoutOrNull(timeoutMs) {
                val web = createWebView(holder, onRequest = { requestUrl, request ->
                    if (!found.isCompleted && request.method == "GET" && predicate(requestUrl)) {
                        // le corps n'est pas lisible depuis la WebView : on le récupère nous-mêmes et on le lui rend
                        captureResponse(requestUrl, request.requestHeaders)?.also { (response, body) ->
                            found.complete(body)
                        }?.first
                    } else null
                })
                onMain { web.loadUrl(url, headers) }
                found.await()
            }
        } finally {
            destroy(holder.get())
        }
    }

    // ------------------------------------------------------------------ WebView plumbing

    private val mainHandler = Handler(Looper.getMainLooper())

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun createWebView(
        holder: AtomicReference<WebView?>,
        shouldAllowNavigation: ((String, Boolean) -> Boolean)? = null,
        onRequest: ((url: String, request: WebResourceRequest) -> WebResourceResponse?)? = null,
    ): WebView = onMain {
        val context: Context = AndroidContextHolder.context
        val web = WebView(context)
        holder.set(web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = false // inutile pour extraire des liens, économise données et mémoire
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = NetworkClient.USER_AGENT
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val requestUrl = request.url.toString()
                if (shouldAllowNavigation != null && !shouldAllowNavigation(requestUrl, request.isForMainFrame)) {
                    // requête refusée par l'appelant : réponse vide plutôt que de la laisser partir
                    return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                return onRequest?.invoke(requestUrl, request)
            }
        }
        web
    }

    private suspend fun evalString(web: WebView, script: String): String? = withContext(Dispatchers.Main) {
        suspendCoroutine { cont ->
            runCatching {
                web.evaluateJavascript(script) { raw -> cont.resume(decodeJsString(raw)) }
            }.onFailure { cont.resume(null) }
        }
    }

    /** evaluateJavascript renvoie du JSON : une chaîne arrive entre guillemets et échappée. */
    private fun decodeJsString(raw: String?): String? {
        if (raw == null || raw == "null") return null
        if (raw.length < 2 || raw.first() != '"' || raw.last() != '"') return raw
        val sb = StringBuilder(raw.length)
        var i = 1
        val end = raw.length - 1
        while (i < end) {
            val c = raw[i]
            if (c != '\\' || i + 1 >= end) {
                sb.append(c); i++; continue
            }
            when (val n = raw[i + 1]) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> sb.append('\r')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                '"', '\\', '/' -> sb.append(n)
                'u' -> {
                    val hex = if (i + 6 <= end) raw.substring(i + 2, i + 6) else ""
                    val code = hex.toIntOrNull(16)
                    if (hex.length == 4 && code != null) {
                        sb.append(code.toChar()); i += 6; continue
                    }
                    sb.append(n)
                }
                else -> sb.append(n)
            }
            i += 2
        }
        return sb.toString()
    }

    private fun destroy(web: WebView?) {
        if (web == null) return
        mainHandler.post {
            runCatching {
                web.stopLoading()
                web.loadUrl("about:blank")
                web.destroy()
            }
        }
    }

    /** Refait une requête GET côté natif (en reprenant les en-têtes de la WebView) et la renvoie sous forme de réponse WebView. */
    private fun captureResponse(url: String, requestHeaders: Map<String, String>): Pair<WebResourceResponse, String>? {
        return runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            requestHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
            val status = conn.responseCode
            val stream = if (status in 200..399) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            val mime = conn.contentType?.substringBefore(';')?.trim() ?: "application/octet-stream"
            val charset = conn.contentType?.substringAfter("charset=", "utf-8")?.trim() ?: "utf-8"
            val response = WebResourceResponse(
                mime, charset, status.coerceAtLeast(200), conn.responseMessage?.takeIf { it.isNotBlank() } ?: "OK",
                mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(bytes),
            )
            response to String(bytes, Charsets.UTF_8)
        }.getOrNull()
    }
}
