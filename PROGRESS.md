# Bende — ilerleme kaydı

## Kayıt düzeni incelemesi

`C:\Users\MBB\Desktop\Ajantik Dosyaları` bulundu; aynı adlı OneDrive masaüstü kopyası bulunmadı. Önceki sekiz proje incelendi. Hepsinde `Malzemeler` kaynak/çalışma, `Sonuç` teslim çıktıları için kullanılıyor. Bazı projelerde `eski sürümler` alt klasörleri ve sürüm adlandırılmış APK/ZIP’ler var. README yalnızca bazı köklerde; PROGRESS/test raporu/kanıt klasörü tutarlı değil. Git depoları kaynak klasörlerinde bazen var, bazen yok. Bu nedenle Bende’de aynı iki klasör düzeni, kökte Türkçe README/PROGRESS, `Sonuç/Kanitlar` altında sürümlenmemiş test kanıtı ve Bende kökünde tek Git deposu seçildi. Önceki projelerde hiçbir değişiklik yapılmadı.

## Mimari kararlar ve varsayımlar

- Paket adı `com.bende.app`; istenen varsayılan korundu.
- Kotlin + Jetpack Compose + Material 3; Android Gradle Plugin 9.4.1 ve AGP’nin yerleşik Kotlin desteği, Compose BOM 2026.09.00, minSdk 26, targetSdk 36 ve compileSdk 37 seçildi. Güncel AGP, JDK 21 gereksinimiyle uyumludur.
- Sohbet oturumları bu ilk prototipte uygulamanın özel SharedPreferences alanında JSON olarak tutuluyor. Veritabanı şeması ve yedekleme politikası henüz sabitlenmedi.
- Sağlayıcı ayarı tek seferde bir protokol/profil seçer: OpenAI uyumlu Chat Completions veya Anthropic Messages. API kökü HTTPS ile sınırlandırılmıştır; istek yönlendirmeleri kapalı ve yanıt gövdesi 2 MiB ile sınırlıdır. `/v1/models` model listesi denenebilir, model kimliği elle girilebilir.
- API anahtarı AES/GCM/NoPadding ile şifrelenip uygulama tercihlerine yazılır; AES anahtarı Android Keystore’da üretilir ve dışa aktarılamaz. API anahtarı boşaltılıp saklı değer ayrı silme düğmesiyle temizlenir.
- Android belge seçicisi `OpenDocument` ile dosya adı ve kalıcı okunabilir URI saklar. Gönderim anında metin kodlu dosyalar (256 KB sınır) ve görseller (5 MB sınır) okuyup OpenAI/Anthropic mesaj içeriklerine ekler. Desteklenmeyen ikili dosyalar içeriği olmadan açıklanır. Bu içerik ayarlı üçüncü taraf API’ye gider.
- OpenCode Zen için Zen `/v1` taban adresi hızlı ayarı var; ücretsiz modeli atlatan/hesapsız bir yol sunulmaz. Zen kendi API anahtarını gerektirir ve model bazında veri kullanımı/kullanım süresi değişebilir.
- Pencere sınıfları AndroidX Compose Material 3 Adaptive `currentWindowAdaptiveInfoV2()` ve `WindowSizeClass` API’siyle okunuyor. 600 dp ve üzeri genişlikte kalıcı sol gezinme alanı; kompakt pencerede geçmiş çekmecesi kullanılıyor. Genişlik eşikleri resmi pencere sınıfı API’sinden geliyor; fiziksel aygıt adı kullanılmıyor.
- Bileşen bağımlılıklarında AndroidX/Jetpack (Apache-2.0) ve Material 3 kullanıldı. Haricî kaynak kod kopyalanmadı; GPL kod kullanılmadı. Bağımlılık sürüm/lisans envanteri ve dağıtım lisans bildirimi henüz tamamlanmadı.
- İlk Windows derlemesi Türkçe `ı` içeren üst klasör yolunda AGP yol kontrolünde durdu. Kaynak klasörünü taşımak yerine AGP’nin deneysel `android.overridePathCheck=true` seçeneği kullanıldı.

## Aşama durumu

| Aşama | Durum | Not |
|---|---|---|
| 1. Temel | **Sürüyor — kısmi prototip** | Compose sohbet ekranı, yerel geçmiş, başlık/liste/inline Markdown, yeni sohbet/oturum seçimi, oturum adlandırma ve onaylı silme, dosya/görsel eki ve sağlayıcıya içerik iletimi eklendi. Görsel önizleme, akış yanıtı ve oturum yönetimi cilası eksik. |
| 2. Sağlayıcı katmanı | **Sürüyor — canlı doğrulama eksik** | OpenAI uyumlu ve Anthropic istek/listeme kodu, HTTPS taban adres doğrulaması, otomatik yönlendirmeyi kapatma, 2 MiB yanıt sınırı ve Android Keystore’da AES-GCM anahtarı uygulandı. Ayarlar/şifreli kayıt API 37 emülatöründe denendi. Gerçek sağlayıcı anahtarı yok; canlı model/`/v1/models` yanıtı doğrulanmadı. Tek aktif profil var. |
| 3. Ajan çekirdeği | **Sürüyor — model turu doğrulanmadı** | OpenAI/Anthropic araç şemaları ve 8 turluk yanıt/araç döngüsü eklendi. SAF klasörü kalıcı erişimle seçiliyor; dosya okuma/arama ve onaylı yazma/düzenleme/silme seçilen kökle sınırlı. Kök `AGENTS.md` (32 KB sınırı) her istek için hazırlanıyor; dosya içeriği sağlayıcıya gönderilmeden önce önizlenebiliyor. SAF üzerinden dosyayı emülatörde okuma ve önizleme geçti; canlı model isteği/araç çağrısı anahtar olmadan sınanamadı. Shell/Git araçları, bağlam sıkıştırma ve ön plan servisi eksik. |
| 4. Çoklu ajan | Başlamadı | Yok. |
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
- Uyarlanabilirlik: Aynı emülatör penceresi 1080×2400 (telefon), 1800×2400 (~686 dp orta) ve 2400×2400 (~914 dp geniş) boyutlarına ayarlandı. Orta ve geniş pencerede kalıcı yan gezinme; kompakt görünümde açılır geçmiş çekmecesi gözlendi. Bu çözünürlük değişimi tablet/katlanabilir fiziksel aygıt testi değildir.
- Kanıtlar: `Sonuç/Kanitlar/chat-scenario.png`, `attachment-sent.png`, `persisted-chat.png`, `history-drawer.png`, `tablet-window.png`, `expanded-window.png`, `provider-settings.png`, `provider-error.png`, `project-workspace-drawer.png`, `agent-session.log`, `emulator-session.log`, `provider-session.log`.
- Oturum yönetimi: Geçmiş satırındaki seçeneklerden başlık değiştirildi; uygulama yeniden başlatıldığında yeni başlık kaldı. Silme seçeneği uyarı/onay penceresi gösterdi; onaydan sonra sohbet listeden ve kalıcı kayıttan kaldırıldı. Kanıtlar: `Sonuç/Kanitlar/session-management.png`, `session-delete-confirm.png`, `session-deleted.png`; son süreç logu `session-management.log`.
- Hata gözlemi: Oturum yönetimi senaryosundan sonraki süreç logunda `FATAL EXCEPTION` yok. Soğuk başlangıçta emülatör logu yaklaşık 2,2 sn çizim ve 111 atlanan kare kaydetti; emülatör performansına özgü olup olmadığı fiziksel cihazda doğrulanmadı. Bu senaryo log’u genel kararlılık kanıtı değildir.
- Commit: ilk geliştirme kontrol noktası `be817a5b21a685e240aff03f9829e38df9cafbf6`; resmi uyarlanabilir API kontrol noktası `aca7ba9`; sağlayıcı katmanı `0317144`; SAF dosya araçları ve yazma güvenlik sınırı `6498584`; oturum yönetimi `c47aa6e`; sağlayıcı yanıt/yönlendirme korumaları `5bf8e59`; proje talimatları ve önizlemesi güncel geliştirme kontrol noktası. Android 8+ UTF-8 dönüşüm uyumluluğu ayrıca ele alındı. Aşama koşulları eksik olduğu için commit’ler aşamanın tamamlandığı anlamına gelmiyor.
- Dağıtım: Güncel APK `Sonuç/Bende-debug-0.5.0.apk`; versionCode 6 / versionName 0.5.0; V2 Android Debug imzası doğrulandı (sertifika SHA-256 `91517466005df246b5ceca9ece68fc567995590fa9ca2b5c32a09793a6155bd1`); APK SHA-256 `64503E7943FE6EDE344D94BEF82A50037680E3A37BFC25B21B461636349F5D73`, boyut 18.881.578 bayt. Debug sertifikası üretim/release anahtarı değildir. Yerel depoda Git remote bulunmadığı ve uygulama tamamlanmadığı için GitHub Release oluşturulmadı.

## DOĞRULANMADI

- Gerçek telefon/tablet/katlanabilir cihaz performansı, RAM ve pil davranışı; erişilebilirlik denetimi; TalkBack ve klavye/fare kullanımı.
- OpenAI/Anthropic uç noktaları, kimlik bilgisi şifreleme, sağlayıcı lisans/şartları ve OpenCode ücretsiz model erişim şartları.
- Android 10+ arka plan derleme kısıtları, Android 12+ phantom process sınırı, aarch64 araçları, GitHub OAuth/Actions kota ve secrets davranışı.
- Release anahtarıyla imzalama, güncelleme imza sürekliliği, APK kurulumu/upgrade testi, yayınlama ve GitHub Release.

## Resmî kaynaklar

- Android Developers, [Use a Bill of Materials — Jetpack Compose](https://developer.android.com/develop/ui/compose/bom): kullanılan `2026.09.00` BOM ve Compose kitaplıklarının eşleşmesi.
- Android Developers, [AGP 9.0 release notes](https://developer.android.com/build/releases/agp-9-0-0-release-notes): AGP 9 ile yerleşik Kotlin desteği.
- Android Developers, [Window size classes](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes): pencere genişliği sınıfları ve aygıt boyutu yerine mevcut pencereyi temel alma.
- Android Developers, [Compose Material 3 Adaptive sürüm notları](https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive): kullanılan resmi uyarlanabilir Compose API’si.
- Android Developers, [ActivityResultContracts.OpenDocument](https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.OpenDocument): kullanıcı denetimli belge seçimi ve URI erişimi.
- Android Developers, [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files): kullanıcının seçtiği belge/klasör için erişim ve kalıcı URI izinleri.
- AndroidX, [DocumentFile sürüm notları](https://developer.android.com/jetpack/androidx/releases/documentfile): kullanılan `1.1.0` sürümü ve klasör belge işlemleri.
- Android Developers, [Android Keystore system](https://developer.android.com/privacy-and-security/keystore): anahtar materyalini dışa aktarmadan kriptografik işlem yapma.
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
- Koyu tema, Android tablet/katlanabilir üzerinde gerçek test ve tam erişilebilirlik incelemesi yok.
