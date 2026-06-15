package ao.elephantbet.aviatorbot

import android.os.Handler
import android.os.Looper
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cliente WebSocket para Supabase Realtime.
 * Gere ligação, reconexão automática e envio de frames.
 */
class RealtimeWsClient(
    private val supaUrl: String,
    private val supaKey: String,
    private val deviceId: String,
    private val onPedirTela: () -> Unit,
    private val onPararTela: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val client  = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var ligado = false

    // Canal Supabase Realtime para este device
    private val canal = "screen_stream_$deviceId"

    fun ligar() {
        // Supabase Realtime WebSocket URL
        val wsUrl = supaUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/') + "/realtime/v1/websocket?apikey=$supaKey&vsn=1.0.0"

        val request = Request.Builder().url(wsUrl).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                ligado = true
                // Subscrever canal do device para receber comandos do painel
                val join = JSONObject().apply {
                    put("topic", "realtime:$canal")
                    put("event", "phx_join")
                    put("payload", JSONObject())
                    put("ref", "1")
                }
                webSocket.send(join.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    val event   = msg.optString("event")
                    val payload = msg.optJSONObject("payload") ?: return

                    when (event) {
                        "broadcast" -> {
                            val innerEvent = payload.optString("event")
                            when (innerEvent) {
                                "pedir_tela" -> handler.post { onPedirTela() }
                                "parar_tela" -> handler.post { onPararTela() }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ligado = false
                // Reconectar após 5s
                handler.postDelayed({ ligar() }, 5000)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ligado = false
            }
        })
    }

    fun enviar(dados: Map<String, Any>) {
        if (!ligado) return
        try {
            val payload = JSONObject().apply {
                put("type", "broadcast")
                put("event", dados["event"] as? String ?: "frame")
                val inner = JSONObject()
                dados.forEach { (k, v) -> inner.put(k, v) }
                put("payload", inner)
            }
            val msg = JSONObject().apply {
                put("topic", "realtime:$canal")
                put("event", "broadcast")
                put("payload", payload)
                put("ref", System.currentTimeMillis().toString())
            }
            ws?.send(msg.toString())
        } catch (_: Exception) {}
    }

    fun desligar() {
        ligado = false
        ws?.close(1000, "Desligar")
        ws = null
    }
}
