package com.bende.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Ajanın onay bekleyen tarayıcı eylemi.
 * Ekran [BrowserSession.pendingApproval] üzerinden bu isteği gözlemler ve kararı
 * [BrowserSession.decideApproval] ile iletir.
 */
data class BrowserApprovalRequest(
    val invocation: ToolInvocation,
    val decision: CompletableDeferred<Boolean>,
    val targetSummary: String = "",
    val actionLabel: String = ""
)

/**
 * Uygulama içi tarayıcının tek durum kaynağı.
 *
 * - WebView örneği buraya [attach] ile bağlanır, [detach] ile çıkarılır.
 * - Ajan araçları komutları [CompletableDeferred] tabanlı bir kuyruk üzerinden gönderir;
 *   komutlar UI thread'de `evaluateJavascript` / `loadUrl` ile çalışır ve sonuçlar
 *   [COMMAND_TIMEOUT_MS] (15 sn) içinde beklenir.
 * - "Devredildi mi" bayrağı [handedOff] burada tutulur; devir kapalıyken ajan araçları çalışmaz.
 */
object BrowserSession {

    /** Bir tarayıcı komutunun en fazla bekleme süresi. */
    const val COMMAND_TIMEOUT_MS = 15_000L

    // ---------------------------------------------------------------- durum
    /** Yüklü olan (ya da son bilinen) sayfa adresi. */
    var url by mutableStateOf("")
        private set

    /** Sayfa başlığı. */
    var title by mutableStateOf("")
        private set

    /** Son DOM özeti (sayfa yüklenince otomatik yenilenir). */
    var domSummary by mutableStateOf<String?>(null)
        private set

    /** Kullanıcı sayfayı ajana devretti mi? */
    var handedOff by mutableStateOf(false)
        private set

    /** Ajan aracının son komut sonucu (özet). */
    var lastCommandResult by mutableStateOf<String?>(null)
        private set

    /** Sayfa yükleniyor mu? */
    var loading by mutableStateOf(false)
        private set

    /** Yüzde 0-100 yükleme ilerlemesi. */
    var progress by mutableStateOf(0)
        private set

    /** Son yükleme hatası (Türkçe). */
    var lastError by mutableStateOf<String?>(null)
        private set

    /** Geçmişte geri gidilebilir mi? */
    var canGoBack by mutableStateOf(false)
        private set

    /** Geçmişte ileri gidilebilir mi? */
    var canGoForward by mutableStateOf(false)
        private set

    /** Ajanın bekleyen onay isteği (yoksa null). */
    var pendingApproval by mutableStateOf<BrowserApprovalRequest?>(null)
        private set

    /** WebView bağlı mı? */
    var attached by mutableStateOf(false)
        private set

    // ------------------------------------------------------------ iç alanlar
    private var webView: WebView? = null
    private var navigationResult: CompletableDeferred<String>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = Channel<BrowserCommand>(Channel.UNLIMITED)
    private var pumpJob: Job? = null

    private sealed class BrowserCommand {
        abstract val result: CompletableDeferred<String>

        class Navigate(val target: String, override val result: CompletableDeferred<String> = CompletableDeferred()) : BrowserCommand()

        class Evaluate(val script: String, override val result: CompletableDeferred<String> = CompletableDeferred()) : BrowserCommand()
    }

    // ------------------------------------------------------- yaşam döngüsü

    /**
     * WebView'i bağlar: güvenli ayarları kurar, durumu güncelleyen
     * WebViewClient / WebChromeClient takar ve komut kuyruğunu başlatır.
     * UI thread'den çağrılmalıdır.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun attach(webView: WebView) {
        this.webView = webView
        attached = true
        lastError = null
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            safeBrowsingEnabled = true
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val scheme = request?.url?.scheme?.lowercase()
                return !(scheme == "http" || scheme == "https")
            }

            override fun onPageStarted(view: WebView?, startedUrl: String?, favicon: Bitmap?) {
                loading = true
                progress = 0
                lastError = null
                startedUrl?.let { url = it }
            }

            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                val web = view ?: return
                loading = false
                progress = 100
                url = finishedUrl ?: web.url.orEmpty()
                title = web.title.orEmpty()
                canGoBack = web.canGoBack()
                canGoForward = web.canGoForward()
                navigationResult?.let { pending ->
                    if (!pending.isCompleted) pending.complete(url)
                    navigationResult = null
                }
                web.evaluateJavascript(BrowserJs.pageSummary()) { raw ->
                    domSummary = decodeText(raw).take(4_000)
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame != true) return
                loading = false
                val message = "Sayfa yüklenemedi. Bağlantınızı ve adresi kontrol edin. (${error?.description ?: "bilinmeyen hata"})"
                lastError = message
                navigationResult?.let { pending ->
                    if (!pending.isCompleted) pending.completeExceptionally(IllegalStateException(message))
                    navigationResult = null
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress = newProgress
                loading = newProgress in 0..99
            }

            override fun onReceivedTitle(view: WebView?, pageTitle: String?) {
                title = pageTitle.orEmpty()
            }
        }
        ensurePump()
    }

    /**
     * WebView'i çıkarır: bekleyen komutlar ve onaylar güvenli biçimde kapatılır.
     * UI thread'den çağrılmalıdır (ör. DisposableEffect onDispose).
     */
    fun detach() {
        val web = webView
        webView = null
        attached = false
        loading = false
        progress = 0
        web?.stopLoading()
        failNavigation("Tarayıcı ekranı kapatıldı.")
        drainQueue("Tarayıcı ekranı kapatıldı.")
        pumpJob?.cancel()
        pumpJob = null
        pendingApproval?.let { if (!it.decision.isCompleted) it.decision.complete(false) }
        pendingApproval = null
    }

    // ------------------------------------------------------------ devir

    /** Kullanıcı sayfayı ajana devreder. */
    fun handOff() {
        handedOff = true
        lastCommandResult = "Kullanıcı sayfayı ajana devretti."
    }

    /** Devir geri alınır: ajan komutları artık çalışmaz, bekleyen onay reddedilir. */
    fun revokeHandOff() {
        handedOff = false
        pendingApproval?.let { if (!it.decision.isCompleted) it.decision.complete(false) }
        pendingApproval = null
        lastCommandResult = "Devir geri alındı; ajan sayfayı yönetemiyor."
    }

    // ------------------------------------------------------------ gezinme (UI)

    /** Adres çubuğundan aç; geçerli değilse hata durumuna yazar. */
    fun navigate(rawUrl: String) {
        scope.launch {
            try {
                loadPage(rawUrl)
            } catch (error: Throwable) {
                lastError = error.message ?: "Sayfa açılamadı."
            }
        }
    }

    fun reload() {
        webView?.reload()
    }

    fun goBack() {
        val web = webView ?: return
        if (web.canGoBack()) web.goBack()
    }

    fun goForward() {
        val web = webView ?: return
        if (web.canGoForward()) web.goForward()
    }

    fun stopLoading() {
        webView?.stopLoading()
    }

    fun clearError() {
        lastError = null
    }

    // ------------------------------------------------------------ ajan komutları

    /**
     * Sayfayı açar ve yüklenmesini bekler; yüklemenin bittiği (yönlendirme sonrası) adresi döndürür.
     * @throws IllegalArgumentException geçersiz/güvenli olmayan adres
     */
    suspend fun loadPage(rawUrl: String): String {
        val target = normalizeUrl(rawUrl)
        withContext(Dispatchers.Main) { lastError = null }
        return submit(BrowserCommand.Navigate(target))
    }

    /** JavaScript'i UI thread'de çalıştırır ve JSON kodlu ham sonucu döndürür. */
    suspend fun evaluate(script: String): String {
        require(script.isNotBlank()) { "Çalıştırılacak komut boş olamaz." }
        return submit(BrowserCommand.Evaluate(script))
    }

    /**
     * Kullanıcıdan onay ister; [BrowserApprovalRequest] üzerinden ekran diyalogu açılır.
     * Karar gelene kadar askıda kalır.
     */
    suspend fun requestApproval(invocation: ToolInvocation, targetSummary: String, actionLabel: String): Boolean {
        val request = BrowserApprovalRequest(invocation, CompletableDeferred(), targetSummary, actionLabel)
        withContext(Dispatchers.Main) {
            pendingApproval?.let { if (!it.decision.isCompleted) it.decision.complete(false) }
            pendingApproval = request
        }
        return try {
            request.decision.await()
        } finally {
            withContext(Dispatchers.Main + NonCancellable) {
                if (pendingApproval === request) pendingApproval = null
            }
        }
    }

    /** Ekranın onay diyaloğundan gelen karar. */
    fun decideApproval(approved: Boolean) {
        val request = pendingApproval ?: return
        pendingApproval = null
        if (!request.decision.isCompleted) request.decision.complete(approved)
    }

    /** Son araç sonucunu özet olarak saklar. */
    suspend fun noteResult(text: String) {
        withContext(Dispatchers.Main) { lastCommandResult = text.take(400) }
    }

    /** Son DOM özetini günceller. */
    suspend fun updateDomSummary(text: String) {
        withContext(Dispatchers.Main) { domSummary = text.take(4_000) }
    }

    // ------------------------------------------------------------ yardımcılar

    /**
     * Adresi doğrular ve güvenli biçime getirir.
     * Yalnızca http/https kabul edilir; `javascript:`, `file:`, `content:`, `data:` gibi
     * şemalar reddedilir. Şemasız girilen adreslere `https://` eklenir.
     */
    fun normalizeUrl(rawInput: String): String {
        val raw = rawInput.trim()
        require(raw.isNotBlank()) { "Adres boş olamaz." }
        require(raw.length <= 2_048) { "Adres 2.048 karakter sınırını aşıyor." }
        require(!raw.any { it.isWhitespace() }) { "Adres boşluk içeremez." }
        val scheme = SCHEME_PATTERN.find(raw)?.value?.dropLast(1)?.lowercase()
        val candidate = when {
            scheme == "http" || scheme == "https" -> raw
            scheme != null && !HOST_LIKE.matches(raw) ->
                throw IllegalArgumentException("Güvenlik nedeniyle yalnızca http/https adresleri açılabilir. \"$scheme:\" adresi reddedildi.")
            else -> "https://$raw"
        }
        val uri = Uri.parse(candidate)
        require(uri.scheme == "http" || uri.scheme == "https") { "Yalnızca http/https adresleri açılabilir." }
        require(!uri.host.isNullOrBlank()) { "Geçerli bir alan adı gerekli." }
        return candidate
    }

    /** `evaluateJavascript` sonucunu (JSON kodlu metin) çözümler. */
    fun decodeText(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        val value = runCatching { JSONTokener(raw).nextValue() }.getOrNull()
        return value as? String ?: raw
    }

    /** JSON döndüren komutların sonucunu nesneye çevirir; çözülemezse boş nesne döner. */
    fun decodeObject(raw: String?): JSONObject {
        val text = decodeText(raw)
        return runCatching { JSONObject(text) }.getOrNull() ?: JSONObject()
    }

    // ------------------------------------------------------------ komut kuyruğu

    private suspend fun submit(command: BrowserCommand): String {
        if (webView == null) throw IllegalStateException("Tarayıcı ekranı açık değil. Tarayıcı sekmesini açın.")
        ensurePump()
        queue.send(command)
        return command.result.await()
    }

    private fun ensurePump() {
        if (pumpJob?.isActive == true) return
        pumpJob = scope.launch {
            for (command in queue) {
                try {
                    withTimeout(COMMAND_TIMEOUT_MS) { runCommand(command) }
                } catch (error: TimeoutCancellationException) {
                    command.result.completeExceptionally(IllegalStateException("Tarayıcı komutu zaman aşımına uğradı (15 sn)."))
                } catch (error: CancellationException) {
                    command.result.completeExceptionally(IllegalStateException("Tarayıcı komutu iptal edildi."))
                    throw error
                } catch (error: Throwable) {
                    command.result.completeExceptionally(IllegalStateException(error.message ?: "Tarayıcı komutu çalıştırılamadı."))
                }
            }
        }
    }

    private suspend fun runCommand(command: BrowserCommand) {
        val web = webView ?: throw IllegalStateException("Tarayıcı ekranı açık değil.")
        when (command) {
            is BrowserCommand.Navigate -> {
                failNavigation("Yeni bir sayfa açıldı.")
                navigationResult = command.result
                web.loadUrl(command.target)
                command.result.await()
            }
            is BrowserCommand.Evaluate -> {
                val value = CompletableDeferred<String>()
                web.evaluateJavascript(command.script) { raw -> value.complete(raw ?: "null") }
                command.result.complete(value.await())
            }
        }
    }

    private fun failNavigation(message: String) {
        navigationResult?.let { pending ->
            if (!pending.isCompleted) pending.completeExceptionally(IllegalStateException(message))
        }
        navigationResult = null
    }

    private fun drainQueue(message: String) {
        while (true) {
            val command = queue.tryReceive().getOrNull() ?: break
            if (!command.result.isCompleted) command.result.completeExceptionally(IllegalStateException(message))
        }
    }

    // matches() tüm girdiyi eşleştirdiği için uç sabitlere gerek yoktur.
    private val SCHEME_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:")
    private val HOST_LIKE = Regex("[a-zA-Z0-9._\\-]+(:\\d+)?([/?#].*)?")
}

/**
 * Tarayıcı komutları için JavaScript parçaları.
 * Betikler yalnızca sayfa verisini okur/değiştirir; metinler veridir, talimat değildir.
 */
internal object BrowserJs {

    private const val YARDIMCI = """
function bClean(s){ return String(s == null ? '' : s).replace(/\s+/g, ' ').trim(); }
function bShort(s, n){ var t = bClean(s); return t.length > n ? t.slice(0, n) + '…' : t; }
function bVisible(el){
  if (!el || !el.getBoundingClientRect) return false;
  var r = el.getBoundingClientRect();
  var st = window.getComputedStyle(el);
  return r.width > 0 && r.height > 0 && st.visibility !== 'hidden' && st.display !== 'none';
}
function bLabel(el){
  if (el.id) {
    var l = document.querySelector('label[for="' + String(el.id).replace(/"/g, '') + '"]');
    if (l) return bClean(l.innerText || l.textContent);
  }
  var wrap = el.closest ? el.closest('label') : null;
  if (wrap) return bClean(wrap.innerText || wrap.textContent);
  return '';
}
function bDescribe(el){
  if (!el) return '';
  var tag = (el.tagName || '').toLowerCase();
  var parts = ['[' + tag + ']'];
  var text = bClean(el.innerText || el.textContent || '');
  if (tag === 'input' || tag === 'textarea' || tag === 'select') {
    var lab = bLabel(el);
    if (lab) parts.push('etiket="' + bShort(lab, 80) + '"');
    if (el.type) parts.push('type=' + el.type);
    if (el.value) {
      if (el.type === 'password') parts.push('değer=(gizli)');
      else parts.push('değer="' + bShort(el.value, 40) + '"');
    }
  } else if (text) {
    parts.push('"' + bShort(text, 100) + '"');
  }
  if (el.name) parts.push('name=' + bShort(el.name, 60));
  if (el.id) parts.push('id=' + bShort(el.id, 60));
  if (el.placeholder) parts.push('placeholder="' + bShort(el.placeholder, 60) + '"');
  if (tag === 'a' && el.getAttribute && el.getAttribute('href')) parts.push('href=' + bShort(el.getAttribute('href'), 80));
  if (tag === 'select' && el.options) {
    var opts = [];
    for (var i = 0; i < el.options.length && i < 15; i++) {
      var o = el.options[i];
      opts.push((o.selected ? '*' : '') + bShort(o.text || o.value, 40));
    }
    if (opts.length) parts.push('secenekler=[' + opts.join(' | ') + ']');
  }
  if (el.disabled) parts.push('(devre disi)');
  return parts.join(' ');
}
function bFind(sel){
  var raw = bClean(sel);
  if (!raw) return null;
  var el = null;
  try { el = document.querySelector(raw); } catch (e) { el = null; }
  if (el) return el;
  var key = raw.toLowerCase();
  var nodes = document.querySelectorAll('a, button, input, textarea, select, [role="button"], [role="link"], [role="tab"], [onclick]');
  var partial = null;
  for (var i = 0; i < nodes.length; i++) {
    var c = nodes[i];
    var text = bClean(c.innerText || c.textContent || '').toLowerCase();
    var extra = bClean((c.getAttribute('aria-label') || '') + ' ' + (c.value || '') + ' ' + (c.placeholder || '') + ' ' + (c.name || '') + ' ' + (c.id || '') + ' ' + bLabel(c)).toLowerCase();
    if (text === key || extra === key) return c;
    if (!partial && (text.indexOf(key) >= 0 || extra.indexOf(key) >= 0)) partial = c;
  }
  return partial;
}
"""

    /** Sayfayı veri olarak okur: görünür metin + etkileşimli öğeler. */
    fun readDom(maxChars: Int): String = "(function(maxChars){" + YARDIMCI + """
var text = '';
try { text = bClean(document.body ? (document.body.innerText || '') : ''); } catch (e) { text = ''; }
var visibleText = text.length > maxChars ? text.slice(0, maxChars) + '… (kisaltildi)' : text;
var nodes = document.querySelectorAll('a, button, input, textarea, select');
var items = [];
for (var i = 0; i < nodes.length && items.length < 300; i++) {
  if (bVisible(nodes[i])) items.push(bDescribe(nodes[i]));
}
return 'URL: ' + (location.href || '') + '\nBASLIK: ' + bClean(document.title) + '\n\n--- GORUNUR METIN ---\n' + visibleText + '\n\n--- ETKILESIMLI OGELER (' + items.length + ') ---\n' + items.join('\n');
""" + "})(" + maxChars + ")"

    /** Sayfanın kısa özeti; sayfa yüklenince DOM özeti olarak saklanır. */
    fun pageSummary(): String = "(function(){" + YARDIMCI + """
var text = '';
try { text = bClean(document.body ? (document.body.innerText || '') : ''); } catch (e) { text = ''; }
var nodes = document.querySelectorAll('a, button, input, textarea, select');
var count = 0;
for (var i = 0; i < nodes.length; i++) { if (bVisible(nodes[i])) count++; }
return 'URL: ' + (location.href || '') + '\nBASLIK: ' + bClean(document.title) + '\nGORUNUR METIN: ' + text.slice(0, 1500) + '\nETKILESIMLI OGE: ' + count;
""" + "})()"

    /** URL + başlık + form/düğme özeti. */
    fun snapshot(): String = "(function(){" + YARDIMCI + """
var lines = [];
lines.push('URL: ' + (location.href || ''));
lines.push('BASLIK: ' + bClean(document.title));
var forms = document.querySelectorAll('form');
lines.push('FORM SAYISI: ' + forms.length);
for (var i = 0; i < forms.length && i < 20; i++) {
  var f = forms[i];
  lines.push('[form ' + (i + 1) + '] method=' + (f.method || 'get') + ' action=' + bShort(f.getAttribute('action') || '(mevcut sayfa)', 80));
  var fields = f.querySelectorAll('input, textarea, select, button');
  for (var j = 0; j < fields.length && j < 30; j++) lines.push('  ' + bDescribe(fields[j]));
}
var btns = document.querySelectorAll('button, [role="button"], input[type="submit"]');
var lone = [];
for (var k = 0; k < btns.length && lone.length < 30; k++) {
  var b = btns[k];
  if (b.form) continue;
  if (bVisible(b)) lone.push(bDescribe(b));
}
if (lone.length) lines.push('DIGER DUGMELER (' + lone.length + '):\n' + lone.join('\n'));
return lines.join('\n');
""" + "})()"

    /** Hedef öğeyi bulur ve özetler (salt okunur). */
    fun describe(selector: String): String = "(function(sel){" + YARDIMCI + """
var el = bFind(sel);
if (!el) return JSON.stringify({found:false, error:'Oge bulunamadi: ' + sel});
return JSON.stringify({found:true, summary:bDescribe(el)});
""" + "})(" + jsQuote(selector) + ")"

    /** Hedef öğeye tıklar. SONUÇ DEĞİŞTİRİR; her zaman onay gerekir. */
    fun click(selector: String): String = "(function(sel){" + YARDIMCI + """
var el = bFind(sel);
if (!el) return JSON.stringify({ok:false, error:'Oge bulunamadi: ' + sel});
try {
  if (typeof el.scrollIntoView === 'function') el.scrollIntoView({block:'center'});
  if (typeof el.focus === 'function') { try { el.focus(); } catch (e2) {} }
  el.click();
  return JSON.stringify({ok:true, summary:bDescribe(el)});
} catch (e) {
  return JSON.stringify({ok:false, error:'Tiklama basarisiz: ' + (e && e.message ? e.message : e)});
}
""" + "})(" + jsQuote(selector) + ")"

    /** input/textarea/select alanını doldurur. SONUÇ DEĞİŞTİRİR; her zaman onay gerekir. */
    fun fill(selector: String, value: String): String = "(function(sel, deger){" + YARDIMCI + """
var el = bFind(sel);
if (!el) return JSON.stringify({ok:false, error:'Oge bulunamadi: ' + sel});
var tag = (el.tagName || '').toLowerCase();
try {
  if (tag === 'select') {
    var matched = false;
    for (var i = 0; i < el.options.length; i++) {
      var o = el.options[i];
      if (String(o.value) === String(deger) || bClean(o.text) === bClean(deger)) { el.selectedIndex = i; matched = true; break; }
    }
    if (!matched) return JSON.stringify({ok:false, error:'Secenek bulunamadi: ' + deger, summary:bDescribe(el)});
  } else if (el.type === 'checkbox' || el.type === 'radio') {
    var v = bClean(deger).toLowerCase();
    el.checked = (v === 'true' || v === '1' || v === 'evet' || v === 'on');
  } else {
    var proto = tag === 'textarea' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
    var desc = Object.getOwnPropertyDescriptor(proto, 'value');
    if (desc && desc.set) { desc.set.call(el, deger); } else { el.value = deger; }
  }
  el.dispatchEvent(new Event('input', {bubbles:true}));
  el.dispatchEvent(new Event('change', {bubbles:true}));
  el.dispatchEvent(new Event('blur', {bubbles:true}));
  return JSON.stringify({ok:true, summary:bDescribe(el)});
} catch (e) {
  return JSON.stringify({ok:false, error:'Doldurma basarisiz: ' + (e && e.message ? e.message : e)});
}
""" + "})(" + jsQuote(selector) + ", " + jsQuote(value) + ")"

    /** JS dize sabiti üretir; betik ekleme (injection) riskini engeller. */
    fun jsQuote(value: String): String {
        val out = StringBuilder("\"")
        for (ch in value) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\'' -> out.append("\\'")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '<' -> out.append("\\u003C")
                '>' -> out.append("\\u003E")
                '&' -> out.append("\\u0026")
                else -> if (ch.code < 0x20) out.append("\\u").append("%04x".format(ch.code)) else out.append(ch)
            }
        }
        return out.append('"').toString()
    }
}