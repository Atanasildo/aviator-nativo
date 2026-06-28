package ao.elephantbet.aviatorbot

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    private val particleHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor = Color.parseColor("#000000")
        setContentView(buildSplash())
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, 3800)
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density)
    private fun dpi(v: Float) = dp(v).toInt()

    private fun buildSplash(): FrameLayout {
        val W = resources.displayMetrics.widthPixels
        val H = resources.displayMetrics.heightPixels

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        val colorPrimary = Color.parseColor("#00ff41")  // verde matrix
        val colorAccent  = Color.parseColor("#00cc33")  // verde escuro
        val colorDim     = Color.parseColor("#003311")  // verde muito escuro

        // Partículas estilo matrix
        val particles = Array(80) {
            floatArrayOf(
                (Math.random() * W).toFloat(),
                (Math.random() * H).toFloat(),
                (Math.random() * 0.4 - 0.2).toFloat(),
                (Math.random() * 2.0 + 0.5).toFloat(),  // cai para baixo
                (Math.random() * 0.6 + 0.1).toFloat(),
                (Math.random() * 2.0 + 1.0).toFloat(),
                (Math.random() * 2).toFloat(),
                (Math.random()).toFloat()
            )
        }

        // Linhas verticais estilo terminal
        val lines = Array(20) {
            floatArrayOf(
                (Math.random() * W).toFloat(),
                (Math.random() * H).toFloat(),
                (Math.random() * H * 0.3 + 20).toFloat(),
                (Math.random() * 2.0 + 1.0).toFloat()
            )
        }

        val bgView = object : View(this) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: Canvas) {
                c.drawColor(Color.BLACK)

                // Grid de linhas horizontais subtis (estilo terminal)
                paint.color = Color.parseColor("#0a1a0a")
                paint.strokeWidth = 1f
                var gy = 0f
                while (gy < H) {
                    c.drawLine(0f, gy, W.toFloat(), gy, paint)
                    gy += dp(18f)
                }

                // Linhas verticais matrix (chuva de código)
                paint.strokeWidth = dp(1.5f)
                for (l in lines) {
                    val grad = LinearGradient(
                        l[0], l[1] - l[2], l[0], l[1],
                        intArrayOf(Color.TRANSPARENT, colorDim, colorAccent),
                        null, Shader.TileMode.CLAMP
                    )
                    paint.shader = grad
                    c.drawLine(l[0], l[1] - l[2], l[0], l[1], paint)
                }
                paint.shader = null

                // Partículas verdes
                paint.style = Paint.Style.FILL
                for (p in particles) {
                    val alpha = (p[4] * 200).toInt().coerceIn(0, 200)
                    paint.color = if (p[6].toInt() == 0) colorPrimary else colorAccent
                    paint.alpha = alpha
                    c.drawCircle(p[0], p[1], p[5], paint)
                }
                paint.alpha = 255
            }
        }
        root.addView(bgView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        // Layout central
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        // Glow verde atrás da letra C
        val glowView = object : View(this) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: Canvas) {
                val cx = width / 2f; val cy = height / 2f
                p.shader = RadialGradient(cx, cy, cx,
                    intArrayOf(Color.parseColor("#2200ff41"), Color.parseColor("#0800cc33"), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, cx, p)
            }
        }.apply {
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(dpi(200f), dpi(200f)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }

        // Letra C grande
        val txtC = TextView(this).apply {
            text = "C"
            textSize = 96f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#00ff41"))
            gravity = Gravity.CENTER
            alpha = 0f
            scaleX = 0.3f; scaleY = 0.3f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = -dpi(140f) }
        }

        // Nome ALTUS
        val txtName = TextView(this).apply {
            text = "ALTUS"
            textSize = 42f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#00ff41"))
            gravity = Gravity.CENTER
            letterSpacing = 0.4f
            alpha = 0f
            setShadowLayer(dp(8f), 0f, 0f, Color.parseColor("#00ff41"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }

        // Linha decorativa verde
        val linha = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#00ff41"), Color.parseColor("#00cc33"), Color.TRANSPARENT)
            )
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(dpi(240f), dpi(2f)).apply {
                topMargin = dpi(10f); bottomMargin = dpi(10f)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        // Subtítulo estilo terminal
        val txtSub = TextView(this).apply {
            text = "> AVIATOR · SIGNAL ENGINE"
            textSize = 10f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            setTextColor(Color.parseColor("#00cc33"))
            gravity = Gravity.CENTER
            letterSpacing = 0.2f
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }

        // Tagline
        val txtTag = TextView(this).apply {
            text = "[ AI · REAL-TIME · ENCRYPTED ]"
            textSize = 9f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            setTextColor(Color.parseColor("#005522"))
            gravity = Gravity.CENTER
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpi(28f); gravity = Gravity.CENTER_HORIZONTAL }
        }

        center.addView(glowView)
        center.addView(txtC)
        center.addView(txtName)
        center.addView(linha)
        center.addView(txtSub)
        center.addView(txtTag)

        root.addView(center, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Animações
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(glowView, "alpha", 0f, 1f).setDuration(800),
                ObjectAnimator.ofFloat(txtC, "alpha", 0f, 1f).setDuration(700),
                ObjectAnimator.ofFloat(txtC, "scaleX", 0.3f, 1f).setDuration(800),
                ObjectAnimator.ofFloat(txtC, "scaleY", 0.3f, 1f).setDuration(800)
            )
            startDelay = 400; start()
        }

        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(txtName, "alpha", 0f, 1f).setDuration(600),
                ObjectAnimator.ofFloat(txtName, "translationY", dp(20f), 0f).setDuration(600)
            )
            startDelay = 1300; start()
        }

        AnimatorSet().apply {
            playSequentially(
                ObjectAnimator.ofFloat(linha, "alpha", 0f, 1f).setDuration(400),
                ObjectAnimator.ofFloat(txtSub, "alpha", 0f, 1f).setDuration(500)
            )
            startDelay = 1900; start()
        }

        ObjectAnimator.ofFloat(txtTag, "alpha", 0f, 0.7f).apply {
            duration = 500; startDelay = 2700; start()
        }

        // Pulsar no C (estilo cursor terminal)
        ObjectAnimator.ofFloat(txtC, "alpha", 1f, 0.3f, 1f).apply {
            duration = 600; repeatCount = 2; startDelay = 2200; start()
        }

        // Animação partículas e linhas matrix
        val tick = object : Runnable {
            override fun run() {
                for (p in particles) {
                    p[0] += p[2]; p[1] += p[3]; p[7] += 0.008f
                    p[4] = (0.5f * Math.sin(p[7] * Math.PI).toFloat()).coerceIn(0f, 0.7f)
                    if (p[1] > H + 10f || p[7] >= 1f) {
                        p[0] = (Math.random() * W).toFloat()
                        p[1] = -5f; p[7] = 0f
                        p[3] = (Math.random() * 2.0 + 0.5).toFloat()
                    }
                }
                for (l in lines) {
                    l[1] += l[3]
                    if (l[1] - l[2] > H) { l[1] = -l[2] }
                }
                bgView.invalidate()
                particleHandler.postDelayed(this, 16)
            }
        }
        particleHandler.post(tick)

        return root
    }

    override fun onDestroy() {
        super.onDestroy()
        particleHandler.removeCallbacksAndMessages(null)
    }
}
