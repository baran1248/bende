package com.bende.app

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

// Yedek palet: gri yüzey çizgisi ve tehlike renkleri. Ana rol renkleri MaterialTheme.colorScheme'dan gelir.
private val Line = Color(0xFFE5E7EB)
private val Danger = Color(0xFFB42318)
private val DangerSurface = Color(0xFFFFF1F1)

/**
 * "Tarayıcı devri" ekranı: tam ekran uygulama içi WebView.
 *
 * - Üstte adres alanı ve Git / Geri / İleri / Yenile düğmeleri
 * - Ortada WebView (JavaScript + DOM storage açık, güvenli ayarlar [BrowserSession.attach] içinde)
 * - Altta büyük "Ajanı devret" düğmesi; devir sonrası "Ajan bu sayfayı yönetiyor" rozeti
 *   ve "Devri geri al" düğmesi
 * - Ajanın tıklama/doldurma istekleri [BrowserApprovalDialog] ile onaya sunulur
 *
 * Durum tek kaynaktadır: [BrowserSession]. Adres, ekran döndürülünce [rememberSaveable] ile korunur.
 */
@Composable
fun BrowserHandoffScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val webView = remember { WebView(context) }
    var address by rememberSaveable { mutableStateOf(BrowserSession.url) }

    val colors = MaterialTheme.colorScheme
    val vurgu = colors.primary
    val metin = colors.onSurface
    val sessiz = colors.onSurfaceVariant
    val zemin = colors.background
    val yuzey = colors.surfaceContainerLow
    val vurguYuzey = vurgu.copy(alpha = 0.10f)

    DisposableEffect(webView) {
        BrowserSession.attach(webView)
        onDispose {
            BrowserSession.detach()
            webView.destroy()
        }
    }

    LaunchedEffect(Unit) {
        if (webView.url.isNullOrBlank() && BrowserSession.url.isNotBlank()) {
            BrowserSession.navigate(BrowserSession.url)
        }
    }

    val sessionUrl = BrowserSession.url
    LaunchedEffect(sessionUrl) {
        if (sessionUrl.isNotBlank()) address = sessionUrl
    }

    Column(Modifier.fillMaxSize().background(zemin)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Tarayıcıyı kapat", tint = metin)
            }
            Text("Tarayıcı devri", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = metin)
            Spacer(Modifier.width(8.dp))
            if (BrowserSession.handedOff) {
                Row(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(vurguYuzey).padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(vurgu))
                    Spacer(Modifier.width(6.dp))
                    Text("Ajan bu sayfayı yönetiyor", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = vurgu)
                }
            }
            Spacer(Modifier.weight(1f))
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                placeholder = { Text("https://ornek.com", color = sessiz, fontSize = 13.sp) },
                textStyle = TextStyle(fontSize = 13.sp, color = metin),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { BrowserSession.navigate(address) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = vurgu,
                    unfocusedBorderColor = Line,
                    focusedContainerColor = zemin,
                    unfocusedContainerColor = yuzey
                )
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { BrowserSession.navigate(address) },
                enabled = address.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = vurgu),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) { Text("Git", fontWeight = FontWeight.SemiBold) }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { BrowserSession.goBack() }, enabled = BrowserSession.canGoBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri", tint = if (BrowserSession.canGoBack) metin else Line)
            }
            IconButton(onClick = { BrowserSession.goForward() }, enabled = BrowserSession.canGoForward) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "İleri", tint = if (BrowserSession.canGoForward) metin else Line)
            }
            IconButton(onClick = { BrowserSession.reload() }, enabled = BrowserSession.url.isNotBlank()) {
                Icon(Icons.Filled.Refresh, contentDescription = "Yenile", tint = if (BrowserSession.url.isNotBlank()) metin else Line)
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (BrowserSession.loading) "Yükleniyor…" else if (BrowserSession.url.isNotBlank()) "Hazır" else "",
                color = sessiz,
                fontSize = 11.sp,
                modifier = Modifier.padding(end = 12.dp)
            )
        }

        if (BrowserSession.loading) {
            LinearProgressIndicator(
                progress = { (BrowserSession.progress / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = vurgu,
                trackColor = vurguYuzey
            )
        }

        BrowserSession.lastError?.let { error ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp)).background(DangerSurface).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(error, Modifier.weight(1f), color = Danger, fontSize = 12.sp)
                IconButton(onClick = { BrowserSession.clearError() }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Hatayı kapat", tint = Danger, modifier = Modifier.size(16.dp))
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth().background(yuzey)) {
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize()
            )
            if (BrowserSession.url.isBlank() && !BrowserSession.loading) {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Filled.Public, contentDescription = null, tint = sessiz, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Açık sayfa yok", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = metin)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Başlamak için yukarıya bir adres yazıp \"Git\" düğmesine dokunun.",
                        color = sessiz,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Column(Modifier.fillMaxWidth().background(zemin).padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (BrowserSession.handedOff) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(vurguYuzey).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Ajan bu sayfayı yönetiyor", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = vurgu)
                        Text(
                            "Ajan sayfayı okuyabilir, tıklayabilir ve form doldurabilir. Her değişiklik için onayınız istenir.",
                            fontSize = 11.sp,
                            color = metin,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { BrowserSession.revokeHandOff() },
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Devri geri al", fontSize = 12.sp, color = vurgu) }
                }
            } else {
                Button(
                    onClick = { BrowserSession.handOff() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = vurgu)
                ) {
                    Text("Ajanı devret", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "Devrettiğinizde ajan bu sayfayı okuyup yönetebilir; tıklama ve form doldurma için onayınız istenir.",
                    color = sessiz,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    BrowserSession.pendingApproval?.let { request ->
        BrowserApprovalDialog(request = request, onDecision = { BrowserSession.decideApproval(it) })
    }
}

/**
 * Ajanın tıklama / form doldurma isteği için kullanıcı onayı.
 * Hedef özetlenir; hedef veya değer riskli anahtar kelime içeriyorsa kırmızı uyarı gösterilir
 * ([RiskliEylemTespiti]).
 */
@Composable
fun BrowserApprovalDialog(
    request: BrowserApprovalRequest,
    onDecision: (Boolean) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val vurgu = colors.primary
    val metin = colors.onSurface
    val sessiz = colors.onSurfaceVariant

    val args = request.invocation.arguments
    val selector = args.optString("selector")
    val value = args.optString("value")
    val actionLabel = request.actionLabel.ifBlank {
        when (request.invocation.name) {
            "browser_click" -> "tıklama"
            "browser_fill" -> "form doldurma"
            else -> "sayfa değişikliği"
        }
    }
    val hedef = request.targetSummary.ifBlank { selector }
    val riskMetni = listOf(request.targetSummary, selector, value, request.invocation.name).joinToString(" ")
    val riskliKelimeler = RiskliEylemTespiti.riskliAnahtarKelimeler(riskMetni)
    val riskli = riskliKelimeler.isNotEmpty()

    AlertDialog(
        onDismissRequest = { onDecision(false) },
        title = {
            Text(
                when (request.invocation.name) {
                    "browser_click" -> "Tıklama onayı"
                    "browser_fill" -> "Form doldurma onayı"
                    else -> "Tarayıcı eylemi onayı"
                }
            )
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Sayfa: ${BrowserSession.url.ifBlank { "(açık sayfa yok)" }}",
                    color = metin,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text("Eylem: $actionLabel", Modifier.padding(top = 6.dp), color = metin, fontSize = 12.sp)
                Text(
                    "Hedef: ${hedef.ifBlank { "(belirtilmedi)" }}",
                    Modifier.padding(top = 6.dp),
                    color = sessiz,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                if (request.invocation.name == "browser_fill") {
                    Text(
                        "Girilecek değer: ${value.take(300).ifBlank { "(boş)" }}",
                        Modifier.padding(top = 6.dp),
                        color = sessiz,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (riskli) {
                    Column(
                        Modifier.padding(top = 12.dp).fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp)).background(DangerSurface).padding(12.dp)
                    ) {
                        Text("⚠️ Riskli eylem tespit edildi", color = Danger, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Bu eylem ödeme, silme, satın alma veya mesaj gönderme gibi geri alınamaz bir sonuç doğurabilir. Onaylamadan önce hedefi dikkatle kontrol edin.",
                            Modifier.padding(top = 6.dp),
                            color = Danger,
                            fontSize = 11.sp
                        )
                        Text(
                            "Eşleşen anahtar kelimeler: ${riskliKelimeler.joinToString(", ")}",
                            Modifier.padding(top = 6.dp),
                            color = Danger,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Text(
                    "Bu eylem sayfayı değiştirir. Onay vermezseniz ajan işlemi yapmaz.",
                    Modifier.padding(top = 12.dp),
                    color = sessiz,
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDecision(true) }) {
                Text("İzin ver", color = vurgu, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = { onDecision(false) }) {
                Text("Reddet", color = if (riskli) Danger else sessiz)
            }
        }
    )
}