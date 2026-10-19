package io.github.udhaykumarbala.retro

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.SparseIntArray
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.LibretroDroid
import com.swordfish.libretrodroid.ShaderConfig
import com.swordfish.libretrodroid.Variable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/**
 * Fullscreen NES player built on LibretroDroid's GLRetroView with the fceumm core.
 *
 * Runs in its own process (":player") and is noHistory: leaving it (Quit, Home, another app) saves an
 * automatic state and SRAM, and the next launch of the same game resumes from that state.
 *
 * Controller mapping (positional, Nintendo layout; see README):
 *   Xbox A or X  -> NES B        Xbox B or Y -> NES A
 *   Start (Menu) -> NES Start    View (Back) -> NES Select
 *   D-pad / left stick -> NES D-pad
 *   Guide, Select + Start, or remote Back -> in-game menu
 *   TV remote: D-pad -> D-pad, OK -> NES A, Menu -> NES Start
 */
class PlayerActivity : ComponentActivity() {

    private lateinit var retroView: GLRetroView
    private lateinit var root: FrameLayout
    private lateinit var loading: View
    private lateinit var loadingTitle: TextView
    private lateinit var osd: TextView
    private lateinit var menu: View
    private lateinit var menuAspect: TextView
    private lateinit var menuFilter: TextView
    private lateinit var menuLoad: TextView

    private lateinit var key: String
    private var ready = false
    private var failed = false
    private var quitting = false
    private var menuOpen = false
    private var enginePaused = false
    private var pixelPerfect = false
    private var smooth = false

    /** Per port press counts for each RetroPad key, so two physical buttons mapped to one key behave. */
    private val held = Array(PORTS) { SparseIntArray() }
    private val selectHeld = BooleanArray(PORTS)
    private val startHeld = BooleanArray(PORTS)
    private val dpadX = IntArray(PORTS)
    private val dpadY = IntArray(PORTS)
    /** Gamepad device id to player slot, in order of first use. */
    private val padPorts = SparseIntArray()

    private val hideOsd = Runnable { osd.animate().alpha(0f).setDuration(300).start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        root = findViewById(R.id.playerRoot)
        loading = findViewById(R.id.loading)
        loadingTitle = findViewById(R.id.loadingTitle)
        osd = findViewById(R.id.osd)
        menu = findViewById(R.id.menu)
        menuAspect = findViewById(R.id.menuAspect)
        menuFilter = findViewById(R.id.menuFilter)
        menuLoad = findViewById(R.id.menuLoad)
        hideSystemUi()

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        pixelPerfect = prefs.getBoolean(PREF_PIXEL_PERFECT, false)
        smooth = prefs.getBoolean(PREF_SMOOTH, false)

        key = intent.getStringExtra(EXTRA_KEY) ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val rom = File(intent.getStringExtra(EXTRA_ROM) ?: "")
        loadingTitle.text = title
        findViewById<TextView>(R.id.menuTitle).text = title

        if (!rom.canRead()) {
            fail(getString(R.string.error_missing))
            return
        }

        val sram = Paths.sram(this, key).takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }
        val data = GLRetroViewData(this).apply {
            coreFilePath = File(applicationInfo.nativeLibraryDir, CORE_LIB).absolutePath
            gameFilePath = rom.absolutePath
            systemDirectory = Paths.system(this@PlayerActivity).absolutePath
            savesDirectory = Paths.saves(this@PlayerActivity).absolutePath
            variables = coreVariables()
            saveRAMState = sram
            shader = shaderConfig()
            // Bigger audio buffers: steadier on a slow quad A53 than the low latency path.
            preferLowLatencyAudio = false
            rumbleEventsEnabled = false
        }
        retroView = GLRetroView(this, data).apply {
            isFocusable = false
            isFocusableInTouchMode = false
        }
        root.addView(retroView, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        lifecycle.addObserver(retroView)

        lifecycleScope.launch {
            retroView.getGLRetroErrors().collect { onEmulatorError(it) }
        }
        lifecycleScope.launch {
            retroView.getGLRetroEvents().first { it is GLRetroView.GLRetroEvents.FrameRendered }
            onFirstFrame()
        }

        findViewById<View>(R.id.menuResume).setOnClickListener { closeMenu() }
        findViewById<View>(R.id.menuSave).setOnClickListener { saveSlot() }
        menuLoad.setOnClickListener { loadSlot() }
        findViewById<View>(R.id.menuReset).setOnClickListener {
            runCatching { retroView.reset(false) }
            closeMenu()
            showOsd(getString(R.string.osd_reset))
        }
        menuAspect.setOnClickListener { toggleAspect() }
        menuFilter.setOnClickListener { toggleFilter() }
        findViewById<View>(R.id.menuQuit).setOnClickListener { quit() }
    }

    /**
     * No overscan crop, so the full 240 lines scale exactly 3x to 720p. "4:3" fills 960x720 centred;
     * "PP" (pixel perfect) is 768x720, an exact 3x in both directions.
     */
    private fun coreVariables() = arrayOf(
        Variable("fceumm_aspect", if (pixelPerfect) "PP" else "4:3"),
        Variable("fceumm_overscan_v_top", "0"),
        Variable("fceumm_overscan_v_bottom", "0"),
        Variable("fceumm_overscan_h_left", "0"),
        Variable("fceumm_overscan_h_right", "0"),
    )

    private fun shaderConfig(): ShaderConfig = if (smooth) ShaderConfig.Default else ShaderConfig.Sharp

    private fun onFirstFrame() {
        if (failed) return
        var resumed = false
        val auto = Paths.autoState(this, key)
        if (auto.isFile) {
            resumed = runCatching { retroView.unserializeState(auto.readBytes()) }.getOrDefault(false)
        }
        ready = true
        loading.animate().alpha(0f).setDuration(250).withEndAction { loading.visibility = View.GONE }.start()
        if (resumed) showOsd(getString(R.string.osd_resumed))
    }

    private fun onEmulatorError(code: Int) {
        val msg = when (code) {
            GLRetroView.ERROR_LOAD_LIBRARY -> R.string.error_core
            GLRetroView.ERROR_LOAD_GAME -> R.string.error_game
            GLRetroView.ERROR_GL_NOT_COMPATIBLE -> R.string.error_gl
            else -> R.string.error_generic
        }
        fail(getString(msg))
    }

    private fun fail(message: String) {
        failed = true
        ready = false
        loading.visibility = View.VISIBLE
        loading.alpha = 1f
        loadingTitle.textSize = 20f
        loadingTitle.text = message
        root.postDelayed({ finish() }, 3500)
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        // The lifecycle observer restarts the engine after this returns; keep it paused under the menu.
        if (menuOpen) root.post { if (menuOpen) pauseEngine() }
    }

    override fun onPause() {
        // ON_PAUSE has already been dispatched to GLRetroView here (fragments and lifecycle callbacks run
        // before Activity.onPause), so the GL thread is idle and the core can be touched directly.
        super.onPause()
        enginePaused = false
        if (ready && !quitting && !failed) {
            retroView.onPause()
            saveAuto()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Start every game from a fresh native state: LibretroDroid and the core are process wide singletons.
        if (isFinishing) Process.killProcess(Process.myPid())
    }

    private fun quit() {
        if (ready && !failed) {
            pauseEngine()
            saveAuto()
        }
        quitting = true
        finish()
    }

    private fun pauseEngine() {
        if (enginePaused || !::retroView.isInitialized) return
        retroView.onPause()
        LibretroDroid.pause()
        enginePaused = true
    }

    private fun resumeEngine() {
        if (!enginePaused) return
        LibretroDroid.resume()
        retroView.onResume()
        enginePaused = false
    }

    // ---- saves (call only while the GL thread is paused) -------------------------------------------

    private fun saveAuto() {
        runCatching {
            val state = retroView.serializeState(false)
            if (state.isNotEmpty()) Paths.writeAtomic(Paths.autoState(this, key), state)
        }
        runCatching {
            val sram = retroView.serializeSRAM(false)
            if (sram.isNotEmpty()) Paths.writeAtomic(Paths.sram(this, key), sram)
        }
    }

    private fun saveSlot() {
        val ok = runCatching {
            val state = retroView.serializeState(false)
            state.isNotEmpty() && Paths.writeAtomic(Paths.slotState(this, key), state)
        }.getOrDefault(false)
        closeMenu()
        showOsd(getString(if (ok) R.string.osd_saved else R.string.osd_save_failed))
    }

    private fun loadSlot() {
        val file = Paths.slotState(this, key)
        if (!file.isFile) {
            showOsd(getString(R.string.osd_no_state))
            return
        }
        val ok = runCatching { retroView.unserializeState(file.readBytes(), false) }.getOrDefault(false)
        closeMenu()
        showOsd(getString(if (ok) R.string.osd_loaded else R.string.osd_load_failed))
    }

    // ---- menu ------------------------------------------------------------------------------------

    private fun openMenu() {
        if (!ready || menuOpen || failed) return
        releaseAllInputs()
        pauseEngine()
        menuOpen = true
        updateMenuLabels()
        menu.visibility = View.VISIBLE
        menu.alpha = 0f
        menu.animate().alpha(1f).setDuration(160).start()
        findViewById<View>(R.id.menuResume).requestFocus()
    }

    private fun closeMenu() {
        if (!menuOpen) return
        menuOpen = false
        menu.visibility = View.GONE
        resumeEngine()
    }

    private fun updateMenuLabels() {
        menuAspect.setText(if (pixelPerfect) R.string.menu_aspect_pp else R.string.menu_aspect_43)
        menuFilter.setText(if (smooth) R.string.menu_filter_smooth else R.string.menu_filter_sharp)
        menuLoad.alpha = if (Paths.slotState(this, key).isFile) 1f else 0.45f
    }

    private fun toggleAspect() {
        pixelPerfect = !pixelPerfect
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_PIXEL_PERFECT, pixelPerfect).apply()
        runCatching { retroView.updateVariables(*coreVariables()) }
        updateMenuLabels()
    }

    private fun toggleFilter() {
        smooth = !smooth
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_SMOOTH, smooth).apply()
        runCatching { retroView.shader = shaderConfig() }
        updateMenuLabels()
    }

    private fun showOsd(text: String) {
        osd.removeCallbacks(hideOsd)
        osd.text = text
        osd.animate().alpha(1f).setDuration(150).start()
        osd.postDelayed(hideOsd, 1800)
    }

    // ---- input -----------------------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0

        if (failed || !::retroView.isInitialized) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B) {
                if (first) finish()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (menuOpen) {
            when (code) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_MODE -> {
                    if (first) closeMenu()
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_A -> {
                    if (first) currentFocus?.performClick()
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT -> return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (!ready) {
            // Back leaves while loading; game buttons are swallowed; system keys pass through.
            if (code == KeyEvent.KEYCODE_BACK) {
                if (first) finish()
                return true
            }
            return mapKey(code) != null || super.dispatchKeyEvent(event)
        }

        val port = portOf(event)
        when (code) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_MODE -> {
                if (first) openMenu()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_SELECT -> selectHeld[port] = down
            KeyEvent.KEYCODE_BUTTON_START -> startHeld[port] = down
        }
        if (first && selectHeld[port] && startHeld[port]) {
            openMenu()
            return true
        }

        val target = mapKey(code) ?: return super.dispatchKeyEvent(event)
        if (target == UNMAPPED || event.repeatCount > 0) return true
        press(port, target, down)
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (menuOpen || !ready || !event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            event.actionMasked != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(event)
        }
        val port = portOf(event.device)
        val x = axisToDpad(event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_X))
        val y = axisToDpad(event.getAxisValue(MotionEvent.AXIS_HAT_Y), event.getAxisValue(MotionEvent.AXIS_Y))
        if (x != dpadX[port] || y != dpadY[port]) {
            dpadX[port] = x
            dpadY[port] = y
            retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, x.toFloat(), y.toFloat(), port)
        }
        return true
    }

    private fun axisToDpad(hat: Float, stick: Float): Int = when {
        abs(hat) > 0.5f -> if (hat > 0) 1 else -1
        abs(stick) > STICK_DEADZONE -> if (stick > 0) 1 else -1
        else -> 0
    }

    private fun press(port: Int, retroKey: Int, down: Boolean) {
        val counts = held[port]
        val c = counts.get(retroKey)
        if (down) {
            counts.put(retroKey, c + 1)
            if (c == 0) retroView.sendKeyEvent(KeyEvent.ACTION_DOWN, retroKey, port)
        } else if (c > 0) {
            counts.put(retroKey, c - 1)
            if (c == 1) retroView.sendKeyEvent(KeyEvent.ACTION_UP, retroKey, port)
        }
    }

    private fun releaseAllInputs() {
        for (port in 0 until PORTS) {
            val counts = held[port]
            for (i in 0 until counts.size()) {
                if (counts.valueAt(i) > 0) retroView.sendKeyEvent(KeyEvent.ACTION_UP, counts.keyAt(i), port)
            }
            counts.clear()
            if (dpadX[port] != 0 || dpadY[port] != 0) {
                retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, 0f, 0f, port)
            }
            dpadX[port] = 0
            dpadY[port] = 0
            selectHeld[port] = false
            startHeld[port] = false
        }
    }

    private fun portOf(event: KeyEvent) = portOf(event.device)

    /**
     * Player slot for a device. Remotes and other non-joystick devices always play as player 1.
     * Gamepads take slots in the order they are first used. Android's controllerNumber is not
     * used: TV remotes that register as gamepads take number 1, which pushed a lone Xbox pad to
     * player 2 on the MiTV-AXSO0.
     */
    private fun portOf(device: InputDevice?): Int {
        if (device == null || !device.supportsSource(InputDevice.SOURCE_JOYSTICK)) return 0
        val i = padPorts.indexOfKey(device.id)
        if (i >= 0) return padPorts.valueAt(i)
        val port = padPorts.size().coerceAtMost(PORTS - 1)
        padPorts.put(device.id, port)
        return port
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    companion object {
        const val EXTRA_KEY = "key"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ROM = "rom"

        private const val CORE_LIB = "libfceumm_libretro_android.so"
        private const val PREFS = "player"
        private const val PREF_PIXEL_PERFECT = "pixel_perfect"
        private const val PREF_SMOOTH = "smooth"
        private const val PORTS = 4
        private const val STICK_DEADZONE = 0.5f
        private const val UNMAPPED = -1

        /**
         * Android key code to the key code LibretroDroid understands as the RetroPad button (it maps
         * BUTTON_A to RetroPad A, BUTTON_B to RetroPad B). fceumm reads RetroPad A/B as NES A/B.
         * Returns UNMAPPED for gamepad buttons the NES has no use for, null for keys the system should get.
         */
        fun mapKey(code: Int): Int? = when (code) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_B
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> KeyEvent.KEYCODE_BUTTON_A
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> KeyEvent.KEYCODE_BUTTON_START
            KeyEvent.KEYCODE_BUTTON_SELECT -> KeyEvent.KEYCODE_BUTTON_SELECT
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> code
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR -> UNMAPPED
            else -> null
        }

        fun intent(context: Context, game: Game): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_KEY, game.key)
                .putExtra(EXTRA_TITLE, game.title)
                .putExtra(EXTRA_ROM, game.romFile?.absolutePath)
    }
}
