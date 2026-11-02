package com.pranav.lumen

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaActionSound
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : Activity(), CameraEngine.Listener {

    companion object {
        const val ACCENT = 0xFFFFD60A.toInt()
        const val REC = 0xFFFF3B30.toInt()
        const val DIM = 0xA6FFFFFF.toInt()
        const val GLASS = 0x26FFFFFF
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val WB_PRESETS = listOf(
            "AUTO" to CameraMetadata.CONTROL_AWB_MODE_AUTO,
            "TUNGSTEN" to CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
            "FLUOR" to CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT,
            "DAYLIGHT" to CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
            "CLOUDY" to CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            "SHADE" to CameraMetadata.CONTROL_AWB_MODE_SHADE
        )
        private val CHIPS = listOf("ISO", "SHUTTER", "FOCUS", "WB", "EV", "ZOOM", "LENS")
    }

    private lateinit var engine: CameraEngine
    private lateinit var texture: TextureView
    private lateinit var overlay: OverlayView
    private lateinit var area: FrameLayout
    private lateinit var stage: FrameLayout
    private lateinit var blink: View
    private lateinit var info: TextView
    private lateinit var countText: TextView
    private lateinit var shutter: ShutterView
    private lateinit var thumb: ImageView
    private lateinit var tray: LinearLayout
    private lateinit var chipRow: LinearLayout
    private lateinit var dial: LinearLayout
    private lateinit var zoomRow: LinearLayout
    private lateinit var modeRow: LinearLayout
    private lateinit var bFlash: TextView
    private lateinit var bTimer: TextView
    private lateinit var bFmt: TextView
    private lateinit var bProc: TextView
    private lateinit var bRes: TextView
    private lateinit var bFps: TextView
    private lateinit var bTl: TextView
    private lateinit var orientationListener: OrientationEventListener

    private val ui = Handler(Looper.getMainLooper())
    private val sound = MediaActionSound()
    private val sp by lazy { getSharedPreferences("lumen", MODE_PRIVATE) }
    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }

    private var mode = Mode.PHOTO
    private var currentLens: Lens? = null
    private var selChip: String? = "ISO"
    private var lastUri: Uri? = null
    private var lastMime = "image/jpeg"
    private var recStart = 0L
    private var counting = false
    private var timer = 0
    private var evFloat = 0f
    private var liveIso: Int? = null
    private var liveExp: Long? = null
    private var liveFocus: Float? = null

    // analysis buffers
    private var anBmp: Bitmap? = null
    private var outBmp: Bitmap? = null
    private var px = IntArray(0)
    private var outPx = IntArray(0)
    private var lum = IntArray(0)
    private val histArr = IntArray(256)
    private var zf = 0

    private fun pref(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun setPref(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
    private fun dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density).roundToInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        engine = CameraEngine(this, this)
        engine.output = Output.values()[sp.getInt("output", 0).coerceIn(0, Output.values().size - 1)]
        engine.proc = Proc.values()[sp.getInt("proc", 1).coerceIn(0, Proc.values().size - 1)]
        engine.flash = Flash.values()[sp.getInt("flash", 0).coerceIn(0, Flash.values().size - 1)]
        engine.videoRes = sp.getInt("res", 2160)
        engine.videoFps = sp.getInt("fps", 30)
        engine.tlIntervalSec = sp.getFloat("tl", 1f).toDouble()
        engine.rawAudio = pref("rawAudio", true)
        timer = sp.getInt("timer", 0)
        sound.load(MediaActionSound.SHUTTER_CLICK)

        buildUi()

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                engine.deviceRotation = ((o + 45) / 90 * 90) % 360
            }
        }
    }

    override fun onResume() {
        super.onResume()
        orientationListener.enable()
        if (hasCameraPermission()) begin()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 7)
        applyOverlayPrefs()
        ui.post(analyzer)
    }

    override fun onPause() {
        engine.stop()
        orientationListener.disable()
        sensorManager.unregisterListener(levelListener)
        ui.removeCallbacks(analyzer)
        ui.removeCallbacks(recTicker)
        super.onPause()
    }

    override fun onDestroy() {
        sound.release()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasCameraPermission()) begin() else toast("Lumen needs camera permission to work.")
    }

    private fun hasCameraPermission() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun begin() {
        engine.start()
        if (engine.lenses.isEmpty()) engine.loadLenses()
        if (texture.isAvailable) openCurrent()
    }

    private fun openCurrent() {
        if (!hasCameraPermission()) return
        val st = texture.surfaceTexture ?: return
        val l = currentLens ?: engine.defaultLens() ?: run { toast("No camera found"); return }
        currentLens = l
        engine.mode = mode
        engine.open(l, st)
    }

    // ------------------------------------------------------------------ UI

    private fun pill(label: String, onClick: (TextView) -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 11f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.06f
        gravity = Gravity.CENTER
        isSingleLine = true
        setPadding(dp(10), dp(7), dp(10), dp(7))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(GLASS) }
        setOnClickListener { onClick(this) }
    }

    private fun setPillOn(t: TextView, on: Boolean) {
        (t.background as? GradientDrawable)?.setColor(if (on) ACCENT else GLASS)
        t.setTextColor(if (on) Color.BLACK else Color.WHITE)
    }

    private fun lpH(start: Int = 6) = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(start) }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        // top bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(8))
        }
        bFlash = pill("") { cycleFlash() }
        bTimer = pill("") { cycleTimer() }
        bFmt = pill("") { cycleFmt() }
        bProc = pill("") { cycleProc() }
        bRes = pill("") { cycleRes() }
        bFps = pill("") { cycleFps() }
        bTl = pill("") { cycleTl() }
        val bSet = pill("•••") { showSettings() }
        top.addView(bFlash, lpH(0))
        top.addView(bTimer, lpH())
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        for (v in listOf(bFmt, bProc, bRes, bFps, bTl, bSet)) top.addView(v, lpH())
        root.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        // viewfinder area
        area = FrameLayout(this)
        stage = FrameLayout(this)
        texture = TextureView(this)
        overlay = OverlayView(this)
        blink = View(this).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        stage.addView(texture, FrameLayout.LayoutParams(MATCH, MATCH))
        stage.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        stage.addView(blink, FrameLayout.LayoutParams(MATCH, MATCH))
        area.addView(stage, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))

        info = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.05f
            fontFeatureSettings = "tnum"
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(9), dp(4), dp(9), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(0x66000000) }
        }
        area.addView(info, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(8) })

        countText = TextView(this).apply {
            textSize = 110f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            setShadowLayer(24f, 0f, 2f, 0x99000000.toInt())
            visibility = View.GONE
        }
        area.addView(countText, FrameLayout.LayoutParams(MATCH, MATCH))

        zoomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0x59000000) }
        }
        area.addView(zoomRow, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(14) })

        // pro tray
        tray = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(0xCC000000.toInt())
            visibility = View.GONE
            isClickable = true
        }
        chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scroller = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) }
        dial = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(46)
        }
        tray.addView(scroller, LinearLayout.LayoutParams(MATCH, WRAP))
        tray.addView(dial, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        area.addView(tray, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        for (k in CHIPS) {
            val t = TextView(this).apply {
                tag = k
                textSize = 11f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                fontFeatureSettings = "tnum"
                minWidth = dp(60)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(GLASS) }
                setOnClickListener { selChip = if (selChip == k) null else k; refreshChips(); renderDial() }
            }
            chipRow.addView(t, lpH(if (chipRow.childCount == 0) 0 else 6))
        }

        root.addView(area, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // modes
        modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (m in Mode.values()) {
            val t = TextView(this).apply {
                text = m.label
                tag = m
                textSize = 13f
                letterSpacing = 0.08f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(dp(11), dp(12), dp(11), dp(6))
                setOnClickListener { setMode(m) }
            }
            modeRow.addView(t)
        }
        root.addView(modeRow, LinearLayout.LayoutParams(MATCH, WRAP))

        // controls
        val controls = FrameLayout(this).apply { setPadding(dp(28), dp(8), dp(28), dp(28)) }
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat(); setColor(0xFF1C1C1E.toInt()); setStroke(dp(1.5f), 0x33FFFFFF)
            }
            clipToOutline = true
            contentDescription = "Last photo"
            setOnClickListener { openLast() }
        }
        controls.addView(thumb, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.START or Gravity.CENTER_VERTICAL))
        shutter = ShutterView(this).apply { setOnClickListener { onShutter() } }
        controls.addView(shutter, FrameLayout.LayoutParams(dp(78), dp(78), Gravity.CENTER))
        val flip = pill("FLIP") { flipCamera() }.apply {
            (background as GradientDrawable).cornerRadius = dp(26).toFloat()
            contentDescription = "Switch camera"
        }
        controls.addView(flip, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(controls, LinearLayout.LayoutParams(MATCH, WRAP))

        setContentView(root)

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { openCurrent() }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        setupGestures()
        updateTop()
        markModes()
        refreshChips()
    }

    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(det: ScaleGestureDetector): Boolean {
                setZoom(engine.zoom * det.scaleFactor)
                return true
            }
        })
        val gest = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { tapFocus(e.x, e.y, false); return true }
            override fun onLongPress(e: MotionEvent) { tapFocus(e.x, e.y, true) }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (overlay.focusVisible() && abs(dy) > abs(dx)) { adjustEv(dy); return true }
                return false
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || engine.recording || overlay.focusVisible()) return false
                val ddx = e2.x - e1.x
                if (abs(ddx) > dp(70) && abs(vx) > abs(vy)) {
                    val all = Mode.values()
                    val i = (mode.ordinal + if (ddx < 0) 1 else -1).coerceIn(0, all.size - 1)
                    setMode(all[i])
                    return true
                }
                return false
            }
        })
        stage.setOnTouchListener { _, ev ->
            scale.onTouchEvent(ev)
            if (!scale.isInProgress) gest.onTouchEvent(ev)
            true
        }
    }

    private fun layoutStage() {
        area.post {
            val ps = engine.previewSize
            val aspect = min(ps.width, ps.height).toFloat() / max(ps.width, ps.height)
            var w = area.width.toFloat()
            var h = w / aspect
            if (h > area.height) { h = area.height.toFloat(); w = h * aspect }
            stage.layoutParams = FrameLayout.LayoutParams(w.toInt(), h.toInt(), Gravity.CENTER)
        }
    }

    private fun updateTop() {
        val photo = mode == Mode.PHOTO || mode == Mode.PRO
        bFlash.visibility = if (mode == Mode.TIMELAPSE) View.GONE else View.VISIBLE
        bTimer.visibility = if (photo) View.VISIBLE else View.GONE
        bFmt.visibility = if (photo) View.VISIBLE else View.GONE
        bProc.visibility = if (mode == Mode.TIMELAPSE) View.GONE else View.VISIBLE
        bRes.visibility = if (photo) View.GONE else View.VISIBLE
        bFps.visibility = if (mode == Mode.VIDEO) View.VISIBLE else View.GONE
        bTl.visibility = if (mode == Mode.TIMELAPSE) View.VISIBLE else View.GONE

        val fl = if (!photo && engine.flash != Flash.OFF) "TORCH" else engine.flash.label
        bFlash.text = fl
        setPillOn(bFlash, engine.flash != Flash.OFF)
        bTimer.text = if (timer == 0) "TIMER" else "${timer}S"
        setPillOn(bTimer, timer != 0)
        bFmt.text = engine.output.label
        setPillOn(bFmt, engine.output != Output.JPEG)
        bProc.text = engine.proc.label
        setPillOn(bProc, engine.proc == Proc.OFF)
        bRes.text = when { engine.videoRes >= 2160 -> "4K"; engine.videoRes >= 1080 -> "HD"; else -> "720P" }
        bFps.text = "${engine.videoFps}"
        bTl.text = "1 / ${formatSec(engine.tlIntervalSec)}"
    }

    private fun formatSec(s: Double) = if (s < 1) "${s}s" else "${s.roundToInt()}s"

    private fun markModes() {
        for (i in 0 until modeRow.childCount) {
            val t = modeRow.getChildAt(i) as TextView
            t.setTextColor(if (t.tag == mode) ACCENT else DIM)
        }
    }

    // ------------------------------------------------------------------ top-bar actions

    private fun cycleFlash() {
        if (!engine.hasFlash) { toast("This lens has no flash."); return }
        val photo = mode == Mode.PHOTO || mode == Mode.PRO
        engine.flash = if (photo) {
            Flash.values()[(engine.flash.ordinal + 1) % Flash.values().size]
        } else {
            if (engine.flash == Flash.OFF) Flash.TORCH else Flash.OFF
        }
        sp.edit().putInt("flash", engine.flash.ordinal).apply()
        engine.updateRepeating()
        updateTop()
    }

    private fun cycleTimer() {
        timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 }
        sp.edit().putInt("timer", timer).apply()
        updateTop()
    }

    private fun cycleFmt() {
        if (!engine.rawCapable) {
            engine.output = Output.JPEG
            toast("This lens doesn't offer RAW to apps.")
        } else {
            engine.output = Output.values()[(engine.output.ordinal + 1) % Output.values().size]
            if (engine.output != Output.JPEG) toast("RAW saves the sensor's data as a .dng file. Edit it in Lightroom or Snapseed.")
        }
        sp.edit().putInt("output", engine.output.ordinal).apply()
        updateTop()
    }

    private fun cycleProc() {
        engine.proc = Proc.values()[(engine.proc.ordinal + 1) % Proc.values().size]
        sp.edit().putInt("proc", engine.proc.ordinal).apply()
        toast(
            when (engine.proc) {
                Proc.OFF -> "Noise reduction and sharpening off. Natural grain and detail."
                Proc.LIGHT -> "Minimal noise reduction, light sharpening."
                Proc.PHONE -> "Phone's full noise reduction and sharpening."
            }
        )
        engine.updateRepeating()
        updateTop()
    }

    private fun cycleRes() {
        engine.videoRes = when (engine.videoRes) { 2160 -> 1080; 1080 -> 720; else -> 2160 }
        sp.edit().putInt("res", engine.videoRes).apply()
        updateTop()
        engine.reconfigure()
    }

    private fun cycleFps() {
        val next = when (engine.videoFps) { 24 -> 30; 30 -> 60; else -> 24 }
        if (next == 60 && !engine.supports60()) {
            engine.videoFps = 24
            toast("60 fps isn't available on this lens.")
        } else engine.videoFps = next
        sp.edit().putInt("fps", engine.videoFps).apply()
        updateTop()
        engine.reconfigure()
    }

    private fun cycleTl() {
        val opts = doubleArrayOf(0.5, 1.0, 2.0, 5.0)
        val i = opts.indexOfFirst { abs(it - engine.tlIntervalSec) < 0.01 }
        engine.tlIntervalSec = opts[(i + 1) % opts.size]
        sp.edit().putFloat("tl", engine.tlIntervalSec.toFloat()).apply()
        updateTop()
    }

    // ------------------------------------------------------------------ modes

    private fun setMode(m: Mode) {
        if (engine.recording || m == mode) return
        mode = m
        engine.mode = m
        if (m == Mode.PHOTO) {
            engine.iso = null; engine.expNs = null; engine.focusD = null
            engine.wbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO
        }
        if ((m == Mode.VIDEO || m == Mode.TIMELAPSE) && engine.flash != Flash.OFF && engine.flash != Flash.TORCH) {
            engine.flash = Flash.OFF
        }
        tray.visibility = if (m == Mode.PRO) View.VISIBLE else View.GONE
        zoomRow.visibility = if (m == Mode.PRO) View.GONE else View.VISIBLE
        if (m == Mode.PRO && selChip == null) selChip = "ISO"
        shutter.video = m == Mode.VIDEO || m == Mode.TIMELAPSE
        if (m == Mode.PRO && !pref("hist", false)) { setPref("hist", true); applyOverlayPrefs() }
        updateTop(); markModes(); refreshChips(); renderDial()
        engine.reconfigure()
    }

    private fun flipCamera() {
        if (engine.recording) return
        val front = currentLens?.facing != android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
        currentLens = engine.defaultLens(front)
        openCurrent()
    }

    // ------------------------------------------------------------------ capture

    private fun onShutter() {
        if (mode == Mode.VIDEO || mode == Mode.TIMELAPSE) {
            if (engine.recording) engine.stopRecording() else engine.startRecording()
            return
        }
        if (counting) return
        if (timer > 0) countdown(timer) else shoot()
    }

    private fun countdown(n: Int) {
        counting = true
        countText.visibility = View.VISIBLE
        var left = n
        val tick = object : Runnable {
            override fun run() {
                if (left == 0) {
                    countText.visibility = View.GONE
                    counting = false
                    shoot()
                    return
                }
                countText.text = left.toString()
                left--
                ui.postDelayed(this, 1000)
            }
        }
        ui.post(tick)
    }

    private fun shoot() {
        if (pref("sound", true)) sound.play(MediaActionSound.SHUTTER_CLICK)
        shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        blink.alpha = 0.85f
        blink.animate().alpha(0f).setDuration(180).start()
        engine.capture()
    }

    private val recTicker = object : Runnable {
        override fun run() {
            val t = (System.currentTimeMillis() - recStart) / 1000
            info.text = "● %02d:%02d".format(t / 60, t % 60)
            ui.postDelayed(this, 500)
        }
    }

    // ------------------------------------------------------------------ focus / zoom / EV

    private fun tapFocus(x: Float, y: Float, lock: Boolean) {
        if (stage.width == 0) return
        engine.focusAt(x / stage.width, y / stage.height, lock)
        overlay.focusX = x
        overlay.focusY = y
        overlay.focusUntil = System.currentTimeMillis() + 3000
        overlay.locked = lock
        overlay.sunOffset = 0f
        evFloat = engine.ev.toFloat()
        if (lock) overlay.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        overlay.invalidate()
        refreshChips()
        if (selChip == "FOCUS") renderDial()
    }

    private fun adjustEv(dy: Float) {
        val r = engine.evRange ?: return
        if (r.lower == r.upper) return
        evFloat += dy / dp(18)
        evFloat = evFloat.coerceIn(r.lower.toFloat(), r.upper.toFloat())
        val v = evFloat.roundToInt()
        overlay.sunOffset = -(evFloat / max(1, r.upper)) * dp(36)
        overlay.focusUntil = System.currentTimeMillis() + 3000
        overlay.invalidate()
        if (v != engine.ev) {
            engine.ev = v
            engine.updateRepeating()
            refreshChips()
        }
    }

    private fun setZoom(v: Float) {
        val r = engine.zoomRange
        engine.zoom = v.coerceIn(r.lower, r.upper)
        engine.updateRepeating()
        markZoom()
        refreshChips()
    }

    private fun buildZoom() {
        zoomRow.removeAllViews()
        val r = engine.zoomRange
        val stops = mutableListOf<Float>()
        if (r.lower < 0.95f) stops += r.lower
        for (z in listOf(1f, 2f, 3f, 5f, 10f)) if (z >= r.lower && z <= r.upper) stops += z
        if (stops.size < 2) { zoomRow.visibility = View.GONE; return }
        if (mode != Mode.PRO) zoomRow.visibility = View.VISIBLE
        for (z in stops) {
            val t = TextView(this).apply {
                tag = z
                textSize = 11f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x66000000) }
                setOnClickListener { setZoom(z) }
            }
            zoomRow.addView(t, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
        markZoom()
    }

    private fun zoomLabel(z: Float): String {
        val s = if (abs(z - z.roundToInt()) < 0.05f) z.roundToInt().toString() else "%.1f".format(z)
        return s.removePrefix("0")
    }

    private fun markZoom() {
        val z = engine.zoom
        var active: TextView? = null
        for (i in 0 until zoomRow.childCount) {
            val t = zoomRow.getChildAt(i) as TextView
            if ((t.tag as Float) <= z + 0.01f) active = t
        }
        for (i in 0 until zoomRow.childCount) {
            val t = zoomRow.getChildAt(i) as TextView
            val stop = t.tag as Float
            if (t === active) {
                t.text = zoomLabel(z) + "×"
                t.setTextColor(ACCENT)
            } else {
                t.text = zoomLabel(stop)
                t.setTextColor(Color.WHITE)
            }
        }
    }

    // ------------------------------------------------------------------ pro tray

    private fun chipValue(k: String): Pair<String, Boolean> = when (k) {
        "ISO" -> if (!engine.manualSensor) "—" to false
        else engine.iso?.let { "$it" to true } ?: ((liveIso?.toString() ?: "AUTO") + " A" to false)
        "SHUTTER" -> if (!engine.manualSensor) "—" to false
        else engine.expNs?.let { CameraEngine.fmtShutter(it) to true }
            ?: ((liveExp?.let { CameraEngine.fmtShutter(it) } ?: "AUTO") + " A" to false)
        "FOCUS" -> if (engine.minFocusD <= 0f) "FIXED" to false
        else engine.focusD?.let { focusLabel(it) to true } ?: ("AF" to false)
        "WB" -> (WB_PRESETS.firstOrNull { it.second == engine.wbMode }?.first ?: "AUTO") to (engine.wbMode != CameraMetadata.CONTROL_AWB_MODE_AUTO)
        "EV" -> {
            val v = engine.ev * engine.evStep
            (if (v > 0) "+%.1f" else if (v < 0) "%.1f" else "±0").format(v) to (engine.ev != 0)
        }
        "ZOOM" -> (zoomLabel(engine.zoom) + "×") to (abs(engine.zoom - 1f) > 0.01f)
        "LENS" -> (currentLens?.label ?: "—") to false
        else -> "" to false
    }

    private fun focusLabel(d: Float): String = if (d < 0.02f) "∞" else if (1f / d >= 1f) "%.1fm".format(1f / d) else "%.0fcm".format(100f / d)

    private fun refreshChips() {
        for (i in 0 until chipRow.childCount) {
            val t = chipRow.getChildAt(i) as TextView
            val k = t.tag as String
            val (v, manual) = chipValue(k)
            t.text = "$k\n$v"
            val sel = selChip == k
            (t.background as GradientDrawable).setColor(if (sel) Color.WHITE else GLASS)
            t.setTextColor(
                when {
                    sel -> Color.BLACK
                    manual -> ACCENT
                    else -> Color.WHITE
                }
            )
        }
    }

    private fun note(text: String) {
        dial.addView(TextView(this).apply {
            this.text = text
            setTextColor(DIM)
            textSize = 12f
        }, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun makeDial(
        lo: Double, hi: Double, log: Boolean, cur: Double, isAuto: Boolean,
        set: (Double) -> Unit, auto: (() -> Unit)?, fmt: (Double) -> String
    ) {
        val toP: (Double) -> Int = { v ->
            val c = v.coerceIn(lo, hi)
            (if (log) ln(c / lo) / ln(hi / lo) * 1000 else (c - lo) / (hi - lo) * 1000).roundToInt()
        }
        val fromP: (Int) -> Double = { p -> if (log) lo * (hi / lo).pow(p / 1000.0) else lo + p / 1000.0 * (hi - lo) }
        val value = TextView(this).apply {
            setTextColor(ACCENT)
            textSize = 15f
            fontFeatureSettings = "tnum"
            gravity = Gravity.CENTER
            minWidth = dp(72)
            text = if (isAuto) "AUTO" else fmt(cur)
        }
        var autoBtn: TextView? = null
        if (auto != null) {
            autoBtn = pill("AUTO") { b ->
                auto()
                engine.updateRepeating()
                setPillOn(b, true)
                value.text = "AUTO"
                refreshChips()
            }
            setPillOn(autoBtn, isAuto)
            dial.addView(autoBtn, lpH(0))
        }
        val seek = SeekBar(this).apply {
            max = 1000
            progress = toP(cur)
            progressTintList = ColorStateList.valueOf(ACCENT)
            thumbTintList = ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(0x66FFFFFF)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = fromP(p)
                    set(v)
                    value.text = fmt(v)
                    autoBtn?.let { setPillOn(it, false) }
                    engine.updateRepeating()
                    refreshChips()
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        dial.addView(seek, LinearLayout.LayoutParams(0, WRAP, 1f))
        dial.addView(value, LinearLayout.LayoutParams(WRAP, WRAP))
    }

    private fun renderDial() {
        dial.removeAllViews()
        when (selChip) {
            "ISO" -> {
                val r = engine.isoRange
                if (!engine.manualSensor || r == null) note("This lens doesn't allow manual ISO.")
                else makeDial(
                    r.lower.toDouble(), r.upper.toDouble(), true,
                    (engine.iso ?: liveIso ?: r.lower).toDouble(), engine.iso == null,
                    { v -> engine.iso = v.roundToInt() }, { engine.iso = null }, { v -> "ISO ${v.roundToInt()}" }
                )
            }
            "SHUTTER" -> {
                val r = engine.expRange
                if (!engine.manualSensor || r == null) note("This lens doesn't allow manual shutter speed.")
                else {
                    val hi = min(r.upper, 30_000_000_000L).toDouble()
                    makeDial(
                        r.lower.toDouble(), hi, true,
                        (engine.expNs ?: liveExp ?: 10_000_000L).toDouble(), engine.expNs == null,
                        { v -> engine.expNs = v.toLong() }, { engine.expNs = null },
                        { v -> CameraEngine.fmtShutter(v.toLong()) }
                    )
                }
            }
            "FOCUS" -> {
                val m = engine.minFocusD
                if (m <= 0f) note("This lens has fixed focus.")
                else makeDial(
                    0.0, m.toDouble(), false,
                    (engine.focusD ?: liveFocus ?: 0f).toDouble(), engine.focusD == null,
                    { v -> engine.focusD = v.toFloat() }, { engine.focusD = null; engine.clearFocus() },
                    { v -> focusLabel(v.toFloat()) }
                )
            }
            "WB" -> {
                val avail = engine.chars?.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: IntArray(0)
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for ((name, v) in WB_PRESETS) {
                    if (!avail.contains(v)) continue
                    val b = pill(name) {
                        engine.wbMode = v
                        engine.updateRepeating()
                        renderDial(); refreshChips()
                    }
                    setPillOn(b, engine.wbMode == v)
                    row.addView(b, lpH(if (row.childCount == 0) 0 else 6))
                }
                dial.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
            }
            "EV" -> {
                val r = engine.evRange
                if (r == null || r.lower == r.upper) note("Exposure compensation isn't available.")
                else {
                    if (engine.iso != null || engine.expNs != null) note("EV applies when ISO and shutter are on AUTO.")
                    else makeDial(
                        r.lower.toDouble(), r.upper.toDouble(), false, engine.ev.toDouble(), engine.ev == 0,
                        { v -> engine.ev = v.roundToInt() }, { engine.ev = 0 },
                        { v -> "%+.1f EV".format(v.roundToInt() * engine.evStep) }
                    )
                }
            }
            "ZOOM" -> {
                val r = engine.zoomRange
                makeDial(
                    r.lower.toDouble(), r.upper.toDouble(), true, engine.zoom.toDouble(), false,
                    { v -> engine.zoom = v.toFloat(); markZoom() }, null, { v -> zoomLabel(v.toFloat()) + "×" }
                )
            }
            "LENS" -> {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (l in engine.lenses) {
                    val b = pill(l.label) {
                        if (engine.recording) return@pill
                        currentLens = l
                        openCurrent()
                    }
                    setPillOn(b, l.id == currentLens?.id)
                    row.addView(b, lpH(if (row.childCount == 0) 0 else 6))
                }
                dial.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
            }
        }
    }

    // ------------------------------------------------------------------ overlays

    private val levelListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            var a = Math.toDegrees(atan2(e.values[0].toDouble(), e.values[1].toDouble())).toFloat()
            if (abs(a) > 45f && abs(a) < 135f) a = if (a > 0) a - 90f else a + 90f
            else if (abs(a) >= 135f) a = if (a > 0) a - 180f else a + 180f
            overlay.levelAngle = overlay.levelAngle * 0.7f + a * 0.3f
            overlay.invalidate()
        }
        override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
    }

    private fun applyOverlayPrefs() {
        overlay.grid = pref("grid", true)
        overlay.level = pref("level", true)
        sensorManager.unregisterListener(levelListener)
        if (overlay.level) {
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager.registerListener(levelListener, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
        overlay.invalidate()
    }

    private val analyzer = object : Runnable {
        override fun run() {
            try { analyze() } catch (e: Exception) { }
            ui.postDelayed(this, 120)
        }
    }

    private fun analyze() {
        val hist = pref("hist", false)
        val zebra = pref("zebra", false)
        val peak = pref("peak", false)
        if (!(hist || zebra || peak) || !texture.isAvailable || stage.width == 0) {
            if (overlay.analysis != null || overlay.hist != null) {
                overlay.analysis = null; overlay.hist = null; overlay.invalidate()
            }
            return
        }
        val w = 160
        val h = max(1, (160f * stage.height / stage.width).roundToInt())
        val cur = anBmp
        if (cur == null || cur.width != w || cur.height != h) {
            anBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            px = IntArray(w * h); outPx = IntArray(w * h); lum = IntArray(w * h)
        }
        val bmp = anBmp ?: return
        texture.getBitmap(bmp)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        java.util.Arrays.fill(histArr, 0)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            val l = (r * 54 + g * 183 + b * 19) shr 8
            lum[i] = l
            if (hist) {
                histArr[r shr 2]++
                histArr[64 + (g shr 2)]++
                histArr[128 + (b shr 2)]++
                histArr[192 + (l shr 2)]++
            }
        }
        if (zebra || peak) {
            java.util.Arrays.fill(outPx, 0)
            zf = (zf + 1) % 8
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val p = y * w + x
                    if (zebra && lum[p] > 242 && ((x + y + zf) and 7) < 3) {
                        outPx[p] = 0xC8FFFFFF.toInt(); continue
                    }
                    if (peak) {
                        val gx = -lum[p - w - 1] - 2 * lum[p - 1] - lum[p + w - 1] + lum[p - w + 1] + 2 * lum[p + 1] + lum[p + w + 1]
                        val gy = -lum[p - w - 1] - 2 * lum[p - w] - lum[p - w + 1] + lum[p + w - 1] + 2 * lum[p + w] + lum[p + w + 1]
                        if (gx * gx + gy * gy > 26000) outPx[p] = REC
                    }
                }
            }
            outBmp?.setPixels(outPx, 0, w, 0, 0, w, h)
            overlay.analysis = outBmp
        } else overlay.analysis = null
        overlay.hist = if (hist) histArr else null
        overlay.invalidate()
    }

    // ------------------------------------------------------------------ settings + gallery

    private fun showSettings() {
        val keys = arrayOf("grid", "level", "hist", "zebra", "peak", "sound", "rawAudio")
        val names = arrayOf(
            "Grid", "Level", "Live histogram", "Zebra stripes (clipped highlights)",
            "Focus peaking", "Shutter sound", "Unprocessed audio (no noise suppression)"
        )
        val defaults = booleanArrayOf(true, true, false, false, false, true, true)
        val checked = BooleanArray(keys.size) { pref(keys[it], defaults[it]) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Lumen settings")
            .setMultiChoiceItems(names, checked) { _, i, on ->
                setPref(keys[i], on)
                if (keys[i] == "rawAudio") engine.rawAudio = on
                applyOverlayPrefs()
            }
            .setNeutralButton("Camera info") { _, _ -> showInfo() }
            .setPositiveButton("Done", null)
            .show()
    }

    private fun showInfo() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("What this camera exposes")
            .setMessage(engine.describe())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun openLast() {
        val u = lastUri ?: run { toast("Photos save to DCIM/Lumen"); return }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, lastMime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            toast("No app can open this file. Find it in DCIM/Lumen.")
        }
    }

    // ------------------------------------------------------------------ engine callbacks

    override fun onReady() {
        layoutStage()
        buildZoom()
        if (mode == Mode.PRO) zoomRow.visibility = View.GONE
        if (!engine.rawCapable && engine.output != Output.JPEG) engine.output = Output.JPEG
        updateTop()
        refreshChips()
        renderDial()
    }

    override fun onLive(iso: Int?, expNs: Long?, focusD: Float?) {
        liveIso = iso; liveExp = expNs; liveFocus = focusD
        if (!engine.recording) {
            val parts = mutableListOf<String>()
            iso?.let { parts += "ISO $it" }
            expNs?.let { parts += CameraEngine.fmtShutter(it) }
            currentLens?.let { parts += it.label }
            if (mode == Mode.VIDEO || mode == Mode.TIMELAPSE) {
                val vs: Size = engine.videoSize()
                parts += "${vs.height}p"
            }
            info.text = parts.joinToString("  ·  ")
        }
        refreshChips()
    }

    override fun onSaved(uri: Uri, mime: String, note: String) {
        lastUri = uri
        lastMime = mime
        toast("Saved: $note")
        Thread {
            try {
                val b = contentResolver.loadThumbnail(uri, Size(200, 200), null)
                ui.post { thumb.setImageBitmap(b) }
            } catch (e: Exception) { }
        }.start()
    }

    override fun onMessage(msg: String) = toast(msg)

    override fun onRecording(on: Boolean) {
        shutter.recording = on
        info.setBackgroundColor(if (on) REC else 0x66000000)
        if (on) {
            recStart = System.currentTimeMillis()
            if (pref("sound", true)) sound.play(MediaActionSound.START_VIDEO_RECORDING)
            ui.post(recTicker)
        } else {
            ui.removeCallbacks(recTicker)
            if (pref("sound", true)) sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
            info.background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(0x66000000) }
        }
        for (i in 0 until modeRow.childCount) modeRow.getChildAt(i).alpha = if (on) 0.3f else 1f
    }
}
