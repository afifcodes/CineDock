package com.afif.flix

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Show the splash screen for 1.5 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            val next = if (FirebaseAuth.getInstance().currentUser == null) {
                AuthActivity::class.java
            } else {
                MainActivity::class.java
            }
            val intent = Intent(this@SplashActivity, next)
            startActivity(intent)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, 1500)
    }
}
