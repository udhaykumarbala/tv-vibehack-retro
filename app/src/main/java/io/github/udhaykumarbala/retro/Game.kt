package io.github.udhaykumarbala.retro

import android.graphics.Color
import java.io.File

/**
 * One NES game: either a catalog entry (free homebrew fetched from its author) or a user file from
 * /sdcard/Roms/NES. Mutable state (install, download progress) is only touched on the main thread.
 */
class Game(
    val id: String,
    val title: String,
    val author: String,
    val year: String,
    val genre: String,
    val players: String,
    val description: String,
    val license: String,
    val sourcePage: String,
    val downloadUrl: String,
    val zipEntry: String?,
    val sha256: String,
    val crc32: Long,
    val mapper: String,
    val coverUrl: String,
    val fromCatalog: Boolean,
) {
    /** The playable ROM: the verified download, a matching user file, or the user file itself. */
    @Volatile var romFile: File? = null

    /** Download progress 0..1 while downloading, -1 otherwise. */
    var progress = -1f
    var error: String? = null

    /** Cached ambient colour (0 until computed). */
    var ambient = 0

    /** Filesystem safe key for saves, states, covers and the installed ROM. */
    val key: String = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    val installed: Boolean get() = romFile != null

    /**
     * Archives the app cannot unpack (RAR): the game is listed, but the user downloads it from the author's
     * page and copies the .nes to /sdcard/Roms/NES, where the checksum match picks it up.
     */
    val manualOnly: Boolean = fromCatalog &&
        downloadUrl.substringBefore('?').endsWith(".rar", ignoreCase = true)
    val downloading: Boolean get() = progress >= 0f

    /** Stable cover colour derived from the CRC (or the id when there is none). */
    val baseColor: Int by lazy {
        val seed = if (crc32 != 0L) crc32 else id.hashCode().toLong() and 0xFFFFFFFFL
        val hue = (seed % 360L).toFloat()
        val sat = 0.45f + ((seed shr 9) % 30L) / 100f
        Color.HSVToColor(floatArrayOf(hue, sat, 0.62f))
    }
}
