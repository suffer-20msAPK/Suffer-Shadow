@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.suffershadow.vpn

import android.app.Activity
import android.content.Context
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

val BgDark = Color(0xFF0B1020)
val CardBg = Color(0xFF151B33)
val AccentGreen = Color(0xFF00FFAA)
val PurpleMain = Color(0xFF6200EA)
val RedSoft = Color(0xFFFF6B6B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SufferShadowTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = BgDark) {
                    AppRoot()
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

fun serverStatus(p: Int?, real: Boolean, lang: String): Pair<String, Color> {
    val tm = lang == "tm"
    if (real && VpnState.delayMs >= 0) {
        return Pair((if (tm) "Birikdi • " else "Bağlı • ") + VpnState.delayMs + " ms", AccentGreen)
    }
    if (real && VpnState.delayMs == -2) {
        return Pair(if (tm) "Birikdi, emma internet ýok" else "Bağlı ama internet yok", RedSoft)
    }
    return when {
        p == null -> Pair("—", Color.Gray)
        p == -2 -> Pair("...", Color.Gray)
        p == -1 -> Pair(if (tm) "Işlemeýär" else "Çalışmıyor", RedSoft)
        else -> Pair(
            (if (tm) "Işleýär" else "Çalışıyor") + " • $p ms",
            when {
                p < 150 -> AccentGreen
                p < 400 -> Color(0xFFFFD54F)
                else -> Color(0xFFFF9800)
            }
        )
    }
}

@Composable
fun rememberVpnConnect(): (Server) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Server?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode == Activity.RESULT_OK) startVpnService(context, pending)
    }
    return { s ->
        val intent = VpnService.prepare(context)
        if (intent != null) {
            pending = s
            launcher.launch(intent)
        } else {
            startVpnService(context, s)
        }
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val prefs = remember { context.getSharedPreferences("suffer", Context.MODE_PRIVATE) }

    var lang by remember { mutableStateOf(prefs.getString("lang", "tr") ?: "tr") }
    val tm = lang == "tm"

    val servers = remember {
        val list = mutableStateListOf<Server>()
        var saved = prefs.getString("links", "") ?: ""
        if (saved.isEmpty()) saved = prefs.getString("link", "") ?: ""
        saved.split("\n").forEach { l -> parseServer(l)?.let { list.add(it) } }
        list
    }
    val pings = remember { mutableStateMapOf<String, Int>() }
    var selected by remember { mutableStateOf(prefs.getInt("sel", 0)) }
    var tab by remember { mutableStateOf(0) }
    var showServers by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    val connect = rememberVpnConnect()
    val toggleVpn = rememberVpnToggle { servers.getOrNull(selected) }

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

    val sel = if (servers.isEmpty()) -1 else selected.coerceIn(0, servers.size - 1)
    val current = servers.getOrNull(sel)

    BackHandler(enabled = showServers) { showServers = false }

    val onSelect: (Int) -> Unit = { i ->
        selected = i
        save()
        showServers = false
        val s = servers.getOrNull(i)
        if (s != null) {
            if (VpnState.status == VpnState.CONNECTED || VpnState.status == VpnState.CONNECTING) {
                stopVpnService(context)
                Handler(Looper.getMainLooper()).postDelayed({ connect(s) }, 900)
            } else {
                connect(s)
            }
        }
    }

    val onDelete: (Int) -> Unit = { i ->
        val s = servers.getOrNull(i)
        if (s != null) {
            if (i == sel &&
                (VpnState.status == VpnState.CONNECTED || VpnState.status == VpnState.CONNECTING)
            ) {
                stopVpnService(context)
            }
            pings.remove(s.link)
            servers.removeAt(i)
            if (selected >= servers.size) {
                selected = if (servers.isEmpty()) 0 else servers.size - 1
            }
            save()
        }
    }

    val onAdd: () -> Unit = {
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
            message = if (tm) "$added serwer goşuldy" else "$added sunucu eklendi"
        } else {
            message = if (tm) "Goşup bolmady. Link nädogry ýa-da eýýäm bar"
            else "Eklenemedi. Link geçersiz ya da zaten var"
        }
    }

    Scaffold(
        containerColor = BgDark,
        contentColor = Color.White,
        bottomBar = {
            NavigationBar(containerColor = CardBg) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Text("🏠") },
                    label = { Text(if (tm) "Baş sahypa" else "Ana menü") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1; showServers = false },
                    icon = { Text("⚙️") },
                    label = { Text(if (tm) "Aýratynlyklar" else "Özellikler") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2; showServers = false },
                    icon = { Text("👤") },
                    label = { Text("Profil") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                tab == 0 && !showServers -> HomeTab(
                    lang = lang,
                    current = current,
                    ping = current?.let { pings[it.link] },
                    onToggle = { toggleVpn() },
                    onOpenServers = { showServers = true },
                    onLang = { l ->
                        lang = l
                        prefs.edit().putString("lang", l).apply()
                    }
                )
                tab == 0 && showServers -> ServersTab(
                    lang = lang,
                    servers = servers,
                    pings = pings,
                    selected = sel,
                    onSelect = onSelect,
                    onDelete = onDelete,
                    onRefresh = { servers.forEach { measure(it) } },
                    onBack = { showServers = false }
                )
                tab == 1 -> FeaturesTab(
                    lang = lang,
                    input = input,
                    message = message,
                    onInput = { input = it },
                    onPaste = {
                        val text = clipboard.getText()?.text ?: ""
                        if (text.isNotEmpty()) input = text
                    },
                    onAdd = onAdd
                )
                else -> ProfileTab()
            }
        }
    }
}

@Composable
fun HomeTab(
    lang: String,
    current: Server?,
    ping: Int?,
    onToggle: () -> Unit,
    onOpenServers: () -> Unit,
    onLang: (String) -> Unit
) {
    val tm = lang == "tm"
    val connected = VpnState.status == VpnState.CONNECTED
    val busy = VpnState.status == VpnState.CONNECTING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onLang("tr") }) {
                Text("TR", color = if (lang == "tr") AccentGreen else Color.Gray)
            }
            TextButton(onClick = { onLang("tm") }) {
                Text("TM", color = if (lang == "tm") AccentGreen else Color.Gray)
            }
        }

        Text(
            text = "SUFFER SHADOW",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 4.sp
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = vpnStatusText(lang),
            color = when {
                VpnState.status == VpnState.ERROR -> RedSoft
                connected && VpnState.delayMs == -2 -> Color(0xFFFFD54F)
                connected -> AccentGreen
                else -> Color.Gray
            },
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(220.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = if (connected) listOf(AccentGreen, Color.Transparent)
                        else listOf(PurpleMain, Color.Transparent)
                    )
                )
        ) {
            Button(
                onClick = onToggle,
                enabled = current != null,
                modifier = Modifier.size(150.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (connected) AccentGreen else PurpleMain
                )
            ) {
                Text(
                    text = if (connected || busy) (if (tm) "ÖÇÜR" else "KAPAT")
                    else (if (tm) "GOŞUL" else "BAĞLAN"),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        val st = serverStatus(ping, connected, lang)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CardBg)
                .clickable { onOpenServers() }
                .padding(16.dp)
        ) {
            Text(
                text = current?.name ?: (if (tm) "Serwer saýlanmady" else "Sunucu seçilmedi"),
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            if (current != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = st.first, color = st.second, fontSize = 14.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onOpenServers,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = PurpleMain)
        ) {
            Text(if (tm) "Serwerler" else "Sunucular", color = Color.White)
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
fun ServersTab(
    lang: String,
    servers: List<Server>,
    pings: Map<String, Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    val tm = lang == "tm"
    val connected = VpnState.status == VpnState.CONNECTED

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text("←", color = Color.White, fontSize = 22.sp)
            }
            Text(
                text = (if (tm) "Serwerler" else "Sunucular") + " (${servers.size})",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRefresh) {
                Text(if (tm) "Täzele" else "Yenile", color = AccentGreen)
            }
        }

        if (servers.isEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (tm) "Heniz serwer ýok. Aýratynlyklar bölüminden link goş."
                else "Henüz sunucu yok. Özellikler bölümünden link ekle.",
                color = Color.Gray,
                fontSize = 14.sp
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(servers) { index, s ->
                val isSel = index == selected
                val st = serverStatus(pings[s.link], isSel && connected, lang)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isSel) Color(0xFF2A1B5E) else CardBg)
                        .clickable { onSelect(index) }
                        .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = s.name,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(text = st.first, color = st.second, fontSize = 13.sp)
                    }
                    TextButton(onClick = { onDelete(index) }) {
                        Text(if (tm) "Poz" else "Sil", color = RedSoft)
                    }
                }
            }
        }
    }
}

@Composable
fun FeaturesTab(
    lang: String,
    input: String,
    message: String,
    onInput: (String) -> Unit,
    onPaste: () -> Unit,
    onAdd: () -> Unit
) {
    val tm = lang == "tm"
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text(
            text = if (tm) "Aýratynlyklar" else "Özellikler",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CardBg)
                .padding(16.dp)
        ) {
            Text(
                text = "1. " + (if (tm) "Link goş" else "Link yapıştır"),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = input,
                onValueChange = onInput,
                label = {
                    Text(
                        if (tm) "vless:// ýa-da ss:// linkler (her setire bir)"
                        else "vless:// veya ss:// linkleri (her satıra bir tane)"
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 5
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPaste) {
                    Text(if (tm) "Ýelimle" else "Yapıştır")
                }
                Button(onClick = onAdd) {
                    Text(if (tm) "Goş" else "Ekle")
                }
            }
            if (message.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = message, color = Color.LightGray, fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        SoonCard("2. " + (if (tm) "Programma bloklaýjy" else "Engelleyici"), lang)
        Spacer(modifier = Modifier.height(12.dp))
        SoonCard("3. " + (if (tm) "Ulgamy arassala" else "Ağ temizle"), lang)
    }
}

@Composable
fun SoonCard(title: String, lang: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (lang == "tm") "Ýakynda" else "Yakında",
            color = Color.Gray,
            fontSize = 13.sp
        )
    }
}

@Composable
fun ProfileTab() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Profil", color = Color.Gray, fontSize = 18.sp)
    }
}

@Composable
fun SufferShadowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PurpleMain,
            background = BgDark
        ),
        content = content
    )
}
