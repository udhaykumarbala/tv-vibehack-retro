package io.github.udhaykumarbala.retro

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/**
 * tvOS style game browser: a hero area for the focused game, horizontal shelves below (Continue, NES,
 * Favourites) and one glass highlight that springs between tiles, over an ambient glow in the game's colour.
 */
class LibraryActivity : ComponentActivity(), TileAdapter.Host, Downloader.Listener {

    private enum class Kind(val label: Int) {
        CONTINUE(R.string.shelf_continue), NES(R.string.shelf_nes), FAVOURITES(R.string.shelf_favourites)
    }

    private class Shelf(val kind: Kind, val view: View, val row: RecyclerView, val adapter: TileAdapter)

    private lateinit var root: FrameLayout
    private lateinit var ambient: View
    private lateinit var ambientGlow: GradientDrawable
    private lateinit var heroArt: ImageView
    private lateinit var heroText: View
    private lateinit var heroKicker: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroDescription: TextView
    private lateinit var hintPrimary: TextView
    private lateinit var hintFavourite: TextView
    private lateinit var heroStatus: TextView
    private lateinit var shelfColumn: LinearLayout
    private lateinit var empty: View
    private lateinit var glass: GlassView
    private lateinit var springX: SpringAnimation
    private lateinit var springY: SpringAnimation

    override lateinit var covers: Covers
    private lateinit var prefs: Prefs
    private lateinit var downloader: Downloader
    private lateinit var outfit: Typeface

    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "retro-library") }
    private val ids = HashMap<String, Long>()
    private var density = 1f

    private var games: List<Game> = emptyList()
    private var favourites: Set<String> = emptySet()
    private val shelves = ArrayList<Shelf>()
    private var loaded = false

    private var focusedShelf = -1
    private var focusedGame: Game? = null
    private var heroGame: Game? = null
    private var glassShown = false
    private var glassTargetX = 0f
    private var glassTargetY = 0f
    private var columnTargetY = 0f
    private var ambientColor = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)
        density = resources.displayMetrics.density
        outfit = resources.getFont(R.font.outfit)
        covers = Covers(this)
        prefs = Prefs(this)
        downloader = Downloader(this, this)

        root = findViewById(R.id.root)
        ambient = findViewById(R.id.ambient)
        heroArt = findViewById(R.id.heroArt)
        heroText = findViewById(R.id.heroText)
        heroKicker = findViewById(R.id.heroKicker)
        heroTitle = findViewById(R.id.heroTitle)
        heroMeta = findViewById(R.id.heroMeta)
        heroDescription = findViewById(R.id.heroDescription)
        hintPrimary = findViewById(R.id.hintPrimary)
        hintFavourite = findViewById(R.id.hintFavourite)
        heroStatus = findViewById(R.id.heroStatus)
        shelfColumn = findViewById(R.id.shelves)
        empty = findViewById(R.id.empty)
        glass = findViewById(R.id.glass)

        heroArt.clipToOutline = true
        ambientGlow = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(0.74f, 0.3f)
        }
        ambient.background = ambientGlow
        ambient.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ -> ambientGlow.gradientRadius = (r - l) * 0.6f }

        springX = SpringAnimation(glass, DynamicAnimation.TRANSLATION_X)
        springY = SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y)
        for (s in arrayOf(springX, springY)) {
            s.spring = SpringForce().setStiffness(260f).setDampingRatio(0.58f)
        }

        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_STORAGE)
        }
    }

    override fun onStart() {
        super.onStart()
        reload()
    }

    override fun onResume() {
        super.onResume()
        // Back from a game: Continue order and autosave hints may have changed.
        if (loaded) refreshShelves()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) reload()
    }

    // ---- data ------------------------------------------------------------------------------------

    private fun reload() {
        val canRead = checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        val app = applicationContext
        loader.execute {
            val fresh = Library.load(app, canRead)
            main.post { applyGames(fresh) }
        }
    }

    /** Keeps existing Game instances (they carry download progress) and adopts new ones. */
    private fun applyGames(fresh: List<Game>) {
        val old = games.associateBy { it.id }
        games = fresh.map { g ->
            val prev = old[g.id]
            if (prev == null) g else prev.also { if (!it.downloading) it.romFile = g.romFile ?: it.romFile.takeIf { f -> f?.isFile == true } }
        }
        loaded = true
        refreshShelves()
    }

    private fun refreshShelves() {
        favourites = prefs.favourites()
        val byId = games.associateBy { it.id }
        val lists = linkedMapOf(
            Kind.CONTINUE to prefs.recents().mapNotNull { byId[it] }.filter { it.installed },
            Kind.NES to games,
            Kind.FAVOURITES to games.filter { it.id in favourites },
        ).filterValues { it.isNotEmpty() }

        empty.visibility = if (games.isEmpty()) View.VISIBLE else View.GONE
        if (games.isEmpty()) hideHero()

        if (lists.keys.toList() != shelves.map { it.kind }) {
            rebuildShelves(lists)
        } else {
            var moved = false
            for (shelf in shelves) {
                val list = lists.getValue(shelf.kind)
                if (list.map { it.id } != shelf.adapter.games.map { it.id }) {
                    shelf.adapter.submit(list)
                    moved = true
                } else {
                    shelf.adapter.notifyItemRangeChanged(0, list.size, "state")
                }
            }
            if (moved) onNextLayout { resyncFocus() }
        }
        focusedGame?.let { showHero(it, force = true) }
    }

    private fun rebuildShelves(lists: Map<Kind, List<Game>>) {
        val keepKind = shelves.getOrNull(focusedShelf)?.kind
        val keepGame = focusedGame
        shelfColumn.removeAllViews()
        shelves.clear()
        val inflater = LayoutInflater.from(this)
        for ((kind, list) in lists) {
            val index = shelves.size
            val v = inflater.inflate(R.layout.item_shelf, shelfColumn, false)
            v.findViewById<TextView>(R.id.shelfLabel).setText(kind.label)
            val row = v.findViewById<RecyclerView>(R.id.shelfRow)
            row.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            row.itemAnimator = null
            row.setItemViewCacheSize(4)
            val adapter = TileAdapter(this, index)
            row.adapter = adapter
            adapter.submit(list)
            row.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    // Keep the glass glued to its tile while the row scrolls.
                    if (index != focusedShelf || !glassShown || dx == 0) return
                    glassTargetX -= dx
                    if (springX.isRunning) springX.animateToFinalPosition(glassTargetX) else glass.translationX = glassTargetX
                }
            })
            shelfColumn.addView(v)
            shelves.add(Shelf(kind, v, row, adapter))
        }
        // The column is taller than its clipping viewport; give it its full height explicitly.
        shelfColumn.layoutParams.height = shelves.size * resources.getDimensionPixelSize(R.dimen.shelf_height)
        shelfColumn.requestLayout()
        if (shelves.isEmpty()) {
            focusedShelf = -1
            focusedGame = null
            hideGlass()
            return
        }
        var target = shelves.indexOfFirst { it.kind == keepKind }.takeIf { it >= 0 }
            ?: shelves.indexOfFirst { it.kind == Kind.NES }.coerceAtLeast(0)
        if (keepKind == null) target = 0   // first start: the top shelf
        val pos = keepGame?.let { shelves[target].adapter.games.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        focusedShelf = -1
        onNextLayout { focusTile(target, pos) }
    }

    /** Runs [action] once, right after the next layout pass of the window. */
    private fun onNextLayout(action: () -> Unit) {
        val vto = root.viewTreeObserver
        vto.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                action()
            }
        })
        root.requestLayout()
    }

    private fun focusTile(shelfIndex: Int, position: Int) {
        val shelf = shelves.getOrNull(shelfIndex) ?: return
        val pos = position.coerceIn(0, shelf.adapter.itemCount - 1)
        val holder = shelf.row.findViewHolderForAdapterPosition(pos) as? TileAdapter.Holder
        if (holder != null) {
            holder.card.requestFocus()
            return
        }
        shelf.row.scrollToPosition(pos)
        onNextLayout {
            (shelf.row.findViewHolderForAdapterPosition(pos) as? TileAdapter.Holder)?.card?.requestFocus()
        }
    }

    /** After a data change the focused tile may have moved without a focus change; realign the glass. */
    private fun resyncFocus() {
        val focused = currentFocus
        val shelf = shelves.getOrNull(focusedShelf)
        if (shelf == null) {
            if (shelves.isNotEmpty()) focusTile(0, 0)
            return
        }
        if (focused == null || !isDescendant(shelf.row, focused)) {
            focusTile(focusedShelf, shelf.adapter.games.indexOf(focusedGame).coerceAtLeast(0))
            return
        }
        val holder = shelf.row.findContainingViewHolder(focused) ?: return
        val pos = holder.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION) onTileFocused(focusedShelf, pos, focused)
    }

    private fun isDescendant(parent: ViewGroup, v: View): Boolean {
        var p = v.parent
        while (p is View) {
            if (p === parent) return true
            p = p.parent
        }
        return false
    }

    // ---- TileAdapter.Host ------------------------------------------------------------------------

    override fun isFavourite(game: Game) = game.id in favourites

    override fun stableId(game: Game): Long = ids.getOrPut(game.id) { ids.size.toLong() + 1 }

    override fun generatedCover(game: Game) = CoverDrawable(game.title, game.baseColor, outfit, density)

    override fun onTileFocused(shelf: Int, position: Int, card: View) {
        val s = shelves.getOrNull(shelf) ?: return
        val game = s.adapter.games.getOrNull(position) ?: return
        focusedShelf = shelf
        focusedGame = game

        // Slide the shelf column so the focused shelf sits at the top of the viewport.
        val targetY = -s.view.top.toFloat()
        if (targetY != columnTargetY || shelfColumn.translationY != targetY) {
            columnTargetY = targetY
            shelfColumn.animate().translationY(targetY).setDuration(280)
                .setInterpolator(DecelerateInterpolator(1.6f)).start()
        }

        // Tile rect in root coordinates from layout positions (ignores the scale and column animations),
        // then shifted by where the column is heading.
        val r = Rect(0, 0, card.width, card.height)
        root.offsetDescendantRectToMyCoords(card, r)
        r.offset(0, targetY.toInt())
        val pad = GlassView.INSET_DP * density
        val scale = TileAdapter.FOCUS_SCALE
        val w = card.width * scale + 2 * pad
        val h = card.height * scale + 2 * pad
        val x = r.exactCenterX() - w / 2f
        val y = r.exactCenterY() - h / 2f
        val lp = glass.layoutParams
        if (lp.width != w.toInt() || lp.height != h.toInt()) {
            lp.width = w.toInt()
            lp.height = h.toInt()
            glass.layoutParams = lp
            glass.setRadius(resources.getDimension(R.dimen.tile_radius) * scale + pad)
        }
        glassTargetX = x
        glassTargetY = y
        if (!glassShown) {
            springX.cancel()
            springY.cancel()
            glass.translationX = x
            glass.translationY = y
            glass.animate().alpha(1f).setDuration(180).start()
            glassShown = true
        } else {
            springX.animateToFinalPosition(x)
            springY.animateToFinalPosition(y)
        }
        showHero(game, force = false)
    }

    override fun onTileVertical(shelf: Int, card: View, up: Boolean): Boolean {
        val target = shelf + if (up) -1 else 1
        val dest = shelves.getOrNull(target) ?: return true   // nothing above or below: stay put
        // tvOS style: land on the tile nearest horizontally.
        val from = Rect(0, 0, card.width, card.height)
        root.offsetDescendantRectToMyCoords(card, from)
        var best = -1
        var bestDist = Int.MAX_VALUE
        val row = dest.row
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            val holder = row.getChildViewHolder(child) as? TileAdapter.Holder ?: continue
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) continue
            val r = Rect(0, 0, holder.card.width, holder.card.height)
            root.offsetDescendantRectToMyCoords(holder.card, r)
            // Prefer tiles that are fully on screen.
            val offscreen = if (r.left < 0 || r.right > root.width) root.width else 0
            val d = Math.abs(r.centerX() - from.centerX()) + offscreen
            if (d < bestDist) {
                bestDist = d
                best = pos
            }
        }
        focusTile(target, if (best >= 0) best else 0)
        return true
    }

    override fun onTileActivated(game: Game) {
        when {
            game.installed -> {
                val rom = game.romFile
                if (rom == null || !rom.isFile) {
                    game.romFile = null
                    game.error = getString(R.string.error_missing)
                    onDownloadFinished(game)
                    return
                }
                // Catalog games are checked against the catalog checksum before every launch.
                if (game.fromCatalog && game.sha256.isNotEmpty() &&
                    runCatching { Library.sha256Hex(rom.readBytes()) }.getOrNull() != game.sha256) {
                    game.error = getString(R.string.error_checksum)
                    updateHeroState(game)
                    return
                }
                game.error = null
                prefs.markPlayed(game.id)
                startActivity(PlayerActivity.intent(this, game))
            }
            game.downloading -> Unit
            game.manualOnly -> updateHeroState(game)
            game.fromCatalog -> downloader.start(game)
        }
    }

    override fun onTileFavourite(game: Game) {
        prefs.toggleFavourite(game.id)
        refreshShelves()
    }

    // ---- Downloader.Listener ---------------------------------------------------------------------

    override fun onDownloadProgress(game: Game) {
        for (s in shelves) s.adapter.refreshState(game)
        if (game === focusedGame) updateHeroState(game)
    }

    override fun onDownloadFinished(game: Game) {
        for (s in shelves) s.adapter.refreshState(game)
        if (game === focusedGame) updateHeroState(game)
    }

    // ---- hero ------------------------------------------------------------------------------------

    private fun showHero(game: Game, force: Boolean) {
        val changed = game !== heroGame
        if (!changed && !force) return
        heroGame = game
        if (heroText.alpha == 0f || !changed) {
            bindHero(game)
            heroText.animate().alpha(1f).setDuration(220).start()
            heroArt.animate().alpha(1f).setDuration(220).start()
        } else {
            heroText.animate().alpha(0f).translationX(-10 * density).setDuration(110).withEndAction {
                if (heroGame !== game) return@withEndAction
                bindHero(game)
                heroText.translationX = 10 * density
                heroText.animate().alpha(1f).translationX(0f).setDuration(200).start()
            }.start()
            bindHeroArt(game)
        }
        setAmbient(if (game.ambient != 0) game.ambient else game.baseColor)
    }

    private fun bindHero(game: Game) {
        heroKicker.text = if (game.fromCatalog) {
            getString(R.string.kicker_catalog) + if (game.license.isNotEmpty()) " · " + game.license else ""
        } else {
            getString(R.string.kicker_local)
        }
        heroTitle.text = game.title
        val players = when {
            game.players.isEmpty() -> ""
            game.players == "1" -> getString(R.string.players_one)
            else -> getString(R.string.players_many, game.players)
        }
        heroMeta.text = listOf(game.author, game.year, game.genre, players).filter { it.isNotEmpty() }.joinToString("  ·  ")
        heroMeta.visibility = if (heroMeta.text.isEmpty()) View.GONE else View.VISIBLE
        heroDescription.text = if (game.fromCatalog) game.description
            else getString(R.string.local_description, game.romFile?.absolutePath ?: "")
        updateHeroState(game)
        bindHeroArt(game)
    }

    private fun bindHeroArt(game: Game) {
        val lp = heroArt.layoutParams
        val cached = covers.cached(game, lp.width, lp.height)
        if (cached != null) {
            heroArt.setImageDrawable(BitmapDrawable(resources, cached).apply { isFilterBitmap = cached.width >= lp.width })
            return
        }
        heroArt.setImageDrawable(generatedCover(game))
        covers.load(game, lp.width, lp.height) { bmp ->
            if (bmp == null || heroGame !== game) return@load
            heroArt.setImageDrawable(BitmapDrawable(resources, bmp).apply { isFilterBitmap = bmp.width >= lp.width })
            if (game.ambient != 0) setAmbient(game.ambient)
        }
    }

    private fun updateHeroState(game: Game) {
        hintPrimary.text = when {
            game.downloading -> if (game.progress > 0f) getString(R.string.downloading, (game.progress * 100).toInt())
                else getString(R.string.download_waiting)
            game.installed -> getString(
                if (Paths.autoState(this, game.key).isFile) R.string.action_continue else R.string.action_play
            )
            game.manualOnly -> getString(R.string.action_author_page)
            game.error != null -> getString(R.string.action_retry)
            else -> getString(R.string.action_get)
        }
        hintFavourite.setText(if (isFavourite(game)) R.string.action_unfavourite else R.string.action_favourite)
        val manual = game.manualOnly && !game.installed && game.error == null
        heroStatus.setTextColor(if (manual) 0xB3FFFFFF.toInt() else 0xFFFF8A80.toInt())
        heroStatus.text = when {
            game.error != null -> game.error
            manual -> getString(R.string.manual_hint, game.sourcePage.ifEmpty { game.downloadUrl })
            else -> ""
        }
        // Keep the hero clear of the shelves: one description line less while a status shows.
        val hasStatus = heroStatus.text.isNotEmpty()
        heroStatus.visibility = if (hasStatus) View.VISIBLE else View.GONE
        heroDescription.maxLines = if (hasStatus) 2 else 3
    }

    private fun hideHero() {
        heroGame = null
        heroText.animate().alpha(0f).setDuration(160).start()
        heroArt.animate().alpha(0f).setDuration(160).start()
        ambient.animate().alpha(0f).setDuration(300).start()
        ambientColor = 0
    }

    private fun setAmbient(color: Int) {
        if (color == ambientColor) return
        val first = ambientColor == 0
        ambientColor = color
        val apply = {
            val c = color and 0x00FFFFFF
            ambientGlow.colors = intArrayOf(0x5C000000 or c, 0x26000000 or c, c)
        }
        if (first) {
            apply()
            ambient.animate().alpha(1f).setDuration(600).start()
        } else {
            ambient.animate().alpha(0.35f).setDuration(180).withEndAction {
                if (ambientColor != color) return@withEndAction
                apply()
                ambient.animate().alpha(1f).setDuration(450).start()
            }.start()
        }
    }

    private fun hideGlass() {
        if (!glassShown) return
        glassShown = false
        glass.animate().alpha(0f).setDuration(160).start()
    }

    companion object {
        private const val REQ_STORAGE = 1
    }
}
