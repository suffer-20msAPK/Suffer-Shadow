@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.suffershadow.vpn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// ==========================================
// CYBERPUNK RENK PALETİ (B PLANI)
// ==========================================
internal val BgDark = Color(0xFF07080D)
internal val BgTop = Color(0xFF121422)
internal val CardBg = Color(0xFF121422)
internal val CardBg2 = Color(0xFF1C1E32)
internal val PurpleMain = Color(0xFF9D00FF)
internal val PurpleLight = Color(0xFFD9A0FF)
internal val PurpleDeep = Color(0xFF5A00B5)
internal val AccentGreen = Color(0xFF00FFCC)
internal val RedSoft = Color(0xFFFF3366)
internal val TextMuted = Color(0xFF8E92A6)
internal val BorderPurple = Color(0xFF6C00B8)

private const val SOCIAL_MEDIA_URL = "https://raw.githubusercontent.com/suffer-20msAPK/Suffer-Shadow/main/servers/social.txt"
private const val PUBG_URL = "https://raw.githubusercontent.com/suffer-20msAPK/Suffer-Shadow/main/servers/pubg.txt"

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
        handleNav(intent)
        setContent {
            SufferShadowTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = BgDark) {
                    AppRoot()
                }
            }
        }
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

fun pingServer(s: Server): Int {
    return try {
        val start = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(s.host, s.port), 3000) }
        ((System.nanoTime() - start) / 1_000_000).toInt()
    } catch (_: Exception) {
        -1
    }
}

fun fetchRemoteServers(url: String, category: String, onResult: (List<Server>) -> Unit) {
    thread {
        val list = mutableListOf<Server>()
        try {
            val connection = java.net.URL(url).openConnection()
            connection.connectTimeout = 5000
            val text = connection.getInputStream().bufferedReader().readText()
            text.lines().forEach { line ->
                parseServer(line, isBuiltIn = true, category = category)?.let { list.add(it) }
            }
        } catch (_: Exception) { }
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
        p == null -> "Ölçülmedi" to TextMuted
        p == -2 -> "Ölçülüyor..." to TextMuted
        p == -1 -> (if (tm) "Port elýeterli däl" else "Port erişilemiyor") to RedSoft
        else -> (if (tm) "TCP port jogaby • $p ms" else "TCP port yanıtı • $p ms") to when {
            p < 150 -> AccentGreen
            p < 400 -> Color(0xFFFFD166)
            else -> Color(0xFFFF9D55)
        }
    }
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

    LaunchedEffect(Unit) {
        val (isVip, _) = RedeemManager.checkVip(context)
        isVipUser = isVip
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
        fetchRemoteServers(SOCIAL_MEDIA_URL, "social") { fetched ->
            builtInSocial.clear()
            builtInSocial.addAll(fetched)
        }
        fetchRemoteServers(PUBG_URL, "pubg") { fetched ->
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
            val server = parseServer(line, isBuiltIn = false)
            if (server != null && servers.none { it.link == server.link }) {
                servers.add(server)
                measure(server)
                added++
            }
        }
        if (added > 0) {
            input = ""
            save()
            message = if (lang == "tm") "$added serwer goşuldy" else "$added sunucu eklendi"
        } else {
            message = if (lang == "tm") "Goşup bolmady. Diňe goldanýan VLESS/SS linklerini barla." else "Eklenemedi. Desteklenen VLESS/SS bağlantılarını kontrol et."
        }
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
                containerColor = Color(0xFF10121A),
                contentColor = TextMuted,
                tonalElevation = 0.dp
            ) {
                val navColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = PurpleLight,
                    selectedTextColor = Color.White,
                    indicatorColor = Color(0xFF2A1B40),
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted
                )
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { feedback(FeedbackType.TAP); tab = 0; showServers = false },
                    icon = { NavGlyph("home", if (tab == 0) PurpleLight else TextMuted, Modifier.size(24.dp)) },
                    label = { Text(if (lang == "tm") "Baş sahypa" else "Ana Menü", fontSize = 11.sp) },
                    colors = navColors
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { feedback(FeedbackType.TAP); tab = 1; showServers = false },
                    icon = { NavGlyph("game", if (tab == 1) PurpleLight else TextMuted, Modifier.size(24.dp)) },
                    label = { Text(if (lang == "tm") "Oýun" else "Oyun", fontSize = 11.sp) },
                    colors = navColors
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { feedback(FeedbackType.TAP); tab = 2; showServers = false },
                    icon = { NavGlyph("profile", if (tab == 2) PurpleLight else TextMuted, Modifier.size(24.dp)) },
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
                .background(Brush.verticalGradient(listOf(BgTop, BgDark, BgDark)))
        ) {
            when {
                tab == 0 && !showServers -> HomeTab(
                    lang = lang,
                    current = current,
                    ping = current?.let { pings[it.link] },
                    timeLeft = vpnTimeLeft,
                    everConnected = sessionWasConnected,
                    onToggle = {
                        if (VpnState.status == VpnState.CONNECTED || VpnState.status == VpnState.CONNECTING) {
                            feedback(FeedbackType.DISCONNECT)
                            toggleVpn()
                        } else {
                            if (isVipUser) {
                                feedback(FeedbackType.CONNECT)
                                toggleVpn()
                            } else {
                                feedback(FeedbackType.ERROR)
                                showVipScreen = true
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
                    onRefresh = { feedback(FeedbackType.TAP); loadRemoteData(); servers.forEach { measure(it) } },
                    onBack = { feedback(FeedbackType.TAP); showServers = false }
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
                    onOpenVip = { showVipScreen = true }
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
    val pulseTransition = rememberInfiniteTransition()
    val pulse by pulseTransition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.035f,
        animationSpec = infiniteRepeatable(tween(1150, easing = FastOutSlowInEasing), RepeatMode.Reverse)
    )
    val buttonScale = if (busy) pulse else 1f
    val accent = if (connected) AccentGreen else PurpleMain

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
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BrandHeader()
        Spacer(Modifier.height(14.dp))
        StatusPill(
            text = statusText,
            color = when {
                connected && VpnState.delayMs >= 0 -> AccentGreen
                connected -> Color(0xFFFFD166)
                busy -> PurpleLight
                else -> TextMuted
            }
        )
        Spacer(Modifier.height(26.dp))

        Box(
            modifier = Modifier.size(244.dp).graphicsLayer { scaleX = buttonScale; scaleY = buttonScale },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier.fillMaxSize().clip(CircleShape).background(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.34f), PurpleMain.copy(alpha = 0.10f), Color.Transparent))
                )
            )
            Box(
                modifier = Modifier.size(202.dp).border(1.dp, accent.copy(alpha = 0.46f), CircleShape).padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                AnimatedSurface(
                    modifier = Modifier.fillMaxSize(),
                    shape = CircleShape,
                    brush = Brush.radialGradient(listOf(Color(0xFF252044), Color(0xFF100F24), Color(0xFF080B18))),
                    onClick = onToggle,
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(), 
                        horizontalAlignment = Alignment.CenterHorizontally, 
                        verticalArrangement = Arrangement.Center
                    ) {
                        PowerGlyph(accent, Modifier.size(43.dp))
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = when {
                                connected -> if (tm) "ÖÇÜR" else "KAPAT"
                                busy -> if (tm) "BIRIGÝÄR" else "BAĞLANILIYOR"
                                else -> if (tm) "GOŞUL" else "BAĞLAN"
                            },
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = if (busy) 15.sp else 16.sp, 
                            letterSpacing = 1.2.sp,
                            textAlign = TextAlign.Center, 
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        AnimatedSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = CardBg,
            onClick = onOpenServers
        ) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A1B40)),
                    contentAlignment = Alignment.Center
                ) { Text("S", color = PurpleLight, fontSize = 23.sp, fontWeight = FontWeight.Black) }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(current?.name ?: if (tm) "Serwer saýlanmady" else "Sunucu seçilmedi", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(current?.let { protocolName(it) } ?: if (tm) "Serwer saýlaň" else "Bir sunucu seçin", color = TextMuted, fontSize = 12.sp)
                }
                Text("›", color = PurpleLight, fontSize = 34.sp, fontWeight = FontWeight.Light)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            InfoPill(
                modifier = Modifier.weight(1f),
                title = if (connected && VpnState.delayMs >= 0) "${VpnState.delayMs} ms" else if (ping != null && ping >= 0) "$ping ms" else "—",
                subtitle = if (connected && VpnState.delayMs >= 0) (if (tm) "Tunel gijikmesi" else "Tünel gecikmesi") else (if (tm) "TCP port jogaby" else "TCP port yanıtı")
            )
            InfoPill(
                modifier = Modifier.weight(1f),
                title = when {
                    connected && VpnState.delayMs >= 0 -> if (tm) "Birikdi" else "Bağlandı"
                    connected -> if (tm) "Barlaň" else "Kontrol et"
                    busy -> if (tm) " garaşýar" else "Bekliyor"
                    else -> if (tm) "Öçük" else "Kapalı"
                },
                subtitle = if (tm) "Baglanyşyk ýagdaýy" else "Bağlantı durumu"
            )
        }
        Spacer(Modifier.height(16.dp))
        NeonButton(
            text = if (tm) "Serwerler" else "Sunucular",
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpenServers,
            leading = "▤"
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = if (tm) "Güýçli baglanyşyk. Ýönekeý dolandyryş." else "Güçlü bağlantı. Kolay kontrol.",
            color = TextMuted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}
@Composable
private fun ServersTab(
    lang: String,
    customServers: List<Server>,
    builtInSocial: List<Server>,
    builtInPubg: List<Server>,
    pings: Map<String, Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    val tm = lang == "tm"
    val connected = VpnState.status == VpnState.CONNECTED
    
    var mainTab by remember { mutableStateOf(0) }
    var categoryTab by remember { mutableStateOf(0) }

    val currentList = when {
        mainTab == 1 -> customServers
        categoryTab == 0 -> builtInSocial
        else -> builtInPubg
    }

    val allServers = remember(customServers, builtInSocial, builtInPubg) { customServers + builtInSocial + builtInPubg }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), 
            verticalAlignment = Alignment.CenterVertically
        ) {
            NeonButton(text = "‹", modifier = Modifier.size(44.dp), onClick = onBack, secondary = true)
            Spacer(Modifier.weight(1f))
            Text(if (tm) "Serwerler" else "Sunucular", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            NeonButton(text = if (tm) "Test et" else "Test Et", onClick = onRefresh, secondary = true)
        }

        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NeonButton(
                text = if (tm) "Içindekiler" else "İçindekiler", 
                modifier = Modifier.weight(1f), 
                onClick = { mainTab = 0 }, 
                secondary = mainTab != 0
            )
            NeonButton(
                text = if (tm) "Özel" else "Özel", 
                modifier = Modifier.weight(1f), 
                onClick = { mainTab = 1 }, 
                secondary = mainTab != 1
            )
        }

        if (mainTab == 0) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NeonButton(
                    text = if (tm) "Sosial Media" else "Sosyal Medya", 
                    modifier = Modifier.weight(1f), 
                    onClick = { categoryTab = 0 }, 
                    secondary = categoryTab != 0
                )
                NeonButton(
                    text = "PUBG", 
                    modifier = Modifier.weight(1f), 
                    onClick = { categoryTab = 1 }, 
                    secondary = categoryTab != 1
                )
            }
        }

        if (currentList.isEmpty()) {
            AnimatedSurface(Modifier.fillMaxWidth(), color = CardBg, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("○", color = PurpleLight, fontSize = 34.sp)
                    Text(if (tm) "Serwer ýok" else "Henüz sunucu yok", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        if (mainTab == 0) (if(tm) "Uzakdan kodlar ýüklenýär..." else "Uzaktan sunucular yükleniyor veya henüz eklenmedi.")
                        else (if (tm) "Oýun bölüminden link goşuň." else "Oyun sekmesinden bağlantı ekleyin."), 
                        color = TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(9.dp), 
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                itemsIndexed(currentList) { index, server ->
                    val globalIndex = allServers.indexOfFirst { it.link == server.link }
                    val isSelected = globalIndex == selected
                    val status = serverStatus(pings[server.link], isSelected && connected, lang)
                    
                    AnimatedSurface(
                        modifier = Modifier.fillMaxWidth(),
                        color = if (isSelected) Color(0xFF2A1B40) else CardBg,
                        shape = RoundedCornerShape(12.dp),
                        onClick = { onSelect(globalIndex) }
                    ) {
                        Row(Modifier.padding(start = 15.dp, top = 13.dp, bottom = 13.dp, end = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(server.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(protocolName(server), color = PurpleLight, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    Text("  •  ", color = TextMuted, fontSize = 11.sp)
                                    Text(status.first, color = status.second, fontSize = 12.sp)
                                }
                            }
                            if (isSelected) {
                                Text("✓", color = AccentGreen, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(7.dp))
                            }
                            if (!server.isBuiltIn) {
                                TextButton(onClick = { onDelete(customServers.indexOf(server)) }) {
                                    Text(if (tm) "Poz" else "Sil", color = RedSoft, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun ProfileTab(prefs: android.content.SharedPreferences, feedback: (FeedbackType) -> Unit) {
    var isLoggedIn by remember { mutableStateOf(false) }
    var phoneId by remember { mutableStateOf("") }
    var showAuthDialog by remember { mutableStateOf(false) }
    var isRegisterMode by remember { mutableStateOf(false) }

    if (!isLoggedIn) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("S", color = PurpleLight, fontSize = 80.sp, fontWeight = FontWeight.Black)
            Text("SUFFER SHADOW", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.height(40.dp))
            
            Text(
                text = "Devam etmek için giriş yapmalısınız.",
                color = TextMuted, fontSize = 14.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            
            NeonButton(
                text = "Giriş Yap",
                modifier = Modifier.fillMaxWidth(),
                onClick = { feedback(FeedbackType.TAP); isRegisterMode = false; showAuthDialog = true }
            )
            Spacer(Modifier.height(12.dp))
            NeonButton(
                text = "Yeni Hesap Oluştur",
                modifier = Modifier.fillMaxWidth(),
                secondary = true,
                onClick = { feedback(FeedbackType.TAP); isRegisterMode = true; showAuthDialog = true }
            )
        }
    } else {
        val uriHandler = LocalUriHandler.current

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("PROFİLİM", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Spacer(Modifier.height(4.dp))
            Text("ID: $phoneId", color = PurpleLight, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            
            Spacer(Modifier.height(24.dp))
            
            AnimatedSurface(Modifier.fillMaxWidth(), color = CardBg, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("SOSYAL MEDYA", color = PurpleLight, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                    
                    SocialMediaRow(
                        icon = "🎵", title = "TikTok Kanalım", 
                        buttonText = "Abone Ol", buttonColor = Color(0xFF00F2FE),
                        onClick = { feedback(FeedbackType.TAP); runCatching { uriHandler.openUri("https://tiktok.com/@suffer_pubg") } }
                    )
                    SocialMediaRow(
                        icon = "✈️", title = "Suffere Yaz", 
                        buttonText = "Mesaj", buttonColor = Color(0xFF229ED9),
                        onClick = { feedback(FeedbackType.TAP); runCatching { uriHandler.openUri("https://t.me/suffer_pubg") } }
                    )
                    SocialMediaRow(
                        icon = "📢", title = "Telegram Kanalımız", 
                        buttonText = "Abone Ol", buttonColor = Color(0xFF229ED9),
                        onClick = { feedback(FeedbackType.TAP); runCatching { uriHandler.openUri("https://t.me/suffervpn") } }
                    )
                    SocialMediaRow(
                        icon = "💬", title = "Telegram Sohbetimiz", 
                        buttonText = "Katıl", buttonColor = Color(0xFF229ED9),
                        onClick = { feedback(FeedbackType.TAP); runCatching { uriHandler.openUri("https://t.me/suffer_chat") } }
                    )
                }
            }
            
            Spacer(Modifier.height(30.dp))
            
            NeonButton(
                text = "Çıkış Yap",
                modifier = Modifier.fillMaxWidth(),
                secondary = true,
                onClick = { feedback(FeedbackType.TAP); isLoggedIn = false; phoneId = "" }
            )
        }
    }

    if (showAuthDialog) {
        AuthDialog(
            isRegister = isRegisterMode,
            onDismiss = { showAuthDialog = false },
            onSuccess = { id ->
                phoneId = id
                isLoggedIn = true
                showAuthDialog = false
            }
        )
    }
}

@Composable
private fun SocialMediaRow(icon: String, title: String, buttonText: String, buttonColor: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardBg2).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 24.sp)
        Spacer(Modifier.width(12.dp))
        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        AnimatedSurface(shape = RoundedCornerShape(8.dp), color = buttonColor.copy(alpha = 0.2f), onClick = onClick) {
            Text(text = buttonText, color = buttonColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun AuthDialog(
    isRegister: Boolean,
    onDismiss: () -> Unit,
    onSuccess: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var passConfirm by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        containerColor = CardBg,
        titleContentColor = Color.White,
        textContentColor = TextMuted,
        title = { Text(if (isRegister) "Yeni Hesap Oluştur" else "Giriş Yap", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("🇹🇲 +993", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { if (it.length <= 8) phone = it.filter { char -> char.isDigit() } },
                        placeholder = { Text("61234567") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading
                    )
                }
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("Şifre") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )
                if (isRegister) {
                    OutlinedTextField(
                        value = passConfirm,
                        onValueChange = { passConfirm = it },
                        label = { Text("Şifreyi Onayla") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading
                    )
                }
                if (errorMsg.isNotEmpty()) {
                    Text(errorMsg, color = RedSoft, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    errorMsg = ""
                    // 1. Numara uzunluk kontrolü
                    if (phone.length != 8) {
                        errorMsg = "Numara +993 olmadan 8 haneli olmalıdır."
                        return@TextButton
                    }
                    // 2. Türkmenistan operatör kodları kontrolü
                    val prefix = phone.take(2)
                    if (prefix !in listOf("61", "62", "63", "64", "65", "71")) {
                        errorMsg = "Geçersiz numara! (61, 62, 63, 64, 65, 71 ile başlamalı)"
                        return@TextButton
                    }
                    // 3. Şifre uzunluk kontrolü
                    if (pass.length < 4) {
                        errorMsg = "Şifre en az 4 karakter olmalıdır."
                        return@TextButton
                    }
                    // 4. Kayıt olurken şifre eşleşme kontrolü
                    if (isRegister && pass != passConfirm) {
                        errorMsg = "Şifreler uyuşmuyor!"
                        return@TextButton
                    }

                    isLoading = true
                    val deviceId = RedeemManager.getDeviceId(context)

                    scope.launch {
                        try {
                            if (isRegister) {
                                // KAYIT OLMA İŞLEMİ
                                val exists = UserManager.checkUserExists(phone)
                                if (exists) {
                                    errorMsg = "Bu numara zaten kayıtlı. Lütfen Giriş Yapın."
                                    isLoading = false
                                } else {
                                    val (success, msg) = UserManager.registerUser(phone, pass, deviceId)
                                    if (success) {
                                        onSuccess(phone) // Başarılıysa profil ekranına geç
                                    } else {
                                        errorMsg = msg
                                        isLoading = false
                                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            } else {
                                // GİRİŞ YAPMA İŞLEMİ
                                val (success, msg) = UserManager.loginUser(phone, pass, deviceId)
                                if (success) {
                                    onSuccess(phone) // Başarılıysa profil ekranına geç
                                } else {
                                    errorMsg = msg
                                    isLoading = false
                                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        } catch (e: Exception) {
                            // Herhangi bir hata olursa butonu eski haline getir ve hatayı göster
                            errorMsg = "Bağlantı Hatası: ${e.message}"
                            isLoading = false
                            android.widget.Toast.makeText(context, errorMsg, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                },
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = PurpleLight, strokeWidth = 2.dp)
                } else {
                    Text(if (isRegister) "Kayıt Ol" else "Giriş Yap", color = PurpleLight, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) { Text("Vazgeç", color = TextMuted) }
        }
    )
}
@Composable
internal fun SectionHeading(number: String, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 9.dp)) {
        Text(number, color = PurpleLight, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        Spacer(Modifier.width(9.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
internal fun PreferenceSwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(subtitle, color = TextMuted, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = PurpleMain,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = Color(0xFF30364D)
            )
        )
    }
}

@Composable
private fun BrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Box(
            modifier = Modifier.size(62.dp).clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(PurpleLight, PurpleMain, Color(0xFF24104E))))
                .border(1.dp, PurpleLight.copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("S", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(13.dp))
        Column {
            Text("SUFFER", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp, lineHeight = 27.sp)
            Text("SHADOW", color = PurpleLight, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp, lineHeight = 27.sp)
            Text("FAST VPN", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
        }
    }
}

@Composable
internal fun StatusPill(text: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBg.copy(alpha = 0.82f)).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(9.dp))
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun InfoPill(modifier: Modifier = Modifier, title: String, subtitle: String) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(CardBg2).padding(horizontal = 13.dp, vertical = 12.dp)) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Text(subtitle, color = TextMuted, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
internal fun NeonButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    secondary: Boolean = false,
    leading: String? = null
) {
    AnimatedSurface(
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(8.dp),
        brush = if (secondary) null else Brush.horizontalGradient(listOf(PurpleDeep, PurpleMain, Color(0xFFB833FF))),
        color = if (secondary) Color(0xFF1C1E32) else PurpleMain,
        onClick = onClick,
        contentAlignment = Alignment.Center
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 11.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                Text(leading, color = if (secondary) PurpleLight else Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(9.dp))
            }
            Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, letterSpacing = 1.sp)
        }
    }
}

@Composable
internal fun AnimatedSurface(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp),
    color: Color = CardBg,
    brush: Brush? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 520f)
    )
    val clickModifier = if (onClick != null) {
        Modifier.clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
    } else Modifier
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .then(if (brush != null) Modifier.background(brush, shape) else Modifier.background(color, shape))
            .then(clickModifier),
        contentAlignment = contentAlignment,
        content = content
    )
}

@Composable
private fun NavGlyph(kind: String, color: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 2.1.dp.toPx()
        val style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            "home" -> {
                val path = Path().apply {
                    moveTo(w * 0.12f, h * 0.47f)
                    lineTo(w * 0.5f, h * 0.12f)
                    lineTo(w * 0.88f, h * 0.47f)
                    moveTo(w * 0.22f, h * 0.42f)
                    lineTo(w * 0.22f, h * 0.88f)
                    lineTo(w * 0.78f, h * 0.88f)
                    lineTo(w * 0.78f, h * 0.42f)
                    moveTo(w * 0.43f, h * 0.88f)
                    lineTo(w * 0.43f, h * 0.62f)
                    lineTo(w * 0.57f, h * 0.62f)
                    lineTo(w * 0.57f, h * 0.88f)
                }
                drawPath(path, color, style = style)
            }
            "settings" -> {
                val cx = w / 2f
                val cy = h / 2f
                val outer = w * 0.34f
                for (i in 0 until 8) {
                    val angle = i * PI / 4.0
                    val x1 = cx + cos(angle).toFloat() * outer
                    val y1 = cy + sin(angle).toFloat() * outer
                    val x2 = cx + cos(angle).toFloat() * (outer + w * 0.13f)
                    val y2 = cy + sin(angle).toFloat() * (outer + w * 0.13f)
                    drawLine(color, androidx.compose.ui.geometry.Offset(x1, y1), androidx.compose.ui.geometry.Offset(x2, y2), stroke, cap = StrokeCap.Round)
                }
                drawCircle(color, radius = outer, center = androidx.compose.ui.geometry.Offset(cx, cy), style = style)
                drawCircle(color, radius = w * 0.11f, center = androidx.compose.ui.geometry.Offset(cx, cy), style = style)
            }
            "game" -> {
                drawRoundRect(
                    color,
                    topLeft = Offset(w * 0.08f, h * 0.30f),
                    size = androidx.compose.ui.geometry.Size(w * 0.84f, h * 0.44f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.2f, w * 0.2f),
                    style = style
                )
                drawLine(color, Offset(w * 0.24f, h * 0.52f), Offset(w * 0.42f, h * 0.52f), stroke, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.33f, h * 0.43f), Offset(w * 0.33f, h * 0.61f), stroke, cap = StrokeCap.Round)
                drawCircle(color, radius = w * 0.045f, center = Offset(w * 0.66f, h * 0.47f))
                drawCircle(color, radius = w * 0.045f, center = Offset(w * 0.76f, h * 0.57f))
            }
            else -> {
                drawCircle(color, radius = w * 0.17f, center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.29f), style = style)
                drawArc(color, 180f, 180f, false, androidx.compose.ui.geometry.Offset(w * 0.19f, h * 0.48f), androidx.compose.ui.geometry.Size(w * 0.62f, h * 0.48f), style = style)
            }
        }
    }
}

@Composable
private fun PowerGlyph(color: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val stroke = 3.2.dp.toPx()
        drawArc(
            color = color,
            startAngle = 135f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.16f, size.height * 0.16f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.68f, size.height * 0.68f),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * 0.08f),
            end = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * 0.52f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun SufferShadowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PurpleMain,
            secondary = PurpleLight,
            background = BgDark,
            surface = CardBg,
            onPrimary = Color.White,
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}
object GamePrefs {
    const val BLOCK_BACKGROUND = "gm_block_bg"
    const val PRIORITIZE_GAME = "gm_priority"
    const val SUGGEST_BACKUP = "gm_backup"
    const val AUTO_FAILOVER = "gm_auto_failover"
    const val AB_TARGET = "gm_ab_target"
}

private data class GamePingSample(val timeMs: Long, val ms: Int)
private data class GameProbe(val avg: Int, val min: Int, val jitter: Int, val lossPct: Int)
private data class GameRanked(val server: Server, val result: GameProbe, val load: Int?)

private class GameToolsState(private val prefs: android.content.SharedPreferences) {
    val history = mutableStateListOf<GamePingSample>()
    var blockBackground by mutableStateOf(prefs.getBoolean(GamePrefs.BLOCK_BACKGROUND, false))
    var prioritizeGame by mutableStateOf(prefs.getBoolean(GamePrefs.PRIORITIZE_GAME, false))
    var suggestBackup by mutableStateOf(prefs.getBoolean(GamePrefs.SUGGEST_BACKUP, true))
    var autoFailover by mutableStateOf(prefs.getBoolean(GamePrefs.AUTO_FAILOVER, false))
    var abTarget by mutableStateOf(prefs.getString(GamePrefs.AB_TARGET, "") ?: "")
    var abBefore by mutableStateOf<GameProbe?>(null)
    var abAfter by mutableStateOf<GameProbe?>(null)
    var abRunning by mutableStateOf(false)
    var abError by mutableStateOf("")
    var rankRunning by mutableStateOf(false)
    var ranking by mutableStateOf<List<GameRanked>>(emptyList())
    var backup by mutableStateOf<GameRanked?>(null)
    var backupDismissedFor by mutableStateOf<String?>(null)

    fun setFlag(key: String, value: Boolean) {
        when (key) {
            GamePrefs.BLOCK_BACKGROUND -> blockBackground = value
            GamePrefs.PRIORITIZE_GAME -> prioritizeGame = value
            GamePrefs.SUGGEST_BACKUP -> suggestBackup = value
            GamePrefs.AUTO_FAILOVER -> autoFailover = value
        }
        prefs.edit().putBoolean(key, value).apply()
    }
    fun updateTarget(value: String) {
        abTarget = value
        prefs.edit().putString(GamePrefs.AB_TARGET, value).apply()
    }
    fun runAb(scope: CoroutineScope, vpnOn: Boolean, invalidMsg: String, linkMsg: String) {}
    fun runRanking(scope: CoroutineScope, servers: List<Server>) {}
}

private fun parseGameTarget(raw: String): Pair<String, Int>? = null
private fun probeServer(s: Server, count: Int = 5): GameProbe = GameProbe(0,0,0,0)
private fun probeSamples(count: Int, sample: () -> Int): GameProbe = GameProbe(0,0,0,0)
private fun serverLoad(s: Server): Int? = null
private fun httpFirstByteMs(host: String, port: Int, viaTunnel: Boolean): Int = -1
private fun isStableServer(r: GameProbe): Boolean = false
private fun gameScore(r: GameProbe, load: Int?): Double = 0.0
private suspend fun rankServers(list: List<Server>): List<GameRanked> = emptyList()

@Composable
private fun GameBackupDialog(
    lang: String,
    ranked: GameRanked,
    failedName: String,
    auto: Boolean,
    onSwitch: () -> Unit,
    onDismiss: () -> Unit
) {}

@Composable
internal fun GameCard(content: @Composable ColumnScope.() -> Unit) {
    AnimatedSurface(Modifier.fillMaxWidth(), color = CardBg, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun GameToolsTab(
    lang: String,
    state: GameToolsState,
    current: Server?,
    allServers: List<Server>,
    scope: CoroutineScope,
    onUseServer: (Server) -> Unit,
    input: String,
    message: String,
    soundEnabled: Boolean,
    vibrationEnabled: Boolean,
    onInput: (String) -> Unit,
    onPaste: () -> Unit,
    onAdd: () -> Unit,
    onLang: (String) -> Unit,
    onSound: (Boolean) -> Unit,
    onVibration: (Boolean) -> Unit,
    onClearMeasurements: () -> Unit,
    prefs: android.content.SharedPreferences,
    feedback: (FeedbackType) -> Unit,
    onOpenVip: () -> Unit
) {
    val tm = lang == "tm"

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
    ) {
        Text(
            text = if (tm) "OÝUN MERKEZI" else "OYUN MERKEZİ",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp
        )
        Text(
            text = if (tm) "Premium aýratynlyklar we kod ulanmak" else "Premium özellikler ve kod kullanımı",
            color = PurpleLight,
            fontSize = 13.sp,
            letterSpacing = 1.sp
        )
        
        Spacer(Modifier.height(24.dp))

        AnimatedSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = CardBg,
            onClick = { feedback(FeedbackType.TAP); onOpenVip() }
        ) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🎟️", fontSize = 32.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (tm) "Premium açmak üçin Redeem koduny giriziň" else "Premium açmak için Redeem kodunu girin",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                NeonButton(
                    text = if (tm) "KODY ULAN" else "KODU KULLAN",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { feedback(FeedbackType.TAP); onOpenVip() }
                )
            }
        }

        Spacer(Modifier.height(30.dp))
        
        AnimatedSurface(
            modifier = Modifier.fillMaxWidth(),
            color = CardBg,
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("⚡", fontSize = 40.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (tm) "Täze oýun gurallary ýakyn wagtda goşular" else "Yeni oyun araçları çok yakında eklenecek",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (tm) "Ping testleri, Game Booster we beýlekiler täzeden guralýar." else "Ping testleri, Game Booster ve diğerleri sıfırdan, daha profesyonel bir tasarımla inşa ediliyor.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp
                )
            }
        }
    }
}
