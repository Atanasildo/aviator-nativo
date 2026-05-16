package ao.elephantbet.aviatorbot

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT as MATCH
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT as WRAP
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.Calendar
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var msgText: TextView
    private lateinit var dotView: View
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("aviator_prefs", Context.MODE_PRIVATE) }

    // Sinais automáticos
    private var sinaisAtivos = false
    private var ultimoMinuto = -1
    private val minutosUsados = mutableListOf<Int>() // minutos já gerados nesta hora
    private var horaAtual = -1

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0a0a0f"))
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        setContentView(root)

        // ── BARRA TOPO ──────────────────────────────────────────
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#12121a"))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(58))
        }
        val ico = TextView(this).apply { text = "✈️"; textSize = 20f; layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)) }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            setPadding(dp(8), 0, dp(8), 0)
        }
        info.addView(TextView(this).apply {
            text = "AVIATOR BOT"; textSize = 9f
            setTextColor(Color.parseColor("#64748b")); letterSpacing = 0.15f
        })
        msgText = TextView(this).apply {
            text = "A carregar..."; textSize = 11f
            setTextColor(Color.parseColor("#3b82f6"))
            isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        info.addView(msgText)
        dotView = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(10) }
            background = circulo("#64748b")
        }
        val cfgBtn = TextView(this).apply {
            text = "⚙️"; textSize = 18f
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(Color.parseColor("#1e1e2e"))
            setOnClickListener { mostrarConfig() }
        }
        bar.addView(ico); bar.addView(info); bar.addView(dotView); bar.addView(cfgBtn)
        root.addView(bar)

        // ── WEBVIEW ─────────────────────────────────────────────
        webView = WebView(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f) }
        configurarWebView()
        root.addView(webView)

        carregarSite()
    }

    // ── WEBVIEW ───────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private fun configurarWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            setSupportZoom(true); builtInZoomControls = false
            loadWithOverviewMode = true; useWideViewPort = true
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: SslError?) { h?.proceed() }
            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?) = false
            override fun onPageFinished(v: WebView?, url: String?) {
                super.onPageFinished(v, url)
                setBarra("✅ Site carregado", "#3b82f6")
                // Detectar se chegou ao Aviator
                val u = url ?: ""
                if (u.contains("aviator", ignoreCase = true) || u.contains("spribe", ignoreCase = true)) {
                    iniciarSinais()
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                // Detectar URL do Aviator durante navegação
                val url = view?.url ?: ""
                if ((url.contains("aviator", ignoreCase = true) || url.contains("spribe", ignoreCase = true)) && !sinaisAtivos) {
                    iniciarSinais()
                }
            }
        }
        // Interface para detectar cliques no Aviator via JS
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun aviatorDetectado() = runOnUiThread { iniciarSinais() }
        }, "Android")
    }

    private fun carregarSite() {
        setBarra("🌍 A carregar ElephantBet...", "#3b82f6")
        webView.loadUrl("https://m.elephantbet.co.ao/pt/?action=login")
        // Injetar detector de clique no Aviator periodicamente
        handler.postDelayed({ injetarDetector() }, 5000)
    }

    private fun injetarDetector() {
        val js = """
        (function() {
            if (window._aviatorDetector) return;
            window._aviatorDetector = true;
            // Detectar links/botões do Aviator
            document.addEventListener('click', function(e) {
                var el = e.target;
                var txt = (el.textContent || el.innerText || el.alt || '').toLowerCase();
                var href = (el.href || el.getAttribute('href') || '').toLowerCase();
                if (txt.includes('aviator') || href.includes('aviator') || href.includes('spribe')) {
                    Android.aviatorDetectado();
                }
            }, true);
            // Verificar se já está no Aviator
            if (document.title.toLowerCase().includes('aviator') ||
                window.location.href.toLowerCase().includes('aviator')) {
                Android.aviatorDetectado();
            }
        })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
        if (!sinaisAtivos) handler.postDelayed({ injetarDetector() }, 8000)
    }

    // ── SINAIS AUTOMÁTICOS ────────────────────────────────────────
    private fun iniciarSinais() {
        if (sinaisAtivos) return
        sinaisAtivos = true
        minutosUsados.clear()
        val cal = Calendar.getInstance()
        horaAtual = cal.get(Calendar.HOUR_OF_DAY)
        setBarra("🎯 Sinais activos! A gerar...", "#22c55e")
        gerarProximoSinal()
    }

    private fun gerarProximoSinal() {
        val cal = Calendar.getInstance()
        val horaAgora = cal.get(Calendar.HOUR_OF_DAY)
        val minAgora = cal.get(Calendar.MINUTE)

        // Nova hora — reset dos minutos
        if (horaAgora != horaAtual) {
            horaAtual = horaAgora
            minutosUsados.clear()
            ultimoMinuto = -1
        }

        // Gerar par de minutos sempre crescente
        val parMinutos = gerarParMinutos(minAgora)

        if (parMinutos == null) {
            // Fim da hora — aguardar virar hora
            setBarra("⏳ Min ${60 - minAgora} para nova hora", "#64748b")
            handler.postDelayed({ gerarProximoSinal() }, 60000)
            return
        }

        val (min1, min2) = parMinutos
        minutosUsados.add(min1)
        minutosUsados.add(min2)
        ultimoMinuto = min2

        // Gerar valores aleatórios do sinal
        val alcanceMin = gerarAlcanceMin()
        val alcanceMax = gerarAlcanceMax(alcanceMin)
        val protecao = gerarProtecao()
        val palpite = gerarPalpite()

        // Mostrar sinal na barra
        val sinalTxt = "Min $min1/$min2 | Prot: ${protecao}x | $alcanceMin-${alcanceMax}x"
        setBarra("🎯 $sinalTxt", "#22c55e")

        // Notificação completa via Toast
        val msg = buildString {
            appendLine("🎯 SINAL AVIATOR")
            appendLine("⏰ Min $min1/$min2")
            appendLine("🛡️ Proteção: ${protecao}x")
            appendLine("📈 Alcance: ${alcanceMin}x a ${alcanceMax}x")
            appendLine("🎲 Palpite: $palpite")
        }
        runOnUiThread {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }

        // Calcular tempo até o próximo sinal (entre 3-7 minutos após min2)
        val minRestantes = 60 - minAgora
        val espera = if (minRestantes > 8) {
            val rand = Random.nextInt(3, 7) * 60 * 1000L
            rand
        } else {
            minRestantes * 60 * 1000L
        }

        handler.postDelayed({ gerarProximoSinal() }, espera)
    }

    private fun gerarParMinutos(minAgora: Int): Pair<Int, Int>? {
        // Próximo minuto disponível após o actual e após os já usados
        val base = if (ultimoMinuto == -1) minAgora + 1 else ultimoMinuto + Random.nextInt(2, 5)
        val min1 = base
        val min2 = min1 + 1

        if (min1 >= 59 || min2 >= 60) return null
        if (minutosUsados.contains(min1) || minutosUsados.contains(min2)) {
            // Tentar o próximo
            val novo = (minutosUsados.maxOrNull() ?: minAgora) + Random.nextInt(2, 4)
            if (novo >= 59) return null
            return Pair(novo, novo + 1)
        }
        return Pair(min1, min2)
    }

    private fun gerarAlcanceMin(): Int {
        val opcoes = listOf(1, 2, 3, 5, 10, 20, 30, 50)
        return opcoes[Random.nextInt(opcoes.size)]
    }

    private fun gerarAlcanceMax(min: Int): String {
        val opts = when {
            min <= 2  -> listOf("10", "30", "50", "80", "100", "1000", "1000+")
            min <= 10 -> listOf("30", "50", "80", "100", "500", "1000+")
            min <= 30 -> listOf("50", "80", "100", "200", "500")
            else      -> listOf("80", "100", "200", "500", "1000+")
        }
        return opts[Random.nextInt(opts.size)]
    }

    private fun gerarProtecao(): Double {
        // Proteção nunca passa de 15x
        val opts = listOf(1.3, 1.5, 1.8, 2.0, 2.5, 3.0, 5.0, 8.0, 10.0, 12.0, 15.0)
        return opts[Random.nextInt(opts.size)]
    }

    private fun gerarPalpite(): String {
        val opts = listOf("10x", "30x", "80x", "100x", "1000x", "1000x ou mais")
        val n = Random.nextInt(1, 4)
        return (1..n).map { opts[Random.nextInt(opts.size)] }.distinct().joinToString(", ")
    }

    // ── CONFIG ────────────────────────────────────────────────────
    private fun mostrarConfig() {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen)

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0a0a0f")) }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }

        layout.addView(TextView(this).apply {
            text = "⚙️  CONFIGURAÇÕES"; textSize = 16f
            setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(20) }
        })

        // ── Login manual
        layout.addView(secLabel("🔐  LOGIN"))
        layout.addView(fieldLabel("TELEMÓVEL / UTILIZADOR"))

        val userInput = EditText(this).apply {
            setText(prefs.getString("user", "") ?: "")
            hint = "Ex: 943 427 841"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#475569"))
            textSize = 14f
            inputType = InputType.TYPE_CLASS_PHONE
            background = roundRect("#12121a", "#334155")
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) }
            // Guardar cada dígito automaticamente
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    prefs.edit().putString("user", s.toString()).apply()
                }
            })
        }
        layout.addView(userInput)

        layout.addView(fieldLabel("SENHA"))

        // Container da senha com botão ver/esconder
        val passContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundRect("#12121a", "#334155")
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) }
        }
        val passInput = EditText(this).apply {
            setText(prefs.getString("pass", "") ?: "")
            hint = "A tua senha"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#475569"))
            textSize = 14f
            // Senha SEMPRE visível enquanto digita
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            background = null
            setPadding(dp(14), dp(12), dp(6), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    prefs.edit().putString("pass", s.toString()).apply()
                }
            })
        }
        // Botão copiar senha
        val copyPassBtn = TextView(this).apply {
            text = "📋"; textSize = 18f
            setPadding(dp(10), dp(12), dp(12), dp(12))
            setOnClickListener { copiar("Senha", passInput.text.toString()) }
        }
        passContainer.addView(passInput)
        passContainer.addView(copyPassBtn)
        layout.addView(passContainer)

        // Botões copiar
        val copyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) }
            weightSum = 2f
        }
        copyRow.addView(btnSmall("📋 Copiar Nº") { copiar("Número", userInput.text.toString()) })
        copyRow.addView(btnSmall("📋 Copiar Senha") { copiar("Senha", passInput.text.toString()) })
        layout.addView(copyRow)

        layout.addView(btn("🔐  FAZER LOGIN", "#16a34a") {
            dialog.dismiss()
            // Injetar login manualmente no site
            val u = prefs.getString("user", "") ?: ""
            val p = prefs.getString("pass", "") ?: ""
            if (u.isNotEmpty() && p.isNotEmpty()) {
                // Limpar prefixo 244
                val uLimpo = u.replace(" ","").replace("-","")
                    .let { if (it.startsWith("244") && it.length > 9) it.substring(3) else it }
                injetarLogin(uLimpo, p)
            } else {
                Toast.makeText(this, "Preenche utilizador e senha!", Toast.LENGTH_SHORT).show()
            }
        })

        layout.addView(btn("🗑️  LIMPAR DADOS", "#991b1b") {
            prefs.edit().remove("user").remove("pass").apply()
            userInput.setText(""); passInput.setText("")
            Toast.makeText(this, "Dados apagados", Toast.LENGTH_SHORT).show()
        })

        layout.addView(btn("🎯  ACTIVAR SINAIS MANUAL", "#7c3aed") {
            dialog.dismiss(); iniciarSinais()
        })

        layout.addView(btn("🌍  RECARREGAR SITE", "#0f766e") { dialog.dismiss(); carregarSite() })
        layout.addView(btn("✕  FECHAR", "#1e1e2e") { dialog.dismiss() })

        scroll.addView(layout)
        dialog.setContentView(scroll)
        dialog.show()
    }

    // ── LOGIN INJECTADO ───────────────────────────────────────────
    private fun injetarLogin(user: String, pass: String) {
        val userEsc = user.replace("'", "\\'")
        val passEsc = pass.replace("'", "\\'").replace("\\", "\\\\")
        val js = """
        (function() {
            var selsU = ['input[name="username"]','input[name="phone"]','input[type="tel"]',
                'input[placeholder*="telefone" i]','input[placeholder*="número" i]',
                'input[placeholder*="utilizador" i]','#username','#phone'];
            var selsP = ['input[name="password"]','input[type="password"]',
                'input[placeholder*="senha" i]','input[placeholder*="password" i]','#password'];
            var selsB = ['button[type="submit"]','button[class*="login" i]',
                'button[class*="entrar" i]','input[type="submit"]'];
            function find(sels) {
                for (var s of sels) {
                    try { var el = document.querySelector(s); if (el) return el; } catch(e) {}
                }
                return null;
            }
            function fill(el, val) {
                if (!el) return;
                el.focus();
                try {
                    var d = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');
                    if (d && d.set) d.set.call(el, val); else el.value = val;
                } catch(e) { el.value = val; }
                el.dispatchEvent(new Event('input',{bubbles:true}));
                el.dispatchEvent(new Event('change',{bubbles:true}));
            }
            var uEl = find(selsU), pEl = find(selsP);
            if (uEl && pEl) {
                fill(uEl, '$userEsc'); fill(pEl, '$passEsc');
                setTimeout(function() { var b=find(selsB); if(b) b.click(); }, 1000);
            }
        })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
        setBarra("🔐 A fazer login...", "#facc15")
    }

    // ── CLIPBOARD ─────────────────────────────────────────────────
    private fun copiar(label: String, texto: String) {
        if (texto.isEmpty()) { Toast.makeText(this, "Campo vazio!", Toast.LENGTH_SHORT).show(); return }
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText(label, texto))
        Toast.makeText(this, "✅ $label copiado!", Toast.LENGTH_SHORT).show()
    }

    // ── UI HELPERS ────────────────────────────────────────────────
    private fun setBarra(msg: String, cor: String) {
        runOnUiThread {
            msgText.text = msg
            msgText.setTextColor(Color.parseColor(cor))
            dotView.background = circulo(cor)
        }
    }

    private fun circulo(cor: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(Color.parseColor(cor))
    }

    private fun roundRect(bg: String, border: String) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = dp(10).toFloat()
        setColor(Color.parseColor(bg)); setStroke(dp(1), Color.parseColor(border))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun secLabel(txt: String) = TextView(this).apply {
        text = txt; textSize = 11f; setTextColor(Color.parseColor("#64748b"))
        typeface = Typeface.DEFAULT_BOLD
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16); bottomMargin = dp(10) }
    }

    private fun fieldLabel(txt: String) = TextView(this).apply {
        text = txt; textSize = 10f; setTextColor(Color.parseColor("#94a3b8"))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) }
    }

    private fun btn(txt: String, cor: String, action: () -> Unit) = Button(this).apply {
        text = txt; setTextColor(Color.WHITE); textSize = 13f
        typeface = Typeface.DEFAULT_BOLD; isAllCaps = false
        background = roundRect(cor, cor)
        setPadding(0, dp(14), 0, dp(14))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
    }

    private fun btnSmall(txt: String, action: () -> Unit) = Button(this).apply {
        text = txt; setTextColor(Color.parseColor("#94a3b8")); textSize = 11f
        isAllCaps = false; background = roundRect("#1e1e2e", "#334155")
        setPadding(0, dp(8), 0, dp(8))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(6) }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy(); webView.destroy()
        handler.removeCallbacksAndMessages(null)
    }
}
