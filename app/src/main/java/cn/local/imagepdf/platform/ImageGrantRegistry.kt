package cn.local.imagepdf.platform

import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Only a new, individual grant acquired by ImageRepository is owned here. Legacy grants and
 * grants already held by another tool are deliberately left unregistered and never released.
 */
internal class ImageGrantRegistry(private val preferences: SharedPreferences) {
    fun recordNew(uri: Uri, now: Long = System.currentTimeMillis()): Unit = synchronized(preferences) {
        if (eligible(uri)) preferences.edit().putLong(uri.toString(), now).apply()
    }

    fun touchKnown(uri: Uri, now: Long = System.currentTimeMillis()): Unit = synchronized(preferences) {
        if (eligible(uri) && preferences.contains(uri.toString())) {
            preferences.edit().putLong(uri.toString(), now).apply()
        }
    }

    fun releaseUnused(keep: Set<String>, cutoff: Long, release: (Uri) -> Unit): Unit = synchronized(preferences) {
        val changes = preferences.edit()
        preferences.all.forEach { (value, acquired) ->
            val uri = Uri.parse(value)
            if (!eligible(uri)) {
                changes.remove(value) // Corrupt/old bookkeeping is discarded without revocation.
            } else if (value !in keep && acquired is Long && acquired < cutoff) {
                try { release(uri); changes.remove(value) }
                catch (_: SecurityException) { changes.remove(value) } // Already revoked by provider.
                catch (_: IllegalArgumentException) { changes.remove(value) }
            }
        }
        changes.apply()
    }

    private fun eligible(uri: Uri) = uri.scheme == "content" && !DocumentsContract.isTreeUri(uri)

    companion object { const val PREFERENCES_NAME = "image-owned-grants-v1" }
}

/** A directory-scoped cleaner; it cannot walk sibling tools' source caches. */
internal class ImageSourceFilesCleaner(private val sources: File) {
    fun releaseUnused(keepPaths: Set<String>, cutoff: Long) {
        val ownRoot = sources.canonicalFile
        sources.listFiles().orEmpty().forEach { candidate ->
            if (candidate.isFile && candidate.canonicalFile.parentFile == ownRoot &&
                candidate.absolutePath !in keepPaths && candidate.lastModified() < cutoff) candidate.delete()
        }
    }
}
