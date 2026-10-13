package com.bende.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val GhError = Color(0xFFB42318)
private val GhSoft = Color(0xFFF7F8FA)
private val GhMuted = Color(0xFF667085)
private val GhLine = Color(0xFFE5E7EB)

/**
 * GitHub hesabı, depo ayarları ve bulut derlemesi tek pencerede.
 * Çağıran kod: GitHubHubDialog(onDismiss = { ... })
 */
@Composable
fun GitHubHubDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pat by remember { mutableStateOf("") }
    var patVisible by remember { mutableStateOf(false) }
    var clientId by remember { mutableStateOf(GitHubAuth.clientId(context).orEmpty()) }
    var tokenSaved by remember { mutableStateOf(GitHubAuth.hasToken(context)) }
    var username by remember { mutableStateOf<String?>(null) }

    var repoName by remember { mutableStateOf(GitHubSettings.load(context).name) }
    var repoPrivate by remember { mutableStateOf(GitHubSettings.load(context).isPrivate) }

    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    var device by remember { mutableStateOf<DeviceFlowInfo?>(null) }
    var deviceStatus by remember { mutableStateOf<String?>(null) }
    var deviceJob by remember { mutableStateOf<Job?>(null) }

    var runs by remember { mutableStateOf<List<WorkflowRun>>(emptyList()) }
    var runsLoading by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf<String?>(null) }
    var logLoading by remember { mutableStateOf(false) }
    var downloadState by remember { mutableStateOf<String?>(null) }

    fun refreshUser() {
        scope.launch {
            try {
                val login = withContext(Dispatchers.IO) { GitHubApi.user(context) }
                username = login
                error = null
                status = "Hesap doğrulandı: $login"
            } catch (e: Exception) {
                error = e.message ?: "GitHub hesap bilgisi alınamadı."
            }
        }
    }

    fun savePat() {
        val value = pat.trim()
        if (value.isEmpty()) {
            error = "Kişisel erişim anahtarı (PAT) boş olamaz."
            return
        }
        try {
            GitHubAuth.saveToken(context, value)
            tokenSaved = true
            pat = ""
            status = "Anahtar kaydedildi (Android Keystore ile şifreli)."
            error = null
            refreshUser()
        } catch (e: Exception) {
            error = e.message ?: "Anahtar kaydedilemedi."
        }
    }

    fun clearToken() {
        GitHubAuth.clearToken(context)
        tokenSaved = false
        username = null
        runs = emptyList()
        logText = null
        status = "Bu cihazdaki GitHub anahtarı silindi."
        error = null
    }

    fun saveClientId() {
        GitHubAuth.saveClientId(context, clientId)
        status = "İstemci kimliği kaydedildi."
        error = null
    }

    fun startDeviceFlow() {
        val id = clientId.trim()
        if (id.isEmpty()) {
            error = "Cihaz akışı için GitHub OAuth App istemci kimliğini (client_id) girin."
            return
        }
        GitHubAuth.saveClientId(context, id)
        busy = true
        scope.launch {
            try {
                val info = withContext(Dispatchers.IO) { GitHubAuth.startDeviceFlow(id) }
                device = info
                deviceStatus = "Tarayıcıda ${info.verificationUri} adresine gidin ve şu kodu girin:"
                error = null
                deviceJob = scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            GitHubAuth.completeDeviceFlow(context, id, info) { message -> deviceStatus = message }
                        }
                        tokenSaved = true
                        device = null
                        deviceStatus = null
                        status = "Cihaz akışıyla giriş yapıldı."
                        refreshUser()
                    } catch (e: Exception) {
                        error = e.message ?: "Cihaz akışı tamamlanamadı."
                        deviceStatus = null
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: "Cihaz akışı başlatılamadı."
            } finally {
                busy = false
            }
        }
    }

    fun cancelDeviceFlow() {
        deviceJob?.cancel()
        deviceJob = null
        device = null
        deviceStatus = null
    }

    fun saveRepoSettings(): Boolean {
        val name = repoName.trim()
        if (name.isEmpty()) {
            error = "Depo adı girin."
            return false
        }
        GitHubSettings.save(context, GitHubRepoConfig(name, repoPrivate, GitHubSettings.load(context).branch))
        status = "Depo ayarları kaydedildi: $name (${if (repoPrivate) "private" else "public"})"
        error = null
        return true
    }

    fun pushProject() {
        if (!saveRepoSettings()) return
        busy = true
        status = "Proje GitHub'a iletiliyor…"
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    BuildTools.execute(context, ToolInvocation("ui-push", "github_push_project", JSONObject()))
                }
                status = result
                error = null
            } catch (e: Exception) {
                error = e.message ?: "Proje iletilemedi."
            } finally {
                busy = false
            }
        }
    }

    fun loadRuns() {
        val name = repoName.trim()
        if (name.isEmpty()) {
            error = "Depo adı girin."
            return
        }
        runsLoading = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val owner = GitHubApi.user(context)
                    owner to GitHubApi.listRuns(context, owner, name)
                }
                username = result.first
                runs = result.second
                error = null
                status = if (runs.isEmpty()) "$name için henüz derleme çalışması yok." else "${runs.size} derleme çalışması bulundu."
            } catch (e: Exception) {
                error = e.message ?: "Çalışma listesi alınamadı."
            } finally {
                runsLoading = false
            }
        }
    }

    fun startBuild() {
        val name = repoName.trim()
        if (name.isEmpty()) {
            error = "Depo adı girin."
            return
        }
        busy = true
        status = "Derleme başlatılıyor…"
        scope.launch {
            try {
                val message = withContext(Dispatchers.IO) {
                    val owner = GitHubApi.user(context)
                    GitHubApi.triggerWorkflow(context, owner, name, BuildTools.WORKFLOW_PATH, GitHubSettings.load(context).branch)
                }
                status = message
                error = null
                loadRuns()
            } catch (e: Exception) {
                error = e.message ?: "Derleme başlatılamadı."
            } finally {
                busy = false
            }
        }
    }

    fun loadLog(run: WorkflowRun?) {
        val name = repoName.trim()
        if (name.isEmpty()) {
            error = "Depo adı girin."
            return
        }
        logLoading = true
        logText = null
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    val owner = GitHubApi.user(context)
                    val runId = run?.id
                        ?: GitHubApi.listRuns(context, owner, name).firstOrNull()?.id
                        ?: throw IllegalStateException("Henüz derleme çalışması yok.")
                    val target = GitHubApi.getRun(context, owner, name, runId)
                    val logs = GitHubApi.getRunLogs(context, owner, name, runId)
                    "Run #${target.id} — ${statusText(target)} — ${target.displayTitle}\n\n" + logs.takeLast(16_000)
                }
                logText = text
                error = null
            } catch (e: Exception) {
                error = e.message ?: "Log alınamadı."
            } finally {
                logLoading = false
            }
        }
    }

    fun downloadApk() {
        val name = repoName.trim()
        if (name.isEmpty()) {
            error = "Depo adı girin."
            return
        }
        busy = true
        downloadState = "APK indiriliyor…"
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val owner = GitHubApi.user(context)
                    val bytes = GitHubApi.downloadLatestArtifactApk(context, owner, name)
                    val fileName = "bende-$name-debug.apk"
                    val uri = BuildTools.saveApkToDownloads(context, fileName, bytes)
                    Triple(fileName, uri, bytes.size)
                }
                downloadState = "APK kaydedildi: ${result.first} (${result.third / 1024} KB)\nKonum: İndirilenler klasörü\n${result.second}"
                error = null
            } catch (e: Exception) {
                downloadState = null
                error = e.message ?: "APK indirilemedi."
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (tokenSaved) {
            try {
                username = withContext(Dispatchers.IO) { GitHubApi.user(context) }
            } catch (e: Exception) {
                error = e.message ?: "GitHub hesap bilgisi alınamadı."
            }
        }
    }

    AlertDialog(
        onDismissRequest = { cancelDeviceFlow(); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        containerColor = Color.White,
        title = { Text("GitHub ve bulut derlemesi") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 580.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {

                // ---------- (a) Hesap ----------
                Text("HESAP", color = GhMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text("Kişisel erişim anahtarı (PAT) bu cihazda Android Keystore ile şifrelenerek saklanır; hiçbir yere düz metin olarak yazılmaz.", color = GhMuted, fontSize = 11.sp, lineHeight = 16.sp)
                OutlinedTextField(
                    value = pat,
                    onValueChange = { pat = it },
                    label = { Text("Kişisel erişim anahtarı (PAT)") },
                    singleLine = true,
                    visualTransformation = if (patVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(if (patVisible) "Görünür" else "Gizli · scope: repo, workflow") }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { patVisible = !patVisible }) { Text(if (patVisible) "Gizle" else "Göster") }
                    TextButton(enabled = !busy, onClick = { savePat() }) { Text("Anahtarı kaydet") }
                    TextButton(enabled = tokenSaved && !busy, onClick = { clearToken() }) { Text("Saklı anahtarı sil", color = if (tokenSaved && !busy) GhError else GhMuted) }
                }
                Text(
                    if (tokenSaved) "Hesap: ${username ?: "doğrulanıyor…"}" else "Hesap bağlı değil.",
                    color = if (tokenSaved) MaterialTheme.colorScheme.primary else GhMuted,
                    fontSize = 12.sp
                )
                OutlinedTextField(
                    value = clientId,
                    onValueChange = { clientId = it },
                    label = { Text("OAuth App istemci kimliği (client_id)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("İsteğe bağlı: cihaz akışıyla giriş için gerekli") }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = !busy, onClick = { saveClientId() }) { Text("Kimliği kaydet") }
                    TextButton(enabled = !busy, onClick = { startDeviceFlow() }) { Text("Cihaz akışıyla giriş") }
                }
                val currentDevice = device
                if (currentDevice != null) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(GhSoft).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Cihaz akışı", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Text("1) Tarayıcıda açın: ${currentDevice.verificationUri}", fontSize = 12.sp)
                        Text("2) Kodu girin:", fontSize = 12.sp)
                        Text(currentDevice.userCode, fontFamily = FontFamily.Monospace, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        deviceStatus?.let { Text(it, color = GhMuted, fontSize = 11.sp) }
                        TextButton(onClick = { cancelDeviceFlow() }) { Text("Akışı iptal et") }
                    }
                }

                HorizontalDivider(color = GhLine)

                // ---------- (b) Depo ----------
                Text("DEPO", color = GhMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = repoName,
                    onValueChange = { repoName = it },
                    label = { Text("Depo adı (repository)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Sahibi giriş yapılan hesap. Depo yoksa iletim sırasında oluşturulur.") }
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Private depo", Modifier.weight(1f), fontSize = 13.sp)
                    Switch(checked = repoPrivate, onCheckedChange = { repoPrivate = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = !busy, onClick = { saveRepoSettings() }) { Text("Ayarları kaydet") }
                    TextButton(enabled = !busy, onClick = { pushProject() }) { Text("Projeyi GitHub'a ilet") }
                }

                HorizontalDivider(color = GhLine)

                // ---------- (c) Derleme ----------
                Text("BULUT DERLEMESİ", color = GhMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = !busy, onClick = { startBuild() }) { Text("Derlemeyi başlat") }
                    TextButton(enabled = !runsLoading, onClick = { loadRuns() }) { Text("Durumu yenile") }
                    TextButton(enabled = !busy, onClick = { downloadApk() }) { Text("APK'yı indir") }
                    if (busy || runsLoading || logLoading) CircularProgressIndicator(Modifier.padding(start = 4.dp), strokeWidth = 2.dp)
                }
                if (runs.isEmpty()) {
                    Text("Henüz derleme çalışması yok. \"Derlemeyi başlat\" ile ilk bulut derlemesini çalıştırabilirsin.", color = GhMuted, fontSize = 11.sp, lineHeight = 16.sp)
                } else {
                    runs.take(10).forEach { run ->
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(GhSoft).padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text("#${run.id} · ${run.displayTitle}", fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                            Text("${statusText(run)} · ${run.headBranch ?: "-"} · ${run.createdAt}", fontSize = 11.sp, color = GhMuted)
                            TextButton(onClick = { loadLog(run) }, enabled = !logLoading) { Text("Logu göster") }
                        }
                    }
                }

                Text("LOG", color = GhMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                when {
                    logLoading -> Text("Log yükleniyor…", color = GhMuted, fontSize = 11.sp)
                    logText != null -> SelectionContainer {
                        Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(10.dp)).background(GhSoft).padding(10.dp).verticalScroll(rememberScrollState())) {
                            Text(logText ?: "", fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 15.sp)
                        }
                    }
                    else -> Text("Görüntülenecek log yok. Bir çalışmanın \"Logu göster\" düğmesine dokunun.", color = GhMuted, fontSize = 11.sp)
                }
                downloadState?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, lineHeight = 16.sp)
                }

                HorizontalDivider(color = GhLine)

                // ---------- (d) Durum ----------
                status?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, lineHeight = 17.sp) }
                error?.let { Text("Hata: $it", color = GhError, fontSize = 12.sp, lineHeight = 17.sp) }
                Text("Ağ işlemleri arka planda çalışır; pencere açık kalabilir. GitHub Actions kullanmak için depoda .github/workflows/bende-android.yml bulunmalı (iletim sırasında otomatik eklenir).", color = GhMuted, fontSize = 10.sp, lineHeight = 15.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { cancelDeviceFlow(); onDismiss() }) { Text("Kapat") }
        }
    )
}

private fun statusText(run: WorkflowRun): String = when (run.status) {
    "queued" -> "Sırada"
    "in_progress" -> "Çalışıyor"
    "waiting" -> "Bekliyor"
    "completed" -> when (run.conclusion) {
        "success" -> "Başarılı"
        "failure" -> "Başarısız"
        "cancelled" -> "İptal edildi"
        "timed_out" -> "Zaman aşımı"
        "neutral" -> "Nötr"
        "skipped" -> "Atlandı"
        "action_required" -> "Eylem gerekli"
        else -> "Tamamlandı"
    }
    else -> run.status
}