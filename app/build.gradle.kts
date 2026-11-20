import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    /**
     * MODELE OFFLINE SA POZA DOMYSLNYM BUILDEM.
     *
     * Rozpoznawanie offline (Vosk) wazy 82 MB w modelach i zamienia paczke z 17,7 MB w 99,5 MB.
     * To jest polisa dla urzadzen, na ktorych NIE DZIALA zadna usluga rozpoznawania mowy —
     * a nie domyslny tor. Dlatego modele leza w osobnym katalogu zrodel i wchodza do paczki
     * tylko na zadanie:
     *
     *     ./gradlew assembleDebug                 -> 17,7 MB, silniki systemowe
     *     ./gradlew assembleDebug -Pvosk=true     -> 99,5 MB, z rozpoznawaniem offline
     *
     * Kod Voska jest w obu paczkach; bez modeli sam zglasza, ze nie ma czym rozpoznawac
     * (VoskVoiceInput.maModel), wiec brak assetow nie jest awaria.
     */
    val zModelamiOffline = (project.findProperty("vosk") as String?)?.toBoolean() == true
    if (zModelamiOffline) {
        // CZTERY architektury libvosk.so to 39 MB w paczce, a telefon uruchamia dokladnie jedna.
        // Zostaja te dwie, ktore wystepuja na prawdziwych telefonach. WYLACZNIE dla buildu
        // z Voskiem: w zwyklym wydaniu jedyna biblioteka natywna to libandroidx.graphics.path.so
        // (10 kB na architekture), wiec obciecie ABI nie oszczedzilo by nic, a ODEBRALO BY
        // aplikacji urzadzenia x86_64 (Chromebooki, tablety) — Google Play filtruje po ABI.
        defaultConfig.ndk.abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        sourceSets.getByName("main").assets.srcDir("src/vosk-assets")
        sourceSets.getByName("main").java.srcDir("src/vosk/java")
        sourceSets.getByName("test").java.srcDir("src/vosk/test/java")
    }

    androidResources {
        // Opus jest juz skompresowany - ponowna kompresja w APK nic nie daje,
        // a kosztuje czas rozpakowania przy kazdym odtworzeniu slowka.
        noCompress += listOf("ogg")
    }

    namespace = "com.matura.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.matura.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 35
        versionName = "0.9.18"

    }

    // Klucz do podpisu wydania czytany jest z keystore.properties, ktorego
    // celowo nie ma w repozytorium. Bez tego pliku buduje sie tylko debug.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 wylaczone swiadomie: wiekszosc rozmiaru to grafika PNG, ktorej
            // i tak nie skurczy, a wlaczenie go bez testu na urzadzeniu grozi
            // wywrotka dopiero u testerow. Reguly sa gotowe w proguard-rules.pro.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // brak keystore.properties -> paczka wychodzi niepodpisana,
            // do podpisania osobno (jarsigner) na maszynie, ktora ma klucz
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            // Sufiks tylko dla debug: build testowy instaluje sie OBOK wersji ze sklepu
            // (inny applicationId), wiec mozna sprawdzic poprawki bez odinstalowania i
            // bez utraty lokalnych wynikow. Release pozostaje czyste com.matura.app.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // BuildConfig.DEBUG bramkuje log sciezki glosowej: w buildzie debug chcemy pelny slad
        // (adb logcat -s MaturaVoice), w wersji ze sklepu nie ma lecec linia RMS co pol sekundy.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // ROZPOZNAWANIE MOWY OFFLINE (VoskVoiceInput). Wlasny mikrofon i wlasny dekoder, bez
    // android.speech.RecognitionService — patrz komentarz na gorze VoskVoiceInput.kt.
    //
    // Wersja 0.3.75, a NIE 0.3.47 z wiekszosci poradnikow: biblioteki 64-bitowe w 0.3.75 sa
    // wyrownane do 16 kB (sprawdzone: LOAD align 0x4000 w arm64-v8a i x86_64), a w 0.3.47 do
    // 4 kB (0x1000). Od Androida 15 Google Play odrzuca paczki bez wyrownania 16 kB, wiec
    // 0.3.47 nie dalaby sie wydac. Apache-2.0; ciagnie za soba net.java.dev.jna:jna:5.18.1.
    // Zaleznosc wchodzi TYLKO z -Pvosk=true, tak samo jak kod i modele: same biblioteki
    // natywne libvosk.so to 19 MB na dwa ABI, a bez modeli nie maja czego rozpoznawac.
    if ((project.findProperty("vosk") as String?)?.toBoolean() == true) {
        implementation("com.alphacephei:vosk-android:0.3.75")
    }

    testImplementation("junit:junit:4.13.2")
    // UI i warstwa audio testowane bez emulatora (Robolectric renderuje na JVM).
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // Manifest testowy jest potrzebny OBU wariantom: Robolectric w testDebugUnitTest bierze go
    // ze ścieżki debug, a testReleaseUnitTest z konfiguracji testowej. Sama zamiana debug->test
    // wywraca 20 testów Compose, więc deklarujemy dwa razy.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("androidx.compose.ui:ui-test-manifest")
}
