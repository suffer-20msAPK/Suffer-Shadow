@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.suffershadow.vpn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.json.JSONArray

internal val ShadowBgDark = Color(0xFF050505)
internal val ShadowBgTop = Color(0xFF0F0F11)
internal val ShadowCardBg = Color(0xFF121418)
internal val ShadowCardBg2 = Color(0xFF1A1C23)
internal val ShadowRedNeon = Color(0xFFFF263D)
internal val ShadowRedDeep = Color(0xFF9F1227)

internal val BgDark = ShadowBgDark
internal val BgTop = ShadowBgTop
internal val CardBg = ShadowCardBg
internal val CardBg2 = ShadowCardBg2
internal val PurpleMain = ShadowRedNeon
internal val PurpleLight = Color(0xFFFF4658)
internal val PurpleDeep = ShadowRedDeep
internal val AccentGreen = Color(0xFF20E28B)  
internal val RedSoft = Color(0xFFFF4658)
internal val TextMuted = Color(0xFFA0A6B4)
internal val BorderPurple = Color(0xFF9F1227)

private const val SUPABASE_URL = "http://46.8.225.27:8080"
private const val SUPABASE_ANON_KEY = "sb_publishable_U8scDtQmhf830Pb5r65jxw_paP_dRRa"
private const val VPS_AUTH_TOKEN = "suffer_shadow_gizli_anahtar_123"

internal enum class FeedbackType { TAP, CONNECT, DISCONNECT, ERROR }

object NavRequest {
    var seq by mutableStateOf(0)
    var tab = 0
    var servers = false
}

class MainActivity : ComponentActivity() {
    private fun handleNav(i: Intent?) {
        if (i != null && i.hasExtra("tab")) {
            NavRequest.tab = i.getIntExtra("tab", 0).coerceIn(0, 2)
            NavRequest.servers = i.getBooleanExtra("servers", false)
            NavRequest.seq++
            i.removeExtra("tab")
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNav(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OverlayContext.activity = this
        handleNav(intent)
        setContent {
            SufferShadowTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = BgDark) {
                    AppRoot()
                }
            }
        }
    }

    override fun onDestroy() {
        OverlayContext.activity = null
        super.onDestroy()
    }
}
data class Server(
    val link: String, 
    val name: String, 
    val host: String, 
    val port: Int,
    val isBuiltIn: Boolean = false,
    val category: String = "custom"
)

fun parseServer(raw: String, isBuiltIn: Boolean = false, category: String = "custom"): Server? {
    val link = raw.trim()
    if (link.startsWith("http://") || link.startsWith("https://")) return null 

    return try {
        val name = if (link.contains("#")) URLDecoder.decode(link.substringAfter("#"), "UTF-8") else ""
        val body = link.substringBefore("#")
        val hostPort: String
        when {
            body.startsWith("vless://") -> {
                hostPort = body.substringAfter("@").substringBefore("?").substringBefore("/")
            }
            body.startsWith("ss://") -> {
                val rest = body.removePrefix("ss://").substringBefore("?")
                hostPort = if (rest.contains("@")) {
                    rest.substringAfterLast("@").substringBefore("/")
                } else {
                    val fixed = rest.substringBefore("/").replace('-', '+').replace('_', '/')
                    String(Base64.decode(fixed, Base64.DEFAULT)).substringAfterLast("@")
                }
            }
            else -> return null
        }
        val host = hostPort.substringBeforeLast(":").removePrefix("[").removeSuffix("]")
        val port = hostPort.substringAfterLast(":").trim().toInt()
        if (host.isEmpty() || port !in 1..65535) null else Server(link, name.ifEmpty { host }, host, port, isBuiltIn, category)
    } catch (_: Exception) {
        null
    }
}

fun fetchSubscriptionServers(url: String, onResult: (List<Server>) -> Unit) {
    thread {
        val list = mutableListOf<Server>()
        var responseText: String? = null

        val userAgents = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Happ/1.0", "SufferShadow/1.0", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X)"
        )

        for (ua in userAgents) {
            if (responseText != null) break
            try {
                val conn = java.net.URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", ua)
                conn.setRequestProperty("Accept", "*/*")
                conn.instanceFollowRedirects = true
                if (conn.responseCode in 200..299) {
                    responseText = conn.inputStream.bufferedReader().use { it.readText() }
                }
                conn.disconnect()
            } catch (e: Exception) { }
        }

        if (responseText != null) {
            val decodedText = try {
                String(Base64.decode(responseText.trim(), Base64.DEFAULT))
            } catch (e: Exception) { responseText }

            decodedText.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    parseServer(trimmed, isBuiltIn = false, category = "custom")?.let { list.add(it) }
                }
            }
        }
        Handler(Looper.getMainLooper()).post { onResult(list) }
    }
}
fun pingServer(s: Server): Int {
    return try {
        val start = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(s.host, s.port), 3000) }
        ((System.nanoTime() - start) / 1_000_000).toInt()
    } catch (_: Exception) {
        -1
    }
}

fun fetchRemoteServers(category: String, context: Context, onResult: (List<Server>) -> Unit) {
    thread {
        val list = mutableListOf<Server>()
        try {
            val url = java.net.URL("$SUPABASE_URL/rest/v1/servers?category=eq.$category&select=*")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("apikey", SUPABASE_ANON_KEY)
            conn.setRequestProperty("X-VPS-Auth", VPS_AUTH_TOKEN)
            
            if (conn.responseCode in 200..299) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(response)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val link = obj.optString("link", "")
                    val name = obj.optString("name", "")
                    parseServer(link, isBuiltIn = true, category = category)?.let { 
                        list.add(it.copy(name = name)) 
                    }
                }
            } else {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Sunucu Hatası: ${conn.responseCode}", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) { 
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Bağlantı Hatası: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        Handler(Looper.getMainLooper()).post { onResult(list) }
    }
}

private fun protocolName(server: Server): String = when {
    server.link.startsWith("vless://", true) -> "VLESS"
    server.link.startsWith("ss://", true) -> "Shadowsocks"
    else -> "Bilinmiyor"
}

private fun serverStatus(p: Int?, real: Boolean, lang: String): Pair<String, Color> {
    val tm = lang == "tm"
    if (real && VpnState.delayMs >= 0) {
        return (if (tm) "Tunel gijikmesi • ${VpnState.delayMs} ms" else "Tünel gecikmesi • ${VpnState.delayMs} ms") to AccentGreen
    }
    if (real) {
        return (if (tm) "Birikdi" else "Bağlandı") to AccentGreen
    }
    
    return when {
        p == null || p <= 0 -> "Ölçülmedi" to TextMuted
        p == -2 -> "Ölçülüyor..." to TextMuted
        p == -1 -> (if (tm) "Port elýeterli däl" else "Port erişilemiyor") to Color(0xFFFF4658)
        p <= 600 -> "TCP: $p ms" to Color(0xFF20E28B)
        p <= 1200 -> "TCP: $p ms" to Color(0xFFFFC247)
        p <= 3000 -> "TCP: $p ms" to Color(0xFFFF4658)
        else -> "TCP: $p ms" to Color(0xFF9F1227)
    }
}

private fun calculateDaysLeft(endDateStr: String): Int {
    return try {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val endDate = sdf.parse(endDateStr.substringBefore("."))
        if (endDate != null) {
            val diff = endDate.time - System.currentTimeMillis()
            (diff / (1000 * 60 * 60 * 24)).toInt().coerceAtLeast(0)
        } else 0
    } catch (e: Exception) { 0 }
}
@Composable
private fun rememberUiFeedback(soundEnabled: Boolean, vibrationEnabled: Boolean): (FeedbackType) -> Unit {
    val haptic = LocalHapticFeedback.current
    val toneGenerator = remember { runCatching { ToneGenerator(AudioManager.STREAM_SYSTEM, 42) }.getOrNull() }
    DisposableEffect(toneGenerator) {
        onDispose { runCatching { toneGenerator?.release() } }
    }
    return remember(soundEnabled, vibrationEnabled, toneGenerator, haptic) {
        { type ->
            if (soundEnabled) {
                val tone = when (type) {
                    FeedbackType.TAP -> ToneGenerator.TONE_PROP_BEEP
                    FeedbackType.CONNECT -> ToneGenerator.TONE_PROP_ACK
                    FeedbackType.DISCONNECT -> ToneGenerator.TONE_PROP_NACK
                    FeedbackType.ERROR -> ToneGenerator.TONE_PROP_NACK
                }
                val duration = if (type == FeedbackType.TAP) 38 else 115
                runCatching { toneGenerator?.startTone(tone, duration) }
            }
            if (vibrationEnabled) {
                runCatching {
                    haptic.performHapticFeedback(
                        if (type == FeedbackType.TAP) HapticFeedbackType.TextHandleMove
                        else HapticFeedbackType.LongPress
                    )
                }
            }
        }
    }
}

@Composable
fun rememberVpnConnect(): (Server) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Server?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) startVpnService(context, pending)
    }
    return { server ->
        val intent = VpnService.prepare(context)
        if (intent != null) {
            pending = server
            launcher.launch(intent)
        } else {
            startVpnService(context, server)
        }
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val prefs = remember { context.getSharedPreferences("suffer", Context.MODE_PRIVATE) }
    var showVipScreen by remember { mutableStateOf(false) }
    var isVipUser by remember { mutableStateOf(false) }
    var vipDaysLeft by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val (isVip, endDateStr) = RedeemManager.checkVip(context)
        isVipUser = isVip
        if (isVip && endDateStr != null) {
            vipDaysLeft = calculateDaysLeft(endDateStr)
        }
    }
    var lang by remember { mutableStateOf(prefs.getString("lang", "tr") ?: "tr") }
var soundEnabled by remember { mutableStateOf(prefs.getBoolean("sound_enabled", true)) }
var vibrationEnabled by remember { mutableStateOf(prefs.getBoolean("vibration_enabled", true)) }
val feedback = rememberUiFeedback(soundEnabled, vibrationEnabled)

val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
LaunchedEffect(Unit) {
    if (android.os.Build.VERSION.SDK_INT >= 33 &&
        androidx.core.content.ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
        notifLauncher.launch("android.permission.POST_NOTIFICATIONS")
    }
}

val servers = remember {
    val list = mutableStateListOf<Server>()
    var saved = prefs.getString("links", "") ?: ""
    if (saved.isEmpty()) saved = prefs.getString("link", "") ?: ""
    saved.split("\n").forEach { line -> parseServer(line, isBuiltIn = false)?.let { if (list.none { old -> old.link == it.link }) list.add(it) } }
    list
}

val builtInSocial = remember { mutableStateListOf<Server>() }
val builtInPubg = remember { mutableStateListOf<Server>() }

val loadRemoteData = {
    fetchRemoteServers("social", context) { fetched ->
        builtInSocial.clear()
        builtInSocial.addAll(fetched)
    }
    fetchRemoteServers("pubg", context) { fetched ->
        builtInPubg.clear()
        builtInPubg.addAll(fetched)
    }
}

LaunchedEffect(Unit) { loadRemoteData() }

val pings = remember { mutableStateMapOf<String, Int>() }
var selected by remember { mutableStateOf(prefs.getInt("sel", 0)) }
var tab by remember { mutableStateOf(0) }
var showServers by remember { mutableStateOf(false) }

LaunchedEffect(NavRequest.seq) {
    if (NavRequest.seq > 0) {
        tab = NavRequest.tab
        showServers = NavRequest.servers
    }
}

LaunchedEffect(Unit) {
    BoosterState.load(prefs)
    if (BoosterState.enabled && !BoosterState.serviceRunning) startBoosterService(context)
}
var input by remember { mutableStateOf("") }
var message by remember { mutableStateOf("") }

val sessionMs = 2 * 60 * 60 * 1000L
var vpnTimeLeft by remember { mutableStateOf(7200) }
var sessionWasConnected by remember { mutableStateOf(false) }
var sessionExpired by remember { mutableStateOf(false) }

LaunchedEffect(VpnState.status) {
    if (prefs.getBoolean("session_expired", false)) {
        prefs.edit().putBoolean("session_expired", false).apply()
        sessionExpired = true
    }
    if (VpnState.status == VpnState.CONNECTED) {
        sessionWasConnected = true
        var end = prefs.getLong("session_end", 0L)
        if (end <= System.currentTimeMillis()) {
            end = System.currentTimeMillis() + sessionMs
            prefs.edit().putLong("session_end", end).apply()
        }
        while (VpnState.status == VpnState.CONNECTED) {
            end = prefs.getLong("session_end", end)
            val left = ((end - System.currentTimeMillis()) / 1000L).toInt()
            vpnTimeLeft = left.coerceAtLeast(0)
            if (left <= 0) {
                prefs.edit().remove("session_end").apply()
                stopVpnService(context)
                sessionExpired = true
                break
            }
            delay(500L)
        }
    } else if (VpnState.status != VpnState.CONNECTING) {
        prefs.edit().remove("session_end").apply()
        vpnTimeLeft = 7200
    }
}
val connect = rememberVpnConnect()
val allServers = servers + builtInSocial + builtInPubg
val toggleVpn = rememberVpnToggle { allServers.getOrNull(selected.coerceIn(0, (allServers.size - 1).coerceAtLeast(0))) }

val save: () -> Unit = {
    prefs.edit()
        .putString("links", servers.joinToString("\n") { it.link })
        .putInt("sel", selected)
        .apply()
}

val measure: (Server) -> Unit = { server ->
    pings[server.link] = -2
    thread {
        val result = pingServer(server)
        Handler(Looper.getMainLooper()).post { pings[server.link] = result }
    }
}

LaunchedEffect(servers) { servers.forEach { measure(it) } }

val selectedIndex = if (allServers.isEmpty()) -1 else selected.coerceIn(0, allServers.size - 1)
val current = allServers.getOrNull(selectedIndex)

LaunchedEffect(current?.link) {
    prefs.edit()
        .putString("active_name", current?.name ?: "")
        .putString("active_host", current?.host ?: "")
        .putInt("active_port", current?.port ?: 0)
        .apply()
}

val gameState = remember { GameToolsState(prefs) }
val gameScope = rememberCoroutineScope()

LaunchedEffect(current?.link) {
    gameState.history.clear()
    gameState.backupDismissedFor = null
    val target = current ?: return@LaunchedEffect
    while (true) {
        val ms = withContext(Dispatchers.IO) { pingServer(target) }
        val now = System.currentTimeMillis()
        gameState.history.add(GamePingSample(now, ms))
        while (gameState.history.isNotEmpty() && gameState.history[0].timeMs < now - 30 * 60_000L) {
            gameState.history.removeAt(0)
        }
        delay(3000L)
    }
}

val failStreak = gameState.history.takeLastWhile { it.ms < 0 }.size
val tunnelAlive = VpnState.status == VpnState.CONNECTED && VpnState.delayMs.toInt() >= 0
val needBackup = gameState.suggestBackup && current != null && failStreak >= 5 && !tunnelAlive &&
    gameState.backupDismissedFor != current.link
LaunchedEffect(needBackup, current?.link) {
    if (needBackup && current != null) {
        val others = allServers.filter { it.link != current.link }.take(20)
        gameState.backup = rankServers(others).firstOrNull { it.result.avg >= 0 && it.result.lossPct < 50 }
    } else {
        gameState.backup = null
    }
}

BackHandler(enabled = showServers) { showServers = false }

val onSelect: (Int) -> Unit = { index ->
    feedback(FeedbackType.TAP)
    selected = index
    save()
    showServers = false
    val server = allServers.getOrNull(index)
    if (server != null) {
        if (VpnState.status == VpnState.CONNECTED || VpnState.status == VpnState.CONNECTING) {
            stopVpnService(context)
            Handler(Looper.getMainLooper()).postDelayed({ connect(server) }, 900)
        } else {
            connect(server)
        }
    }
}

val onDelete: (Int) -> Unit = { index ->
    feedback(FeedbackType.TAP)
    val server = servers.getOrNull(index)
    if (server != null) {
        if (index == selectedIndex && (VpnState.status == VpnState.CONNECTED || VpnState.status == VpnState.CONNECTING)) {
            stopVpnService(context)
        }
        pings.remove(server.link)
        servers.removeAt(index)
        selected = when {
            servers.isEmpty() -> 0
            index < selected -> selected - 1
            selected >= servers.size -> servers.size - 1
            else -> selected
        }
        save()
    }
}
val onAdd: () -> Unit = {
    feedback(FeedbackType.TAP)
    var added = 0
    input.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
        if (line.startsWith("http://") || line.startsWith("https://")) {
            fetchSubscriptionServers(line) { fetchedList ->
                var subAdded = 0
                fetchedList.forEach { srv ->
                    if (servers.none { it.link == srv.link }) {
                        servers.add(srv)
                        measure(srv)
                        subAdded++
                    }
                }
                if (subAdded > 0) {
                    save()
                    message = if (lang == "tm") "$subAdded serwer goşuldy (Abonelik)" else "$subAdded sunucu eklendi (Abonelik)"
                } else {
                    message = if (lang == "tm") "Abonelik boş" else "Abonelik boş veya zaten ekli."
                }
            }
        } else {
            val server = parseServer(line, isBuiltIn = false)
            if (server != null && servers.none { it.link == server.link }) {
                servers.add(server)
                measure(server)
                added++
            }
        }
    }
    if (added > 0) {
        input = ""
        save()
        message = if (lang == "tm") "$added serwer goşuldy" else "$added sunucu eklendi"
    } else if (!input.contains("http")) {
        message = if (lang == "tm") "Goşup bolmady." else "Eklenemedi."
    }
    input = ""
}

val switchToServer: (Server) -> Unit = { target ->
    val idx = allServers.indexOfFirst { it.link == target.link }
    if (idx >= 0) {
        if (VpnState.status == VpnState.CONNECTED || VpnState.status == VpnState.CONNECTING) {
            onSelect(idx)
            gameScope.launch {
                delay(15000L)
                if (VpnState.status != VpnState.CONNECTED) {
                    android.widget.Toast.makeText(
                        context,
                        if (lang == "tm") "Ätiýaçlyk serwere geçip bolmady. Gaýtadan birikmegi synanyşyň." else "Yedek sunucuya geçilemedi. Tekrar bağlanmayı dene.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        } else {
            selected = idx
            save()
        }
    }
}

gameState.backup?.let { b ->
    GameBackupDialog(
        lang = lang,
        ranked = b,
        failedName = current?.name ?: "",
        auto = gameState.autoFailover,
        onSwitch = { gameState.backup = null; switchToServer(b.server) },
        onDismiss = { gameState.backupDismissedFor = current?.link; gameState.backup = null }
    )
}

if (sessionExpired) {
    AlertDialog(
        onDismissRequest = { sessionExpired = false },
        containerColor = CardBg,
        titleContentColor = Color.White,
        textContentColor = TextMuted,
        title = { Text(if (lang == "tm") "Wagt gutardy" else "Süre doldu", fontWeight = FontWeight.Bold) },
        text = { Text(if (lang == "tm") "2 sagatlyk sessiýa gutardy we VPN ýapyldy. Gaýtadan birikseňiz täze sessiýa başlanar." else "2 saatlik oturum doldu ve VPN kapatıldı. Yeniden bağlanırsan yeni oturum başlar.") },
        confirmButton = { TextButton(onClick = { sessionExpired = false }) { Text("OK", color = PurpleLight, fontWeight = FontWeight.Bold) } }
    )
}

if (showVipScreen) {
    VipScreen(
        prefs = prefs,
        onVipActivated = {
            isVipUser = true
            val localEnd = prefs.getString("vip_end_date", null)
            if (localEnd != null) vipDaysLeft = calculateDaysLeft(localEnd)
            
            scope.launch {
                kotlinx.coroutines.delay(1000)
                val (_, end) = RedeemManager.checkVip(context)
                if (end != null) vipDaysLeft = calculateDaysLeft(end)
            }
            showVipScreen = false
        },
        onDismiss = { showVipScreen = false }
    )
    return
}
    Scaffold(
        containerColor = BgDark,
        contentColor = Color.White,
        bottomBar = {
            NavigationBar(
                containerColor = ShadowBgTop,
                contentColor = TextMuted,
                tonalElevation = 0.dp
            ) {
                val navColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = ShadowRedNeon,
                    selectedTextColor = Color.White,
                    indicatorColor = Color(0xFF330000),
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted
                )
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { feedback(FeedbackType.TAP); tab = 0; showServers = false },
                    icon = { NavGlyph("home", if (tab == 0) ShadowRedNeon else TextMuted, Modifier.size(24.dp)) },
                    label = { Text(if (lang == "tm") "Baş sahypa" else "Ana Menü", fontSize = 11.sp) },
                    colors = navColors
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { feedback(FeedbackType.TAP); tab = 1; showServers = false },
                    icon = { NavGlyph("game", if (tab == 1) ShadowRedNeon else TextMuted, Modifier.size(24.dp)) },
                    label = { Text(if (lang == "tm") "Oýun" else "Oyun", fontSize = 11.sp) },
                    colors = navColors
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { feedback(FeedbackType.TAP); tab = 2; showServers = false },
                    icon = { NavGlyph("profile", if (tab == 2) ShadowRedNeon else TextMuted, Modifier.size(24.dp)) },
                    label = { Text(if (lang == "tm") "Profil" else "Profil", fontSize = 11.sp) },
                    colors = navColors
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(ShadowBgTop, ShadowBgDark, ShadowBgDark)))
        ) {
            when {
                tab == 0 && !showServers -> HomeTab(
                    lang = lang,
                    current = current,
                    ping = current?.let { pings[it.link] },
                    timeLeft = vpnTimeLeft,
                    everConnected = sessionWasConnected,
                    onToggle = {
                        // KAPATMA SORUNU ÇÖZÜLDÜ
                        if (VpnState.status == VpnState.CONNECTED || VpnState.status == VpnState.CONNECTING) {
                            feedback(FeedbackType.DISCONNECT)
                            stopVpnService(context)
                        } else {
                            val isLoggedIn = prefs.getBoolean("is_logged_in", false)
                            if (!isLoggedIn) {
                                android.widget.Toast.makeText(context, "Lütfen önce giriş yapın!", android.widget.Toast.LENGTH_SHORT).show()
                                tab = 2
                                feedback(FeedbackType.ERROR)
                            } else if (!isVipUser) {
                                feedback(FeedbackType.ERROR)
                                showVipScreen = true
                            } else if (current == null) {
                                android.widget.Toast.makeText(context, "Lütfen bir sunucu seçin!", android.widget.Toast.LENGTH_SHORT).show()
                                showServers = true
                            } else {
                                feedback(FeedbackType.CONNECT)
                                toggleVpn()
                            }
                        }
                    },
                    onOpenServers = { feedback(FeedbackType.TAP); showServers = true }
                )
                
                tab == 0 && showServers -> ServersTab(
                    lang = lang,
                    customServers = servers,
                    builtInSocial = builtInSocial,
                    builtInPubg = builtInPubg,
                    pings = pings,
                    selected = selectedIndex,
                    onSelect = onSelect,
                    onDelete = onDelete,
                    onUpdate = {
                        feedback(FeedbackType.TAP)
                        loadRemoteData()
                    },
                    onTest = {
                        feedback(FeedbackType.TAP)
                        (servers + builtInSocial + builtInPubg).forEach { measure(it) }
                    },
                    onBack = { feedback(FeedbackType.TAP); showServers = false },
                    onAddSubscription = { subUrl ->
                        feedback(FeedbackType.CONNECT)
                        fetchSubscriptionServers(subUrl) { fetchedList ->
                            if (fetchedList.isNotEmpty()) {
                                fetchedList.forEach { srv ->
                                    if (servers.none { it.link == srv.link }) {
                                        servers.add(srv)
                                        measure(srv)
                                    }
                                }
                                save()
                                message = if (lang == "tm") "${fetchedList.size} serwer goşuldy" else "${fetchedList.size} sunucu eklendi"
                            } else {
                                message = if (lang == "tm") "Abonelikden serwer tapylmady" else "Abonelikten sunucu okunamadı."
                            }
                        }
                    }
                )
                
                tab == 1 -> GameToolsTab(
                    lang = lang,
                    state = gameState,
                    current = current,
                    allServers = allServers,
                    scope = gameScope,
                    onUseServer = switchToServer,
                    input = input,
                    message = message,
                    soundEnabled = soundEnabled,
                    vibrationEnabled = vibrationEnabled,
                    onInput = { input = it },
                    onPaste = {
                        feedback(FeedbackType.TAP)
                        val text = clipboard.getText()?.text ?: ""
                        if (text.isNotEmpty()) input = text
                    },
                    onAdd = onAdd,
                    onLang = { newLang -> feedback(FeedbackType.TAP); lang = newLang; prefs.edit().putString("lang", newLang).apply() },
                    onSound = { enabled -> feedback(FeedbackType.TAP); soundEnabled = enabled; prefs.edit().putBoolean("sound_enabled", enabled).apply() },
                    onVibration = { enabled -> feedback(FeedbackType.TAP); vibrationEnabled = enabled; prefs.edit().putBoolean("vibration_enabled", enabled).apply() },
                    onClearMeasurements = {
                        gameState.history.clear()
                        gameState.abBefore = null
                        gameState.abAfter = null
                        gameState.ranking = emptyList()
                        gameState.backup = null
                        android.widget.Toast.makeText(context, if (lang == "tm") "Ölçeg taryhy arassalandy" else "Ölçüm geçmişi temizlendi", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    prefs = prefs,
                    feedback = feedback,
                    onOpenVip = { showVipScreen = true },
                    vipDaysLeft = vipDaysLeft
                )
                else -> ProfileTab(prefs = prefs, feedback = feedback)
            }
        }
    }
}
@Composable
private fun HomeTab(
    lang: String,
    current: Server?,
    ping: Int?,
    timeLeft: Int,
    everConnected: Boolean,
    onToggle: () -> Unit,
    onOpenServers: () -> Unit
) {
    val tm = lang == "tm"
    val connected = VpnState.status == VpnState.CONNECTED
    val busy = VpnState.status == VpnState.CONNECTING

    val hours = timeLeft / 3600
    val minutes = (timeLeft % 3600) / 60
    val seconds = timeLeft % 60
    val timeString = String.format("%02d:%02d:%02d", hours, minutes, seconds)
    
    val statusText = when {
        connected -> "$timeString • " + (if (tm) "BIRIKDI" else "BAĞLANILDI")
        busy -> if (tm) "BIRIGÝÄR" else "BAĞLANILIYOR"
        everConnected || VpnState.lastFailed -> if (tm) "BIRIKME KESILDI" else "BAĞLANTI KESİLDİ"
        else -> if (tm) "Birikdirilmedi" else "Bağlı değil"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF1A0000), ShadowBgDark),
                    center = Offset(0.5f, 0.3f),
                    radius = 1200f
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF330000), ShadowBgDark)))
                        .border(1.dp, ShadowRedDeep, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("S", color = ShadowRedNeon, fontSize = 32.sp, fontWeight = FontWeight.Black)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("SUFFER", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, lineHeight = 24.sp)
                    Text("SHADOW", color = ShadowRedNeon, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, lineHeight = 24.sp)
                    Text("F A S T   V P N", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(ShadowCardBg).border(1.dp, Color(0xFF333333), CircleShape), contentAlignment = Alignment.Center) {
                    Text("🔔", fontSize = 16.sp)
                }
                Box(Modifier.size(36.dp).clip(CircleShape).background(ShadowCardBg).border(1.dp, Color(0xFF333333), CircleShape), contentAlignment = Alignment.Center) {
                    Text("⚙️", fontSize = 16.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(ShadowCardBg)
                .border(1.dp, Color(0xFF2A0000), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (connected) ShadowRedNeon else Color.DarkGray))
                Spacer(modifier = Modifier.width(12.dp))
                Text(statusText, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("›", color = ShadowRedNeon, fontSize = 24.sp, fontWeight = FontWeight.Light)
            }
        }

        Spacer(modifier = Modifier.height(30.dp))

        val infinite = rememberInfiniteTransition()
        val pulse by infinite.animateFloat(0.4f, 1f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse))
        val sweep by infinite.animateFloat(0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing)))
        val btnScale by animateFloatAsState(if (busy) 0.96f else 1f, tween(500))

        Box(
            modifier = Modifier
                .size(240.dp)
                .scale(btnScale)
                .clip(CircleShape)
                .background(Brush.radialGradient(colors = listOf(Color(0xFF1A0000), ShadowBgDark)))
                .clickable { onToggle() }
                .drawBehind {
                    val stroke = 2.dp.toPx()
                    drawCircle(color = ShadowRedNeon.copy(alpha = pulse * 0.3f), style = Stroke(stroke * 3), radius = size.minDimension / 2)
                    drawArc(
                        color = ShadowRedNeon,
                        startAngle = sweep,
                        sweepAngle = 80f,
                        useCenter = false,
                        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                        topLeft = Offset(4.dp.toPx(), 4.dp.toPx()),
                        size = Size(size.width - 8.dp.toPx(), size.height - 8.dp.toPx())
                    )
                }
                .border(1.dp, ShadowRedNeon.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (busy) {
                    CircularProgressIndicator(color = ShadowRedNeon, modifier = Modifier.size(56.dp), strokeWidth = 4.dp)
                } else {
                    Canvas(modifier = Modifier.size(56.dp)) {
                        val strokeW = 4.dp.toPx()
                        drawArc(color = ShadowRedNeon, startAngle = 135f, sweepAngle = 270f, useCenter = false, topLeft = Offset(strokeW, strokeW), size = Size(size.width - strokeW*2, size.height - strokeW*2), style = Stroke(width = strokeW, cap = StrokeCap.Round))
                        drawLine(color = ShadowRedNeon, start = Offset(size.width/2, 0f), end = Offset(size.width/2, size.height*0.5f), strokeWidth = strokeW, cap = StrokeCap.Round)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = when {
                        connected -> if (tm) "ÖÇÜR" else "KAPAT"
                        busy -> if (tm) "BIRIGÝÄR" else "BAĞLANILIYOR"
                        else -> if (tm) "GOŞUL" else "BAĞLAN"
                    },
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(30.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(ShadowCardBg)
                .border(width = 1.dp, brush = Brush.linearGradient(listOf(ShadowRedNeon, Color(0xFF441115), ShadowRedNeon)), shape = RoundedCornerShape(16.dp))
                .clickable { onOpenServers() }
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.width(56.dp).height(40.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF222222)), contentAlignment = Alignment.Center) {
                    Text("🌐", fontSize = 20.sp) 
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(current?.name ?: "Sunucu Seçilmedi", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚡", fontSize = 12.sp, color = ShadowRedNeon)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(current?.let { protocolName(it) } ?: "Bilinmiyor", color = ShadowRedNeon, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Text("›", color = ShadowRedNeon, fontSize = 32.sp, fontWeight = FontWeight.Light)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(ShadowCardBg).border(1.dp, Color(0xFF2A0000), RoundedCornerShape(16.dp)).padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF2A0000)), contentAlignment = Alignment.Center) {
                        Text("🔗", fontSize = 16.sp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(if (ping != null && ping >= 0) "$ping ms" else "—", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("TCP port yanıtı", color = TextMuted, fontSize = 11.sp)
                    }
                }
            }
            Box(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(ShadowCardBg).border(1.dp, Color(0xFF2A0000), RoundedCornerShape(16.dp)).padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF2A0000)), contentAlignment = Alignment.Center) {
                        Text("⏱️", fontSize = 16.sp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(if (connected) "Açık" else "Kapalı", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("Bağlantı durumu", color = TextMuted, fontSize = 11.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFF9C1027), ShadowRedNeon, Color(0xFFFF5360))))
                .border(1.dp, Color(0x88FF7A86), RoundedCornerShape(12.dp))
                .clickable { onOpenServers() },
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("▤", color = Color.White, fontSize = 20.sp)
                Spacer(Modifier.width(10.dp))
                Text("Sunucular", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.width(10.dp))
                Text("›", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Light)
            }
        }
        
        Spacer(modifier = Modifier.height(30.dp))
    }
}
