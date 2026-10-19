package io.github.udhaykumarbala.retro

import android.graphics.Outline
import android.graphics.drawable.BitmapDrawable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** One horizontal shelf of game tiles. */
class TileAdapter(
    private val host: Host,
    val shelfIndex: Int,
) : RecyclerView.Adapter<TileAdapter.Holder>() {

    interface Host {
        val covers: Covers
        fun isFavourite(game: Game): Boolean
        fun stableId(game: Game): Long
        fun generatedCover(game: Game): CoverDrawable
        fun onTileFocused(shelf: Int, position: Int, card: View)
        fun onTileActivated(game: Game)
        fun onTileFavourite(game: Game)
        fun onTileVertical(shelf: Int, card: View, up: Boolean): Boolean
    }

    var games: List<Game> = emptyList()
        private set

    init {
        setHasStableIds(true)
    }

    fun submit(list: List<Game>) {
        games = list
        notifyDataSetChanged()
    }

    fun refreshState(game: Game) {
        val i = games.indexOf(game)
        if (i >= 0) notifyItemChanged(i, PAYLOAD_STATE)
    }

    override fun getItemCount() = games.size
    override fun getItemId(position: Int) = host.stableId(games[position])

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_tile, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_STATE }) {
            holder.bindState(games[position])
        } else {
            onBindViewHolder(holder, position)
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val game = games[position]
        holder.game = game
        holder.bindCover(game)
        holder.bindState(game)
    }

    inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val card: View = v.findViewById(R.id.card)
        private val cover: ImageView = v.findViewById(R.id.cover)
        private val favourite: View = v.findViewById(R.id.favourite)
        private val badge: TextView = v.findViewById(R.id.badge)
        private val progress: View = v.findViewById(R.id.progress)
        private val progressFill: View = v.findViewById(R.id.progressFill)
        var game: Game? = null

        init {
            val res = v.resources
            val radius = res.getDimension(R.dimen.tile_radius)
            card.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            card.clipToOutline = true
            progressFill.pivotX = 0f

            card.setOnClickListener { game?.let(host::onTileActivated) }
            card.setOnLongClickListener { game?.let(host::onTileFavourite); true }
            card.setOnKeyListener { _, keyCode, event -> onKey(keyCode, event) }
            card.setOnFocusChangeListener { view, focused ->
                val s = if (focused) FOCUS_SCALE else 1f
                view.animate().scaleX(s).scaleY(s)
                    .setDuration(if (focused) 260L else 180L)
                    .setInterpolator(OvershootInterpolator(if (focused) 1.6f else 0f))
                    .start()
                val pos = bindingAdapterPosition
                if (focused && pos != RecyclerView.NO_POSITION) host.onTileFocused(shelfIndex, pos, view)
            }
        }

        private fun onKey(keyCode: Int, event: KeyEvent): Boolean {
            val down = event.action == KeyEvent.ACTION_DOWN
            val first = down && event.repeatCount == 0
            val pos = bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return false
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> {
                    if (first) game?.let(host::onTileActivated)
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_MENU -> {
                    if (first) game?.let(host::onTileFavourite)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!down) return false
                    return host.onTileVertical(shelfIndex, card, keyCode == KeyEvent.KEYCODE_DPAD_UP)
                }
                // Keep focus inside the row at its ends.
                KeyEvent.KEYCODE_DPAD_LEFT -> return down && pos == 0
                KeyEvent.KEYCODE_DPAD_RIGHT -> return down && pos == itemCount - 1
            }
            return false
        }

        fun bindCover(game: Game) {
            val w = card.layoutParams.width
            val h = card.layoutParams.height
            val bmp = host.covers.cached(game, w, h)
            if (bmp != null) {
                cover.setImageDrawable(pixelDrawable(bmp, w))
                return
            }
            cover.setImageDrawable(host.generatedCover(game))
            host.covers.load(game, w, h) { loaded ->
                if (loaded != null && this.game === game) cover.setImageDrawable(pixelDrawable(loaded, w))
            }
        }

        private fun pixelDrawable(bmp: android.graphics.Bitmap, targetW: Int) =
            BitmapDrawable(itemView.resources, bmp).apply {
                // Small pixel art (e.g. a 256x240 title screen) is upscaled crisp, not blurred.
                isFilterBitmap = bmp.width >= targetW
            }

        fun bindState(game: Game) {
            favourite.visibility = if (host.isFavourite(game)) View.VISIBLE else View.GONE
            badge.visibility = if (game.fromCatalog && !game.installed && !game.downloading && !game.manualOnly) View.VISIBLE else View.GONE
            if (game.downloading) {
                progress.visibility = View.VISIBLE
                progressFill.scaleX = game.progress.coerceIn(0.02f, 1f)
            } else {
                progress.visibility = View.GONE
            }
        }
    }

    companion object {
        const val FOCUS_SCALE = 1.1f
        private const val PAYLOAD_STATE = "state"
    }
}
