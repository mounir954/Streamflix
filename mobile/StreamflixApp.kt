package com.streamflixreborn.streamflix.mobile

import android.app.Application
import com.streamflixreborn.streamflix.utils.AndroidContextHolder
import java.io.File

class StreamflixApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidContextHolder.context = applicationContext

        // Le code partagé range ses fichiers (préférences, cookies) sous <user.home>/.streamflix/.
        // On redirige user.home vers le stockage privé de l'app, AVANT le premier accès à ces objets.
        val home = File(filesDir, "home").apply { mkdirs() }
        System.setProperty("user.home", home.absolutePath)
    }
}
