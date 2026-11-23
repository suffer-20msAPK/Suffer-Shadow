package com.suffershadow.vpn

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.net.URLDecoder
import kotlin.concurrent.thread

object VpnState {
    const val IDLE = 0
    const val CONNECTING = 1
    const val CONNECTED = 2
    const val ERROR = 3

    const val ST_START = 1
    const val ST_SERVER = 2
    const val ST_VERIFY = 3
    const val ST_DONE = 4
    const val ST_FAILED = 5

    var status by mutableStateOf(IDLE)
    var stage by mutableStateOf(0)
    var delayMs by mutableStateOf(-1)
    var error by mutableStateOf("")
    var failCode by mutableStateOf(0)
    var logLines by mutableStateOf("")
}

object XrayConfig {
    private fun params(query: String): Map<String, String> {
        val m = HashMap<String, String>()
        query.split("&").forEach {
            if (it.contains("=")) {
                m[it.substringBefore("=")] = URLDecoder.decode(it.substringAfter("="), "UTF-8")
            }
        }
        return m
    }

    private fun b64(s: String): String {
        var t = s.replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        return String(Base64.decode(t, Base64.DEFAULT))
    }

    fun build(link: String, logPath: String): String {
        val body = link.trim().substringBefore("#")
        val outbound = when {
            body.startsWith("vless://") -> vless(body)
            body.startsWith("ss://") -> shadowsocks(body)
            else -> throw IllegalArgumentException("Desteklenmeyen link")
        }

        val root = JSONObject()
        root.put(
            "log",
            JSONObject().put("loglevel", "info").put("access", "none").put("error", logPath)
        )

        val tun = JSONObject()
        tun.put("tag", "tun-in")
        tun.put("protocol", "tun")
        tun.put("settings", JSONObject().put("name", "xray0").put("mtu", 1500))
        tun.put(
            "sniffing",
            JSONObject().put("enabled", true)
                .put("destOverride", JSONArray().put("http").put("tls"))
        )

        val socks = JSONObject()
        socks.put("tag", "socks-in")
        socks.put("listen", "127.0.0.1")
        socks.put("port", 10808)
        socks.put("protocol", "socks")
        socks.put("settings", JSONObject().put("auth", "noauth").put("udp", true))

        root.put("inbounds", JSONArray().put(tun).put(socks))

        val direct = JSONObject().put("tag", "direct").put("protocol", "freedom")
        root.put("outbounds", JSONArray().put(outbound).put(direct))
        return root.toString()
    }

    private fun vless(body: String): JSONObject {
        val rest = body.removePrefix("vless://")
        val id = rest.substringBefore("@")
        val afterAt = rest.substringAfter("@")
        val hostPort = afterAt.substringBefore("?").substringBefore("/")
        val query = if (afterAt.contains("?")) afterAt.substringAfter("?") else ""
        val host = hostPort.substringBeforeLast(":").removePrefix("[").removeSuffix("]")
        val port = hostPort.substringAfterLast(":").toInt()
        val p = params(query)

        val settings = JSONObject()
        settings.put("address", host)
        settings.put("port", port)
        settings.put("id", id)
        settings.put("encryption", p["encryption"] ?: "none")
        val flow = p["flow"] ?: ""
        if (flow.isNotEmpty()) settings.put("flow", flow)

        val out = JSONObject()
        out.put("tag", "proxy")
        out.put("protocol", "vless")
        out.put("settings", settings)
        val st = stream(p, host)
        if (st != null) out.put("streamSettings", st)
        return out
    }

    private fun stream(p: Map<String, String>, host: String): JSONObject? {
        val net = p["type"] ?: "tcp"
        val sec = p["security"] ?: "none"
        if (net == "tcp" && sec == "none") return null

        val s = JSONObject()
        s.put("network", net)
        s.put("security", sec)

        if (net == "ws") {
            val ws = JSONObject()
            ws.put("path", p["path"] ?: "/")
            val h = p["host"] ?: ""
            if (h.isNotEmpty()) ws.put("headers", JSONObject().put("Host", h))
            s.put("wsSettings", ws)
        }
        if (net == "grpc") {
            s.put("grpcSettings", JSONObject().put("serviceName", p["serviceName"] ?: ""))
        }
        if (net == "xhttp") {
            val x = JSONObject()
            x.put("path", p["path"] ?: "/")
            val h = p["host"] ?: ""
            if (h.isNotEmpty()) x.put("host", h)
            val mode = p["mode"] ?: ""
            if (mode.isNotEmpty()) x.put("mode", mode)
            s.put("xhttpSettings", x)
        }

        if (sec == "tls") {
            val t = JSONObject()
            t.put("serverName", p["sni"] ?: p["host"] ?: host)
            val fp = p["fp"] ?: ""
            if (fp.isNotEmpty()) t.put("fingerprint", fp)
            val alpn = p["alpn"] ?: ""
            if (alpn.isNotEmpty()) {
                val arr = JSONArray()
                alpn.split(",").forEach { arr.put(it) }
                t.put("alpn", arr)
            }
            s.put("tlsSettings", t)
        }
        if (sec == "reality") {
            val r = JSONObject()
            r.put("serverName", p["sni"] ?: host)
            r.put("fingerprint", p["fp"] ?: "chrome")
            r.put("publicKey", p["pbk"] ?: "")
            r.put("shortId", p["sid"] ?: "")
            r.put("spiderX", p["spx"] ?: "")
            s.put("realitySettings", r)
        }
        return s
    }

    private fun shadowsocks(body: String): JSONObject {
        val rest = body.removePrefix("ss://").substringBefore("?")
        val creds: String
        val hp: String
        if (rest.contains("@")) {
            val ui = rest.substringBeforeLast("@")
            creds = if (ui.contains(":")) URLDecoder.decode(ui, "UTF-8") else b64(ui)
            hp = rest.substringAfterLast("@")
        } else {
            val all = b64(rest.substringBefore("/"))
            creds = all.substringBeforeLast("@")
            hp = all.substringAfterLast("@")
        }
        val hostPort = hp.substringBefore("/")
        val settings = JSONObject()
        settings.put("address", hostPort.substringBeforeLast(":"))
        settings.put("port", hostPort.substringAfterLast(":").toInt())
        settings.put("method", creds.substringBefore(":"))
        settings.put("password", creds.substringAfter(":"))

        val out = JSONObject()
        out.put("tag", "proxy")
        out.put("protocol", "shadowsocks")
        out.put("settings", settings)
        return out
    }
}

class SufferVpnService : VpnService() {
    private var pfd: ParcelFileDescriptor? = null
    private var controller: CoreController? = null

    private val handler = object : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(code: Long, msg: String?): Long = 0
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cleanup()
            stopSelf()
            return START_NOT_STICKY
        }
        val link = intent?.getStringExtra(EXTRA_LINK)
        if (link == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        showNotification()
        VpnState.error = ""
        VpnState.delayMs = -1
        VpnState.failCode = 0
        VpnState.logLines = ""
        VpnState.stage = VpnState.ST_START
        VpnState.status = VpnState.CONNECTING
        thread { startVpn(link) }
        return START_NOT_STICKY
    }

    private fun startVpn(link: String) {
        try {
            val logFile = File(filesDir, "xray.log")
            try {
                logFile.delete()
            } catch (e: Exception) {
            }
            val config = XrayConfig.build(link, logFile.absolutePath)

            val b = Builder()
            b.setSession("Suffer Shadow")
            b.setMtu(1500)
            b.addAddress("10.10.10.2", 24)
            b.addRoute("0.0.0.0", 0)
            b.addDnsServer("1.1.1.1")
            b.addDnsServer("8.8.8.8")
            try {
                b.addDisallowedApplication(packageName)
            } catch (e: Exception) {
            }

            val p = b.establish() ?: throw IllegalStateException("VPN izni verilmedi")
            pfd = p

            Libv2ray.initCoreEnv(filesDir.absolutePath, "")
            val c = Libv2ray.newCoreController(handler)
            controller = c
            c.startLoop(config, p.fd)

            verify(link)
        } catch (e: Exception) {
            VpnState.error = e.message ?: "bilinmeyen hata"
            cleanup()
            VpnState.status = VpnState.ERROR
            stopSelf()
        }
    }

    private fun alive(): Boolean = controller != null

    private fun verify(link: String) {
        // 1) Sunucu portuna doğrudan TCP erişimi (uygulama VPN dışında, yani gerçek ağ yolu)
        val srv = parseServer(link)
        val tcp = if (srv != null) pingServer(srv) else -1
        if (!alive()) return
        if (tcp < 0) {
            fail(1)
            return
        }
        VpnState.stage = VpnState.ST_SERVER
        Thread.sleep(600)

        // 2) Tünel (Xray) üzerinden gerçek internet isteği
        VpnState.stage = VpnState.ST_VERIFY
        val ipMs = httpViaProxy("http://1.1.1.1/cdn-cgi/trace")
        if (!alive()) return
        if (ipMs < 0) {
            fail(2)
            return
        }
        var ms = httpViaProxy("http://connectivitycheck.gstatic.com/generate_204")
        if (!alive()) return
        if (ms < 0) {
            fail(3)
            return
        }
        val ms2 = httpViaProxy("http://connectivitycheck.gstatic.com/generate_204")
        if (ms2 >= 0) ms = ms2
        if (!alive()) return

        VpnState.delayMs = ms
        VpnState.stage = VpnState.ST_DONE
        VpnState.status = VpnState.CONNECTED
    }

    private fun httpViaProxy(url: String): Int {
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 10808))
            val t0 = System.nanoTime()
            val conn = URL(url).openConnection(proxy) as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..399) ((System.nanoTime() - t0) / 1_000_000).toInt() else -1
        } catch (e: Exception) {
            -1
        }
    }

    private fun fail(code: Int) {
        if (!alive()) return
        try {
            Thread.sleep(300)
        } catch (e: Exception) {
        }
        VpnState.failCode = code
        VpnState.logLines = logTail()
        VpnState.delayMs = -2
        VpnState.stage = VpnState.ST_FAILED
    }

    private fun logTail(): String {
        return try {
            val f = File(filesDir, "xray.log")
            if (!f.exists()) {
                ""
            } else {
                val keys = listOf("failed", "rror", "arning", "reject", "refused", "timeout")
                val lines = f.readLines().filter { l -> keys.any { k -> l.contains(k) } }
                lines.takeLast(4).joinToString("\n") { l ->
                    if (l.length > 170) l.takeLast(170) else l
                }
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun cleanup() {
        try {
            controller?.stopLoop()
        } catch (e: Exception) {
        }
        controller = null
        try {
            pfd?.close()
        } catch (e: Exception) {
        }
        pfd = null
        if (VpnState.status != VpnState.ERROR) {
            VpnState.status = VpnState.IDLE
        }
        VpnState.stage = 0
        VpnState.delayMs = -1
        VpnState.failCode = 0
        VpnState.logLines = ""
        try {
            stopForeground(true)
        } catch (e: Exception) {
        }
    }

    private fun showNotification() {
        try {
            val id = "suffer_vpn"
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(id, "VPN", NotificationManager.IMPORTANCE_LOW)
                )
            }
            val nb = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, id)
            else Notification.Builder(this)
            val n = nb.setContentTitle("Suffer Shadow")
                .setContentText("VPN")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .build()
            startForeground(1, n)
        } catch (e: Exception) {
        }
    }

    override fun onRevoke() {
        cleanup()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.suffershadow.vpn.STOP"
        const val EXTRA_LINK = "link"
    }
}

fun startVpnService(context: Context, s: Server?) {
    if (s == null) return
    val i = Intent(context, SufferVpnService::class.java)
    i.putExtra(SufferVpnService.EXTRA_LINK, s.link)
    if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
}

fun stopVpnService(context: Context) {
    val i = Intent(context, SufferVpnService::class.java)
    i.action = SufferVpnService.ACTION_STOP
    context.startService(i)
}

@Composable
fun rememberVpnToggle(getServer: () -> Server?): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode == Activity.RESULT_OK) startVpnService(context, getServer())
    }
    return {
        if (VpnState.status == VpnState.CONNECTING || VpnState.status == VpnState.CONNECTED) {
            stopVpnService(context)
        } else {
            val s = getServer()
            if (s != null) {
                val intent = VpnService.prepare(context)
                if (intent != null) launcher.launch(intent) else startVpnService(context, s)
            }
        }
    }
}

fun vpnStatusText(lang: String): String {
    val tm = lang == "tm"
    return when (VpnState.status) {
        VpnState.CONNECTING -> when (VpnState.stage) {
            VpnState.ST_SERVER ->
                if (tm) "Serwere birikdi" else "Sunucuya bağlandı"
            VpnState.ST_VERIFY ->
                if (tm) "Internet barlanýar..." else "İnternet erişimi doğrulanıyor..."
            VpnState.ST_FAILED -> {
                val reason = when (VpnState.failCode) {
                    1 -> if (tm) "Serwer portuna göni baglanyp bolmady (tor päsgelçiligi ýa-da ýalňyş link)"
                    else "Sunucu portuna doğrudan ulaşılamıyor (ağ engeli ya da hatalı link)"
                    2 -> if (tm) "Serwer porty açyk, emma tunel maglumat geçirmeýär (link möhleti ýa-da şifrleme?)"
                    else "Sunucu portu açık ama tünel veri geçirmiyor (link süresi ya da şifreleme?)"
                    else -> if (tm) "Internet bar, emma domen atlary çözülmeýär (DNS)"
                    else "İnternet var ama alan adları çözülemiyor (DNS sorunu)"
                }
                val head = (if (tm) "Birikme näsazlygy [" else "Bağlantı hatası [") +
                        VpnState.failCode + "]\n" + reason
                if (VpnState.logLines.isNotEmpty()) head + "\n\n" + VpnState.logLines else head
            }
            else -> if (tm) "Birigýär..." else "Bağlanıyor..."
        }
        VpnState.CONNECTED ->
            (if (tm) "Birikme üstünlikli • " else "Bağlantı başarılı • ") + VpnState.delayMs + " ms"
        VpnState.ERROR -> (if (tm) "Ýalňyşlyk: " else "Hata: ") + VpnState.error
        else -> if (tm) "Haçan taýynsyň?" else "Ne zaman hazırsın?"
    }
}
