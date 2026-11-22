package com.suffershadow.vpn

import android.util.Base64

data class VpnConfig(
    val protocol: String,
    val server: String,
    val port: Int,
    val passwordOrUuid: String,
    val method: String = "",
    val sni: String = "",
    val pbk: String = ""
)

object VpnParser {

    // Senin verdiğin Shadowsocks (ss://) kodunu ayrıştıran fonksiyon
    fun parseSS(ssUrl: String): VpnConfig? {
        try {
            val cleanUrl = ssUrl.substringAfter("ss://").substringBefore("#")
            val decoded = String(Base64.decode(cleanUrl, Base64.DEFAULT))
            val parts = decoded.split("@", ":")
            return VpnConfig(
                protocol = "Shadowsocks",
                server = parts[parts.size - 2],
                port = parts.last().toInt(),
                method = parts[0],
                passwordOrUuid = parts[1]
            )
        } catch (e: Exception) { 
            return null 
        }
    }

    // Senin verdiğin VLESS Reality (vless://) kodunu ayrıştıran fonksiyon
    fun parseVLESS(vlessUrl: String): VpnConfig? {
        try {
            val uri = java.net.URI(vlessUrl)
            val params = uri.query.split("&").associate {
                val (key, value) = it.split("=")
                key to value
            }
            return VpnConfig(
                protocol = "VLESS Reality",
                server = uri.host,
                port = uri.port,
                passwordOrUuid = uri.userInfo,
                sni = params["sni"] ?: "",
                pbk = params["pbk"] ?: ""
            )
        } catch (e: Exception) { 
            return null 
        }
    }
}
