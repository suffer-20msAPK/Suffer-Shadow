@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.suffershadow.vpn

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch // <-- EKLENEN İMPORT (Hata 75 ve 76'yı çözer)
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// --- Efsanevi Açılış Sesi ve Animasyonu ---
// DÜZELTME: MainActivity'de BoosterHeroCard çağrıldığı için fonksiyon adı değiştirildi.
@Composable
fun BoosterHeroCard(lang: String, prefs: SharedPreferences, feedback: (FeedbackType) -> Unit) {
    val tm = lang == "tm"
    val context = LocalContext.current
    var isBoosting by remember { mutableStateOf(false) }
    var boostProgress by remember { mutableStateOf(0f) }
    val rotation = rememberInfiniteTransition().animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing))
    )
    val pulse = rememberInfiniteTransition().animateFloat(
        initialValue = 0.95f, targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse)
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .border(2.dp, Brush.horizontalGradient(listOf(PurpleLight, PurpleMain, AccentGreen)), RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF1A0E40), BgDark)), RoundedCornerShape(24.dp))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        // Arka plan çizimleri (Neon Kokpit)
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            drawCircle(PurpleMain.copy(alpha = 0.1f), radius = size.minDimension / 2.5f, center = c)
            // Dönen çember
            drawArc(
                color = PurpleLight,
                startAngle = rotation.value,
                sweepAngle = 120f,
                useCenter = false,
                topLeft = Offset(c.x - size.minDimension / 3f, c.y - size.minDimension / 3f),
                size = androidx.compose.ui.geometry.Size(size.minDimension / 1.5f, size.minDimension / 1.5f),
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SUFFER BOOSTER", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Text(
                if (BoosterState.enabled) (if(tm) "SİSTEM OPTİMİZE EDİLDİ" else "SİSTEM OPTİMİZE EDİLDİ")
                else (if(tm) "OPTİMİZASYON BEKLENİYOR" else "OPTİMİZASYON BEKLENİYOR"),
                color = if (BoosterState.enabled) AccentGreen else TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(20.dp))

            // Ortadaki Dev Güç Butonu
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .graphicsLayer { scaleX = if (isBoosting) pulse.value else 1f; scaleY = if (isBoosting) pulse.value else 1f }
                    .background(Brush.radialGradient(listOf(PurpleMain, PurpleDeep)), CircleShape)
                    .border(3.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                    .clickable {
                        feedback(FeedbackType.CONNECT)
                        BoosterSound.playChime() // Efsanevi ses
                        isBoosting = true
                        BoosterState.enabled = true
                        prefs.edit().putBoolean(BoosterPrefs.ENABLED, true).apply()
                        startBoosterService(context)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(if (BoosterState.enabled) "AKTİF" else "BAŞLAT", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

// --- Hata ayıklama / Bilgi Kartı (Rakamsız) ---
@Composable
private fun InfoStat(title: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.Black)
        Text(title, color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

// =====================================================================
// Performans Monitörü (Modernize Edildi)
// =====================================================================
@Composable
internal fun PerformanceSection(lang: String, feedback: (FeedbackType) -> Unit) {
    val tm = lang == "tm"
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000L); now = System.currentTimeMillis() } }
    val svc = BoosterState.serviceRunning

    // Rakamlar (01, 02) kaldırıldı
    Text(if (tm) "PERFORMANS KOKPİTİ" else "PERFORMANS KOKPİTİ", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))

    AnimatedSurface(Modifier.fillMaxWidth(), color = CardBg, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InfoStat("PING", if (BoosterState.pingAvg >= 0) "${BoosterState.pingAvg}ms" else "--", if (BoosterState.pingAvg in 0..100) AccentGreen else Color(0xFFFFD166))
                InfoStat("JITTER", if (BoosterState.jitterMs >= 0) "${BoosterState.jitterMs}ms" else "--", PurpleLight)
                InfoStat(if(tm) "KAYIP" else "KAYIP", if (BoosterState.lossPct >= 0) "%${BoosterState.lossPct}" else "--", RedSoft)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InfoStat("RAM", if (BoosterState.ramUsedPct >= 0) "%${BoosterState.ramUsedPct}" else "--", Color.White)
                InfoStat("CPU", if (BoosterState.cpuPct >= 0) "%${BoosterState.cpuPct}" else "--", Color.White)
                InfoStat(if(tm) "ISI" else "ISI", if (BoosterState.battTempC >= 0) String.format(Locale.US, "%.1f°C", BoosterState.battTempC) else "--", if (BoosterState.thermalWarn) RedSoft else Color.White)
            }
        }
    }
}

// =====================================================================
// Oyun Geçmişi (Rakamsız)
// =====================================================================
@Composable
internal fun GameHistorySection(lang: String, prefs: SharedPreferences, feedback: (FeedbackType) -> Unit) {
    val tm = lang == "tm"
    var refresh by remember { mutableStateOf(0) }
    val items = remember(refresh) { GameHistory.read(prefs) }
    val fmt = remember { java.text.SimpleDateFormat("dd.MM HH:mm", Locale.US) }

    Text(if (tm) "MAÇ GEÇMİŞİ" else "MAÇ GEÇMİŞİ", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))

    AnimatedSurface(Modifier.fillMaxWidth(), color = CardBg, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            if (items.isEmpty()) {
                Text(if (tm) "Henüz kayıtlı maç yok." else "Henüz kayıtlı maç yok.", color = TextMuted, fontSize = 12.sp)
            } else {
                items.take(3).forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(fmt.format(java.util.Date(r.startMs)), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(r.server.ifEmpty { "Bilinmeyen Sunucu" }, color = TextMuted, fontSize = 11.sp)
                        }
                        Text("${r.avgPing} ms", color = PurpleLight, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// =====================================================================
// Redeem (Aynı kaldı, tasarımı uyumlu)
// =====================================================================
@Composable
internal fun RedeemSection(lang: String, prefs: SharedPreferences, feedback: (FeedbackType) -> Unit) {
    val tm = lang == "tm"
    val context = LocalContext.current
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var msgColor by remember { mutableStateOf(TextMuted) }
    val scope = rememberCoroutineScope()

    Text("REDEEM CODE", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))

    AnimatedSurface(Modifier.fillMaxWidth(), color = CardBg, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = code,
                onValueChange = { v -> code = v.uppercase(java.util.Locale.US).filter { it.isLetterOrDigit() || it == '-' }.take(32) },
                label = { Text(if(tm) "VIP Kodu" else "VIP Kodu") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            NeonButton(
                text = if (busy) "..." else (if (tm) "KODU KULLAN" else "KODU KULLAN"),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (code.length < 4) return@NeonButton
                    busy = true
                    scope.launch {
                        val (success, m) = RedeemManager.redeemCode(context, code.trim())
                        busy = false
                        msg = m
                        msgColor = if (success) AccentGreen else RedSoft
                        if (success) code = ""
                    }
                }
            )
            if (msg.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(msg, color = msgColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// =====================================================================
// Debug (Rakamsız)
// =====================================================================
@Composable
internal fun DebugSection(lang: String, feedback: (FeedbackType) -> Unit) {
    val tm = lang == "tm"
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var open by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    val text = remember(open, refresh) { if (open) DebugLog.read(context) else "" }

    Text(if (tm) "HATA AYIKLAMA" else "HATA AYIKLAMA", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))

    AnimatedSurface(Modifier.fillMaxWidth(), color = CardBg, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            NeonButton(
                text = if (open) (if (tm) "Gizle" else "Gizle") else (if (tm) "Göster" else "Göster"),
                modifier = Modifier.fillMaxWidth(),
                secondary = true,
                onClick = { open = !open }
            )
            if (open) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().background(CardBg2, RoundedCornerShape(8.dp)).padding(8.dp)) {
                    Text(text.ifEmpty { "Kayıt yok." }, color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
