package ao.elephantbet.aviatorbot

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
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
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.statusBarColor = Color.parseColor("#0a0a0f")
        setContentView(buildSplash())
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, 3500)
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density)
    private fun dpi(v: Float) = dp(v).toInt()

    private fun buildSplash(): FrameLayout {
        val W = resources.displayMetrics.widthPixels
        val H = resources.displayMetrics.heightPixels

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0a0a0f"))

        // Partículas: [x, y, vx, vy, alpha, size, colorIdx, life]
        val particles = Array(50) {
            floatArrayOf(
                (Math.random() * W).toFloat(),
                (Math.random() * H).toFloat(),
                (Math.random() * 1.4 - 0.7).toFloat(),
                (-Math.random() * 1.3 - 0.2).toFloat(),
                (Math.random() * 0.5 + 0.1).toFloat(),
                (Math.random() * 3 + 1).toFloat(),
                (Math.random() * 4).toFloat(),
                (Math.random()).toFloat()
            )
        }
        val pColors = intArrayOf(
            Color.parseColor("#ef4444"),
            Color.parseColor("#ff6600"),
            Color.parseColor("#fbbf24"),
            Color.parseColor("#ffffff")
        )

        var planeX = -dp(60f)
        var planeY = H * 0.75f

        // Canvas principal
        val bgView = object : View(this) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: Canvas) {
                // Fundo gradiente radial
                paint.shader = RadialGradient(
                    W / 2f, H / 2f, H / 1.4f,
                    intArrayOf(Color.parseColor("#1a0505"), Color.parseColor("#0a0a0f")),
                    null, Shader.TileMode.CLAMP
                )
                c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
                paint.shader = null

                // Rastro e avião
                if (planeX > 0f) {
                    val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG)
                    trailPaint.style = Paint.Style.STROKE
                    trailPaint.strokeWidth = dp(2.5f)
                    trailPaint.shader = LinearGradient(
                        0f, H * 0.75f, planeX, planeY,
                        Color.TRANSPARENT, Color.parseColor("#ef4444"), Shader.TileMode.CLAMP
                    )
                    val path = Path()
                    path.moveTo(0f, H * 0.75f)
                    path.quadTo(planeX * 0.4f, H * 0.68f, planeX, planeY)
                    c.drawPath(path, trailPaint)

                    // Brilho
                    paint.shader = RadialGradient(
                        planeX, planeY, dp(65f),
                        intArrayOf(
                            Color.parseColor("#55ef4444"),
                            Color.parseColor("#25ff6600"),
                            Color.TRANSPARENT
                        ),
                        floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
                    )
                    c.drawCircle(planeX, planeY, dp(65f), paint)
                    paint.shader = null

                    // Avião
                    c.save()
                    c.translate(planeX, planeY)
                    val angle = -22f + (planeX / W) * 22f
                    c.rotate(angle)
                    drawPlane(c, dp(36f), paint)
                    c.restore()
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
        root.addView(bgView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Layout central com textos
        val center = LinearLayout(this)
        center.orientation = LinearLayout.VERTICAL
        center.gravity = Gravity.CENTER

        val txtAviator = TextView(this).apply {
            text = "AVIATOR"
            textSize = 52f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            letterSpacing = 0.2f
            alpha = 0f
            scaleX = 0.5f
            scaleY = 0.5f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val linha = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.parseColor("#ef4444"),
                    Color.parseColor("#ff6600"),
                    Color.TRANSPARENT
                )
            )
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(dpi(200f), dpi(2f)).apply {
                topMargin = dpi(8f)
                bottomMargin = dpi(8f)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        val txtBot = TextView(this).apply {
            text = "B O T"
            textSize = 18f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(Color.parseColor("#ef4444"))
            gravity = Gravity.CENTER
            letterSpacing = 0.5f
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val txtTag = TextView(this).apply {
            text = "Inteligência Artificial · Sinais em Tempo Real"
            textSize = 10f
            setTextColor(Color.parseColor("#64748b"))
            gravity = Gravity.CENTER
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpi(24f) }
        }

        center.addView(txtAviator)
        center.addView(linha)
        center.addView(txtBot)
        center.addView(txtTag)

        root.addView(center, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Animação do avião
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800
            interpolator = DecelerateInterpolator(2f)
            startDelay = 300
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                planeX = -dp(60f) + t * (W * 0.72f + dp(60f))
                planeY = H * 0.75f - t * (H * 0.3f)
                bgView.invalidate()
            }
            start()
        }

        // Título
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(txtAviator, "alpha", 0f, 1f).setDuration(600),
                ObjectAnimator.ofFloat(txtAviator, "scaleX", 0.5f, 1f).setDuration(700),
                ObjectAnimator.ofFloat(txtAviator, "scaleY", 0.5f, 1f).setDuration(700)
            )
            startDelay = 1200
            start()
        }

        // Linha e Bot
        AnimatorSet().apply {
            playSequentially(
                ObjectAnimator.ofFloat(linha, "alpha", 0f, 1f).setDuration(400),
                ObjectAnimator.ofFloat(txtBot, "alpha", 0f, 1f).setDuration(400)
            )
            startDelay = 1900
            start()
        }

        // Tagline
        ObjectAnimator.ofFloat(txtTag, "alpha", 0f, 1f).apply {
            duration = 500; startDelay = 2600; start()
        }

        // Pulsar
        ObjectAnimator.ofFloat(txtAviator, "alpha", 1f, 0.6f, 1f).apply {
            duration = 800; repeatCount = 1; startDelay = 2300; start()
        }

        // Partículas animadas
        val tick = object : Runnable {
            override fun run() {
                for (p in particles) {
                    p[0] += p[2]; p[1] += p[3]; p[7] += 0.008f
                    p[4] = (0.5f * Math.sin(p[7] * Math.PI).toFloat()).coerceIn(0f, 0.7f)
                    if (p[1] < -10f || p[7] >= 1f) {
                        p[0] = (Math.random() * W).toFloat()
                        p[1] = H + 5f; p[7] = 0f
                        p[3] = (-Math.random() * 1.3 - 0.2).toFloat()
                    }
                }
                bgView.invalidate()
                particleHandler.postDelayed(this, 16)
            }
        }
        particleHandler.post(tick)

        return root
    }

    private fun drawPlane(c: Canvas, s: Float, paint: Paint) {
        paint.shader = null
        // Corpo
        paint.color = Color.WHITE
        val body = Path()
        body.moveTo(0f, -s * 0.8f)
        body.lineTo(s * 0.22f, s * 0.3f)
        body.lineTo(0f, s * 0.12f)
        body.lineTo(-s * 0.22f, s * 0.3f)
        body.close()
        c.drawPath(body, paint)
        // Asa direita
        paint.color = Color.parseColor("#e2e8f0")
        val wr = Path()
        wr.moveTo(s * 0.08f, 0f); wr.lineTo(s * 0.85f, s * 0.45f)
        wr.lineTo(s * 0.65f, s * 0.52f); wr.lineTo(s * 0.04f, s * 0.18f); wr.close()
        c.drawPath(wr, paint)
        // Asa esquerda
        val wl = Path()
        wl.moveTo(-s * 0.08f, 0f); wl.lineTo(-s * 0.85f, s * 0.45f)
        wl.lineTo(-s * 0.65f, s * 0.52f); wl.lineTo(-s * 0.04f, s * 0.18f); wl.close()
        c.drawPath(wl, paint)
        // Cauda vermelha
        paint.color = Color.parseColor("#ef4444")
        val tr = Path()
        tr.moveTo(s * 0.04f, s * 0.22f); tr.lineTo(s * 0.32f, s * 0.62f)
        tr.lineTo(s * 0.22f, s * 0.68f); tr.lineTo(0f, s * 0.32f); tr.close()
        c.drawPath(tr, paint)
        val tl = Path()
        tl.moveTo(-s * 0.04f, s * 0.22f); tl.lineTo(-s * 0.32f, s * 0.62f)
        tl.lineTo(-s * 0.22f, s * 0.68f); tl.lineTo(0f, s * 0.32f); tl.close()
        c.drawPath(tl, paint)
        // Janelas
        paint.color = Color.parseColor("#bae6fd")
        for (i in -1..1) c.drawCircle(i * s * 0.09f, -s * 0.1f, s * 0.065f, paint)
    }

    override fun onDestroy() {
        super.onDestroy()
        particleHandler.removeCallbacksAndMessages(null)
    }
}
