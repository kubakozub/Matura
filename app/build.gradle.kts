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
        versionCode = 25
        versionName = "0.9.8"
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

    testImplementation("junit:junit:4.13.2")
    // UI i warstwa audio testowane bez emulatora (Robolectric renderuje na JVM).
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
