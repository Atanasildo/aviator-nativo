package ao.elephantbet.aviatorbot

import android.animation.*
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.statusBarColor = Color.parseColor("#0a0a0f")

        val root = buildSplash()
        setContentView(root)

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

        // Root frame
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0a0a0f"))

        // ── Partículas ──────────────────────────────────────────
        val particles = ArrayList<FloatArray>() // x, y, vx, vy, alpha, size, colorIdx, life
        val colors = intArrayOf(
            Color.parseColor("#ef4444"),
            Color.parseColor("#ff6600"),
            Color.parseColor("#fbbf24"),
            Color.parseColor("#ffffff")
        )
        repeat(50) {
            particles.add(floatArrayOf(
                (Math.random() * W).toFloat(),
                (Math.random() * H).toFloat(),
                (Math.random() * 1.5 - 0.75).toFloat(),
                (-Math.random() * 1.5 - 0.3).toFloat(),
                (Math.random() * 0.5 + 0.1).toFloat(),
                (Math.random() * 3 + 1).toFloat(),
                (Math.random() * 4).toFloat(),
                (Math.random()).toFloat()
            ))
        }

        val particleHandler = Handler(Looper.getMainLooper())
        var planeX = -dp(60f)
        var planeY = H * 0.75f

        // Canvas de fundo com partículas e avião
        val canvas = object : View(this) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            override fun onDraw(c: Canvas) {
                // Fundo radial
                val grad = RadialGradient(W / 2f, H / 2f, H / 1.4f,
                    intArrayOf(Color.parseColor("#1a0505"), Color.parseColor("#0a0a0f")),
                    null, Shader.TileMode.CLAMP)
                paint.shader = grad
                c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
                paint.shader = null

                // Rastro do avião
                if (planeX > 0) {
                    val tp = Paint(Paint.ANTI_ALIAS_FLAG)
                    tp.style = Paint.Style.STROKE
                    tp.strokeWidth = dp(2f)
                    tp.shader = LinearGradient(0f, H * 0.75f, planeX, planeY,
                        Color.TRANSPARENT, Color.parseColor("#ef4444"), Shader.TileMode.CLAMP)
                    val path = Path()
                    path.moveTo(0f, H * 0.75f)
                    path.quadTo(planeX * 0.4f, H * 0.65f, planeX, planeY)
                    c.drawPath(path, tp)

                    // Brilho
                    val gd = RadialGradient(planeX, planeY, dp(70f),
                        intArrayOf(Color.parseColor("#60ef4444"), Color.parseColor("#30ff6600"), Color.TRANSPARENT),
                        floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                    paint.shader = gd
                    c.drawCircle(planeX, planeY, dp(70f), paint)
                    paint.shader = null

                    // Avião
                    c.save()
                    c.translate(planeX, planeY)
                    val angle = -25f + (planeX / W) * 25f
                    c.rotate(angle)
                    drawPlane(c, dp(38f))
                    c.restore()
                }

                // Partículas
                for (p in particles) {
                    paint.color = colors[p[6].toInt().coerceIn(0, 3)]
                    paint.alpha = (p[4] * 255).toInt().coerceIn(0, 255)
                    c.drawCircle(p[0], p[1], p[5], paint)
                    paint.alpha = 255
                }
            }

            fun drawPlane(c: Canvas, s: Float) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                // Corpo
                p.color = Color.WHITE
                val body = Path()
                body.moveTo(0f, -s * 0.8f)
                body.lineTo(s * 0.22f, s * 0.3f)
                body.lineTo(0f, s * 0.12f)
                body.lineTo(-s * 0.22f, s * 0.3f)
                body.close()
                c.drawPath(body, p)
                // Asa direita
                p.color = Color.parseColor("#e2e8f0")
                val wr = Path()
                wr.moveTo(s * 0.08f, 0f)
                wr.lineTo(s * 0.85f, s * 0.45f)
                wr.lineTo(s * 0.65f, s * 0.52f)
                wr.lineTo(s * 0.04f, s * 0.18f)
                wr.close()
                c.drawPath(wr, p)
                // Asa esquerda
                val wl = Path()
                wl.moveTo(-s * 0.08f, 0f)
                wl.lineTo(-s * 0.85f, s * 0.45f)
                wl.lineTo(-s * 0.65f, s * 0.52f)
                wl.lineTo(-s * 0.04f, s * 0.18f)
                wl.close()
                c.drawPath(wl, p)
                // Cauda vermelha
                p.color = Color.parseColor("#ef4444")
                val tr = Path()
                tr.moveTo(s * 0.04f, s * 0.22f)
                tr.lineTo(s * 0.32f, s * 0.62f)
                tr.lineTo(s * 0.22f, s * 0.68f)
                tr.lineTo(0f, s * 0.32f)
                tr.close()
                c.drawPath(tr, p)
                val tl = Path()
                tl.moveTo(-s * 0.04f, s * 0.22f)
                tl.lineTo(-s * 0.32f, s * 0.62f)
                tl.lineTo(-s * 0.22f, s * 0.68f)
                tl.lineTo(0f, s * 0.32f)
                tl.close()
                c.drawPath(tl, p)
                // Janelas
                p.color = Color.parseColor("#bae6fd")
                for (i in -1..1) c.drawCircle(i * s * 0.09f, -s * 0.1f, s * 0.07f, p)
            }
        }
        root.addView(canvas, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ── Textos centrados ─────────────────────────────────────
        val center = LinearLayout(this)
        center.orientation = LinearLayout.VERTICAL
        center.gravity = Gravity.CENTER

        val txtAviator = TextView(this)
        txtAviator.text = "AVIATOR"
        txtAviator.textSize = 52f
        txtAviator.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        txtAviator.setTextColor(Color.WHITE)
        txtAviator.gravity = Gravity.CENTER
        txtAviator.letterSpacing = 0.2f
        txtAviator.alpha = 0f
        txtAviator.scaleX = 0.5f
        txtAviator.scaleY = 0.5f
        val lpA = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        txtAviator.layoutParams = lpA

        val linha = View(this)
        linha.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.TRANSPARENT, Color.parseColor("#ef4444"), Color.parseColor("#ff6600"), Color.TRANSPARENT))
        val lpL = LinearLayout.LayoutParams(dpi(200f), dpi(2f))
        lpL.topMargin = dpi(8f); lpL.bottomMargin = dpi(8f)
        lpL.gravity = Gravity.CENTER_HORIZONTAL
        linha.layoutParams = lpL
        linha.alpha = 0f

        val txtBot = TextView(this)
        txtBot.text = "B O T"
        txtBot.textSize = 18f
        txtBot.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        txtBot.setTextColor(Color.parseColor("#ef4444"))
        txtBot.gravity = Gravity.CENTER
        txtBot.letterSpacing = 0.5f
        txtBot.alpha = 0f
        txtBot.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val txtTag = TextView(this)
        txtTag.text = "Inteligência Artificial · Sinais em Tempo Real"
        txtTag.textSize = 10f
        txtTag.setTextColor(Color.parseColor("#64748b"))
        txtTag.gravity = Gravity.CENTER
        txtTag.alpha = 0f
        val lpT = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lpT.topMargin = dpi(24f)
        txtTag.layoutParams = lpT

        center.addView(txtAviator)
        center.addView(linha)
        center.addView(txtBot)
        center.addView(txtTag)

        val lpCenter = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(center, lpCenter)

        // ── Animação do avião ─────────────────────────────────────
        val animPlane = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800
            interpolator = DecelerateInterpolator(2f)
            startDelay = 300
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                planeX = -dp(60f) + t * (W * 0.72f + dp(60f))
                planeY = H * 0.75f - t * (H * 0.3f)
                canvas.invalidate()
            }
        }

        // Animação do título
        val animTitle = AnimatorSet()
        animTitle.playTogether(
            ObjectAnimator.ofFloat(txtAviator, "alpha", 0f, 1f).setDuration(600),
            ObjectAnimator.ofFloat(txtAviator, "scaleX", 0.5f, 1f).setDuration(700),
            ObjectAnimator.ofFloat(txtAviator, "scaleY", 0.5f, 1f).setDuration(700)
        )
        animTitle.startDelay = 1200

        val animSub = AnimatorSet()
        animSub.playSequentially(
            ObjectAnimator.ofFloat(linha, "alpha", 0f, 1f).setDuration(400),
            ObjectAnimator.ofFloat(txtBot, "alpha", 0f, 1f).setDuration(400)
        )
        animSub.startDelay = 1900

        val animTag = ObjectAnimator.ofFloat(txtTag, "alpha", 0f, 1f).apply {
            duration = 500; startDelay = 2600
        }

        val animPulse = ObjectAnimator.ofFloat(txtAviator, "alpha", 1f, 0.6f, 1f).apply {
            duration = 800; repeatCount = 1; startDelay = 2200
        }

        AnimatorSet().apply {
            playTogether(animPlane, animTitle, animSub, animTag, animPulse)
            start()
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
                        p[3] = (-Math.random() * 1.5 - 0.3).toFloat()
                    }
                }
                canvas.invalidate()
                particleHandler.postDelayed(this, 16)
            }
        }
        particleHandler.post(tick)

        return root
    }
}
