@file:Suppress("UnusedMaterial3ScaffoldPaddingParameter")

package com.suffershadow.vpn

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// -----------------------------------------------------------------------------
// SUFFER SHADOW — landscape GameSpace overlay
// UI ONLY: connect the callbacks and stats to the app's existing VPN/service code.
// Keep the existing overlay service, permissions and WindowManager implementation.
// -----------------------------------------------------------------------------

private val HudRed = Color(0xFFFF243D)
private val HudRedDark = Color(0xFF8F1022)
private val HudBlack = Color(0xE9080A10)
private val HudPanel = Color(0xE9161923)
private val HudMuted = Color(0xFF969EAD)
private val HudWhite = Color(0xFFF4F6FA)
private val HudGreen = Color(0xFF27E58D)
private val HudYellow = Color(0xFFFFC247)
private val HudBlue = Color(0xFF69C9FF)

/**
 * UI model. Feed this with real values from the VPN service and Android APIs.
 * Use null for values that the device/app cannot read; the UI will show "—".
 */
data class GameOverlayStats(
    val isVpnConnected: Boolean = false,
    val serverName: String = "Germany",
    val serverFlag: String = "🇩🇪",
    val pingMs: Int? = null,
    val jitterMs: Int? = null,
    val packetLossPercent: Float? = null,
    val downloadRate: String = "—",
    val uploadRate: String = "—",
    val fps: Int? = null,
    val ramUsagePercent: Int? = null,
    val freeRamMb: Int? = null,
    val cpuTemperatureC: Float? = null,
    val batteryPercent: Int? = null,
    val vpnDuration: String = "—",
    val gameMode: String = "Beklemede"
)

private data class PingStyle(
    val color: Color,
    val litBars: Int,
    val label: String
)

private fun pingStyle(pingMs: Int?): PingStyle = when {
    pingMs == null || pingMs <= 0 -> PingStyle(HudMuted, 0, "ÖLÇÜLMEDİ")
    pingMs <= 600 -> PingStyle(HudGreen, 4, "İYİ")
    pingMs <= 1200 -> PingStyle(HudYellow, 3, "ORTA")
    pingMs <= 3000 -> PingStyle(HudRed, 2, "YÜKSEK")
    else -> PingStyle(HudRedDark, 1, "ÇOK YÜKSEK")
}

@Composable
fun SufferShadowGameSpaceOverlay(
    stats: GameOverlayStats,
    modifier: Modifier = Modifier,
    onVpnClick: () -> Unit = {},
    onServerClick: () -> Unit = {},
    onBoosterClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onMinimize: () -> Unit = {},
    onClose: () -> Unit = {}
) {
    val pulse by rememberNeonPulse()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
              // Reference layout: left information rail + completely open game center + right rail.
        // It intentionally does NOT place one large card over the middle of the game.
        GameCenterNeonRails(
            modifier = Modifier.fillMaxSize(),
            pulse = pulse
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1.06f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                BrandPlate(pulse = pulse)
                NetworkPanel(
                    stats = stats,
                    pulse = pulse,
                    modifier = Modifier.weight(1f)
                )
                MiniTrafficPanel(
                    download = stats.downloadRate,
                    upload = stats.uploadRate
                )
            }

            // Transparent center: the live game remains visually dominant here.
            Box(
                modifier = Modifier
                    .weight(2.02f)
                    .fillMaxHeight()
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "SUFFER SHADOW",
                        color = HudWhite.copy(alpha = 0.92f),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 3.sp
                    )
                    Text(
                        text = "GAME SPACE",
                        color = HudRed,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 4.sp
                    )
                }

                // A compact floating status label, not a blocking center panel.
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(HudBlack.copy(alpha = 0.66f))
                        .border(1.dp, HudRed.copy(alpha = 0.48f), RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (stats.isVpnConnected) HudGreen else HudRed)
                    )
                    Text(
                        text = if (stats.isVpnConnected) "VPN KORUMASI AKTİF" else "VPN BAĞLANTISI KESİLDİ",
                        color = HudWhite,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.7.sp
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1.06f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SystemPanel(
                    stats = stats,
                    modifier = Modifier.weight(1f)
                )
                ControlPanel(
                    onVpnClick = onVpnClick,
                    onServerClick = onServerClick,
                    onBoosterClick = onBoosterClick,
                    onSettingsClick = onSettingsClick,
                    onMinimize = onMinimize,
                    onClose = onClose
                )
            }
        }
    }
}

@Composable
private fun rememberNeonPulse(): State<Float> {
    val transition = rememberInfiniteTransition(label = "sufferNeonPulse")
    return transition.animateFloat(
        initialValue = 0.48f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1350),
            repeatMode = RepeatMode.Reverse
        ),
        label = "neonPulse"
    )
}

@Composable
private fun GameCenterNeonRails(
    modifier: Modifier,
    pulse: Float
) {
    Canvas(modifier = modifier) {
        val leftTop = size.width * 0.285f
        val leftBottom = size.width * 0.215f
        val rightTop = size.width * 0.715f
        val rightBottom = size.width * 0.785f
        val top = size.height * 0.02f
        val bottom = size.height * 0.98f

        fun neonRail(x1: Float, x2: Float) {
            val start = Offset(x1, top)
            val end = Offset(x2, bottom)
            drawLine(
                color = HudRed.copy(alpha = 0.13f * pulse),
                start = start,
                end = end,
                strokeWidth = 13.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = HudRed.copy(alpha = 0.30f * pulse),
                start = start,
                end = end,
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color(0xFFFF7180).copy(alpha = 0.82f * pulse),
                start = start,
                end = end,
                strokeWidth = 1.3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        neonRail(leftTop, leftBottom)
        neonRail(rightTop, rightBottom)

        // Small segmented marks make the rails feel like a gaming HUD frame.
        for (i in 0..8) {
            val fraction = 0.12f + i * 0.095f
            val y = size.height * fraction
            val xLeft = leftTop + (leftBottom - leftTop) * fraction
            val xRight = rightTop + (rightBottom - rightTop) * fraction
            drawLine(
                color = HudRed.copy(alpha = 0.50f * pulse),
                start = Offset(xLeft - 4.dp.toPx(), y),
                end = Offset(xLeft + 5.dp.toPx(), y),
                strokeWidth = 2.dp.toPx()
            )
            drawLine(
                color = HudRed.copy(alpha = 0.50f * pulse),
                start = Offset(xRight - 5.dp.toPx(), y),
                end = Offset(xRight + 4.dp.toPx(), y),
                strokeWidth = 2.dp.toPx()
            )
        }
    }
}

@Composable
private fun BrandPlate(pulse: Float) {
    HudPanel(
        modifier = Modifier.fillMaxWidth(),
        pulse = pulse,
        contentPadding = 10.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        Brush.linearGradient(listOf(Color(0xFF5C0714), Color(0xFF16080D)))
                    )
                    .border(1.dp, HudRed.copy(alpha = 0.8f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("S", color = HudRed, fontSize = 24.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(9.dp))
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    "SUFFER SHADOW",
                    color = HudWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "PERFORMANCE HUD",
                    color = HudRed,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
            }
        }
    }
}
@Composable
private fun NetworkPanel(
    stats: GameOverlayStats,
    pulse: Float,
    modifier: Modifier = Modifier
) {
    HudPanel(
        modifier = modifier.fillMaxWidth(),
        pulse = pulse,
        contentPadding = 12.dp
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            PanelHeading(title = "VPN & AĞ", code = "01")
            StatusPill(
                text = if (stats.isVpnConnected) "BAĞLANTI KORUNUYOR" else "BAĞLANTI KESİLDİ",
                active = stats.isVpnConnected
            )
            ServerIdentity(flag = stats.serverFlag, server = stats.serverName)
            HudDivider()
            MetricLine("◉", "Ping", stats.pingMs?.let { "$it ms" } ?: "—")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⌁", color = HudRed, fontSize = 17.sp, modifier = Modifier.width(22.dp))
                Text("Anten", color = HudMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                PingBars(pingMs = stats.pingMs)
            }
            MetricLine("◌", "Jitter", stats.jitterMs?.let { "$it ms" } ?: "—")
            MetricLine("▤", "Paket kaybı", stats.packetLossPercent?.let { "%${formatNumber(it)}" } ?: "—")
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun SystemPanel(
    stats: GameOverlayStats,
    modifier: Modifier = Modifier
) {
    HudPanel(
        modifier = modifier.fillMaxWidth(),
        pulse = 0.75f,
        contentPadding = 12.dp
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PanelHeading(title = "SİSTEM", code = "02")
            SystemMetric("▥", "FPS", stats.fps?.let { "$it" } ?: "Erişilemiyor", HudGreen)
            SystemMetric(
                "▦", "RAM",
                if (stats.ramUsagePercent != null) {
                    "%${stats.ramUsagePercent}  •  boş ${stats.freeRamMb?.let { "$it MB" } ?: "—"}"
                } else "Erişilemiyor",
                HudBlue
            )
            SystemMetric("◈", "CPU", "Erişilemiyor", HudMuted)
            SystemMetric(
                "♨", "Isı",
                stats.cpuTemperatureC?.let { "${formatNumber(it)}°C" } ?: "Erişilemiyor",
                HudYellow
            )
            SystemMetric("▣", "Pil", stats.batteryPercent?.let { "%$it" } ?: "—", HudGreen)
            HudDivider()
            SystemMetric("◷", "VPN süresi", stats.vpnDuration, HudWhite)
            SystemMetric("⌘", "Oyun modu", stats.gameMode, HudRed)
            Spacer(Modifier.weight(1f))
            Text(
                "SİSTEM VERİLERİ CİHAZ İZİNLERİNE BAĞLIDIR",
                color = HudMuted,
                fontSize = 8.sp,
                lineHeight = 11.sp
            )
        }
    }
}

@Composable
private fun ControlPanel(
    onVpnClick: () -> Unit,
    onServerClick: () -> Unit,
    onBoosterClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit
) {
    HudPanel(
        modifier = Modifier.fillMaxWidth(),
        pulse = 0.7f,
        contentPadding = 10.dp
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelHeading(title = "HIZLI KONTROL", code = "03")
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                HudActionTile("⏻", "VPN", Modifier.weight(1f), onVpnClick)
                HudActionTile("▤", "SUNUCU", Modifier.weight(1f), onServerClick)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                HudActionTile("✦", "BOOST", Modifier.weight(1f), onBoosterClick)
                HudActionTile("⚙", "AYAR", Modifier.weight(1f), onSettingsClick)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                HudActionTile("⌄", "KÜÇÜLT", Modifier.weight(1f), onMinimize)
                HudActionTile("×", "KAPAT", Modifier.weight(1f), onClose, danger = true)
            }
        }
    }
}
@Composable
private fun HudActionTile(
    glyph: String,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    danger: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(100),
        label = "hudButtonScale"
    )
    val edge by animateColorAsState(
        targetValue = if (pressed || danger) HudRed else Color(0xFF5B2630),
        animationSpec = tween(120),
        label = "hudButtonEdge"
    )

    Column(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF29202A), Color(0xFF101219))
                )
            )
            .border(1.dp, edge.copy(alpha = if (pressed) 1f else 0.72f), RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 9.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            glyph,
            color = if (danger) HudRed else HudWhite,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            label,
            color = HudWhite,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MiniTrafficPanel(download: String, upload: String) {
    HudPanel(modifier = Modifier.fillMaxWidth(), pulse = 0.6f, contentPadding = 10.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TrafficMiniRow("↓", "İndir", download, HudGreen, Modifier.weight(1f))
            TrafficMiniRow("↑", "Yükle", upload, HudBlue, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ServerIdentity(flag: String, server: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF080B11).copy(alpha = 0.78f))
            .border(1.dp, HudRed.copy(alpha = 0.32f), RoundedCornerShape(12.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 37.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Color(0xFF242832)),
            contentAlignment = Alignment.Center
        ) {
            Text(flag, fontSize = 27.sp)
        }
        Spacer(Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("AKTİF SUNUCU", color = HudRed, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(server, color = HudWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("›", color = HudRed, fontSize = 27.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
private fun PanelHeading(title: String, code: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 3.dp, height = 17.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(HudRed)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            title,
            color = HudRed,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.4.sp,
            modifier = Modifier.weight(1f)
        )
        Text(code, color = HudMuted.copy(alpha = 0.65f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun StatusPill(text: String, active: Boolean) {
    val color = if (active) HudGreen else HudRed
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.42f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(color))
        Spacer(Modifier.width(7.dp))
        Text(text, color = color, fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp)
    }
}

@Composable
private fun MetricLine(glyph: String, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(glyph, color = HudRed, fontSize = 15.sp, modifier = Modifier.width(22.dp))
        Text(label, color = HudMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
        Text(
            value,
            color = HudWhite,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
@Composable
private fun SystemMetric(glyph: String, label: String, value: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(glyph, color = tint, fontSize = 15.sp, modifier = Modifier.width(22.dp))
        Text(label, color = HudMuted, fontSize = 10.sp, modifier = Modifier.weight(0.7f))
        Text(
            value,
            color = HudWhite,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.3f)
        )
    }
}

@Composable
private fun TrafficMiniRow(
    glyph: String,
    label: String,
    value: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(glyph, color = tint, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(5.dp))
            Text(label, color = HudMuted, fontSize = 9.sp)
        }
        Text(
            value,
            color = HudWhite,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PingBars(pingMs: Int?) {
    val style = pingStyle(pingMs)
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        for (index in 0 until 4) {
            val height = 5.dp + (index * 3).dp
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(height)
                    .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                    .background(
                        if (index < style.litBars) style.color else Color(0xFF3B414D)
                    )
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(style.label, color = style.color, fontSize = 7.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun HudDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Brush.horizontalGradient(listOf(Color.Transparent, HudRed.copy(alpha = 0.52f), Color.Transparent)))
    )
}

@Composable
private fun HudPanel(
    modifier: Modifier = Modifier,
    pulse: Float,
    contentPadding: Dp,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(17.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xEF171A23), Color(0xED090B11), Color(0xEE151017))
                )
            )
            .drawBehind {
                // Soft inner red glow. A subtle pulse keeps the frame alive without flashing.
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(HudRed.copy(alpha = 0.12f * pulse), Color.Transparent)
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(17.dp.toPx())
                )
            }
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(HudRed.copy(alpha = 0.92f * pulse), Color(0xFF54202A), Color(0xFF24232C))
                ),
                shape = RoundedCornerShape(17.dp)
            )
            .padding(contentPadding)
    ) {
        content()
    }
}

private fun formatNumber(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else "%.1f".format(value)
