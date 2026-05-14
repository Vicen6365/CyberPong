package com.cyberpong.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force dark mode off so the game looks correct
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            applicationContext.getTheme().applyStyle(
                android.R.style.Theme_DeviceDefault_DayNight_NoActionBar, true
            )
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true

            // Cache disabled for dev - fresh load every time
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE

            // Important: force dark mode OFF so canvas renders correctly
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                settings.isForceDark = android.webkit.WebSettings.FORCE_DARK_OFF
            }

            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()

            setBackgroundColor(Color.parseColor("#050510"))

            // Enable hardware layer
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        // Enable remote debugging
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        setContentView(webView)
        webView.loadUrl("file:///android_asset/game.html")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }
    }

    override fun onBackPressed() {
        // Block back button - game handles its own flow
    }
}
