@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.suffershadow.vpn

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
                    color = Color(0xFF0F0F13)
                ) {
                    VpnScreen()
                }
            }
        }
    }
}

fun describeLink(link: String): String? {
    val l = link.trim().substringBefore("#")
    return try {
        when {
            l.startsWith("vless://") -> {
                val hostPort = l.substringAfter("@").substringBefore("?")
                if (hostPort.contains(":")) "VLESS  •  $hostPort" else null
            }
            l.startsWith("ss://") -> {
                if (l.contains("@")) {
                    "Shadowsocks  •  " + l.substringAfter("@").substringBefore("?")
                } else {
                    "Shadowsocks"
                }
            }
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

@Composable
fun VpnScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val prefs = remember { context.getSharedPreferences("suffer", Context.MODE_PRIVATE) }

    var savedLink by remember { mutableStateOf(prefs.getString("link", "") ?: "") }
    var input by remember { mutableStateOf(savedLink) }
    var message by remember { mutableStateOf("") }
    var isConnected by remember { mutableStateOf(false) }

    val summary = describeLink(savedLink)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(40.dp))
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

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("vless:// veya ss:// linki") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 4
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val text = clipboard.getText()?.text ?: ""
                if (text.isNotEmpty()) input = text
            }) {
                Text("Yapıştır")
            }
            Button(onClick = {
                if (describeLink(input) != null) {
                    savedLink = input.trim()
                    prefs.edit().putString("link", savedLink).apply()
                    message = "Link kaydedildi"
                } else {
                    message = "Link tanınmadı. vless:// veya ss:// ile başlamalı"
                }
            }) {
                Text("Kaydet")
            }
        }
        if (message.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = message, color = Color.LightGray, fontSize = 13.sp)
        }

        Spacer(modifier = Modifier.height(40.dp))

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
                enabled = summary != null,
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

        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = summary ?: "Henüz link eklenmedi",
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
