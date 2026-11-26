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
    private const val SUPABASE_URL = "https://yeizclxibejfqhtfyqmi.supabase.co"
    private const val SUPABASE_ANON_KEY = "sb_publishable_U8scDtQmhf830Pb5r65jxw_paP_dRRa"

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
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("apikey", SUPABASE_ANON_KEY)
            // Authorization başlığını sildik, çakışma yapıyordu

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(response)
                return@withContext jsonArray.length() > 0
            } else {
                return@withContext false
            }
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
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("apikey", SUPABASE_ANON_KEY)
            conn.setRequestProperty("Prefer", "return=minimal")

            val os = OutputStreamWriter(conn.outputStream)
            os.write(json.toString())
            os.flush()
            os.close()

            if (conn.responseCode in 200..299) {
                return@withContext Pair(true, "Kayıt başarılı")
            } else {
                val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Bilinmeyen Hata"
                return@withContext Pair(false, "Kayıt başarısız: $errorBody")
            }
        } catch (e: Exception) {
            return@withContext Pair(false, "Hata: ${e.message}")
        }
    }

    suspend fun loginUser(phoneId: String, password: String, deviceId: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val hashedPass = hashPassword(password)
            val url = URL("$SUPABASE_URL/rest/v1/users?phone_id=eq.$phoneId&password=eq.$hashedPass&select=*")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("apikey", SUPABASE_ANON_KEY)

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(response)

                if (jsonArray.length() == 0) {
                    return@withContext Pair(false, "Numara veya şifre hatalı!")
                }

                val user = jsonArray.getJSONObject(0)
                val status = user.optString("status", "active")
                val currentDeviceId = user.optString("device_id", "")

                if (status == "suspended") {
                    return@withContext Pair(false, "Hesabınız askıya alınmış.")
                }

                if (currentDeviceId.isNotEmpty() && currentDeviceId != deviceId) {
                    val updateUrl = URL("$SUPABASE_URL/rest/v1/users?phone_id=eq.$phoneId")
                    val updateConn = updateUrl.openConnection() as HttpURLConnection
                    updateConn.requestMethod = "PATCH"
                    updateConn.doOutput = true
                    updateConn.connectTimeout = 5000
                    updateConn.readTimeout = 5000
                    updateConn.setRequestProperty("Content-Type", "application/json")
                    updateConn.setRequestProperty("apikey", SUPABASE_ANON_KEY)
                    
                    val updateJson = JSONObject().put("device_id", deviceId)
                    val os = OutputStreamWriter(updateConn.outputStream)
                    os.write(updateJson.toString())
                    os.flush()
                    os.close()
                    
                    return@withContext Pair(true, "Giriş başarılı. (Eski cihazın bağlantısı kesildi)")
                }
                
                return@withContext Pair(true, "Giriş başarılı")
            } else {
                val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Bilinmeyen Hata"
                return@withContext Pair(false, "Giriş başarısız: $errorBody")
            }
        } catch (e: Exception) {
            return@withContext Pair(false, "Hata: ${e.message}")
        }
    }
}
