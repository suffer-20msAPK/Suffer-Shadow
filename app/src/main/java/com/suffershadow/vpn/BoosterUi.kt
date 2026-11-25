package com.suffershadow.vpn

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

private fun t(tm: Boolean, tr: String, tk: String): String = if (tm) tk else tr

// =====================================================================
// 2-3. GAME BOOSTER KARTI (Oyun sekmesinin en üstü)
// =====================================================================

@Composable
private fun PermRow(title: String, ok: Boolean, okText: String, fixText: String, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (ok) {
            Text(okText, color = AccentGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        } else {
            Text(
                fixText, color = PurpleLight, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onFix() }.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
            )
        }
    }
}

@Composable
internal fun BoosterHeroCard(lang: String, prefs: SharedPreferences, feedback: (FeedbackType) -> Unit) {
    val tm = lang == "tm"
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Ayarlar ekranından dönünce izin durumları yeniden okunur
    var permTick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) permTick++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val overlayOk = remember(permTick) { canDrawOverlay(context) }
    val usageOk = remember(permTick) { hasUsageAccess(context) }
    val dndOk = remember(permTick) { hasDndAccess(context) }
    val pubgPkg = remember(permTick) { findInstalledPubg(context) }

    var autoOverlay by remember { mutableStateOf(prefs.getBoolean(BoosterPrefs.AUTO_OVERLAY, true)) }
    var dnd by remember { mutableStateOf(prefs.getBoolean(BoosterPrefs.DND, false)) }
    var launchMsg by remember { mutableStateOf("") }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            now = System.currentTimeMillis()
        }
    }

    val enabled = BoosterState.enabled

    fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_LONG).show()

    fun safeStartService(action: String? = null) {
        try {
            startBoosterService(context, action)
        } catch (e: Exception) {
            DebugLog.add(context, "booster start: " + e.message)
        }
    }

    val setEnabled: (Boolean) -> Unit = { on ->
        feedback(FeedbackType.TAP)
        BoosterState.enabled = on
        prefs.edit().putBoolean(BoosterPrefs.ENABLED, on).apply()
        if (on) {
            safeStartService()
            // Eksik izin varsa ilgili Android ayarına yönlendir (önce overlay, sonra kullanım erişimi)
            if (!overlayOk) {
                toast(t(tm, "Kayan panel için 'Diğer uygulamaların üzerinde göster' iznini ver.", "Ýüzýan panel üçin 'Beýleki programmalaryň üstünde görkez' rugsadyny beriň."))
                openSettingsScreen(context, "overlay")
            } else if (!usageOk) {
                toast(t(tm, "PUBG'yi algılamak için 'Kullanım erişimi' iznini ver.", "PUBG-ni tanamak üçin 'Ulanyş girelgesi' rugsadyny beriň."))
                openSettingsScreen(context, "usage")
            }
        } else {
            // Yalnızca oyun optimizasyonu + overlay kapanır; VPN servisine dokunulmaz
            stopBoosterService(context)
        }
    }

    val startPubg: () -> Unit = {
        feedback(FeedbackType.TAP)
        when (launchPubg(context)) {
            0 -> {
                launchMsg = t(tm, "PUBG başlatılıyor…", "PUBG açylýar…")
                if (BoosterState.enabled && !BoosterState.serviceRunning) safeStartService()
                if (!BoosterState.enabled) {
                    toast(t(tm, "Oyun modu için Game Booster'ı etkinleştir.", "Oýun modi üçin Game Booster-i işjeňleşdiriň."))
                }
            }
            1 -> {
                launchMsg = t(tm, "PUBG Mobile cihazda kurulu değil.", "PUBG Mobile enjamda gurlmadyk.")
                toast(launchMsg)
            }
            else -> {
                launchMsg = t(tm, "PUBG başlatılamadı. Hata ayıklama günlüğüne bak.", "PUBG açyp bolmady. Ýalňyşlyk ýazgysyna serediň.")
                toast(launchMsg)
            }
        }
    }

    val detect = when {
        !enabled -> t(tm, "Pasif", "Passiw")
        !usageOk -> t(tm, "Kullanım erişimi gerekli", "Ulanyş girelgesi gerek")
        BoosterState.gameRunning -> t(tm, "PUBG algılandı", "PUBG tapyldy") + " (" + BoosterState.gamePkg + ")"
        BoosterState.launchingUntil > now -> t(tm, "PUBG açılıyor…", "PUBG açylýar…")
        else -> t(tm, "PUBG bekleniyor", "PUBG garaşylýar")
    }

    val shape = RoundedCornerShape(24.dp)
    AnimatedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, Brush.linearGradient(listOf(PurpleLight, PurpleMain, AccentGreen.copy(alpha = if (enabled) 0.9f else 0.25f))), shape),
        shape = shape,
        brush = Brush.verticalGradient(listOf(Color(0xFF2A1260), Color(0xFF160C36), Color(0xFF0E1124))),
        contentAlignment = Alignment.TopStart
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("GAME BOOSTER", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                    Text(t(tm, "PUBG için ağ ve cihaz takibi", "PUBG üçin tor we enjam gözegçiligi"), color = PurpleLight, fontSize = 12.sp)
                }
                StatusPill(
                    text = if (enabled) t(tm, "AKTİF", "IŞJEŇ") else t(tm, "PASİF", "PASSIW"),
                    color = if (enabled) AccentGreen else TextMuted
                )
            }
            Spacer(Modifier.height(8.dp))
            PreferenceSwitchRow(
                title = t(tm, "GAME BOOSTER'İ ETKİNLEŞTİR", "GAME BOOSTER-i IŞJEŇLEŞDIR"),
                subtitle = t(tm, "Kapatınca yalnızca oyun modu ve kayan panel kapanır; VPN bağlantısı kesilmez.", "Ýapanyňyzda diňe oýun modi we panel ýapylýar; VPN üzülmeýär."),
                checked = enabled,
                onCheckedChange = setEnabled
            )

            Spacer(Modifier.height(6.dp))
            NeonButton(
                text = t(tm, "PUBG'Yİ BAŞLAT", "PUBG-NI IŞE GOŞ"),
                modifier = Modifier.fillMaxWidth(),
                onClick = startPubg,
                leading = "▶"
            )
            if (launchMsg.isNotEmpty() || pubgPkg == null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (launchMsg.isNotEmpty()) launchMsg
                    else t(tm, "PUBG Mobile bulunamadı (desteklenen: ${PUBG_PACKAGES.size} sürüm paketi).", "PUBG Mobile tapylmady (goldanýan: ${PUBG_PACKAGES.size} paket)."),
                    color = if (pubgPkg == null) Color(0xFFFFD166) else TextMuted, fontSize = 12.sp
                )
            }

            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
            Spacer(Modifier.height(6.dp))

            PermRow(
                t(tm, "Oyun algılama", "Oýun tanamak"),
                ok = usageOk,
                okText = detect,
                fixText = t(tm, "İzin ver ›", "Rugsat ber ›")
            ) { feedback(FeedbackType.TAP); openSettingsScreen(context, "usage") }
            PermRow(
                t(tm, "Overlay izni", "Overlay rugsady"),
                ok = overlayOk,
                okText = t(tm, "Verildi", "Berildi") + if (BoosterState.overlayShown) " • " + t(tm, "panel açık", "panel açyk") else "",
                fixText = t(tm, "İzin ver ›", "Rugsat ber ›")
            ) { feedback(FeedbackType.TAP); openSettingsScreen(context, "overlay") }
            PermRow(
                t(tm, "PUBG Mobile", "PUBG Mobile"),
                ok = pubgPkg != null,
                okText = t(tm, "Kurulu", "Gurlan") + " (" + (pubgPkg ?: "") + ")",
                fixText = t(tm, "Kurulu değil", "Gurlmadyk")
            ) { feedback(FeedbackType.TAP); toast(t(tm, "PUBG Mobile'ı mağazadan kur.", "PUBG Mobile-y dükandan guruň.")) }
            if (enabled && !usageOk) {
                Text(
                    t(tm, "Kullanım erişimi olmadan PUBG'nin açıldığı algılanamaz; oyun modu ve panel otomatik başlamaz.", "Ulanyş girelgesiz PUBG-niň açylandygy bilinmeýär; oýun modi awtomatik başlamaýar."),
                    color = Color(0xFFFFD166), fontSize = 11.sp, lineHeight = 15.sp
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                InfoPill(Modifier.weight(1f), if (BoosterState.pingAvg >= 0) "${BoosterState.pingAvg} ms" else "—", "Ping")
                InfoPill(Modifier.weight(1f), if (BoosterState.jitterMs >= 0) "${BoosterState.jitterMs} ms" else "—", "Jitter")
                InfoPill(Modifier.weight(1f), if (BoosterState.lossPct >= 0) "%${BoosterState.lossPct}" else "—", t(tm, "Kayıp", "Ýitgi"))
            }
            if (BoosterState.thermalWarn) {
                Spacer(Modifier.height(8.dp))
                Text(
                    t(tm, "⚠ Cihaz ısınıyor", "⚠ Enjam gyzýar") + (if (BoosterState.battTempC >= 0f) String.format(Locale.US, " (%.1f °C)", BoosterState.battTempC) else "") +
                        t(tm, ": oyuna kısa bir ara vermen önerilir.", ": oýuna gysga arakesme beriň."),
                    color = RedSoft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
            PreferenceSwitchRow(
                title = t(tm, "Oyun açılınca paneli otomatik aç", "Oýun açylanda paneli awtomatik aç"),
                subtitle = t(tm, "Mor SUFFER SHADOW düğmesi ekran kenarında görünür", "Ekranyň gyrasynda mawy-melewşe düwme görünýär"),
                checked = autoOverlay,
                onCheckedChange = { feedback(FeedbackType.TAP); autoOverlay = it; prefs.edit().putBoolean(BoosterPrefs.AUTO_OVERLAY, it).apply() }
            )
            PreferenceSwitchRow(
                title = t(tm, "Oyun sırasında bildirimleri sessize al", "Oýun wagtynda bildirişleri sesiz et"),
                subtitle = if (dndOk) t(tm, "Rahatsız Etme (yalnızca öncelikliler); oyun bitince eski haline döner", "Biynjalyk etme (diňe ileri tutulýanlar); oýun gutaranda gaýdyp gelýär")
                else t(tm, "Rahatsız Etme erişimi gerekli; açınca Ayarlar'a yönlendirilirsin", "Biynjalyk etme girelgesi gerek; açanyňyzda Sazlamalara geçirilýärsiňiz"),
                checked = dnd,
                onCheckedChange = {
                    feedback(FeedbackType.TAP)
                    dnd = it
                    prefs.edit().putBoolean(BoosterPrefs.DND, it).apply()
                    if (it && !dndOk) openSettingsScreen(context, "dnd")
                }
            )
            PreferenceSwitchRow(
                title = t(tm, "Panel animasyonlarını azalt", "Panel animasiýalaryny azalt"),
                subtitle = t(tm, "Kayan paneldeki açılma/kapanma animasyonları kapanır", "Panelde açylyş/ýapylyş animasiýalary ýapylýar"),
                checked = BoosterState.reduceAnim,
                onCheckedChange = {
                    feedback(FeedbackType.TAP)
                    BoosterState.reduceAnim = it
                    prefs.edit().putBoolean(BoosterPrefs.REDUCE_ANIM, it).apply()
                }
            )

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                NeonButton(
                    text = if (BoosterState.overlayShown) t(tm, "Paneli gizle", "Paneli gizle") else t(tm, "Paneli göster", "Paneli görkez"),
                    modifier = Modifier.weight(1f),
                    secondary = true,
                    onClick = {
                        feedback(FeedbackType.TAP)
                        when {
                            !BoosterState.enabled -> toast(t(tm, "Önce Game Booster'ı etkinleştir.", "Ilki bilen Game Booster-i işjeňleşdiriň."))
                            !overlayOk -> { openSettingsScreen(context, "overlay") }
                            BoosterState.overlayShown -> safeStartService(GameBoosterService.ACTION_HIDE_PANEL)
                            else -> safeStartService(GameBoosterService.ACTION_SHOW_PANEL)
                        }
                    }
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                t(
                    tm,
                    "Not: Android uygulamalara CPU Turbo / GPU Turbo / gerçek RAM temizleme / dokunmatik hassasiyeti vermez; bu uygulama bunları taklit etmez. Gösterilen ölçümler gerçek sistem ve ağ verisidir.",
                    "Bellik: Android CPU/GPU Turbo, hakyky RAM arassalamak ýa-da galtaşma duýgurlygyny programmalara bermeýär; bu programma olary öýkünmeýär. Görkezilen ölçegler hakyky ulgam we tor maglumatydyr."
                ),
                color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp
            )
        }
    }
}

// =====================================================================
// Performans monitörü + canlı trafik + kararlılık + oyun oturum sayacı
// =====================================================================

@Composable
private fun MetricRow(label: String, value: String, valueColor: Color = Color.White) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun PerformanceSection(lang: String, feedback: (FeedbackType) -> Unit) {
    val tm = lang == "tm"
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            now = System.currentTimeMillis()
        }
    }
    val svc = BoosterState.serviceRunning
    val connected = VpnState.status == VpnState.CONNECTED
    val na = t(tm, "erişilemiyor", "elýeterli däl")

    SectionHeading("02", t(tm, "Oyun performans monitörü", "Oýun öndürijilik monitory"))
    GameCard {
        if (!svc) {
            Text(
                t(tm, "Sistem ölçümleri (RAM, CPU, ısı, ping) Game Booster etkinken toplanır.", "Ulgam ölçegleri (RAM, CPU, gyzgynlyk, ping) Game Booster işjeňkä ýygnalýar."),
                color = Color(0xFFFFD166), fontSize = 12.sp
            )
            Spacer(Modifier.height(6.dp))
        }

        // Canlı internet trafiği (VPN'in gerçek trafiği)
        Text(t(tm, "Canlı internet trafiği", "Janly internet trafigi"), color = PurpleLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        if (connected && VpnState.speedSupported) {
            MetricRow("↓ " + t(tm, "İndirme", "Ýüklemek"), fmtRate(VpnState.rxBps))
            MetricRow("↑ " + t(tm, "Yükleme", "Ugratmak"), fmtRate(VpnState.txBps))
            MetricRow(t(tm, "Oturum toplamı", "Sessiýa jemi"), "↓ " + fmtRate(VpnState.rxTotal).replace("/s", "") + "  ↑ " + fmtRate(VpnState.txTotal).replace("/s", ""))
        } else {
            MetricRow(t(tm, "Durum", "Ýagdaý"), if (connected) t(tm, "Hız ölçülemiyor", "Tizlik ölçenok") else t(tm, "VPN bağlı değil", "VPN birikmedik"), TextMuted)
        }

        Spacer(Modifier.height(8.dp))
        Text(t(tm, "Cihaz", "Enjam"), color = PurpleLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        MetricRow(
            "RAM",
            if (BoosterState.ramUsedPct >= 0) "%${BoosterState.ramUsedPct} • " + t(tm, "boş", "boş") + " ${BoosterState.ramFreeMb} MB" else "—"
        )
        MetricRow("CPU", if (BoosterState.cpuPct >= 0) "%${BoosterState.cpuPct}" else if (svc) na else "—")
        MetricRow("FPS", t(tm, "ölçülemiyor (Android oyun FPS'ini üçüncü taraf uygulamaya vermez)", "ölçenok (Android oýun FPS-ni bermeýär)"), TextMuted)
        MetricRow(
            t(tm, "Pil / ısı", "Batareýa / gyzgynlyk"),
            (if (BoosterState.battPct >= 0) "%${BoosterState.battPct}" else "—") + " • " +
                (if (BoosterState.battTempC >= 0f) String.format(Locale.US, "%.1f °C", BoosterState.battTempC) else "—"),
            if (BoosterState.thermalWarn) RedSoft else Color.White
        )

        Spacer(Modifier.height(8.dp))
        Text(t(tm, "Oyun oturumu", "Oýun sessiýasy"), color = PurpleLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        MetricRow(
            t(tm, "Oyun oturum sayacı", "Oýun sessiýa sanawajy"),
            if (BoosterState.gameRunning && BoosterState.sessionStartMs > 0L) fmtClock((now - BoosterState.sessionStartMs) / 1000L) else "—"
        )
        MetricRow(
            t(tm, "Oyun modu", "Oýun modi"),
            if (BoosterState.gameRunning) t(tm, "Aktif", "Işjeň") else t(tm, "Beklemede", "Garaşylýar"),
            if (BoosterState.gameRunning) AccentGreen else Color.White
        )

        // Bağlantı kararlılığı (ping / jitter / paket kaybı)
        Spacer(Modifier.height(8.dp))
        Text(t(tm, "Bağlantı kararlılığı", "Baglanyşyk durnuklylygy"), color = PurpleLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        val p = BoosterState.pingAvg
        if (p < 0) {
            MetricRow(t(tm, "Durum", "Ýagdaý"), if (svc) t(tm, "Sunucuya ulaşılamıyor / sunucu seçilmedi", "Serwere ýetip bolanok / serwer saýlanmady") else "—", TextMuted)
        } else {
            val issues = ArrayList<String>()
            if (p > 300) issues.add(t(tm, "ping yüksek", "ping ýokary"))
            if (BoosterState.jitterMs > 100) issues.add(t(tm, "jitter yüksek", "jitter ýokary"))
            if (BoosterState.lossPct > 20) issues.add(t(tm, "paket kaybı", "paket ýitgisi"))
            if (issues.isEmpty()) {
                MetricRow(t(tm, "Durum", "Ýagdaý"), t(tm, "Kararlı", "Durnukly"), AccentGreen)
            } else {
                MetricRow(t(tm, "Durum", "Ýagdaý"), t(tm, "Kararsız", "Durnuksyz") + ": " + issues.joinToString(", "), Color(0xFFFFD166))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            t(tm, "Kararlı: ping ≤ 300 ms, jitter ≤ 100 ms, kayıp ≤ %20. Ölçüm, seçili sunucu portuna TCP bağlantı süresidir.", "Durnukly: ping ≤ 300 ms, jitter ≤ 100 ms, ýitgi ≤ %20. Ölçeg saýlanan serweriň portuna TCP wagtydyr."),
            color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp
        )
    }
}

// =====================================================================
// Oyun geçmişi
// =====================================================================

@Composable
internal fun GameHistorySection(lang: String, prefs: SharedPreferences, feedback: (FeedbackType) -> Unit) {
    val tm = lang == "tm"
    var refresh by remember { mutableStateOf(0) }
    val running = BoosterState.gameRunning
    val items = remember(running, refresh) { GameHistory.read(prefs) }
    val fmt = remember { SimpleDateFormat("dd.MM HH:mm", Locale.US) }

    SectionHeading("09", t(tm, "Oyun geçmişi", "Oýun taryhy"))
    GameCard {
        if (items.isEmpty()) {
            Text(
                t(tm, "Henüz kayıtlı oyun oturumu yok. Game Booster etkinken PUBG'yi açıp kapattığında (en az 15 sn) oturum buraya yazılır.", "Heniz ýazylan oýun ýok. Game Booster işjeňkä PUBG açylyp ýapylanda (iň az 15 sek) şu ýere ýazylýar."),
                color = TextMuted, fontSize = 12.sp, lineHeight = 16.sp
            )
        } else {
            items.take(10).forEach { r ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(fmt.format(Date(r.startMs)) + " • " + fmtClock(r.durationMs / 1000L), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (r.server.isEmpty()) "—" else r.server, color = TextMuted, fontSize = 11.sp, maxLines = 1)
                    }
                    Text(
                        (if (r.avgPing >= 0) "${r.avgPing} ms" else "—") + " • " + t(tm, "kayıp", "ýitgi") + " %${r.lossPct}",
                        color = PurpleLight, fontSize = 12.sp
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            NeonButton(
                text = t(tm, "Geçmişi temizle", "Taryhy arassala"),
                modifier = Modifier.fillMaxWidth(),
                secondary = true,
                onClick = {
                    feedback(FeedbackType.TAP)
                    prefs.edit().remove(BoosterPrefs.HISTORY).apply()
                    refresh++
                }
            )
        }
    }
}

// =====================================================================
// Hata ayıklama günlüğü (teknik hatalar ana ekranda değil burada)
// =====================================================================

@Composable
internal fun DebugSection(lang: String, feedback: (FeedbackType) -> Unit) {
    val tm = lang == "tm"
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var open by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    val text = remember(open, refresh) { if (open) DebugLog.read(context) else "" }

    SectionHeading("15", t(tm, "Hata ayıklama", "Ýalňyşlyk ýazgysy"))
    GameCard {
        Text(
            t(tm, "VPN ve Game Booster'ın teknik hata kayıtları ana ekranda gösterilmez, burada saklanır.", "VPN we Game Booster-iň tehniki ýalňyşlyklary esasy ekranda görkezilmeýär, şu ýerde saklanýar."),
            color = TextMuted, fontSize = 12.sp, lineHeight = 16.sp
        )
        Spacer(Modifier.height(10.dp))
        NeonButton(
            text = if (open) t(tm, "Günlüğü gizle", "Ýazgyny gizle") else t(tm, "Günlüğü göster", "Ýazgyny görkez"),
            modifier = Modifier.fillMaxWidth(),
            secondary = true,
            onClick = { feedback(FeedbackType.TAP); open = !open }
        )
        if (open) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().background(CardBg2, RoundedCornerShape(12.dp)).padding(10.dp)) {
                Text(
                    if (text.isEmpty()) t(tm, "Kayıt yok.", "Ýazgy ýok.") else text,
                    color = TextMuted, fontSize = 10.sp, lineHeight = 13.sp, fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeonButton(t(tm, "Yenile", "Täzele"), Modifier.weight(1f), onClick = { feedback(FeedbackType.TAP); refresh++ }, secondary = true)
                NeonButton(t(tm, "Kopyala", "Göçür"), Modifier.weight(1f), onClick = { feedback(FeedbackType.TAP); clipboard.setText(AnnotatedString(text)) }, secondary = true)
                NeonButton(t(tm, "Temizle", "Arassala"), Modifier.weight(1f), onClick = { feedback(FeedbackType.TAP); DebugLog.clear(context); refresh++ }, secondary = true)
            }
        }
    }
}

// =====================================================================
// REDEEM CODE — SUFFER SHADOW'un kendi promosyon kodları (PUBG UC / oyun içi kod DEĞİL)
// =====================================================================

object RedeemConfig {
    /**
     * Doğrulama sunucusunun HTTPS adresi, örn. "https://redeem.example.com/v1/redeem".
     * Boş bırakılırsa uygulama kodu kabul ETMEZ ve "doğrulama sunucusu yapılandırılmadı" der.
     * Hazır referans sunucu: backend/redeem-server (bkz. backend/README.md).
     */
    const val API_URL = ""
}

enum class RedeemResult { SUCCESS, INVALID, EXPIRED, USED, RATE_LIMITED, UNSUPPORTED, NOT_CONFIGURED, NETWORK_ERROR, BAD_RESPONSE }

data class RedeemOutcome(val result: RedeemResult, val minutes: Int = 0, val appliedNow: Boolean = false)

object RedeemClient {
    private const val SUPPORTED_REWARD = "session_minutes"

    private fun deviceId(prefs: SharedPreferences): String {
        var id = prefs.getString("device_id", "") ?: ""
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

    /** Ödül: VPN süresine dakika ekler. VPN bağlıysa mevcut oturum uzar; değilse bir sonraki oturuma eklenir. */
    private fun applyMinutes(prefs: SharedPreferences, minutes: Int): Boolean {
        val add = minutes * 60_000L
        val end = prefs.getLong("session_end", 0L)
        return if (VpnState.status == VpnState.CONNECTED && end > System.currentTimeMillis()) {
            prefs.edit().putLong("session_end", end + add).apply()
            true
        } else {
            prefs.edit().putLong("bonus_ms", prefs.getLong("bonus_ms", 0L) + add).apply()
            false
        }
    }

    fun redeem(context: Context, prefs: SharedPreferences, code: String, onDone: (RedeemOutcome) -> Unit) {
        thread(name = "redeem") {
            val out = try {
                call(prefs, code)
            } catch (e: org.json.JSONException) {
                DebugLog.add(context, "redeem: bad json")
                RedeemOutcome(RedeemResult.BAD_RESPONSE)
            } catch (e: Exception) {
                DebugLog.add(context, "redeem: " + (e.message ?: e.javaClass.simpleName))
                RedeemOutcome(RedeemResult.NETWORK_ERROR)
            }
            Handler(Looper.getMainLooper()).post { onDone(out) }
        }
    }

    private fun call(prefs: SharedPreferences, code: String): RedeemOutcome {
        val url = RedeemConfig.API_URL
        if (url.isBlank() || !url.startsWith("https://")) return RedeemOutcome(RedeemResult.NOT_CONFIGURED)

        val nonce = UUID.randomUUID().toString()
        val body = JSONObject()
            .put("code", code)
            .put("deviceId", deviceId(prefs))
            .put("nonce", nonce)
            .put("appVersion", "1.0")
            .put("supports", JSONArray().put(SUPPORTED_REWARD)) // sunucu desteklenmeyen ödülü tüketmeden reddeder
            .toString()

        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val http = conn.responseCode
            if (http == 429) return RedeemOutcome(RedeemResult.RATE_LIMITED)
            val stream = if (http in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val j = JSONObject(text)
            // Yanıt bu isteğe aittir (nonce eşleşmeli); aksi halde kabul edilmez
            if (j.optString("nonce") != nonce) return RedeemOutcome(RedeemResult.BAD_RESPONSE)
            return when (j.optString("status")) {
                "ok" -> {
                    val reward = j.optJSONObject("reward") ?: return RedeemOutcome(RedeemResult.BAD_RESPONSE)
                    if (reward.optString("type") != SUPPORTED_REWARD) return RedeemOutcome(RedeemResult.UNSUPPORTED)
                    val minutes = reward.optInt("value", 0)
                    if (minutes !in 1..1440) return RedeemOutcome(RedeemResult.BAD_RESPONSE)
                    RedeemOutcome(RedeemResult.SUCCESS, minutes, applyMinutes(prefs, minutes))
                }
                "invalid" -> RedeemOutcome(RedeemResult.INVALID)
                "expired" -> RedeemOutcome(RedeemResult.EXPIRED)
                "used" -> RedeemOutcome(RedeemResult.USED)
                "rate_limited" -> RedeemOutcome(RedeemResult.RATE_LIMITED)
                "unsupported" -> RedeemOutcome(RedeemResult.UNSUPPORTED)
                else -> RedeemOutcome(RedeemResult.BAD_RESPONSE)
            }
        } finally {
            conn.disconnect()
        }
    }
}

@Composable
internal fun RedeemSection(lang: String, prefs: SharedPreferences, feedback: (FeedbackType) -> Unit) {
    val tm = lang == "tm"
    val context = LocalContext.current
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var msgColor by remember { mutableStateOf(TextMuted) }

    val submit: () -> Unit = {
        feedback(FeedbackType.TAP)
        if (!busy) {
            busy = true
            msg = ""
            RedeemClient.redeem(context, prefs, code.trim()) { o ->
                busy = false
                when (o.result) {
                    RedeemResult.SUCCESS -> {
                        msgColor = AccentGreen
                        msg = t(tm, "Kod kabul edildi: +${o.minutes} dk VPN süresi", "Kod kabul edildi: +${o.minutes} min VPN wagty") +
                            if (o.appliedNow) "" else t(tm, " (bir sonraki bağlantıda uygulanır)", " (indiki birikmede ulanylýar)")
                        code = ""
                    }
                    RedeemResult.INVALID -> { msgColor = RedSoft; msg = t(tm, "Geçersiz kod.", "Nädogry kod.") }
                    RedeemResult.EXPIRED -> { msgColor = Color(0xFFFFD166); msg = t(tm, "Bu kodun süresi dolmuş.", "Bu koduň möhleti gutaran.") }
                    RedeemResult.USED -> { msgColor = Color(0xFFFFD166); msg = t(tm, "Bu kod daha önce kullanılmış.", "Bu kod öň ulanylypdyr.") }
                    RedeemResult.RATE_LIMITED -> { msgColor = Color(0xFFFFD166); msg = t(tm, "Çok fazla deneme. Biraz bekleyip tekrar dene.", "Gaty köp synanyşyk. Biraz garaşyp gaýtadan synanyşyň.") }
                    RedeemResult.UNSUPPORTED -> { msgColor = Color(0xFFFFD166); msg = t(tm, "Bu kodun ödülü uygulamanın bu sürümünde desteklenmiyor. Kod harcanmadı.", "Bu koduň baýragy programmanyň bu wersiýasynda goldanylmaýar. Kod ulanylmady.") }
                    RedeemResult.NOT_CONFIGURED -> { msgColor = Color(0xFFFFD166); msg = t(tm, "Kod doğrulama sunucusu henüz yapılandırılmamış; kod kullanılamaz.", "Kod barlag serweri heniz sazlanmandyr; kody ulanyp bolmaýar.") }
                    RedeemResult.NETWORK_ERROR -> { msgColor = RedSoft; msg = t(tm, "Doğrulama sunucusuna ulaşılamadı. İnternetini kontrol et.", "Barlag serwerine ýetip bolmady. Internetiňizi barlaň.") }
                    RedeemResult.BAD_RESPONSE -> { msgColor = RedSoft; msg = t(tm, "Sunucudan geçersiz yanıt geldi. Kod kullanılmadı.", "Serwerden nädogry jogap geldi. Kod ulanylmady.") }
                }
            }
        }
    }

    SectionHeading("01", "REDEEM CODE")
    val shape = RoundedCornerShape(22.dp)
    AnimatedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.2.dp, Brush.horizontalGradient(listOf(PurpleMain, PurpleLight, PurpleMain)), shape),
        shape = shape,
        brush = Brush.linearGradient(listOf(Color(0xFF1F0E4A), Color(0xFF120A2C), Color(0xFF0E1124))),
        contentAlignment = Alignment.TopStart
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("SUFFER SHADOW PROMO", color = PurpleLight, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                t(tm, "Promosyon kodunu gir, VPN süreni uzat.", "Promo kody giriziň, VPN wagtyňyzy uzaldyň."),
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { v -> code = v.uppercase().filter { it.isLetterOrDigit() || it == '-' }.take(32) },
                label = { Text(t(tm, "Kod", "Kod")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            NeonButton(
                text = if (busy) t(tm, "Doğrulanıyor…", "Barlanýar…") else t(tm, "KODU KULLAN", "KODY ULAN"),
                modifier = Modifier.fillMaxWidth(),
                onClick = { if (code.length >= 4) submit() else { msgColor = RedSoft; msg = t(tm, "Geçersiz kod.", "Nädogry kod.") } }
            )
            if (msg.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(msg, color = msgColor, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                t(
                    tm,
                    "Bu alan yalnızca SUFFER SHADOW promosyon kodları içindir. PUBG UC veya oyun içi ödül kodları desteklenmez. Kodlar uygulamada değil, sunucu tarafında doğrulanır.",
                    "Bu bölüm diňe SUFFER SHADOW promo kodlary üçindir. PUBG UC ýa-da oýun içindäki baýrak kodlary goldanylmaýar. Kodlar programmada däl, serwerde barlanýar."
                ),
                color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp
            )
        }
    }
}
