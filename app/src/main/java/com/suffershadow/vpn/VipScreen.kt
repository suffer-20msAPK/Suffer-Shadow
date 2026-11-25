@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.suffershadow.vpn

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private const val TELEGRAM_USERNAME = "suffer_pubg" // Telegram kullanıcı adın

@Composable
fun VipScreen(
    prefs: android.content.SharedPreferences,
    onVipActivated: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var vipEndDate by remember { mutableStateOf<String?>(null) }

    // Ekran açıldığında mevcut VIP durumunu kontrol et
    LaunchedEffect(Unit) {
        val (isVip, endDate) = RedeemManager.checkVip(context)
        if (isVip) {
            vipEndDate = endDate
            onVipActivated() // Zaten VIP ise doğrudan geç
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1A0E40), BgDark, BgDark)))
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))
        Text("VIP ÜYELİK", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        Text("Suffer Shadow ayrıcalıkları", color = PurpleLight, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        
        Spacer(Modifier.height(30.dp))

        // Avantajlar Kartı
        AnimatedSurface(
            modifier = Modifier.fillMaxWidth(),
            color = CardBg,
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("NELER KAZANACAKSIN?", color = AccentGreen, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(12.dp))
                VipFeature("🚀", "Sınırsız ve kesintisiz VPN erişimi")
                VipFeature("⚡", "Öncelikli yüksek hızlı sunucular")
                VipFeature("🛡️", "Gelişmiş DNS ve reklam engelleme")
                VipFeature("🎮", "PUBG için düşük ping optimizasyonu")
            }
        }

        Spacer(Modifier.height(30.dp))

        // Telegram Satın Alma Butonu
        NeonButton(
            text = "VIP SÜRÜMÜ AÇ",
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/$TELEGRAM_USERNAME"))
                    context.startActivity(intent)
                } catch (e: Exception) {
                    message = "Telegram açılamadı. Lütfen yüklü olduğundan emin olun."
                    isError = true
                }
            },
            leading = "➤"
        )
        
        Spacer(Modifier.height(16.dp))
        
        Text(
            "Ödeme sonrası size özel bir VIP kodu verilecektir.",
            color = TextMuted, fontSize = 12.sp, textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(40.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.1f)))
        Spacer(Modifier.height(20.dp))

        // Redeem Kodu Girişi
        Text("ELİNİZDE KOD VAR MI?", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '-' } },
            label = { Text("VIP Kodu") },
            placeholder = { Text("SHADOW-XXXX-XXXX") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        
        NeonButton(
            text = if (isLoading) "Doğrulanıyor..." else "KODU ETKİNLEŞTİR",
            modifier = Modifier.fillMaxWidth(),
            secondary = true,
            onClick = {
                if (code.length < 5) {
                    message = "Lütfen geçerli bir kod girin."
                    isError = true
                    return@NeonButton
                }
                isLoading = true
                scope.launch {
                    val (success, msg) = RedeemManager.redeemCode(context, code)
                    isLoading = false
                    message = msg
                    isError = !success
                    if (success) {
                        val (_, endDate) = RedeemManager.checkVip(context)
                        vipEndDate = endDate
                        kotlinx.coroutines.delay(1500)
                        onVipActivated()
                    }
                }
            }
        )

        if (message.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(message, color = if (isError) RedSoft else AccentGreen, fontSize = 13.sp, textAlign = TextAlign.Center)
        }

        if (vipEndDate != null) {
            Spacer(Modifier.height(20.dp))
            Text(
                "VIP Bitiş Tarihi: ${vipEndDate!!.substringBefore("T")}",
                color = PurpleLight, fontSize = 14.sp, fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(30.dp))
        TextButton(onClick = onDismiss) {
            Text("Geri Dön", color = TextMuted)
        }
    }
}

@Composable
private fun VipFeature(icon: String, text: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 18.sp)
        Spacer(Modifier.width(12.dp))
        Text(text, color = Color.White, fontSize = 14.sp)
    }
}
