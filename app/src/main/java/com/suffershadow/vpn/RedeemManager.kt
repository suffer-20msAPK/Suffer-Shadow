package com.suffershadow.vpn

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

object RedeemManager {
    // Senin Supabase Bilgilerin
    private const val SUPABASE_URL = "https://yeizclxibejfqhtfyqmi.supabase.co"
    private const val SUPABASE_ANON_KEY = "sb_publishable_U8scDtQmhf830Pb5r65jxw_paP_dRRa"
    
    private const val PREF_NAME = "suffer_vip"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_VIP_END = "vip_end_date"
    private const val KEY_IS_VIP = "is_vip"
    private const val KEY_LAST_CHECK = "last_check_time"

    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    suspend fun checkVip(context: Context): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        val deviceId = getDeviceId(context)
        val json = JSONObject().put("deviceId", deviceId)
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        
        try {
            val response = postRequest("$SUPABASE_URL/functions/v1/check-vip", json.toString())
            val resJson = JSONObject(response)
            if (resJson.getString("status") == "ok") {
                val isVip = resJson.getBoolean("is_vip")
                val endDate = resJson.optString("end_date", null)
                
                // Başarılıysa yerel önbelleğe kaydet
                prefs.edit()
                    .putBoolean(KEY_IS_VIP, isVip)
                    .putString(KEY_VIP_END, endDate)
                    .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                    .apply()
                
                return@withContext Pair(isVip, endDate)
            }
        } catch (e: Exception) {
            // İnternet yoksa yerel önbelleği kontrol et
            val cachedIsVip = prefs.getBoolean(KEY_IS_VIP, false)
            val cachedEnd = prefs.getString(KEY_VIP_END, null)
            val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0L)
            val now = System.currentTimeMillis()

            // SAAT HİLESİ KORUMASI: Eğer cihaz saati son kontrol zamanından geriye alınmışsa, önbelleği reddet
            if (now < lastCheck) {
                return@withContext Pair(false, null) 
            }

            if (cachedIsVip && cachedEnd != null) {
                // Tarihi güvenli bir şekilde (UTC) oku ve cihaz saatiyle karşılaştır
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val endDate = try { sdf.parse(cachedEnd.substringBefore(".")) } catch (ex: Exception) { null }
                
                if (endDate != null && Date(now).after(endDate)) {
                    return@withContext Pair(false, cachedEnd) // Süre çevrimdışıyken dolmuş
                }
                return@withContext Pair(true, cachedEnd) // Çevrimdışı ama süresi henüz dolmamış
            }
            return@withContext Pair(false, null)
        }
        return@withContext Pair(false, null)
    }

    suspend fun redeemCode(context: Context, code: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val deviceId = getDeviceId(context)
        val json = JSONObject().put("code", code).put("deviceId", deviceId)
        
        try {
            val response = postRequest("$SUPABASE_URL/functions/v1/redeem", json.toString())
            val resJson = JSONObject(response)
            if (resJson.getString("status") == "ok") {
                val endDate = resJson.optString("end_date", null)
                val msg = resJson.optString("message", "VIP aktif")
                
                val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putBoolean(KEY_IS_VIP, true)
                    .putString(KEY_VIP_END, endDate)
                    .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                    .apply()
                
                return@withContext Pair(true, msg)
            } else {
                return@withContext Pair(false, resJson.optString("message", "Bilinmeyen hata"))
            }
        } catch (e: Exception) {
            return@withContext Pair(false, "Sunucuya ulaşılamadı. İnternetinizi kontrol edin.")
        }
    }

    private fun postRequest(urlString: String, body: String): String {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $SUPABASE_ANON_KEY")
        conn.setRequestProperty("apikey", SUPABASE_ANON_KEY)
        
        val os = OutputStreamWriter(conn.outputStream)
        os.write(body)
        os.flush()
        os.close()

        val responseCode = conn.responseCode
        val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
        return stream.bufferedReader().use { it.readText() }
    }
}
