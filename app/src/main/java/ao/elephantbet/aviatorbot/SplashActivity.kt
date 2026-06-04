package ao.elephantbet.aviatorbot

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
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
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    private val particleHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor = Color.parseColor("#06060f")
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
        root.setBackgroundColor(Color.parseColor("#06060f"))

        // Cores NEXUS — roxo escuro + ciano
        val colorPrimary  = Color.parseColor("#7c3aed")  // roxo
        val colorAccent   = Color.parseColor("#06b6d4")  // ciano
        val colorGlow     = Color.parseColor("#4f46e5")  // índigo

        // Partículas
        val particles = Array(60) {
            floatArrayOf(
                (Math.random() * W).toFloat(),
                (Math.random() * H).toFloat(),
                (Math.random() * 1.2 - 0.6).toFloat(),
                (-Math.random() * 1.0 - 0.1).toFloat(),
                (Math.random() * 0.4 + 0.1).toFloat(),
                (Math.random() * 2.5 + 0.5).toFloat(),
                (Math.random() * 3).toFloat(),
                (Math.random()).toFloat()
            )
        }
        val pColors = intArrayOf(colorPrimary, colorAccent, colorGlow, Color.parseColor("#a78bfa"))

        // Hexágonos de fundo (estética tech)
        val hexCenters = Array(6) {
            floatArrayOf(
                (Math.random() * W).toFloat(),
                (Math.random() * H).toFloat(),
                (Math.random() * 60 + 30).toFloat()
            )
        }

        val bgView = object : View(this) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: Canvas) {
                // Fundo gradiente radial escuro
                paint.shader = RadialGradient(
                    W * 0.5f, H * 0.4f, H * 0.8f,
                    intArrayOf(Color.parseColor("#0d0b1e"), Color.parseColor("#06060f")),
                    null, Shader.TileMode.CLAMP
                )
                c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
                paint.shader = null

                // Hexágonos subtis
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(1f)
                for (h in hexCenters) {
                    paint.color = Color.parseColor("#1a1040")
                    drawHex(c, h[0], h[1], h[2], paint)
                }
                paint.style = Paint.Style.FILL

                // Grid de pontos
                paint.color = Color.parseColor("#0f0a2a")
                var gx = 0f
                while (gx < W) {
                    var gy = 0f
                    while (gy < H) {
                        c.drawCircle(gx, gy, dp(0.8f), paint)
                        gy += dp(28f)
                    }
                    gx += dp(28f)
                }

                // Partículas
                for (p in particles) {
                    paint.color = pColors[p[6].toInt().coerceIn(0, 3)]
                    paint.alpha = (p[4] * 255).toInt().coerceIn(0, 255)
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

        // Círculo de glow atrás do N
        val glowView = object : View(this) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: Canvas) {
                val cx = width / 2f; val cy = height / 2f
                p.shader = RadialGradient(cx, cy, cx,
                    intArrayOf(Color.parseColor("#447c3aed"), Color.parseColor("#2206b6d4"), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, cx, p)
            }
        }.apply {
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(dpi(180f), dpi(180f)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }

        // Letra N grande
        val txtN = TextView(this).apply {
            text = "N"
            textSize = 96f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            alpha = 0f
            scaleX = 0.3f; scaleY = 0.3f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = -dpi(140f) }
        }

        // Nome NEXUS
        val txtName = TextView(this).apply {
            text = "NEXUS"
            textSize = 44f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            letterSpacing = 0.35f
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }

        // Linha decorativa gradiente roxo-ciano
        val linha = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.TRANSPARENT, colorPrimary, colorAccent, Color.TRANSPARENT)
            )
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(dpi(220f), dpi(2f)).apply {
                topMargin = dpi(10f); bottomMargin = dpi(10f)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        // Subtítulo
        val txtSub = TextView(this).apply {
            text = "A  V  I  A  T  O  R   ·   B  O  T"
            textSize = 11f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            setTextColor(Color.parseColor("#7c3aed"))
            gravity = Gravity.CENTER
            letterSpacing = 0.3f
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }

        // Tagline
        val txtTag = TextView(this).apply {
            text = "Inteligência Artificial · Sinais em Tempo Real"
            textSize = 10f
            setTextColor(Color.parseColor("#4a5568"))
            gravity = Gravity.CENTER
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpi(28f); gravity = Gravity.CENTER_HORIZONTAL }
        }

        center.addView(glowView)
        center.addView(txtN)
        center.addView(txtName)
        center.addView(linha)
        center.addView(txtSub)
        center.addView(txtTag)

        root.addView(center, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // ── Animações ──────────────────────────────────────────────
        // Glow + N entram juntos
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(glowView, "alpha", 0f, 1f).setDuration(800),
                ObjectAnimator.ofFloat(txtN, "alpha", 0f, 1f).setDuration(700),
                ObjectAnimator.ofFloat(txtN, "scaleX", 0.3f, 1f).setDuration(800),
                ObjectAnimator.ofFloat(txtN, "scaleY", 0.3f, 1f).setDuration(800)
            )
            startDelay = 400; start()
        }

        // NEXUS aparece
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(txtName, "alpha", 0f, 1f).setDuration(600),
                ObjectAnimator.ofFloat(txtName, "translationY", dp(20f), 0f).setDuration(600)
            )
            startDelay = 1300; start()
        }

        // Linha e subtítulo
        AnimatorSet().apply {
            playSequentially(
                ObjectAnimator.ofFloat(linha, "alpha", 0f, 1f).setDuration(400),
                ObjectAnimator.ofFloat(txtSub, "alpha", 0f, 1f).setDuration(500)
            )
            startDelay = 1900; start()
        }

        // Tagline
        ObjectAnimator.ofFloat(txtTag, "alpha", 0f, 1f).apply {
            duration = 500; startDelay = 2700; start()
        }

        // Pulsar no N
        ObjectAnimator.ofFloat(txtN, "alpha", 1f, 0.5f, 1f).apply {
            duration = 900; repeatCount = 1; startDelay = 2400; start()
        }

        // Partículas
        val tick = object : Runnable {
            override fun run() {
                for (p in particles) {
                    p[0] += p[2]; p[1] += p[3]; p[7] += 0.007f
                    p[4] = (0.4f * Math.sin(p[7] * Math.PI).toFloat()).coerceIn(0f, 0.6f)
                    if (p[1] < -10f || p[7] >= 1f) {
                        p[0] = (Math.random() * W).toFloat()
                        p[1] = H + 5f; p[7] = 0f
                        p[3] = (-Math.random() * 1.0 - 0.1).toFloat()
                    }
                }
                bgView.invalidate()
                particleHandler.postDelayed(this, 16)
            }
        }
        particleHandler.post(tick)

        return root
    }

    private fun drawHex(c: Canvas, cx: Float, cy: Float, r: Float, p: Paint) {
        val path = Path()
        for (i in 0..5) {
            val angle = Math.toRadians((60 * i - 30).toDouble())
            val x = cx + r * Math.cos(angle).toFloat()
            val y = cy + r * Math.sin(angle).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawPath(path, p)
    }

    override fun onDestroy() {
        super.onDestroy()
        particleHandler.removeCallbacksAndMessages(null)
    }
}
