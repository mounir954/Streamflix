package com.streamflixreborn.streamflix.utils

import android.content.Context

/** Contexte applicatif, renseigné par StreamflixApp.onCreate() ; nécessaire à la WebView du résolveur. */
object AndroidContextHolder {
    lateinit var context: Context
}
