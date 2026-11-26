package com.suffershadow.vpn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object UserManager {
    // RedeemManager'daki aynı URL ve Key'i kullanıyoruz
    private const val SUPABASE_URL = "https://yeizclxibejfqhtfyqmi.supabase.co"
    private const val SUPABASE_ANON_KEY = "sb_publishable_U8scDtQmhf830Pb5r65jxw_paP_dRRa"

    // Şifreyi güvenli bir şekilde gizlemek için SHA-256 kullanıyoruz
    fun hashPassword(password: String): String {
        val bytes = password.toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.fold("") { str, it -> str + "%02x".format(it) }
    }

    suspend fun checkUserExists(phoneId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$SUPABASE_URL/rest/v1/users?phone_id=eq.$phoneId&select=id")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("apikey", SUPABASE_ANON_KEY)
            conn.setRequestProperty("Authorization", "Bearer $SUPABASE_ANON_KEY")
            
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val jsonArray = JSONArray(response)
            return@withContext jsonArray.length() > 0 // Eğer 1 veya daha fazla sonuç döndüyse kullanıcı vardır
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }

    suspend fun registerUser(phoneId: String, password: String, deviceId: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val hashedPass = hashPassword(password)
            val json = JSONObject().apply {
                put("phone_id", phoneId)
                put("password", hashedPass)
                put("device_id", deviceId)
                put("status", "active")
            }

            val url = URL("$SUPABASE_URL/rest/v1/users")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("apikey", SUPABASE_ANON_KEY)
            conn.setRequestProperty("Authorization", "Bearer $SUPABASE_ANON_KEY")
            conn.setRequestProperty("Prefer", "return=minimal")

            val os = OutputStreamWriter(conn.outputStream)
            os.write(json.toString())
            os.flush()
            os.close()

            if (conn.responseCode in 200..299) {
                return@withContext Pair(true, "Kayıt başarılı")
            } else {
                return@withContext Pair(false, "Kayıt başarısız oldu: ${conn.responseCode}")
            }
        } catch (e: Exception) {
            return@withContext Pair(false, "Hata: ${e.message}")
        }
    }

    suspend fun loginUser(phoneId: String, password: String, deviceId: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val hashedPass = hashPassword(password)
            // Hem numarayı hem şifreyi kontrol ediyoruz
            val url = URL("$SUPABASE_URL/rest/v1/users?phone_id=eq.$phoneId&password=eq.$hashedPass&select=*")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("apikey", SUPABASE_ANON_KEY)
            conn.setRequestProperty("Authorization", "Bearer $SUPABASE_ANON_KEY")
            
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val jsonArray = JSONArray(response)

            if (jsonArray.length() == 0) {
                return@withContext Pair(false, "Numara veya şifre hatalı!")
            }

            val user = jsonArray.getJSONObject(0)
            val status = user.optString("status", "active")
            val currentDeviceId = user.optString("device_id", "")

            if (status == "suspended") {
                return@withContext Pair(false, "Hesabınız askıya alınmış. Destek ile iletişime geçin.")
            }

            // CİHAZ KONTROLÜ
            if (currentDeviceId.isNotEmpty() && currentDeviceId != deviceId) {
                // Eğer farklı bir cihazdan giriş yapılıyorsa eski cihazı at ve yeni cihazı kaydet
                val updateUrl = URL("$SUPABASE_URL/rest/v1/users?phone_id=eq.$phoneId")
                val updateConn = updateUrl.openConnection() as HttpURLConnection
                updateConn.requestMethod = "PATCH"
                updateConn.doOutput = true
                updateConn.setRequestProperty("Content-Type", "application/json")
                updateConn.setRequestProperty("apikey", SUPABASE_ANON_KEY)
                updateConn.setRequestProperty("Authorization", "Bearer $SUPABASE_ANON_KEY")
                
                val updateJson = JSONObject().put("device_id", deviceId)
                val os = OutputStreamWriter(updateConn.outputStream)
                os.write(updateJson.toString())
                os.flush()
                os.close()
                
                return@withContext Pair(true, "Giriş başarılı. (Başka bir cihazdan giriş yapıldı, eski cihazın bağlantısı kesildi)")
            }
            
            return@withContext Pair(true, "Giriş başarılı")
        } catch (e: Exception) {
            return@withContext Pair(false, "Hata: ${e.message}")
        }
    }
}
