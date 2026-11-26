package com.nevo.htmlrunner

import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadFromUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0F1115.toInt())
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val openBtn = Button(this).apply {
            text = "פתח קובץ HTML"
            setOnClickListener { picker.launch(arrayOf("text/html", "*/*")) }
        }
        val homeBtn = Button(this).apply {
            text = "מריץ פייתון"
            setOnClickListener { loadAsset() }
        }
        bar.addView(openBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(homeBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView, request: WebResourceRequest
                ): WebResourceResponse? = cdnCached(request) ?: super.shouldInterceptRequest(view, request)
            }
            webChromeClient = WebChromeClient()
        }

        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        val data = intent?.data
        if (data != null) loadFromUri(data) else loadAsset()
    }

    private fun showHtml(html: String) {
        web.loadDataWithBaseURL("https://appassets.local/", html, "text/html", "utf-8", null)
    }

    private fun loadAsset() {
        val html = assets.open("python-runner.html").bufferedReader().use { it.readText() }
        showHtml(html)
    }

    private fun loadFromUri(uri: Uri) {
        try {
            val html = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            showHtml(html)
        } catch (e: Exception) {
            Toast.makeText(this, "לא הצלחתי לפתוח: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Downloads files from cdn.jsdelivr.net natively, caches them on the phone
    // (so later runs work offline) and serves them to the page with CORS allowed.
    private fun cdnCached(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (url.host != "cdn.jsdelivr.net") return null
        return try {
            val name = url.lastPathSegment ?: "file"
            val f = File(filesDir, "cdn/" + Integer.toHexString(url.toString().hashCode()) + "_" + name)
            if (!f.exists()) {
                f.parentFile?.mkdirs()
                val tmp = File(f.path + ".tmp")
                val conn = URL(url.toString()).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                tmp.renameTo(f)
            }
            val mime = when {
                name.endsWith(".js") -> "text/javascript"
                name.endsWith(".wasm") -> "application/wasm"
                name.endsWith(".json") -> "application/json"
                name.endsWith(".zip") -> "application/zip"
                else -> "application/octet-stream"
            }
            WebResourceResponse(mime, null, 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), f.inputStream())
        } catch (e: Exception) {
            null
        }
    }
}
