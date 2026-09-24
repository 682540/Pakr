package com.webviewapp

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var launchRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_splash)

        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val agreed = prefs.getBoolean("disc_agreed", false)
        launchRunnable = Runnable {
            if (!isFinishing) {
                if (agreed) {
                    startActivity(Intent(this, MainActivity::class.java))
                } else {
                    startActivity(Intent(this, DisclaimerActivity::class.java))
                }
                finish()
            }
        }
        handler.postDelayed(launchRunnable!!, 120)
    }

    override fun onDestroy() {
        launchRunnable?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }
}
