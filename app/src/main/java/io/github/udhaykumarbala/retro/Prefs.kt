package io.github.udhaykumarbala.retro

import android.content.Context

/**
 * Favourites and recently played games. Only the library process reads and writes these; the player
 * process only writes files, so there is no cross process SharedPreferences caching problem.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    fun favourites(): Set<String> = sp.getStringSet(KEY_FAVS, emptySet())!!.toSet()

    fun toggleFavourite(id: String): Boolean {
        val favs = favourites().toMutableSet()
        val now = !favs.remove(id)
        if (now) favs.add(id)
        sp.edit().putStringSet(KEY_FAVS, favs).apply()
        return now
    }

    /** Most recent first. */
    fun recents(): List<String> =
        sp.getString(KEY_RECENTS, "")!!.split('\n').filter { it.isNotEmpty() }

    fun markPlayed(id: String) {
        val list = recents().toMutableList()
        list.remove(id)
        list.add(0, id)
        sp.edit().putString(KEY_RECENTS, list.take(MAX_RECENTS).joinToString("\n")).apply()
    }

    companion object {
        private const val KEY_FAVS = "favourites"
        private const val KEY_RECENTS = "recents"
        private const val MAX_RECENTS = 20
    }
}
