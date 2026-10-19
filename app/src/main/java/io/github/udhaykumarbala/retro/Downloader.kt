package io.github.udhaykumarbala.retro

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/**
 * Downloads catalog games one at a time: fetch from the author's download_url, unzip zip_entry when set,
 * verify rom_sha256, then store as files/roms/<key>.nes. Callbacks run on the main thread.
 */
class Downloader(context: Context, private val listener: Listener) {

    interface Listener {
        fun onDownloadProgress(game: Game)
        fun onDownloadFinished(game: Game)
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "retro-download").apply { priority = Thread.MIN_PRIORITY } }

    fun start(game: Game) {
        if (game.downloading || game.installed || !game.fromCatalog || game.manualOnly) return
        game.error = null
        game.progress = 0f
        listener.onDownloadProgress(game)
        worker.execute { run(game) }
    }

    private fun run(game: Game) {
        val roms = Paths.roms(app)
        val part = File(roms, game.key + ".part")
        val target = Paths.installedRom(app, game)
        var error: String? = null
        try {
            fetchWithBackoff(game, part)
            val rom = extractRom(part, game)
            if (rom.size < 16) throw IOException(app.getString(R.string.error_no_rom_in_zip))
            // Always verify. A mismatch usually means the author uploaded a new build: keep the catalog
            // honest and do not install a file nobody has checked.
            if (game.sha256.isEmpty() || Library.sha256Hex(rom) != game.sha256) {
                throw IOException(app.getString(R.string.error_checksum))
            }
            if (!Paths.writeAtomic(target, rom)) throw IOException("cannot write ROM")
        } catch (e: BusyException) {
            error = app.getString(R.string.error_busy, if (Net.isItch(game.downloadUrl)) "itch.io" else e.host.removePrefix("www."))
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        } finally {
            part.delete()
        }
        main.post {
            game.progress = -1f
            game.error = error
            if (error == null) game.romFile = target
            listener.onDownloadFinished(game)
        }
    }

    /**
     * Downloads into [part], resolving itch.io links first (their signed URL is only valid for about a
     * minute, so it is fetched right after). HTTP 429 is retried after 2, 5 and 15 seconds.
     */
    private fun fetchWithBackoff(game: Game, part: File) {
        var attempt = 0
        while (true) {
            try {
                val url = if (Net.isItch(game.downloadUrl)) Net.resolveItch(game.downloadUrl) else game.downloadUrl
                Net.download(url, part, MAX_DOWNLOAD, onProgress = { p ->
                    main.post {
                        game.progress = p.coerceIn(0f, 0.99f)
                        listener.onDownloadProgress(game)
                    }
                })
                return
            } catch (e: BusyException) {
                if (attempt >= BACKOFF_MS.size) throw e
                Thread.sleep(BACKOFF_MS[attempt++])
            }
        }
    }

    /** The ROM bytes: the zip_entry (or the first .nes) when the download is a zip, else the file itself. */
    private fun extractRom(file: File, game: Game): ByteArray {
        val isZip = file.inputStream().use { s ->
            val head = ByteArray(4)
            s.read(head) == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
                head[2] == 3.toByte() && head[3] == 4.toByte()
        }
        val isRar = file.inputStream().use { s ->
            val head = ByteArray(4)
            s.read(head) == 4 && String(head, Charsets.ISO_8859_1) == "Rar!"
        }
        if (isRar) throw IOException(app.getString(R.string.error_rar))
        if (!isZip) {
            if (file.length() > MAX_ROM) throw IOException("ROM too large")
            return file.readBytes()
        }
        ZipFile(file).use { zip ->
            val wanted = game.zipEntry
            val entry = if (wanted != null) {
                zip.getEntry(wanted) ?: zip.entries().asSequence().firstOrNull {
                    !it.isDirectory && it.name.substringAfterLast('/') == wanted.substringAfterLast('/')
                }
            } else {
                zip.entries().asSequence().firstOrNull { !it.isDirectory && it.name.endsWith(".nes", true) }
            } ?: throw IOException(app.getString(R.string.error_no_rom_in_zip))
            if (entry.size > MAX_ROM) throw IOException("ROM too large")
            return zip.getInputStream(entry).use { it.readBytes() }
        }
    }

    companion object {
        private const val MAX_DOWNLOAD = 64L * 1024 * 1024
        private const val MAX_ROM = 8L * 1024 * 1024
        private val BACKOFF_MS = longArrayOf(2_000, 5_000, 15_000)
    }
}
