# Bende

**Bende**, Kotlin ve Jetpack Compose ile geliştirilen Android yapay zekâ ajanı projesidir.

## Mevcut durum

Bu ilk geliştirme yapısıdır. Açık mavi-beyaz sohbet arayüzü, oturum geçmişi, basit Markdown gösterimi, dosya seçici ve yerel sohbet kaydı bulunur. Model sağlayıcısı henüz bağlanmadığından mesajlar bir yapay zekâ servisine gönderilmez ve asistan yanıtı üretilmez. Seçilen dosyanın adı ve uygulamanın açabildiği kalıcı içerik URI’si oturuma kaydedilir; dosya içeriği henüz modele aktarılmaz.

## Emülatörde kurulum

`Sonuç/Bende-debug-0.1.0.apk` dosyasını Android telefona veya emülatöre aktarın ve yükleyin. Bu APK, yerel geliştirme ve deneme için Android debug anahtarıyla imzalanmıştır; yayıma hazır bir imza değildir.

## Kaynaktan derleme

1. JDK 21 ve Android SDK’yı kurun.
2. `Malzemeler/bende-android` klasörünü Android Studio’da açın veya o klasörde `gradlew.bat :app:assembleDebug` çalıştırın.
3. Debug APK `app/build/outputs/apk/debug/app-debug.apk` konumunda oluşur.

Gradle Wrapper 9.8.1’i indirip sabitler. Windows’ta çalışma alanının Türkçe karakter içeren klasör adı Android Gradle Plugin yol denetimini tetiklediği için `android.overridePathCheck=true` ayarı kullanılır.

## Planlananlar

OpenAI uyumlu/Anthropic sağlayıcıları, Keystore ile anahtar saklama, model yanıtları, araç kullanan ajan, GitHub entegrasyonu, yerel derleme/model, tarayıcı devri ve yayın imzası henüz geliştirilmemiştir. Aşama kayıtları, test kanıtları ve doğrulanmayanlar için [`PROGRESS.md`](PROGRESS.md) dosyasına bakın.
