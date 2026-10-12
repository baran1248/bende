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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_EXPANDED_LOWER_BOUND
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_MEDIUM_LOWER_BOUND
import org.json.JSONArray
import org.json.JSONObject

private val Blue = Color(0xFF2563EB)
private val Ink = Color(0xFF182230)
private val Muted = Color(0xFF667085)
private val Line = Color(0xFFE5E7EB)
private val SurfaceSoft = Color(0xFFF7F8FA)

data class Attachment(val name: String, val uri: String?)
data class Message(
    val id: Long,
    val text: String,
    val user: Boolean,
    val attachment: Attachment? = null,
    val toolCalls: List<ToolInvocation> = emptyList(),
    val toolResultFor: String? = null,
    val toolName: String? = null
)
data class ChatSession(val id: Long, val title: String, val messages: List<Message>)
data class ToolApproval(val invocation: ToolInvocation, val decision: CompletableDeferred<Boolean>)

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
    MaterialTheme(colorScheme = lightColorScheme(primary = Blue, onPrimary = Color.White, secondary = Blue, onSecondary = Color.White, tertiary = Blue, onTertiary = Color.White, background = Color.White, surface = Color.White, surfaceContainer = Color.White, surfaceContainerHigh = Color.White, surfaceContainerLow = SurfaceSoft, surfaceVariant = SurfaceSoft, onSurface = Ink, onSurfaceVariant = Muted, outline = Line), content = content)
}

@Composable
private fun BendeApp() {
    val context = LocalContext.current
    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    val wide = windowSizeClass.isWidthAtLeastBreakpoint(WIDTH_DP_EXPANDED_LOWER_BOUND)
    val persistentHistory = windowSizeClass.isWidthAtLeastBreakpoint(WIDTH_DP_MEDIUM_LOWER_BOUND)
    var sessions by remember { mutableStateOf(loadSessions(context)) }
    var selectedId by remember { mutableLongStateOf(sessions.firstOrNull()?.id ?: 1L) }
    var input by remember { mutableStateOf("") }
    var attachment by remember { mutableStateOf<Attachment?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var providerConfig by remember { mutableStateOf(ProviderStore.load(context)) }
    var projectName by remember { mutableStateOf(ProjectWorkspace.name(context)) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var pendingApproval by remember { mutableStateOf<ToolApproval?>(null) }
    var editingSession by remember { mutableStateOf<ChatSession?>(null) }
    var editedTitle by remember { mutableStateOf("") }
    var deletingSession by remember { mutableStateOf<ChatSession?>(null) }
    val scope = rememberCoroutineScope()
    val current = sessions.firstOrNull { it.id == selectedId } ?: sessions.first()
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            runCatching { context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = context.contentResolver.query(it, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)) else null } ?: it.lastPathSegment ?: "Dosya"
            attachment = Attachment(name, it.toString())
        }
    }
    val projectPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(it, flags) }
            val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, it)
            projectName = root?.name ?: "Proje"
            ProjectWorkspace.save(context, it, projectName ?: "Proje")
        }
    }

    fun persist(next: List<ChatSession>) { sessions = next; saveSessions(context, next) }
    fun newChat() {
        val session = ChatSession(System.currentTimeMillis(), "Yeni sohbet", emptyList())
        persist(listOf(session) + sessions); selectedId = session.id; input = ""; attachment = null; sendError = null
    }
    fun renameSession(session: ChatSession) {
        val title = editedTitle.trim().take(80)
        if (title.isNotEmpty()) persist(sessions.map { if (it.id == session.id) it.copy(title = title) else it })
        editingSession = null
    }
    fun deleteSession(session: ChatSession) {
        if (sending) return
        val remaining = sessions.filterNot { it.id == session.id }
        val next = remaining.ifEmpty { listOf(ChatSession(System.currentTimeMillis(), "Yeni sohbet", emptyList())) }
        persist(next)
        if (selectedId == session.id) {
            selectedId = next.first().id
            sendError = null
        }
        deletingSession = null
    }
    fun send() {
        if ((input.isBlank() && attachment == null) || sending) return
        val value = input.trim()
        val userMessage = Message(System.currentTimeMillis(), value, true, attachment)
        val updated = current.copy(title = if (current.messages.isEmpty()) value.take(32).ifBlank { attachment?.name ?: "Yeni sohbet" } else current.title, messages = current.messages + userMessage)
        persist(sessions.map { if (it.id == current.id) updated else it })
        val targetId = current.id
        val requestHistory = updated.messages
        input = ""; attachment = null
        sendError = null
        sending = true
        scope.launch {
            try {
                var history = requestHistory
                var completed = false
                for (step in 0 until 8) {
                    val answer = ProviderApi.complete(context, providerConfig, history)
                    if (answer.toolCalls.isEmpty()) {
                        val finalMessage = Message(System.currentTimeMillis(), answer.text, false)
                        history = history + finalMessage
                        sessions.firstOrNull { it.id == targetId }?.let { latest -> persist(sessions.map { session -> if (session.id == targetId) latest.copy(messages = history) else session }) }
                        completed = true
                        break
                    }
                    val toolMessage = Message(System.currentTimeMillis(), answer.text, false, toolCalls = answer.toolCalls)
                    history = history + toolMessage
                    sessions.firstOrNull { it.id == targetId }?.let { latest -> persist(sessions.map { session -> if (session.id == targetId) latest.copy(messages = history) else session }) }
                    for (call in answer.toolCalls) {
                        val result = if (AgentTools.needsApproval(call.name)) {
                            val request = ToolApproval(call, CompletableDeferred())
                            pendingApproval = request
                            val approved = request.decision.await()
                            pendingApproval = null
                            if (approved) runCatching { AgentTools.execute(context, call) }.getOrElse { "Araç hatası: ${it.message ?: "İşlem tamamlanamadı."}" }
                            else "Kullanıcı işlemi reddetti; dosya değiştirilmedi."
                        } else runCatching { AgentTools.execute(context, call) }.getOrElse { "Araç hatası: ${it.message ?: "İşlem tamamlanamadı."}" }
                        val toolResult = Message(System.currentTimeMillis(), result, false, toolResultFor = call.id, toolName = call.name)
                        history = history + toolResult
                        sessions.firstOrNull { it.id == targetId }?.let { latest -> persist(sessions.map { session -> if (session.id == targetId) latest.copy(messages = history) else session }) }
                    }
                }
                if (!completed) sendError = "Ajan 8 araç/yanıt adımı sonunda durdu. Kaldığı yerden yeni mesajla devam edebilirsin."
            } catch (error: Exception) {
                sendError = error.message ?: "İstek tamamlanamadı. Bağlantı ve sağlayıcı ayarlarını kontrol edin."
            } finally { sending = false }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.White)) {
      Row(Modifier.fillMaxSize()) {
        if (persistentHistory) HistoryPanel(sessions, selectedId, projectName, allowDelete = !sending, onSelectProject = { showHistory = false; projectPicker.launch(null) }, onSelect = { selectedId = it.id; showHistory = false; sendError = null }, onNew = ::newChat, onSettings = { showSettings = true }, onRename = { editingSession = it; editedTitle = it.title }, onDelete = { deletingSession = it }, modifier = Modifier.width(if (wide) 300.dp else 250.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            TopBar(current.title, !persistentHistory, providerConfig.model.takeIf { providerConfig.apiKey.isNotBlank() && it.isNotBlank() }, onMenu = { showHistory = !showHistory }, onSettings = { showSettings = true })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (current.messages.isEmpty()) Welcome(onPrompt = { input = it })
                else MessagesList(current.messages, sending = sending && selectedId == current.id, modifier = Modifier.fillMaxSize().padding(horizontal = if (wide) 72.dp else 20.dp))
                sendError?.let { error ->
                    Text("Bağlantı hatası: $error", Modifier.align(Alignment.TopCenter).padding(12.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFFFF1F1)).padding(12.dp), color = Color(0xFFB42318), fontSize = 12.sp)
                }
            }
            Composer(input, { input = it }, attachment, { attachment = null }, onAttach = { filePicker.launch(arrayOf("*/*")) }, onSend = ::send, sending = sending, modifier = Modifier.fillMaxWidth().padding(horizontal = if (wide) 72.dp else 16.dp, vertical = 12.dp))
            Text("Bende hata yapabilir. Önemli bilgileri kontrol edin.", Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp), color = Muted, fontSize = 11.sp)
        }
      }
      if (!persistentHistory && showHistory) {
          Box(Modifier.fillMaxSize()) {
              Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).clickable { showHistory = false })
              HistoryPanel(sessions, selectedId, projectName, allowDelete = !sending, onSelectProject = { showHistory = false; projectPicker.launch(null) }, onSelect = { selectedId = it.id; showHistory = false; sendError = null }, onNew = { newChat(); showHistory = false }, onSettings = { showSettings = true; showHistory = false }, onRename = { editingSession = it; editedTitle = it.title }, onDelete = { deletingSession = it }, modifier = Modifier.width(300.dp).fillMaxHeight().align(Alignment.CenterStart))
          }
      }
    }
    if (showSettings) ProviderSettingsDialog(
        initial = providerConfig,
        onDismiss = { showSettings = false },
        onSave = { config -> ProviderStore.save(context, config); providerConfig = ProviderStore.load(context); showSettings = false },
        onClearKey = { ProviderStore.clearKey(context); providerConfig = ProviderStore.load(context) }
    )
    editingSession?.let { session ->
        AlertDialog(
            onDismissRequest = { editingSession = null },
            title = { Text("Sohbeti yeniden adlandır") },
            text = { OutlinedTextField(value = editedTitle, onValueChange = { editedTitle = it.take(80) }, singleLine = true, label = { Text("Sohbet adı") }) },
            confirmButton = { TextButton(enabled = editedTitle.isNotBlank(), onClick = { renameSession(session) }) { Text("Kaydet") } },
            dismissButton = { TextButton(onClick = { editingSession = null }) { Text("İptal") } }
        )
    }
    deletingSession?.let { session ->
        AlertDialog(
            onDismissRequest = { deletingSession = null },
            title = { Text("Sohbet silinsin mi?") },
            text = { Text("‘${session.title}’ ve içindeki mesajlar bu cihazdan silinecek. Bu işlem geri alınamaz.") },
            confirmButton = { TextButton(onClick = { deleteSession(session) }) { Text("Sil", color = Color(0xFFB42318)) } },
            dismissButton = { TextButton(onClick = { deletingSession = null }) { Text("Vazgeç") } }
        )
    }
    pendingApproval?.let { request ->
        val call = request.invocation
        val args = call.arguments
        val path = args.optString("path")
        val preview = when (call.name) {
            "write_file" -> args.optString("content")
            "edit_file" -> "Bul: ${args.optString("old_text")}\n\nYeni: ${args.optString("new_text")}"
            else -> path
        }
        AlertDialog(
            onDismissRequest = { request.decision.complete(false) },
            title = { Text(if (call.name == "delete_file") "Dosya silme izni" else "Dosya değişikliği izni") },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("Proje: ${projectName ?: "Seçilmedi"}\nAraç: ${call.name}\nYol: $path", color = Ink)
                if (call.name == "delete_file") Text("Bu işlem dosyayı kalıcı olarak siler.", Modifier.padding(top = 12.dp), color = Color(0xFFB42318))
                else Text(preview.take(3000), Modifier.padding(top = 12.dp), color = Muted, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                Text("Ajan bu işlemi yapabilmek için onayınızı bekliyor.", Modifier.padding(top = 12.dp), color = Muted, fontSize = 12.sp)
            } },
            confirmButton = { TextButton(onClick = { request.decision.complete(true) }) { Text("İzin ver") } },
            dismissButton = { TextButton(onClick = { request.decision.complete(false) }) { Text("Reddet") } }
        )
    }
}

@Composable
private fun ProviderSettingsDialog(
    initial: ProviderConfig,
    onDismiss: () -> Unit,
    onSave: (ProviderConfig) -> Unit,
    onClearKey: () -> Unit
) {
    var kind by remember { mutableStateOf(initial.kind) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    var expanded by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf(emptyList<String>()) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        containerColor = Color.White,
        title = { Text("Model sağlayıcısı") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 570.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("OpenAI uyumlu bir API veya Anthropic kullan. API anahtarı bu cihazda Android Keystore ile şifrelenerek saklanır.", color = Muted, fontSize = 12.sp)
                Box {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("${kind.label}  ▾") }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        ProviderKind.entries.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = {
                            kind = option
                            if (baseUrl == "https://api.openai.com" || baseUrl == "https://api.anthropic.com") baseUrl = if (option == ProviderKind.ANTHROPIC) "https://api.anthropic.com" else "https://api.openai.com"
                            expanded = false
                        }) }
                    }
                }
                OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("API adresi") }, singleLine = true, modifier = Modifier.fillMaxWidth(), supportingText = { Text("Örn. https://api.openai.com veya https://openrouter.ai/api/v1") })
                OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API anahtarı") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(model, { model = it }, label = { Text("Model kimliği") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (models.isNotEmpty()) {
                    Text("Bulunan modeller", color = Muted, fontSize = 12.sp)
                    Column(Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState())) {
                        models.take(80).forEach { id ->
                            TextButton(onClick = { model = id }, modifier = Modifier.fillMaxWidth()) { Text(id, modifier = Modifier.fillMaxWidth(), maxLines = 1) }
                        }
                    }
                }
                Text("İsteklerin ve eklediğin içerikler seçtiğin sağlayıcıya gönderilir. OpenCode Zen ayrı hesap ve API anahtarı ister; ücretsiz modellerin veri şartları modele göre değişebilir.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                status?.let { Text(it, color = if (it.startsWith("Hata")) Color(0xFFB42318) else Blue, fontSize = 12.sp) }
                OutlinedButton(onClick = { baseUrl = "https://opencode.ai/zen" }, modifier = Modifier.fillMaxWidth()) { Text("OpenCode Zen adresini kullan") }
                TextButton(onClick = { apiKey = ""; onClearKey(); status = "Bu cihazdaki saklı anahtar silindi." }, modifier = Modifier.fillMaxWidth()) { Text("Saklı anahtarı sil") }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = !loading, onClick = {
                    val config = ProviderConfig(kind, baseUrl, apiKey, model)
                    loading = true; status = "Modeller yükleniyor…"
                    scope.launch {
                        try { models = ProviderApi.listModels(config); status = if (models.isEmpty()) "Bağlantı başarılı; model bulunamadı." else "${models.size} model bulundu. Birini seçebilirsin." }
                        catch (error: Exception) { status = "Hata: ${error.message ?: "Bağlantı kurulamadı."}" }
                        finally { loading = false }
                    }
                }) { if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Modelleri bul") }
                TextButton(onClick = { onSave(ProviderConfig(kind, baseUrl, apiKey, model)) }) { Text("Kaydet") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("İptal") } }
    )
}

@Composable
private fun TopBar(title: String, showMenu: Boolean, model: String?, onMenu: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).border(width = 0.5.dp, color = Line).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showMenu) IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, contentDescription = "Sohbet geçmişi", tint = Ink) }
        Text(title, Modifier.weight(1f), color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
        Text(model?.let { "Model: $it" } ?: "Model bağlanmadı", Modifier.clip(RoundedCornerShape(16.dp)).background(SurfaceSoft).clickable(onClick = onSettings).padding(horizontal = 12.dp, vertical = 8.dp), color = Muted, fontSize = 12.sp, maxLines = 1)
        IconButton(onClick = onSettings) { Icon(Icons.Default.MoreHoriz, contentDescription = "Diğer seçenekler", tint = Muted) }
    }
}

@Composable
private fun HistoryPanel(sessions: List<ChatSession>, selectedId: Long, projectName: String?, allowDelete: Boolean, onSelectProject: () -> Unit, onSelect: (ChatSession) -> Unit, onNew: () -> Unit, onSettings: () -> Unit, onRename: (ChatSession) -> Unit, onDelete: (ChatSession) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxHeight().background(Color(0xFFFAFAFB)).border(width = 0.5.dp, color = Line).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(Blue), contentAlignment = Alignment.Center) { Icon(Icons.Default.SmartToy, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp)) }
            Text("Bende", Modifier.padding(start = 10.dp).weight(1f), color = Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            IconButton(onClick = onNew) { Icon(Icons.Default.Add, contentDescription = "Yeni sohbet", tint = Blue) }
        }
        Spacer(Modifier.height(22.dp))
        Button(onClick = onNew, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(12.dp)) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Yeni sohbet") }
        Text("ÇALIŞMA ALANI", Modifier.padding(top = 24.dp, bottom = 8.dp, start = 8.dp), color = Muted, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 1.sp)
        NavRow(Icons.Default.FolderOpen, if (projectName == null) "Proje seç" else "${projectName.take(18)}", if (projectName == null) "seç" else "açık", onSelectProject)
        NavRow(Icons.Default.Terminal, "Kod araçları", "5 araç")
        Text("GEÇMİŞ", Modifier.padding(top = 24.dp, bottom = 8.dp, start = 8.dp), color = Muted, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 1.sp)
        LazyColumn(Modifier.weight(1f)) {
            items(sessions, key = { it.id }) { session ->
                var menuExpanded by remember(session.id) { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (session.id == selectedId) Color(0xFFEFF4FF) else Color.Transparent).padding(start = 10.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).clickable { onSelect(session) }.padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ChatBubbleOutline, null, Modifier.size(16.dp), tint = if (session.id == selectedId) Blue else Muted)
                        Text(session.title, Modifier.padding(start = 9.dp), color = Ink, fontSize = 13.sp, maxLines = 1)
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.MoreHoriz, contentDescription = "${session.title} seçenekleri", tint = Muted, modifier = Modifier.size(18.dp)) }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(text = { Text("Yeniden adlandır") }, onClick = { menuExpanded = false; onRename(session) })
                            DropdownMenuItem(text = { Text("Sohbeti sil") }, enabled = allowDelete, onClick = { menuExpanded = false; onDelete(session) })
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = Line)
        Row(Modifier.fillMaxWidth().clickable(onClick = onSettings).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Settings, null, tint = Muted, modifier = Modifier.size(18.dp)); Text("Ayarlar", Modifier.padding(start = 10.dp), color = Muted, fontSize = 13.sp) }
    }
}

@Composable
private fun NavRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, badge: String, onClick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = Muted, modifier = Modifier.size(17.dp)); Text(title, Modifier.weight(1f).padding(start = 10.dp), color = Ink, fontSize = 13.sp, maxLines = 1); Text(badge, color = Muted, fontSize = 10.sp) }
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
private fun MessagesList(messages: List<Message>, sending: Boolean = false, modifier: Modifier = Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(top = 24.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        items(messages, key = { it.id }) { message ->
            if (message.user) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Column(Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 5.dp)).background(Color(0xFFF1F3F6)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    message.attachment?.let { Text("📎  ${it.name}", color = Blue, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp)) }
                    MarkdownText(message.text, Ink)
                }
            } else if (message.toolResultFor != null) Row(Modifier.fillMaxWidth()) {
                Column(Modifier.widthIn(max = 640.dp).clip(RoundedCornerShape(12.dp)).background(SurfaceSoft).padding(12.dp)) {
                    Text("${message.toolName ?: "Araç"} sonucu", color = Muted, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    Text(message.text.take(3000), Modifier.padding(top = 4.dp), color = Ink, fontSize = 12.sp, lineHeight = 18.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            } else if (message.toolCalls.isNotEmpty() && message.text.isBlank()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SmartToy, null, tint = Blue, modifier = Modifier.size(18.dp))
                Text(message.toolCalls.joinToString { AgentTools.display(it) }, Modifier.padding(start = 8.dp), color = Muted, fontSize = 12.sp)
            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(Color(0xFFEFF4FF)), contentAlignment = Alignment.Center) { Icon(Icons.Default.SmartToy, null, tint = Blue, modifier = Modifier.size(16.dp)) }
                Column(Modifier.padding(start = 11.dp).widthIn(max = 600.dp)) { Text("Bende", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp); MarkdownText(message.text, Ink, Modifier.padding(top = 6.dp)); if (message.toolCalls.isNotEmpty()) Text("Araçlar: ${message.toolCalls.joinToString { it.name }}", Modifier.padding(top = 6.dp), color = Muted, fontSize = 11.sp) }
            }
        }
        if (sending) item(key = "assistant-thinking") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Blue)
                Text("Yanıt hazırlanıyor…", Modifier.padding(start = 10.dp), color = Muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun MarkdownText(text: String, color: Color, modifier: Modifier = Modifier) {
    val annotated = buildAnnotatedString {
        var inCodeBlock = false
        text.lines().forEachIndexed { i, line ->
            if (i > 0) append("\n")
            if (line.trimStart().startsWith("```")) {
                inCodeBlock = !inCodeBlock
            } else if (inCodeBlock) {
                withStyle(SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, background = SurfaceSoft, color = color)) { append("  $line  ") }
            } else {
                val heading = Regex("^#{1,6}\\s+").containsMatchIn(line)
                val clean = if (heading) line.replaceFirst(Regex("^#{1,6}\\s+"), "") else line
                when {
                    heading -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp, color = color)) { appendInlineMarkdown(clean, color) }
                    clean.startsWith("- ") || clean.startsWith("* ") -> { append("• "); appendInlineMarkdown(clean.drop(2), color) }
                    clean.matches(Regex("^\\d+\\.\\s+.*")) -> { append(clean.substringBefore('.') + ". "); appendInlineMarkdown(clean.substringAfter(". "), color) }
                    clean.startsWith("> ") -> withStyle(SpanStyle(color = Muted, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { appendInlineMarkdown(clean.drop(2), color) }
                    else -> appendInlineMarkdown(clean, color)
                }
            }
        }
    }
    Text(annotated, modifier, color = color, fontSize = 14.sp, lineHeight = 22.sp)
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInlineMarkdown(text: String, color: Color) {
    val token = Regex("(\\*\\*.+?\\*\\*|__.+?__|`[^`]+`|\\*[^*]+\\*|_[^_]+_)")
    var cursor = 0
    token.findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first))
        val raw = match.value
        when {
            raw.startsWith("**") || raw.startsWith("__") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(raw.substring(2, raw.length - 2)) }
            raw.startsWith("`") -> withStyle(SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, background = SurfaceSoft, color = color)) { append(raw.substring(1, raw.length - 1)) }
            else -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(raw.substring(1, raw.length - 1)) }
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}

@Composable
private fun Composer(input: String, onInput: (String) -> Unit, attachment: Attachment?, clearAttachment: () -> Unit, onAttach: () -> Unit, onSend: () -> Unit, sending: Boolean, modifier: Modifier = Modifier) {
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
            IconButton(onClick = onSend, enabled = !sending && (input.isNotBlank() || attachment != null), modifier = Modifier.size(40.dp).clip(CircleShape).background(if (!sending && (input.isNotBlank() || attachment != null)) Blue else Color(0xFFE5E7EB))) { Icon(Icons.AutoMirrored.Filled.Send, "Gönder", tint = Color.White, modifier = Modifier.size(18.dp)) }
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
            val calls = m.optJSONArray("toolCalls")?.let { a -> (0 until a.length()).map { index ->
                val call = a.getJSONObject(index)
                ToolInvocation(call.getString("id"), call.getString("name"), JSONObject(call.optString("arguments", "{}")))
            } }.orEmpty()
            Message(m.getLong("id"), m.getString("text"), m.getBoolean("user"), if (name != null) Attachment(name, uri) else null,
                calls, m.optString("toolResultFor").takeIf { it.isNotBlank() }, m.optString("toolName").takeIf { it.isNotBlank() })
        })
    }.ifEmpty { listOf(ChatSession(1L, "Yeni sohbet", emptyList())) }
} catch (_: Exception) { listOf(ChatSession(1L, "Yeni sohbet", emptyList())) }

private fun saveSessions(context: Context, sessions: List<ChatSession>) {
    val arr = JSONArray()
    sessions.forEach { session ->
        val msgs = JSONArray()
        session.messages.forEach { msg ->
            val calls = JSONArray().apply { msg.toolCalls.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("arguments", it.arguments.toString())) } }
            msgs.put(JSONObject().put("id", msg.id).put("text", msg.text).put("user", msg.user).put("attachmentName", msg.attachment?.name ?: "").put("attachmentUri", msg.attachment?.uri ?: "")
                .put("toolCalls", calls).put("toolResultFor", msg.toolResultFor ?: "").put("toolName", msg.toolName ?: ""))
        }
        arr.put(JSONObject().put("id", session.id).put("title", session.title).put("messages", msgs))
    }
    context.getSharedPreferences("bende", Context.MODE_PRIVATE).edit().putString("sessions", arr.toString()).apply()
}
