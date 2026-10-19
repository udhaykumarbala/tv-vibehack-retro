package io.github.udhaykumarbala.retro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.util.concurrent.Executors

/**
 * Cover art: downloaded once into cache/covers, decoded at the size it is shown (RGB_565, subsampled)
 * and kept in a small memory LRU. Callbacks run on the main thread.
 */
class Covers(context: Context) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "retro-covers").apply { priority = Thread.MIN_PRIORITY } }
    private val pending = HashMap<String, MutableList<(Bitmap?) -> Unit>>()
    private val failed = HashSet<String>()
    private val memory = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(game: Game, w: Int, h: Int): Bitmap? = memory.get(memKey(game, w))

    /** Calls back with the bitmap, or null when there is no cover; never calls back for a game without a URL. */
    fun load(game: Game, w: Int, h: Int, callback: (Bitmap?) -> Unit) {
        if (game.coverUrl.isEmpty()) return
        val mk = memKey(game, w)
        memory.get(mk)?.let { callback(it); return }
        if (mk in failed) return
        pending[mk]?.let { it.add(callback); return }
        pending[mk] = mutableListOf(callback)
        pool.execute {
            val bmp = try { fetchAndDecode(game, w, h) } catch (e: Throwable) { null }
            if (bmp != null && game.ambient == 0) game.ambient = vividColor(bmp)
            main.post {
                if (bmp != null) memory.put(mk, bmp) else failed.add(mk)
                pending.remove(mk)?.forEach { it(bmp) }
            }
        }
    }

    private fun memKey(game: Game, w: Int) = game.key + "@" + w

    private fun fetchAndDecode(game: Game, w: Int, h: Int): Bitmap? {
        val file = File(Paths.covers(app), game.key + ".img")
        if (!file.isFile || file.length() == 0L) {
            val part = File(file.parentFile, file.name + ".part")
            try {
                Net.download(game.coverUrl, part, 4L * 1024 * 1024)
                if (!part.renameTo(file)) return null
            } finally {
                part.delete()
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) {
            file.delete()
            return null
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    companion object {
        /** A vivid colour from the art (ported from the launcher's banner sampling). */
        fun vividColor(src: Bitmap): Int {
            val n = 12
            val bmp = Bitmap.createScaledBitmap(src, n, n, true)
            val hsv = FloatArray(3)
            var best = 0xFF444444.toInt()
            var bestScore = -1f
            var r = 0L; var g = 0L; var b = 0L
            for (y in 0 until n) for (x in 0 until n) {
                val p = bmp.getPixel(x, y)
                r += Color.red(p); g += Color.green(p); b += Color.blue(p)
                Color.colorToHSV(p, hsv)
                val score = hsv[1] * minOf(1f, hsv[2] * 1.4f)
                if (score > bestScore) { bestScore = score; best = p }
            }
            if (bmp !== src) bmp.recycle()
            if (bestScore < 0.25f) {
                val avg = Color.rgb((r / (n * n)).toInt(), (g / (n * n)).toInt(), (b / (n * n)).toInt())
                Color.colorToHSV(avg, hsv)
                hsv[2] = maxOf(hsv[2], 0.55f)
                return Color.HSVToColor(hsv)
            }
            Color.colorToHSV(best, hsv)
            hsv[1] = minOf(1f, hsv[1] * 1.1f)
            hsv[2] = maxOf(0.5f, hsv[2])
            return Color.HSVToColor(hsv)
        }
    }
}
