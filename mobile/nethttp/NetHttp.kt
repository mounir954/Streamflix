package com.streamflixreborn.streamflix.mobile.nethttp

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.net.URI
import java.time.Duration
import java.util.Optional
import java.util.concurrent.TimeUnit

/*
 * Android n'a pas java.net.http (HttpClient/HttpRequest/HttpResponse), utilisé par le backend desktop.
 * Ces trois classes en reproduisent le sous-ensemble utilisé (GET/POST, headers, timeout, redirections,
 * BodyHandlers ofInputStream / ofByteArray / ofString / discarding) au-dessus d'OkHttp.
 */

class HttpClient private constructor(private val ok: OkHttpClient) {

    enum class Redirect { NEVER, NORMAL, ALWAYS }
    enum class Version { HTTP_1_1, HTTP_2 }

    class Builder {
        private var redirect = Redirect.NEVER
        private var version = Version.HTTP_2
        private var connectTimeout: Duration = Duration.ofSeconds(30)

        fun followRedirects(r: Redirect) = apply { redirect = r }
        fun version(v: Version) = apply { version = v }
        fun connectTimeout(d: Duration) = apply { connectTimeout = d }

        fun build(): HttpClient {
            val b = OkHttpClient.Builder()
                .followRedirects(redirect != Redirect.NEVER)
                .followSslRedirects(redirect != Redirect.NEVER)
                .connectTimeout(connectTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
            if (version == Version.HTTP_1_1) b.protocols(listOf(Protocol.HTTP_1_1))
            return HttpClient(b.build())
        }
    }

    companion object {
        @JvmStatic
        fun newBuilder() = Builder()
    }

    @Throws(java.io.IOException::class)
    fun <T> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        var client = ok
        request.timeout?.let { t ->
            // comme java.net.http : le timeout couvre l'attente de la réponse, pas le streaming du corps
            client = ok.newBuilder()
                .connectTimeout(t.toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(t.toMillis(), TimeUnit.MILLISECONDS)
                .build()
        }
        val call: Call = client.newCall(request.toOkHttp())
        val response = call.execute()
        val body = response.body
        val result: Any? = when (handler.kind) {
            HttpResponse.BodyHandler.Kind.INPUT_STREAM -> {
                // la fermeture du flux doit aussi libérer la connexion OkHttp
                val src = body?.byteStream() ?: InputStream.nullInputStream()
                object : InputStream() {
                    override fun read(): Int = src.read()
                    override fun read(b: ByteArray, off: Int, len: Int): Int = src.read(b, off, len)
                    override fun available(): Int = src.available()
                    override fun close() {
                        runCatching { src.close() }
                        runCatching { response.close() }
                    }
                }
            }
            HttpResponse.BodyHandler.Kind.BYTE_ARRAY -> response.use { body?.bytes() ?: ByteArray(0) }
            HttpResponse.BodyHandler.Kind.STRING -> response.use { body?.string() ?: "" }
            HttpResponse.BodyHandler.Kind.DISCARDING -> {
                response.close(); null
            }
        }
        @Suppress("UNCHECKED_CAST")
        return HttpResponse(
            status = response.code,
            uri = response.request.url.toUri(),
            headerMap = response.headers.toMultimap(),
            body = result as T,
        )
    }
}

class HttpRequest private constructor(
    internal val uri: URI,
    private val method: String,
    private val headers: List<Pair<String, String>>,
    private val bodyBytes: ByteArray?,
    internal val timeout: Duration?,
) {
    class Builder internal constructor(private val uri: URI) {
        private var method = "GET"
        private val headers = ArrayList<Pair<String, String>>()
        private var bodyBytes: ByteArray? = null
        private var timeout: Duration? = null

        fun header(name: String, value: String) = apply { headers.add(name to value) }
        fun timeout(d: Duration) = apply { timeout = d }
        fun GET() = apply { method = "GET"; bodyBytes = null }
        fun POST(publisher: BodyPublisher) = apply { method = "POST"; bodyBytes = publisher.bytes }
        fun PUT(publisher: BodyPublisher) = apply { method = "PUT"; bodyBytes = publisher.bytes }
        fun build() = HttpRequest(uri, method, headers.toList(), bodyBytes, timeout)
    }

    class BodyPublisher internal constructor(internal val bytes: ByteArray)

    object BodyPublishers {
        @JvmStatic
        fun ofString(s: String) = BodyPublisher(s.toByteArray(Charsets.UTF_8))

        @JvmStatic
        fun ofByteArray(b: ByteArray) = BodyPublisher(b)

        @JvmStatic
        fun noBody() = BodyPublisher(ByteArray(0))
    }

    companion object {
        @JvmStatic
        fun newBuilder(uri: URI) = Builder(uri)
    }

    internal fun toOkHttp(): Request {
        val b = Request.Builder().url(uri.toString())
        val contentType = headers.firstOrNull { it.first.equals("content-type", true) }?.second
        for ((k, v) in headers) {
            // java.net.http refuse ces en-têtes ; OkHttp les gère lui-même
            if (k.equals("content-type", true)) continue
            b.addHeader(k, v)
        }
        val body = bodyBytes?.toRequestBody((contentType ?: "application/octet-stream").toMediaTypeOrNull())
        when (method) {
            "GET" -> b.get()
            else -> b.method(method, body ?: ByteArray(0).toRequestBody(null))
        }
        return b.build()
    }
}

class HttpResponse<T> internal constructor(
    private val status: Int,
    private val uri: URI,
    private val headerMap: Map<String, List<String>>,
    private val body: T,
) {
    class HttpHeaders internal constructor(private val map: Map<String, List<String>>) {
        fun firstValue(name: String): Optional<String> =
            Optional.ofNullable(map.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull())

        fun map(): Map<String, List<String>> = map
    }

    fun statusCode() = status
    fun uri(): URI = uri
    fun headers() = HttpHeaders(headerMap)
    fun body(): T = body

    class BodyHandler<T> internal constructor(internal val kind: Kind) {
        internal enum class Kind { INPUT_STREAM, BYTE_ARRAY, STRING, DISCARDING }
    }

    object BodyHandlers {
        @JvmStatic
        fun ofInputStream() = BodyHandler<InputStream>(BodyHandler.Kind.INPUT_STREAM)

        @JvmStatic
        fun ofByteArray() = BodyHandler<ByteArray>(BodyHandler.Kind.BYTE_ARRAY)

        @JvmStatic
        fun ofString() = BodyHandler<String>(BodyHandler.Kind.STRING)

        @JvmStatic
        fun discarding() = BodyHandler<Void?>(BodyHandler.Kind.DISCARDING)
    }
}
