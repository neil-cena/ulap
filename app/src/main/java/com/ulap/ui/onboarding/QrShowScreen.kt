package com.ulap.ui.onboarding

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.ulap.domain.model.BotCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun QrShowScreen(
    token: String,
    chatId: String,
    additionalBots: List<BotCredential> = emptyList(),
    telegramLoggingEnabled: Boolean = false,
    telegramLoggingChatId: String? = null,
    googlePhotosWebClientId: String? = null,
) {
    val payload = remember(token, chatId, additionalBots, telegramLoggingEnabled, telegramLoggingChatId, googlePhotosWebClientId) {
        val json = JSONObject().put("t", token).put("c", chatId)
        if (additionalBots.isNotEmpty()) {
            val arr = JSONArray()
            additionalBots.forEach { bot ->
                arr.put(JSONObject().put("k", bot.token).put("l", bot.label))
            }
            json.put("b", arr)
        }
        if (telegramLoggingEnabled) json.put("le", true)
        if (!telegramLoggingChatId.isNullOrBlank()) json.put("lc", telegramLoggingChatId)
        if (!googlePhotosWebClientId.isNullOrBlank()) json.put("gp", googlePhotosWebClientId)
        json.toString()
    }

    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(payload) {
        qrBitmap = withContext(Dispatchers.Default) { generateQrBitmap(payload, 512) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Add another phone", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Open Ulap on your other phone, tap \"Scan QR\" on the welcome screen, then point its camera at this code.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        qrBitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "QR code",
                modifier = Modifier.size(260.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            buildString {
                append("This code contains your bot token, chat ID")
                if (additionalBots.isNotEmpty()) {
                    append(", and ${additionalBots.size} additional bot${if (additionalBots.size == 1) "" else "s"}")
                }
                if (telegramLoggingEnabled && !telegramLoggingChatId.isNullOrBlank()) {
                    append(", Telegram log settings")
                }
                if (!googlePhotosWebClientId.isNullOrBlank()) {
                    append(", Google Photos credentials")
                }
                append(".\nOnly share it with devices you own.")
            },
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun generateQrBitmap(content: String, size: Int): Bitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 1)
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
    }
    return bmp
}
