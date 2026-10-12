# Bende

**Bende**, Kotlin ve Jetpack Compose ile geliştirilen Android yapay zekâ ajanı projesidir.

## Mevcut durum

Bu, erken aşamada ve kurulabilir bir prototiptir. Şu özellikler vardır:

- Compose sohbet arayüzü, oturum geçmişi ve cihazda yerel kayıt.
- Oturum başlığını değiştirme ve onay adımıyla oturum silme.
- Dört ayrı sohbet ajanını eşzamanlı çalıştırma ve her birinin ilerlemesini geçmişte görme.
- Temel Markdown gösterimi.
- Dosya ve görsel eki seçimi. Sağlayıcı ayarlanıp mesaj gönderildiğinde metin dosyaları (256 KB’a kadar) ve görseller (5 MB’a kadar) okunup isteğe eklenir; desteklenmeyen ikili dosyalar yalnızca dosya adıyla bildirilir.
- OpenAI uyumlu Chat Completions ve Anthropic Messages istekleri; `/v1/models` model listesi. Gemini API’nin OpenAI uyumlu adresi ve model listeleme yolu da desteklenir.
- Android Keystore içinde tutulan AES-GCM anahtarıyla API anahtarı şifreleme.
- Android’in sistem klasör seçicisiyle seçilen proje klasöründe metin okuma/arama ve kullanıcı onayıyla dosya yazma/düzenleme/silme için sınırlı ajan araçları.
- Seçilen proje kökündeki `AGENTS.md` talimatlarını sağlayıcıya proje rehberi olarak ekleme (32 KB dosya sınırı).

Sağlayıcı ayar ekranı ve şifreli anahtar saklama API 37 emülatöründe denendi. Gemini için ayar ekranındaki hızlı adresi seçip API anahtarı girdikten sonra modelleri listeleyebilirsin. Gerçek Gemini isteği henüz doğrulanmadı. Mesajlar, seçilen eklerin desteklenen içerikleri ve kök `AGENTS.md` dosyası (varsa), ayarlanan sağlayıcıya gönderilir; talimat dosyasını uygulama içinden önizleyebilirsin. Çoklu ajan akışının gerçek sağlayıcıyla eşzamanlı çalışması henüz doğrulanmadı. OpenCode Zen kendi hesabını ve API anahtarını ister; ücretsiz model erişimi ve veri kullanımı modele göre değişir. Gizlilik şartlarını denetlemediğin hassas dosyaları bu modellere gönderme.

## Emülatörde kurulum

`Sonuç/Bende-debug-0.6.1.apk` dosyasını Android telefona veya emülatöre aktarın ve yükleyin. Bu APK, yerel geliştirme ve deneme için Android debug anahtarıyla imzalanmıştır; yayıma hazır bir imza değildir.

## Kaynaktan derleme

1. JDK 21 ve Android SDK’yı kurun.
2. `Malzemeler/bende-android` klasörünü Android Studio’da açın veya o klasörde `gradlew.bat :app:assembleDebug` çalıştırın.
3. Debug APK `app/build/outputs/apk/debug/app-debug.apk` konumunda oluşur.

Gradle Wrapper 9.8.1’i indirip sabitler. Windows’ta çalışma alanının Türkçe karakter içeren klasör adı Android Gradle Plugin yol denetimini tetiklediği için `android.overridePathCheck=true` ayarı kullanılır.

## Planlananlar

Sağlayıcı bağlantı kodu hazır; gerçek anahtarla canlı istek yapılması henüz doğrulanmadı. Ajanın sınırlı dosya araçları, proje talimatı, yazma onayı ve birden fazla sohbeti eşzamanlı yürütme kodu var, ancak modelle uçtan uca araç ve eşzamanlı ajan turu doğrulanmadı. Shell/Git araçları, GitHub entegrasyonu, cihaz içi derleme/model, tarayıcı devri ve üretim sürüm imzası tamamlanmadı. Aşama durumu, test kanıtları ve doğrulanmayanlar için [`PROGRESS.md`](PROGRESS.md) dosyasına bakın.
