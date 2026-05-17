package ao.elephantbet.aviatorbot

import android.animation.*
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.animation.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fullscreen sem status bar
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor = Color.parseColor("#0a0a0f")

        val root = SplashCanvas(this)
        setContentView(root)

        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, 3800)
    }
}

class SplashCanvas(context: android.content.Context) : FrameLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val particles = mutableListOf<Particle>()
    private var animFrame = 0
    private var planeX = 0f
    private var planeY = 0f
    private var planeAngle = 0f

    data class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float,
                        var alpha: Float, var size: Float, var color: Int, var life: Float)

    init {
        setBackgroundColor(Color.parseColor("#0a0a0f"))
        buildUI()
        spawnParticles()
    }

    private fun buildUI() {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels

        // Canvas de partículas (fundo)
        val particleView = object : View(context) {
            override fun onDraw(canvas: Canvas) {
                // Fundo gradiente
                val grad = RadialGradient(w/2f, h/2f, h/1.5f,
                    intArrayOf(Color.parseColor("#1a0505"), Color.parseColor("#0a0a0f")),
                    null, Shader.TileMode.CLAMP)
                val paint = Paint().apply { shader = grad }
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

                // Partículas
                val pp = Paint(Paint.ANTI_ALIAS_FLAG)
                for (p in particles) {
                    pp.color = p.color
                    pp.alpha = (p.alpha * 255).toInt()
                    canvas.drawCircle(p.x, p.y, p.size, pp)
                }
            }
        }
        addView(particleView, LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Linha de rastro (trail)
        val trailView = object : View(context) {
            override fun onDraw(canvas: Canvas) {
                val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = dp(2f)
                    shader = LinearGradient(0f, 0f, planeX, planeY,
                        Color.TRANSPARENT, Color.parseColor("#ef4444"), Shader.TileMode.CLAMP)
                }
                val path = Path()
                path.moveTo(0f, h * 0.75f)
                path.quadTo(w * 0.3f, h * 0.6f, planeX, planeY)
                canvas.drawPath(path, tp)
            }
        }
        addView(trailView, LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Círculo de brilho atrás do avião
        val glowView = object : View(context) {
            override fun onDraw(canvas: Canvas) {
                if (planeX <= 0) return
                val gp = Paint(Paint.ANTI_ALIAS_FLAG)
                val gd = RadialGradient(planeX, planeY, dp(80f),
                    intArrayOf(Color.parseColor("#80ef4444"), Color.parseColor("#40ff6600"), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                gp.shader = gd
                canvas.drawCircle(planeX, planeY, dp(80f), gp)
            }
        }
        addView(glowView, LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // ── AVIÃO SVG desenhado via canvas ────────────────────────
        val planeView = object : View(context) {
            override fun onDraw(canvas: Canvas) {
                if (planeX <= 0) return
                canvas.save()
                canvas.translate(planeX, planeY)
                canvas.rotate(planeAngle)
                desenharAviao(canvas, dp(40f))
                canvas.restore()
            }
        }
        addView(planeView, LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // ── Textos ────────────────────────────────────────────────
        val centroLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        // Título AVIATOR
        val txtAviator = TextView(context).apply {
            text = "AVIATOR"
            textSize = 52f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            letterSpacing = 0.2f
            alpha = 0f
            scaleX = 0.5f; scaleY = 0.5f
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        }

        // Linha decorativa
        val linha = View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#ef4444"), Color.parseColor("#ff6600"), Color.TRANSPARENT))
            layoutParams = LinearLayout.LayoutParams(dp(200f).toInt(), dp(2f).toInt()).apply {
                topMargin = dp(8f).toInt(); bottomMargin = dp(8f).toInt()
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            alpha = 0f
        }

        // Subtítulo BOT
        val txtBot = TextView(context).apply {
            text = "B O T"
            textSize = 18f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(Color.parseColor("#ef4444"))
            gravity = android.view.Gravity.CENTER
            letterSpacing = 0.5f
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        }

        // Tagline
        val txtTag = TextView(context).apply {
            text = "Inteligência Artificial · Sinais em Tempo Real"
            textSize = 10f
            setTextColor(Color.parseColor("#64748b"))
            gravity = android.view.Gravity.CENTER
            letterSpacing = 0.1f
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = dp(24f).toInt()
            }
        }

        // Loading bar
        val loadingBg = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(4f)
                setColor(Color.parseColor("#1e293b"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(200f).toInt(), dp(3f).toInt()).apply {
                topMargin = dp(32f).toInt()
            }
            alpha = 0f
        }

        centroLayout.addView(txtAviator)
        centroLayout.addView(linha)
        centroLayout.addView(txtBot)
        centroLayout.addView(txtTag)
        centroLayout.addView(loadingBg)
        addView(centroLayout)

        // ── ANIMAÇÕES ─────────────────────────────────────────────
        // 1. Avião entra da esquerda-baixo para centro
        planeX = -dp(60f); planeY = h * 0.8f

        val animPlane = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800
            interpolator = DecelerateInterpolator(2f)
            startDelay = 200
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                planeX = (-dp(60f)) + t * (w * 0.75f + dp(60f))
                planeY = h * 0.8f - t * (h * 0.35f)
                planeAngle = -30f + t * 30f  // inclina para cima e volta ao normal
                particleView.invalidate()
                trailView.invalidate()
                glowView.invalidate()
                planeView.invalidate()
            }
        }

        // 2. Título aparece com scale
        val animTitulo = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(txtAviator, "alpha", 0f, 1f).setDuration(600),
                ObjectAnimator.ofFloat(txtAviator, "scaleX", 0.5f, 1f).setDuration(700),
                ObjectAnimator.ofFloat(txtAviator, "scaleY", 0.5f, 1f).setDuration(700)
            )
            startDelay = 1200
        }

        // 3. Linha e subtítulo
        val animSub = AnimatorSet().apply {
            playSequentially(
                ObjectAnimator.ofFloat(linha, "alpha", 0f, 1f).setDuration(400),
                ObjectAnimator.ofFloat(txtBot, "alpha", 0f, 1f).setDuration(400)
            )
            startDelay = 1800
        }

        // 4. Tagline e loading
        val animTag = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(txtTag, "alpha", 0f, 1f).setDuration(500),
                ObjectAnimator.ofFloat(loadingBg, "alpha", 0f, 1f).setDuration(500)
            )
            startDelay = 2400
        }

        // 5. Pulsar do título
        val animPulse = ObjectAnimator.ofFloat(txtAviator, "alpha", 1f, 0.7f, 1f).apply {
            duration = 800; repeatCount = 2; startDelay = 2000
        }

        AnimatorSet().apply {
            playTogether(animPlane, animTitulo, animSub, animTag, animPulse)
            start()
        }

        // Partículas animadas
        animarParticulas(particleView)
    }

    private fun desenharAviao(canvas: Canvas, scale: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Corpo
        paint.color = Color.WHITE
        val body = Path().apply {
            moveTo(0f, -scale * 0.8f)
            lineTo(scale * 0.25f, scale * 0.3f)
            lineTo(0f, scale * 0.15f)
            lineTo(-scale * 0.25f, scale * 0.3f)
            close()
        }
        canvas.drawPath(body, paint)

        // Asa direita
        paint.color = Color.parseColor("#f1f5f9")
        val wingR = Path().apply {
            moveTo(scale * 0.1f, 0f)
            lineTo(scale * 0.9f, scale * 0.4f)
            lineTo(scale * 0.7f, scale * 0.5f)
            lineTo(scale * 0.05f, scale * 0.2f)
            close()
        }
        canvas.drawPath(wingR, paint)

        // Asa esquerda
        val wingL = Path().apply {
            moveTo(-scale * 0.1f, 0f)
            lineTo(-scale * 0.9f, scale * 0.4f)
            lineTo(-scale * 0.7f, scale * 0.5f)
            lineTo(-scale * 0.05f, scale * 0.2f)
            close()
        }
        canvas.drawPath(wingL, paint)

        // Cauda
        paint.color = Color.parseColor("#ef4444")
        val tail = Path().apply {
            moveTo(scale * 0.05f, scale * 0.25f)
            lineTo(scale * 0.35f, scale * 0.65f)
            lineTo(scale * 0.25f, scale * 0.7f)
            lineTo(0f, scale * 0.35f)
            close()
        }
        canvas.drawPath(tail, paint)
        val tailL = Path().apply {
            moveTo(-scale * 0.05f, scale * 0.25f)
            lineTo(-scale * 0.35f, scale * 0.65f)
            lineTo(-scale * 0.25f, scale * 0.7f)
            lineTo(0f, scale * 0.35f)
            close()
        }
        canvas.drawPath(tailL, paint)

        // Janelas
        paint.color = Color.parseColor("#bae6fd")
        for (i in -1..1) {
            canvas.drawCircle(i * scale * 0.1f, -scale * 0.1f, scale * 0.07f, paint)
        }
    }

    private fun spawnParticles() {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val colors = intArrayOf(
            Color.parseColor("#ef4444"), Color.parseColor("#ff6600"),
            Color.parseColor("#fbbf24"), Color.parseColor("#ffffff")
        )
        repeat(60) {
            particles.add(Particle(
                x = (Math.random() * w).toFloat(),
                y = (Math.random() * h).toFloat(),
                vx = (Math.random() * 1.5 - 0.75).toFloat(),
                vy = (-Math.random() * 1.5 - 0.2).toFloat(),
                alpha = (Math.random() * 0.6 + 0.1).toFloat(),
                size = (Math.random() * 3 + 1).toFloat(),
                color = colors[(Math.random() * colors.size).toInt()],
                life = (Math.random()).toFloat()
            ))
        }
    }

    private fun animarParticulas(view: View) {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val tick = object : Runnable {
            override fun run() {
                for (p in particles) {
                    p.x += p.vx; p.y += p.vy
                    p.life += 0.008f; p.alpha = (0.5f * Math.sin(p.life * Math.PI).toFloat()).coerceIn(0f, 0.8f)
                    if (p.y < -10 || p.life >= 1f) {
                        p.x = (Math.random() * w).toFloat(); p.y = h + 5f; p.life = 0f
                        p.vy = (-Math.random() * 1.5 - 0.2).toFloat()
                    }
                }
                view.invalidate()
                handler.postDelayed(this, 16)
            }
        }
        handler.post(tick)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); handler.removeCallbacksAndMessages(null) }
}
