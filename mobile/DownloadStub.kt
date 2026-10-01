package com.streamflixreborn.streamflix.mobile

import com.streamflixreborn.streamflix.mobile.http.HttpExchange

/*
 * Le gestionnaire de téléchargements desktop repose sur ffmpeg (processus externe) pour assembler
 * les flux HLS en MP4, indisponible sur Android. L'interface masque donc les téléchargements ;
 * ces routes répondent proprement 501 si quelque chose les appelle quand même.
 */
private fun notSupported(exchange: HttpExchange) =
    sendJson(exchange, 501, """{"error":"downloads are not available on Android"}""")

fun handleDownloadStart(exchange: HttpExchange) = notSupported(exchange)
fun handleDownloadStatus(exchange: HttpExchange) = notSupported(exchange)
fun handleDownloadFile(exchange: HttpExchange) = notSupported(exchange)
fun handleDownloadDelete(exchange: HttpExchange) = notSupported(exchange)
fun handleDownloadCancel(exchange: HttpExchange) = notSupported(exchange)
fun handleDownloadPause(exchange: HttpExchange) = notSupported(exchange)
