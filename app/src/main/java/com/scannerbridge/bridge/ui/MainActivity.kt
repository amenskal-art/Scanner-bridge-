package com.scannerbridge.bridge.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.scannerbridge.bridge.CrashApp
import com.scannerbridge.bridge.R
import com.scannerbridge.bridge.databinding.ActivityMainBinding
import com.scannerbridge.bridge.server.FrameBridge
import com.scannerbridge.bridge.server.MjpegServer
import com.scannerbridge.bridge.server.StreamForegroundService
import com.scannerbridge.bridge.util.NetworkUtils
import com.scannerbridge.bridge.util.PairingClient
import kotlin.concurrent.thread

/**
 * Flow (PC shows the QR, this phone's webcam reads it):
 *  1. Plug in the USB-C webcam -> live feed shows.
 *  2. Tap "Scan PC Code". The webcam frames are scanned for the PC's QR.
 *  3. On decode: parse {pc_ip, port, token}, start the MJPEG server, then POST
 *     this phone's own stream address back to the PC gate.
 *  4. PC auto-connects to this phone's stream.
 */
class MainActivity : AppCompatActivity(),
    CameraBridgeFragment.Callbacks, MjpegServer.Listener {

    private lateinit var binding: ActivityMainBinding

    private val streamPort = 8080
    private var server: MjpegServer? = null
    private var frameBridge: FrameBridge? = null
    private var cameraFragment: CameraBridgeFragment? = null

    private var cameraReady = false
    private var streaming = false
    private var scanning = false
    private var pairedPcName = ""

    private val ui = Handler(Looper.getMainLooper())

    private val permReq = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { attachCameraFragment() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.actionButton.setOnClickListener { toggleScanning() }
        binding.stopButton.setOnClickListener { stopEverything() }
        setupCameraControls()
        setupFullscreen()

        // If we crashed last time, show the reason on screen instead of
        // disappearing silently.
        val lastCrash = CrashApp.readAndClear(application)
        if (lastCrash != null) {
            showCrashDialog(lastCrash)
        }

        requestRuntimePermissions()
        startStatsTicker()
        updateUi()
    }

    private fun showCrashDialog(text: String) {
        try {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Previous crash details")
                .setMessage(text.take(4000))
                .setPositiveButton("Copy") { _, _ ->
                    val cb = getSystemService(Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    cb.setPrimaryClip(
                        android.content.ClipData.newPlainText("crash", text))
                    toast("Crash log copied")
                }
                .setNegativeButton("Dismiss", null)
                .show()
        } catch (_: Throwable) {}
    }

    // ---------------- permissions ----------------
    private fun requestRuntimePermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.CAMERA
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.isNotEmpty()) permReq.launch(needed.toTypedArray())
        else attachCameraFragment()
    }

    private fun attachCameraFragment() {
        if (cameraFragment != null) return
        // Post to the main looper so we never commit a fragment transaction
        // during the permission-result callback window (which throws on
        // Android 14+). Also guard the whole thing so an AUSBC init failure
        // shows a message instead of silently killing the app.
        ui.post {
            if (cameraFragment != null) return@post
            if (isFinishing || isDestroyed) return@post
            try {
                val frag = CameraBridgeFragment.newInstance().apply {
                    callbacks = this@MainActivity
                }
                cameraFragment = frag
                supportFragmentManager.beginTransaction()
                    .replace(binding.previewContainer.id, frag)
                    .commitAllowingStateLoss()
            } catch (t: Throwable) {
                cameraFragment = null
                binding.scanHint.text =
                    "Scanner engine failed to start: ${t.message ?: t.javaClass.simpleName}"
                toast("Scanner init failed (see message)")
            }
        }
    }

    // ---------------- scan control ----------------
    // Launches the phone-camera QR scanner and handles its result.
    private val qrScanLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val text = result.data?.getStringExtra(QrScanActivity.EXTRA_QR)
            if (!text.isNullOrBlank()) handleScannedQr(text)
        }
    }

    private fun toggleScanning() {
        if (streaming) { toast("Already paired and streaming"); return }
        // Scan the PC's QR with the PHONE camera (not the webcam). The webcam
        // does not need to be connected yet — it's only used for streaming
        // after pairing.
        qrScanLauncher.launch(android.content.Intent(this, QrScanActivity::class.java))
    }

    /** Called with the text decoded by the phone-camera scanner. */
    private fun handleScannedQr(text: String) {
        val target = PairingClient.parsePcQr(text) ?: run {
            toast("That QR isn't a Scanner Pro pairing code.")
            return
        }
        binding.scanHint.text = "Code read \u2014 connecting to PC ${target.ip}..."
        updateUi()
        pairWithPc(target)
    }

    private fun pairWithPc(target: PairingClient.PcTarget) {
        val phoneIp = NetworkUtils.getLocalIpAddress()
        if (phoneIp == null) {
            runOnUiThread { toast("No Wi-Fi. Join the same network as the PC.") }
            return
        }

        // Start the local MJPEG server first so it's live when the PC connects.
        startStreaming(phoneIp)

        // Then call the PC gate back with our address (off the UI thread).
        thread {
            val name = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            val ok = PairingClient.sendAddress(
                target = target,
                phoneIp = phoneIp,
                streamPort = streamPort,
                deviceName = name
            )
            runOnUiThread {
                if (ok) {
                    pairedPcName = target.ip
                    binding.scanHint.text = "Paired. The PC is now receiving your scanner."
                    toast("Paired with PC")
                } else {
                    binding.scanHint.text =
                        "Couldn't reach the PC gate at ${target.ip}:${target.port}. " +
                        "Same Wi-Fi? Try again."
                    toast("Pairing callback failed")
                }
                updateUi()
            }
        }
    }

    // ---------------- streaming ----------------
    private fun startStreaming(phoneIp: String) {
        if (streaming) return
        val srv = MjpegServer(streamPort).apply { listener = this@MainActivity }
        val bridge = FrameBridge(srv)
        server = srv
        frameBridge = bridge
        cameraFragment?.frameBridge = bridge
        srv.start()
        streaming = true

        val url = NetworkUtils.buildStreamUrl(phoneIp, streamPort)
        // On Android 14+ starting a FGS can throw if the app isn't in a valid
        // state. The stream works without it; the service only keeps things
        // alive with the screen off. So never let it crash the app.
        try {
            val svc = Intent(this, StreamForegroundService::class.java)
                .putExtra(StreamForegroundService.EXTRA_URL, url)
            ContextCompat.startForegroundService(this, svc)
        } catch (t: Throwable) {
            // streaming still works in the foreground; just log to the UI
            runOnUiThread { binding.scanHint.text =
                "Streaming (note: background keep-alive unavailable on this OS)." }
        }

        runOnUiThread {
            updateUi()
        }
    }

    private fun stopEverything() {
        scanning = false
        cameraFragment?.frameBridge = null
        server?.stop()
        server = null
        frameBridge = null
        streaming = false
        pairedPcName = ""
        stopService(Intent(this, StreamForegroundService::class.java))
        binding.scanHint.text = "Tap Scan PC Code, then aim the scanner at the QR on your PC."
        updateUi()
        toast("Stopped")
    }

    // ---------------- UI ----------------
    private fun updateUi() {
        binding.actionButton.text = when {
            streaming -> "Paired \u2713"
            scanning -> "Scanning... (tap to stop)"
            else -> "Scan PC Code"
        }
        binding.actionButton.isEnabled = !streaming
        binding.stopButton.visibility = if (streaming || scanning) View.VISIBLE else View.GONE

        val online = streaming
        binding.statusDot.setBackgroundResource(
            if (online) R.drawable.dot_online else R.drawable.dot_offline)
        binding.statusText.text = when {
            streaming -> "Streaming"
            scanning -> "Scanning"
            cameraReady -> "Scanner ready"
            else -> "No scanner"
        }
    }

    private fun startStatsTicker() {
        ui.postDelayed(object : Runnable {
            override fun run() {
                val fps = frameBridge?.fpsCounter?.fps ?: 0
                binding.fpsBadge.text = "$fps fps"
                binding.statFps.text = fps.toString()
                binding.statClients.text = (server?.connectedClients ?: 0).toString()
                frameBridge?.let { fb ->
                    if (cameraReady && fb.frameWidth > 0)
                        binding.statRes.text = "${fb.frameWidth}\u00d7${fb.frameHeight}"
                }
                ui.postDelayed(this, 1000)
            }
        }, 1000)
    }

    // ---------------- scanner controls ----------------
    private fun setupCameraControls() {
        // Collapse/expand
        binding.controlsHeader.setOnClickListener {
            val body = binding.controlsBody
            val showing = body.visibility == View.VISIBLE
            body.visibility = if (showing) View.GONE else View.VISIBLE
            binding.controlsToggle.text = if (showing) "Show" else "Hide"
        }

        fun seek(sb: android.widget.SeekBar, apply: (Int) -> Unit) {
            sb.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) apply(value)
                }
                override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
            })
        }

        // SeekBars are 0..100; the fragment maps each value into the control's
        // real device range. Brightness drives gamma (+gain lift) since UVC has
        // no setBrightness.
        seek(binding.sbBrightness) { cameraFragment?.ctlSetBrightness(it) }
        seek(binding.sbContrast)   { cameraFragment?.ctlSetContrast(it) }
        seek(binding.sbGain)       { cameraFragment?.ctlSetGain(it) }
        seek(binding.sbSharpness)  { cameraFragment?.ctlSetSharpness(it) }
        seek(binding.sbZoom)       { cameraFragment?.ctlSetZoom(it) }

        binding.cbAutoWb.setOnCheckedChangeListener { _, on ->
            cameraFragment?.ctlSetAutoWhiteBalance(on)
        }
    }

    /**
     * Push the current UI slider/checkbox values to the camera. UVC control
     * setters only take effect once the camera is open, so the initial slider
     * positions are dead until we apply them here (called from onCameraOpened).
     * This is the other half of why brightness "did nothing": nothing ever
     * sent the starting values to the device.
     */
    private fun applyInitialCameraControls() {
        val frag = cameraFragment ?: return
        try {
            frag.ctlSetAutoWhiteBalance(binding.cbAutoWb.isChecked)
            frag.ctlSetBrightness(binding.sbBrightness.progress)
            frag.ctlSetContrast(binding.sbContrast.progress)
            frag.ctlSetGain(binding.sbGain.progress)
            frag.ctlSetSharpness(binding.sbSharpness.progress)
            frag.ctlSetZoom(binding.sbZoom.progress)
        } catch (_: Throwable) { }
    }

    // ---------------- fullscreen / landscape ----------------
    private var isFullscreen = false
    // Remembers where the controls card normally lives so we can put it back.
    private var controlsCardParent: ViewGroup? = null
    private var controlsCardIndex: Int = -1
    // Same for the live-preview container.
    private var previewParent: ViewGroup? = null
    private var previewIndex: Int = -1
    private var previewOriginalParams: ViewGroup.LayoutParams? = null

    private fun setupFullscreen() {
        binding.fullscreenButton.setOnClickListener { enterFullscreen() }
        binding.fsExitButton.setOnClickListener { exitFullscreen() }
        binding.fsToggleControlsButton.setOnClickListener { toggleFullscreenControls() }
    }

    private fun enterFullscreen() {
        if (isFullscreen) return
        isFullscreen = true

        // Landscape + immersive (hide status/nav bars), like YouTube.
        requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemBars(true)

        // Move the live preview into the full-screen overlay so it can fill the
        // entire display edge-to-edge (no card padding / black borders).
        val preview = binding.previewContainer
        previewParent = preview.parent as? ViewGroup
        previewIndex = previewParent?.indexOfChild(preview) ?: -1
        previewOriginalParams = preview.layoutParams
        previewParent?.removeView(preview)
        preview.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        binding.fullscreenPreviewHolder.addView(preview)

        // Move the scanner-controls card into the floating overlay panel so it
        // can be shown/hidden over the video. Make its body always visible
        // there (the collapse header is hidden in fullscreen).
        val card = binding.controlsCard
        controlsCardParent = card.parent as? ViewGroup
        controlsCardIndex = controlsCardParent?.indexOfChild(card) ?: -1
        controlsCardParent?.removeView(card)
        binding.fsControlsHost.addView(card)
        binding.controlsBody.visibility = View.VISIBLE
        binding.controlsHeader.visibility = View.GONE
        // Start with the panel hidden; the Controls button reveals it.
        binding.fsControlsScroll.visibility = View.GONE
        binding.fsToggleControlsButton.text = "Controls"

        binding.contentScroll.visibility = View.GONE
        binding.fullscreenOverlay.visibility = View.VISIBLE
    }

    private fun exitFullscreen() {
        if (!isFullscreen) return
        isFullscreen = false

        requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        hideSystemBars(false)

        binding.fullscreenOverlay.visibility = View.GONE
        binding.contentScroll.visibility = View.VISIBLE

        // Put the preview back into its original card slot. Restore the exact
        // LinearLayout params it had (240dp height) so the portrait card looks
        // unchanged.
        val preview = binding.previewContainer
        (preview.parent as? ViewGroup)?.removeView(preview)
        val restoredParams = previewOriginalParams
            ?: ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (240 * resources.displayMetrics.density).toInt()
            )
        preview.layoutParams = restoredParams
        val pParent = previewParent
        if (pParent != null) {
            val idx = previewIndex.coerceIn(0, pParent.childCount)
            pParent.addView(preview, idx)
        }

        // Put the controls card back where it was.
        val card = binding.controlsCard
        (card.parent as? ViewGroup)?.removeView(card)
        binding.controlsHeader.visibility = View.VISIBLE
        // Restore the collapsed/expanded state used in portrait.
        binding.controlsBody.visibility = View.GONE
        binding.controlsToggle.text = "Show"
        val parent = controlsCardParent
        if (parent != null) {
            val idx = controlsCardIndex.coerceIn(0, parent.childCount)
            parent.addView(card, idx)
        }
        binding.fsControlsScroll.visibility = View.GONE
    }

    private fun toggleFullscreenControls() {
        val panel = binding.fsControlsScroll
        val showing = panel.visibility == View.VISIBLE
        panel.visibility = if (showing) View.GONE else View.VISIBLE
        binding.fsToggleControlsButton.text = if (showing) "Controls" else "Hide"
    }

    private fun hideSystemBars(hide: Boolean) {
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (hide) {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
            controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onBackPressed() {
        if (isFullscreen) {
            exitFullscreen()
            return
        }
        super.onBackPressed()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------------- camera callbacks ----------------
    override fun onCameraOpened(width: Int, height: Int) {
        cameraReady = true
        // If we're already streaming (paired before the webcam opened), make
        // sure this freshly-opened camera feeds the live bridge so real frames
        // replace the placeholder on the PC.
        if (frameBridge != null) {
            cameraFragment?.frameBridge = frameBridge
        }
        // UVC control setters only work once the camera is open. Push the
        // current slider values now so brightness etc. actually take effect.
        applyInitialCameraControls()
        runOnUiThread {
            binding.previewPlaceholder.visibility = View.GONE
            binding.statRes.text = "${width}\u00d7${height}"
            updateUi()
        }
    }

    override fun onCameraClosed() {
        cameraReady = false
        runOnUiThread {
            binding.previewPlaceholder.visibility = View.VISIBLE
            binding.statRes.text = "--"
            updateUi()
        }
    }

    // ---------------- server callbacks ----------------
    override fun onClientCountChanged(count: Int) {
        runOnUiThread { binding.statClients.text = count.toString() }
    }

    override fun onServerError(message: String) {
        runOnUiThread { toast("Server error: $message") }
    }

    override fun onDestroy() {
        if (streaming || scanning) stopEverything()
        super.onDestroy()
    }
}
