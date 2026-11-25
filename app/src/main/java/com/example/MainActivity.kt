package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import kotlin.math.ceil

/**
 * Portal Siswa (Kiosk CBT). Antarmuka & logika ujian = assets/index.html (dari aplikasi_client_siswa.html).
 * Lapisan native: kunci layar (screen pinning), anti-screenshot, paksa volume >= 70%,
 * deteksi keluar aplikasi, tombol Back diarahkan ke dialog PIN Keluar.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val START_URL = "https://$HOST/assets/index.html"
        private const val MIN_VOLUME_RATIO = 0.70
    }

    private lateinit var webView: WebView
    private lateinit var audio: AudioManager
    private var examActive = false
    private var leftApp = false
    private var examStartTime = 0L
    private var pendingRequest: PermissionRequest? = null
    private val handler = Handler(Looper.getMainLooper())

    private val volumeGuard = object : Runnable {
        override fun run() {
            if (!examActive) return
            enforceVolume()
            handler.postDelayed(this, 1500)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val req = pendingRequest ?: return@registerForActivityResult
            pendingRequest = null
            val ok = req.resources.filter { r -> androidPermission(r)?.let { hasPermission(it) } == true }
            if (ok.isEmpty()) req.deny() else req.grant(ok.toTypedArray())
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        volumeControlStream = AudioManager.STREAM_MUSIC

        webView = WebView(this)
        setContentView(webView)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.addJavascriptInterface(Bridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
                request ?: return null
                return loader.shouldInterceptRequest(request.url)
            }

            // Halaman utama tidak boleh berpindah ke situs lain
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean = request != null && request.isForMainFrame && request.url.host != HOST
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request ?: return
                runOnUiThread { handlePermission(request) }
            }

            override fun onJsConfirm(
                view: WebView?, url: String?, message: String?, result: JsResult?
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }
                    .setNegativeButton("Batal") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsAlert(
                view: WebView?, url: String?, message: String?, result: JsResult?
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.confirm() }
                    .show()
                return true
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (examActive) {
                    webView.evaluateJavascript("if(typeof bukaModalKeluar==='function')bukaModalKeluar();", null)
                } else {
                    finish()
                }
            }
        })

        webView.loadUrl(START_URL)
    }

    // ---------- Izin kamera / mikrofon untuk WebView ----------
    private fun androidPermission(resource: String): String? = when (resource) {
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
        else -> null
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun handlePermission(request: PermissionRequest) {
        if (request.origin.host != HOST) { request.deny(); return }
        val needed = request.resources.mapNotNull { androidPermission(it) }.distinct()
        if (needed.isEmpty()) { request.deny(); return }
        val missing = needed.filter { !hasPermission(it) }
        if (missing.isEmpty()) {
            request.grant(request.resources.filter { androidPermission(it) != null }.toTypedArray())
        } else {
            pendingRequest?.deny()
            pendingRequest = request
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // ---------- Mode kiosk ----------
    private fun setExamActive(active: Boolean) {
        if (examActive == active) return
        examActive = active
        val ctrl = WindowInsetsControllerCompat(window, window.decorView)
        if (active) {
            examStartTime = System.currentTimeMillis()
            leftApp = false
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
            try { startLockTask() } catch (_: Exception) {}
            handler.post(volumeGuard)
        } else {
            handler.removeCallbacks(volumeGuard)
            try { stopLockTask() } catch (_: Exception) {}
            ctrl.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(
                WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
            leftApp = false
        }
    }

    private fun enforceVolume() {
        try {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val min = ceil(max * MIN_VOLUME_RATIO).toInt()
            if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) < min) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, min, 0)
            }
        } catch (_: Exception) {}
    }

    // ---------- Deteksi hanya saat siswa berpindah aplikasi atau berusaha keluar ----------
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Terpanggil saat siswa sengaja menekan tombol Home, Recent Apps, atau berpindah aplikasi
        if (examActive && System.currentTimeMillis() - examStartTime > 5000) {
            leftApp = true
        }
    }

    override fun onStop() {
        super.onStop()
        // Aplikasi masuk background (berpindah ke aplikasi lain atau home screen)
        if (examActive && !isChangingConfigurations && System.currentTimeMillis() - examStartTime > 5000) {
            leftApp = true
        }
    }

    override fun onResume() {
        super.onResume()
        reportIfReturned()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Hanya laporkan pelanggaran jika siswa sebelumnya benar-benar keluar/berpindah aplikasi (leftApp)
        if (hasFocus) {
            reportIfReturned()
        }
    }

    private fun reportIfReturned() {
        if (examActive && leftApp) {
            leftApp = false
            handler.postDelayed({
                webView.evaluateJavascript(
                    "if(typeof handleExamViolation==='function'&&isExamActive)handleExamViolation();", null
                )
            }, 600)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView.destroy()
        super.onDestroy()
    }

    // ---------- Jembatan JavaScript <-> Android ----------
    private inner class Bridge {
        @JavascriptInterface
        fun onExamState(active: Boolean) {
            runOnUiThread { setExamActive(active) }
        }

        @JavascriptInterface
        fun getClipboard(): String {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return ""
            if (clip.itemCount == 0) return ""
            return clip.getItemAt(0).coerceToText(this@MainActivity)?.toString() ?: ""
        }
    }
}
