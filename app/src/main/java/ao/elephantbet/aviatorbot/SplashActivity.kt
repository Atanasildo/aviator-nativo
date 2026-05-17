package ao.elephantbet.aviatorbot

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.TranslateAnimation
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Layout root
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0a0a0f"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(root)

        // Ícone do avião
        val planeText = TextView(this).apply {
            text = "✈️"
            textSize = 72f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(24) }
        }

        // Título AVIATOR
        val titleText = TextView(this).apply {
            text = "NELLA"
            textSize = 44f
            setTextColor(Color.parseColor("#ef4444"))
            gravity = Gravity.CENTER
            letterSpacing = 0.15f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        // Subtítulo BOT
        val subtitleText = TextView(this).apply {
            text = "BOT"
            textSize = 18f
            setTextColor(Color.parseColor("#64748b"))
            gravity = Gravity.CENTER
            letterSpacing = 0.4f
        }

        root.addView(planeText)
        root.addView(titleText)
        root.addView(subtitleText)

        // Animação fade in do avião
        val fadeIn = AlphaAnimation(0f, 1f).apply { duration = 900 }
        planeText.startAnimation(fadeIn)

        // Animação slide up + fade para título e subtítulo
        val slideUp = AnimationSet(true).apply {
            addAnimation(TranslateAnimation(0f, 0f, dp(60).toFloat(), 0f).apply { duration = 800 })
            addAnimation(AlphaAnimation(0f, 1f).apply { duration = 800 })
            startOffset = 400
        }
        titleText.startAnimation(slideUp)
        subtitleText.startAnimation(slideUp)

        // Navegar para MainActivity após 2.5s
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, 2500)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
