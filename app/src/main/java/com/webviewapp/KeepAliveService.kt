package com.webviewapp

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * ================= Pakr 保活增强层 =================
 *
 * 目标：网页切到后台后 JS 继续运行；收到消息时弹系统通知；进程不被系统回收。
 *
 * 设计原则：本文件独立完成全部初始化，MainActivity 只需两处改动——
 *   1. 在 _pakrBridge 上暴露 pushNotice(title, body)
 *   2. onPause 里删掉 webView.pauseTimers()（否则后台 JS 被全局冻结）
 *
 * 构建时 .github/workflows/build.yml 会自动把本文件改包名并搬到用户填的包目录下，
 * 无需任何额外配置。
 */
class PakrApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Postman.ensureChannels(this)
        AppState.attach(this)
    }
}

/**
 * 前台状态跟踪。用 started/stopped 计数而不是 resumed/paused，
 * 这样权限弹窗、拍照、文件选择这类"只暂停不停止"的场景不会被误判成切后台。
 */
object AppState {
    @Volatile
    var foreground: Boolean = true
        private set

    private var attached = false

    fun attach(app: Application) {
        if (attached) return
        attached = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var startedCount = 0

            override fun onActivityStarted(activity: Activity) {
                startedCount++
                foreground = true
                if (KeepAlive.isEnabled(activity)) {
                    KeepAlive.start(activity)
                    Battery.promptOnce(activity)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                startedCount--
                if (startedCount <= 0) {
                    startedCount = 0
                    foreground = false
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}

/** 前台服务的启动入口，任何异常都吞掉——保活失败也绝不能拖垮主流程 */
object KeepAlive {
    private const val PREFS = "pakr_keepalive"
    private const val KEY_ENABLED = "keepalive_enabled"

    /** 保活开关，默认开（保持原有基座行为），网页设置里可关 */
    fun isEnabled(ctx: Context): Boolean = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
    } catch (_: Throwable) {
        true
    }

    /** 网页设置项调用：写偏好并立刻生效 */
    fun setEnabled(ctx: Context, on: Boolean) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, on).apply()
        } catch (_: Throwable) {
        }
        if (on) start(ctx) else stop(ctx)
    }

    fun stop(ctx: Context) {
        try {
            ctx.stopService(Intent(ctx, KeepAliveService::class.java))
        } catch (_: Throwable) {
        }
    }

    fun start(ctx: Context) {
        try {
            val i = Intent(ctx, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        } catch (_: Throwable) {
        }
    }
}

/**
 * 常驻前台服务。通知走 IMPORTANCE_MIN 通道，在通知栏会被折叠成一条细线，
 * 只有"正在后台运行"这一行小字，不会像普通通知那样骚扰。
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Postman.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val n = Postman.buildForeground(this)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    Postman.FGS_ID,
                    n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(Postman.FGS_ID, n)
            }
        } catch (_: Throwable) {
            // 前台服务被系统拒绝（权限/策略），直接退出，不影响网页本身
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /** 被划出最近任务后尽力自救一次 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        KeepAlive.start(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

/** 通知中心：负责渠道、前台通知、消息通知 */
object Postman {
    const val FGS_ID = 1001
    private const val MSG_ID_BASE = 2001

    private const val CHANNEL_ALIVE = "pakr_alive"
    private const val CHANNEL_MSG = "pakr_msg"

    private val idGen = AtomicInteger(0)
    @Volatile
    private var channelsReady = false

    @Suppress("NewApi")
    fun ensureChannels(ctx: Context) {
        if (channelsReady) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            channelsReady = true
            return
        }
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALIVE,
                    "后台运行",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                    description = "保持网页连接，接收新消息"
                }
            )

            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_MSG,
                    "消息通知",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "应用在后台时的角色回复提醒"
                }
            )

            channelsReady = true
        } catch (_: Throwable) {
        }
    }

    private fun contentIntent(ctx: Context): PendingIntent? {
        return try {
            val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val flags = if (Build.VERSION.SDK_INT >= 23) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            PendingIntent.getActivity(ctx, 0, i, flags)
        } catch (_: Throwable) {
            null
        }
    }

    fun buildForeground(ctx: Context): Notification {
        val b = NotificationCompat.Builder(ctx, CHANNEL_ALIVE)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("正在后台运行")
            .setContentText("保持连接，随时接收新消息")
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        contentIntent(ctx)?.let { b.setContentIntent(it) }
        return b.build()
    }

    /**
     * 消息通知。前台时直接丢弃——页面内部自己会显示提示条，
     * 不需要系统通知来打扰。
     */
    fun push(ctx: Context, title: String, body: String) {
        if (AppState.foreground) return
        try {
            ensureChannels(ctx)

            if (Build.VERSION.SDK_INT >= 33) {
                if (ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }
            }

            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val b = NotificationCompat.Builder(ctx, CHANNEL_MSG)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            contentIntent(ctx)?.let { b.setContentIntent(it) }

            nm.notify(MSG_ID_BASE + (idGen.incrementAndGet() % 500), b.build())
        } catch (_: Throwable) {
        }
    }
}

/** 电池优化白名单引导。荣耀/华为系的省电策略对后台进程极不友好，这一步很关键 */
object Battery {

    fun promptOnce(activity: Activity) {
        try {
            val sp = activity.getSharedPreferences("pakr_keepalive", Context.MODE_PRIVATE)
            if (sp.getBoolean("battery_prompted", false)) return
            sp.edit().putBoolean("battery_prompted", true).apply()
        } catch (_: Throwable) {
            return
        }
        activity.window.decorView.postDelayed({
            try {
                prompt(activity)
            } catch (_: Throwable) {
            }
        }, 9000)
    }

    fun prompt(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(activity.packageName)) {
                    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    i.data = android.net.Uri.parse("package:" + activity.packageName)
                    activity.startActivity(i)
                    return
                }
            } catch (_: Throwable) {
            }
        }
        try {
            activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Throwable) {
        }
    }
}
