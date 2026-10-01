package com.streamflixreborn.streamflix.mobile

import com.streamflixreborn.streamflix.mobile.http.HttpExchange
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Le serveur n'écoute que sur 127.0.0.1, mais tout ce qui tourne sur le téléphone (une autre appli, une page web
 * ouverte dans Chrome) peut quand même s'y connecter. Seule la WebView de l'application doit pouvoir l'utiliser :
 * un jeton aléatoire, généré à chaque lancement, est passé dans la première URL (?k=...) puis mémorisé par la
 * WebView dans un cookie SameSite=Strict. Toute requête sans ce jeton reçoit un 403.
 */
class SessionGuard {
    val token: String = ByteArray(24).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    private val cookieName = "sf_session"

    fun check(exchange: HttpExchange): Boolean {
        val cookies = exchange.requestHeaders.getFirst("Cookie").orEmpty()
        val fromCookie = cookies.split(';').map { it.trim() }
            .firstOrNull { it.startsWith("$cookieName=") }?.substringAfter('=')
        if (fromCookie != null && same(fromCookie)) return true

        val fromQuery = (exchange.requestURI.rawQuery ?: "").split('&')
            .firstOrNull { it.startsWith("k=") }?.substringAfter('=')?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        if (fromQuery != null && same(fromQuery)) {
            exchange.responseHeaders.add("Set-Cookie", "$cookieName=$token; Path=/; HttpOnly; SameSite=Strict")
            return true
        }

        exchange.sendResponseHeaders(403, -1)
        return false
    }

    // comparaison en temps constant
    private fun same(candidate: String): Boolean =
        MessageDigest.isEqual(candidate.toByteArray(), token.toByteArray())
}
