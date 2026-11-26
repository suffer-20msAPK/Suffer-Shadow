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
        val jsonArray = org.json.JSONArray(response)
        
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
        
        val updateJson = org.json.JSONObject().put("is_used", true)
        val os = OutputStreamWriter(updateConn.outputStream)
        os.write(updateJson.toString())
        os.flush()
        os.close()
        
        // 3. Süreyi hesapla ve cihaza kaydet
        val days = codeData.optInt("duration_days", 30)
        val endDateMs = System.currentTimeMillis() + (days * 24L * 60L * 60L * 1000L)
        
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val endDateStr = sdf.format(java.util.Date(endDateMs))
        
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_IS_VIP, true)
            .putString(KEY_VIP_END, endDateStr)
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .apply()

        // Opsiyonel: vip_subscriptions tablosuna da yaz (İleride cihaz değişirse diye)
        try {
            val vipUrl = URL("$SUPABASE_URL/rest/v1/vip_subscriptions")
            val vipConn = vipUrl.openConnection() as HttpURLConnection
            vipConn.requestMethod = "POST"
            vipConn.doOutput = true
            vipConn.setRequestProperty("apikey", SUPABASE_ANON_KEY)
            vipConn.setRequestProperty("Authorization", "Bearer $SUPABASE_ANON_KEY")
            vipConn.setRequestProperty("Content-Type", "application/json")
            vipConn.setRequestProperty("Prefer", "resolution=merge-duplicates")
            
            val vipJson = org.json.JSONObject().apply {
                put("device_id", deviceId)
                put("start_date", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(java.util.Date()))
                put("end_date", endDateStr + "Z")
                put("is_active", true)
            }
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
