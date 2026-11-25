package app.noortilawa.quran

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorOverlay: LinearLayout
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var wasScreenFacingUp = false
    private var lastFlipEventAt = 0L
    private lateinit var assetLoader: WebViewAssetLoader
    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        pendingWebPermissionRequest?.let { req ->
            val audioGranted = results[Manifest.permission.RECORD_AUDIO] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (audioGranted) {
                req.grant(req.resources)
            } else {
                req.deny()
            }
            pendingWebPermissionRequest = null
        }
        pendingGeoCallback?.let { cb ->
            val locGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            cb.invoke(pendingGeoOrigin, locGranted, false)
            pendingGeoOrigin = null
            pendingGeoCallback = null
        }
    }

    companion object {
        private const val TARGET_URL = "https://appassets.androidplatform.net/index.html?mode=app"
        private const val ALLOWED_HOST = "appassets.androidplatform.net"
        private const val THEME_HEX = "#061412"
        private const val APP_TITLE = "نور التلاوة"
        private const val FONT_FAMILY = "Amiri"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        val themeColorInt = Color.parseColor(THEME_HEX)
        window.statusBarColor = themeColorInt
        window.navigationBarColor = themeColorInt
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColorInt)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val titleBar = TextView(this).apply {
            text = APP_TITLE
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(themeColorInt)
            setPadding(48, 28, 48, 28)
            gravity = Gravity.CENTER_VERTICAL
        }
        mainColumn.addView(titleBar)

        val contentFrame = FrameLayout(this).apply {
            setBackgroundColor(themeColorInt)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        webView = WebView(this).apply {
            setBackgroundColor(themeColorInt)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        configureWebSettings(webView.settings)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                8,
                Gravity.TOP
            )
        }

        errorOverlay = buildOfflineOverlay(themeColorInt)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress in 1..99) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                val granted = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    callback?.invoke(origin, true, false)
                } else {
                    pendingGeoOrigin = origin
                    pendingGeoCallback = callback
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }
            override fun onPermissionRequest(request: PermissionRequest?) {
                val req = request ?: return
                runOnUiThread {
                    val needsAudio = req.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    if (!needsAudio) {
                        req.deny()
                        return@runOnUiThread
                    }
                    val granted = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        req.grant(req.resources)
                    } else {
                        pendingWebPermissionRequest = req
                        permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    }
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                if (url.host?.lowercase() == "appassets.androidplatform.net") {
                    val response = assetLoader.shouldInterceptRequest(url)
                    return fixMimeType(url.toString(), response)
                }
                return null
            }
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase() ?: ""
                if (scheme != "http" && scheme != "https") {
                    openExternalUri(uri)
                    return true
                }
                val host = uri.host?.lowercase() ?: ""
                if (host == ALLOWED_HOST || host.endsWith(".$ALLOWED_HOST") || host == "appassets.androidplatform.net") {
                    return false
                }
                openExternalUri(uri)
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    errorOverlay.visibility = View.VISIBLE
                }
            }
        }

        contentFrame.addView(webView)
        contentFrame.addView(progressBar)
        contentFrame.addView(errorOverlay)
        mainColumn.addView(contentFrame)
        setContentView(mainColumn)

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(TARGET_URL)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebSettings(settings: WebSettings) {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadsImagesAutomatically = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setGeolocationEnabled(true)
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
    }

    private fun buildOfflineOverlay(backgroundColorInt: Int): LinearLayout {
        val textColor = Color.WHITE
        val subTextColor = Color.parseColor("#B7D4CC")

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(backgroundColorInt)
            setPadding(64, 64, 64, 64)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val title = TextView(this).apply {
            text = "تعذر الاتصال بالإنترنت"
            textSize = 20f
            setTextColor(textColor)
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "يرجى التحقق من الشبكة ثم المحاولة مرة أخرى لمتابعة التلاوة."
            textSize = 14f
            setTextColor(subTextColor)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 48)
        }

        val retryBtn = Button(this).apply {
            text = "إعادة المحاولة"
            setOnClickListener {
                container.visibility = View.GONE
                progressBar.visibility = View.VISIBLE
                webView.loadUrl(TARGET_URL)
            }
        }

        container.addView(title)
        container.addView(subtitle)
        container.addView(retryBtn)
        return container
    }

    private fun openExternalUri(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            // No handler installed on device
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        sensorManager?.unregisterListener(this)
        // Keep WebView audio/radio stream active while navigating or screen locks
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        if (e.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val z = e.values[2]
        if (z > 2.5f) {
            wasScreenFacingUp = true
        } else if (wasScreenFacingUp && z < -7.0f) {
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastFlipEventAt > 1200L) {
                lastFlipEventAt = nowMs
                wasScreenFacingUp = false
                runOnUiThread {
                    webView.evaluateJavascript(
                        "window.dispatchEvent(new CustomEvent('noor-phone-flipped'));",
                        null
                    )
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    private fun fixMimeType(urlString: String, response: WebResourceResponse?): WebResourceResponse? {
        if (response == null) return null
        val cleanUrl = urlString.substringBefore('?').substringBefore('#').lowercase()
        val mime = when {
            cleanUrl.endsWith(".js") || cleanUrl.endsWith(".mjs") -> "application/javascript"
            cleanUrl.endsWith(".css") -> "text/css"
            cleanUrl.endsWith(".html") || cleanUrl.endsWith(".htm") -> "text/html"
            cleanUrl.endsWith(".json") -> "application/json"
            cleanUrl.endsWith(".png") -> "image/png"
            cleanUrl.endsWith(".jpg") || cleanUrl.endsWith(".jpeg") || cleanUrl.endsWith(".jfif") -> "image/jpeg"
            cleanUrl.endsWith(".svg") -> "image/svg+xml"
            cleanUrl.endsWith(".woff2") -> "font/woff2"
            cleanUrl.endsWith(".woff") -> "font/woff"
            cleanUrl.endsWith(".ttf") -> "font/ttf"
            cleanUrl.endsWith(".ico") -> "image/x-icon"
            else -> null
        }
        if (mime != null) {
            response.mimeType = mime
        }
        return response
    }

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        webView.destroy()
        super.onDestroy()
    }
}
