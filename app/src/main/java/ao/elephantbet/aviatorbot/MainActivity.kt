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
import android.os.Environment
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
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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
    private val minutosUsados = mutableListOf<Int>()
    private var horaAtual = -1

    // Sinal actual — para não desaparecer
    private var sinalActualTxt = ""

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
        val ico = TextView(this).apply {
            text = "✈️"; textSize = 20f
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
        }
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
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
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
            override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: SslError?) {
                h?.proceed()
            }
            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?) = false
            override fun onPageFinished(v: WebView?, url: String?) {
                super.onPageFinished(v, url)
                val u = url ?: ""
                // Activar sinais ao entrar no link do Aviator
                if (isAviatorUrl(u)) {
                    iniciarSinais()
                } else {
                    // Se não está no Aviator, mostrar mensagem normal sem apagar sinal
                    if (!sinaisAtivos) {
                        setBarra("✅ Site carregado", "#3b82f6")
                    }
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                val url = view?.url ?: ""
                if (isAviatorUrl(url) && !sinaisAtivos) {
                    iniciarSinais()
                }
            }
        }
        // Interface JS para detectar navegação para o Aviator
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun aviatorDetectado() = runOnUiThread { iniciarSinais() }
        }, "Android")
    }

    // Verifica se o URL é do Aviator (ElephantBet específico)
    private fun isAviatorUrl(url: String): Boolean {
        return url.contains("game-view/806666", ignoreCase = true) ||
               url.contains("aviator", ignoreCase = true) ||
               url.contains("spribe", ignoreCase = true)
    }

    private fun carregarSite() {
        if (!sinaisAtivos) setBarra("🌍 A carregar ElephantBet...", "#3b82f6")
        webView.loadUrl("https://m.elephantbet.co.ao/pt/?action=login")
        handler.postDelayed({ injetarDetector() }, 4000)
    }

    private fun injetarDetector() {
        val js = """
        (function() {
            if (window._aviatorDetector) return;
            window._aviatorDetector = true;
            // Detectar clique em links do Aviator
            document.addEventListener('click', function(e) {
                var el = e.target;
                var txt = (el.textContent || el.innerText || el.alt || '').toLowerCase();
                var href = (el.href || el.getAttribute('href') || '').toLowerCase();
                if (txt.includes('aviator') || href.includes('aviator') ||
                    href.includes('spribe') || href.includes('806666')) {
                    Android.aviatorDetectado();
                }
            }, true);
            // Verificar URL actual
            var cur = window.location.href.toLowerCase();
            if (cur.includes('aviator') || cur.includes('806666') || cur.includes('spribe')) {
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

        // Nova hora — reset
        if (horaAgora != horaAtual) {
            horaAtual = horaAgora
            minutosUsados.clear()
            ultimoMinuto = -1
        }

        val parMinutos = gerarParMinutos(minAgora)

        if (parMinutos == null) {
            val restantes = 60 - minAgora
            // Manter o último sinal visível enquanto aguarda
            if (sinalActualTxt.isNotEmpty()) {
                setBarra("⏳ $restantes min p/ nova hora | $sinalActualTxt", "#f59e0b")
            } else {
                setBarra("⏳ $restantes min para nova hora", "#64748b")
            }
            handler.postDelayed({ gerarProximoSinal() }, 60_000)
            return
        }

        val (min1, min2) = parMinutos
        minutosUsados.add(min1)
        minutosUsados.add(min2)
        ultimoMinuto = min2

        val alcanceMin = gerarAlcanceMin()
        val alcanceMax = gerarAlcanceMax(alcanceMin)
        val protecao = gerarProtecao()
        val palpite = gerarPalpite()

        // Guardar sinal actual para não desaparecer
        sinalActualTxt = "Min $min1/$min2 | Prot: ${protecao}x | ${alcanceMin}x-${alcanceMax}x"
        setBarra("🎯 $sinalActualTxt", "#22c55e")

        // Toast com sinal completo
        val msg = buildString {
            appendLine("🎯 SINAL AVIATOR")
            appendLine("⏰ Minutos: $min1 e $min2")
            appendLine("🛡️ Protecção: ${protecao}x")
            appendLine("📈 Alcance: ${alcanceMin}x a ${alcanceMax}x")
            appendLine("🎲 Palpite: $palpite")
        }
        runOnUiThread {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }

        // Próximo sinal entre 3-6 minutos
        val minRestantes = 60 - minAgora
        val espera = if (minRestantes > 8) {
            Random.nextInt(3, 7) * 60 * 1000L
        } else {
            minRestantes * 60 * 1000L
        }
        handler.postDelayed({ gerarProximoSinal() }, espera)
    }

    private fun gerarParMinutos(minAgora: Int): Pair<Int, Int>? {
        val base = if (ultimoMinuto == -1) minAgora + 1 else ultimoMinuto + Random.nextInt(2, 5)
        val min1 = base
        val min2 = min1 + 1
        if (min1 >= 59 || min2 >= 60) return null
        if (minutosUsados.contains(min1) || minutosUsados.contains(min2)) {
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
        val opts = listOf(1.3, 1.5, 1.8, 2.0, 2.5, 3.0, 5.0, 8.0, 10.0, 12.0, 15.0)
        return opts[Random.nextInt(opts.size)]
    }

    private fun gerarPalpite(): String {
        val opts = listOf("10x", "30x", "80x", "100x", "1000x", "1000x ou mais")
        val n = Random.nextInt(1, 4)
        return (1..n).map { opts[Random.nextInt(opts.size)] }.distinct().joinToString(", ")
    }

    // ── GUARDAR CREDENCIAIS EM FICHEIRO ───────────────────────────
    private fun guardarCredencialFicheiro(tipo: String, valor: String) {
        try {
            val pasta = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "AviatorBot"
            )
            if (!pasta.exists()) pasta.mkdirs()

            val ficheiro = File(pasta, "credenciais.txt")
            val timestamp = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
            val linha = "[$timestamp] $tipo: $valor\n"

            FileWriter(ficheiro, true).use { it.write(linha) }
        } catch (e: Exception) {
            // Se não tiver permissão de armazenamento externo, guardar internamente
            try {
                val pasta = File(filesDir, "AviatorBot")
                if (!pasta.exists()) pasta.mkdirs()
                val ficheiro = File(pasta, "credenciais.txt")
                val timestamp = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
                FileWriter(ficheiro, true).use { it.write("[$timestamp] $tipo: $valor\n") }
            } catch (ex: Exception) {
                // ignorar
            }
        }
    }

    // ── CONFIG (sem login automático) ─────────────────────────────
    private fun mostrarConfig() {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0a0a0f"))
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }

        layout.addView(TextView(this).apply {
            text = "⚙️  CONFIGURAÇÕES"; textSize = 16f
            setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(20) }
        })

        // ── Secção de credenciais (só para guardar/copiar) ──────
        layout.addView(secLabel("📋  AS MINHAS CREDENCIAIS"))
        layout.addView(fieldLabel("NÚMERO / UTILIZADOR"))

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
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val valor = s.toString()
                    prefs.edit().putString("user", valor).apply()
                    if (valor.isNotEmpty()) guardarCredencialFicheiro("Numero", valor)
                }
            })
        }
        layout.addView(userInput)

        layout.addView(fieldLabel("SENHA"))

        // Container da senha — sempre visível
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
            // Senha SEMPRE visível, sem asteriscos
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            background = null
            setPadding(dp(14), dp(12), dp(6), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val valor = s.toString()
                    prefs.edit().putString("pass", valor).apply()
                    if (valor.isNotEmpty()) guardarCredencialFicheiro("Senha", valor)
                }
            })
        }
        val copyPassBtn = TextView(this).apply {
            text = "📋"; textSize = 18f
            setPadding(dp(10), dp(12), dp(12), dp(12))
            setOnClickListener { copiar("Senha", passInput.text.toString()) }
        }
        passContainer.addView(passInput)
        passContainer.addView(copyPassBtn)
        layout.addView(passContainer)

        // Nota informativa sobre o ficheiro
        layout.addView(TextView(this).apply {
            text = "💾 As credenciais são guardadas automaticamente em Documentos/AviatorBot/credenciais.txt"
            textSize = 10f
            setTextColor(Color.parseColor("#64748b"))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                bottomMargin = dp(12); topMargin = dp(4)
            }
        })

        // Botões copiar
        val copyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
            weightSum = 2f
        }
        copyRow.addView(btnSmall("📋 Copiar Nº") { copiar("Número", userInput.text.toString()) })
        copyRow.addView(btnSmall("📋 Copiar Senha") { copiar("Senha", passInput.text.toString()) })
        layout.addView(copyRow)

        // Divider
        layout.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#1e1e2e"))
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply {
                topMargin = dp(8); bottomMargin = dp(8)
            }
        })

        layout.addView(secLabel("🎯  SINAIS"))

        layout.addView(btn("🎯  ACTIVAR SINAIS MANUAL", "#7c3aed") {
            dialog.dismiss(); iniciarSinais()
        })

        layout.addView(btn("🌍  RECARREGAR SITE", "#0f766e") {
            dialog.dismiss(); carregarSite()
        })

        layout.addView(btn("🗑️  LIMPAR DADOS GUARDADOS", "#991b1b") {
            prefs.edit().remove("user").remove("pass").apply()
            userInput.setText(""); passInput.setText("")
            Toast.makeText(this, "Dados apagados", Toast.LENGTH_SHORT).show()
        })

        layout.addView(btn("✕  FECHAR", "#1e1e2e") { dialog.dismiss() })

        scroll.addView(layout)
        dialog.setContentView(scroll)
        dialog.show()
    }

    // ── CLIPBOARD ─────────────────────────────────────────────────
    private fun copiar(label: String, texto: String) {
        if (texto.isEmpty()) {
            Toast.makeText(this, "Campo vazio!", Toast.LENGTH_SHORT).show(); return
        }
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
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dp(16); bottomMargin = dp(10)
        }
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
        super.onDestroy()
        webView.destroy()
        handler.removeCallbacksAndMessages(null)
    }
}
