package com.bende.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

private val Blue = Color(0xFF2563EB)
private val Ink = Color(0xFF182230)
private val Muted = Color(0xFF667085)
private val Line = Color(0xFFE5E7EB)
private val SurfaceSoft = Color(0xFFF7F8FA)

data class Attachment(val name: String, val uri: String?)
data class Message(val id: Long, val text: String, val user: Boolean, val attachment: Attachment? = null)
data class ChatSession(val id: Long, val title: String, val messages: List<Message>)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.WHITE
        window.navigationBarColor = android.graphics.Color.WHITE
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContent { BendeTheme { BendeApp() } }
    }
}

@Composable
private fun BendeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Blue, onPrimary = Color.White, background = Color.White, surface = Color.White, onSurface = Ink, outline = Line), content = content)
}

@Composable
private fun BendeApp() {
    val context = LocalContext.current
    val width = LocalConfiguration.current.screenWidthDp
    val wide = width >= 840
    var sessions by remember { mutableStateOf(loadSessions(context)) }
    var selectedId by remember { mutableLongStateOf(sessions.firstOrNull()?.id ?: 1L) }
    var input by remember { mutableStateOf("") }
    var attachment by remember { mutableStateOf<Attachment?>(null) }
    var showHistory by remember { mutableStateOf(wide) }
    var showSettings by remember { mutableStateOf(false) }
    val current = sessions.firstOrNull { it.id == selectedId } ?: sessions.first()
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            runCatching { context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = context.contentResolver.query(it, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)) else null } ?: it.lastPathSegment ?: "Dosya"
            attachment = Attachment(name, it.toString())
        }
    }

    fun persist(next: List<ChatSession>) { sessions = next; saveSessions(context, next) }
    fun newChat() {
        val session = ChatSession(System.currentTimeMillis(), "Yeni sohbet", emptyList())
        persist(listOf(session) + sessions); selectedId = session.id; input = ""; attachment = null
    }
    fun send() {
        if (input.isBlank() && attachment == null) return
        val value = input.trim()
        val userMessage = Message(System.currentTimeMillis(), value, true, attachment)
        val updated = current.copy(title = if (current.messages.isEmpty()) value.take(32).ifBlank { attachment?.name ?: "Yeni sohbet" } else current.title, messages = current.messages + userMessage)
        persist(sessions.map { if (it.id == current.id) updated else it })
        input = ""; attachment = null
    }

    Box(Modifier.fillMaxSize().background(Color.White)) {
      Row(Modifier.fillMaxSize()) {
        if (wide) HistoryPanel(sessions, selectedId, onSelect = { selectedId = it.id; showHistory = false }, onNew = ::newChat, onSettings = { showSettings = true }, modifier = Modifier.width(300.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            TopBar(current.title, wide, onMenu = { showHistory = !showHistory }, onSettings = { showSettings = true })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (current.messages.isEmpty()) Welcome(onPrompt = { input = it })
                else MessagesList(current.messages, modifier = Modifier.fillMaxSize().padding(horizontal = if (wide) 72.dp else 20.dp))
            }
            Composer(input, { input = it }, attachment, { attachment = null }, onAttach = { filePicker.launch(arrayOf("*/*")) }, onSend = ::send, modifier = Modifier.fillMaxWidth().padding(horizontal = if (wide) 72.dp else 16.dp, vertical = 12.dp))
            Text("Bende hata yapabilir. Önemli bilgileri kontrol edin.", Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp), color = Muted, fontSize = 11.sp)
        }
      }
      if (!wide && showHistory) {
          Box(Modifier.fillMaxSize()) {
              Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).clickable { showHistory = false })
              HistoryPanel(sessions, selectedId, onSelect = { selectedId = it.id; showHistory = false }, onNew = { newChat(); showHistory = false }, onSettings = { showSettings = true; showHistory = false }, modifier = Modifier.width(300.dp).fillMaxHeight().align(Alignment.CenterStart))
          }
      }
    }
    if (showSettings) AlertDialog(onDismissRequest = { showSettings = false }, title = { Text("Ayarlar") }, text = { Text("Sağlayıcı, model ve ajan ayarları sonraki aşamada eklenecek.") }, confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Tamam") } })
}

@Composable
private fun TopBar(title: String, wide: Boolean, onMenu: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).border(width = 0.5.dp, color = Line).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!wide) IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, contentDescription = "Sohbet geçmişi", tint = Ink) }
        Text(title, Modifier.weight(1f), color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
        Text("Model bağlanmadı", Modifier.clip(RoundedCornerShape(16.dp)).background(SurfaceSoft).clickable(onClick = onSettings).padding(horizontal = 12.dp, vertical = 8.dp), color = Muted, fontSize = 12.sp)
        IconButton(onClick = onSettings) { Icon(Icons.Default.MoreHoriz, contentDescription = "Diğer seçenekler", tint = Muted) }
    }
}

@Composable
private fun HistoryPanel(sessions: List<ChatSession>, selectedId: Long, onSelect: (ChatSession) -> Unit, onNew: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxHeight().background(Color(0xFFFAFAFB)).border(width = 0.5.dp, color = Line).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(Blue), contentAlignment = Alignment.Center) { Icon(Icons.Default.SmartToy, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp)) }
            Text("Bende", Modifier.padding(start = 10.dp).weight(1f), color = Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            IconButton(onClick = onNew) { Icon(Icons.Default.Add, contentDescription = "Yeni sohbet", tint = Blue) }
        }
        Spacer(Modifier.height(22.dp))
        Button(onClick = onNew, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(12.dp)) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Yeni sohbet") }
        Text("ÇALIŞMA ALANI", Modifier.padding(top = 24.dp, bottom = 8.dp, start = 8.dp), color = Muted, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 1.sp)
        NavRow(Icons.Default.FolderOpen, "Proje seç", "Yakında")
        NavRow(Icons.Default.Terminal, "Kod araçları", "Yakında")
        Text("GEÇMİŞ", Modifier.padding(top = 24.dp, bottom = 8.dp, start = 8.dp), color = Muted, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 1.sp)
        LazyColumn(Modifier.weight(1f)) {
            items(sessions, key = { it.id }) { session ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (session.id == selectedId) Color(0xFFEFF4FF) else Color.Transparent).clickable { onSelect(session) }.padding(horizontal = 10.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ChatBubbleOutline, null, Modifier.size(16.dp), tint = if (session.id == selectedId) Blue else Muted)
                    Text(session.title, Modifier.padding(start = 9.dp), color = Ink, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
        HorizontalDivider(color = Line)
        Row(Modifier.fillMaxWidth().clickable(onClick = onSettings).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Settings, null, tint = Muted, modifier = Modifier.size(18.dp)); Text("Ayarlar", Modifier.padding(start = 10.dp), color = Muted, fontSize = 13.sp) }
    }
}

@Composable
private fun NavRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, badge: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = Muted, modifier = Modifier.size(17.dp)); Text(title, Modifier.weight(1f).padding(start = 10.dp), color = Ink, fontSize = 13.sp); Text(badge, color = Muted, fontSize = 10.sp) }
}

@Composable
private fun Welcome(onPrompt: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(62.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFFEFF4FF)), contentAlignment = Alignment.Center) { Icon(Icons.Default.SmartToy, null, tint = Blue, modifier = Modifier.size(30.dp)) }
        Text("Bugün ne yapalım?", Modifier.padding(top = 20.dp), fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Text("Fikrini yaz, birlikte başlayalım.", Modifier.padding(top = 8.dp, bottom = 26.dp), color = Muted, fontSize = 14.sp)
        Column(Modifier.widthIn(max = 480.dp)) {
            Suggestion(Icons.Default.Code, "Bir fikri uygulamaya dönüştür", "Basit bir yapılacaklar listesi tasarla", onPrompt)
            Suggestion(Icons.Default.Terminal, "Kod hakkında yardım al", "Bu kodu benim için açıkla", onPrompt)
            Suggestion(Icons.Default.Image, "Dosyalarını incele", "Eklediğim dosya hakkında bilgi ver", onPrompt)
        }
    }
}

@Composable
private fun Suggestion(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, prompt: String, onPrompt: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(SurfaceSoft).clickable { onPrompt(prompt) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Blue, modifier = Modifier.size(19.dp))
        Column(Modifier.padding(start = 12.dp)) { Text(title, fontWeight = FontWeight.Medium, color = Ink, fontSize = 13.sp); Text(prompt, Modifier.padding(top = 3.dp), color = Muted, fontSize = 12.sp) }
    }
}

@Composable
private fun MessagesList(messages: List<Message>, modifier: Modifier = Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(top = 24.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        items(messages, key = { it.id }) { message ->
            if (message.user) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Column(Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 5.dp)).background(Color(0xFFF1F3F6)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    message.attachment?.let { Text("📎  ${it.name}", color = Blue, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp)) }
                    MarkdownText(message.text, Ink)
                }
            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(Color(0xFFEFF4FF)), contentAlignment = Alignment.Center) { Icon(Icons.Default.SmartToy, null, tint = Blue, modifier = Modifier.size(16.dp)) }
                Column(Modifier.padding(start = 11.dp).widthIn(max = 600.dp)) { Text("Bende", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp); MarkdownText(message.text, Ink, Modifier.padding(top = 6.dp)) }
            }
        }
    }
}

@Composable
private fun MarkdownText(text: String, color: Color, modifier: Modifier = Modifier) {
    val annotated = buildAnnotatedString {
        text.lines().forEachIndexed { i, line ->
            if (i > 0) append("\n")
            val heading = line.startsWith("#")
            val clean = line.trimStart('#').trim()
            if (heading) withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp, color = color)) { append(clean) }
            else if (line.startsWith("- ") || line.startsWith("* ")) { append("• "); append(line.drop(2)) }
            else append(line.replace("**", ""))
        }
    }
    Text(annotated, modifier, color = color, fontSize = 14.sp, lineHeight = 22.sp)
}

@Composable
private fun Composer(input: String, onInput: (String) -> Unit, attachment: Attachment?, clearAttachment: () -> Unit, onAttach: () -> Unit, onSend: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).border(1.dp, Line, RoundedCornerShape(20.dp)).background(Color.White).padding(10.dp)) {
        AnimatedVisibility(attachment != null) {
            Row(Modifier.padding(start = 6.dp, bottom = 8.dp).clip(RoundedCornerShape(8.dp)).background(SurfaceSoft).padding(start = 9.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AttachFile, null, tint = Blue, modifier = Modifier.size(15.dp)); Text(attachment?.name.orEmpty(), Modifier.padding(horizontal = 5.dp), color = Muted, fontSize = 12.sp); IconButton(onClick = clearAttachment, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Eki kaldır", modifier = Modifier.size(16.dp), tint = Muted) }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = onAttach, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Add, "Dosya ekle", tint = Muted) }
            Box(Modifier.weight(1f).padding(horizontal = 7.dp, vertical = 11.dp)) {
                if (input.isEmpty()) Text("Bende'ye mesaj yaz", color = Color(0xFF98A2B3), fontSize = 14.sp)
                BasicTextField(value = input, onValueChange = onInput, modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(color = Ink, fontSize = 14.sp, lineHeight = 20.sp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { onSend() }), maxLines = 5)
            }
            IconButton(onClick = onSend, modifier = Modifier.size(40.dp).clip(CircleShape).background(if (input.isNotBlank() || attachment != null) Blue else Color(0xFFE5E7EB))) { Icon(Icons.AutoMirrored.Filled.Send, "Gönder", tint = Color.White, modifier = Modifier.size(18.dp)) }
        }
    }
}

private fun loadSessions(context: Context): List<ChatSession> = try {
    val raw = context.getSharedPreferences("bende", Context.MODE_PRIVATE).getString("sessions", null) ?: return listOf(ChatSession(1L, "Yeni sohbet", emptyList()))
    val arr = JSONArray(raw)
    (0 until arr.length()).map { i ->
        val obj = arr.getJSONObject(i)
        val msgs = obj.getJSONArray("messages")
        ChatSession(obj.getLong("id"), obj.getString("title"), (0 until msgs.length()).map { j ->
            val m = msgs.getJSONObject(j)
            val uri = m.optString("attachmentUri").takeIf { it.isNotEmpty() }
            val name = m.optString("attachmentName").takeIf { it.isNotEmpty() } ?: m.optString("attachment").takeIf { it.isNotEmpty() }
            Message(m.getLong("id"), m.getString("text"), m.getBoolean("user"), if (name != null) Attachment(name, uri) else null)
        })
    }.ifEmpty { listOf(ChatSession(1L, "Yeni sohbet", emptyList())) }
} catch (_: Exception) { listOf(ChatSession(1L, "Yeni sohbet", emptyList())) }

private fun saveSessions(context: Context, sessions: List<ChatSession>) {
    val arr = JSONArray()
    sessions.forEach { session ->
        val msgs = JSONArray()
        session.messages.forEach { msg -> msgs.put(JSONObject().put("id", msg.id).put("text", msg.text).put("user", msg.user).put("attachmentName", msg.attachment?.name ?: "").put("attachmentUri", msg.attachment?.uri ?: "")) }
        arr.put(JSONObject().put("id", session.id).put("title", session.title).put("messages", msgs))
    }
    context.getSharedPreferences("bende", Context.MODE_PRIVATE).edit().putString("sessions", arr.toString()).apply()
}
