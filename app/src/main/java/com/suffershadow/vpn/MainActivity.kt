@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.suffershadow.vpn

import android.content.Context
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SufferShadowTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F0F13)
                ) {
                    VpnScreen()
                }
            }
        }
    }
}

data class Server(val link: String, val name: String, val host: String, val port: Int)

fun parseServer(raw: String): Server? {
    val link = raw.trim()
    return try {
        val name = if (link.contains("#"))
            URLDecoder.decode(link.substringAfter("#"), "UTF-8") else ""
        val body = link.substringBefore("#")
        val hostPort: String
        when {
            body.startsWith("vless://") -> {
                hostPort = body.substringAfter("@").substringBefore("?").substringBefore("/")
            }
            body.startsWith("ss://") -> {
                val rest = body.removePrefix("ss://").substringBefore("?")
                hostPort = if (rest.contains("@")) {
                    rest.substringAfter("@").substringBefore("/")
                } else {
                    val fixed = rest.replace('-', '+').replace('_', '/')
                    String(Base64.decode(fixed, Base64.DEFAULT)).substringAfter("@")
                }
            }
            else -> return null
        }
        val host = hostPort.substringBeforeLast(":")
        val port = hostPort.substringAfterLast(":").trim().toInt()
        if (host.isEmpty()) null else Server(link, name.ifEmpty { host }, host, port)
    } catch (e: Exception) {
        null
    }
}

fun pingServer(s: Server): Int {
    return try {
        val start = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(s.host, s.port), 3000) }
        ((System.nanoTime() - start) / 1_000_000).toInt()
    } catch (e: Exception) {
        -1
    }
}

@Composable
fun VpnScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val prefs = remember { context.getSharedPreferences("suffer", Context.MODE_PRIVATE) }

    var lang by remember { mutableStateOf(prefs.getString("lang", "tr") ?: "tr") }
    val tx: (String, String) -> String = { trText, tmText -> if (lang == "tm") tmText else trText }

    val servers = remember {
        val list = mutableStateListOf<Server>()
        var saved = prefs.getString("links", "") ?: ""
        if (saved.isEmpty()) saved = prefs.getString("link", "") ?: ""
        saved.split("\n").forEach { l -> parseServer(l)?.let { list.add(it) } }
        list
    }
    val pings = remember { mutableStateMapOf<String, Int>() }
    var selected by remember { mutableStateOf(prefs.getInt("sel", 0)) }
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var isConnected by remember { mutableStateOf(false) }

    val save: () -> Unit = {
        prefs.edit()
            .putString("links", servers.joinToString("\n") { it.link })
            .putInt("sel", selected)
            .apply()
    }

    val measure: (Server) -> Unit = { s ->
        pings[s.link] = -2
        thread {
            val r = pingServer(s)
            pings[s.link] = r
        }
    }

    LaunchedEffect(Unit) {
        servers.forEach { measure(it) }
    }

    if (selected >= servers.size) selected = if (servers.isEmpty()) 0 else servers.size - 1
    val current = servers.getOrNull(selected)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            val setLang: (String) -> Unit = { l ->
                lang = l
                prefs.edit().putString("lang", l).apply()
            }
            TextButton(onClick = { setLang("tr") }) {
                Text("TR", color = if (lang == "tr") Color(0xFF00FFAA) else Color.Gray)
            }
            TextButton(onClick = { setLang("tm") }) {
                Text("TM", color = if (lang == "tm") Color(0xFF00FFAA) else Color.Gray)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "SUFFER SHADOW",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 4.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (isConnected)
                tx("Test modu (gerçek tünel henüz yok)", "Synag režimi (hakyky tunel entek ýok)")
            else tx("Ne zaman hazırsın?", "Haçan taýynsyň?"),
            color = if (isConnected) Color(0xFF00FFAA) else Color.Gray,
            fontSize = 15.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(200.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = if (isConnected) listOf(Color(0xFF00FFAA), Color.Transparent)
                        else listOf(Color(0xFF6200EA), Color.Transparent)
                    )
                )
        ) {
            Button(
                onClick = { isConnected = !isConnected },
                enabled = current != null,
                modifier = Modifier.size(140.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected) Color(0xFF00FFAA) else Color(0xFF6200EA)
                )
            ) {
                Text(
                    text = if (isConnected) tx("KAPAT", "ÖÇÜR") else tx("BAĞLAN", "GOŞUL"),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = current?.name ?: tx("Sunucu seçilmedi", "Serwer saýlanmady"),
            color = Color.LightGray,
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tx("Sunucular", "Serwerler") + " (${servers.size})",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { servers.forEach { measure(it) } }) {
                Text(tx("Yenile", "Täzele"))
            }
        }

        if (servers.isEmpty()) {
            Text(
                text = tx("Henüz sunucu yok. Aşağıdan link ekle.",
                    "Heniz serwer ýok. Aşakdan link goş."),
                color = Color.Gray,
                fontSize = 13.sp
            )
        }

        servers.forEachIndexed { index, s ->
            val p = pings[s.link]
            val isSel = index == selected
            val statusText = when {
                p == null -> "—"
                p == -2 -> "..."
                p == -1 -> tx("Çalışmıyor", "Işlemeýär")
                else -> tx("Çalışıyor", "Işleýär") + " • $p ms"
            }
            val statusColor = when {
                p == null || p == -2 -> Color.Gray
                p == -1 -> Color(0xFFFF6B6B)
                p < 150 -> Color(0xFF00FFAA)
                p < 400 -> Color(0xFFFFD54F)
                else -> Color(0xFFFF9800)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSel) Color(0xFF2A1B5E) else Color(0xFF1A1A22))
                    .clickable {
                        selected = index
                        save()
                    }
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = s.name,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = statusText,
                        color = statusColor,
                        fontSize = 13.sp
                    )
                }
                TextButton(onClick = {
                    pings.remove(s.link)
                    servers.removeAt(index)
                    if (selected >= servers.size) {
                        selected = if (servers.isEmpty()) 0 else servers.size - 1
                    }
                    save()
                }) {
                    Text(tx("Sil", "Poz"), color = Color(0xFFFF6B6B))
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = {
                Text(tx("vless:// veya ss:// linkleri (her satıra bir tane)",
                    "vless:// ýa-da ss:// linkler (her setire bir)"))
            },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 5
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val text = clipboard.getText()?.text ?: ""
                if (text.isNotEmpty()) input = text
            }) {
                Text(tx("Yapıştır", "Ýelimle"))
            }
            Button(onClick = {
                var added = 0
                input.split("\n").forEach { line ->
                    val s = parseServer(line)
                    if (s != null && servers.none { it.link == s.link }) {
                        servers.add(s)
                        measure(s)
                        added++
                    }
                }
                if (added > 0) {
                    input = ""
                    save()
                    message = if (lang == "tm") "$added serwer goşuldy" else "$added sunucu eklendi"
                } else {
                    message = tx("Eklenemedi. Link geçersiz ya da zaten var",
                        "Goşup bolmady. Link nädogry ýa-da eýýäm bar")
                }
            }) {
                Text(tx("Ekle", "Goş"))
            }
        }
        if (message.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = message, color = Color.LightGray, fontSize = 13.sp)
        }
        Spacer(modifier = Modifier.height(30.dp))
    }
}

@Composable
fun SufferShadowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF6200EA),
            background = Color(0xFF0F0F13)
        ),
        content = content
    )
}
