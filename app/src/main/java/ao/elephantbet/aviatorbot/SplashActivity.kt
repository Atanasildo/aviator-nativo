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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    private val particleHandler = Handler(Looper.getMainLooper())

    // ── Paleta ORION ───────────────────────────────────────────────────────
    private val COLOR_BG        = Color.parseColor("#060b14")  // fundo profundo
    private val COLOR_SURFACE   = Color.parseColor("#0a1628")  // cards
    private val COLOR_PRIMARY   = Color.parseColor("#38bdf8")  // ciano principal
    private val COLOR_ACCENT    = Color.parseColor("#818cf8")  // índigo acento
    private val COLOR_DIM       = Color.parseColor("#0d2137")  // rastro de partícula
    private val COLOR_STAR_RED  = Color.parseColor("#f87171")  // Betelgeuse
    private val COLOR_STAR_BLUE = Color.parseColor("#93c5fd")  // Rigel/Bellatrix

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.statusBarColor = COLOR_BG
        setContentView(buildSplash())
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, 4200)
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density)
    private fun dpi(v: Float) = dp(v).toInt()

    private fun buildSplash(): FrameLayout {
        val W = resources.displayMetrics.widthPixels
        val H = resources.displayMetrics.heightPixels

        val root = FrameLayout(this)
        root.setBackgroundColor(COLOR_BG)

        // ── Partículas de poeira estelar ─────────────────────────────────
        // [x, y, vx, vy, alpha, radius, colorType, phase]
        val particles = Array(60) {
            floatArrayOf(
                (Math.random() * W).toFloat(),
                (Math.random() * H).toFloat(),
                (Math.random() * 0.3 - 0.15).toFloat(),
                (Math.random() * 0.6 + 0.2).toFloat(),
                (Math.random() * 0.6 + 0.1).toFloat(),
                (Math.random() * 1.5 + 0.5).toFloat(),
                (Math.random() * 3).toFloat(),
                (Math.random()).toFloat()
            )
        }

        // ── Rastros de luz (nebulosa) ─────────────────────────────────────
        val trails = Array(12) {
            floatArrayOf(
                (Math.random() * W).toFloat(),
                (Math.random() * H).toFloat(),
                (Math.random() * H * 0.2 + 30).toFloat(),
                (Math.random() * 1.5 + 0.5).toFloat()
            )
        }

        // ── Constelação de Orion — coordenadas normalizadas (0..1) ────────
        // [x_norm, y_norm, radius_px, colorType, pulsePhase]
        // colorType: 0=ciano, 1=vermelho(Betelgeuse), 2=azul(Rigel/Bellatrix)
        val cx = W / 2f
        val cy = H / 2f - dp(60f)
        val scale = dp(90f)  // escala da constelação

        // Estrelas principais de Orion (posições relativas ao centro)
        val stars = arrayOf(
            // Ombros
            floatArrayOf(cx - scale * 0.28f, cy - scale * 0.32f, dp(4.5f), 1f, 0f),   // Betelgeuse (vermelho)
            floatArrayOf(cx + scale * 0.32f, cy - scale * 0.38f, dp(3.5f), 2f, 0.4f), // Bellatrix (azul)
            // Cinto de Orion (3 estrelas alinhadas)
            floatArrayOf(cx - scale * 0.12f, cy + scale * 0.04f, dp(3f),   0f, 0.2f), // Mintaka
            floatArrayOf(cx,                 cy + scale * 0.08f, dp(3.2f),  0f, 0.5f), // Alnilam
            floatArrayOf(cx + scale * 0.12f, cy + scale * 0.04f, dp(3f),   0f, 0.8f), // Alnitak
            // Joelhos/pés
            floatArrayOf(cx - scale * 0.38f, cy + scale * 0.52f, dp(4f),   2f, 0.6f), // Rigel (azul brilhante)
            floatArrayOf(cx + scale * 0.28f, cy + scale * 0.48f, dp(3f),   2f, 0.9f)  // Saiph
        )

        // Linhas da constelação: pares de índice em `stars`
        val constellationLines = arrayOf(
            intArrayOf(0, 1),  // ombro a ombro
            intArrayOf(0, 2),  // Betelgeuse → Mintaka
            intArrayOf(1, 4),  // Bellatrix → Alnitak
            intArrayOf(2, 3),  // cinto
            intArrayOf(3, 4),  // cinto
            intArrayOf(2, 5),  // Mintaka → Rigel
            intArrayOf(4, 6)   // Alnitak → Saiph
        )

        // Estado de pulso das estrelas
        val starPhases = FloatArray(stars.size) { (Math.random() * Math.PI * 2).toFloat() }

        // ── View de fundo — partículas + constelação ──────────────────────
        val bgView = object : View(this) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            var time = 0f

            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(COLOR_BG)

                // Nebulosa suave no centro
                val nebulaGrad = RadialGradient(
                    W / 2f, H / 2f, W * 0.7f,
                    intArrayOf(
                        Color.argb(18, 56, 189, 248),
                        Color.argb(8,  129, 140, 248),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                paint.shader = nebulaGrad
                paint.style = Paint.Style.FILL
                canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
                paint.shader = null

                // Rastros de luz (nebulosa linear)
                paint.strokeWidth = dp(1f)
                for (t in trails) {
                    val grad = LinearGradient(
                        t[0], t[1] - t[2], t[0], t[1],
                        intArrayOf(Color.TRANSPARENT, Color.argb(15, 56, 189, 248), Color.argb(40, 56, 189, 248)),
                        null, Shader.TileMode.CLAMP
                    )
                    paint.shader = grad
                    canvas.drawLine(t[0], t[1] - t[2], t[0], t[1], paint)
                }
                paint.shader = null

                // Poeira estelar (partículas)
                paint.style = Paint.Style.FILL
                for (p in particles) {
                    val alpha = (p[4] * 180).toInt().coerceIn(0, 180)
                    paint.color = when (p[6].toInt()) {
                        0 -> COLOR_PRIMARY
                        1 -> COLOR_ACCENT
                        else -> Color.parseColor("#e2e8f0")
                    }
                    paint.alpha = alpha
                    canvas.drawCircle(p[0], p[1], p[5], paint)
                }
                paint.alpha = 255

                // Linhas da constelação
                paint.strokeWidth = dp(0.8f)
                paint.color = Color.argb(70, 56, 189, 248)
                for (line in constellationLines) {
                    val s1 = stars[line[0]]; val s2 = stars[line[1]]
                    canvas.drawLine(s1[0], s1[1], s2[0], s2[1], paint)
                }

                // Estrelas da constelação com pulso
                for (i in stars.indices) {
                    val s = stars[i]
                    val pulse = (0.7f + 0.3f * Math.sin((starPhases[i] + time * 0.04f).toDouble()).toFloat())
                    val baseR = s[2]
                    val r = baseR * pulse

                    // Glow externo
                    val glowColor = when (s[3].toInt()) {
                        1 -> COLOR_STAR_RED
                        2 -> COLOR_STAR_BLUE
                        else -> COLOR_PRIMARY
                    }
                    paint.color = Color.argb(40, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor))
                    canvas.drawCircle(s[0], s[1], r * 2.5f, paint)

                    // Núcleo
                    paint.color = glowColor
                    paint.alpha = (180 * pulse).toInt().coerceIn(100, 255)
                    canvas.drawCircle(s[0], s[1], r, paint)
                }
                paint.alpha = 255
                time++
            }
        }
        root.addView(bgView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        // ── Layout central ────────────────────────────────────────────────
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        // Anel orbital (logo ORION)
        val logoView = object : View(this) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            var angle = 0f

            override fun onDraw(canvas: Canvas) {
                val cx = width / 2f; val cy = height / 2f
                val outerR = cx * 0.88f
                val innerR = cx * 0.58f

                // Glow de fundo
                p.shader = RadialGradient(cx, cy, outerR,
                    intArrayOf(Color.argb(30, 56, 189, 248), Color.argb(10, 56, 189, 248), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
                canvas.drawCircle(cx, cy, outerR, p)
                p.shader = null

                // Anel externo
                p.style = Paint.Style.STROKE
                p.strokeWidth = dp(1f)
                p.color = Color.argb(60, 56, 189, 248)
                canvas.drawCircle(cx, cy, outerR, p)

                // Anel interno
                p.strokeWidth = dp(0.6f)
                p.color = Color.argb(40, 129, 140, 248)
                canvas.drawCircle(cx, cy, innerR, p)

                // Ponto orbitante
                val rad = Math.toRadians(angle.toDouble())
                val ox = (cx + outerR * Math.cos(rad)).toFloat()
                val oy = (cy + outerR * Math.sin(rad)).toFloat()

                // Rastro do ponto orbitante
                p.style = Paint.Style.FILL
                p.color = Color.argb(30, 56, 189, 248)
                canvas.drawCircle(ox, oy, dp(5f), p)
                p.color = COLOR_PRIMARY
                canvas.drawCircle(ox, oy, dp(3.5f), p)
                p.color = Color.WHITE
                canvas.drawCircle(ox, oy, dp(1.5f), p)

                // Ponto central
                p.color = Color.argb(60, 56, 189, 248)
                canvas.drawCircle(cx, cy, dp(8f), p)
                p.color = COLOR_PRIMARY
                canvas.drawCircle(cx, cy, dp(4f), p)
                p.color = Color.WHITE
                canvas.drawCircle(cx, cy, dp(2f), p)
            }
        }.apply {
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(dpi(130f), dpi(130f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dpi(8f)
            }
        }

        // Animação orbital
        val orbitalAnim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 5000
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                (logoView as? View)?.let { v ->
                    val field = v.javaClass.getDeclaredField("angle")
                    field.isAccessible = true
                    field.setFloat(v, it.animatedValue as Float)
                    v.invalidate()
                }
            }
        }

        // Nome ORION
        val txtName = TextView(this).apply {
            text = "ORION"
            textSize = 44f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#e2e8f0"))
            gravity = Gravity.CENTER
            letterSpacing = 0.5f
            alpha = 0f
            setShadowLayer(dp(12f), 0f, 0f, Color.argb(120, 56, 189, 248))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }

        // Linha decorativa ciano
        val linha = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.parseColor("#38bdf8"),
                    Color.parseColor("#818cf8"),
                    Color.TRANSPARENT
                )
            )
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(dpi(200f), dpi(1f)).apply {
                topMargin = dpi(8f); bottomMargin = dpi(10f)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        // Subtítulo
        val txtSub = TextView(this).apply {
            text = "> SIGNAL SYSTEM"
            textSize = 10f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            setTextColor(Color.parseColor("#38bdf8"))
            gravity = Gravity.CENTER
            letterSpacing = 0.25f
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }

        // Barra de progresso
        val progressBg = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0a1628"))
                cornerRadius = dp(2f)
            }
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(dpi(160f), dpi(3f)).apply {
                topMargin = dpi(32f)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        val progressFill = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#38bdf8"), Color.parseColor("#818cf8"))
            ).apply { cornerRadius = dp(2f) }
        }

        // Tagline
        val txtTag = TextView(this).apply {
            text = "[ ENCRYPTED · REAL-TIME · AI ]"
            textSize = 8.5f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            setTextColor(Color.argb(100, 100, 116, 139))
            gravity = Gravity.CENTER
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpi(6f); gravity = Gravity.CENTER_HORIZONTAL }
        }

        center.addView(logoView)
        center.addView(txtName)
        center.addView(linha)
        center.addView(txtSub)
        center.addView(progressBg)
        center.addView(txtTag)

        root.addView(center, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // ── Sequência de animações ────────────────────────────────────────

        // 1. Logo orbital aparece
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(logoView, "alpha", 0f, 1f).setDuration(900),
                ObjectAnimator.ofFloat(logoView, "scaleX", 0.4f, 1f).setDuration(900),
                ObjectAnimator.ofFloat(logoView, "scaleY", 0.4f, 1f).setDuration(900)
            )
            startDelay = 300; start()
        }

        // 2. Nome ORION
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(txtName, "alpha", 0f, 1f).setDuration(600),
                ObjectAnimator.ofFloat(txtName, "translationY", dp(16f), 0f).setDuration(600)
            )
            startDelay = 1100; start()
        }

        // 3. Linha + subtítulo
        AnimatorSet().apply {
            playSequentially(
                ObjectAnimator.ofFloat(linha, "alpha", 0f, 1f).setDuration(350),
                ObjectAnimator.ofFloat(txtSub, "alpha", 0f, 1f).setDuration(450)
            )
            startDelay = 1700; start()
        }

        // 4. Barra de progresso
        Handler(Looper.getMainLooper()).postDelayed({
            ObjectAnimator.ofFloat(progressBg, "alpha", 0f, 1f).apply {
                duration = 300; start()
            }
            // Animar largura do fill
            val targetW = dpi(160f)
            ValueAnimator.ofInt(0, targetW).apply {
                duration = 1800
                addUpdateListener {
                    progressFill.layoutParams = (progressFill.layoutParams as? LinearLayout.LayoutParams)?.apply {
                        width = it.animatedValue as Int
                    } ?: LinearLayout.LayoutParams(it.animatedValue as Int, dpi(3f))
                    progressFill.requestLayout()
                }
                start()
            }
        }, 2400)

        // 5. Tagline
        ObjectAnimator.ofFloat(txtTag, "alpha", 0f, 0.6f).apply {
            duration = 500; startDelay = 2600; start()
        }

        // 6. Pulso no nome
        ObjectAnimator.ofFloat(txtName, "alpha", 1f, 0.4f, 1f).apply {
            duration = 700; repeatCount = 1; startDelay = 2800; start()
        }

        // Iniciar orbital
        orbitalAnim.start()

        // ── Animação fundo: partículas + estrelas ─────────────────────────
        val tick = object : Runnable {
            override fun run() {
                for (p in particles) {
                    p[0] += p[2]; p[1] += p[3]; p[7] += 0.006f
                    p[4] = (0.5f * Math.sin(p[7] * Math.PI).toFloat()).coerceIn(0f, 0.7f)
                    if (p[1] > H + 10f || p[7] >= 1f) {
                        p[0] = (Math.random() * W).toFloat()
                        p[1] = -5f; p[7] = 0f
                        p[3] = (Math.random() * 0.6 + 0.2).toFloat()
                    }
                }
                for (t in trails) {
                    t[1] += t[3]
                    if (t[1] - t[2] > H) { t[1] = -t[2] }
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
