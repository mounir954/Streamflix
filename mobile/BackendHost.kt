package com.streamflixreborn.streamflix.mobile

import android.content.Context
import com.streamflixreborn.streamflix.mobile.http.HttpServer
import java.io.FileNotFoundException

/**
 * Démarre (une seule fois par processus) le serveur local qui sert l'API et l'interface.
 * Reste vivant tant que le processus de l'application l'est, y compris si l'activité est recréée.
 */
object BackendHost {
    /** Port préféré, fixe : change l'origine web (cookies/localStorage) s'il varie. */
    const val PREFERRED_PORT = 38417

    @Volatile
    private var server: HttpServer? = null
    private val guard = SessionGuard()

    /** URL de démarrage de la WebView : contient le jeton de session, qui devient un cookie à la première requête. */
    @Volatile
    var startUrl: String = ""
        private set

    @Synchronized
    fun ensureStarted(context: Context): Int {
        server?.let { return it.port }
        val appContext = context.applicationContext
        bundledAssetOpener = { name ->
            try {
                appContext.assets.open(name)
            } catch (e: FileNotFoundException) {
                null
            }
        }
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28)
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).longVersionCode
        else
            @Suppress("DEPRECATION") appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionCode.toLong()
        val webDir = WebBundle.prepare(appContext.filesDir, versionCode) { appContext.assets.open("web.zip") }
        val started = startBackend(PREFERRED_PORT, StaticSite(FileSiteSource(webDir)), guard)
        server = started
        startUrl = "http://127.0.0.1:${started.port}/?k=${guard.token}"
        return started.port
    }
}
