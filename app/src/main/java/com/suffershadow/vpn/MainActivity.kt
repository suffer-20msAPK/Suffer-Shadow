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

private val BgDark = Color(0xFF080B18)
private val BgTop = Color(0xFF10152B)
private val CardBg = Color(0xFF151B33)
private val CardBg2 = Color(0xFF1B2140)
private val PurpleMain = Color(0xFF7B20FF)
private val PurpleLight = Color(0xFFB46BFF)
private val PurpleDeep = Color(0xFF4210A8)
private val AccentGreen = Color(0xFF00EFA8)
private val RedSoft = Color(0xFFFF7185)
private val TextMuted = Color(0xFFA7AEC8)
private val BorderPurple = Color(0xFF50308C)

// UZAKTAN SUNUCU LİNKLERİ (BURAYA KENDİ LİNKLERİNİZİ YAPIŞTIRIN)
private const val SOCIAL_MEDIA_URL = "https://example.com/social.txt" // Sosyal Medya sunucuları için link
private const val PUBG_URL = "https://example.com/pubg.txt" // PUBG sunucuları için link

private enum class FeedbackType { TAP, CONNECT, DISCONNECT, ERROR }

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

data class Server(
    val link: String, 
    val name: String, 
    val host: String, 
    val port: Int,
    val isBuiltIn: Boolean = false, // İçindekiler mi Özel mi?
    val category: String = "custom" // "social", "pubg", "custom"
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

// Uzaktan Sunucu Çekme Fonksiyonu
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
        } catch (_: Exception) { /* Hata durumunda boş liste döner */ }
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
    // Kırmızı yazı (internet yok) iptal edildi, direkt bağlandı gösteriyor.
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

    var lang by remember { mutableStateOf(prefs.getString("lang", "tr") ?: "tr") }
    var soundEnabled by remember { mutableStateOf(prefs.getBoolean("sound_enabled", true)) }
    var vibrationEnabled by remember { mutableStateOf(prefs.getBoolean("vibration_enabled", true)) }
    val feedback = rememberUiFeedback(soundEnabled, vibrationEnabled)

    val servers = remember {
        val list = mutableStateListOf<Server>()
        var saved = prefs.getString("links", "") ?: ""
        if (saved.isEmpty()) saved = prefs.getString("link", "") ?: ""
        saved.split("\n").forEach { line -> parseServer(line, isBuiltIn = false)?.let { if (list.none { old -> old.link == it.link }) list.add(it) } }
        list
    }

    // Uzaktan gelen sunucuları tutacağımız listeler
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

    // Uygulama açıldığında uzaktan sunucuları çek
    LaunchedEffect(Unit) { loadRemoteData() }

    val pings = remember { mutableStateMapOf<String, Int>() }
    var selected by remember { mutableStateOf(prefs.getInt("sel", 0)) }
    var tab by remember { mutableStateOf(0) }
    var showServers by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    // VPN Geri Sayım (2 Saat = 7200 saniye)
    var vpnTimeLeft by remember { mutableStateOf(7200) }
    
    LaunchedEffect(VpnState.status) {
        if (VpnState.status == VpnState.CONNECTED) {
            vpnTimeLeft = 7200 // Bağlanınca sıfırla
            while (vpnTimeLeft > 0 && VpnState.status == VpnState.CONNECTED) {
                delay(1000L)
                vpnTimeLeft--
            }
            if (vpnTimeLeft <= 0) {
                stopVpnService(context) // Süre bitince kapat
            }
        } else {
            vpnTimeLeft = 7200 // Bağlantı kesilirse sıfırla
        }
    }

    val connect = rememberVpnConnect()
    // Tüm sunucuları tek bir listede birleştirerek seçim işlemlerini kolaylaştırıyoruz
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

    // ---- Oyun araçları durumu (15-20) ----
    val gameState = remember { GameToolsState(prefs) }
    val gameScope = rememberCoroutineScope()

    // 17: seçili sunucuya her 3 sn ölçüm, son 30 dk saklanır
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

    // 16: art arda 5 başarısız ölçüm + tünel de çalışmıyorsa yedek sunucu bul
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
        val server = servers.getOrNull(index) // Sadece özel sunucular silinebilir
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

    Scaffold(
        containerColor = BgDark,
        contentColor = Color.White,
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF10162B),
                contentColor = TextMuted,
                tonalElevation = 0.dp
            ) {
                val navColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = PurpleLight,
                    selectedTextColor = Color.White,
                    indicatorColor = Color(0xFF302052),
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
                    icon = { NavGlyph("settings", if (tab == 1) PurpleLight else TextMuted, Modifier.size(24.dp)) },
                    label = { Text(if (lang == "tm") "Aýratynlyklar" else "Özellikler", fontSize = 11.sp) },
                    colors = navColors
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { feedback(FeedbackType.TAP); tab = 2; showServers = false },
                    icon = { NavGlyph("game", if (tab == 2) PurpleLight else TextMuted, Modifier.size(24.dp)) },
                    label = { Text(if (lang == "tm") "Oýun" else "Oyun", fontSize = 11.sp) },
                    colors = navColors
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { feedback(FeedbackType.TAP); tab = 3; showServers = false },
                    icon = { NavGlyph("profile", if (tab == 3) PurpleLight else TextMuted, Modifier.size(24.dp)) },
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
                    onToggle = {
                        if (VpnState.stage == VpnState.ST_FAILED) {
                            feedback(FeedbackType.CONNECT)
                            stopVpnService(context)
                            Handler(Looper.getMainLooper()).postDelayed({ toggleVpn() }, 650)
                        } else {
                            feedback(if (VpnState.status == VpnState.CONNECTED || VpnState.status == VpnState.CONNECTING) FeedbackType.DISCONNECT else FeedbackType.CONNECT)
                            toggleVpn()
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
                tab == 1 -> FeaturesTab(
                    lang = lang,
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
                    feedback = feedback
                )
                tab == 2 -> GameToolsTab(
                    lang = lang,
                    state = gameState,
                    current = current,
                    allServers = allServers,
                    scope = gameScope,
                    onUseServer = switchToServer,
                    feedback = feedback
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
    onToggle: () -> Unit,
    onOpenServers: () -> Unit
) {
    val tm = lang == "tm"
    val connected = VpnState.status == VpnState.CONNECTED
    val failed = VpnState.stage == VpnState.ST_FAILED || VpnState.status == VpnState.ERROR
    val busy = VpnState.status == VpnState.CONNECTING && !failed
    val pulseTransition = rememberInfiniteTransition()
    val pulse by pulseTransition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.035f,
        animationSpec = infiniteRepeatable(tween(1150, easing = FastOutSlowInEasing), RepeatMode.Reverse)
    )
    val buttonScale = if (busy) pulse else 1f
    val accent = if (connected) AccentGreen else PurpleMain

    // Zaman formatlama (02:00:00)
    val hours = timeLeft / 3600
    val minutes = (timeLeft % 3600) / 60
    val seconds = timeLeft % 60
    val timeString = String.format("%02d:%02d:%02d", hours, minutes, seconds)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BrandHeader()
        Spacer(Modifier.height(14.dp))
        StatusPill(
            text = if (connected) "$timeString • ${if(tm) "Birikdi" else "Bağlandı"}" else vpnStatusText(lang),
            color = when {
                VpnState.status == VpnState.ERROR || VpnState.stage == VpnState.ST_FAILED -> RedSoft
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
                                failed -> if (tm) "GAÝTADAN SYNANYŞ" else "TEKRAR DENE"
                                busy -> if (tm) "BIRIGÝÄR" else "BAĞLANIYOR"
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
            shape = RoundedCornerShape(20.dp),
            color = CardBg,
            onClick = onOpenServers
        ) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFF302052)),
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
                    connected || VpnState.stage == VpnState.ST_FAILED -> if (tm) "Barlaň" else "Kontrol et"
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
    
    // 0 = İçindekiler, 1 = Özel
    var mainTab by remember { mutableStateOf(0) }
    // 0 = Sosyal Medya, 1 = PUBG
    var categoryTab by remember { mutableStateOf(0) }

    val currentList = when {
        mainTab == 1 -> customServers
        categoryTab == 0 -> builtInSocial
        else -> builtInPubg
    }

    // Seçili olan sunucunun tüm listedeki (allServers) indexini bulmak için
    val allServers = remember(customServers, builtInSocial, builtInPubg) { customServers + builtInSocial + builtInPubg }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        
        // EN ÜST KISIM (Geri Butonu ve Test Et)
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

        // SEKMELER (İçindekiler / Özel)
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

        // ALT KATEGORİLER (Sadece İçindekiler seçiliyse)
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

        // LİSTE
        if (currentList.isEmpty()) {
            AnimatedSurface(Modifier.fillMaxWidth(), color = CardBg, shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("○", color = PurpleLight, fontSize = 34.sp)
                    Text(if (tm) "Serwer ýok" else "Henüz sunucu yok", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        if (mainTab == 0) (if(tm) "Uzakdan kodlar ýüklenýär..." else "Uzaktan sunucular yükleniyor veya henüz eklenmedi.")
                        else (if (tm) "Aýratynlyklar bölüminden link goşuň." else "Özellikler bölümünden bağlantı ekleyin."), 
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
                        color = if (isSelected) Color(0xFF28194A) else CardBg,
                        shape = RoundedCornerShape(17.dp),
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
                            // SADECE ÖZEL SUNUCULARDA SİL BUTONU GÖZÜKÜR
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
// Profil kısmındaki ikonlar için yardımcı Composable
@Composable
private fun SocialIcon(type: String, modifier: Modifier = Modifier) {
    Text(
        text = if (type == "tiktok") "🎵" else "✈️",
        modifier = modifier,
        fontSize = 18.sp
    )
}

@Composable
private fun ProfileLinkButton(tag: String, title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    AnimatedSurface(
        modifier = modifier.height(76.dp),
        color = Color(0xFF20183B),
        shape = RoundedCornerShape(15.dp),
        onClick = onClick
    ) {
        Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            SocialIcon(tag)
            Spacer(Modifier.height(4.dp))
            Text(title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

@Composable
private fun ProfileTab(prefs: android.content.SharedPreferences, feedback: (FeedbackType) -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var profileName by remember { mutableStateOf(prefs.getString("profile_name", "Suffer Shadow") ?: "Suffer Shadow") }
    var photoUriText by remember { mutableStateOf(prefs.getString("profile_photo_uri", null)) }
    var photoBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editedName by remember { mutableStateOf(profileName) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            photoUriText = uri.toString()
            prefs.edit().putString("profile_photo_uri", photoUriText).apply()
        }
    }

    LaunchedEffect(photoUriText) {
        photoBitmap = null
        val savedUri = photoUriText?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (savedUri != null) {
            val handler = Handler(Looper.getMainLooper())
            thread {
                val bitmap = runCatching {
                    context.contentResolver.openInputStream(savedUri)?.use { stream -> BitmapFactory.decodeStream(stream)?.asImageBitmap() }
                }.getOrNull()
                handler.post { photoBitmap = bitmap }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("PROFİL", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        Text("Suffer Shadow topluluğu", color = TextMuted, fontSize = 12.sp)
        Spacer(Modifier.height(20.dp))
        Box(contentAlignment = Alignment.BottomEnd) {
            Box(
                Modifier.size(124.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(PurpleLight, PurpleDeep, CardBg)))
                    .border(3.dp, PurpleLight, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (photoBitmap != null) {
                    Image(bitmap = photoBitmap!!, contentDescription = "Profil fotoğrafı", modifier = Modifier.fillMaxSize().clip(CircleShape))
                } else {
                    Text("S", color = Color.White, fontSize = 66.sp, fontWeight = FontWeight.Black)
                }
            }
            AnimatedSurface(
                modifier = Modifier.size(39.dp).border(2.dp, BgDark, CircleShape),
                shape = CircleShape,
                brush = Brush.linearGradient(listOf(PurpleLight, PurpleMain)),
                onClick = { feedback(FeedbackType.TAP); photoPicker.launch(arrayOf("image/*")) },
                contentAlignment = Alignment.Center
            ) { Text("+", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(profileName, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { feedback(FeedbackType.TAP); editedName = profileName; showEditDialog = true }) {
                Text("Düzenle", color = PurpleLight, fontSize = 12.sp)
            }
        }
        Text("VPN Kullanıcısı", color = TextMuted, fontSize = 12.sp)

        Spacer(Modifier.height(22.dp))
        AnimatedSurface(Modifier.fillMaxWidth(), color = CardBg, shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("SOSYAL MEDYA", color = PurpleLight, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    ProfileLinkButton("tiktok", "TikTok kanalım", Modifier.weight(1f)) { feedback(FeedbackType.TAP); runCatching { uriHandler.openUri("https://tiktok.com/@suffer_pubg") } }
                    ProfileLinkButton("telegram", "Suffere yaz", Modifier.weight(1f)) { feedback(FeedbackType.TAP); runCatching { uriHandler.openUri("https://t.me/suffer_pubg") } }
                }
                Spacer(Modifier.height(9.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    ProfileLinkButton("telegram", "Kanala abone ol", Modifier.weight(1f)) { feedback(FeedbackType.TAP); runCatching { uriHandler.openUri("https://t.me/suffervpn") } }
                    ProfileLinkButton("telegram", "Sohbete katıl", Modifier.weight(1f)) { feedback(FeedbackType.TAP); runCatching { uriHandler.openUri("https://t.me/suffer_chat") } }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        NeonButton(
            text = "SUFFERE YAZ",
            modifier = Modifier.fillMaxWidth(),
            leading = "➤",
            onClick = { feedback(FeedbackType.TAP); runCatching { uriHandler.openUri("https://t.me/suffer_pubg") } }
        )
        Spacer(Modifier.height(18.dp))
        Text("SUFFER SHADOW • FAST VPN", color = TextMuted, fontSize = 10.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(18.dp))
    }

    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            properties = DialogProperties(dismissOnClickOutside = true),
            containerColor = CardBg,
            title = { Text("Profil adını değiştir", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = editedName,
                    onValueChange = { editedName = it },
                    singleLine = true,
                    label = { Text("Kullanıcı adı") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    feedback(FeedbackType.TAP)
                    profileName = editedName.trim().ifBlank { "Suffer Shadow" }
                    prefs.edit().putString("profile_name", profileName).apply()
                    showEditDialog = false
                }) { Text("Kaydet", color = PurpleLight) }
            },
            dismissButton = {
                TextButton(onClick = { feedback(FeedbackType.TAP); showEditDialog = false }) { Text("Vazgeç", color = TextMuted) }
            }
        )
    }
}

@Composable
private fun FeaturesTab(
    lang: String,
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
    feedback: (FeedbackType) -> Unit
) {
    val tm = lang == "tm"
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Text(if (tm) "Aýratynlyklar" else "Özellikler", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(if (tm) "Suffer Shadow sazlamalary" else "Suffer Shadow ayarları", color = TextMuted, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))

        SectionHeading("01", if (tm) "Link goş" else "Link yapıştır")
        AnimatedSurface(Modifier.fillMaxWidth(), color = CardBg, shape = RoundedCornerShape(19.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(if (tm) "VLESS / Shadowsocks baglanyşyklary" else "VLESS / Shadowsocks bağlantıları", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (tm) "Her setire bir serwer linkini goýuň." else "Her satıra bir sunucu bağlantısı yapıştırın.",
                    color = TextMuted,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = onInput,
                    label = { Text(if (tm) "vless:// ýa-da ss://" else "vless:// veya ss://") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 5
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    NeonButton(text = if (tm) "Ýelimle" else "Yapıştır", modifier = Modifier.weight(1f), secondary = true, onClick = onPaste)
                    NeonButton(text = if (tm) "Goş" else "Ekle", modifier = Modifier.weight(1f), onClick = onAdd)
                }
                if (message.isNotEmpty()) {
                    Spacer(Modifier.height(9.dp))
                    Text(message, color = AccentGreen, fontSize = 12.sp)
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    if (tm) "HTTPS abonelik linklerini we Hysteria2-i goldamak aýratyn parser talap edýär." else "Not: HTTPS abonelik bağlantısı ve Hysteria2 için ayrıca içe aktarma/protokol desteği gerekir.",
                    color = TextMuted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }
        }

        Spacer(Modifier.height(17.dp))
        SectionHeading("02", if (tm) "Programma bloklaýjy" else "Uygulama Engelleyici")
        SoonFeatureCard(
            title = if (tm) "Diňe saýlanan programmalar VPN ulansyn" else "Yalnızca seçilen uygulamalar VPN kullansın",
            subtitle = if (tm) "Programmanyň içindäki saýlama we VPN ugrukdyrmasy indiki tapgyrda." else "Uygulama seçimi ve Android VPN yönlendirmesi sonraki geliştirme aşamasında.",
            icon = "▦",
            feedback = feedback
        )
        Spacer(Modifier.height(12.dp))
        SectionHeading("03", if (tm) "Tor arassala" else "Ağ Temizle")
        SoonFeatureCard(
            title = if (tm) "Howpsuz ulgam hyzmaty" else "Güvenli sistem bakımı",
            subtitle = if (tm) "Android rugsat berýän çäkde işleýär." else "Android'in izin verdiği güvenli işlemlerle uygulanacak.",
            icon = "✧",
            feedback = feedback
        )

        Spacer(Modifier.height(18.dp))
        SectionHeading("04", if (tm) "Dil sazlamasy" else "Dil Ayarları")
        AnimatedSurface(Modifier.fillMaxWidth(), color = CardBg, shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(if (tm) "Programma dili" else "Uygulama dili", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(11.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NeonButton(text = "Türkçe", modifier = Modifier.weight(1f), secondary = lang != "tr", onClick = { onLang("tr") })
                    NeonButton(text = "Türkmençe", modifier = Modifier.weight(1f), secondary = lang != "tm", onClick = { onLang("tm") })
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionHeading("05", if (tm) "Ses we titreşim" else "Ses ve Titreşim")
        AnimatedSurface(Modifier.fillMaxWidth(), color = CardBg, shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                PreferenceSwitchRow(
                    title = if (tm) "Düwme sesleri" else "Buton sesleri",
                    subtitle = if (tm) "Basanyňyzda gysga ses" else "Dokunmalarda kısa geri bildirim sesi",
                    checked = soundEnabled,
                    onCheckedChange = { feedback(FeedbackType.TAP); onSound(it) }
                )
                Spacer(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))
                PreferenceSwitchRow(
                    title = if (tm) "Titreşim" else "Titreşim",
                    subtitle = if (tm) "Dolandyryşda ýeňil duýgur seslenme" else "Dokunma ve bağlantıda hafif dokunsal geri bildirim",
                    checked = vibrationEnabled,
                    onCheckedChange = { feedback(FeedbackType.TAP); onVibration(it) }
                )
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SectionHeading(number: String, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 9.dp)) {
        Text(number, color = PurpleLight, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        Spacer(Modifier.width(9.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun SoonFeatureCard(title: String, subtitle: String, icon: String, feedback: (FeedbackType) -> Unit) {
    AnimatedSurface(Modifier.fillMaxWidth(), color = CardBg, shape = RoundedCornerShape(18.dp), onClick = { feedback(FeedbackType.TAP) }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(43.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFF302052)), contentAlignment = Alignment.Center) {
                Text(icon, color = PurpleLight, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = TextMuted, fontSize = 11.sp, lineHeight = 15.sp)
            }
            Text("Yakında", color = PurpleLight, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PreferenceSwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
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
            modifier = Modifier.size(62.dp).clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(PurpleLight, PurpleMain, Color(0xFF24104E))))
                .border(1.dp, PurpleLight.copy(alpha = 0.6f), RoundedCornerShape(20.dp)),
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
private fun StatusPill(text: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardBg.copy(alpha = 0.82f)).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(9.dp))
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun InfoPill(modifier: Modifier = Modifier, title: String, subtitle: String) {
    Column(modifier.clip(RoundedCornerShape(15.dp)).background(CardBg2).padding(horizontal = 13.dp, vertical = 12.dp)) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Text(subtitle, color = TextMuted, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun NeonButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    secondary: Boolean = false,
    leading: String? = null
) {
    AnimatedSurface(
        modifier = modifier.heightIn(min = 46.dp),
        shape = RoundedCornerShape(15.dp),
        brush = if (secondary) null else Brush.horizontalGradient(listOf(PurpleDeep, PurpleMain, Color(0xFF8D2BFF))),
        color = if (secondary) Color(0xFF1A2038) else PurpleMain,
        onClick = onClick,
        contentAlignment = Alignment.Center
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 11.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                Text(leading, color = if (secondary) PurpleLight else Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(9.dp))
            }
            Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun AnimatedSurface(
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

// =====================================================================
// OYUN ARAÇLARI (15–20)
// =====================================================================

/** VPN servisi bu anahtarları SharedPreferences("suffer") üzerinden okuyabilir. */
object GamePrefs {
    const val BLOCK_BACKGROUND = "gm_block_bg"
    const val PRIORITIZE_GAME = "gm_priority"
    const val SUGGEST_BACKUP = "gm_backup"
    const val AUTO_FAILOVER = "gm_auto_failover"
    const val AB_TARGET = "gm_ab_target"
}

private data class GamePingSample(val timeMs: Long, val ms: Int) // ms = -1 -> zaman aşımı
private data class GameProbe(val avg: Int, val min: Int, val jitter: Int, val lossPct: Int)
private data class GameRanked(val server: Server, val result: GameProbe, val load: Int?)

private class GameToolsState(private val prefs: android.content.SharedPreferences) {
    // 17: ping geçmişi (son 30 dk)
    val history = mutableStateListOf<GamePingSample>()

    // Ayarlar
    var blockBackground by mutableStateOf(prefs.getBoolean(GamePrefs.BLOCK_BACKGROUND, false))
    var prioritizeGame by mutableStateOf(prefs.getBoolean(GamePrefs.PRIORITIZE_GAME, false))
    var suggestBackup by mutableStateOf(prefs.getBoolean(GamePrefs.SUGGEST_BACKUP, true))
    var autoFailover by mutableStateOf(prefs.getBoolean(GamePrefs.AUTO_FAILOVER, false))

    // 18: önce / sonra testi
    var abTarget by mutableStateOf(prefs.getString(GamePrefs.AB_TARGET, "") ?: "")
    var abBefore by mutableStateOf<GameProbe?>(null)
    var abAfter by mutableStateOf<GameProbe?>(null)
    var abRunning by mutableStateOf(false)
    var abError by mutableStateOf("")

    // 20: sunucu önerisi
    var rankRunning by mutableStateOf(false)
    var ranking by mutableStateOf<List<GameRanked>>(emptyList())

    // 16: yedek sunucu
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

    fun runAb(scope: CoroutineScope, vpnOn: Boolean, invalidMsg: String) {
        if (abRunning) return
        val t = parseGameTarget(abTarget)
        if (t == null) {
            abError = invalidMsg
            return
        }
        abError = ""
        abRunning = true
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    probeServer(Server("", t.first, t.first, t.second), 8)
                }
                if (vpnOn) abAfter = r else abBefore = r
            } finally {
                abRunning = false
            }
        }
    }

    fun runRanking(scope: CoroutineScope, servers: List<Server>) {
        if (rankRunning || servers.isEmpty()) return
        rankRunning = true
        scope.launch {
            try {
                ranking = rankServers(servers.take(24))
            } finally {
                rankRunning = false
            }
        }
    }
}

private fun parseGameTarget(raw: String): Pair<String, Int>? {
    val t = raw.trim().removePrefix("http://").removePrefix("https://").substringBefore("/")
    if (t.isEmpty()) return null
    val host = if (t.contains(":")) t.substringBeforeLast(":") else t
    val port = if (t.contains(":")) t.substringAfterLast(":").toIntOrNull() else 443
    if (host.isBlank() || port == null || port !in 1..65535) return null
    return host to port
}

/** Aynı sunucuya art arda TCP bağlantı süresi ölçer: ortalama, en düşük, jitter ve kayıp. */
private fun probeServer(s: Server, count: Int = 5): GameProbe {
    val ok = ArrayList<Int>()
    repeat(count) {
        val p = pingServer(s)
        if (p >= 0) ok.add(p)
        Thread.sleep(120)
    }
    val loss = (count - ok.size) * 100 / count
    if (ok.isEmpty()) return GameProbe(-1, -1, 0, 100)
    val jitter = if (ok.size < 2) 0 else ok.zipWithNext { a, b -> abs(a - b) }.average().toInt()
    return GameProbe(ok.average().toInt(), ok.minOrNull() ?: 0, jitter, loss)
}

/** Sunucu adında "load=40" (veya yuk=40) varsa yoğunluk olarak okunur. */
private fun serverLoad(s: Server): Int? =
    Regex("(?i)(?:load|yuk|yük|yogunluk|yoğunluk)\\s*[=:]\\s*(\\d{1,3})")
        .find(s.name)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)

/** Düşük puan = daha iyi. Ping, jitter, kayıp ve (varsa) yoğunluk birleştirilir. */
private fun gameScore(r: GameProbe, load: Int?): Double {
    if (r.avg < 0) return 1_000_000.0
    return r.avg + r.jitter * 2.0 + r.lossPct * 6.0 + (load ?: 0) * 1.5
}

private suspend fun rankServers(list: List<Server>): List<GameRanked> = coroutineScope {
    list.map { s -> async(Dispatchers.IO) { GameRanked(s, probeServer(s, 5), serverLoad(s)) } }
        .awaitAll()
        .sortedBy { gameScore(it.result, it.load) }
}

private fun openAppSettings(context: Context, pkg: String) {
    runCatching {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
private fun GamePingGraph(samples: List<GamePingSample>, windowMinutes: Int, modifier: Modifier = Modifier) {
    val now = System.currentTimeMillis()
    val windowMs = windowMinutes * 60_000L
    val visible = samples.filter { it.timeMs >= now - windowMs }
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        for (i in 0..3) {
            val y = h * i / 3f
            drawLine(Color.White.copy(alpha = 0.07f), Offset(0f, y), Offset(w, y), 1f)
        }
        if (visible.isEmpty()) return@Canvas
        val top = visible.maxOf { it.ms }.coerceAtLeast(50) * 1.15f
        val path = Path()
        var started = false
        visible.forEach { s ->
            val x = ((s.timeMs - (now - windowMs)).toFloat() / windowMs) * w
            if (s.ms >= 0) {
                val y = h - (s.ms / top) * h
                if (!started) {
                    path.moveTo(x, y)
                    started = true
                } else {
                    path.lineTo(x, y)
                }
            } else {
                drawCircle(RedSoft, 5f, Offset(x, 6f))
                started = false
            }
        }
        drawPath(path, PurpleLight, style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun GameBackupDialog(
    lang: String,
    ranked: GameRanked,
    failedName: String,
    auto: Boolean,
    onSwitch: () -> Unit,
    onDismiss: () -> Unit
) {
    val tm = lang == "tm"
    var left by remember(ranked.server.link) { mutableStateOf(10) }
    if (auto) {
        LaunchedEffect(ranked.server.link) {
            while (left > 0) {
                delay(1000L)
                left--
            }
            onSwitch()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        titleContentColor = Color.White,
        textContentColor = TextMuted,
        title = { Text(if (tm) "Ätiýaçlyk serwer teklibi" else "Yedek sunucu önerisi", fontWeight = FontWeight.Bold) },
        text = {
            val line1 = if (tm) "“$failedName” elýeterli däl." else "“$failedName” erişilemiyor."
            val line2 = (if (tm) "Ätiýaçlyk: " else "Yedek: ") + ranked.server.name + " • ${ranked.result.avg} ms"
            val line3 = if (tm) "Oýun wagtynda geçmek birikmäni birnäçe sekuntlyk üzüp biler. Oýun dowam edýän bolsa garaşmak has howpsuz bolup biler."
            else "Maç sırasında geçiş bağlantıyı birkaç saniye kesebilir. Maç sürüyorsa beklemek daha güvenli olabilir."
            val line4 = if (auto) (if (tm) "\n\nAwtomatik geçiş: $left sek" else "\n\nOtomatik geçiş: $left sn") else ""
            Text("$line1\n$line2\n\n$line3$line4", fontSize = 13.sp, lineHeight = 18.sp)
        },
        confirmButton = {
            TextButton(onClick = onSwitch) {
                Text(if (tm) "Şindi geç" else "Şimdi geç", color = PurpleLight, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (tm) "Bes et" else "Vazgeç", color = TextMuted)
            }
        }
    )
}

@Composable
private fun GameCard(content: @Composable ColumnScope.() -> Unit) {
    AnimatedSurface(Modifier.fillMaxWidth(), color = CardBg, shape = RoundedCornerShape(18.dp)) {
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
    feedback: (FeedbackType) -> Unit
) {
    val tm = lang == "tm"
    val context = LocalContext.current
    val vpnOn = VpnState.status == VpnState.CONNECTED
    var win by remember { mutableStateOf(1) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Text(if (tm) "Oýun gurallary" else "Oyun Araçları", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(if (tm) "Ping, serwer we birikme gözegçiligi" else "Ping, sunucu ve bağlantı takibi", color = TextMuted, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))

        // ---------- 15: Arka plan trafiği ----------
        SectionHeading("15", if (tm) "Arka plan trafigi" else "Arka Plan Trafiği Kontrolü")
        GameCard {
            PreferenceSwitchRow(
                title = if (tm) "Oýun wagtynda arka plany çäkle" else "Oyun sırasında arka planı kısıtla",
                subtitle = if (tm) "Gereksiz ýüklemeler we täzelenmeler birikmäni bulaşdyrmasyn" else "Gereksiz indirme ve güncellemeler bağlantıyı meşgul etmesin",
                checked = state.blockBackground,
                onCheckedChange = { feedback(FeedbackType.TAP); state.setFlag(GamePrefs.BLOCK_BACKGROUND, it) }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (tm) "Mağaza programmalarynyň arka plan wagtyndaky maglumatyny sistema sazlamalaryndan çäkläp bilersiň:"
                else "Mağaza uygulamalarının arka plan verisini sistem ayarlarından kısıtlayabilirsin:",
                color = TextMuted, fontSize = 11.sp, lineHeight = 15.sp
            )
            listOf(
                "Google Play" to "com.android.vending",
                "Galaxy Store" to "com.sec.android.app.samsungapps",
                "Xiaomi GetApps" to "com.xiaomi.mipicks",
                "Huawei AppGallery" to "com.huawei.appmarket"
            ).forEach { (title, pkg) ->
                Spacer(Modifier.height(8.dp))
                AnimatedSurface(
                    Modifier.fillMaxWidth(),
                    color = CardBg2,
                    shape = RoundedCornerShape(13.dp),
                    onClick = { feedback(FeedbackType.TAP); openAppSettings(context, pkg) }
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(if (tm) "Sazlamalar" else "Ayarlar", color = PurpleLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (tm) "Sazlama düwmeleri derrew işleýär. Üstdäki açar VPN hyzmatynyň goldawyna bagly."
                else "Ayar düğmeleri hemen etki eder. Üstteki anahtar VPN servisinin desteğine bağlıdır.",
                color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp
            )
        }

        // ---------- 16: Akıllı sunucu yedekleme ----------
        Spacer(Modifier.height(18.dp))
        SectionHeading("16", if (tm) "Akylly serwer ätiýaçlygy" else "Akıllı Sunucu Yedekleme")
        AnimatedSurface(Modifier.fillMaxWidth(), color = CardBg, shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                PreferenceSwitchRow(
                    title = if (tm) "Ätiýaçlyk serwer teklip et" else "Yedek sunucu öner",
                    subtitle = if (tm) "Esasy serwer yzly-yzyna 5 gezek jogap bermese" else "Ana sunucu art arda 5 ölçümde yanıt vermezse",
                    checked = state.suggestBackup,
                    onCheckedChange = { feedback(FeedbackType.TAP); state.setFlag(GamePrefs.SUGGEST_BACKUP, it) }
                )
                Spacer(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))
                PreferenceSwitchRow(
                    title = if (tm) "Awtomatik geçiş (10 sek garaşyp)" else "Otomatik geçiş (10 sn beklemeli)",
                    subtitle = if (tm) "Öçük. Oýun wagtynda birikme üzülip biler, şonuň üçin elden tassyklamak maslahat berilýär" else "Kapalı. Maç sırasında bağlantı kesilebilir; elle onay önerilir",
                    checked = state.autoFailover,
                    onCheckedChange = { feedback(FeedbackType.TAP); state.setFlag(GamePrefs.AUTO_FAILOVER, it) }
                )
            }
        }

        // ---------- 17: Ping grafiği ----------
        Spacer(Modifier.height(18.dp))
        SectionHeading("17", if (tm) "Oýun ping grafigi" else "Oyun Ping Grafiği")
        GameCard {
            Text(
                current?.name ?: (if (tm) "Serwer saýlanmady" else "Sunucu seçilmedi"),
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 5, 30).forEach { m ->
                    NeonButton(
                        text = "$m " + (if (tm) "min" else "dk"),
                        modifier = Modifier.weight(1f),
                        secondary = win != m,
                        onClick = { feedback(FeedbackType.TAP); win = m }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(12.dp)).background(CardBg2).padding(8.dp)) {
                GamePingGraph(state.history, win, Modifier.fillMaxSize())
            }
            val now = System.currentTimeMillis()
            val visible = state.history.filter { it.timeMs >= now - win * 60_000L }
            val okMs = visible.filter { it.ms >= 0 }.map { it.ms }
            val loss = if (visible.isEmpty()) 0 else (visible.size - okMs.size) * 100 / visible.size
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoPill(
                    Modifier.weight(1f),
                    if (okMs.isEmpty()) "-" else "${okMs.average().toInt()} ms",
                    if (tm) "Ortaça" else "Ortalama"
                )
                InfoPill(
                    Modifier.weight(1f),
                    if (okMs.isEmpty()) "-" else "${okMs.minOrNull() ?: 0}–${okMs.maxOrNull() ?: 0}",
                    if (tm) "Iň az–iň köp" else "Min–Maks"
                )
                InfoPill(Modifier.weight(1f), "%$loss", if (tm) "Ýitgi" else "Kayıp")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (tm) "Her 3 sekuntda serwer portuna TCP ölçegi alynýar. Kırmızı nokta: jogap ýok."
                else "Seçili sunucu portuna her 3 saniyede TCP ölçümü yapılır. Kırmızı nokta: yanıt yok.",
                color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp
            )
        }

        // ---------- 18: Önce / Sonra testi ----------
        Spacer(Modifier.height(18.dp))
        SectionHeading("18", if (tm) "Öň / Soň synagy" else "Önce / Sonra Testi")
        GameCard {
            Text(
                if (tm) "Şol bir oýun serwerine VPN öçükkä we açykka ölçeg al."
                else "Aynı oyun sunucusuna VPN kapalıyken ve açıkken ölçüm yap.",
                color = TextMuted, fontSize = 12.sp
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = state.abTarget,
                onValueChange = { state.updateTarget(it) },
                label = { Text(if (tm) "Oýun serweri (ip:port)" else "Oyun sunucusu (ip:port)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            NeonButton(
                text = if (state.abRunning) (if (tm) "Ölçülýär..." else "Ölçülüyor...")
                else if (vpnOn) (if (tm) "Ölç (VPN açyk)" else "Ölç (VPN açık)")
                else (if (tm) "Ölç (VPN öçük)" else "Ölç (VPN kapalı)"),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    feedback(FeedbackType.TAP)
                    state.runAb(scope, vpnOn, if (tm) "Dogry ip:port ýa-da host giriz." else "Geçerli bir ip:port veya alan adı gir.")
                }
            )
            if (state.abError.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(state.abError, color = RedSoft, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val b = state.abBefore
                val a = state.abAfter
                InfoPill(
                    Modifier.weight(1f),
                    if (b == null) "-" else if (b.avg < 0) (if (tm) "Elýeterli däl" else "Erişilemedi") else "${b.avg} ms",
                    (if (tm) "VPN öçük" else "VPN kapalı") + (if (b != null && b.avg >= 0) " • ±${b.jitter}" else "")
                )
                InfoPill(
                    Modifier.weight(1f),
                    if (a == null) "-" else if (a.avg < 0) (if (tm) "Elýeterli däl" else "Erişilemedi") else "${a.avg} ms",
                    (if (tm) "VPN açyk" else "VPN açık") + (if (a != null && a.avg >= 0) " • ±${a.jitter}" else "")
                )
            }
            val b2 = state.abBefore
            val a2 = state.abAfter
            if (b2 != null && a2 != null && b2.avg >= 0 && a2.avg >= 0) {
                val diff = a2.avg - b2.avg
                Spacer(Modifier.height(10.dp))
                val text = when {
                    diff < 0 -> if (tm) "VPN bilen ${-diff} ms pes" else "VPN ile ${-diff} ms daha düşük"
                    diff > 0 -> if (tm) "VPN bilen $diff ms ýokary" else "VPN ile $diff ms daha yüksek"
                    else -> if (tm) "Tapawut ýok" else "Fark yok"
                }
                Text(text, color = if (diff <= 0) AccentGreen else Color(0xFFFFD166), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (tm) "TCP birikme wagty ölçelýär; oýunlar UDP ulanyp biler. Programma VPN-den çykarylan bolsa «açyk» ölçeg tunelden geçmezligi mümkin."
                else "TCP bağlantı süresi ölçülür; oyunlar UDP kullanabilir. Uygulama VPN dışında tutuluyorsa «açık» ölçüm tünelden geçmeyebilir.",
                color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp
            )
        }

        // ---------- 19: Oyun trafiğine öncelik ----------
        Spacer(Modifier.height(18.dp))
        SectionHeading("19", if (tm) "Oýun trafigine ileri tutmak" else "Oyun Trafiğine Öncelik")
        AnimatedSurface(Modifier.fillMaxWidth(), color = CardBg, shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                PreferenceSwitchRow(
                    title = if (tm) "Oýun paketlerini öňe geçir" else "Oyun paketlerini öne al",
                    subtitle = if (tm) "Uly ýüklemelerden öň geçsin. Täsiri ulga we serwere bagly" else "Büyük indirmelerin önüne geçsin. Etkisi ağa ve sunucuya bağlıdır",
                    checked = state.prioritizeGame,
                    onCheckedChange = { feedback(FeedbackType.TAP); state.setFlag(GamePrefs.PRIORITIZE_GAME, it) }
                )
            }
        }

        // ---------- 20: Akıllı oyun sunucusu önerisi ----------
        Spacer(Modifier.height(18.dp))
        SectionHeading("20", if (tm) "Akylly oýun serweri" else "Akıllı Oyun Sunucusu Önerisi")
        GameCard {
            Text(
                if (tm) "Ping, jitter, paket ýitgisi we (bar bolsa) ýygnaklyk maglumatlary birleşdirilýär."
                else "Ping, jitter, paket kaybı ve (varsa) yoğunluk verileri birleştirilir.",
                color = TextMuted, fontSize = 12.sp
            )
            Spacer(Modifier.height(10.dp))
            NeonButton(
                text = if (state.rankRunning) (if (tm) "Synag edilýär..." else "Test ediliyor...") else (if (tm) "Serwerleri synag et" else "Sunucuları test et"),
                modifier = Modifier.fillMaxWidth(),
                onClick = { feedback(FeedbackType.TAP); state.runRanking(scope, allServers) }
            )
            if (allServers.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(if (tm) "Heniz serwer ýok." else "Henüz sunucu yok.", color = TextMuted, fontSize = 12.sp)
            }
            val ranked = state.ranking
            ranked.take(5).forEachIndexed { i, r ->
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(CardBg2).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.server.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Spacer(Modifier.height(3.dp))
                        val detail = if (r.result.avg < 0) {
                            if (tm) "Elýeterli däl" else "Erişilemiyor"
                        } else {
                            "${r.result.avg} ms • jitter ${r.result.jitter} • " + (if (tm) "ýitgi" else "kayıp") + " %${r.result.lossPct}" +
                                (if (r.load != null) " • " + (if (tm) "ýygnaklyk" else "yoğunluk") + " %${r.load}" else "")
                        }
                        Text(detail, color = TextMuted, fontSize = 11.sp)
                    }
                    if (i == 0 && r.result.avg >= 0) {
                        Text(if (tm) "Maslahat" else "Önerilen", color = AccentGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            val best = ranked.firstOrNull()
            if (best != null && best.result.avg >= 0) {
                Spacer(Modifier.height(10.dp))
                NeonButton(
                    text = if (tm) "Şu serwere geç" else "Bu sunucuya geç",
                    modifier = Modifier.fillMaxWidth(),
                    secondary = true,
                    onClick = { feedback(FeedbackType.TAP); onUseServer(best.server) }
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (tm) "Ýygnaklyk maglumaty serwer adyndaky load=NN bellikden okalýar; ýok bolsa hasaba alynmaýar."
                else "Yoğunluk bilgisi sunucu adındaki load=NN etiketinden okunur; yoksa hesaba katılmaz.",
                color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}
