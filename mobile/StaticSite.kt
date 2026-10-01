package com.streamflixreborn.streamflix.mobile

import com.streamflixreborn.streamflix.mobile.http.HttpExchange
import java.io.InputStream

/** Source des fichiers de l'interface : les assets Android en production, un dossier en test. */
interface SiteSource {
    /** Ouvre un fichier relatif à la racine du site (ex: "en/index.html"), ou null s'il n'existe pas. */
    fun open(path: String): InputStream?
}

/**
 * Sert l'export statique Next.js depuis le backend (même origine que l'API : pas de CORS, cookies partagés).
 *
 * Règles (identiques à celles validées avec le navigateur de test) :
 *  - "/"            -> index.html (qui redirige vers la langue)
 *  - "/x/"          -> x/index.html ; "/x" -> x puis x/index.html
 *  - /<lang>/film/<slug> et /<lang>/serie/<slug> -> page placeholder <lang>/<film|serie>/_/index.html,
 *    le vrai slug est relu dans l'URL par le front (ContentLoader)
 *  - requêtes RSC (.txt) introuvables -> 404 : Next bascule alors sur une navigation classique
 */
class StaticSite(private val source: SiteSource) {

    private val dynamicRoute = Regex("^(en|it|fr|es|de)/(film|serie)/[^/]+/?$")

    fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
            exchange.sendResponseHeaders(405, -1)
            return
        }
        val rel = exchange.requestURI.path.trimStart('/')
        if (rel.split('/').any { it == ".." } || rel.contains('\u0000') || rel.contains('\\')) {
            exchange.sendResponseHeaders(400, -1)
            return
        }

        val candidates = when {
            rel.isEmpty() -> listOf("index.html")
            rel.endsWith("/") -> listOf(rel + "index.html")
            else -> listOf(rel, "$rel/index.html")
        }
        for (candidate in candidates) {
            val bytes = readAll(candidate) ?: continue
            return send(exchange, 200, candidate, bytes)
        }

        dynamicRoute.matchEntire(rel)?.let { m ->
            val (locale, kind) = m.destructured
            readAll("$locale/$kind/_/index.html")?.let { return send(exchange, 200, "index.html", it) }
        }

        // 404 "propre" pour les pages, 404 vide pour les ressources (.txt RSC, .js, images...)
        val isPage = !rel.substringAfterLast('/').contains('.')
        val notFound = if (isPage) readAll("404.html") else null
        if (notFound != null) send(exchange, 404, "404.html", notFound) else exchange.sendResponseHeaders(404, -1)
    }

    private fun readAll(path: String): ByteArray? =
        runCatching { source.open(path)?.use { it.readBytes() } }.getOrNull()

    private fun send(exchange: HttpExchange, status: Int, path: String, bytes: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType(path))
        exchange.responseHeaders.add(
            "Cache-Control",
            // les fichiers de _next/static portent un hash dans leur nom
            if (path.startsWith("_next/static/")) "public, max-age=31536000, immutable" else "no-cache",
        )
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun contentType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json", "map" -> "application/json; charset=utf-8"
        "txt" -> "text/plain; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "ico" -> "image/x-icon"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ttf" -> "font/ttf"
        "webmanifest" -> "application/manifest+json"
        else -> "application/octet-stream"
    }
}
