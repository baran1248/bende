# Bende — ilerleme kaydı

## Kayıt düzeni incelemesi

`C:\Users\MBB\Desktop\Ajantik Dosyaları` bulundu; aynı adlı OneDrive masaüstü kopyası bulunmadı. Önceki sekiz proje incelendi. Hepsinde `Malzemeler` kaynak/çalışma, `Sonuç` teslim çıktıları için kullanılıyor. Bazı projelerde `eski sürümler` alt klasörleri ve sürüm adlandırılmış APK/ZIP’ler var. README yalnızca bazı köklerde; PROGRESS/test raporu/kanıt klasörü tutarlı değil. Git depoları kaynak klasörlerinde bazen var, bazen yok. Bu nedenle Bende’de aynı iki klasör düzeni, kökte Türkçe README/PROGRESS, `Sonuç/Kanitlar` altında sürümlenmemiş test kanıtı ve Bende kökünde tek Git deposu seçildi. Önceki projelerde hiçbir değişiklik yapılmadı.

## Mimari kararlar ve varsayımlar

- Paket adı `com.bende.app`; istenen varsayılan korundu.
- Kotlin + Jetpack Compose + Material 3; Android Gradle Plugin 9.4.1 ve AGP’nin yerleşik Kotlin desteği, Compose BOM 2026.09.00, minSdk 26, targetSdk 36 ve compileSdk 37 seçildi. Güncel AGP, JDK 21 gereksinimiyle uyumludur.
- Sohbet oturumları bu ilk prototipte uygulamanın özel SharedPreferences alanında JSON olarak tutuluyor. Veritabanı şeması ve yedekleme politikası henüz sabitlenmedi.
- Sağlayıcı yokken sahte model seçimi/yanıtı göstermemek için üst barda “Model bağlanmadı” gösteriliyor. Gönderilen mesaj yerel taslak/geçmişe kaydoluyor.
- Android belge seçicisi `OpenDocument` ile dosya adı ve kalıcı okunabilir URI saklanıyor. Bu sürüm dosya içeriğini okumuyor veya modele göndermiyor.
- Geniş pencere sınıfı 840 dp ve üzeri kabul edildi; burada kalıcı sol gezinme alanı var. Daha dar pencerelerde geçmiş açılır çekmeceyle gösteriliyor. Breakpoint’ler pencere genişliğine göre değerlendirilir; fiziksel aygıt adı kullanılmaz.
- Bileşen bağımlılıklarında AndroidX/Jetpack (Apache-2.0) ve Material 3 kullanıldı. Haricî kaynak kod kopyalanmadı; GPL kod kullanılmadı. Bağımlılık sürüm/lisans envanteri ve dağıtım lisans bildirimi henüz tamamlanmadı.
- İlk Windows derlemesi Türkçe `ı` içeren üst klasör yolunda AGP yol kontrolünde durdu. Kaynak klasörünü taşımak yerine AGP’nin deneysel `android.overridePathCheck=true` seçeneği kullanıldı.

## Aşama durumu

| Aşama | Durum | Not |
|---|---|---|
| 1. Temel | **Sürüyor — kısmi prototip** | Compose sohbet ekranı, yerel geçmiş, basit Markdown, yeni sohbet/oturum seçimi ve belge seçicisi mevcut. Gerçek asistan yanıtı, görsel önizleme ve ekin içeriğini modele aktarma yok; bu nedenle aşama bitmiş sayılmadı. |
| 2. Sağlayıcı katmanı | Başlamadı | OpenAI uyumlu/Anthropic istekleri, `/v1/models`, API anahtarı/Android Keystore ve ücretsiz model koşulları doğrulanmadı. |
| 3. Ajan çekirdeği | Başlamadı | Dosya/shell/git araçları, onay akışı, proje sınırı, bağlam sıkıştırma ve ön plan servisi yok. |
| 4. Çoklu ajan | Başlamadı | Yok. |
| 5. GitHub ve bulut derleme | Başlamadı | OAuth, GitHub Actions/Secrets/Release veya uçtan uca proje üretim testi yok. |
| 6. Tarayıcı devri | Başlamadı | Yok. |
| 7. Cihaz içi derleme | Başlamadı | Yok. |
| 8. Yerel model | Başlamadı | Yok. |
| 9. Cilalama | Başlamadı | İlk UI turu yapıldı; geniş ekranlar tam QA edilmedi. |

## Aşama 1 denetimi

- Derleme: `./gradlew.bat :app:assembleDebug` başarılı. Uyarılar: Android pencere sistem çubuğu API’si için deprecated uyarıları; ayrıca `android.overridePathCheck` deneysel. APK debug anahtarıyla imzalı.
- Emülatör: Kullanıcının belirttiği gibi başka ajan tarafından kullanılan `emulator-5554` kullanılmadı. Kendi `Bende_Test_API_37` API 37 emülatörü `emulator-5558` portunda oluşturulup kullanıldı.
- Senaryo: Uygulama açıldı; Downloads içinden `bende-sample.txt` seçildi, metin mesajına eklenip gönderildi; ek dosya tek başına da gönderildi. Başlık ve ek ekranda göründü. Uygulama kapatılıp açıldığında mesaj ve yeni kayıt biçimindeki ek adı korundu. Geçmiş çekmecesi açıldı ve seçili oturum gösterildi.
- Uyarlanabilirlik: Aynı emülatör penceresi 1080×2400 (telefon), 1800×2400 (~686 dp orta) ve 2400×2400 (~914 dp geniş) boyutlarına ayarlandı. Orta genişlikte sohbet görünümü; 840 dp üstünde kalıcı yan gezinme gözlendi. Bu çözünürlük değişimi tablet/katlanabilir fiziksel aygıt testi değildir.
- Kanıtlar: `Sonuç/Kanitlar/chat-scenario.png`, `attachment-sent.png`, `persisted-chat.png`, `history-drawer.png`, `tablet-window.png`, `expanded-window.png`, `emulator-session.log`.
- Hata gözlemi: Son uygulama oturumu için yakalanan log’da `FATAL EXCEPTION` yok. Bu tek senaryo log’u genel kararlılık kanıtı değildir.
- Commit: Aşama henüz tamamlanmadı; kaynak ve kanıtlar ilk geliştirme kontrol noktası olarak commit edilecek.

## DOĞRULANMADI

- Gerçek telefon/tablet/katlanabilir cihaz performansı, RAM ve pil davranışı; erişilebilirlik denetimi; TalkBack ve klavye/fare kullanımı.
- OpenAI/Anthropic uç noktaları, kimlik bilgisi şifreleme, sağlayıcı lisans/şartları ve OpenCode ücretsiz model erişim şartları.
- Android 10+ arka plan derleme kısıtları, Android 12+ phantom process sınırı, aarch64 araçları, GitHub OAuth/Actions kota ve secrets davranışı.
- Release anahtarıyla imzalama, güncelleme imza sürekliliği, APK kurulumu/upgrade testi, yayınlama ve GitHub Release.

## Resmî kaynaklar

- Android Developers, [Use a Bill of Materials — Jetpack Compose](https://developer.android.com/develop/ui/compose/bom): kullanılan `2026.09.00` BOM ve Compose kitaplıklarının eşleşmesi.
- Android Developers, [AGP 9.0 release notes](https://developer.android.com/build/releases/agp-9-0-0-release-notes): AGP 9 ile yerleşik Kotlin desteği.
- Android Developers, [Window size classes](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes): pencere genişliği sınıfları ve aygıt boyutu yerine mevcut pencereyi temel alma.
- Android Developers, [ActivityResultContracts.OpenDocument](https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.OpenDocument): kullanıcı denetimli belge seçimi ve URI erişimi.
- AndroidX, [Lisans](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt): Jetpack kitaplıklarının lisans bilgisi.

## Bilinen sorunlar

- Mesaj gönderilince yalnızca yerel geçmişe yazılır; yanıt bekleme göstergesi veya sağlayıcı hatası durumu yoktur.
- Markdown yalnızca başlık ve basit madde işaretlerini işler; tam CommonMark/kod bloğu gösterimi değil.
- Ekin adı/URI’si sohbet kaydına eklenir; dosya baytları kopyalanmaz veya modele aktarılmaz. İçerik URI’sinin gelecekte kullanımında sağlayıcı erişiminin iptal edilmesi ele alınmalıdır.
- Geçmiş oturumu silme/yeniden adlandırma ve veri dışa/içe aktarma yok.
- Koyu tema, Android tablet/katlanabilir üzerinde gerçek test ve tam erişilebilirlik incelemesi yok.
