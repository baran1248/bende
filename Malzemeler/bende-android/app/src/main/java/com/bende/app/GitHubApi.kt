package com.bende.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// ---------------------------------------------------------------------------
// Veri tipleri
// ---------------------------------------------------------------------------

data class DeviceFlowInfo(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val intervalSeconds: Int,
    val expiresInSeconds: Int
)

data class WorkflowRun(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
    val headBranch: String?,
    val displayTitle: String,
    val createdAt: String,
    val htmlUrl: String?
)

data class GitHubFile(val path: String, val bytes: ByteArray)

data class GitHubRepoConfig(val name: String = "", val isPrivate: Boolean = true, val branch: String = "main")

private class HttpResponse(val code: Int, val bytes: ByteArray, val headers: Map<String, List<String>>) {
    val text: String get() = String(bytes, Charsets.UTF_8)
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

// ---------------------------------------------------------------------------
// Ayarlar: depo adı ve görünürlük
// ---------------------------------------------------------------------------

object GitHubSettings {
    private const val PREFS = "github_settings"

    fun load(context: Context): GitHubRepoConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return GitHubRepoConfig(
            name = prefs.getString("repo_name", "") ?: "",
            isPrivate = prefs.getBoolean("repo_private", true),
            branch = prefs.getString("branch", "main")?.takeIf { it.isNotBlank() } ?: "main"
        )
    }

    fun save(context: Context, config: GitHubRepoConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("repo_name", config.name.trim())
            .putBoolean("repo_private", config.isPrivate)
            .putString("branch", config.branch.trim().ifBlank { "main" })
            .apply()
    }
}

// ---------------------------------------------------------------------------
// Kimlik: PAT (Keystore ile AES-GCM) + cihaz akışı (Device Flow)
// ---------------------------------------------------------------------------

object GitHubAuth {
    private const val PREFS = "github"
    private const val KEY_ALIAS = "bende_github_key_v1"
    private const val AUTH = "https://github.com"

    fun hasToken(context: Context): Boolean = token(context) != null

    fun token(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("pat", null)
            ?.let { encrypted -> runCatching { decrypt(encrypted) }.getOrNull() }

    fun saveToken(context: Context, value: String) {
        val trimmed = value.trim()
        require(trimmed.isNotBlank()) { "GitHub kişisel erişim anahtarı (PAT) boş olamaz." }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("pat", encrypt(trimmed)).apply()
    }

    fun clearToken(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("pat").apply()
    }

    fun clientId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("client_id", null)?.takeIf { it.isNotBlank() }

    fun saveClientId(context: Context, value: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        val trimmed = value.trim()
        if (trimmed.isEmpty()) prefs.remove("client_id") else prefs.putString("client_id", trimmed)
        prefs.apply()
    }

    /** Device Flow adım 1: https://github.com/login/device/code */
    suspend fun startDeviceFlow(clientId: String): DeviceFlowInfo = withContext(Dispatchers.IO) {
        val id = clientId.trim()
        require(id.isNotBlank()) { "GitHub OAuth App istemci kimliğini (client_id) girin." }
        val response = rawRequest(
            token = null,
            url = "$AUTH/login/device/code",
            method = "POST",
            body = formBody(mapOf("client_id" to id, "scope" to "repo workflow")),
            contentType = "application/x-www-form-urlencoded",
            accept = "application/json"
        )
        if (response.code !in 200..299) fail(response)
        val fields = parseFormOrJson(response.text)
        val deviceCode = fields["device_code"]?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("GitHub device_code döndürmedi; istemci kimliğini kontrol edin.")
        val userCode = fields["user_code"]?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("GitHub kullanıcı kodu döndürmedi.")
        val verification = fields["verification_uri"]?.takeIf { it.isNotBlank() }
            ?: fields["verification_url"]?.takeIf { it.isNotBlank() }
            ?: "https://github.com/login/device"
        DeviceFlowInfo(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = verification,
            intervalSeconds = fields["interval"]?.toIntOrNull()?.coerceAtLeast(5) ?: 5,
            expiresInSeconds = fields["expires_in"]?.toIntOrNull()?.coerceIn(60, 1800) ?: 900
        )
    }

    /** Device Flow adım 2: erişim anahtarı için yoklama (poll). Başarıda anahtarı kaydeder. */
    suspend fun completeDeviceFlow(
        context: Context,
        clientId: String,
        info: DeviceFlowInfo,
        onStatus: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val id = clientId.trim()
        require(id.isNotBlank()) { "GitHub OAuth App istemci kimliğini (client_id) girin." }
        var intervalMs = info.intervalSeconds.coerceAtLeast(5) * 1000L
        val deadline = System.currentTimeMillis() + info.expiresInSeconds.coerceIn(60, 1800) * 1000L
        onStatus("GitHub onayı bekleniyor…")
        while (System.currentTimeMillis() < deadline) {
            delay(intervalMs)
            val response = rawRequest(
                token = null,
                url = "$AUTH/login/oauth/access_token",
                method = "POST",
                body = formBody(
                    mapOf(
                        "client_id" to id,
                        "device_code" to info.deviceCode,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"
                    )
                ),
                contentType = "application/x-www-form-urlencoded",
                accept = "application/json"
            )
            if (response.code !in 200..299) fail(response)
            val fields = parseFormOrJson(response.text)
            when (val error = fields["error"]?.takeIf { it.isNotBlank() }) {
                null -> {
                    val token = fields["access_token"]?.takeIf { it.isNotBlank() }
                        ?: throw IllegalStateException("GitHub erişim anahtarı yanıtta bulunamadı.")
                    saveToken(context, token)
                    onStatus("Giriş tamamlandı; anahtar bu cihazda şifrelenerek saklandı.")
                    return@withContext token
                }
                "authorization_pending" -> onStatus("GitHub'da onay bekleniyor…")
                "slow_down" -> {
                    intervalMs += 5_000L
                    onStatus("Çok sık yoklama yapıldı; aralık artırıldı, onay bekleniyor…")
                }
                "expired_token" -> throw IllegalStateException("Cihaz akışı süresi doldu. Yeniden giriş yapın.")
                "access_denied" -> throw IllegalStateException("GitHub erişimi sizin tarafınızdan reddedildi.")
                "unsupported_grant_type" -> throw IllegalStateException("Bu istemci cihaz akışını desteklemiyor. OAuth App ayarlarını kontrol edin.")
                else -> throw IllegalStateException("GitHub cihaz akışı hatası: $error")
            }
        }
        throw IllegalStateException("Cihaz akışı zaman aşımına uğradı. Yeniden deneyin.")
    }

    // -- Android Keystore ile AES-GCM şifreleme (yalnızca bu nesneye ait) --

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
}

// ---------------------------------------------------------------------------
// GitHub REST API
// ---------------------------------------------------------------------------

object GitHubApi {
    private const val API = "https://api.github.com"

    /** GET /user -> giriş yapılmış kullanıcının adı (login). */
    suspend fun user(context: Context): String = withContext(Dispatchers.IO) {
        JSONObject(apiRequest(context, "$API/user").text).optString("login").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("GitHub kullanıcı adı okunamadı.")
    }

    /** Depo var mı? */
    suspend fun repoExists(context: Context, owner: String, repo: String): Boolean = withContext(Dispatchers.IO) {
        val response = rawRequest(requireToken(context), "$API/repos/${segment(owner, "Depo sahibi")}/${segment(repo, "Depo adı")}")
        when (response.code) {
            200 -> true
            404 -> false
            else -> fail(response)
        }
    }

    /** Depo oluşturur; varsa görünürlüğü günceller. Dönen değer: "sahip/depo". */
    suspend fun createOrUpdateRepo(context: Context, name: String, private: Boolean): String = withContext(Dispatchers.IO) {
        val repo = segment(name, "Depo adı")
        val login = user(context)
        val create = rawRequest(
            requireToken(context), "$API/user/repos", "POST",
            JSONObject().put("name", repo).put("private", private).put("has_issues", true).put("has_projects", false).put("has_wiki", false).toString().toByteArray(Charsets.UTF_8)
        )
        if (create.code in 200..299) return@withContext "$login/$repo (yeni depo, ${if (private) "private" else "public"})"
        if (create.code == 422) {
            val patch = rawRequest(
                requireToken(context), "$API/repos/$login/$repo", "PATCH",
                JSONObject().put("private", private).toString().toByteArray(Charsets.UTF_8)
            )
            if (patch.code in 200..299) return@withContext "$login/$repo (depo mevcut, görünürlük: ${if (private) "private" else "public"})"
            fail(patch)
        }
        fail(create)
    }

    /** Tek dosyayı contents API ile iletir (sha kontrolü + PUT base64). */
    suspend fun putFile(context: Context, owner: String, repo: String, path: String, bytes: ByteArray, message: String): String = withContext(Dispatchers.IO) {
        val o = segment(owner, "Depo sahibi")
        val r = segment(repo, "Depo adı")
        val p = filePath(path)
        val token = requireToken(context)
        val existing = rawRequest(token, "$API/repos/$o/$r/contents/$p")
        val sha = when (existing.code) {
            200 -> JSONObject(existing.text).optString("sha").takeIf { it.isNotBlank() }
            404 -> null
            else -> fail(existing)
        }
        val body = JSONObject().put("message", message).put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
        if (sha != null) body.put("sha", sha)
        val response = rawRequest(token, "$API/repos/$o/$r/contents/$p", "PUT", body.toString().toByteArray(Charsets.UTF_8))
        if (response.code !in 200..299) fail(response)
        JSONObject(response.text).optJSONObject("commit")?.optString("sha")?.takeIf { it.isNotBlank() } ?: "ok"
    }

    /** Çoklu dosyayı tree + commit + ref güncellemesiyle iletir. Dönen değer: commit sha. */
    suspend fun pushFiles(context: Context, owner: String, repo: String, message: String, files: List<GitHubFile>, branch: String? = null): String = withContext(Dispatchers.IO) {
        require(files.isNotEmpty()) { "İletilecek dosya bulunamadı." }
        val o = segment(owner, "Depo sahibi")
        val r = segment(repo, "Depo adı")
        val token = requireToken(context)
        val branchName = segment(branch?.takeIf { it.isNotBlank() } ?: GitHubSettings.load(context).branch.ifBlank { "main" }, "Dal adı")

        val entries = JSONArray()
        files.forEach { file ->
            val entry = JSONObject().put("path", filePath(file.path)).put("mode", "100644").put("type", "blob")
            if (isUtf8Text(file.bytes)) entry.put("content", String(file.bytes, Charsets.UTF_8))
            else entry.put("encoding", "base64").put("content", Base64.encodeToString(file.bytes, Base64.NO_WRAP))
            entries.put(entry)
        }

        var commitSha: String? = null
        var treeSha: String? = null
        val refResponse = rawRequest(token, "$API/repos/$o/$r/git/ref/heads/$branchName")
        when {
            refResponse.code in 200..299 -> {
                commitSha = JSONObject(refResponse.text).optJSONObject("object")?.optString("sha")?.takeIf { it.isNotBlank() }
                if (commitSha != null) {
                    val commit = JSONObject(apiRequest(context, "$API/repos/$o/$r/git/commits/$commitSha").text)
                    treeSha = commit.optJSONObject("tree")?.optString("sha")?.takeIf { it.isNotBlank() }
                }
            }
            refResponse.code == 404 -> { /* boş depo ya da yeni dal */ }
            else -> fail(refResponse)
        }

        val treeBody = JSONObject().put("tree", entries)
        if (treeSha != null) treeBody.put("base_tree", treeSha)
        val tree = JSONObject(apiRequest(context, "$API/repos/$o/$r/git/trees", "POST", treeBody).text).optString("sha").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("GitHub ağaç (tree) oluşturulamadı.")

        val commitBody = JSONObject().put("message", message).put("tree", tree)
        if (commitSha != null) commitBody.put("parents", JSONArray().put(commitSha))
        val newCommit = JSONObject(apiRequest(context, "$API/repos/$o/$r/git/commits", "POST", commitBody).text).optString("sha").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("GitHub commit oluşturulamadı.")

        if (commitSha != null) {
            apiRequest(context, "$API/repos/$o/$r/git/refs/heads/$branchName", "PATCH", JSONObject().put("sha", newCommit))
        } else {
            apiRequest(context, "$API/repos/$o/$r/git/refs", "POST", JSONObject().put("ref", "refs/heads/$branchName").put("sha", newCommit))
        }
        newCommit
    }

    /** Dosya depoda var mı? */
    suspend fun fileExists(context: Context, owner: String, repo: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val response = rawRequest(requireToken(context), "$API/repos/${segment(owner, "Depo sahibi")}/${segment(repo, "Depo adı")}/contents/${filePath(path)}")
        when (response.code) {
            200 -> true
            404 -> false
            else -> fail(response)
        }
    }

    /** workflow_dispatch ile iş akışı tetikler. */
    suspend fun triggerWorkflow(context: Context, owner: String, repo: String, workflowFile: String, ref: String): String = withContext(Dispatchers.IO) {
        val o = segment(owner, "Depo sahibi")
        val r = segment(repo, "Depo adı")
        val file = filePath(workflowFile)
        val reference = segment(ref, "Dal adı")
        val response = rawRequest(
            requireToken(context), "$API/repos/$o/$r/actions/workflows/$file/dispatches", "POST",
            JSONObject().put("ref", reference).toString().toByteArray(Charsets.UTF_8)
        )
        if (response.code !in 200..299) fail(response)
        "Derleme başlatıldı: $o/$r ($reference dalı, $file)."
    }

    /** İş akışı çalıştırmalarını listeler. */
    suspend fun listRuns(context: Context, owner: String, repo: String, limit: Int = 15): List<WorkflowRun> = withContext(Dispatchers.IO) {
        val o = segment(owner, "Depo sahibi")
        val r = segment(repo, "Depo adı")
        val response = apiRequest(context, "$API/repos/$o/$r/actions/runs?per_page=${limit.coerceIn(1, 50)}")
        val array = JSONObject(response.text).optJSONArray("workflow_runs") ?: JSONArray()
        (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { parseRun(it) } }
    }

    /** Tek çalıştırmanın durumu. */
    suspend fun getRun(context: Context, owner: String, repo: String, runId: Long): WorkflowRun = withContext(Dispatchers.IO) {
        val o = segment(owner, "Depo sahibi")
        val r = segment(repo, "Depo adı")
        require(runId > 0) { "Geçerli bir çalıştırma kimliği (run_id) gerekli." }
        parseRun(JSONObject(apiRequest(context, "$API/repos/$o/$r/actions/runs/$runId").text))
    }

    /** Çalıştırma loglarını toplar; en fazla ~200 KB (sondan kırpılmış). */
    suspend fun getRunLogs(context: Context, owner: String, repo: String, runId: Long): String = withContext(Dispatchers.IO) {
        val o = segment(owner, "Depo sahibi")
        val r = segment(repo, "Depo adı")
        require(runId > 0) { "Geçerli bir çalıştırma kimliği (run_id) gerekli." }
        val token = requireToken(context)
        val jobsResponse = apiRequest(context, "$API/repos/$o/$r/actions/runs/$runId/jobs?per_page=20")
        val jobs = JSONObject(jobsResponse.text).optJSONArray("jobs") ?: JSONArray()
        if (jobs.length() == 0) return@withContext "Bu çalıştırmaya ait iş (job) yok; log henüz oluşmamış olabilir."
        val builder = StringBuilder()
        for (i in 0 until jobs.length()) {
            val job = jobs.optJSONObject(i) ?: continue
            val jobId = job.optLong("id")
            val jobName = job.optString("name").ifBlank { "İş $jobId" }
            builder.append("== ").append(jobName).append(" ==\n")
            val logResponse = rawRequest(token, "$API/repos/$o/$r/jobs/$jobId/logs", accept = "*/*", maxBytes = 6 * 1024 * 1024, readTimeoutMs = 90_000)
            if (logResponse.code in 200..299) builder.append(logResponse.text.trimEnd()).append('\n')
            else builder.append("[Log alınamadı: HTTP ").append(logResponse.code).append("]\n")
            builder.append('\n')
            if (builder.length > 2 * 1024 * 1024) break
        }
        builder.toString().takeLast(200_000).ifBlank { "Log bulunamadı." }
    }

    /** En son başarılı çalıştırmadan APK içeren artifact'ı indirir (zip içinden .apk). Sınır: 60 MB. */
    suspend fun downloadLatestArtifactApk(context: Context, owner: String, repo: String): ByteArray = withContext(Dispatchers.IO) {
        val o = segment(owner, "Depo sahibi")
        val r = segment(repo, "Depo adı")
        val token = requireToken(context)
        val runsJson = JSONObject(apiRequest(context, "$API/repos/$o/$r/actions/runs?per_page=15").text).optJSONArray("workflow_runs") ?: JSONArray()
        val candidates = (0 until runsJson.length()).mapNotNull { i -> runsJson.optJSONObject(i) }
            .filter { it.optString("status") == "completed" && it.optString("conclusion") == "success" }
        if (candidates.isEmpty()) throw IllegalStateException("Başarılı tamamlanmış derleme bulunamadı. Önce derlemeyi başlatın.")
        var inspected = 0
        for (run in candidates) {
            if (inspected >= 5) break
            val runId = run.optLong("id")
            val artifactsJson = JSONObject(apiRequest(context, "$API/repos/$o/$r/actions/runs/$runId/artifacts").text).optJSONArray("artifacts") ?: JSONArray()
            if (artifactsJson.length() == 0) continue
            inspected++
            var chosen: JSONObject? = null
            for (j in 0 until artifactsJson.length()) {
                val artifact = artifactsJson.optJSONObject(j) ?: continue
                if (artifact.optBoolean("expired", false)) continue
                if (artifact.optString("name", "").contains("apk", ignoreCase = true)) { chosen = artifact; break }
                if (chosen == null) chosen = artifact
            }
            val artifact = chosen ?: continue
            val artifactId = artifact.optLong("id")
            val zip = rawRequest(token, "$API/repos/$o/$r/actions/artifacts/$artifactId/zip", accept = "application/vnd.github+json", maxBytes = 60 * 1024 * 1024, readTimeoutMs = 120_000)
            if (zip.code !in 200..299) fail(zip)
            extractApk(zip.bytes)?.let { return@withContext it }
        }
        throw IllegalStateException("İndirilen artifact içinde .apk dosyası bulunamadı.")
    }

    // -- yardımcılar --

    private fun requireToken(context: Context): String =
        GitHubAuth.token(context) ?: throw IllegalStateException("GitHub anahtarı girilmemiş. GitHub ekranından PAT ile veya cihaz akışıyla giriş yapın.")

    private fun apiRequest(context: Context, url: String, method: String = "GET", body: JSONObject? = null): HttpResponse {
        val response = rawRequest(
            token = requireToken(context),
            url = url,
            method = method,
            body = body?.toString()?.toByteArray(Charsets.UTF_8)
        )
        if (response.code !in 200..299) fail(response)
        return response
    }

    private fun parseRun(json: JSONObject): WorkflowRun = WorkflowRun(
        id = json.optLong("id"),
        name = json.optString("name").ifBlank { "İsimsiz iş akışı" },
        status = json.optString("status").ifBlank { "bilinmiyor" },
        conclusion = json.optString("conclusion").takeIf { it.isNotBlank() },
        headBranch = json.optString("head_branch").takeIf { it.isNotBlank() },
        displayTitle = json.optString("display_title").ifBlank { json.optString("name") },
        createdAt = json.optString("created_at"),
        htmlUrl = json.optString("html_url").takeIf { it.isNotBlank() }
    )

    private fun extractApk(zipBytes: ByteArray): ByteArray? {
        var best: ByteArray? = null
        var bestScore = Long.MIN_VALUE
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                    val bytes = readBounded(zip, 60 * 1024 * 1024)
                    // debug APK tercih edilir; eşitlikte büyük dosya kazanır.
                    val score = (if (entry.name.endsWith("debug.apk", ignoreCase = true)) 1_000_000_000L else 0L) + bytes.size
                    if (score > bestScore) {
                        bestScore = score
                        best = bytes
                    }
                }
            }
        }
        return best
    }
}

// ---------------------------------------------------------------------------
// Ortak HTTP yardımcıları
// ---------------------------------------------------------------------------

private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

private fun rawRequest(
    token: String?,
    url: String,
    method: String = "GET",
    body: ByteArray? = null,
    contentType: String = "application/json; charset=utf-8",
    accept: String = "application/vnd.github+json",
    maxBytes: Int = MAX_RESPONSE_BYTES,
    readTimeoutMs: Int = 60_000
): HttpResponse {
    var currentUrl = url
    var currentMethod = method
    var currentBody = body
    var currentToken = token
    for (attempt in 0 until 5) {
        val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = currentMethod
            connectTimeout = 20_000
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "Bende-Android")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (currentToken != null) setRequestProperty("Authorization", "Bearer $currentToken")
            if (currentBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", contentType)
            }
        }
        try {
            currentBody?.let { payload -> connection.outputStream.use { it.write(payload) } }
            val code = connection.responseCode
            if (code in 301..308 && code != 306) {
                val location = connection.getHeaderField("Location")
                    ?: throw IllegalStateException("GitHub yönlendirme adresi vermedi.")
                val previous = URL(currentUrl)
                val next = URL(previous, location)
                // Farklı bir ana bilgisayara yönlendirmede yetki başlığını asla taşıma.
                if (!next.host.equals(previous.host, ignoreCase = true)) currentToken = null
                if (code == 303 || (code in 301..302 && currentMethod == "POST")) {
                    currentMethod = "GET"
                    currentBody = null
                }
                currentUrl = next.toString()
                continue
            }
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = readBounded(stream, maxBytes)
            val headers = mutableMapOf<String, List<String>>()
            connection.headerFields?.forEach { (key, values) -> if (key != null) headers[key] = values }
            return HttpResponse(code, bytes, headers)
        } finally {
            connection.disconnect()
        }
    }
    throw IllegalStateException("GitHub'dan yanıt alınamadı; çok fazla yönlendirme yapıldı.")
}

private fun readBounded(stream: java.io.InputStream?, maxBytes: Int): ByteArray {
    stream ?: return ByteArray(0)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    stream.use { input ->
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= maxBytes) { "GitHub yanıtı ${maxBytes / (1024 * 1024)} MB sınırını aşıyor." }
            output.write(buffer, 0, count)
        }
    }
    return output.toByteArray()
}

private fun fail(response: HttpResponse): Nothing {
    val detail = runCatching {
        val json = JSONObject(response.text)
        json.optString("message").takeIf { it.isNotBlank() } ?: json.optString("error").takeIf { it.isNotBlank() }
    }.getOrNull()
    val base = when (response.code) {
        401 -> "GitHub oturumu geçersiz (401). Kişisel erişim anahtarınızı (PAT) kontrol edin veya yeniden giriş yapın."
        403 -> "GitHub isteği reddetti (403). Anahtar yetkisi yetersiz olabilir veya API hız sınırı doldurulmuş olabilir."
        404 -> "GitHub'da kayıt bulunamadı (404). Depo sahibi, depo adı veya iş akışı dosyası hatalı olabilir."
        409 -> "GitHub çakışma bildirdi (409). Kaynak zaten var olabilir ya da işlem çakışıyor."
        422 -> "GitHub isteği doğrulayamadı (422). Gönderilen bilgiler eksik veya geçersiz."
        429 -> "GitHub istek sınırı aşıldı (429). Bir süre bekleyip yeniden deneyin."
        else -> "GitHub HTTP ${response.code} döndürdü."
    }
    val remaining = response.header("X-RateLimit-Remaining")
    val rate = remaining?.let { " Kalan API istek hakkı: $it." } ?: ""
    val message = detail?.let { " ($it)" } ?: ""
    throw IllegalStateException(base + message + rate)
}

private fun parseFormOrJson(text: String): Map<String, String> {
    val trimmed = text.trim()
    if (trimmed.startsWith("{")) {
        val json = JSONObject(trimmed)
        return json.keys().asSequence().associateWith { key -> json.optString(key) }
    }
    return trimmed.split('&').mapNotNull { part ->
        val index = part.indexOf('=')
        if (index <= 0) null else part.substring(0, index) to urlDecode(part.substring(index + 1))
    }.toMap()
}

private fun formBody(fields: Map<String, String>): ByteArray =
    fields.entries.joinToString("&") { "${urlEncode(it.key)}=${urlEncode(it.value)}" }.toByteArray(Charsets.UTF_8)

private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun urlDecode(value: String): String = java.net.URLDecoder.decode(value, "UTF-8")

private fun segment(value: String, label: String): String {
    val trimmed = value.trim()
    require(trimmed.isNotBlank() && trimmed.matches(Regex("[A-Za-z0-9._-]+"))) { "$label geçerli değil: \"$value\"" }
    return trimmed
}

private fun filePath(value: String): String {
    val path = value.trim().replace('\\', '/')
    val parts = path.split('/')
    require(path.isNotBlank() && !path.startsWith('/') && parts.size <= 32) { "Geçersiz dosya yolu: \"$value\"" }
    require(parts.all { it.isNotBlank() && it != "." && it != ".." && it.none { c -> c.isISOControl() || c == ':' } }) { "Geçersiz dosya yolu: \"$value\"" }
    return parts.joinToString("/")
}

private fun isUtf8Text(bytes: ByteArray): Boolean {
    if (bytes.any { it == 0.toByte() }) return false
    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    return runCatching { decoder.decode(ByteBuffer.wrap(bytes)); true }.getOrDefault(false)
}