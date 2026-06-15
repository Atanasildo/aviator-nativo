package ao.elephantbet.aviatorbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class NexusAccessibilityService : AccessibilityService() {

    private val executor     = Executors.newSingleThreadExecutor()
    private val handler      = Handler(Looper.getMainLooper())
    private var wsClient: RealtimeWsClient? = null
    private var transmitindo = false
    private var capturaJob: Runnable? = null

    private val JPEG_QUALITY: Int = 55
    private val INTERVALO_MS: Long = 67L

    companion object {
        var instance: NexusAccessibilityService? = null
        var supaUrl: String  = ""
        var supaKey: String  = ""
        var deviceId: String = ""
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        ligarRealtime()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        pararStream()
        wsClient?.desligar()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun ligarRealtime() {
        val url = supaUrl
        val key = supaKey
        val id: String = deviceId
        if (url.isEmpty()) {
            handler.postDelayed({ ligarRealtime() }, 2000L)
            return
        }
        wsClient = RealtimeWsClient(
            supaUrl  = url,
            supaKey  = key,
            deviceId = id,
            onPedirTela = { iniciarStream() },
            onPararTela  = { pararStream() }
        )
        wsClient!!.ligar()
    }

    fun iniciarStream() {
        if (transmitindo) return
        transmitindo = true

        wsClient?.enviar(mapOf(
            "event"     to "stream_iniciado",
            "device_id" to deviceId
        ))

        val loop = object : Runnable {
            override fun run() {
                if (!transmitindo) return
                capturarFrame()
                handler.postDelayed(this, INTERVALO_MS)
            }
        }
        capturaJob = loop
        handler.post(loop)
    }

    fun pararStream() {
        transmitindo = false
        capturaJob?.let { handler.removeCallbacks(it) }
        capturaJob = null
        wsClient?.enviar(mapOf(
            "event"     to "stream_parado",
            "device_id" to deviceId
        ))
    }

    private fun capturarFrame() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            wsClient?.enviar(mapOf(
                "event"     to "stream_erro",
                "device_id" to deviceId,
                "msg"       to "Android < 11 nao suporta takeScreenshot"
            ))
            pararStream()
            return
        }

        try {
            @Suppress("NewApi")
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val hardwareBmp = Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace
                            ) ?: return
                            screenshot.hardwareBuffer.close()

                            val softBmp = hardwareBmp.copy(Bitmap.Config.ARGB_8888, false)
                            hardwareBmp.recycle()

                            val targetW: Int = 540
                            val targetH: Int = (softBmp.height * targetW.toFloat() / softBmp.width).toInt()
                            val scaled = Bitmap.createScaledBitmap(softBmp, targetW, targetH, false)
                            softBmp.recycle()

                            val baos = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
                            scaled.recycle()

                            val b64: String = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                            val wStr: String = targetW.toString()
                            val hStr: String = targetH.toString()

                            wsClient?.enviar(mapOf(
                                "event"     to "frame",
                                "device_id" to deviceId,
                                "data"      to b64,
                                "w"         to wStr,
                                "h"         to hStr
                            ))
                        } catch (_: Exception) {}
                    }

                    override fun onFailure(errorCode: Int) {}
                }
            )
        } catch (_: Exception) {}
    }
}
