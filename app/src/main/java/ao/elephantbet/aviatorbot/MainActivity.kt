package ao.elephantbet.aviatorbot

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.roundToInt
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var msgText: TextView
    private lateinit var dotView: View
    private val prefs by lazy { getSharedPreferences("aviator", MODE_PRIVATE) }

    private val signalHandler = Handler(Looper.getMainLooper())
    private var signalRunnable: Runnable? = null
    private var sinaisAtivos = false

    private var horaAtual = -1
    private var ultimoMinuto = -1

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0a0a0f"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(root)

        // Barra de sinais
        val barLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#12121a"))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58))
        }

        val icoText = TextView(this).apply {
            text = "📡"; textSize = 20f
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
        }

        val infoLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(8), 0, dp(8), 0)
        }
        val lblText = TextView(this).apply {
            text = "AVIATOR BOT"; textSize = 9f
            setTextColor(Color.parseColor("#64748b")); letterSpacing = 0.15f
        }
        msgText = TextView(this).apply {
            text = "A carregar..."; textSize = 11f
            setTextColor(Color.parseColor("#3b82f6"))
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        infoLayout.addView(lblText); infoLayout.addView(msgText)

        dotView = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(10) }
            post { background = criarCirculo("#64748b") }
        }

        val cfgBtn = TextView(this).apply {
            text = "⚙️"; textSize = 18f
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(Color.parseColor("#1e1e2e"))
            setOnClickListener { mostrarConfig() }
        }

        barLayout.addView(icoText); barLayout.addView(infoLayout)
        barLayout.addView(dotView); barLayout.addView(cfgBtn)
        root.addView(barLayout)

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        configurarWebView()
        root.addView(webView)

        setBarra("🌍 A carregar...", "#3b82f6")
        webView.loadUrl("https://m.elephantbet.co.ao/pt/?action=login")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configurarWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            setSupportZoom(true); builtInZoomControls = false
            loadWithOverviewMode = true; useWideViewPort = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed()
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val u = url ?: ""
                if (u.contains("aviator", true) || u.contains("spribe", true) || u.contains("game", true)) {
                    if (!sinaisAtivos) iniciarSinais()
                } else {
                    setBarra("✅ Site carregado — clique no Aviator", "#3b82f6")
                }
                injectAviatorDetector()
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) setBarra("❌ Erro ao carregar", "#ef4444")
            }
        }

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun aviatorAberto() {
                runOnUiThread { if (!sinaisAtivos) iniciarSinais() }
            }
        }, "Android")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress == 100) injectAviatorDetector()
            }
        }
    }

    private fun injectAviatorDetector() {
        val js = """
        (function(){
            document.querySelectorAll('a,[class*="aviator" i],[data-game*="aviator" i],img[alt*="aviator" i]').forEach(function(el){
                el.addEventListener('click',function(){ Android.aviatorAberto(); },{once:true});
            });
            if(!window._aviatorObserver){
                window._aviatorObserver = new MutationObserver(function(muts){
                    muts.forEach(function(m){
                        m.addedNodes.forEach(function(n){
                            if(n.nodeType===1){
                                var t=(n.className||'')+(n.id||'')+(n.innerHTML||'');
                                if(/aviator|spribe/i.test(t)){ Android.aviatorAberto(); }
                            }
                        });
                    });
                });
                window._aviatorObserver.observe(document.body||document.documentElement,{childList:true,subtree:true});
            }
        })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // ── GERADOR DE SINAIS ─────────────────────────────────────────
    private fun iniciarSinais() {
        sinaisAtivos = true
        val cal = java.util.Calendar.getInstance()
        horaAtual = cal.get(java.util.Calendar.HOUR_OF_DAY)
        ultimoMinuto = cal.get(java.util.Calendar.MINUTE)
        setBarra("🤖 Bot activo — A analisar...", "#22c55e")
        agendarProximoSinal(primeiroSinal = true)
    }

    private fun pararSinais() {
        sinaisAtivos = false
        signalRunnable?.let { signalHandler.removeCallbacks(it) }
        signalRunnable = null
    }

    private fun agendarProximoSinal(primeiroSinal: Boolean = false) {
        if (!sinaisAtivos) return
        val delay = if (primeiroSinal) 4000L else Random.nextLong(45_000L, 95_000L)
        signalRunnable = Runnable {
            if (!sinaisAtivos) return@Runnable
            emitirSinal()
            agendarProximoSinal()
        }
        signalHandler.postDelayed(signalRunnable!!, delay)
    }

    private fun emitirSinal() {
        val cal = java.util.Calendar.getInstance()
        val horaAgora = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val minutoAgora = cal.get(java.util.Calendar.MINUTE)

        // Nova hora — resetar
        if (horaAgora != horaAtual) {
            horaAtual = horaAgora
            ultimoMinuto = minutoAgora
        }

        // Próximo minuto: sempre à frente do último e do minuto actual (+2 min mínimo)
        val minBase = maxOf(ultimoMinuto + 1, minutoAgora + 2)

        // Fim do ciclo desta hora
        if (minBase >= 58) {
            ultimoMinuto = 58
            setBarra("🔄 Ciclo a terminar — A aguardar nova hora...", "#64748b")
            dotView.post { dotView.background = criarCirculo("#64748b") }
            return
        }

        // Variação aleatória de 0 a 3 minutos sobre a base
        val min1 = (minBase + Random.nextInt(0, 4)).coerceAtMost(57)
        val min2 = (min1 + 1).coerceAtMost(58)
        ultimoMinuto = min2

        // Multiplicadores
        val protecao = gerarProtecao()       // <= 15x
        val alcMin   = gerarAlcanceMin()     // baixo
        val alcMax   = gerarAlcanceMax()     // alto

        val hora   = String.format("%02d", horaAtual)
        val minStr = String.format("%02d/%02d", min1, min2)

        val msg = "Min $hora:$minStr  🛡 Prot: ${protecao}x  🎯 ${alcMin}x – ${alcMax}x"

        val cor = when {
            alcMax.replace("+","").toFloatOrNull() ?: 0f >= 500f -> "#ec4899"
            alcMax.replace("+","").toFloatOrNull() ?: 0f >= 80f  -> "#22c55e"
            alcMax.replace("+","").toFloatOrNull() ?: 0f >= 20f  -> "#facc15"
            else -> "#3b82f6"
        }

        setBarra(msg, cor)
        mostrarNotificacao(msg)
    }

    // Protecção: 1.2x a 15x (nunca acima de 15)
    private fun gerarProtecao(): String {
        val r = Random.nextFloat()
        val v = when {
            r < 0.40f -> Random.nextFloat() * 3.8f + 1.2f   // 1.2–5
            r < 0.75f -> Random.nextFloat() * 5f + 5f       // 5–10
            else      -> Random.nextFloat() * 5f + 10f      // 10–15
        }.coerceIn(1.2f, 15f)
        return fmt(v)
    }

    // Alcance mínimo
    private fun gerarAlcanceMin(): String {
        val r = Random.nextFloat()
        val v = when {
            r < 0.40f -> Random.nextFloat() * 2f + 1.5f   // 1.5–3.5
            r < 0.70f -> Random.nextFloat() * 6f + 3f     // 3–9
            else      -> Random.nextFloat() * 5f + 8f     // 8–13
        }
        return fmt(v)
    }

    // Alcance máximo: 10, 30, 80, 1000x ou mais (aleatório ponderado)
    private fun gerarAlcanceMax(): String {
        val r = Random.nextFloat()
        return when {
            r < 0.28f -> fmt(Random.nextFloat() * 20f + 10f)    // 10–30
            r < 0.50f -> fmt(Random.nextFloat() * 50f + 30f)    // 30–80
            r < 0.68f -> fmt(Random.nextFloat() * 170f + 80f)   // 80–250
            r < 0.80f -> "500"
            r < 0.91f -> "1000"
            else      -> "1000+"
        }
    }

    private fun fmt(v: Float): String =
        if (v >= 10f) v.roundToInt().toString()
        else ((v * 10).roundToInt() / 10f).toString()

    // ── PAINEL CONFIG ─────────────────────────────────────────────
    private fun mostrarConfig() {
        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_NoActionBar).create()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0a0a0f"))
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        fun lbl(txt: String) = TextView(this).apply {
            text = txt; textSize = 9f
            setTextColor(Color.parseColor("#64748b"))
            setPadding(0, dp(10), 0, dp(4))
        }

        fun btn(txt: String, cor: String, action: () -> Unit) = Button(this).apply {
            text = txt
            setBackgroundColor(Color.parseColor(cor))
            setTextColor(Color.WHITE)
            setPadding(0, dp(12), 0, dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            setOnClickListener { action() }
        }

        layout.addView(TextView(this).apply {
            text = "⚙️ CONFIGURAÇÕES"; textSize = 13f; setTextColor(Color.WHITE)
        })

        // Campo utilizador
        layout.addView(lbl("TELEMÓVEL / UTILIZADOR"))
        val userInput = EditText(this).apply {
            hint = "Ex: 9XXXXXXXX"
            setText(prefs.getString("user", "") ?: "")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748b"))
            setBackgroundColor(Color.parseColor("#12121a"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    prefs.edit().putString("user", s.toString().trim()).apply()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        layout.addView(userInput)

        // Campo senha — VISÍVEL enquanto digita
        layout.addView(lbl("SENHA"))
        val passInput = EditText(this).apply {
            hint = "A tua senha"
            setText(prefs.getString("pass", "") ?: "")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748b"))
            setBackgroundColor(Color.parseColor("#12121a"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            // Texto visível (sem mascarar com asteriscos)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    prefs.edit().putString("pass", s.toString()).apply()
                    val u = userInput.text.toString().trim()
                    val p = s.toString()
                    if (u.isNotEmpty() && p.isNotEmpty()) guardarNoClipboard(u, p)
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        layout.addView(passInput)

        layout.addView(btn("🗑️ Limpar Credenciais", "#991b1b") {
            prefs.edit().remove("user").remove("pass").apply()
            userInput.setText(""); passInput.setText("")
            Toast.makeText(this, "Credenciais limpas", Toast.LENGTH_SHORT).show()
        })

        layout.addView(btn("🌍 Recarregar Site", "#0f766e") {
            dialog.dismiss()
            setBarra("🌍 A recarregar...", "#3b82f6")
            webView.loadUrl("https://m.elephantbet.co.ao/pt/?action=login")
        })

        layout.addView(btn("✕ Fechar", "#1e1e2e") { dialog.dismiss() })

        val scroll = ScrollView(this).apply { addView(layout) }
        dialog.setView(scroll)
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun guardarNoClipboard(user: String, pass: String) {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("aviator", "$user | $pass"))
    }

    private fun setBarra(msg: String, cor: String) {
        msgText.text = msg
        msgText.setTextColor(Color.parseColor(cor))
        dotView.post { dotView.background = criarCirculo(cor) }
    }

    private fun mostrarNotificacao(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun criarCirculo(cor: String) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        setColor(Color.parseColor(cor))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        pararSinais()
        webView.destroy()
    }
}
