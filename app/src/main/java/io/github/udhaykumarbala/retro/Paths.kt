package io.github.udhaykumarbala.retro

import android.content.Context
import android.os.Environment
import java.io.File

/** Where everything lives. All app data stays in the app-private files dir. */
object Paths {
    fun roms(c: Context) = dir(File(c.filesDir, "roms"))
    fun saves(c: Context) = dir(File(c.filesDir, "saves"))
    fun states(c: Context) = dir(File(c.filesDir, "states"))
    fun system(c: Context) = dir(File(c.filesDir, "system"))
    fun covers(c: Context) = dir(File(c.cacheDir, "covers"))

    fun installedRom(c: Context, g: Game) = File(roms(c), g.key + ".nes")
    fun sram(c: Context, key: String) = File(saves(c), "$key.srm")
    fun autoState(c: Context, key: String) = File(states(c), "$key.auto.state")
    fun slotState(c: Context, key: String) = File(states(c), "$key.slot1.state")

    /** User ROM folder: /sdcard/Roms/NES. */
    @Suppress("DEPRECATION")
    fun userRoms(): File = File(Environment.getExternalStorageDirectory(), "Roms/NES")

    private fun dir(f: File): File {
        if (!f.isDirectory) f.mkdirs()
        return f
    }

    /** Write through a temp file and rename, so a crash never leaves a half written save. */
    fun writeAtomic(target: File, bytes: ByteArray): Boolean {
        val tmp = File(target.parentFile, target.name + ".tmp")
        return try {
            tmp.outputStream().use { it.write(bytes) }
            tmp.renameTo(target) || run { target.delete(); tmp.renameTo(target) }
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }
}
