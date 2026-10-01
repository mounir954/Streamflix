package com.streamflixreborn.streamflix.mobile

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * Écran unique : une WebView qui affiche l'interface StreamFlix servie par le backend embarqué
 * (http://127.0.0.1:<port>/). Gère le plein écran vidéo, le bouton retour et les liens externes.
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private var webView: WebView? = null
    private lateinit var splash: View
    private lateinit var errorView: View
    private lateinit var errorText: TextView

    // plein écran vidéo (bouton "plein écran" du lecteur -> requestFullscreen -> onShowCustomView)
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var baseUrl: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)

        splash = buildSplash()
        errorView = buildErrorView()
        root.addView(splash)
        root.addView(errorView)
        errorView.visibility = View.GONE

        startBackendThenLoad(savedInstanceState)
    }

    private fun startBackendThenLoad(savedInstanceState: Bundle?) {
        splash.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        Thread({
            try {
                val port = BackendHost.ensureStarted(this)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    baseUrl = "http://127.0.0.1:$port/"
                    showWebView(savedInstanceState)
                }
            } catch (e: Throwable) {
                runOnUiThread { showError("Impossible de démarrer le serveur local.\n\n${e.javaClass.simpleName}: ${e.message}") }
            }
        }, "streamflix-backend-start").start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWebView(savedInstanceState: Bundle?) {
        val web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                // les flux HLS sont servis par le proxy local (http) mais certaines ressources peuvent être https
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(false)
                builtInZoomControls = false
                setSupportMultipleWindows(false)
                cacheMode = WebSettings.LOAD_DEFAULT
                allowFileAccess = false
                allowContentAccess = false
            }
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (isLocal(uri)) return false
                // tout ce qui n'est pas l'app (pub, liens de sites tiers) s'ouvre dans le navigateur
                openExternal(uri)
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                splash.visibility = View.GONE
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && isLocal(request.url)) {
                    showError("L'interface locale n'a pas pu être chargée (${error.description}).")
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // le processus de rendu a été tué (manque de mémoire) : on recrée proprement la WebView
                webView?.let { root.removeView(it); it.destroy() }
                webView = null
                startBackendThenLoad(null)
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                view.setBackgroundColor(Color.BLACK)
                root.addView(view, FrameLayout.LayoutParams(-1, -1))
                web.visibility = View.INVISIBLE
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                applyImmersive(true)
            }

            override fun onHideCustomView() {
                val view = customView ?: return
                root.removeView(view)
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                web.visibility = View.VISIBLE
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                applyImmersive(false)
            }

            // poster vidéo par défaut transparent plutôt que l'icône "lecture" grise d'Android
            override fun getDefaultVideoPoster(): android.graphics.Bitmap? =
                android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        }

        webView = web
        root.addView(web, 0)
        // Toujours un chargement neuf (pas de restoreState) : le jeton de session change à chaque lancement
        // du processus, une URL restaurée d'une session précédente serait refusée par le serveur.
        web.loadUrl(BackendHost.startUrl)
    }

    private fun isLocal(uri: Uri): Boolean {
        val base = baseUrl?.let { Uri.parse(it) } ?: return false
        return uri.host == base.host && uri.port == base.port
    }

    private fun openExternal(uri: Uri) {
        val scheme = uri.scheme ?: return
        if (scheme != "http" && scheme != "https" && scheme != "mailto") return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, uri.toString(), Toast.LENGTH_LONG).show()
        }
    }

    private fun showError(message: String) {
        splash.visibility = View.GONE
        errorText.text = message
        errorView.visibility = View.VISIBLE
    }

    private fun buildSplash(): View = FrameLayout(this).apply {
        setBackgroundColor(Color.BLACK)
        val bar = ProgressBar(this@MainActivity).apply { isIndeterminate = true }
        addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    }

    private fun buildErrorView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.BLACK)
        setPadding(64, 64, 64, 64)
        errorText = TextView(this@MainActivity).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        addView(errorText, LinearLayout.LayoutParams(-1, -2))
        val retry = Button(this@MainActivity).apply {
            text = "Réessayer"
            setOnClickListener { startBackendThenLoad(null) }
        }
        addView(retry, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 48 })
    }

    @Suppress("DEPRECATION")
    private fun applyImmersive(enabled: Boolean) {
        val flags = if (enabled) {
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        } else {
            View.SYSTEM_UI_FLAG_VISIBLE
        }
        window.decorView.systemUiVisibility = flags
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val web = webView
        when {
            customView != null -> webView?.webChromeClient?.onHideCustomView()
            web != null && web.canGoBack() -> web.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onPause() {
        // ne PAS appeler webView.onPause() : la lecture se coupe sinon quand l'écran passe en arrière-plan / PiP
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        customViewCallback?.onCustomViewHidden()
        webView?.let {
            root.removeView(it)
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }
}
