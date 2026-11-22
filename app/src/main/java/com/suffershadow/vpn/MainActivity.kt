package com.suffershadow.vpn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SufferShadowTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F0F13) // Koyu arka plan
                ) {
                    VpnScreen()
                }
            }
        }
    }
}

@Composable
fun VpnScreen() {
    var isConnected by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Üst Kısım: Logo ve Durum
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(50.dp))
            Text(
                text = "SUFFER SHADOW",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isConnected) "Bağlantı Güvenli" else "Ne zaman hazırsın?",
                color = if (isConnected) Color(0xFF00FFAA) else Color.Gray,
                fontSize = 16.sp
            )
        }

        // Orta Kısım: Bağlan Butonu
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(240.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = if (isConnected) listOf(Color(0xFF00FFAA), Color.Transparent)
                        else listOf(Color(0xFF6200EA), Color.Transparent)
                    )
                )
        ) {
            Button(
                onClick = { isConnected = !isConnected },
                modifier = Modifier.size(170.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected) Color(0xFF00FFAA) else Color(0xFF6200EA)
                )
            ) {
                Text(
                    text = if (isConnected) "KAPAT" else "BAĞLAN",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
        }

        // Alt Kısım: Sunucu Bilgisi
        Text(
            text = "Sunucu: 46.8.230.211 (Hazır)",
            color = Color.LightGray,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(30.dp))
    }
}

@Composable
fun SufferShadowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF6200EA),
            background = Color(0xFF0F0F13)
        ),
        content = content
    )
}
