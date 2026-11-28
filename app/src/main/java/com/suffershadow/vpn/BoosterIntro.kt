@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.suffershadow.vpn

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.*
import kotlin.random.Random

private fun lx(tm: Boolean, tr: String, tk: String): String = if (tm) tk else tr
private val Warn = Color(0xFFFFD166)

object IntroPrefs {
    const val SOUND = "gb_intro_sound"
    const val HAPTIC = "gb_intro_haptic"
    const val QUALITY = "gb_intro_quality"
    const val PROFILE = "gb_profile"
}

enum class IntroQuality { FULL, LITE, OFF }

fun resolveIntroQuality(context: Context, prefs: SharedPreferences): IntroQuality {
    when (prefs.getString(IntroPrefs.QUALITY, "auto")) {
        "full" -> return IntroQuality.FULL
        "lite" -> return IntroQuality.LITE
        "off" -> return IntroQuality.OFF
    }
    val animScale = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    } catch (e: Exception) { 1f }
    if (animScale == 0f) return IntroQuality.OFF
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val weak = am.isLowRamDevice || am.memoryClass <= 192 ||
        Runtime.getRuntime().availableProcessors() <= 4 || BoosterState.reduceAnim
    return if (weak) IntroQuality.LITE else IntroQuality.FULL
}
enum class BoostProfile(
    val key: String,
    val intervalMs: Long,
    val reduceAnim: Boolean,
    val autoOverlay: Boolean,
    val dnd: Boolean,
    val color: Color
) {
    ECO("eco", 5000L, true, false, false, Color(0xFF00EFA8)),
    BALANCED("balanced", 2000L, false, true, false, Color(0xFFB46BFF)),
    ULTIMATE("ultimate", 1000L, false, true, true, Color(0xFFFF4D8D));

    fun desc(tm: Boolean): String = when (this) {
        ECO -> lx(tm, "Ölçüm 5 sn'de bir • panel animasyonları kapalı • panel otomatik açılmaz. En az pil tüketimi.", "Ölçeg 5 sekuntda bir • panel animasiýalary ýapyk • panel awtomatik açylmaýar. Iň az batareýa sarp edilýär.")
        BALANCED -> lx(tm, "Ölçüm 2 sn'de bir • oyun açılınca panel görünür • bildirimler normal.", "Ölçeg 2 sekuntda bir • oýun açylanda panel görünýär • bildirişler adaty.")
        ULTIMATE -> lx(tm, "Ölçüm 1 sn'de bir (ping/jitter daha hızlı takip) • panel otomatik • oyunda Rahatsız Etme. Pil tüketimi biraz artar.", "Ölçeg 1 sekuntda bir • panel awtomatik • oýunda Biynjalyk etme. Batareýa sarp edilişi birneme artýar.")
    }

    companion object {
        fun from(key: String?): BoostProfile = values().firstOrNull { it.key == key } ?: BALANCED
        fun current(prefs: SharedPreferences): BoostProfile = from(prefs.getString(IntroPrefs.PROFILE, "balanced"))
        fun intervalMs(prefs: SharedPreferences): Long = current(prefs).intervalMs
    }
}

object BoostProfileHolder {
    var profile by mutableStateOf<BoostProfile?>(null)
    fun get(prefs: SharedPreferences): BoostProfile = profile ?: BoostProfile.current(prefs)
}

fun applyBoostProfile(prefs: SharedPreferences, p: BoostProfile) {
    prefs.edit()
        .putString(IntroPrefs.PROFILE, p.key)
        .putBoolean(BoosterPrefs.REDUCE_ANIM, p.reduceAnim)
        .putBoolean(BoosterPrefs.AUTO_OVERLAY, p.autoOverlay)
        .putBoolean(BoosterPrefs.DND, p.dnd)
        .apply()
    BoosterState.reduceAnim = p.reduceAnim
    BoostProfileHolder.profile = p
}

private class IntroTimeline(val scale: Float) {
    val dimEnd = 320f * scale
    val dotStart = 260f * scale
    val growStart = 650f * scale
    val burst = 1500f * scale
    val textStart = 1950f * scale
    val chime = 2800f * scale
    val fadeStart = 2950f * scale
    val total = 3450f * scale
}

private fun prog(t: Float, a: Float, b: Float): Float = ((t - a) / (b - a)).coerceIn(0f, 1f)
private fun easeOut(x: Float): Float = 1f - (1f - x).pow(3)
private fun easeIn(x: Float): Float = x * x * x
private object BoosterSound {
    private const val RATE = 44100

    fun introPcm(tl: IntroTimeline): ShortArray {
        val n = ((tl.total + 350f) / 1000f * RATE).toInt()
        val buf = FloatArray(n)
        val rnd = Random(7)
        val s0 = (tl.dotStart / 1000f * RATE).toInt()
        val s1 = (tl.burst / 1000f * RATE).toInt().coerceAtMost(n)
        var ph1 = 0.0
        var ph2 = 0.0
        var lp = 0f
        for (i in s0 until s1) {
            val p = (i - s0).toFloat() / (s1 - s0)
            val f = 40.0 + 75.0 * p * p
            ph1 += 2 * PI * f / RATE
            ph2 += 2 * PI * f * 2.01 / RATE
            val env = p.pow(1.5f) * 0.55f
            val trem = 0.85f + 0.15f * sin(2 * PI * (4.0 + 10.0 * p) * i / RATE).toFloat()
            lp += ((rnd.nextFloat() * 2f - 1f) - lp) * (0.02f + 0.12f * p)
            buf[i] += env * trem * (sin(ph1).toFloat() * 0.8f + sin(ph2).toFloat() * 0.25f * p) + lp * 0.22f * p * p
        }
        val pulseLen = (0.45 * RATE).toInt()
        val clickLen = RATE / 50
        var ph = 0.0
        for (k in 0 until pulseLen) {
            val i = s1 + k
            if (i >= n) break
            val tt = k.toFloat() / RATE
            val f = 95.0 * exp(-tt * 9.0) + 38.0
            ph += 2 * PI * f / RATE
            var s = sin(ph).toFloat() * exp(-tt * 7f) * 0.9f
            if (k < clickLen) s += (rnd.nextFloat() * 2f - 1f) * 0.3f * (1f - k.toFloat() / clickLen)
            if (tt < 0.09f) {
                val d = sin(2 * PI * 880.0 * tt).toFloat()
                s += (round(d * 4f) / 4f) * 0.1f * (1f - tt / 0.09f)
            }
            buf[i] += s
        }
        addChime(buf, (tl.chime / 1000f * RATE).toInt())
        return toPcm(buf)
    }

    fun chimePcm(): ShortArray {
        val buf = FloatArray((0.45 * RATE).toInt())
        addChime(buf, 0)
        return toPcm(buf)
    }

    private fun addChime(buf: FloatArray, start: Int) {
        val notes = listOf(1318.5 to 0, 1760.0 to (0.075 * RATE).toInt())
        for ((f, off) in notes) {
            val len = (0.22 * RATE).toInt()
            for (k in 0 until len) {
                val i = start + off + k
                if (i >= buf.size) break
                val tt = k.toDouble() / RATE
                val env = (min(1.0, tt / 0.004) * exp(-tt * 16.0)).toFloat()
                buf[i] += (sin(2 * PI * f * tt) * 0.22 + sin(2 * PI * f * 2 * tt) * 0.06).toFloat() * env
            }
        }
    }

    private fun toPcm(buf: FloatArray): ShortArray =
        ShortArray(buf.size) { (tanh(buf[it] * 1.1f) * 0.85f * 32767f).toInt().toShort() }

    fun makeTrack(pcm: ShortArray): AudioTrack? = try {
        if (Build.VERSION.SDK_INT < 23) null else AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
            .also { it.write(pcm, 0, pcm.size) }
    } catch (e: Exception) { null }

    fun playChime() {
        thread(name = "boost-chime") {
            val tr = makeTrack(chimePcm()) ?: return@thread
            runCatching { tr.play() }
            Thread.sleep(600L)
            runCatching { tr.stop(); tr.release() }
        }
    }
}
private fun DrawScope.drawEnergyCore(c: Offset, r: Float, intensity: Float, color: Color, lite: Boolean) {
    if (r <= 0.5f) return
    val glowR = r * (if (lite) 2.0f else 2.6f)
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.45f * intensity), color.copy(alpha = 0.12f * intensity), Color.Transparent), center = c, radius = glowR), radius = glowR, center = c)
    drawCircle(Brush.radialGradient(listOf(Color.White, PurpleLight, color, PurpleDeep.copy(alpha = 0f)), center = c, radius = r), radius = r, center = c)
    drawCircle(Color.White.copy(alpha = 0.85f * intensity), radius = r * 0.28f, center = c)
}

private fun DrawScope.drawRings(c: Offset, baseR: Float, rotDeg: Float, alpha: Float, color: Color, lite: Boolean) {
    if (alpha <= 0f || baseR <= 0f) return
    val specs = if (lite) listOf(1.55f to 1f, 2.05f to -0.6f) else listOf(1.45f to 1f, 1.85f to -0.7f, 2.3f to 0.45f)
    specs.forEachIndexed { i, (k, speed) ->
        val rr = baseR * k
        val topLeft = Offset(c.x - rr, c.y - rr)
        val sz = Size(rr * 2f, rr * 2f)
        val start = rotDeg * speed + i * 40f
        val w = 3.dp.toPx() - i * 0.6f.dp.toPx()
        val sweep = 250f - i * 40f
        if (!lite) drawArc(color.copy(alpha = 0.16f * alpha), start, sweep, false, topLeft, sz, style = Stroke(w * 4f, cap = StrokeCap.Round))
        drawArc(color.copy(alpha = 0.9f * alpha), start, sweep, false, topLeft, sz, style = Stroke(w, cap = StrokeCap.Round))
        drawArc(Color.White.copy(alpha = 0.7f * alpha), start + sweep + 20f, 16f, false, topLeft, sz, style = Stroke(w, cap = StrokeCap.Round))
    }
}

private class Particle(val angle: Float, val speed: Float, val size: Float, val delay: Float, val white: Boolean)

private fun DrawScope.drawIntro(t: Float, tl: IntroTimeline, lite: Boolean, ps: List<Particle>) {
    val w = size.width
    val h = size.height
    val m = min(w, h)
    val c = Offset(w / 2f, h * 0.42f)
    val s = tl.scale
    val dim = easeOut(prog(t, 0f, tl.dimEnd))
    drawRect(Color(0xFF04050D).copy(alpha = 0.9f * dim))
    drawRect(Brush.radialGradient(listOf(Color(0xFF2A1260).copy(alpha = 0.55f * dim), Color.Transparent), center = c, radius = m * 0.9f))
    if (t < tl.dotStart) return
    val appear = easeOut(prog(t, tl.dotStart, tl.growStart))
    val grow = easeIn(prog(t, tl.growStart, tl.burst))
    val dotR = m * 0.012f
    val maxR = m * 0.10f
    val after = prog(t, tl.burst, tl.burst + 600f * s)
    val coreR = if (t < tl.burst) dotR * appear + (maxR - dotR) * grow else maxR * (1f + 0.22f * (1f - easeOut(after)))
    if (!lite && t < tl.burst) {
        ps.forEachIndexed { i, p ->
            if (i % 2 == 0) {
                val q = prog(t, tl.growStart + p.delay * 400f * s, tl.burst)
                if (q > 0f && q < 1f) {
                    val d = m * 0.45f * (1f - easeIn(q)) * p.speed + coreR
                    val pos = c + Offset(cos(p.angle) * d, sin(p.angle) * d)
                    drawCircle(if (p.white) Color.White else PurpleLight, (p.size * 0.8f).dp.toPx(), pos, alpha = (q * 1.4f).coerceAtMost(1f) * 0.9f)
                }
            }
        }
    }
    val ringStart = tl.burst - 150f * s
    if (t >= ringStart) {
        val ra = easeOut(prog(t, ringStart, tl.burst + 400f * s))
        drawRings(c, maxR * (0.7f + 0.3f * ra), (t - tl.burst) * 0.18f, ra, PurpleLight, lite)
    }
    drawEnergyCore(c, coreR, 0.6f + 0.4f * appear, PurpleMain, lite)
    if (t >= tl.burst) {
        val waves = if (lite) 1 else 2
        for (k in 0 until waves) {
            val e = prog(t, tl.burst + k * 140f * s, tl.burst + (700f + k * 140f) * s)
            if (e > 0f && e < 1f) {
                drawCircle(PurpleLight.copy(alpha = (1f - e) * 0.8f), radius = maxR * (1f + 4.2f * easeOut(e)), center = c, style = Stroke(width = (7f * (1f - e) + 1f).dp.toPx()))
            }
        }
        val f = prog(t, tl.burst, tl.burst + 260f * s)
        if (f < 1f) drawCircle(Color.White.copy(alpha = 0.55f * (1f - f)), radius = maxR * (1.2f + 2f * f), center = c)
        ps.forEach { p ->
            val q = prog(t, tl.burst + p.delay * 250f * s, tl.burst + (1100f + p.delay * 250f) * s)
            if (q > 0f && q < 1f) {
                val dir = Offset(cos(p.angle), sin(p.angle))
                val pos = c + dir * (maxR + m * 0.48f * p.speed * easeOut(q))
                val col = if (p.white) Color.White else PurpleLight
                if (!lite) drawLine(col.copy(alpha = 0.35f * (1f - q)), pos - dir * (16f * p.speed).dp.toPx(), pos, strokeWidth = p.size.dp.toPx() * 0.7f, cap = StrokeCap.Round)
                drawCircle(col.copy(alpha = 1f - q), p.size.dp.toPx(), pos)
            }
        }
    }
}@Composable
private fun BoosterIntro(
    tl: IntroTimeline, lite: Boolean, sound: Boolean, haptic: Boolean,
    time: () -> Float, onTime: (Float) -> Unit, onDone: () -> Unit
) {
    val view = LocalView.current
    val particles = remember(lite) {
        val r = Random(42)
        List(if (lite) 18 else 64) {
            Particle(r.nextFloat() * 2f * PI.toFloat(), 0.35f + r.nextFloat() * 0.65f, 1.2f + r.nextFloat() * 2.2f, r.nextFloat() * 0.25f, r.nextFloat() < 0.3f)
        }
    }

    LaunchedEffect(Unit) {
        var track: AudioTrack? = null
        try {
            if (sound) track = withContext(Dispatchers.Default) { BoosterSound.makeTrack(BoosterSound.introPcm(tl)) }
            runCatching { track?.play() }
            var start = -1L
            var burstDone = false
            var endDone = false
            while (true) {
                val now = withFrameNanos { it }
                if (start < 0L) start = now
                val t = (now - start) / 1_000_000f
                onTime(t)
                if (haptic && !burstDone && t >= tl.burst) { burstDone = true; view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
                if (haptic && !endDone && t >= tl.chime) { endDone = true; view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                if (t >= tl.total) break
            }
            val tr = track
            track = null
            if (tr != null) thread { Thread.sleep(700L); runCatching { tr.stop(); tr.release() } }
            onDone()
        } finally {
            track?.let { runCatching { it.stop(); it.release() } }
        }
    }

    Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - prog(time(), tl.fadeStart, tl.total) }
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDone() }
    ) {
        Canvas(Modifier.fillMaxSize()) { drawIntro(time(), tl, lite, particles) }
        Column(
            Modifier.align(Alignment.Center).offset(y = 150.dp)
                .graphicsLayer {
                    val a = easeOut(prog(time(), tl.textStart, tl.textStart + 450f * tl.scale))
                    alpha = a
                    translationY = (1f - a) * 24.dp.toPx()
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    val p = prog(time(), tl.textStart + 250f * tl.scale, tl.textStart + 1000f * tl.scale)
                    if (p > 0f && p < 1f) {
                        val x = -size.width * 0.3f + p * size.width * 1.6f
                        val half = 50.dp.toPx()
                        drawRect(
                            Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.9f), Color.Transparent), start = Offset(x - half, 0f), end = Offset(x + half, size.height)),
                            blendMode = BlendMode.SrcAtop
                        )
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("SUFFER SHADOW", style = TextStyle(color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp, shadow = Shadow(PurpleLight, Offset.Zero, if (lite) 12f else 28f)))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.width(220.dp).height(2.dp).graphicsLayer { scaleX = easeOut(prog(time(), tl.textStart + 100f * tl.scale, tl.textStart + 700f * tl.scale)) }
                .background(Brush.horizontalGradient(listOf(Color.Transparent, PurpleLight, Color.White, PurpleLight, Color.Transparent)))
            )
            Spacer(Modifier.height(8.dp))
            Text("GAME BOOSTER", color = PurpleLight, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp)
        }
    }
}

@Composable
internal fun GameBoosterScreen(lang: String, prefs: SharedPreferences, feedback: (FeedbackType) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var quality by remember { mutableStateOf(resolveIntroQuality(context, prefs)) }
    var introActive by remember { mutableStateOf(quality != IntroQuality.OFF) }
    var introRun by remember { mutableStateOf(0) }
    val tl = remember(quality) { IntroTimeline(if (quality == IntroQuality.LITE) 0.65f else 1f) }
    val tState = remember { mutableStateOf(0f) }

    Dialog(
        onDismissRequest = { if (introActive) introActive = false else onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        val dv = LocalView.current
        SideEffect { (dv.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
        Box(Modifier.fillMaxSize()) {
            BoosterPanel(
                lang = lang, prefs = prefs, feedback = feedback, lite = quality != IntroQuality.FULL, onClose = onClose,
                onReplayIntro = {
                    quality = resolveIntroQuality(context, prefs)
                    if (quality != IntroQuality.OFF) { tState.value = 0f; introRun++; introActive = true }
                },
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    val a = if (!introActive) 1f else prog(tState.value, tl.fadeStart, tl.total)
                    alpha = a; scaleX = 0.95f + 0.05f * a; scaleY = scaleX
                }
            )
            if (introActive) {
                key(introRun) {
                    BoosterIntro(tl = tl, lite = quality == IntroQuality.LITE, sound = prefs.getBoolean(IntroPrefs.SOUND, true), haptic = prefs.getBoolean(IntroPrefs.HAPTIC, true), time = { tState.value }, onTime = { tState.value = it }, onDone = { introActive = false })
                }
            }
        }
    }
}

private data class QuickStats(val ramPct: Int = -1, val ramFreeMb: Int = -1, val battPct: Int = -1, val tempC: Float = -1f, val charging: Boolean = false)

private fun readQuickStats(context: Context): QuickStats = try {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mi = ActivityManager.MemoryInfo()
    am.getMemoryInfo(mi)
    val ramPct = if (mi.totalMem > 0L) (((mi.totalMem - mi.availMem) * 100L) / mi.totalMem).toInt() else -1
    val free = (mi.availMem / (1024L * 1024L)).toInt()
    val b = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val lvl = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val sc = b?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val temp = b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
    val plugged = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    QuickStats(ramPct, free, if (lvl >= 0 && sc > 0) lvl * 100 / sc else -1, if (temp >= 0) temp / 10f else -1f, plugged)
} catch (e: Exception) { QuickStats() }
@Composable
private fun PanelCore(profile: BoostProfile, boosting: Boolean, lite: Boolean, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition()
    val rot by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(if (lite) 9000 else 6000, easing = LinearEasing)))
    val breathe by inf.animateFloat(0.92f, 1.06f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse))
    val boostAmt by animateFloatAsState(if (boosting) 1f else 0f, tween(400))
    Canvas(modifier) {
        val c = center
        val base = size.minDimension * 0.17f
        drawRings(c, base, rot * (if (boosting) 3f else 1f), 0.85f, profile.color, lite)
        drawEnergyCore(c, base * (breathe + 0.15f * boostAmt), 0.8f + 0.2f * boostAmt, profile.color, lite)
    }
}

@Composable
private fun StatTile(modifier: Modifier, label: String, value: String, sub: String, progress: Float?, color: Color) {
    val shape = RoundedCornerShape(15.dp)
    Column(modifier.clip(shape).background(CardBg2).border(1.dp, color.copy(alpha = 0.28f), shape).padding(horizontal = 11.dp, vertical = 10.dp)) {
        Text(label, color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        if (progress != null) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.08f))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(color))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(sub, color = TextMuted, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun FeatureCard(modifier: Modifier, icon: String, title: String, onClick: () -> Unit) {
    AnimatedSurface(modifier.height(78.dp), color = CardBg, shape = RoundedCornerShape(16.dp), onClick = onClick) {
        Column(Modifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF302052)), contentAlignment = Alignment.Center) {
                Text(icon, color = PurpleLight, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(5.dp))
            Text(title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ProfileChip(modifier: Modifier, p: BoostProfile, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(15.dp)
    AnimatedSurface(modifier.height(60.dp).border(if (selected) 1.5.dp else 1.dp, if (selected) p.color else Color.White.copy(alpha = 0.08f), shape), shape = shape, color = if (selected) p.color.copy(alpha = 0.16f) else CardBg, onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(p.name, color = if (selected) Color.White else TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            Spacer(Modifier.height(3.dp))
            Text("${p.intervalMs / 1000} sn", color = if (selected) p.color else TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BoosterPanel(
    lang: String, prefs: SharedPreferences, feedback: (FeedbackType) -> Unit, lite: Boolean,
    onClose: () -> Unit, onReplayIntro: () -> Unit, modifier: Modifier = Modifier
) {
    val tm = lang == "tm"
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val profile = BoostProfileHolder.get(prefs)

    var stats by remember { mutableStateOf(QuickStats()) }
    var boosting by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<List<Pair<String, Color>>>(emptyList()) }
    var showPerms by remember { mutableStateOf(false) }
    var showIntroSettings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) { stats = withContext(Dispatchers.IO) { readQuickStats(context) }; delay(2000L) }
    }

    val toast: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    val startService: (String?) -> Unit = { action -> try { startBoosterService(context, action) } catch (e: Exception) { DebugLog.add(context, "booster start: " + e.message) } }

    val runBoost: () -> Unit = {
        if (!boosting) {
            feedback(FeedbackType.CONNECT)
            boosting = true
            report = emptyList()
            scope.launch {
                val out = ArrayList<Pair<String, Color>>()
                val p = BoostProfileHolder.get(prefs)
                try {
                    step = lx(tm, "PROFİL UYGULANIYOR…", "PROFIL ULANYLÝAR…")
                    applyBoostProfile(prefs, p)
                    out.add(lx(tm, "Profil uygulandı: ${p.name}", "Profil ulanyldy: ${p.name}") to AccentGreen)
                    delay(300L)
                    step = lx(tm, "SERVİS BAŞLATILIYOR…", "HYZMAT IŞE GOŞULÝAR…")
                    if (!BoosterState.enabled || !BoosterState.serviceRunning) {
                        BoosterState.enabled = true
                        prefs.edit().putBoolean(BoosterPrefs.ENABLED, true).apply()
                        startService(null)
                    }
                    out.add(lx(tm, "Game Booster servisi aktif", "Game Booster hyzmaty işjeň") to AccentGreen)
                    if (!hasUsageAccess(context)) out.add(lx(tm, "Kullanım erişimi yok: PUBG otomatik algılanmaz", "Ulanyş girelgesi ýok: PUBG awtomatik tanalmaýar") to Warn)
                    if (p.autoOverlay && !canDrawOverlay(context)) out.add(lx(tm, "Overlay izni yok: kayan panel açılamaz", "Overlay rugsady ýok: panel açylmaýar") to Warn)
                    if (p.dnd && !hasDndAccess(context)) out.add(lx(tm, "Rahatsız Etme erişimi yok: bildirimler sessize alınamaz", "Biynjalyk etme girelgesi ýok") to Warn)
                    delay(250L)
                    step = lx(tm, "AĞ TESTİ…", "TOR SYNAGY…")
                    val host = prefs.getString("active_host", "") ?: ""
                    val port = prefs.getInt("active_port", 0)
                    if (host.isEmpty() || port <= 0) {
                        out.add(lx(tm, "Sunucu seçilmedi; ağ testi atlandı", "Serwer saýlanmady; tor synagy geçildi") to Warn)
                    } else {
                        val samples = withContext(Dispatchers.IO) { List(6) { val v = pingServer(Server("", host, host, port)); Thread.sleep(120L); v } }
                        val ok = samples.filter { it >= 0 }
                        val loss = (samples.size - ok.size) * 100 / samples.size
                        if (ok.isEmpty()) {
                            out.add(lx(tm, "Sunucuya ulaşılamadı (%100 kayıp)", "Serwere ýetip bolmady (%100 ýitgi)") to RedSoft)
                        } else {
                            val avg = ok.average().toInt()
                            val jit = if (ok.size < 2) 0 else ok.zipWithNext { a, b -> abs(a - b) }.average().toInt()
                            val col = when { avg <= 120 && jit <= 30 && loss == 0 -> AccentGreen; avg <= 300 && jit <= 100 && loss <= 20 -> Warn; else -> RedSoft }
                            out.add(lx(tm, "Ağ: $avg ms • jitter $jit ms • kayıp %$loss", "Tor: $avg ms • jitter $jit ms • ýitgi %$loss") to col)
                        }
                    }
                    step = lx(tm, "CİHAZ KONTROLÜ…", "ENJAM BARLAGY…")
                    val s = withContext(Dispatchers.IO) { readQuickStats(context) }
                    stats = s
                    if (s.ramPct >= 85) out.add(lx(tm, "RAM dolu (%${s.ramPct}). Arka plandaki uygulamaları kapatmak yardımcı olur.", "RAM doly (%${s.ramPct}). Fondaky programmalary ýapmak kömek eder.") to Warn)
                    else if (s.ramFreeMb >= 0) out.add(lx(tm, "Boş RAM: ${s.ramFreeMb} MB", "Boş RAM: ${s.ramFreeMb} MB") to AccentGreen)
                    when {
                        s.tempC >= 42f -> out.add(lx(tm, "Cihaz sıcak (${fmtTemp(s.tempC)}). Kısa bir mola önerilir.", "Enjam gyzgyn (${fmtTemp(s.tempC)}). Gysga arakesme maslahat berilýär.") to RedSoft)
                        s.tempC >= 38f -> out.add(lx(tm, "Cihaz ılık (${fmtTemp(s.tempC)})", "Enjam ýyly (${fmtTemp(s.tempC)})") to Warn)
                        s.tempC >= 0f -> out.add(lx(tm, "Sıcaklık normal (${fmtTemp(s.tempC)})", "Gyzgynlyk adaty (${fmtTemp(s.tempC)})") to AccentGreen)
                    }
                    if (s.battPct in 0..20 && !s.charging) out.add(lx(tm, "Pil düşük (%${s.battPct}). Şarja takman önerilir.", "Batareýa az (%${s.battPct}). Zarýada goşuň.") to Warn)
                } finally {
                    report = out
                    boosting = false
                    step = ""
                }
                if (prefs.getBoolean(IntroPrefs.HAPTIC, true)) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                if (prefs.getBoolean(IntroPrefs.SOUND, true)) BoosterSound.playChime()
            }
        }
    }
    Column(
        modifier.background(Brush.verticalGradient(listOf(Color(0xFF1A0E40), BgDark, BgDark))).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NeonButton(text = "‹", modifier = Modifier.size(44.dp), onClick = { feedback(FeedbackType.TAP); onClose() }, secondary = true)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SUFFER SHADOW", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                Text("GAME BOOSTER", color = profile.color, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
            }
            NeonButton(text = "✦", modifier = Modifier.size(44.dp), onClick = { feedback(FeedbackType.TAP); showIntroSettings = true }, secondary = true)
        }
        Spacer(Modifier.height(12.dp))
        StatusPill(
            text = (if (BoosterState.enabled) lx(tm, "AKTİF", "IŞJEŇ") else lx(tm, "PASİF", "PASSIW")) + " • " + profile.name + (if (BoosterState.gameRunning) " • " + lx(tm, "PUBG algılandı", "PUBG tapyldy") else ""),
            color = if (BoosterState.enabled) AccentGreen else TextMuted
        )
        PanelCore(profile, boosting, lite, Modifier.size(230.dp))
        Text(if (BoosterState.pingAvg >= 0) "Ping ${BoosterState.pingAvg} ms" else lx(tm, "Hazır", "Taýýar"), color = TextMuted, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        val btnShape = RoundedCornerShape(20.dp)
        AnimatedSurface(
            modifier = Modifier.fillMaxWidth().height(62.dp).border(1.5.dp, Brush.horizontalGradient(listOf(profile.color, PurpleLight, profile.color)), btnShape),
            shape = btnShape, brush = Brush.horizontalGradient(listOf(PurpleDeep, profile.color.copy(alpha = 0.85f), PurpleDeep)), onClick = runBoost, enabled = !boosting
        ) { Text(if (boosting) step else "BOOST ET", color = Color.White, fontSize = if (boosting) 15.sp else 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp) }
        
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            BoostProfile.values().forEach { p ->
                ProfileChip(Modifier.weight(1f), p, p == profile) { feedback(FeedbackType.TAP); applyBoostProfile(prefs, p) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(profile.desc(tm), color = TextMuted, fontSize = 11.sp, lineHeight = 15.sp, textAlign = TextAlign.Center)

        if (report.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            GameCard {
                Text(lx(tm, "BOOST SONUCU", "BOOST NETIJESI"), color = PurpleLight, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                report.forEach { (line, col) ->
                    Row(Modifier.padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(col))
                        Spacer(Modifier.width(9.dp))
                        Text(line, color = Color.White, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        val cpu = BoosterState.cpuPct
        val connected = VpnState.status == VpnState.CONNECTED
        val tempCol = when { stats.tempC >= 42f -> RedSoft; stats.tempC >= 38f -> Warn; else -> AccentGreen }
        
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(Modifier.weight(1f), "CPU", if (cpu >= 0) "%$cpu" else if (BoosterState.serviceRunning) lx(tm, "Kısıtlı", "Çäkli") else "—", if (cpu >= 0) lx(tm, "kullanım", "ulanyş") else lx(tm, "sistem izin vermez", "ulgam rugsat bermeýär"), if (cpu >= 0) cpu / 100f else null, PurpleLight)
            StatTile(Modifier.weight(1f), "RAM", if (stats.ramPct >= 0) "%${stats.ramPct}" else "—", if (stats.ramFreeMb >= 0) lx(tm, "boş ${stats.ramFreeMb} MB", "boş ${stats.ramFreeMb} MB") else "—", if (stats.ramPct >= 0) stats.ramPct / 100f else null, if (stats.ramPct >= 85) Warn else PurpleLight)
            StatTile(Modifier.weight(1f), lx(tm, "SICAKLIK", "GYZGYNLYK"), if (stats.tempC >= 0f) fmtTemp(stats.tempC) else "—", when { stats.tempC < 0f -> "—"; stats.tempC >= 42f -> lx(tm, "sıcak", "gyzgyn"); stats.tempC >= 38f -> lx(tm, "ılık", "ýyly"); else -> lx(tm, "normal", "adaty") }, if (stats.tempC >= 0f) (stats.tempC / 50f) else null, tempCol)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(Modifier.weight(1f), lx(tm, "PİL", "BATAREÝA"), if (stats.battPct >= 0) "%${stats.battPct}" else "—", if (stats.charging) lx(tm, "şarj oluyor", "zarýad alýar") else lx(tm, "pil", "batareýa"), if (stats.battPct >= 0) stats.battPct / 100f else null, if (stats.battPct in 0..20 && !stats.charging) Warn else AccentGreen)
            StatTile(Modifier.weight(1f), "PING", if (BoosterState.pingAvg >= 0) "${BoosterState.pingAvg} ms" else "—", if (BoosterState.pingAvg >= 0) "±${BoosterState.jitterMs} • %${BoosterState.lossPct}" else if (BoosterState.serviceRunning) lx(tm, "sunucu yok", "serwer ýok") else lx(tm, "servis kapalı", "hyzmat ýapyk"), null, PurpleLight)
            StatTile(Modifier.weight(1f), lx(tm, "AĞ", "TOR"), if (connected && VpnState.speedSupported) "↓ " + fmtRate(VpnState.rxBps) else "—", if (connected && VpnState.speedSupported) "↑ " + fmtRate(VpnState.txBps) else lx(tm, "VPN kapalı", "VPN ýapyk"), null, PurpleLight)
        }

        Spacer(Modifier.height(12.dp))
        AnimatedSurface(Modifier.fillMaxWidth(), color = CardBg, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(horizontal = 14.dp)) {
                PreferenceSwitchRow(
                    title = lx(tm, "Game Booster servisi", "Game Booster hyzmaty"), subtitle = lx(tm, "Kapatınca yalnızca oyun modu ve panel kapanır; VPN kesilmez.", "Ýapanyňyzda diňe oýun modi we panel ýapylýar; VPN üzülmeýär."),
                    checked = BoosterState.enabled,
                    onCheckedChange = { on ->
                        feedback(FeedbackType.TAP); BoosterState.enabled = on; prefs.edit().putBoolean(BoosterPrefs.ENABLED, on).apply()
                        if (on) startService(null) else stopBoosterService(context)
                    }
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FeatureCard(Modifier.weight(1f), "▶", lx(tm, "PUBG Başlat", "PUBG aç")) {
                feedback(FeedbackType.TAP)
                when (launchPubg(context)) {
                    0 -> if (BoosterState.enabled && !BoosterState.serviceRunning) startService(null)
                    1 -> toast(lx(tm, "PUBG Mobile kurulu değil.", "PUBG Mobile gurlmadyk."))
                    else -> toast(lx(tm, "PUBG başlatılamadı.", "PUBG açyp bolmady."))
                }
            }
            FeatureCard(Modifier.weight(1f), "◧", lx(tm, "Kayan Panel", "Panel")) {
                feedback(FeedbackType.TAP)
                when {
                    !BoosterState.enabled -> toast(lx(tm, "Önce BOOST ET'e bas veya servisi aç.", "Ilki BOOST ET basyň ýa-da hyzmaty açyň."))
                    !canDrawOverlay(context) -> openSettingsScreen(context, "overlay")
                    BoosterState.overlayShown -> startService(GameBoosterService.ACTION_HIDE_PANEL)
                    else -> startService(GameBoosterService.ACTION_SHOW_PANEL)
                }
            }
            FeatureCard(Modifier.weight(1f), "☰", lx(tm, "Sunucular", "Serwerler")) {
                feedback(FeedbackType.TAP); NavRequest.tab = 0; NavRequest.servers = true; NavRequest.seq++; onClose()
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FeatureCard(Modifier.weight(1f), "✓", lx(tm, "İzinler", "Rugsatlar")) { feedback(FeedbackType.TAP); showPerms = true }
            FeatureCard(Modifier.weight(1f), "✦", lx(tm, "Açılış", "Açylyş")) { feedback(FeedbackType.TAP); showIntroSettings = true }
            FeatureCard(Modifier.weight(1f), "⚙", lx(tm, "Oyun Araçları", "Oýun gurallary")) { feedback(FeedbackType.TAP); onClose() }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            lx(tm, "BOOST ET; seçili profili uygular, Game Booster servisini başlatır, seçili sunucuya ağ testi yapar ve cihaz durumunu kontrol eder. Android, uygulamalara CPU/GPU hızını değiştirme veya diğer uygulamaları zorla kapatma izni vermez; bu ekran bunları taklit etmez.", "BOOST ET saýlanan profili ulanýar, hyzmaty işe goşýar, serwere tor synagyny geçirýär we enjamy barlaýar. Android programmalara CPU/GPU tizligini üýtgetmäge ýa-da beýleki programmalary mejbury ýapmaga rugsat bermeýär; bu ekran olary öýkünmeýär."),
            color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
    }

    if (showPerms) {
        Dialog(onDismissRequest = { showPerms = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(BgDark).verticalScroll(rememberScrollState()).padding(16.dp)) {
                NeonButton(text = lx(tm, "‹ Geri", "‹ Yza"), modifier = Modifier.fillMaxWidth(), onClick = { showPerms = false }, secondary = true)
                Spacer(Modifier.height(12.dp))
                BoosterHeroCard(lang = lang, prefs = prefs, feedback = feedback)
            }
        }
    }

    if (showIntroSettings) {
        IntroSettingsDialog(tm = tm, prefs = prefs, feedback = feedback, onReplay = { showIntroSettings = false; onReplayIntro() }, onDismiss = { showIntroSettings = false })
    }
}

private fun fmtTemp(c: Float): String = String.format(Locale.US, "%.1f°C", c)

@Composable
private fun IntroSettingsDialog(tm: Boolean, prefs: SharedPreferences, feedback: (FeedbackType) -> Unit, onReplay: () -> Unit, onDismiss: () -> Unit) {
    var sound by remember { mutableStateOf(prefs.getBoolean(IntroPrefs.SOUND, true)) }
    var haptic by remember { mutableStateOf(prefs.getBoolean(IntroPrefs.HAPTIC, true)) }
    var quality by remember { mutableStateOf(prefs.getString(IntroPrefs.QUALITY, "auto") ?: "auto") }
    val setQ: (String) -> Unit = { q -> feedback(FeedbackType.TAP); quality = q; prefs.edit().putString(IntroPrefs.QUALITY, q).apply() }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = CardBg, titleContentColor = Color.White, textContentColor = TextMuted,
        title = { Text(lx(tm, "Açılış ve efektler", "Açylyş we effektler"), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                PreferenceSwitchRow(title = lx(tm, "Sesler", "Sesler"), subtitle = lx(tm, "Açılış ve BOOST sesleri", "Açylyş we BOOST sesleri"), checked = sound, onCheckedChange = { feedback(FeedbackType.TAP); sound = it; prefs.edit().putBoolean(IntroPrefs.SOUND, it).apply() })
                PreferenceSwitchRow(title = lx(tm, "Titreşim", "Titreme"), subtitle = lx(tm, "Çekirdek açılınca ve bitişte", "Özen açylanda we soňunda"), checked = haptic, onCheckedChange = { feedback(FeedbackType.TAP); haptic = it; prefs.edit().putBoolean(IntroPrefs.HAPTIC, it).apply() })
                Spacer(Modifier.height(6.dp))
                Text(lx(tm, "Animasyon kalitesi", "Animasiýa hili"), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeonButton(lx(tm, "Otomatik", "Awtomatik"), Modifier.weight(1f), onClick = { setQ("auto") }, secondary = quality != "auto")
                    NeonButton(lx(tm, "Tam", "Doly"), Modifier.weight(1f), onClick = { setQ("full") }, secondary = quality != "full")
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeonButton(lx(tm, "Hafif", "Ýeňil"), Modifier.weight(1f), onClick = { setQ("lite") }, secondary = quality != "lite")
                    NeonButton(lx(tm, "Kapalı", "Ýapyk"), Modifier.weight(1f), onClick = { setQ("off") }, secondary = quality != "off")
                }
            }
        },
        confirmButton = { TextButton(onClick = { feedback(FeedbackType.TAP); onReplay() }) { Text(lx(tm, "Önizle", "Synap gör"), color = PurpleLight, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(lx(tm, "Kapat", "Ýap"), color = TextMuted) } }
    )
}

@Composable
internal fun BoosterEntryCard(lang: String, prefs: SharedPreferences, onOpen: () -> Unit) {
    val tm = lang == "tm"
    val profile = BoostProfileHolder.get(prefs)
    val shape = RoundedCornerShape(24.dp)
    AnimatedSurface(
        modifier = Modifier.fillMaxWidth().border(1.5.dp, Brush.linearGradient(listOf(PurpleLight, PurpleMain, profile.color)), shape),
        shape = shape, brush = Brush.verticalGradient(listOf(Color(0xFF2A1260), Color(0xFF160C36), Color(0xFF0E1124))), onClick = onOpen, contentAlignment = Alignment.TopStart
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            PanelCore(profile, false, true, Modifier.size(84.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("GAME BOOSTER", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(2.dp))
                Text((if (BoosterState.enabled) lx(tm, "Aktif", "Işjeň") else lx(tm, "Pasif", "Passiw")) + " • " + profile.name, color = if (BoosterState.enabled) AccentGreen else TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(lx(tm, "Açmak için dokun", "Açmak üçin basyň"), color = PurpleLight, fontSize = 11.sp)
            }
            Text("›", color = PurpleLight, fontSize = 34.sp, fontWeight = FontWeight.Light)
        }
    }
}

