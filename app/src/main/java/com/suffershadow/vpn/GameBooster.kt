package com.suffershadow.vpn

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

// =====================================================================
// PUBG paketleri (bölgesel sürümler). Yeni sürüm eklemek için listeye paket adı eklemek yeterli.
// AndroidManifest.xml içindeki <queries> bölümüne de aynı paket adı eklenmelidir (paket görünürlüğü).
// =====================================================================
val PUBG_PACKAGES = listOf(
    "com.tencent.ig",             // Global
    "com.pubg.krmobile",          // Kore
    "com.vng.pubgmobile",         // Vietnam
    "com.rekoo.pubgm",            // Tayvan
    "com.pubg.imobile",           // BGMI (Hindistan)
    "com.tencent.tmgp.pubgmhd",   // Game For Peace (Çin)
    "com.tencent.iglite"          // PUBG Mobile Lite
)

object BoosterPrefs {
    const val ENABLED = "gb_enabled"
    const val AUTO_OVERLAY = "gb_auto_overlay"
    const val DND = "gb_dnd"
    const val REDUCE_ANIM = "gb_reduce_anim"
    const val HISTORY = "gb_history"
    const val DND_PREV = "gb_dnd_prev"
    const val BUBBLE_X = "gb_bubble_x"
    const val BUBBLE_Y = "gb_bubble_y"
}

/** Arayüzün ve kayan panelin okuduğu canlı durum (gerçek ölçümler; ölçülemeyen değer -1 kalır). */
object BoosterState {
    var enabled by mutableStateOf(false)
    var serviceRunning by mutableStateOf(false)
    var gameRunning by mutableStateOf(false)
    var gamePkg by mutableStateOf("")
    var launchedPkg by mutableStateOf("")
    var launchingUntil by mutableStateOf(0L)
    var sessionStartMs by mutableStateOf(0L)
    var reduceAnim by mutableStateOf(false)
    var dndActive by mutableStateOf(false)
    var overlayShown by mutableStateOf(false)

    var pingAvg by mutableStateOf(-1)
    var jitterMs by mutableStateOf(-1)
    var lossPct by mutableStateOf(-1)

    var ramUsedPct by mutableStateOf(-1)
    var ramFreeMb by mutableStateOf(-1)
    var cpuPct by mutableStateOf(-1)
    var battTempC by mutableStateOf(-1f)
    var battPct by mutableStateOf(-1)
    var thermalStatus by mutableStateOf(-1)
    var thermalWarn by mutableStateOf(false)

    fun load(prefs: SharedPreferences) {
        enabled = prefs.getBoolean(BoosterPrefs.ENABLED, false)
        reduceAnim = prefs.getBoolean(BoosterPrefs.REDUCE_ANIM, false)
    }
}

// ---------------------------------------------------------------------
// İzinler ve yardımcılar
// ---------------------------------------------------------------------
fun hasUsageAccess(context: Context): Boolean {
    return try {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }
}

fun canDrawOverlay(context: Context): Boolean =
    try { Settings.canDrawOverlays(context) } catch (e: Exception) { false }

fun hasDndAccess(context: Context): Boolean =
    try {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted
    } catch (e: Exception) {
        false
    }

/** kind: "usage" | "overlay" | "dnd" */
fun openSettingsScreen(context: Context, kind: String) {
    val intent = when (kind) {
        "usage" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
        "dnd" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        else -> Intent(Settings.ACTION_SETTINGS)
    }
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        DebugLog.add(context, "settings($kind): " + e.message)
    }
}

fun findInstalledPubg(context: Context): String? {
    val pm = context.packageManager
    return PUBG_PACKAGES.firstOrNull { pm.getLaunchIntentForPackage(it) != null }
}

/** 0 = başlatıldı, 1 = PUBG kurulu değil, 2 = başlatılamadı */
fun launchPubg(context: Context): Int {
    val pkg = findInstalledPubg(context) ?: return 1
    return try {
        val i = context.packageManager.getLaunchIntentForPackage(pkg) ?: return 1
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        BoosterState.launchedPkg = pkg
        BoosterState.launchingUntil = System.currentTimeMillis() + 20000L
        0
    } catch (e: Exception) {
        DebugLog.add(context, "PUBG start: " + e.message)
        2
    }
}

fun startBoosterService(context: Context, action: String? = null) {
    val i = Intent(context, GameBoosterService::class.java)
    if (action != null) i.action = action
    androidx.core.content.ContextCompat.startForegroundService(context, i)
}

fun stopBoosterService(context: Context) {
    context.stopService(Intent(context, GameBoosterService::class.java))
}

fun fmtRate(bps: Long): String {
    val kb = bps / 1024.0
    return if (kb >= 1024.0) String.format(Locale.US, "%.1f MB/s", kb / 1024.0)
    else String.format(Locale.US, "%d KB/s", kb.toLong())
}

fun fmtClock(totalSec: Long): String {
    val t = if (totalSec < 0L) 0L else totalSec
    return String.format(Locale.US, "%02d:%02d:%02d", t / 3600, (t % 3600) / 60, t % 60)
}

// ---------------------------------------------------------------------
// Oyun geçmişi ve hata ayıklama günlüğü
// ---------------------------------------------------------------------
data class GameSessionRecord(val startMs: Long, val durationMs: Long, val avgPing: Int, val lossPct: Int, val server: String)

object GameHistory {
    fun add(prefs: SharedPreferences, r: GameSessionRecord) {
        val old = try { JSONArray(prefs.getString(BoosterPrefs.HISTORY, "[]")) } catch (e: Exception) { JSONArray() }
        val out = JSONArray()
        out.put(JSONObject().put("s", r.startMs).put("d", r.durationMs).put("p", r.avgPing).put("l", r.lossPct).put("n", r.server))
        for (i in 0 until minOf(old.length(), 29)) out.put(old.get(i))
        prefs.edit().putString(BoosterPrefs.HISTORY, out.toString()).apply()
    }

    fun read(prefs: SharedPreferences): List<GameSessionRecord> {
        val list = ArrayList<GameSessionRecord>()
        try {
            val arr = JSONArray(prefs.getString(BoosterPrefs.HISTORY, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(GameSessionRecord(o.optLong("s"), o.optLong("d"), o.optInt("p", -1), o.optInt("l", 0), o.optString("n", "")))
            }
        } catch (e: Exception) {
        }
        return list
    }
}

/** Teknik hatalar ana ekranda değil, bu günlükte saklanır (Oyun sekmesi > Hata ayıklama). */
object DebugLog {
    private val lock = Any()

    fun add(context: Context, line: String) {
        synchronized(lock) {
            try {
                val f = File(context.filesDir, "debug.log")
                val ts = java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(java.util.Date())
                f.appendText(ts + "  " + line.replace('\n', ' ') + "\n")
                if (f.length() > 100_000L) {
                    f.writeText(f.readLines().takeLast(300).joinToString("\n") + "\n")
                }
            } catch (e: Exception) {
            }
        }
    }

    fun read(context: Context): String = synchronized(lock) {
        try {
            val f = File(context.filesDir, "debug.log")
            if (f.exists()) f.readLines().takeLast(60).joinToString("\n") else ""
        } catch (e: Exception) {
            ""
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            try {
                File(context.filesDir, "debug.log").delete()
            } catch (e: Exception) {
            }
        }
    }
}

// ---------------------------------------------------------------------
// Game Booster servisi
// ---------------------------------------------------------------------
class GameBoosterService : Service() {
    companion object {
        const val ACTION_SHOW_PANEL = "com.suffershadow.vpn.SHOW_PANEL"
        const val ACTION_HIDE_PANEL = "com.suffershadow.vpn.HIDE_PANEL"
        const val CHANNEL_ID = "suffer_booster"
        const val NOTIF_ID = 3
        const val NOTIF_HOT_ID = 4
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var overlay: OverlayController? = null
    private lateinit var prefs: SharedPreferences

    private val pingSamples = ArrayList<Int>()
    private var fgPkg: String? = null
    private var lastQuery = 0L
    private var inGame = false
    private var dndChanged = false
    private var sSum = 0L
    private var sN = 0
    private var sLost = 0
    private var sTotal = 0
    private var lastThermalNotify = 0L
    private var lastCpu: LongArray? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("suffer", Context.MODE_PRIVATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground(if (BoosterState.gameRunning) "Oyun modu aktif" else "PUBG bekleniyor")
        BoosterState.serviceRunning = true
        if (!running) {
            running = true
            startWorker()
        }
        when (intent?.action) {
            ACTION_SHOW_PANEL -> main.post { showOverlay() }
            ACTION_HIDE_PANEL -> main.post { overlay?.hide() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        if (inGame) onGameEnd()
        restoreDnd()
        try {
            overlay?.hide()
        } catch (e: Exception) {
        }
        overlay = null
        BoosterState.serviceRunning = false
        BoosterState.gameRunning = false
        BoosterState.dndActive = false
        BoosterState.overlayShown = false
        try {
            stopForeground(true)
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    // ---- bildirim ----
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(CHANNEL_ID, "Game Booster", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun builder(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)

    private fun openAppPending(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("tab", 1)
        return PendingIntent.getActivity(this, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun startAsForeground(text: String) {
        try {
            ensureChannel()
            startForeground(NOTIF_ID, buildStatusNotification(text))
        } catch (e: Exception) {
            DebugLog.add(this, "booster fg: " + e.message)
        }
    }

    private fun buildStatusNotification(text: String): Notification =
        builder()
            .setContentTitle("GAME BOOSTER")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentIntent(openAppPending())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateNotification(text: String) {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildStatusNotification(text))
        } catch (e: Exception) {
        }
    }

    private fun notifyHot(temp: Float) {
        try {
            val t = if (temp >= 0f) String.format(Locale.US, " (%.1f °C)", temp) else ""
            val n = builder()
                .setContentTitle("Cihaz ısınıyor$t")
                .setContentText("Oyuna kısa bir ara vermen önerilir.")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setAutoCancel(true)
                .build()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_HOT_ID, n)
        } catch (e: Exception) {
        }
    }

    // ---- ana döngü ----
    private fun startWorker() {
        thread(name = "booster-worker") {
            while (running) {
                try {
                    tickSystem()
                    tickGame()
                    tickPing()
                } catch (e: Exception) {
                    DebugLog.add(this, "booster loop: " + e.message)
                }
                try {
                    Thread.sleep(2000L)
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun tickSystem() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        if (mi.totalMem > 0L) {
            BoosterState.ramUsedPct = (((mi.totalMem - mi.availMem) * 100L) / mi.totalMem).toInt()
            BoosterState.ramFreeMb = (mi.availMem / (1024L * 1024L)).toInt()
        }
        BoosterState.cpuPct = readCpuPct()

        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (b != null) {
            val t = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            BoosterState.battTempC = if (t >= 0) t / 10f else -1f
            val lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            BoosterState.battPct = if (lvl >= 0 && sc > 0) lvl * 100 / sc else -1
        }
        var thermal = -1
        if (Build.VERSION.SDK_INT >= 29) {
            thermal = (getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
        }
        BoosterState.thermalStatus = thermal
        val hot = BoosterState.battTempC >= 43f || thermal >= 3 // 3 = THERMAL_STATUS_SEVERE
        BoosterState.thermalWarn = hot
        val now = System.currentTimeMillis()
        if (hot && now - lastThermalNotify > 300_000L) {
            lastThermalNotify = now
            notifyHot(BoosterState.battTempC)
        }
    }

    /** Android 8+ uygulamalara /proc/stat erişimi vermez; okunamazsa -1 (erişilemiyor) döner. */
    private fun readCpuPct(): Int {
        return try {
            val line = File("/proc/stat").bufferedReader().use { it.readLine() } ?: return -1
            val p = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (p.size < 5) return -1
            val idle = p[3] + p[4]
            val total = p.sum()
            val prev = lastCpu
            lastCpu = longArrayOf(idle, total)
            if (prev == null) return -1
            val dt = total - prev[1]
            val di = idle - prev[0]
            if (dt <= 0L) -1 else (((dt - di) * 100L) / dt).toInt().coerceIn(0, 100)
        } catch (e: Exception) {
            -1
        }
    }

    private fun pollForeground() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val from = if (lastQuery == 0L) now - 60_000L else lastQuery - 1000L
        val events = usm.queryEvents(from, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                fgPkg = e.packageName
            } else if (e.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND && e.packageName == fgPkg) {
                fgPkg = null
            }
        }
        lastQuery = now
    }

    private fun tickGame() {
        if (!hasUsageAccess(this)) return
        pollForeground()
        val pkg = fgPkg ?: return
        // Kendi uygulamamız ve sistem arayüzü nötr: oyun durumunu değiştirmez
        if (pkg == packageName || pkg == "com.android.systemui") return
        val isGame = PUBG_PACKAGES.contains(pkg)
        if (isGame && !inGame) onGameStart(pkg) else if (!isGame && inGame) onGameEnd()
    }

    private fun onGameStart(pkg: String) {
        inGame = true
        sSum = 0L; sN = 0; sLost = 0; sTotal = 0
        BoosterState.gamePkg = pkg
        BoosterState.sessionStartMs = System.currentTimeMillis()
        BoosterState.gameRunning = true
        BoosterState.launchingUntil = 0L
        if (prefs.getBoolean(BoosterPrefs.DND, false)) enableDnd()
        if (prefs.getBoolean(BoosterPrefs.AUTO_OVERLAY, true) && canDrawOverlay(this)) main.post { showOverlay() }
        updateNotification("Oyun modu aktif • PUBG çalışıyor")
    }

    private fun onGameEnd() {
        inGame = false
        val now = System.currentTimeMillis()
        val dur = now - BoosterState.sessionStartMs
        if (dur >= 15_000L) {
            GameHistory.add(
                prefs,
                GameSessionRecord(
                    BoosterState.sessionStartMs, dur,
                    if (sN > 0) (sSum / sN).toInt() else -1,
                    if (sTotal > 0) sLost * 100 / sTotal else 0,
                    prefs.getString("active_name", "") ?: ""
                )
            )
        }
        BoosterState.gameRunning = false
        BoosterState.gamePkg = ""
        restoreDnd()
        main.post { overlay?.hide() }
        updateNotification("PUBG bekleniyor")
    }

    private fun tickPing() {
        val host = prefs.getString("active_host", "") ?: ""
        val port = prefs.getInt("active_port", 0)
        if (host.isEmpty() || port <= 0) {
            BoosterState.pingAvg = -1
            BoosterState.jitterMs = -1
            BoosterState.lossPct = -1
            return
        }
        val ms = pingServer(Server("", host, host, port))
        synchronized(pingSamples) {
            pingSamples.add(ms)
            while (pingSamples.size > 60) pingSamples.removeAt(0)
        }
        if (inGame) {
            sTotal++
            if (ms < 0) sLost++ else {
                sSum += ms.toLong()
                sN++
            }
        }
        val win = synchronized(pingSamples) { pingSamples.takeLast(30) }
        val ok = win.filter { it >= 0 }
        BoosterState.pingAvg = if (ok.isEmpty()) -1 else ok.average().toInt()
        BoosterState.jitterMs = if (ok.size < 2) 0 else ok.zipWithNext { a, b -> abs(a - b) }.average().toInt()
        BoosterState.lossPct = (win.size - ok.size) * 100 / win.size
    }

    // ---- bildirimleri sessize alma (Rahatsız Etme) ----
    private fun enableDnd() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.isNotificationPolicyAccessGranted) return
            val prev = nm.currentInterruptionFilter
            // Kullanıcı zaten kendi DND modunu kullanıyorsa dokunma
            if (prev == NotificationManager.INTERRUPTION_FILTER_ALL) {
                prefs.edit().putInt(BoosterPrefs.DND_PREV, prev).apply()
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                dndChanged = true
                BoosterState.dndActive = true
            }
        } catch (e: Exception) {
            DebugLog.add(this, "dnd on: " + e.message)
        }
    }

    private fun restoreDnd() {
        if (!dndChanged) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(prefs.getInt(BoosterPrefs.DND_PREV, NotificationManager.INTERRUPTION_FILTER_ALL))
            }
        } catch (e: Exception) {
            DebugLog.add(this, "dnd off: " + e.message)
        }
        dndChanged = false
        BoosterState.dndActive = false
    }

    // ---- kayan panel ----
    private fun showOverlay() {
        if (!canDrawOverlay(this)) {
            android.widget.Toast.makeText(this, "Kayan panel için 'Diğer uygulamaların üzerinde göster' izni gerekli", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (overlay == null) {
            overlay = OverlayController(
                this, prefs,
                { synchronized(pingSamples) { ArrayList(pingSamples).takeLast(40) } },
                { tab -> openApp(tab) }
            )
        }
        try {
            overlay?.show()
        } catch (e: Exception) {
            DebugLog.add(this, "overlay: " + e.message)
        }
    }

    /** tab: 0 Ana Menü, 1 Oyun, 2 Profil; -1 = Ana Menü > sunucu listesi. */
    private fun openApp(tab: Int) {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra("tab", if (tab < 0) 0 else tab)
                    .putExtra("servers", tab < 0)
            )
        } catch (e: Exception) {
            DebugLog.add(this, "openApp: " + e.message)
        }
    }
}

// ---------------------------------------------------------------------
// Kayan düğme + iki yandan açılan panel (düz Android View'ları; Compose bağımlılığı yok)
// ---------------------------------------------------------------------
private class SparkView(ctx: Context) : View(ctx) {
    var data: List<Int> = emptyList()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB46BFF.toInt(); strokeWidth = 3f; style = Paint.Style.STROKE
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF7185.toInt() }

    override fun onDraw(c: Canvas) {
        val d = data
        if (d.size < 2) return
        val mx = maxOf(50, d.maxOrNull() ?: 50) * 1.15f
        val w = width.toFloat()
        val h = height.toFloat()
        val step = w / (d.size - 1)
        val path = android.graphics.Path()
        var started = false
        d.forEachIndexed { i, v ->
            val x = i * step
            if (v >= 0) {
                val y = h - (v / mx) * h
                if (!started) {
                    path.moveTo(x, y)
                    started = true
                } else {
                    path.lineTo(x, y)
                }
            } else {
                c.drawCircle(x, 4f, 3f, dot)
                started = false
            }
        }
        c.drawPath(path, line)
    }
}

private class OverlayController(
    private val ctx: Context,
    private val prefs: SharedPreferences,
    private val pingSnapshot: () -> List<Int>,
    private val openApp: (Int) -> Unit
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ctx.resources.displayMetrics.density
    private val ui = Handler(Looper.getMainLooper())

    private var bubble: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var panel: LinearLayout? = null
    private var panelOpen = false

    private var left: LinearLayout? = null
    private var right: LinearLayout? = null
    private var spark: SparkView? = null
    private var tvDetail: TextView? = null

    private var tvVpn: TextView? = null
    private var tvServer: TextView? = null
    private var tvPing: TextView? = null
    private var tvJitter: TextView? = null
    private var tvLoss: TextView? = null
    private var tvDown: TextView? = null
    private var tvUp: TextView? = null
    private var tvFps: TextView? = null
    private var tvRam: TextView? = null
    private var tvCpu: TextView? = null
    private var tvTemp: TextView? = null
    private var tvTimer: TextView? = null
    private var tvMode: TextView? = null

    private val tick = object : Runnable {
        override fun run() {
            updateTexts()
            ui.postDelayed(this, 1000L)
        }
    }

    private fun dp(v: Int): Int = (v * density).toInt()
    private val type: Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE
    private val flags: Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

    private fun animMs(): Long = if (BoosterState.reduceAnim) 0L else 240L

    private fun screenSize(): Pair<Int, Int> {
        val m = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(m)
        return Pair(m.widthPixels, m.heightPixels)
    }

    fun show() {
        if (bubble != null) return
        val size = dp(50)
        val tv = TextView(ctx).apply {
            text = "S"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            alpha = 0.92f
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFB46BFF.toInt(), 0xFF7B20FF.toInt(), 0xFF4210A8.toInt())
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), 0x99FFFFFF.toInt())
            }
        }
        val lp = WindowManager.LayoutParams(size, size, type, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(BoosterPrefs.BUBBLE_X, 0)
            y = prefs.getInt(BoosterPrefs.BUBBLE_Y, dp(120))
        }
        tv.setOnTouchListener(dragListener(lp, size))
        wm.addView(tv, lp)
        bubble = tv
        bubbleLp = lp
        BoosterState.overlayShown = true
        ui.post(tick)
    }

    fun hide() {
        ui.removeCallbacks(tick)
        removePanelNow()
        try {
            bubble?.let { wm.removeView(it) }
        } catch (e: Exception) {
        }
        bubble = null
        bubbleLp = null
        BoosterState.overlayShown = false
    }

    private fun dragListener(lp: WindowManager.LayoutParams, size: Int): View.OnTouchListener {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        return View.OnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        try {
                            wm.updateViewLayout(v, lp)
                        } catch (ex: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel() else snapToEdge(v, lp, size)
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge(v: View, lp: WindowManager.LayoutParams, size: Int) {
        val (sw, sh) = screenSize()
        val targetX = if (lp.x + size / 2 < sw / 2) 0 else sw - size
        lp.y = lp.y.coerceIn(0, maxOf(0, sh - size))
        val anim = ValueAnimator.ofInt(lp.x, targetX)
        anim.duration = animMs()
        anim.addUpdateListener {
            lp.x = it.animatedValue as Int
            try {
                wm.updateViewLayout(v, lp)
            } catch (ex: Exception) {
            }
        }
        anim.start()
        prefs.edit().putInt(BoosterPrefs.BUBBLE_X, targetX).putInt(BoosterPrefs.BUBBLE_Y, lp.y).apply()
    }

    private fun togglePanel() {
        if (panelOpen) closePanel() else openPanel()
    }

    // ---- panel ----
    private fun rounded(color: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (stroke > 0) setStroke(stroke, strokeColor)
        }

    private fun label(text: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }

    private fun card(title: String): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(0xF2151B33.toInt(), dp(14), dp(1), 0xFF50308C.toInt())
        setPadding(dp(10), dp(8), dp(10), dp(8))
        addView(label(title, 10f, 0xFFB46BFF.toInt(), true))
    }

    private fun row(card: LinearLayout): TextView {
        val tv = label("", 11f, 0xFFFFFFFF.toInt())
        tv.setPadding(0, dp(2), 0, dp(2))
        card.addView(tv)
        return tv
    }

    private fun button(text: String, onClick: () -> Unit): TextView =
        label(text, 11f, 0xFFFFFFFF.toInt(), true).apply {
            gravity = Gravity.CENTER
            background = rounded(0xFF302052.toInt(), dp(10))
            setPadding(dp(8), dp(7), dp(8), dp(7))
            setOnClickListener { onClick() }
        }

    private fun buildPanel(): LinearLayout {
        val l = card("VPN & AĞ")
        val r = card("SİSTEM")
        tvVpn = row(l); tvServer = row(l); tvPing = row(l); tvJitter = row(l)
        tvLoss = row(l); tvDown = row(l); tvUp = row(l)
        val sp = SparkView(ctx)
        sp.visibility = View.GONE
        l.addView(sp, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))
        spark = sp
        tvDetail = label("", 10f, 0xFFA7AEC8.toInt()).apply { visibility = View.GONE }
        l.addView(tvDetail)

        tvFps = row(r); tvRam = row(r); tvCpu = row(r); tvTemp = row(r); tvTimer = row(r); tvMode = row(r)

        val cols = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        cols.addView(l, LinearLayout.LayoutParams(dp(158), LinearLayout.LayoutParams.WRAP_CONTENT))
        val gap = View(ctx)
        cols.addView(gap, LinearLayout.LayoutParams(dp(6), 1))
        cols.addView(r, LinearLayout.LayoutParams(dp(158), LinearLayout.LayoutParams.WRAP_CONTENT))
        left = l
        right = r

        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val bw = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        bw.setMargins(dp(2), 0, dp(2), 0)
        bar.addView(button("Grafik") {
            val s = spark ?: return@button
            s.visibility = if (s.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }, bw)
        bar.addView(button("Sunucu") { openApp(-1) }, bw)
        bar.addView(button("VPN") {
            val d = tvDetail ?: return@button
            d.visibility = if (d.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            updateTexts()
        }, bw)
        bar.addView(button("Uygulama") { openApp(0) }, bw)
        bar.addView(button("Küçült") { closePanel() }, bw)

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(cols)
            val bp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            bp.topMargin = dp(6)
            addView(bar, bp)
        }
    }

    private fun openPanel() {
        if (panelOpen) return
        val root = buildPanel()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            type, flags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(16)
        }
        try {
            wm.addView(root, lp)
        } catch (e: Exception) {
            DebugLog.add(ctx, "panel add: " + e.message)
            return
        }
        panel = root
        panelOpen = true
        updateTexts()
        val d = animMs()
        val off = dp(180).toFloat()
        left?.let {
            it.translationX = -off
            it.alpha = 0f
            it.animate().translationX(0f).alpha(1f).setDuration(d).setInterpolator(DecelerateInterpolator()).start()
        }
        right?.let {
            it.translationX = off
            it.alpha = 0f
            it.animate().translationX(0f).alpha(1f).setDuration(d).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun closePanel() {
        if (!panelOpen) return
        panelOpen = false
        val d = animMs()
        val off = dp(180).toFloat()
        left?.animate()?.translationX(-off)?.alpha(0f)?.setDuration(d)?.start()
        val r = right
        if (r != null) {
            r.animate().translationX(off).alpha(0f).setDuration(d).withEndAction { removePanelNow() }.start()
        } else {
            removePanelNow()
        }
    }

    private fun removePanelNow() {
        panelOpen = false
        try {
            panel?.let { wm.removeView(it) }
        } catch (e: Exception) {
        }
        panel = null
    }

    private fun updateTexts() {
        if (!panelOpen) return
        val white = 0xFFFFFFFF.toInt()
        val red = 0xFFFF7185.toInt()
        val green = 0xFF00EFA8.toInt()
        val connected = VpnState.status == VpnState.CONNECTED
        val st = when (VpnState.status) {
            VpnState.CONNECTED -> "Bağlandı"
            VpnState.CONNECTING -> "Bağlanılıyor"
            else -> "Bağlantı kesildi"
        }
        tvVpn?.text = "VPN: $st"
        tvVpn?.setTextColor(if (connected) green else white)
        tvServer?.text = "Sunucu: " + (prefs.getString("active_name", "") ?: "").ifEmpty { "—" }
        tvPing?.text = "Ping: " + (if (BoosterState.pingAvg >= 0) "${BoosterState.pingAvg} ms" else "—")
        tvJitter?.text = "Jitter: " + (if (BoosterState.jitterMs >= 0) "${BoosterState.jitterMs} ms" else "—")
        tvLoss?.text = "Paket kaybı: " + (if (BoosterState.lossPct >= 0) "%${BoosterState.lossPct}" else "—")
        tvDown?.text = "↓ " + (if (connected) fmtRate(VpnState.rxBps) else "—")
        tvUp?.text = "↑ " + (if (connected) fmtRate(VpnState.txBps) else "—")

        tvFps?.text = "FPS: erişilemiyor"
        tvRam?.text = "RAM: " + (if (BoosterState.ramUsedPct >= 0) "%${BoosterState.ramUsedPct} • boş ${BoosterState.ramFreeMb} MB" else "—")
        tvCpu?.text = "CPU: " + (if (BoosterState.cpuPct >= 0) "%${BoosterState.cpuPct}" else "erişilemiyor")
        val temp = if (BoosterState.battTempC >= 0f) String.format(Locale.US, "%.1f°C", BoosterState.battTempC) else "—"
        tvTemp?.text = "Isı: $temp" + (if (BoosterState.battPct >= 0) " • Pil %${BoosterState.battPct}" else "")
        tvTemp?.setTextColor(if (BoosterState.thermalWarn) red else white)

        val end = prefs.getLong("session_end", 0L)
        tvTimer?.text = "VPN süresi: " + (if (connected && end > 0L) fmtClock((end - System.currentTimeMillis()) / 1000L) else "—")
        tvMode?.text = "Oyun modu: " + (if (BoosterState.gameRunning) "Aktif" else "Beklemede")
        tvMode?.setTextColor(if (BoosterState.gameRunning) green else white)

        spark?.let {
            it.data = pingSnapshot()
            it.invalidate()
        }
        tvDetail?.let {
            if (it.visibility == View.VISIBLE) {
                val delay = if (VpnState.delayMs >= 0) "${VpnState.delayMs} ms" else "—"
                it.text = "Tünel gecikmesi: $delay\nToplam ↓ " + fmtRate(VpnState.rxTotal).replace("/s", "") + " ↑ " + fmtRate(VpnState.txTotal).replace("/s", "")
            }
        }
    }
}
