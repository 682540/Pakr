package com.webviewapp

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * ================= Pakr 桌宠悬浮球 =================
 *
 * 一个蓝色小圆球，浮在真实桌面（其他应用之上）：
 *   - 单击：弹出选择菜单（回到小凛机 / 隐藏桌宠），选中后才生效；再次单击可收起
 *   - 按住拖动：可移动到任意位置，松手后记住（默认停屏幕右侧）
 *   - 长按：同样弹出选择菜单
 *
 * 依赖 SYSTEM_ALERT_WINDOW 权限；由网页「美化 → 桌宠」开关控制启停。
 * 为了在 App 退到后台后依然常驻，本服务以前台服务方式运行。
 */
class FloatingBallService : Service() {

    private val ui = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var ball: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var menu: View? = null

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false
    private var longPressed = false
    private val longPressRunnable = Runnable { longPressed = true; showMenu() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try { Postman.ensureChannels(this) } catch (_: Throwable) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val n = Postman.buildFloat(this)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    Postman.FGS_FLOAT_ID,
                    n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(Postman.FGS_FLOAT_ID, n)
            }
        } catch (_: Throwable) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (canOverlay()) showBall() else {
                FloatingBall.writeEnabled(this, false)
                stopSelf()
            }
        } catch (_: Throwable) {
            stopSelf()
        }
        return START_STICKY
    }

    private fun canOverlay(): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)

    private fun density(): Float = resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density()).toInt()

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun showBall() {
        if (ball != null) return
        val w = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = w
        val size = dp(52)
        val v = View(this)
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(Color.parseColor("#3d86c4"))
        d.setStroke(dp(2), Color.parseColor("#eaf5ff"))
        v.background = d
        v.alpha = 0.94f
        val p = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        val maxX = (resources.displayMetrics.widthPixels - size).coerceAtLeast(0)
        val maxY = (resources.displayMetrics.heightPixels - size).coerceAtLeast(0)
        val defY = (resources.displayMetrics.heightPixels * 0.45f).toInt().coerceIn(0, maxY)
        val pos = FloatingBall.getPos(this, maxX, defY)
        p.x = pos[0].coerceIn(0, maxX)
        p.y = pos[1].coerceIn(0, maxY)
        attachTouch(v, p, w)
        try { w.addView(v, p) } catch (_: Throwable) {}
        ball = v; lp = p
    }

    private fun attachTouch(v: View, p: WindowManager.LayoutParams, w: WindowManager) {
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longPressed = false
                    moved = false
                    downRawX = e.rawX; downRawY = e.rawY
                    startX = p.x; startY = p.y
                    ui.removeCallbacks(longPressRunnable)
                    ui.postDelayed(longPressRunnable, 480L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (abs(dx) > 12f || abs(dy) > 12f) {
                        if (!moved) { moved = true; hideMenu() }
                        ui.removeCallbacks(longPressRunnable)
                    }
                    if (moved) {
                        val maxX = (resources.displayMetrics.widthPixels - p.width).coerceAtLeast(0)
                        val maxY = (resources.displayMetrics.heightPixels - p.height).coerceAtLeast(0)
                        p.x = (startX + dx).toInt().coerceIn(0, maxX)
                        p.y = (startY + dy).toInt().coerceIn(0, maxY)
                        try { w.updateViewLayout(v, p) } catch (_: Throwable) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ui.removeCallbacks(longPressRunnable)
                    if (!moved && !longPressed) {
                        // 单击：弹出/收起选择菜单（不再直接回前台，选中后才生效）
                        if (menu != null) hideMenu() else showMenu()
                    } else if (moved) {
                        FloatingBall.savePos(this, p.x, p.y)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun bringToFront() {
        try {
            val i = packageManager.getLaunchIntentForPackage(packageName) ?: return
            i.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            startActivity(i)
        } catch (_: Throwable) {}
    }

    private fun showMenu() {
        hideMenu()
        try {
            val w = wm ?: return
            val p0 = lp ?: return
            val m = LinearLayout(this)
            m.orientation = LinearLayout.VERTICAL
            m.setPadding(dp(6), dp(6), dp(6), dp(6))
            val bg = GradientDrawable()
            bg.cornerRadius = dp(14).toFloat()
            bg.setColor(Color.parseColor("#f2f8ff"))
            bg.setStroke(dp(1), Color.parseColor("#bcd9f3"))
            m.background = bg
            m.addView(menuBtn("回到小凛机") { hideMenu(); bringToFront() })
            m.addView(menuBtn("隐藏桌宠") {
                hideMenu()
                FloatingBall.setEnabled(this, false)
                stopSelf()
            })
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            p.gravity = Gravity.TOP or Gravity.START
            val maxX = resources.displayMetrics.widthPixels
            p.x = (p0.x - dp(96)).coerceAtLeast(dp(8)).coerceAtMost((maxX - dp(130)).coerceAtLeast(0))
            p.y = (p0.y + dp(60)).coerceAtMost(resources.displayMetrics.heightPixels - dp(150))
            try { w.addView(m, p) } catch (_: Throwable) { return }
            menu = m
        } catch (_: Throwable) {}
    }

    private fun menuBtn(text: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 14f
        t.setTextColor(Color.parseColor("#1c4a7a"))
        t.setPadding(dp(18), dp(11), dp(18), dp(11))
        t.isClickable = true
        t.isFocusable = false
        t.setOnClickListener { try { onClick() } catch (_: Throwable) {} }
        return t
    }

    private fun hideMenu() {
        val m = menu ?: return
        menu = null
        try { wm?.removeView(m) } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        ui.removeCallbacks(longPressRunnable)
        hideMenu()
        val v = ball
        ball = null
        try { if (v != null) wm?.removeView(v) } catch (_: Throwable) {}
        super.onDestroy()
    }
}

/** 桌宠悬浮球状态与位置存储 */
object FloatingBall {
    private const val PREFS = "pakr_floatball"
    private const val KEY_ON = "on"
    private const val KEY_POS = "pos"

    fun isEnabled(ctx: Context): Boolean = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)
    } catch (_: Throwable) { false }

    fun writeEnabled(ctx: Context, on: Boolean) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ON, on).apply()
        } catch (_: Throwable) {}
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        writeEnabled(ctx, on)
        if (on) start(ctx) else stop(ctx)
    }

    fun start(ctx: Context) {
        try {
            val i = Intent(ctx, FloatingBallService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        } catch (_: Throwable) {}
    }

    fun stop(ctx: Context) {
        try { ctx.stopService(Intent(ctx, FloatingBallService::class.java)) } catch (_: Throwable) {}
    }

    fun getPos(ctx: Context, defX: Int, defY: Int): IntArray = try {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_POS, null)
        if (s == null) intArrayOf(defX, defY)
        else {
            val a = s.split(",")
            intArrayOf(a[0].trim().toInt(), a[1].trim().toInt())
        }
    } catch (_: Throwable) { intArrayOf(defX, defY) }

    fun savePos(ctx: Context, x: Int, y: Int) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_POS, x.toString() + "," + y.toString()).apply()
        } catch (_: Throwable) {}
    }
}
