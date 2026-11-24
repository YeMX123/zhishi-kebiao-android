package com.zhishi.kebiao

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

open class MainActivity : AppCompatActivity() {
    protected lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uris = WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data)
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    protected open fun startUrl(): String = "file:///android_asset/prod/entry.html"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.addJavascriptInterface(WebBridge(this), "AndroidBridge")
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                wv: WebView?, cb: ValueCallback<Array<Uri>>?, params: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                fileLauncher.launch(Intent.createChooser(intent, "选择文件"))
                return true
            }
        }
        webView.loadUrl(startUrl())
    }

    override fun onResume() {
        super.onResume()
        webView.evaluateJavascript("if(typeof loadCustomBackground==='function')loadCustomBackground();if(typeof checkPendingImport==='function')checkPendingImport();", null)
    }
}
