package com.streamflixreborn.streamflix.mobile

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * L'interface (export Next.js) est livrée dans l'APK sous forme d'un seul fichier web.zip, décompressé au premier
 * lancement dans le stockage privé de l'app. Pourquoi un zip plutôt que des assets bruts : Android ignore par défaut
 * les dossiers dont le nom commence par "_" dans les assets, et Next.js génère justement "_next/".
 */
object WebBundle {

    /** Décompresse [zip] dans [dest] (créé/vidé au besoin). Protégé contre les entrées "../" (zip-slip). */
    fun unzip(zip: InputStream, dest: File) {
        if (dest.exists()) dest.deleteRecursively()
        dest.mkdirs()
        val root = dest.canonicalPath + File.separator
        ZipInputStream(zip.buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val target = File(dest, entry.name)
                if (!target.canonicalPath.startsWith(root)) throw SecurityException("entrée de zip invalide: ${entry.name}")
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
            }
        }
    }

    /**
     * Retourne le dossier de l'interface prête à servir. Une nouvelle version de l'app (versionCode) a son propre
     * dossier ; l'ancien est supprimé. Le fichier ".complete" évite d'utiliser une extraction interrompue.
     */
    fun prepare(filesDir: File, versionCode: Long, openZip: () -> InputStream): File {
        val target = File(filesDir, "web-$versionCode")
        val marker = File(target, ".complete")
        if (!marker.exists()) {
            unzip(openZip(), target)
            marker.writeText("ok")
            filesDir.listFiles { f -> f.isDirectory && f.name.startsWith("web-") && f.name != target.name }
                ?.forEach { it.deleteRecursively() }
        }
        return target
    }
}

/** Sert les fichiers d'un dossier, en refusant toute sortie du dossier. */
class FileSiteSource(private val root: File) : SiteSource {
    private val rootPath = root.canonicalPath + File.separator

    override fun open(path: String): InputStream? {
        val file = File(root, path)
        return if (file.isFile && file.canonicalPath.startsWith(rootPath)) FileInputStream(file) else null
    }
}
