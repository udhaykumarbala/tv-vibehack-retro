package io.github.udhaykumarbala.retro

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32

/** Loads the bundled catalog and scans the user ROM folder. Runs on a background thread. */
object Library {
    private const val TAG = "RetroLibrary"
    private const val MAX_ROM_BYTES = 8 * 1024 * 1024

    fun load(context: Context, canReadSdcard: Boolean): List<Game> {
        val catalog = readCatalog(context)
        for (g in catalog) {
            val f = Paths.installedRom(context, g)
            if (f.isFile && f.length() > 0) g.romFile = f
        }
        val bySha = catalog.filter { it.sha256.isNotEmpty() }.associateBy { it.sha256 }

        val local = ArrayList<Game>()
        if (canReadSdcard) {
            val seen = HashSet<String>()
            for (file in scanUserRoms(context)) {
                val (sha, crc) = file.second
                val match = bySha[sha]
                if (match != null) {
                    // The user already has this catalog game: no download needed, no duplicate tile.
                    if (match.romFile == null) match.romFile = file.first
                    continue
                }
                val id = "local:%08x".format(crc)
                if (!seen.add(id)) continue
                local.add(localGame(file.first, id, crc).also { it.romFile = file.first })
            }
        }
        return (catalog + local).sortedBy { it.title.lowercase() }
    }

    // ---- catalog ---------------------------------------------------------------------------------

    private fun readCatalog(context: Context): List<Game> {
        val text = try {
            context.assets.open("catalog/nes.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            return emptyList()   // built without a catalog
        }
        return try {
            val root = JSONTokener(text).nextValue()
            val arr: JSONArray = when (root) {
                is JSONArray -> root
                is JSONObject -> root.optJSONArray("games") ?: JSONArray()
                else -> JSONArray()
            }
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::parseEntry) }
        } catch (e: Exception) {
            Log.w(TAG, "bad catalog", e)
            emptyList()
        }
    }

    private fun parseEntry(o: JSONObject): Game? {
        val id = o.str("id")
        val title = o.str("title")
        val url = o.str("download_url")
        if (id.isEmpty() || title.isEmpty() || url.isEmpty()) return null
        return Game(
            id = id,
            title = title,
            author = o.str("author"),
            year = o.str("year"),
            genre = o.str("genre"),
            players = o.str("players"),
            description = o.str("description"),
            license = o.str("license"),
            sourcePage = o.str("source_page"),
            downloadUrl = url,
            zipEntry = o.str("zip_entry").ifEmpty { null },
            sha256 = o.str("rom_sha256").lowercase(),
            crc32 = parseCrc(o.opt("rom_crc32")),
            mapper = o.str("mapper"),
            coverUrl = o.str("cover_url"),
            fromCatalog = true,
        )
    }

    private fun JSONObject.str(name: String): String {
        val v = opt(name)
        return if (v == null || v == JSONObject.NULL) "" else v.toString().trim()
    }

    private fun parseCrc(v: Any?): Long = when (v) {
        is Number -> v.toLong() and 0xFFFFFFFFL
        is String -> v.trim().removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: 0L
        else -> 0L
    }

    // ---- user files ------------------------------------------------------------------------------

    /** (file, (sha256, crc32)) for every readable .nes file; hashes are cached by path, size and mtime. */
    private fun scanUserRoms(context: Context): List<Pair<File, Pair<String, Long>>> {
        val dir = Paths.userRoms()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".nes", ignoreCase = true) }
            ?: return emptyList()
        val cache = context.getSharedPreferences("scan", Context.MODE_PRIVATE)
        val edit = cache.edit()
        val out = ArrayList<Pair<File, Pair<String, Long>>>()
        val keep = HashSet<String>()
        for (f in files) {
            if (f.length() <= 16 || f.length() > MAX_ROM_BYTES) continue
            val stamp = "${f.length()}:${f.lastModified()}"
            keep.add(f.absolutePath)
            val cached = cache.getString(f.absolutePath, null)?.split('|')
            if (cached != null && cached.size == 3 && cached[0] == stamp) {
                out.add(f to (cached[1] to cached[2].toLong()))
                continue
            }
            try {
                val bytes = f.readBytes()
                val sha = sha256Hex(bytes)
                val crc = CRC32().apply { update(bytes) }.value
                edit.putString(f.absolutePath, "$stamp|$sha|$crc")
                out.add(f to (sha to crc))
            } catch (e: Exception) {
                Log.w(TAG, "cannot read $f", e)
            }
        }
        for (k in cache.all.keys) if (k !in keep) edit.remove(k)
        edit.apply()
        return out
    }

    private fun localGame(file: File, id: String, crc: Long): Game {
        val title = file.nameWithoutExtension
            .replace(Regex("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]"), "")   // drop "(USA)", "[!]" style tags
            .replace('_', ' ')
            .trim()
            .ifEmpty { file.nameWithoutExtension }
        return Game(
            id = id, title = title, author = "", year = "", genre = "", players = "",
            description = "", license = "", sourcePage = "", downloadUrl = "", zipEntry = null,
            sha256 = "", crc32 = crc, mapper = "", coverUrl = "", fromCatalog = false,
        )
    }

    fun sha256Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(64)
        for (b in d) sb.append("%02x".format(b))
        return sb.toString()
    }
}
