package ao.elephantbet.aviatorbot

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var barLayout: LinearLayout
    private lateinit var msgText: TextView
    private lateinit var dotView: View
    private lateinit var cfgBtn: TextView

    private var sseClient: OkHttpClient? = null
    private var sseCall: Call? = null
    private val prefs by lazy { getSharedPreferences("aviator", MODE_PRIVATE) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Layout raiz ──────────────────────────────────────────
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0a0a0f"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(root)

        // ── Barra de sinais no topo ───────────────────────────────
        barLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#12121a"))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)
            )
        }

        // Ícone
        val icoText = TextView(this).apply {
            text = "📡"
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
        }

        // Info (label + msg)
        val infoLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(8), 0, dp(8), 0)
        }
        val lblText = TextView(this).apply {
            text = "AVIATOR BOT"
            textSize = 9f
            setTextColor(Color.parseColor("#64748b"))
            letterSpacing = 0.15f
        }
        msgText = TextView(this).apply {
            text = "A carregar..."
            textSize = 12f
            setTextColor(Color.parseColor("#3b82f6"))
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        infoLayout.addView(lblText)
        infoLayout.addView(msgText)

        // Dot animado
        dotView = View(this).apply {
            setBackgroundColor(Color.parseColor("#64748b"))
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply {
                marginEnd = dp(10)
            }
            // Tornar circular
            post { background = createCircle("#64748b") }
        }

        // Botão config ⚙️
        cfgBtn = TextView(this).apply {
            text = "⚙️"
            textSize = 18f
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(Color.parseColor("#1e1e2e"))
            setOnClickListener { mostrarConfig() }
        }

        barLayout.addView(icoText)
        barLayout.addView(infoLayout)
        barLayout.addView(dotView)
        barLayout.addView(cfgBtn)
        root.addView(barLayout)

        // ── WebView nativa ───────────────────────────────────────
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        configurarWebView()
        root.addView(webView)

        // Carregar site
        carregarSite()

        // Ligar servidor se guardado
        val srvUrl = prefs.getString("srv_url", "") ?: ""
        if (srvUrl.isNotEmpty()) ligarServidor(srvUrl)
    }

    // ── WEBVIEW CONFIG ────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private fun configurarWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            setSupportZoom(true)
            builtInZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        // Ignorar erros SSL
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed() // Ignorar erros SSL
            }

            // Interceptar respostas e remover headers de bloqueio
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                return null // Deixar passar tudo
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                setBarra("✅ Site carregado", "#3b82f6")
                // Tentar login automático
                val user = prefs.getString("user", "") ?: ""
                val pass = prefs.getString("pass", "") ?: ""
                if (user.isNotEmpty() && pass.isNotEmpty()) {
                    view?.postDelayed({ tentarLogin(user, pass) }, 2500)
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    setBarra("❌ Erro ao carregar site", "#ef4444")
                }
            }
        }

        webView.webChromeClient = WebChromeClient()

        // Interface JavaScript para comunicação
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onLoginSucesso() {
                runOnUiThread { setBarra("✅ Login efectuado!", "#22c55e") }
            }
            @JavascriptInterface
            fun onLoginErro(msg: String) {
                runOnUiThread { setBarra("❌ Erro login: $msg", "#ef4444") }
            }
        }, "Android")
    }

    private fun carregarSite() {
        setBarra("🌍 A carregar ElephantBet...", "#3b82f6")
        webView.loadUrl("https://m.elephantbet.co.ao/pt/?action=login")
    }

    // ── AUTO LOGIN ────────────────────────────────────────────────
    private fun tentarLogin(user: String, pass: String) {
        val userEsc = user.replace("'", "\\'")
        val passEsc = pass.replace("'", "\\'")

        val js = """
        (function() {
            var selsU = [
                'input[name="username"]', 'input[name="phone"]', 'input[name="login"]',
                'input[type="tel"]', 'input[placeholder*="phone" i]',
                'input[placeholder*="utilizador" i]', 'input[placeholder*="número" i]',
                'input[placeholder*="telemóvel" i]', '#username', '#phone', '#login'
            ];
            var selsP = ['input[name="password"]', 'input[type="password"]', '#password'];
            var selsB = [
                'button[type="submit"]', 'button[class*="login" i]',
                'button[class*="entrar" i]', 'input[type="submit"]',
                'button[class*="submit" i]'
            ];
            function find(ss) { for(var s of ss){ var e=document.querySelector(s); if(e) return e; } return null; }
            function fill(e, v) {
                if(!e) return;
                e.focus();
                var nv = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                if(nv) nv.set.call(e, v);
                else e.value = v;
                e.dispatchEvent(new Event('input', {bubbles:true}));
                e.dispatchEvent(new Event('change', {bubbles:true}));
            }
            var uEl = find(selsU);
            var pEl = find(selsP);
            if(uEl && pEl) {
                fill(uEl, '$userEsc');
                fill(pEl, '$passEsc');
                setTimeout(function() {
                    var btn = find(selsB);
                    if(btn) {
                        btn.click();
                        Android.onLoginSucesso();
                    } else {
                        Android.onLoginErro('Botão não encontrado');
                    }
                }, 1200);
                return 'OK';
            } else {
                // Tentar novamente em 3 segundos
                setTimeout(function() {
                    var u2 = find(selsU), p2 = find(selsP);
                    if(u2 && p2) {
                        fill(u2, '$userEsc');
                        fill(p2, '$passEsc');
                        setTimeout(function(){ var b=find(selsB); if(b) b.click(); }, 1000);
                    }
                }, 3000);
                return 'AGUARDAR';
            }
        })();
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            runOnUiThread {
                when {
                    result?.contains("OK") == true -> setBarra("🔐 A fazer login...", "#facc15")
                    result?.contains("AGUARDAR") == true -> setBarra("⏳ A aguardar formulário...", "#facc15")
                }
            }
        }
    }

    // ── PAINEL CONFIG ─────────────────────────────────────────────
    private fun mostrarConfig() {
        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_NoActionBar)
            .create()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0a0a0f"))
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        fun addLabel(txt: String) = TextView(this).apply {
            text = txt; textSize = 10f
            setTextColor(Color.parseColor("#64748b"))
            setPadding(0, dp(12), 0, dp(4))
        }

        fun addInput(hint: String, saved: String = "", isPass: Boolean = false) = EditText(this).apply {
            this.hint = hint
            setText(saved)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748b"))
            setBackgroundColor(Color.parseColor("#12121a"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            if (isPass) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        fun addBtn(txt: String, color: String, action: () -> Unit) = Button(this).apply {
            text = txt
            setBackgroundColor(Color.parseColor(color))
            setTextColor(Color.WHITE)
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }

        // Título
        layout.addView(TextView(this).apply {
            text = "⚙️ CONFIGURAÇÕES"
            textSize = 13f; setTextColor(Color.WHITE)
        })

        // Secção login
        layout.addView(addLabel("🔐 LOGIN AUTOMÁTICO"))
        layout.addView(TextView(this).apply { text = "TELEMÓVEL / UTILIZADOR"; textSize = 9f; setTextColor(Color.parseColor("#64748b")) })
        val userInput = addInput("Ex: 9XX XXX XXX", prefs.getString("user","") ?: "")
        layout.addView(userInput)
        layout.addView(TextView(this).apply { text = "SENHA"; textSize = 9f; setTextColor(Color.parseColor("#64748b")); setPadding(0,dp(8),0,dp(4)) })
        val passInput = addInput("A tua senha", prefs.getString("pass","") ?: "", true)
        layout.addView(passInput)

        layout.addView(addBtn("💾 Guardar e Entrar", "#16a34a") {
            val u = userInput.text.toString().trim()
            val p = passInput.text.toString().trim()
            if (u.isNotEmpty() && p.isNotEmpty()) {
                prefs.edit().putString("user", u).putString("pass", p).apply()
                dialog.dismiss()
                carregarSite()
            }
        })

        layout.addView(addBtn("🗑️ Limpar Credenciais", "#991b1b") {
            prefs.edit().remove("user").remove("pass").apply()
            userInput.setText(""); passInput.setText("")
        })

        // Secção servidor
        layout.addView(addLabel("📡 SERVIDOR DE SINAIS"))
        layout.addView(TextView(this).apply { text = "URL DO SERVIDOR"; textSize = 9f; setTextColor(Color.parseColor("#64748b")) })
        val srvInput = addInput("http://SEU-IP:3000", prefs.getString("srv_url","") ?: "")
        layout.addView(srvInput)

        layout.addView(addBtn("📡 Ligar ao Servidor", "#3b82f6") {
            val s = srvInput.text.toString().trim()
            if (s.isNotEmpty()) {
                prefs.edit().putString("srv_url", s).apply()
                ligarServidor(s)
                dialog.dismiss()
            }
        })

        layout.addView(addBtn("🌍 Recarregar Site", "#0f766e") {
            dialog.dismiss(); carregarSite()
        })

        layout.addView(addBtn("✕ Fechar", "#1e1e2e") { dialog.dismiss() })

        val scroll = ScrollView(this).apply { addView(layout) }
        dialog.setView(scroll)
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    // ── SSE SINAIS ────────────────────────────────────────────────
    private fun ligarServidor(url: String) {
        sseCall?.cancel()
        setBarra("🔄 A ligar ao servidor...", "#3b82f6")

        sseClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()

        val req = Request.Builder().url("$url/sinais")
            .addHeader("Accept", "text/event-stream")
            .build()

        sseCall = sseClient!!.newCall(req)
        sseCall!!.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { setBarra("❌ Sem ligação ao servidor", "#ef4444") }
            }
            override fun onResponse(call: Call, response: Response) {
                runOnUiThread { setBarra("✅ Bot ativo! A monitorizar...", "#22c55e") }
                try {
                    val source = response.body?.source() ?: return
                    while (!call.isCanceled()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.startsWith("data:")) {
                            val json = line.removePrefix("data:").trim()
                            try {
                                val obj = JSONObject(json)
                                val msg = obj.optString("mensagem", "")
                                val nivel = obj.optString("nivel", "info")
                                val tipo = obj.optString("tipo", "")
                                runOnUiThread { processarSinal(tipo, msg, nivel) }
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {
                    runOnUiThread { setBarra("❌ Ligação perdida", "#ef4444") }
                }
            }
        })
    }

    private fun processarSinal(tipo: String, msg: String, nivel: String) {
        val cor = when (nivel) {
            "entrada" -> "#22c55e"
            "aviso"   -> "#facc15"
            "pausa"   -> "#ef4444"
            else      -> "#3b82f6"
        }
        setBarra(msg, cor)

        // Notificação para sinais importantes
        if (tipo == "SINAL_ROSA" || tipo == "PADRAO_TEMPO") {
            mostrarNotificacao(msg)
        }
    }

    private fun setBarra(msg: String, cor: String) {
        msgText.text = msg
        msgText.setTextColor(Color.parseColor(cor))
        dotView.post { dotView.background = createCircle(cor) }
    }

    private fun mostrarNotificacao(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun createCircle(color: String): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.parseColor(color))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        sseCall?.cancel()
        webView.destroy()
    }
}
