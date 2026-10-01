package com.streamflixreborn.streamflix.mobile.http

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/*
 * Android n'embarque pas com.sun.net.httpserver (utilisé tel quel par le backend desktop).
 * Ce fichier réimplémente le sous-ensemble d'API dont Backend.kt a besoin (createContext, HttpExchange,
 * Headers, sendResponseHeaders, responseBody...) au-dessus d'un simple ServerSocket, pour que le code
 * du backend reste identique au portage desktop : seuls les imports changent.
 *
 * Choix volontaires :
 *  - écoute sur 127.0.0.1 uniquement (l'original écoute sur 0.0.0.0, inutile et exposé sur un téléphone)
 *  - une requête par connexion ("Connection: close"), ce qui évite toute gestion de keep-alive
 *  - sendResponseHeaders(code, 0) => Transfer-Encoding: chunked ; (code, n>0) => Content-Length ; (code, -1) => pas de corps
 */

fun interface HttpHandler {
    @Throws(IOException::class)
    fun handle(exchange: HttpExchange)
}

/** Équivalent de com.sun.net.httpserver.Headers : clés insensibles à la casse, plusieurs valeurs possibles. */
class Headers {
    private val map = LinkedHashMap<String, Pair<String, MutableList<String>>>()

    fun add(key: String, value: String) {
        map.getOrPut(key.lowercase()) { key to mutableListOf() }.second.add(value)
    }

    fun set(key: String, value: String) {
        map[key.lowercase()] = key to mutableListOf(value)
    }

    fun getFirst(key: String): String? = map[key.lowercase()]?.second?.firstOrNull()

    operator fun get(key: String): List<String>? = map[key.lowercase()]?.second

    fun containsKey(key: String): Boolean = map.containsKey(key.lowercase())

    fun remove(key: String) {
        map.remove(key.lowercase())
    }

    internal fun forEachEntry(action: (name: String, value: String) -> Unit) {
        for ((_, entry) in map) for (v in entry.second) action(entry.first, v)
    }
}

class HttpExchange internal constructor(
    val requestMethod: String,
    val requestURI: URI,
    val requestHeaders: Headers,
    private val rawRequestBody: InputStream,
    private val socket: Socket,
) {
    val responseHeaders = Headers()

    private val socketOut: OutputStream = BufferedOutputStream(socket.getOutputStream(), 16 * 1024)
    private var headersSent = false
    private var closed = false
    private var bodyStream: OutputStream? = null

    val requestBody: InputStream get() = rawRequestBody

    /** À n'utiliser qu'après sendResponseHeaders (comme l'API d'origine). */
    val responseBody: OutputStream
        get() = bodyStream ?: throw IOException("sendResponseHeaders() must be called before responseBody")

    val headersAlreadySent: Boolean get() = headersSent

    @Synchronized
    fun sendResponseHeaders(status: Int, length: Long) {
        if (headersSent) throw IOException("headers already sent")
        headersSent = true

        val noBody = length < 0 || requestMethod == "HEAD" || status == 204 || status == 304
        val chunked = !noBody && length == 0L

        val head = StringBuilder(256)
        head.append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status)).append("\r\n")
        responseHeaders.forEachEntry { name, value ->
            // ces en-têtes sont gérés par le serveur
            val n = name.lowercase()
            if (n != "content-length" && n != "transfer-encoding" && n != "connection") {
                head.append(name).append(": ").append(value.replace('\r', ' ').replace('\n', ' ')).append("\r\n")
            }
        }
        when {
            status == 204 || status == 304 -> Unit
            length < 0 -> head.append("Content-Length: 0\r\n")
            chunked -> head.append("Transfer-Encoding: chunked\r\n")
            else -> head.append("Content-Length: ").append(length).append("\r\n")
        }
        head.append("Connection: close\r\n\r\n")

        socketOut.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        socketOut.flush()

        bodyStream = when {
            // HEAD : on annonce la longueur mais on n'envoie rien
            noBody -> DiscardingStream(this)
            chunked -> ChunkedStream(socketOut, this)
            else -> FixedLengthStream(socketOut, length, this)
        }
        if (length < 0 || status == 204 || status == 304) close()
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        runCatching { (bodyStream as? Finishable)?.finish() }
        runCatching { socketOut.flush() }
        runCatching { socket.shutdownOutput() }
        runCatching { socket.close() }
    }

    private interface Finishable {
        fun finish()
    }

    private class DiscardingStream(private val owner: HttpExchange) : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
        override fun close() = owner.close()
    }

    private class FixedLengthStream(
        private val out: OutputStream,
        private val limit: Long,
        private val owner: HttpExchange,
    ) : OutputStream(), Finishable {
        private var written = 0L

        override fun write(b: Int) {
            if (written + 1 > limit) throw IOException("more bytes than the declared Content-Length")
            out.write(b); written++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (written + len > limit) throw IOException("more bytes than the declared Content-Length")
            out.write(b, off, len); written += len
        }

        override fun flush() = out.flush()
        override fun finish() = out.flush()
        override fun close() = owner.close()
    }

    private class ChunkedStream(
        private val out: OutputStream,
        private val owner: HttpExchange,
    ) : OutputStream(), Finishable {
        private val buffer = ByteArrayOutputStream(8192)
        private var finished = false

        override fun write(b: Int) {
            buffer.write(b)
            if (buffer.size() >= 8192) emit()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            buffer.write(b, off, len)
            if (buffer.size() >= 8192) emit()
        }

        private fun emit() {
            val size = buffer.size()
            if (size == 0) return
            out.write(Integer.toHexString(size).toByteArray(Charsets.ISO_8859_1))
            out.write(CRLF)
            buffer.writeTo(out)
            out.write(CRLF)
            buffer.reset()
        }

        // flush() = pousse vraiment les données au client (nécessaire pour le flux SSE du terminal debug)
        override fun flush() {
            emit()
            out.flush()
        }

        override fun finish() {
            if (finished) return
            finished = true
            emit()
            out.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }

        override fun close() = owner.close()

        private companion object {
            val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
        }
    }

    private fun reasonPhrase(status: Int): String = when (status) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        206 -> "Partial Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        416 -> "Range Not Satisfiable"
        500 -> "Internal Server Error"
        501 -> "Not Implemented"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> "Status $status"
    }
}

class HttpServer private constructor(private val serverSocket: ServerSocket) {

    companion object {
        /** Même signature que com.sun.net.httpserver.HttpServer.create(addr, backlog). */
        @JvmStatic
        fun create(address: InetSocketAddress, backlog: Int): HttpServer {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(address, if (backlog > 0) backlog else 50)
            return HttpServer(socket)
        }

        /** Raccourci : loopback uniquement. */
        fun createLoopback(port: Int): HttpServer =
            create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 128)

        private const val MAX_LINE = 16 * 1024
        private const val MAX_HEADERS = 100
    }

    var executor: Executor = Executors.newCachedThreadPool()

    private val contexts = ArrayList<Pair<String, HttpHandler>>()

    @Volatile
    private var running = false
    private var acceptThread: Thread? = null

    val port: Int get() = serverSocket.localPort

    @Synchronized
    fun createContext(path: String, handler: HttpHandler) {
        contexts.add(path to handler)
        // le préfixe le plus long gagne, comme com.sun
        contexts.sortByDescending { it.first.length }
    }

    fun start() {
        running = true
        acceptThread = Thread({
            while (running) {
                val client = try {
                    serverSocket.accept()
                } catch (e: SocketException) {
                    break
                } catch (e: IOException) {
                    if (!running) break else continue
                }
                try {
                    executor.execute { serve(client) }
                } catch (e: Exception) {
                    runCatching { client.close() }
                }
            }
        }, "streamflix-http-accept").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop(@Suppress("UNUSED_PARAMETER") delaySeconds: Int = 0) {
        running = false
        runCatching { serverSocket.close() }
        (executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }

    private fun serve(client: Socket) {
        var exchange: HttpExchange? = null
        try {
            client.tcpNoDelay = true
            client.soTimeout = 30_000 // lecture de la requête uniquement
            val input = BufferedInputStream(client.getInputStream(), 16 * 1024)

            val requestLine = readLine(input)
            if (requestLine == null) {
                client.close()
                return
            }
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                rawError(client, 400)
                return
            }
            val method = parts[0].uppercase()
            val target = parts[1]

            val headers = Headers()
            var count = 0
            while (true) {
                val line = readLine(input)
                if (line == null) {
                    client.close()
                    return
                }
                if (line.isEmpty()) break
                if (++count > MAX_HEADERS) {
                    rawError(client, 400)
                    return
                }
                val idx = line.indexOf(':')
                if (idx > 0) headers.add(line.substring(0, idx).trim(), line.substring(idx + 1).trim())
            }

            val uri = safeUri(target)
            val contentLength = headers.getFirst("Content-Length")?.toLongOrNull() ?: 0L
            val body: InputStream = if (contentLength > 0) LimitedStream(input, contentLength) else EmptyStream

            // le client peut rester silencieux longtemps pendant qu'on streame la réponse
            client.soTimeout = 0

            val handler = synchronized(this) {
                contexts.firstOrNull { uri.path.startsWith(it.first) }?.second
            }
            val ex = HttpExchange(method, uri, headers, body, client)
            exchange = ex
            if (handler == null) {
                ex.sendResponseHeaders(404, -1)
                return
            }
            handler.handle(ex)
            if (!ex.headersAlreadySent) ex.sendResponseHeaders(500, -1)
        } catch (e: Throwable) {
            // client déconnecté, handler en échec, etc. : on ne peut plus rien pour cette connexion
            runCatching {
                val ex = exchange
                if (ex != null && !ex.headersAlreadySent) ex.sendResponseHeaders(500, -1)
            }
        } finally {
            val ex = exchange
            if (ex != null) ex.close() else runCatching { client.close() }
        }
    }

    private fun rawError(client: Socket, status: Int) {
        runCatching {
            client.getOutputStream()
                .write("HTTP/1.1 $status Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        }
        runCatching { client.close() }
    }

    /** Lit une ligne terminée par CRLF (ou LF). Retourne null si la connexion est fermée avant toute donnée. */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            if (sb.length > MAX_LINE) throw IOException("line too long")
        }
    }

    /** URI.create échoue sur les caractères non encodés (espaces, [, |, accents) : on les encode nous-mêmes. */
    private fun safeUri(raw: String): URI {
        val target = if (raw.startsWith("http://") || raw.startsWith("https://")) {
            "/" + raw.substringAfter("://").substringAfter('/', "")
        } else raw
        return try {
            URI(target)
        } catch (e: Exception) {
            val sb = StringBuilder()
            for (byte in target.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt() and 0xFF
                val ch = c.toChar()
                if (c < 0x80 && (ch.isLetterOrDigit() || ch in "-._~:/?#@!$&'()*+,;=%")) sb.append(ch)
                else sb.append('%').append(String.format("%02X", c))
            }
            URI(sb.toString())
        }
    }

    private object EmptyStream : InputStream() {
        override fun read(): Int = -1
    }

    private class LimitedStream(private val src: InputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val v = src.read()
            if (v >= 0) remaining--
            return v
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = src.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }
    }
}
