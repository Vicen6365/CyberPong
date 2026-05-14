package com.cyberpong.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.mediaPlaybackRequiresUserGesture = false

            // Force dark mode OFF so canvas renders with correct colors
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                settings.forceDark = WebSettings.FORCE_DARK_OFF
            }

            // No cache for dev - fresh load every time
            settings.cacheMode = WebSettings.LOAD_NO_CACHE

            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            setBackgroundColor(Color.parseColor("#050510"))
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        // Enable remote debugging (dev only)
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
        // Block back - game handles its own flow
    }
}
