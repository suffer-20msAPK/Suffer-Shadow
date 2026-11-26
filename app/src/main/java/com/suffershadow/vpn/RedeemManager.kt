package com.suffershadow.vpn

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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

            // SAAT HİLESİ KORUMASI
            if (now < lastCheck) {
                return@withContext Pair(false, null) 
            }

            if (cachedIsVip && cachedEnd != null) {
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val endDate = try { sdf.parse(cachedEnd.substringBefore(".")) } catch (ex: Exception) { null }
                
                if (endDate != null && Date(now).after(endDate)) {
                    return@withContext Pair(false, cachedEnd)
                }
                return@withContext Pair(true, cachedEnd)
            }
            return@withContext Pair(false, null)
        }
        return@withContext Pair(false, null)
    }

    // VIP KODU DOĞRUDAN VERİTABANINDAN OKUYAN FONKSİYON
    suspend fun redeemCode(context: Context, code: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val deviceId = getDeviceId(context)
        val cleanCode = code.trim().toUpperCase()
        
        try {
            // 1. Kodu doğrudan veritabanından sorgula
            val url = URL("$SUPABASE_URL/rest/v1/redeem_codes?code=eq.$cleanCode&select=*")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("apikey", SUPABASE_ANON_KEY)
            conn.setRequestProperty("Authorization", "Bearer $SUPABASE_ANON_KEY")

            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                return@withContext Pair(false, "Sunucuya bağlanılamadı. (Kod: $responseCode)")
            }

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val jsonArray = JSONArray(response)
            
            if (jsonArray.length() == 0) {
                return@withContext Pair(false, "Geçersiz kod.")
            }
            
            val codeData = jsonArray.getJSONObject(0)
            val isUsed = codeData.optBoolean("is_used", false)
            
            if (isUsed) {
                return@withContext Pair(false, "Bu kod daha önce kullanılmış.")
            }
            
            // 2. Kodu "kullanıldı" olarak işaretle
            val updateUrl = URL("$SUPABASE_URL/rest/v1/redeem_codes?code=eq.$cleanCode")
            val updateConn = updateUrl.openConnection() as HttpURLConnection
            updateConn.requestMethod = "PATCH"
            updateConn.doOutput = true
            updateConn.connectTimeout = 5000
            updateConn.readTimeout = 5000
            updateConn.setRequestProperty("apikey", SUPABASE_ANON_KEY)
            updateConn.setRequestProperty("Authorization", "Bearer $SUPABASE_ANON_KEY")
            updateConn.setRequestProperty("Content-Type", "application/json")
            
            val updateJson = JSONObject()
            updateJson.put("is_used", true)
            val os = OutputStreamWriter(updateConn.outputStream)
            os.write(updateJson.toString())
            os.flush()
            os.close()
            
            // 3. Süreyi hesapla ve cihaza kaydet
            val days = codeData.optInt("duration_days", 30)
            val endDateMs = System.currentTimeMillis() + (days * 24L * 60L * 60L * 1000L)
            
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val endDateStr = sdf.format(Date(endDateMs))
            
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean(KEY_IS_VIP, true)
                .putString(KEY_VIP_END, endDateStr)
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                .apply()

            // Opsiyonel: vip_subscriptions tablosuna da yaz
            try {
                val vipUrl = URL("$SUPABASE_URL/rest/v1/vip_subscriptions")
                val vipConn = vipUrl.openConnection() as HttpURLConnection
                vipConn.requestMethod = "POST"
                vipConn.doOutput = true
                vipConn.setRequestProperty("apikey", SUPABASE_ANON_KEY)
                vipConn.setRequestProperty("Authorization", "Bearer $SUPABASE_ANON_KEY")
                vipConn.setRequestProperty("Content-Type", "application/json")
                vipConn.setRequestProperty("Prefer", "resolution=merge-duplicates")
                
                val vipJson = JSONObject()
                vipJson.put("device_id", deviceId)
                vipJson.put("start_date", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()))
                vipJson.put("end_date", endDateStr + "Z")
                vipJson.put("is_active", true)
                
                val vos = OutputStreamWriter(vipConn.outputStream)
                vos.write(vipJson.toString())
                vos.flush()
                vos.close()
            } catch (e: Exception) {
                // VIP tablosuna yazamazsa bile kod kullanılmış sayılsın, hata vermesin
            }
                
            return@withContext Pair(true, "$days Günlük VIP Başarıyla Aktif Edildi!")
            
        } catch (e: Exception) {
            return@withContext Pair(false, "Bağlantı hatası: ${e.message}")
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
