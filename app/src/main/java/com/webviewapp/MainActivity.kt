package com.webviewapp

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.GestureDetector
import android.view.MotionEvent
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: TopProgressBar
    private lateinit var overlay: View
    private lateinit var spinner: IOSSpinnerView
    private lateinit var loadingText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var overlayVisible = false

    private val dotsFrames = arrayOf("", ".", "..", "...")
    private var dotsIndex = 0
    private val dotsRunnable = object : Runnable {
        override fun run() {
            loadingText.text = "加载中${dotsFrames[dotsIndex]}"
            dotsIndex = (dotsIndex + 1) % dotsFrames.size
            handler.postDelayed(this, 500)
        }
    }

    private val timeoutRunnable  = Runnable { hideOverlay() }
    // 用命名 Runnable 管理延迟隐藏，确保可被 removeCallbacks 取消
    private val delayHideRunnable = Runnable { hideOverlay() }

    // 边缘手势阈值
    private val EDGE_SWIPE_MIN_X = 80f   // 水平位移最小值 px
    private val EDGE_SWIPE_MAX_Y = 120f  // 垂直位移最大值 px（避免误触）
    private val EDGE_SWIPE_MIN_V = 200f  // 最小速度 px/s

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // 强制亮色模式，防止系统暗色主题影响 WebView 渲染
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        setContentView(R.layout.activity_main)
        // 申请通知权限（Android 13+），否则后台消息通知会被系统静默丢弃
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001
                    )
                }
            }
        } catch (_: Throwable) {}
        webView     = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        overlay     = findViewById(R.id.overlay)
        spinner     = findViewById(R.id.spinner)
        loadingText = findViewById(R.id.loadingText)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        swipeRefresh.setColorSchemeColors(
            android.graphics.Color.parseColor("#6366F1")
        )
        // 触发距离设为 140dp（接近 Chrome），避免轻划误触
        val density = resources.displayMetrics.density
        val triggerDp = 120
        try {
            val field = androidx.swiperefreshlayout.widget.SwipeRefreshLayout::class.java
                .getDeclaredField("mTotalDragDistance")
            field.isAccessible = true
            field.setFloat(swipeRefresh, triggerDp * density)
        } catch (_: Exception) {}
        swipeRefresh.setProgressViewOffset(false, 0, (triggerDp * density).toInt())
        swipeRefresh.setOnRefreshListener {
            forceShowOverlay()
            // 稍等 50ms 再 reload，确保 overlay 已完全显示后才清空页面
            handler.postDelayed({ webView.reload() }, 50)
        }
        showOverlay()
        setupWebView()
        setupEdgeSwipe()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // 防止加载过程中白屏：设置 WebView 背景与 overlay 一致
        webView.setBackgroundColor(android.graphics.Color.WHITE)
        webView.setBackgroundColor(android.graphics.Color.WHITE)
        webView.settings.apply {
            javaScriptEnabled                = true
            domStorageEnabled                = true
            databaseEnabled                  = true
            useWideViewPort                  = true
            loadWithOverviewMode             = true
            setSupportZoom(false)
            builtInZoomControls              = false
            displayZoomControls              = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode                 = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowContentAccess               = true
            allowFileAccess                  = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
        }
        // 开启 Service Worker（支持 PWA 类网站）
        try {
            android.webkit.ServiceWorkerController.getInstance().serviceWorkerWebSettings?.apply {
                allowContentAccess = true
                allowFileAccess    = true
            }
            android.webkit.ServiceWorkerController.getInstance()
                .setServiceWorkerClient(object : android.webkit.ServiceWorkerClient() {
                    override fun shouldInterceptRequest(request: WebResourceRequest) = null
                })
        } catch (_: Exception) {}
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                // 每次新页面/刷新必须强制显示 overlay，不受上次状态影响
                handler.removeCallbacks(delayHideRunnable)
                forceShowOverlay()
            }

            override fun onPageFinished(view: WebView, url: String) {
                swipeRefresh.isRefreshing = false
                fetchThemeColor(view)
                handler.removeCallbacks(delayHideRunnable)
                // 用 JS 检测页面真正渲染完成（两帧后），再隐藏 overlay
                // 超时兜底：1200ms 强制隐藏
                handler.postDelayed(delayHideRunnable, 1200)
                view.evaluateJavascript("""
                    (function(){
                        function done(){
                            requestAnimationFrame(function(){
                                requestAnimationFrame(function(){
                                    try{ window._pakrBridge.onPageReady(); }catch(e){}
                                });
                            });
                        }
                        if(document.readyState==='complete'){ done(); }
                        else { window.addEventListener('load', done, {once:true}); }
                    })();
                """.trimIndent(), null)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                // 非 http/https 协议（intent://, mailto:, tel: 等）交给系统处理
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) {}
                    return true
                }
                // http/https 全部在 WebView 内处理，包括 OAuth/SSO 重定向
                return false
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    swipeRefresh.isRefreshing = false
                    handler.removeCallbacks(delayHideRunnable)
                    hideOverlay()
                    view.loadData(errorHtml(), "text/html", "UTF-8")
                }
            }

            // 修复：SSL 证书错误默认会取消加载白屏，直接放行
            @Suppress("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                handler.proceed()
            }

            // 修复：HTTP 4xx/5xx 错误显示友好页面
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: android.webkit.WebResourceResponse) {
                if (request.isForMainFrame && (errorResponse.statusCode >= 400)) {
                    swipeRefresh.isRefreshing = false
                    handler.removeCallbacks(delayHideRunnable)
                    hideOverlay()
                    view.loadData(errorHtml(), "text/html", "UTF-8")
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.setProgress(newProgress)
                // 新页面开始加载时（progress 重置为低值）补充触发 showOverlay
                if (newProgress <= 5) showOverlay()
                // overlay 隐藏统一由 onPageFinished 负责，这里不再提前触发
            }
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, msg: android.os.Message
            ): Boolean {
                val href = view.handler.obtainMessage()
                view.requestFocusNodeHref(href)
                val url = href.data?.getString("url")
                if (!url.isNullOrEmpty()) view.loadUrl(url)
                else {
                    val child = WebView(view.context)
                    child.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(v: WebView, u: String, f: android.graphics.Bitmap?) {
                            view.loadUrl(u)
                            child.destroy()
                        }
                    }
                    val transport = msg.obj as? WebView.WebViewTransport
                    transport?.webView = child
                    msg.sendToTarget()
                }
                return true
            }
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams
            ): Boolean {
                fileChooserCallbackRef?.onReceiveValue(null)
                fileChooserCallbackRef = filePathCallback
                // 上传前切换为桌面UA，确保ChatGPT不拦截上传请求
                webView.settings.userAgentString = DESKTOP_UA
                try {
                    // 创建相机临时文件
                    val photoFile = java.io.File(
                        cacheDir,
                        "webview_uploads/camera_${System.currentTimeMillis()}.jpg"
                    ).also { it.parentFile?.mkdirs() }
                    cameraImageUri = androidx.core.content.FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.fileprovider",
                        photoFile
                    )
                    val cameraIntent = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraImageUri)
                        addFlags(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                    val fileIntent = fileChooserParams.createIntent()
                    val chooser = android.content.Intent.createChooser(fileIntent, "选择图片").apply {
                        putExtra(android.content.Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
                    }
                    startActivityForResult(chooser, FILE_CHOOSER_REQUEST)
                } catch (e: Exception) {
                    filePathCallback.onReceiveValue(null)
                    fileChooserCallbackRef = null
                }
                return true
            }
        }
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            try {
                val uri = Uri.parse(url)
                val filename = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
                val req = DownloadManager.Request(uri).apply {
                    setMimeType(mimetype)
                    addRequestHeader("User-Agent", userAgent)
                    setDescription("正在下载...")
                    setTitle(filename)
                    allowScanningByMediaScanner()
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                }
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(req)
                android.widget.Toast.makeText(this, "开始下载：$filename", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
            }
        }
        // 键盘弹出适配：全屏模式下 adjustResize 失效，手动监听 IME Insets 调整容器高度
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(swipeRefresh) { view, insets ->
            val imeInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            val lp = view.layoutParams as android.widget.FrameLayout.LayoutParams
            lp.bottomMargin = imeInsets.bottom
            view.layoutParams = lp
            // WebView padding 清零，用 marginBottom 控制
            webView.setPadding(0, 0, 0, 0)
            insets
        }

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onThemeColor(hex: String) {
                try {
                    val color = android.graphics.Color.parseColor(hex)
                    runOnUiThread { progressBar.setBarColor(color) }
                } catch (e: Exception) {}
            }
        }, "ThemeBridge")
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onPageReady() {
                // JS 确认页面两帧后真正渲染完成，取消超时兜底并立即隐藏 overlay
                handler.post {
                    handler.removeCallbacks(delayHideRunnable)
                    hideOverlay()
                }
            }

            // ===== 保活增强：网页在后台收到消息时弹系统通知 =====
            @JavascriptInterface
            fun pushNotice(title: String, body: String) {
                Postman.push(this@MainActivity.applicationContext, title, body)
            }

            // 网页可据此决定：就地显示提示条，还是让 App 弹系统通知
            @JavascriptInterface
            fun isForeground(): Boolean = AppState.foreground

            // 网页设置项里可主动调用，引导关闭电池优化
            @JavascriptInterface
            fun openBatterySettings() {
                handler.post { Battery.prompt(this@MainActivity) }
            }
        }, "_pakrBridge")
        // UA：移动版 Chrome（无 wv 标识），上传时临时切桌面UA
        webView.settings.userAgentString = MOBILE_UA
        // 【手势流畅度修复】网页是固定布局（内部自己滚动），WebView 自身 scrollY 恒为 0，
        // 于是 SwipeRefreshLayout 一直处于启用状态：手指每移动一次它都要向 WebView 询问
        // “能不能往上滚”（代价很高的调用），并且手势只要带一点向下成分就会被它整段抢走，
        // 表现就是滑动卡顿、像断触。这里直接关掉下拉刷新。
        swipeRefresh.isEnabled = false
        // 去掉过度滚动（回弹/光晕）带来的额外合成开销
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        // 明确走硬件加速合成
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.loadUrl(APP_URL)
    }

    private fun fetchThemeColor(view: WebView) {
        val js = """
            (function() {
                var m = document.querySelector('meta[name="theme-color"]');
                if (m && m.content) { ThemeBridge.onThemeColor(m.content); return; }
                var el = document.elementFromPoint(window.innerWidth/2, 1);
                if (el) {
                    var bg = getComputedStyle(el).backgroundColor;
                    var r = bg.match(/rgba?\((\d+),(\d+),(\d+)/);
                    if (r) ThemeBridge.onThemeColor(
                        '#' + [r[1],r[2],r[3]].map(function(x){
                            return ('0' + parseInt(x).toString(16)).slice(-2);
                        }).join('')
                    );
                }
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private fun showOverlay() {
        if (overlayVisible) return
        overlayVisible = true
        overlay.animate().cancel()
        // 壳自带的白遮罩与顶部进度条一律不显示，加载过程交给网页自己的动画
        overlay.visibility = View.GONE
        progressBar.visibility = View.GONE
        // spinner.start()  // 已隐藏壳自带的转圈，避免与网页加载动画抢帧
        dotsIndex = 0
        handler.removeCallbacks(dotsRunnable)
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, 30_000L)
    }

    // 边缘滑动手势：左边缘右滑=后退，右边缘左滑=前进
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupEdgeSwipe() {
        val edgeLeft  = findViewById<View>(R.id.edgeLeft)
        val edgeRight = findViewById<View>(R.id.edgeRight)

        fun makeGesture(onSwipeRight: (() -> Unit)? = null, onSwipeLeft: (() -> Unit)? = null)
            = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true
                override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                    val dx = e2.x - (e1?.x ?: e2.x)
                    val dy = e2.y - (e1?.y ?: e2.y)
                    if (abs(dx) < EDGE_SWIPE_MIN_X) return false
                    if (abs(dy) > EDGE_SWIPE_MAX_Y) return false
                    if (abs(vx) < EDGE_SWIPE_MIN_V) return false
                    return if (dx > 0) { onSwipeRight?.invoke(); onSwipeRight != null }
                    else               { onSwipeLeft?.invoke();  onSwipeLeft  != null }
                }
            })

        val leftGesture  = makeGesture(onSwipeRight = {
            if (webView.canGoBack()) {
                forceShowOverlay()
                handler.postDelayed({ webView.goBack() }, 50)
                handler.postDelayed({ if (overlayVisible) hideOverlay() }, 1500)
            }
        })
        val rightGesture = makeGesture(onSwipeLeft = {
            if (webView.canGoForward()) {
                forceShowOverlay()
                handler.postDelayed({ webView.goForward() }, 50)
                handler.postDelayed({ if (overlayVisible) hideOverlay() }, 1500)
            }
        })

        edgeLeft.setOnTouchListener  { _, e -> leftGesture.onTouchEvent(e) }
        edgeRight.setOnTouchListener { _, e -> rightGesture.onTouchEvent(e) }
    }

    // 强制显示 overlay，不受 overlayVisible 守卫限制（用于 reload 等场景）
    private fun forceShowOverlay() {
        overlayVisible = false
        showOverlay()
    }

    private fun hideOverlay() {
        if (!overlayVisible) return
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(dotsRunnable)
        overlayVisible = false
        overlay.animate().cancel()
        overlay.animate().alpha(0f).setDuration(150).withEndAction {
            // 守卫：动画期间如果 showOverlay 再次被触发，不强制隐藏
            if (!overlayVisible) {
                overlay.visibility = View.GONE
                spinner.stop()
                progressBar.visibility = View.GONE
            }
        }.start()
    }

    private fun errorHtml() = """
        <html><body style="margin:0;display:flex;align-items:center;justify-content:center;
        height:100vh;font-family:sans-serif;flex-direction:column;background:#fff;color:#333;">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#999" stroke-width="1.5">
          <circle cx="12" cy="12" r="10"/>
          <line x1="12" y1="8" x2="12" y2="12"/>
          <line x1="12" y1="16" x2="12.01" y2="16"/>
        </svg>
        <p style="margin-top:16px;font-size:15px;">网络连接失败</p>
        <button onclick="location.reload()"
          style="margin-top:12px;padding:10px 24px;border:none;border-radius:999px;
          background:#000;color:#fff;font-size:14px;cursor:pointer;">重试</button>
        </body></html>
    """.trimIndent()

    private var backPressedTime = 0L
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            val now = System.currentTimeMillis()
            if (now - backPressedTime < 2000) {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            } else {
                backPressedTime = now
                android.widget.Toast.makeText(this, "再按一次退出", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
        // 切回 App 时不主动操作 overlay，让 WebView 自然恢复
    }

    override fun onPause() {
        super.onPause()
        // ===== 保活增强：不再暂停 WebView =====
        // 原实现调用 pauseTimers()，它会全局冻结所有 WebView 的 JS 定时器，
        // 导致 App 一进后台页面就"死掉"，消息回调与通知全部失效。
        // 保活的前提是后台继续跑，所以这里只保留 Cookie 持久化。
        CookieManager.getInstance().flush()
    }

    override fun onStop() {
        super.onStop()
        CookieManager.getInstance().flush()  // 强杀时也持久化 Cookie
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        // 清理文件选择回调，防止内存泄漏
        fileChooserCallbackRef?.onReceiveValue(null)
        fileChooserCallbackRef = null
        // 先从父布局移除再 destroy，防止 WebView 内存泄漏
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    private var fileChooserCallbackRef: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            val results: Array<Uri>? = if (resultCode == RESULT_OK) {
                when {
                    // 相机拍照：data 为 null 或 data.data 为 null
                    (data == null || data.data == null) && cameraImageUri != null -> {
                        arrayOf(cameraImageUri!!)
                    }
                    // 多选文件
                    data?.clipData != null -> {
                        val clip = data.clipData!!
                        Array(clip.itemCount) { i ->
                            clip.getItemAt(i).uri.also { uri ->
                                try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                            }
                        }
                    }
                    // 单选文件
                    data?.data != null -> {
                        val uri = data.data!!
                        try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                        arrayOf(uri)
                    }
                    else -> null
                }
            } else null
            fileChooserCallbackRef?.onReceiveValue(results)
            fileChooserCallbackRef = null
            cameraImageUri = null
            // 上传完成后恢复移动UA
            webView.settings.userAgentString = MOBILE_UA
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }



    companion object {
        const val APP_URL   = "{{APP_URL}}"
        const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.6422.165 Mobile Safari/537.36"
        const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
        private const val FILE_CHOOSER_REQUEST = 1001
    }
}
