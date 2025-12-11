package com.example.pannsonnx

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Intent
import android.os.Bundle
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import androidx.appcompat.app.AppCompatActivity
import com.example.pannsonnx.databinding.ActivitySplashBinding

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val FADE_DURATION = 2000L // 1 second for fade in and out

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Animate the logo to fade in
        val fadeIn = AlphaAnimation(0f, 1f).apply {
            duration = FADE_DURATION
            fillAfter = true
        }

        binding.logoImageView.startAnimation(fadeIn)

        fadeIn.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation?) {}

            override fun onAnimationEnd(animation: Animation?) {
                // After fade in, wait for a brief moment then fade out
                binding.logoImageView.animate()
                    .alpha(0f)
                    .setDuration(FADE_DURATION)
                    .setStartDelay(500) // Small delay before fading out
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            // After fade out, navigate to the main activity
                            val intent = Intent(this@SplashActivity, MainActivity::class.java)
                            startActivity(intent)
                            finish()
                        }
                    })
            }

            override fun onAnimationRepeat(animation: Animation?) {}
        })
    }
}


