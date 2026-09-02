package com.mirsal.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent screenshots and most screen-recording capture of the app.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        webView = WebView(this)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler(
                "/assets/",
                WebViewAssetLoader.AssetsPathHandler(this)
            )
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        webView.webChromeClient = WebChromeClient()

        webView.webViewClient = object : androidx.webkit.WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                url: String
            ): android.webkit.WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(android.net.Uri.parse(url))
            }
        }

        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
        setContentView(webView)
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
