# Bende - ilerleme kaydı

## Bu kopya (Bende 2) için çalışma planı — 13.10.2026

- Bu çalışma `Ajantik Dosyaları/Bende 2` kopyası üzerinden yürütülür; orijinal `Bende` klasörüne dokunulmaz. Uygulama adı **Bende**, paket adı `com.bende.app` olarak kalır.
- Amaç: orijinal görev promptundaki 1-9 aşamalarını mümkün olduğunca tamamlamak. Kurallar aynı: yalnızca derlenen/test edilen iş "çalışıyor" sayılır, doğrulanmayanlar DOĞRULANMADI listesine girer.
- **Sorumluluk sınırı (varsayım):** GitHub hesabı yan hesap `baran1248` olarak verildi. GitHub Device Flow OAuth uygulaması açmak, depo oluşturmak, Release yayınlamak gibi hesaba dokunan işlerde yalnızca bu yan hesap kullanılır; canlı test için kullanıcıdan erişim token'ı (PAT) istenir. Ücretli/kota aşan işlem yapılmaz.
- Aşama önceliği:
  1. Çekirdek iyileştirme (Aşama 1/3/9): yanıt akışı (streaming), bağlam sıkıştırma, oturum dışa/içe aktarma, koyu tema + tema seçici, erişilebilirlik/boş-durum cilası, `list_files` aracı.
  2. Aşama 6: uygulama içi WebView tarayıcı devri (DOM okuma, tıklama, form doldurma; riskli eylemlerde onay; sayfa içeriği veri sayılır).
  3. Aşama 5: GitHub katmanı (Device Flow/PAT ile giriş, depo oluşturma, kod iletme, Actions ile APK derleme, log okuma, Release/Artifact indirme) + "derle" aracı soyutlaması (yerel/bulut/otomatik ayarı).
  4. Aşama 7-8 (cihaz içi derleme, llama.cpp yerel model): gömülü JDK/NDK ve llama.cpp yerel derlemesi bu ortamda (Windows masaüstü, APK boyutu ve NDK derleme süresi sınırları) üretilemez; arayüz/soyutlama ve gerekçeyle raporlanır, uydurma test yapılmaz.
- Teslimat: sürüm numaralı debug APK `Sonuç/` içine, README ve PROGRESS güncellenir, her aşama sonunda git commit.

## 0.7.0 turu — 13.10.2026 (Bende 2 kopyası)

### Yapılanlar

- **Araç altyapısı:** `MergedTools.kt` ile üç araç ailesi tek şemada birleştirildi: dosya araçları (6: `read_file`, `list_files`, `search_files`, `write_file`, `edit_file`, `delete_file`), GitHub/bulut derleme araçları (5: `github_push_project`, `build_start`, `build_status`, `build_log`, `build_download`), tarayıcı devri araçları (5: `browser_open`, `browser_read_dom`, `browser_click`, `browser_fill`, `browser_snapshot`). Onay akışı: dosya ve GitHub yazma işlemleri uygulamanın genel onay penceresini bekler; tarayıcı araçları kendi onay diyaloğunu yürütür. Tüm araç yürütmeleri 15 dk süre sınırı ile.
- **Yanıt akışı (streaming):** OpenAI uyumlu SSE ve Anthropic event akışı ayrıştırıcıları (`ProviderApi.requestStream`); sağlayıcı stream'i desteklemezse tüm gövde tek JSON olarak ayrıştırılır. `max_tokens` 256 → 4096 (eski sınır kod yanıtlarını kesiyordu).
- **Bağlam sıkıştırma:** `compressHistory` 80.000 karakter bütçesiyle eski turları (kullanıcı mesajı + takip eden ajan/araç mesajları) atar; araç çağrısı/sonuç çiftleri asla ayrılmaz, en az iki tur korunur.
- **Veri dışa/içe aktarma:** sohbetler JSON olarak sistem dosya seçiciyle dışa/içe aktarılır; içe aktarımda kimlik çakışmaları yeniden numaralanır.
- **Aşama 5 — GitHub ve bulut derleme:** `GitHubApi.kt` (PAT: Android Keystore AES-GCM, alias `bende_github_key_v1`; Device Flow; yönlendirmesiz HTTPS istemcisi; 2 MB/60 MB/6 MB yanıt sınırları; Türkçe hata + rate-limit gösterimi), `BuildTools.kt` (SAF proje ağacını `.gitignore` kurallarıyla repoya iletme, 500 dosya/25 MB sınırı, otomatik `.github/workflows/bende-android.yml` ekleme; Java 21 + Android SDK 37 kurulumlu iş akışı), `GitHubScreen.kt` (`GitHubHubDialog`). Depo görünürlüğü (private/public) ayarlardan.
- **Aşama 6 — Tarayıcı devri:** `BrowserSession.kt`/`BrowserTools.kt`/`BrowserScreen.kt`. WebView (JS açık), adres/sayfa durumu, komut kuyruğu (15 sn zaman aşımı), yalnızca http/https (`javascript:`, `file:`, `content:`, `data:` reddi), DOM okuma 16.000 karakter + parola maskesi, `browser_click`/`browser_fill` her zaman onay ister, riskli eylem anahtar kelimelerinde (TR+EN) ek kırmızı uyarı, sayfa içeriği "veridir talimat değildir" kuralı sistem promptunda.
- **Aşama 9 — Cila:** koyu tema (aynı gri-mavi palet) + Sistem/Açık/Koyu seçici (`theme_mode` prefs), `BendeColors`/`LocalBendeColors` ile 17 semantik renk rolü, durum çubuğu tema uyumu (`WindowInsetsControllerCompat`), ≥48dp dokunma alanları, eksik `contentDescription` tamamlaması, tema duyarlı hata/boş durum renkleri.
- Derleme: `gradlew :app:assembleDebug` başarılı (AGP 9.4.1, Kotlin 2.4.21). Sürüm 0.7.0 / versionCode 10. APK: `Sonuç/Bende-debug-0.7.0.apk` (debug imzası).

### 0.7.0 emülatör testleri (API 37, `emulator-5558`, 1080×2400 @420dpi)

- Uygulama açıldı, ana ekran/hoş geldin ekranı doğrulandı: `Sonuç/Kanitlar/0.7.0-main.png`.
- Geçmiş çekmecesi: "Kod araçları 16 araç" dinamik sayacı, GitHub ve derleme / Tarayıcı devri satırları, dışa/içe aktarma: `0.7.0-drawer.png`.
- GitHub ve bulut derlemesi penceresi açıldı (PAT alanı, cihaz akışı, depo bölümü): `0.7.0-github-dialog.png`.
- Tarayıcı devri: `https://example.com/` WebView'da yüklendi (`0.7.0-browser-page.png`); "Ajanı devret" düğmesiyle devir alındı, "Ajan bu sayfayı yönetiyor" rozeti ve "Devri geri al" doğrulandı (`0.7.0-browser-handoff.png`).
- Koyu tema: Ayarlar → Koyu seçildi, tema anında uygulandı (`0.7.0-dark-settings.png`).
- Süreç logunda `FATAL EXCEPTION` yok.

### Bu turun kararları

- Araç aileleri tek `MergedTools` birleştiricisinden geçer; böylece yeni araç ailesi eklemek UI'da tek nokta değişikliği gerektirir.
- Shell aracı bilinçli olarak eklenmedi: SAF proje ağacı gerçek dosya yolu değildir (`content://`), cihazda git/shell ikilisi yoktur; git işlemleri GitHub API üzerinden yapılır. Aşama 7'nin cihaz içi derleme hedefiyle birlikte yeniden değerlendirilmelidir.
- Aşama 7 (cihaz içi derleme: gömülü JDK + Gradle + aarch64 araçları) ve Aşama 8 (llama.cpp ile yerel model) bu turda yapılmadı: gömülü JDK/NDK ve llama.cpp yerel derlemesi APK boyutu (yüzlerce MB), NDK derleme süresi ve Android 12+ phantom process / arka plan kısıtları nedeniyle bu ortamda dürüstçe test edilemez. "Derle" araçları bulut (GitHub Actions) arka ucuna bağlıdır; yerel arka uç ve otomatik geçiş henüz yoktur. Uydurma test yapılmamıştır.

### 0.7.0 sonrası DOĞRULANMADI ekleri

- GitHub API canlı istekleri (giriş, depo oluşturma, iletme, Actions derleme, log, APK indirme) — canlı test için hesap erişim anahtarı (PAT) bekleniyor; kod yalnızca derlendi ve arayüzü emülatörde açıldı.
- Tarayıcı devrinde ajan ile uçtan uca DOM okuma/tıklama/form doldurma turu (gerçek model çağrısı gerekli); `browser_*` araçlarının model çağrı zinciri.
- Streaming yanıtın gerçek sağlayıcıyla canlı doğrulaması (kod ayrıştırıcıları derlendi; ağ testi yapılmadı).
- Koyu temanın gerçek cihaz/tablet/katlanabilir üzerinde kontrast ve TalkBack denetimi.

### 0.7.0 bilinen sorunlar ekleri

- GitHub iş akışı `assembleDebug` üretir; üretim (release) imzası ve GitHub Secrets'ta imza anahtarı saklama henüz yok — yayına hazır imzalı APK için bu adım kullanıcı onayıyla yapılmalıdır.
- `github_push_project` seçili SAF kökünü iletir; 500 dosya/25 MB üstü projelerde eksik iletme olur (uyarı gösterilir).
- Aşama 8'deki "derleme + yerel model eşzamanlı bellek yönetimi" tasarımı uygulanmadı (ilgili bileşen yok).

## Kayıt düzeni incelemesi

`C:\Users\MBB\Desktop\Ajantik Dosyaları` bulundu; aynı adlı OneDrive masaüstü kopyası bulunmadı. Önceki sekiz proje incelendi. Hepsinde `Malzemeler` kaynak/çalışma, `Sonuç` teslim çıktıları için kullanılıyor. Bazı projelerde `eski sürümler` alt klasörleri ve sürüm adlandırılmış APK/ZIP’ler var. README yalnızca bazı köklerde; PROGRESS/test raporu/kanıt klasörü tutarlı değil. Git depoları kaynak klasörlerinde bazen var, bazen yok. Bu nedenle Bende’de aynı iki klasör düzeni, kökte Türkçe README/PROGRESS, `Sonuç/Kanitlar` altında sürümlenmemiş test kanıtı ve Bende kökünde tek Git deposu seçildi. Önceki projelerde hiçbir değişiklik yapılmadı.

## Mimari kararlar ve varsayımlar

- Paket adı `com.bende.app`; istenen varsayılan korundu.
- Kotlin + Jetpack Compose + Material 3; Android Gradle Plugin 9.4.1 ve AGP’nin yerleşik Kotlin desteği, Compose BOM 2026.09.00, minSdk 26, targetSdk 36 ve compileSdk 37 seçildi. Güncel AGP, JDK 21 gereksinimiyle uyumludur.
- Sohbet oturumları bu ilk prototipte uygulamanın özel SharedPreferences alanında JSON olarak tutuluyor. Veritabanı şeması ve yedekleme politikası henüz sabitlenmedi.
- Sağlayıcı ayarı tek seferde bir protokol/profil seçer: OpenAI uyumlu Chat Completions veya Anthropic Messages. API kökü HTTPS ile sınırlandırılmıştır; istek yönlendirmeleri kapalı ve yanıt gövdesi 2 MiB ile sınırlıdır. `/v1/models` model listesi denenebilir, model kimliği elle girilebilir. Google Gemini OpenAI uyumlu API’sinin `/v1beta/openai` taban adresi özel ele alınır; sağlayıcıya giden OpenAI uyumlu yanıtlar en fazla 256 token ister.
- API anahtarı AES/GCM/NoPadding ile şifrelenip uygulama tercihlerine yazılır; AES anahtarı Android Keystore’da üretilir ve dışa aktarılamaz. API anahtarı boşaltılıp saklı değer ayrı silme düğmesiyle temizlenir.
- Kullanıcı başlatımlı ajan işleri Compose ekran scope’u yerine uygulama sürecine bağlı `SupervisorJob` scope’unda çalışır. Çalışan iş varken Android ön plan hizmeti ve devam bildirimi gösterilir; son iş bittiğinde hizmet kapatılır. API 34+ için `specialUse` türü manifestte açıklanır. İşler uygulama süreci öldürülürse devam etmez; kalıcı kuyruk/otomatik kurtarma henüz yoktur.
- Android belge seçicisi `OpenDocument` ile dosya adı ve kalıcı okunabilir URI saklar. Gönderim anında metin kodlu dosyalar (256 KB sınır) ve görseller (5 MB sınır) okuyup OpenAI/Anthropic mesaj içeriklerine ekler. Desteklenmeyen ikili dosyalar içeriği olmadan açıklanır. Bu içerik ayarlı üçüncü taraf API’ye gider.
- OpenCode Zen için Zen `/v1` taban adresi hızlı ayarı var; ücretsiz modeli atlatan/hesapsız bir yol sunulmaz. Zen kendi API anahtarını gerektirir ve model bazında veri kullanımı/kullanım süresi değişebilir.
- Pencere sınıfları AndroidX Compose Material 3 Adaptive `currentWindowAdaptiveInfoV2()` ve `WindowSizeClass` API’siyle okunuyor. 600 dp ve üzeri genişlikte kalıcı sol gezinme alanı; kompakt pencerede geçmiş çekmecesi kullanılıyor. Genişlik eşikleri resmi pencere sınıfı API’sinden geliyor; fiziksel aygıt adı kullanılmıyor.
- Bileşen bağımlılıklarında AndroidX/Jetpack (Apache-2.0) ve Material 3 kullanıldı. Haricî kaynak kod kopyalanmadı; GPL kod kullanılmadı. Bağımlılık sürüm/lisans envanteri ve dağıtım lisans bildirimi henüz tamamlanmadı.
- İlk Windows derlemesi Türkçe `ı` içeren üst klasör yolunda AGP yol kontrolünde durdu. Kaynak klasörünü taşımak yerine AGP’nin deneysel `android.overridePathCheck=true` seçeneği kullanıldı.

## Aşama durumu

| Aşama | Durum | Not |
|---|---|---|
| 1. Temel | **Sürüyor — kısmi prototip** | Compose sohbet ekranı, yerel geçmiş, başlık/liste/inline Markdown, yeni sohbet/oturum seçimi, oturum adlandırma ve onaylı silme, dosya/görsel eki ve sağlayıcıya içerik iletimi eklendi. Görsel önizleme, akış yanıtı ve oturum yönetimi cilası eksik. |
| 2. Sağlayıcı katmanı | **Sürüyor — Gemini canlı doğrulaması eksik** | OpenAI uyumlu ve Anthropic istek/listeme kodu, HTTPS taban adres doğrulaması, otomatik yönlendirmeyi kapatma, 2 MiB yanıt sınırı ve Android Keystore’da AES-GCM anahtarı uygulandı. Gemini’nin OpenAI uyumlu URL yolu ve modeller API’si Google’ın resmi dokümanıyla doğrulandı; hızlı adres seçimi eklendi. Kullanıcının anahtarı verildi ancak bu oturumun bilgisayar arayüzü emülatörü göstermediğinden anahtarı uygulamaya güvenli biçimde giremedim; Google’a gerçek istek yapılmadı. `/v1/models` ve gerçek sohbet yanıtı doğrulanmayı bekliyor. Tek aktif profil var. |
| 3. Ajan çekirdeği | **Sürüyor — model turu doğrulanmadı** | OpenAI/Anthropic araç şemaları ve 8 turluk yanıt/araç döngüsü eklendi. SAF klasörü kalıcı erişimle seçiliyor; dosya okuma/arama ve onaylı yazma/düzenleme/silme seçilen kökle sınırlı. Kök `AGENTS.md` (32 KB sınırı) her istek için hazırlanıyor; dosya içeriği sağlayıcıya gönderilmeden önce önizlenebiliyor. Kullanıcı başlatımlı işler için `specialUse` ön plan hizmeti uygulandı ve API 37’de bildirim/FGS durumu doğrulandı. SAF dosya okuma ve talimat önizlemesi geçti; canlı model isteği/araç çağrısı sınanamadı. Shell/Git araçları, bağlam sıkıştırma ve süreç ölümü sonrası kurtarma eksik. |
| 4. Çoklu ajan | **Sürüyor — canlı eşzamanlı test yok** | Dört ayrı sohbet için eşzamanlı coroutine işleri, oturum başına yükleme göstergesi/hata durumu ve tekil onay penceresi kuyruğu eklendi. Ajan işleri uygulama sürecine bağlı scope’a taşındı; ön plan hizmeti toplam etkin işler varken açık kalır. 0.6.2 derlenip API 37 emülatörüne kuruldu. Gerçek sağlayıcı anahtarı uygulamaya girilemediğinden canlı çoklu ajan paralelliği kanıtlanmadı. |
| 5. GitHub ve bulut derleme | Başlamadı | OAuth, GitHub Actions/Secrets/Release veya uçtan uca proje üretim testi yok. |
| 6. Tarayıcı devri | Başlamadı | Yok. |
| 7. Cihaz içi derleme | Başlamadı | Yok. |
| 8. Yerel model | Başlamadı | Yok. |
| 9. Cilalama | Başlamadı | İlk UI turu ve emülatörde pencere boyutu denemeleri yapıldı; tam erişilebilirlik ve cihaz QA’sı yok. |

## Aşama 1 denetimi

- Derleme: `./gradlew.bat :app:assembleDebug` başarılı. Uyarılar: Android pencere sistem çubuğu API’si için deprecated uyarıları; ayrıca `android.overridePathCheck` deneysel. APK debug anahtarıyla imzalı.
- Emülatör: Kullanıcının belirttiği gibi başka ajan tarafından kullanılan `emulator-5554` kullanılmadı. Kendi `Bende_Test_API_37` API 37 emülatörü `emulator-5558` portunda oluşturulup kullanıldı.
- Senaryo: Uygulama açıldı; Downloads içinden `bende-sample.txt` seçildi, metin mesajına eklenip gönderildi; ek dosya tek başına da gönderildi. Başlık ve ek ekranda göründü. Uygulama kapatılıp açıldığında mesaj ve yeni kayıt biçimindeki ek adı korundu. Geçmiş çekmecesi açıldı ve seçili oturum gösterildi.
- Oturum yönetimi: Geçmiş satırındaki seçeneklerden başlık değiştirildi; uygulama yeniden başlatıldığında yeni başlık kaldı. Silme seçeneği uyarı/onay penceresi gösterdi; onaydan sonra sohbet listeden ve kalıcı kayıttan kaldırıldı. Kanıtlar: `Sonuç/Kanitlar/session-management.png`, `session-delete-confirm.png`, `session-deleted.png`.
- Sağlayıcı senaryosu: Sağlayıcı ayarları açıldı. Test anahtarı girilip kaydedildi; `provider.xml` içinde anahtarın açık metni yerine yalnızca AES-GCM şifreli veri göründü. Anahtar emülatörden silindi. Anahtar yokken mesaj gönderimi kullanıcıya anlaşılır ayar hatası gösterdi ve uygulama kapanmadı. API 37’de son süreç logunda `FATAL EXCEPTION` yok. Gerçek ağa giden sağlayıcı isteği yapılmadı.
- Ajan çalışma alanı senaryosu: Android sistem klasör seçicisinde `Documents` klasörü seçildi; okuma/yazma URI izni alındı ve uygulama kapatılıp yeniden açıldıktan sonra `workspace.xml` içinde klasör kaydı ve sistemde kalıcı URI izni korundu. Araçlara modelden çağrı yaptırmak için gerçek sağlayıcı anahtarı bulunmadığından dosya okuma/yazma/aramanın uçtan uca araç turu sınanmadı. API 37’de son süreç logunda çökme yok.
- Proje talimatı senaryosu: Seçili emülatör `Documents` klasörüne yalnızca test için `AGENTS.md` kondu. Uygulamanın SAF erişimiyle içeriği okuyup önizlediği ve dosyanın seçili sağlayıcıya her model isteğinde gönderileceğini belirttiği ekranda doğrulandı. Test dosyası sonrasında emülatörden kaldırıldı. Gerçek API isteğinin talimat metnini aldığı canlı ağ testi yapılamadı. Kanıtlar: `Sonuç/Kanitlar/agents-preview.png`, `agents-integration-session.log`.
- Çoklu ajan: 0.6.0 sürümü derlenip 5558’e kuruldu, uygulama açıldı ve süreç logunda `FATAL EXCEPTION` yok. 0.6.1 de kuruldu ve başlatıldı. Bilgisayar arayüzü aracı emülatörü görünür uygulama olarak sunmadığından gerçek sağlayıcıyla paralellik ve kullanıcının Gemini anahtarıyla istek sınanamadı; Google’a istek gönderilmedi. Derleme/açılış kanıtı `Sonuç/Kanitlar/multi-agent-ui.png`, `multi-agent-session.log`; bunlar eşzamanlı ajan işleyişinin kanıtı değildir.
- Uyarlanabilirlik: Aynı emülatör penceresi 1080×2400 (telefon), 1800×2400 (~686 dp orta) ve 2400×2400 (~914 dp geniş) boyutlarına ayarlandı. Orta ve geniş pencerede kalıcı yan gezinme; kompakt görünümde açılır geçmiş çekmecesi gözlendi. Bu çözünürlük değişimi tablet/katlanabilir fiziksel aygıt testi değildir.
- Kanıtlar: `Sonuç/Kanitlar/chat-scenario.png`, `attachment-sent.png`, `persisted-chat.png`, `history-drawer.png`, `tablet-window.png`, `expanded-window.png`, `provider-settings.png`, `provider-error.png`, `project-workspace-drawer.png`, `agent-session.log`, `emulator-session.log`, `provider-session.log`.
- Oturum yönetimi: Geçmiş satırındaki seçeneklerden başlık değiştirildi; uygulama yeniden başlatıldığında yeni başlık kaldı. Silme seçeneği uyarı/onay penceresi gösterdi; onaydan sonra sohbet listeden ve kalıcı kayıttan kaldırıldı. Kanıtlar: `Sonuç/Kanitlar/session-management.png`, `session-delete-confirm.png`, `session-deleted.png`; son süreç logu `session-management.log`.
- Hata gözlemi: Oturum yönetimi senaryosundan sonraki süreç logunda `FATAL EXCEPTION` yok. Soğuk başlangıçta emülatör logu yaklaşık 2,2 sn çizim ve 111 atlanan kare kaydetti; emülatör performansına özgü olup olmadığı fiziksel cihazda doğrulanmadı. Bu senaryo log’u genel kararlılık kanıtı değildir.
- Ön plan hizmeti: 0.6.2 API 37 emülatörüne kuruldu. Uygulama ön plandayken uygulamanın UID’siyle servis başlatıldı; `dumpsys activity services` çıktısında `startForegroundCount=1`, `isForeground=true`, `foregroundId=4071` ve `types=0x40000000` gözlendi. Servis normal şekilde durduruldu. Bu test bileşenin bildirimle foreground durumuna geçmesini doğrular; gerçek model işi sürerken uygulamayı arkaya alma veya süreci öldürüp kurtarma davranışını doğrulamaz. Ayrıntı `Sonuç/Kanitlar/foreground-service-session.log`; ekran görüntüsü arayüz aracı emülatörü göstermediğinden alınamadı.
- Commit: ilk geliştirme kontrol noktası `be817a5b21a685e240aff03f9829e38df9cafbf6`; resmi uyarlanabilir API kontrol noktası `aca7ba9`; sağlayıcı katmanı `0317144`; SAF dosya araçları ve yazma güvenlik sınırı `6498584`; oturum yönetimi `c47aa6e`; sağlayıcı yanıt/yönlendirme korumaları `5bf8e59`; proje talimatları ve önizlemesi `ee5f05d`; çoklu ajan `c3bcd8e`; Gemini uyumu `9e80eed`; ön plan işi commit’i henüz atılmadı. Android 8+ UTF-8 dönüşüm uyumluluğu ayrıca ele alındı. Aşama koşulları eksik olduğu için commit’ler aşamanın tamamlandığı anlamına gelmiyor.
- Dağıtım: Güncel APK `Sonuç/Bende-debug-0.6.2.apk`; versionCode 9 / versionName 0.6.2. Derlendi, `emulator-5558` üzerine kuruldu ve başlatıldı. `dumpsys package` ile sürüm 9 doğrulandı. `apksigner` V2 imzasını doğruladı (sertifika SHA-256 `91517466005df246b5ceca9ece68fc567995590fa9ca2b5c32a09793a6155bd1`); APK SHA-256 `6086B6BC39BD998CECD70E79C6264644CECC14FBA9C992ADBFA4855C4CD550F0`. Bu debug imzası yayına uygun değildir. Yerel depoda Git remote bulunmadığından ve üretim imza anahtarı GitHub’da güvenli biçimde saklanmadığından GitHub Release/yayıma hazır imzalı APK oluşturulmadı.

## DOĞRULANMADI

- Gerçek telefon/tablet/katlanabilir cihaz performansı, RAM ve pil davranışı; erişilebilirlik denetimi; TalkBack ve klavye/fare kullanımı.
- OpenAI/Anthropic uç noktaları, kimlik bilgisi şifreleme, sağlayıcı lisans/şartları ve OpenCode ücretsiz model erişim şartları.
- Android 10+ arka plan derleme kısıtları, Android 12+ phantom process sınırı, aarch64 araçları, GitHub OAuth/Actions kota ve secrets davranışı.
- Ön plan hizmetiyle gerçek ajan/model işi sürdürme, bildirim izni kapalıyken görünürlük, süreç ölümü sonrası devam/kurtarma ve Android 8–13 gerçek cihaz uyumluluğu.
- Release anahtarıyla imzalama, güncelleme imza sürekliliği, APK kurulumu/upgrade testi, yayınlama ve GitHub Release.

## Resmî kaynaklar

- Android Developers, [Use a Bill of Materials — Jetpack Compose](https://developer.android.com/develop/ui/compose/bom): kullanılan `2026.09.00` BOM ve Compose kitaplıklarının eşleşmesi.
- Android Developers, [AGP 9.0 release notes](https://developer.android.com/build/releases/agp-9-0-0-release-notes): AGP 9 ile yerleşik Kotlin desteği.
- Android Developers, [Window size classes](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes): pencere genişliği sınıfları ve aygıt boyutu yerine mevcut pencereyi temel alma.
- Android Developers, [Compose Material 3 Adaptive sürüm notları](https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive): kullanılan resmi uyarlanabilir Compose API’si.
- Android Developers, [ActivityResultContracts.OpenDocument](https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.OpenDocument): kullanıcı denetimli belge seçimi ve URI erişimi.
- Android Developers, [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files): kullanıcının seçtiği belge/klasör için erişim ve kalıcı URI izinleri.
- Google AI for Developers, [Gemini API OpenAI compatibility](https://ai.google.dev/gemini-api/docs/openai): uyumlu taban URL, Chat Completions, model listesi ve Bearer anahtar kullanım örnekleri.
- AndroidX, [DocumentFile sürüm notları](https://developer.android.com/jetpack/androidx/releases/documentfile): kullanılan `1.1.0` sürümü ve klasör belge işlemleri.
- Android Developers, [Android Keystore system](https://developer.android.com/privacy-and-security/keystore): anahtar materyalini dışa aktarmadan kriptografik işlem yapma.
- Android Developers, [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types): Android 14+ hizmet türü ve `specialUse` gereksinimi.
- Android Developers, [Foreground service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout): Android 15+ `dataSync`/`mediaProcessing` süre kısıtları.
- OpenAI, [Chat Completions API](https://platform.openai.com/docs/api-reference/chat/create): kullanılan sohbet isteği biçimi.
- Anthropic, [API documentation](https://docs.anthropic.com/en/api/overview): Messages API kimlik doğrulama/istek protokolü.
- OpenCode, [Zen](https://docs.opencode.ai/docs/zen/): Zen hesabı/anahtarı, uç noktalar ve model bazlı gizlilik istisnaları.
- GitHub, [Included product usage](https://docs.github.com/en/billing/reference/product-usage-included): GitHub Free planında aylık dahil Actions dakikası (2.000); plana göre değişir ve aşım ücret doğurabilir.
- AndroidX, [Lisans](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt): Jetpack kitaplıklarının lisans bilgisi.

## Bilinen sorunlar

- Sağlayıcı cevapları normal yanıt olarak alınır; token akışı/iptal ve gerçek model çağrısı doğrulanmadı.
- Markdown başlık, liste, alıntı, fenced code ve basit inline kalıpları destekler; tam CommonMark/kod bloklarının ayrı temalı yüzeyi değil.
- Ekin URI’si sohbet kaydında tutulur; istek sırasında metin/görsel içeriği okunup sağlayıcıya gönderilir. İzin iptal edilirse ek yeniden seçilmelidir.
- Veri dışa/içe aktarma yok.
- Dosya araçları gerçek model çağrısı olmadan çalıştırılmadı. Shell ve Git araçları, çoklu ajan, iş sürerken activity kapanınca devam, Android foreground service, bağlam sıkıştırma, yerel derleme/model ve WebView devri yok.
- Çoklu ajan işleme kodu mevcut; canlı sağlayıcı ile paralellik doğrulanmadı. Bir ajan onay beklerken diğerinin onay penceresi mutex ile sıraya alınır.
- Koyu tema, Android tablet/katlanabilir üzerinde gerçek test ve tam erişilebilirlik incelemesi yok.
