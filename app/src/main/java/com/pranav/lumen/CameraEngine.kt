package com.pranav.lumen

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.AudioManager
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class Lens(val id: String, val facing: Int, val mm: Int, val label: String)

enum class Flash(val label: String) { OFF("FLASH OFF"), AUTO("FLASH AUTO"), ON("FLASH ON"), TORCH("TORCH") }
enum class Output(val label: String) { JPEG("JPEG"), RAW("RAW"), RAW_JPEG("RAW+JPEG") }
enum class Proc(val label: String) { OFF("PROC OFF"), LIGHT("PROC LITE"), PHONE("PROC HQ") }
enum class Mode(val label: String) { TIMELAPSE("TIME-LAPSE"), VIDEO("VIDEO"), PHOTO("PHOTO"), PRO("PRO") }

/**
 * Camera2 engine. Talks to the sensor directly: manual ISO / shutter / focus,
 * noise-reduction and sharpening switched off at the HAL, RAW_SENSOR -> DNG.
 */
class CameraEngine(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onReady()
        fun onLive(iso: Int?, expNs: Long?, focusD: Float?)
        fun onSaved(uri: Uri, mime: String, note: String)
        fun onMessage(msg: String)
        fun onRecording(on: Boolean)
    }

    private val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val main = Handler(Looper.getMainLooper())

    var lenses: List<Lens> = emptyList(); private set
    var lens: Lens? = null; private set
    var chars: CameraCharacteristics? = null; private set

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var recorder: MediaRecorder? = null
    private var recUri: Uri? = null
    private var recPfd: ParcelFileDescriptor? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null

    var previewSize = Size(1440, 1080); private set
    @Volatile var recording = false; private set

    // ---- user settings ----
    var mode = Mode.PHOTO
    var iso: Int? = null
    var expNs: Long? = null
    var focusD: Float? = null            // diopters, 0 = infinity
    var wbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO
    var ev = 0
    var zoom = 1f
    var flash = Flash.OFF
    var output = Output.JPEG
    var proc = Proc.LIGHT
    var videoRes = 2160
    var videoFps = 30
    var tlIntervalSec = 1.0
    var rawAudio = true
    @Volatile var deviceRotation = 0
    var aeLocked = false
    private var afRect: MeteringRectangle? = null

    @Volatile var lastIso: Int? = null; private set
    @Volatile var lastExp: Long? = null; private set

    // ---- capabilities ----
    private fun caps(): IntArray = chars?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
    private fun streamMap() = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val manualSensor: Boolean get() = caps().contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
    val rawCapable: Boolean
        get() = caps().contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) &&
            (streamMap()?.getOutputSizes(ImageFormat.RAW_SENSOR)?.isNotEmpty() == true)
    val isoRange: Range<Int>? get() = chars?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    val expRange: Range<Long>? get() = chars?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    val minFocusD: Float get() = chars?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
    val evRange: Range<Int>? get() = chars?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
    val evStep: Float get() = chars?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: (1f / 3f)
    val hasFlash: Boolean get() = chars?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    val sensorOrientation: Int get() = chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    val isFront: Boolean get() = lens?.facing == CameraCharacteristics.LENS_FACING_FRONT
    val zoomRange: Range<Float>
        get() {
            if (Build.VERSION.SDK_INT >= 30) {
                val r = chars?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                if (r != null) return r
            }
            val m = chars?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            return Range(1f, max(1f, m))
        }
    fun supports60(): Boolean =
        chars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.any { it.upper >= 60 } == true

    // ---- lifecycle ----
    fun start() {
        if (thread == null) {
            val t = HandlerThread("lumen-camera")
            t.start()
            thread = t
            handler = Handler(t.looper)
        }
    }

    fun stop() {
        closeCamera()
        thread?.quitSafely()
        thread = null
        handler = null
    }

    fun loadLenses() {
        val list = mutableListOf<Lens>()
        for (id in manager.cameraIdList) {
            try {
                val c = manager.getCameraCharacteristics(id)
                val facing = c.get(CameraCharacteristics.LENS_FACING) ?: continue
                if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) continue
                val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 4f
                val s = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val mm = if (s != null) (f * 43.27f / hypot(s.width, s.height)).roundToInt() else 0
                val label = (if (facing == CameraCharacteristics.LENS_FACING_FRONT) "FRONT " else "") + "${mm}mm"
                list += Lens(id, facing, mm, label)
            } catch (e: Exception) {
                // skip cameras we can't describe
            }
        }
        lenses = list.sortedWith(compareBy<Lens>({ it.facing == CameraCharacteristics.LENS_FACING_FRONT }, { it.mm }))
    }

    fun defaultLens(front: Boolean = false): Lens? {
        val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val same = lenses.filter { it.facing == want }
        return same.firstOrNull { it.id == "0" || it.id == "1" } ?: same.minByOrNull { abs(it.mm - 25) } ?: lenses.firstOrNull()
    }

    fun resetManual() {
        iso = null; expNs = null; focusD = null
        wbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO
        ev = 0; zoom = 1f; afRect = null; aeLocked = false
    }

    @SuppressLint("MissingPermission")
    fun open(l: Lens, st: SurfaceTexture) {
        closeCamera()
        if (st !== surfaceTexture) {
            previewSurface?.release()
            surfaceTexture = st
            previewSurface = Surface(st)
        }
        lens = l
        try {
            chars = manager.getCameraCharacteristics(l.id)
        } catch (e: Exception) {
            msg("Can't read camera ${l.id}"); return
        }
        resetManual()
        val r = zoomRange
        zoom = 1f.coerceIn(r.lower, r.upper)
        try {
            manager.openCamera(l.id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d
                    createSession()
                }
                override fun onDisconnected(d: CameraDevice) {
                    d.close(); if (device === d) device = null
                }
                override fun onError(d: CameraDevice, error: Int) {
                    d.close(); if (device === d) device = null
                    msg("Camera error $error. Close other camera apps and try again.")
                }
            }, handler)
        } catch (e: Exception) {
            msg("Can't open camera: ${e.message}")
        }
    }

    fun closeCamera() {
        if (recorder != null) stopRecordingInternal(reopen = false)
        try { session?.close() } catch (e: Exception) { }
        session = null
        try { device?.close() } catch (e: Exception) { }
        device = null
        jpegReader?.close(); jpegReader = null
        rawReader?.close(); rawReader = null
    }

    fun reconfigure() { handler?.post { createSession() } }

    // ---- sizes ----
    private fun choosePreview(aspect: Float, maxW: Int): Size {
        val sizes = streamMap()?.getOutputSizes(SurfaceTexture::class.java) ?: return Size(1920, 1080)
        val fit = sizes.filter { abs(it.width.toFloat() / it.height - aspect) < 0.02f && it.width <= maxW }
        return fit.maxByOrNull { it.width * it.height } ?: sizes.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)
    }

    private fun largest(format: Int): Size? =
        streamMap()?.getOutputSizes(format)?.maxByOrNull { it.width.toLong() * it.height }

    fun videoSize(): Size {
        val sizes = streamMap()?.getOutputSizes(MediaRecorder::class.java) ?: return Size(1920, 1080)
        val target = when {
            videoRes >= 2160 -> 3840
            videoRes >= 1080 -> 1920
            else -> 1280
        }
        return sizes.filter { abs(it.width.toFloat() / it.height - 16f / 9f) < 0.02f && it.width <= target }
            .maxByOrNull { it.width } ?: Size(1920, 1080)
    }

    private fun fpsRange(): Range<Int>? {
        val r = chars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        val want = if (mode == Mode.TIMELAPSE) 30 else videoFps
        return r.filter { it.upper == want }.maxByOrNull { it.lower }
            ?: r.filter { it.upper <= want }.maxByOrNull { it.upper * 1000 + it.lower }
    }

    // ---- session ----
    private fun createSession() {
        val d = device ?: return
        val st = surfaceTexture ?: return
        val pv = previewSurface ?: return
        try {
            try { session?.close() } catch (e: Exception) { }
            session = null
            jpegReader?.close(); jpegReader = null
            rawReader?.close(); rawReader = null

            val surfaces = mutableListOf<Surface>()
            if (mode == Mode.PHOTO || mode == Mode.PRO) {
                val js = largest(ImageFormat.JPEG) ?: Size(4000, 3000)
                previewSize = choosePreview(js.width.toFloat() / js.height, 1920)
                val jr = ImageReader.newInstance(js.width, js.height, ImageFormat.JPEG, 3)
                jr.setOnImageAvailableListener({ r -> onJpeg(r) }, handler)
                jpegReader = jr
                surfaces += jr.surface
                if (rawCapable) {
                    val rs = largest(ImageFormat.RAW_SENSOR)
                    if (rs != null) {
                        val rr = ImageReader.newInstance(rs.width, rs.height, ImageFormat.RAW_SENSOR, 3)
                        rr.setOnImageAvailableListener({ r -> onRaw(r) }, handler)
                        rawReader = rr
                        surfaces += rr.surface
                    }
                }
            } else {
                val vs = videoSize()
                previewSize = choosePreview(vs.width.toFloat() / vs.height, 1920)
            }
            st.setDefaultBufferSize(previewSize.width, previewSize.height)
            surfaces.add(0, pv)
            recorder?.let { surfaces += it.surface }

            @Suppress("DEPRECATION")
            d.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    updateRepeating()
                    main.post { listener.onReady() }
                    if (recorder != null) startRecorderNow()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    msg("The camera refused this combination. Try a lower resolution or frame rate.")
                    if (recorder != null) abortRecorder()
                }
            }, handler)
        } catch (e: Exception) {
            msg("Session error: ${e.message}")
        }
    }

    fun updateRepeating() {
        val s = session ?: return
        val d = device ?: return
        val pv = previewSurface ?: return
        try {
            val tpl = if (recorder != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
            val b = d.createCaptureRequest(tpl)
            b.addTarget(pv)
            recorder?.let { b.addTarget(it.surface) }
            applyCommon(b, false)
            s.setRepeatingRequest(b.build(), liveCb, handler)
        } catch (e: Exception) {
            // session may be closing; ignore
        }
    }

    private var lastLive = 0L
    private val liveCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
            res.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
            res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExp = it }
            val now = System.currentTimeMillis()
            if (now - lastLive > 400) {
                lastLive = now
                val f = res.get(CaptureResult.LENS_FOCUS_DISTANCE)
                val i = lastIso; val e = lastExp
                main.post { listener.onLive(i, e, f) }
            }
        }
    }

    private fun pick(b: CaptureRequest.Builder, key: CaptureRequest.Key<Int>, avail: IntArray, vararg prefs: Int) {
        val v = prefs.firstOrNull { avail.contains(it) } ?: return
        b.set(key, v)
    }

    private fun applyCommon(b: CaptureRequest.Builder, still: Boolean) {
        val video = mode == Mode.VIDEO || mode == Mode.TIMELAPSE
        val manualExp = manualSensor && (iso != null || expNs != null)

        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        // exposure + flash
        if (manualExp) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso ?: lastIso ?: 100)
            val e = expNs ?: lastExp ?: 10_000_000L
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, e)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, if (still) e else max(e, 33_333_333L))
            val fm = when {
                flash == Flash.TORCH || (video && flash == Flash.ON) -> CameraMetadata.FLASH_MODE_TORCH
                still && flash == Flash.ON -> CameraMetadata.FLASH_MODE_SINGLE
                else -> CameraMetadata.FLASH_MODE_OFF
            }
            if (hasFlash) b.set(CaptureRequest.FLASH_MODE, fm)
        } else {
            val ae = when {
                hasFlash && !video && flash == Flash.AUTO -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
                hasFlash && !video && flash == Flash.ON -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                else -> CameraMetadata.CONTROL_AE_MODE_ON
            }
            b.set(CaptureRequest.CONTROL_AE_MODE, ae)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev)
            b.set(CaptureRequest.CONTROL_AE_LOCK, aeLocked)
            if (hasFlash) {
                val torch = flash == Flash.TORCH || (video && flash == Flash.ON)
                b.set(CaptureRequest.FLASH_MODE, if (torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
            }
            val r = afRect
            if (r != null) b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(r))
        }
        if (video) fpsRange()?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }

        // focus
        val fd = focusD
        val r = afRect
        if (fd != null && minFocusD > 0f) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, fd)
        } else if (r != null) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(r))
        } else {
            b.set(
                CaptureRequest.CONTROL_AF_MODE,
                if (video) CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        }

        // white balance
        b.set(CaptureRequest.CONTROL_AWB_MODE, wbMode)

        // zoom
        applyZoom(b)

        // image processing in the ISP
        val nr = chars?.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: IntArray(0)
        val ed = chars?.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: IntArray(0)
        val nrKey = CaptureRequest.NOISE_REDUCTION_MODE
        val edKey = CaptureRequest.EDGE_MODE
        when (proc) {
            Proc.OFF -> {
                pick(b, nrKey, nr, CameraMetadata.NOISE_REDUCTION_MODE_OFF, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
                pick(b, edKey, ed, CameraMetadata.EDGE_MODE_OFF, CameraMetadata.EDGE_MODE_FAST)
            }
            Proc.LIGHT -> {
                pick(b, nrKey, nr, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
                pick(b, edKey, ed, CameraMetadata.EDGE_MODE_FAST)
            }
            Proc.PHONE -> {
                if (still) {
                    pick(b, nrKey, nr, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
                    pick(b, edKey, ed, CameraMetadata.EDGE_MODE_HIGH_QUALITY, CameraMetadata.EDGE_MODE_FAST)
                } else {
                    pick(b, nrKey, nr, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
                    pick(b, edKey, ed, CameraMetadata.EDGE_MODE_FAST)
                }
            }
        }

        if (still) {
            b.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
        }
    }

    private fun applyZoom(b: CaptureRequest.Builder) {
        if (Build.VERSION.SDK_INT >= 30 && chars?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        } else {
            val a = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val z = max(1f, zoom)
            val w = (a.width() / z).toInt()
            val h = (a.height() / z).toInt()
            b.set(
                CaptureRequest.SCALER_CROP_REGION,
                Rect(a.centerX() - w / 2, a.centerY() - h / 2, a.centerX() + w / 2, a.centerY() + h / 2)
            )
        }
    }

    fun jpegOrientation(): Int {
        val s = sensorOrientation
        val d = deviceRotation
        return if (isFront) (s - d + 360) % 360 else (s + d) % 360
    }

    private fun exifOrientation(deg: Int): Int = when (deg) {
        90 -> 6
        180 -> 3
        270 -> 8
        else -> 1
    }

    // ---- focus ----
    fun focusAt(nxIn: Float, nyIn: Float, lock: Boolean) {
        val a = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        var nx = nxIn.coerceIn(0f, 1f)
        val ny = nyIn.coerceIn(0f, 1f)
        if (isFront) nx = 1f - nx
        val sx: Float
        val sy: Float
        when (sensorOrientation) {
            90 -> { sx = ny; sy = 1f - nx }
            180 -> { sx = 1f - nx; sy = 1f - ny }
            270 -> { sx = 1f - ny; sy = nx }
            else -> { sx = nx; sy = ny }
        }
        val half = (min(a.width(), a.height()) * 0.08f).toInt()
        val cx = (sx * a.width()).toInt().coerceIn(half, a.width() - half)
        val cy = (sy * a.height()).toInt().coerceIn(half, a.height() - half)
        val rect = MeteringRectangle(cx - half, cy - half, half * 2, half * 2, MeteringRectangle.METERING_WEIGHT_MAX - 1)
        afRect = rect
        aeLocked = lock
        focusD = null
        handler?.post {
            val s = session ?: return@post
            val d = device ?: return@post
            val pv = previewSurface ?: return@post
            try {
                val b = d.createCaptureRequest(if (recorder != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
                b.addTarget(pv)
                recorder?.let { b.addTarget(it.surface) }
                applyCommon(b, false)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                s.capture(b.build(), null, handler)
                updateRepeating()
            } catch (e: Exception) { }
        }
    }

    fun clearFocus() {
        afRect = null
        aeLocked = false
        updateRepeating()
    }

    // ---- stills ----
    private val shotLock = Any()
    private var pendingRaw: Image? = null
    private var pendingResult: TotalCaptureResult? = null

    fun capture() {
        handler?.post {
            val s = session ?: return@post
            val d = device ?: return@post
            val pv = previewSurface ?: return@post
            val manualExp = manualSensor && (iso != null || expNs != null)
            val needPre = !manualExp && hasFlash && (flash == Flash.ON || flash == Flash.AUTO)
            if (needPre) {
                try {
                    val p = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    p.addTarget(pv)
                    applyCommon(p, false)
                    p.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                    s.capture(p.build(), null, handler)
                } catch (e: Exception) { }
                handler?.postDelayed({ captureStill() }, 900)
            } else {
                captureStill()
            }
        }
    }

    private fun captureStill() {
        val s = session ?: return
        val d = device ?: return
        val jr = jpegReader
        val rr = rawReader
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            val wantR = output != Output.JPEG && rr != null
            val wantJ = output != Output.RAW || rr == null
            if (wantJ && jr != null) b.addTarget(jr.surface)
            if (wantR && rr != null) {
                b.addTarget(rr.surface)
                synchronized(shotLock) {
                    pendingRaw?.close(); pendingRaw = null; pendingResult = null
                }
            }
            applyCommon(b, true)
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(cs: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                    if (wantR) synchronized(shotLock) {
                        pendingResult = res
                        tryWriteRaw()
                    }
                }
                override fun onCaptureFailed(cs: CameraCaptureSession, r: CaptureRequest, f: android.hardware.camera2.CaptureFailure) {
                    msg("Capture failed. Try again.")
                }
            }, handler)
        } catch (e: Exception) {
            msg("Capture failed: ${e.message}")
        }
    }

    private fun onJpeg(r: ImageReader) {
        val img = r.acquireNextImage() ?: return
        try {
            val buf = img.planes[0].buffer
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            val note = "JPEG ${img.width}×${img.height} · ${proc.label}"
            val uri = saveBytes(bytes, "jpg", "image/jpeg")
            if (uri != null) main.post { listener.onSaved(uri, "image/jpeg", note) }
        } catch (e: Exception) {
            msg("Couldn't save JPEG: ${e.message}")
        } finally {
            img.close()
        }
    }

    private fun onRaw(r: ImageReader) {
        val img = r.acquireNextImage() ?: return
        synchronized(shotLock) {
            pendingRaw?.close()
            pendingRaw = img
            tryWriteRaw()
        }
    }

    /** Called with shotLock held. Writes a DNG once both the RAW frame and its metadata have arrived. */
    private fun tryWriteRaw() {
        val img = pendingRaw ?: return
        val res = pendingResult ?: return
        val c = chars ?: return
        pendingRaw = null
        pendingResult = null
        try {
            val dng = DngCreator(c, res)
            dng.setOrientation(exifOrientation(jpegOrientation()))
            val uri = insert("dng", "image/x-adobe-dng", images = true)
            if (uri != null) {
                ctx.contentResolver.openOutputStream(uri)?.use { dng.writeImage(it, img) }
                publish(uri)
                val note = "RAW DNG ${img.width}×${img.height} · straight from the sensor"
                main.post { listener.onSaved(uri, "image/x-adobe-dng", note) }
            }
            dng.close()
        } catch (e: Exception) {
            msg("RAW save failed: ${e.message}")
        } finally {
            img.close()
        }
    }

    // ---- MediaStore ----
    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    private fun insert(ext: String, mime: String, images: Boolean): Uri? {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "LUMEN_${stamp()}.$ext")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Lumen")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val col = if (images) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return ctx.contentResolver.insert(col, v)
    }

    private fun publish(uri: Uri) {
        val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        ctx.contentResolver.update(uri, v, null, null)
    }

    private fun saveBytes(bytes: ByteArray, ext: String, mime: String): Uri? {
        val uri = insert(ext, mime, images = true) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        publish(uri)
        return uri
    }

    // ---- video ----
    private fun audioSource(): Int {
        if (rawAudio) {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") {
                return MediaRecorder.AudioSource.UNPROCESSED
            }
        }
        return MediaRecorder.AudioSource.CAMCORDER
    }

    private fun bitrate(s: Size, fps: Int): Int {
        val bpp = 0.2
        return (s.width.toDouble() * s.height * fps * bpp).toInt().coerceIn(8_000_000, 120_000_000)
    }

    fun startRecording() {
        handler?.post { startRecordingInternal() }
    }

    private fun startRecordingInternal() {
        if (recorder != null || device == null) return
        val tl = mode == Mode.TIMELAPSE
        var uri: Uri? = null
        try {
            val vs = videoSize()
            uri = insert("mp4", "video/mp4", images = false) ?: return
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "w") ?: return
            recUri = uri
            recPfd = pfd
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
            val src = audioSource()
            if (!tl) r.setAudioSource(src)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(pfd.fileDescriptor)
            val fps = if (tl) 30 else (fpsRange()?.upper ?: 30)
            r.setVideoEncodingBitRate(bitrate(vs, fps))
            r.setVideoFrameRate(fps)
            if (tl) r.setCaptureRate(1.0 / tlIntervalSec)
            r.setVideoSize(vs.width, vs.height)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (!tl) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(320_000)
                r.setAudioSamplingRate(48_000)
                r.setAudioChannels(if (src == MediaRecorder.AudioSource.UNPROCESSED) 1 else 2)
            }
            r.setOrientationHint(jpegOrientation())
            r.prepare()
            recorder = r
            createSession()
        } catch (e: Exception) {
            msg("Can't start recording: ${e.message}")
            abortRecorder()
        }
    }

    private fun startRecorderNow() {
        try {
            recorder?.start()
            recording = true
            main.post { listener.onRecording(true) }
        } catch (e: Exception) {
            msg("Recorder failed to start: ${e.message}")
            abortRecorder()
        }
    }

    private fun abortRecorder() {
        try { recorder?.release() } catch (e: Exception) { }
        recorder = null
        recording = false
        try { recPfd?.close() } catch (e: Exception) { }
        recPfd = null
        recUri?.let { try { ctx.contentResolver.delete(it, null, null) } catch (e: Exception) { } }
        recUri = null
        main.post { listener.onRecording(false) }
        if (device != null) createSession()
    }

    fun stopRecording() {
        handler?.post { stopRecordingInternal(reopen = true) }
    }

    private fun stopRecordingInternal(reopen: Boolean) {
        val r = recorder ?: return
        recording = false
        recorder = null
        try { session?.stopRepeating() } catch (e: Exception) { }
        var ok = true
        try { r.stop() } catch (e: Exception) { ok = false }
        try { r.release() } catch (e: Exception) { }
        try { recPfd?.close() } catch (e: Exception) { }
        recPfd = null
        val uri = recUri
        recUri = null
        if (uri != null) {
            if (ok) {
                publish(uri)
                val vs = videoSize()
                val note = if (mode == Mode.TIMELAPSE) "Time-lapse ${vs.width}×${vs.height}, 1 frame every ${tlIntervalSec}s"
                else "Video ${vs.width}×${vs.height} @ ${fpsRange()?.upper ?: 30} fps"
                main.post { listener.onSaved(uri, "video/mp4", note) }
            } else {
                try { ctx.contentResolver.delete(uri, null, null) } catch (e: Exception) { }
                msg("Clip too short, nothing saved.")
            }
        }
        main.post { listener.onRecording(false) }
        if (reopen && device != null) createSession()
    }

    // ---- info ----
    fun describe(): String {
        val c = chars ?: return "No camera open."
        val lvl = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL 3"
            else -> "EXTERNAL"
        }
        val sb = StringBuilder()
        sb.append("Lens: ${lens?.label} (id ${lens?.id})\n")
        sb.append("Camera2 hardware level: $lvl\n")
        sb.append("Manual sensor control: ${if (manualSensor) "yes" else "no"}\n")
        sb.append("RAW DNG capture: ${if (rawCapable) "yes" else "no"}\n")
        isoRange?.let { sb.append("ISO: ${it.lower} to ${it.upper}\n") }
        expRange?.let { sb.append("Shutter: ${fmtShutter(it.lower)} to ${fmtShutter(it.upper)}\n") }
        sb.append("Manual focus: ${if (minFocusD > 0f) "yes, down to %.1f cm".format(100f / minFocusD) else "fixed focus"}\n")
        largest(ImageFormat.JPEG)?.let { sb.append("Max JPEG: ${it.width}×${it.height}\n") }
        largest(ImageFormat.RAW_SENSOR)?.let { sb.append("Max RAW: ${it.width}×${it.height}\n") }
        sb.append("60 fps video: ${if (supports60()) "yes" else "no"}\n")
        val z = zoomRange
        sb.append("Zoom: %.1f× to %.1f×\n".format(z.lower, z.upper))
        sb.append("\nAll cameras exposed to apps:\n")
        lenses.forEach { sb.append("  ${it.label} (id ${it.id})\n") }
        return sb.toString()
    }

    private fun msg(m: String) { main.post { listener.onMessage(m) } }

    companion object {
        fun fmtShutter(ns: Long): String {
            val s = ns / 1e9
            return if (s >= 0.4) "%.1fs".format(s) else "1/${(1 / s).roundToInt()}"
        }
    }
}
