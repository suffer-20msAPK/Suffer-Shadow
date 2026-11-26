package com.suffershadow.vpn

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.*

object BoosterPrefs {
    const val ENABLED = "booster_enabled"
    const val AUTO_OVERLAY = "booster_auto_overlay"
    const val DND = "booster_dnd"
    const val REDUCE_ANIM = "booster_reduce_anim"
    const val HISTORY = "booster_history"
}

object BoosterState {
    var enabled by mutableStateOf(false)
    var serviceRunning by mutableStateOf(false)
    var overlayShown by mutableStateOf(false)
    var gameRunning by mutableStateOf(false)
    var gamePkg by mutableStateOf("")
    var launchingUntil by mutableStateOf(0L)
    var pingAvg by mutableStateOf(-1)
    var jitterMs by mutableStateOf(-1)
    var lossPct by mutableStateOf(-1)
    var cpuPct by mutableStateOf(-1)
    var ramUsedPct by mutableStateOf(-1)
    var ramFreeMb by mutableStateOf(-1)
    var battPct by mutableStateOf(-1)
    var battTempC by mutableStateOf(-1f)
    var thermalWarn by mutableStateOf(false)
    var sessionStartMs by mutableStateOf(0L)
    var reduceAnim by mutableStateOf(false)

    fun load(prefs: SharedPreferences) {
        enabled = prefs.getBoolean(BoosterPrefs.ENABLED, false)
        reduceAnim = prefs.getBoolean(BoosterPrefs.REDUCE_ANIM, false)
    }
}

fun startBoosterService(context: Context, action: String? = null) {
    val intent = Intent(context, GameBoosterService::class.java).apply {
        if (action != null) this.action = action
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

fun stopBoosterService(context: Context) {
    context.stopService(Intent(context, GameBoosterService::class.java))
}

fun canDrawOverlay(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
}

class GameBoosterService : LifecycleService(), SavedStateRegistryOwner, ViewModelStoreOwner {
    
    companion object {
        const val ACTION_SHOW_PANEL = "com.suffershadow.vpn.SHOW_PANEL"
        const val ACTION_HIDE_PANEL = "com.suffershadow.vpn.HIDE_PANEL"
        const val CHANNEL_ID = "game_booster_channel"
        const val NOTIF_ID = 2
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var monitorJob: Job? = null
    
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()

    private val prefs by lazy { getSharedPreferences("suffer", Context.MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        BoosterState.serviceRunning = true
        BoosterState.enabled = true
        prefs.edit().putBoolean(BoosterPrefs.ENABLED, true).apply()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        startMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_SHOW_PANEL -> showOverlay()
            ACTION_HIDE_PANEL -> hideOverlay()
            else -> if (canDrawOverlay(this)) showOverlay()
        }
        return START_STICKY
    }

    private fun showOverlay() {
        if (overlayView != null || !canDrawOverlay(this)) return

        overlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@GameBoosterService)
            setViewTreeViewModelStoreOwner(this@GameBoosterService)
            setViewTreeSavedStateRegistryOwner(this@GameBoosterService)
            setContent {
                SufferShadowTheme {
                    // BoosterIntro.kt içindeki şık paneli çağırıyoruz
                    GameBoosterScreen(
                        lang = prefs.getString("lang", "tr") ?: "tr",
                        prefs = prefs,
                        feedback = { /* Haptic/Tone işlemleri */ },
                        onClose = { hideOverlay() }
                    )
                }
            }
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            windowManager.addView(overlayView, overlayParams)
            BoosterState.overlayShown = true
        } catch (e: Exception) {
            DebugLog.add(this, "Overlay error: ${e.message}")
        }
    }

    private fun hideOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {}
            overlayView = null
            BoosterState.overlayShown = false
        }
    }

    private fun startMonitoring() {
        monitorJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                // Cihaz durumunu oku
                // (Buraya CPU/RAM okuma mantığı eklenebilir, şimdilik basit geçiyoruz)
                delay(2000L)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Game Booster", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("SUFFER GAME BOOSTER")
            .setContentText("Oyun modu aktif. Kayan panel hazır.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
        monitorJob?.cancel()
        BoosterState.serviceRunning = false
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
