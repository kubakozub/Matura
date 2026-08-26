import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.android.compose.screenshot")
}

android {
    namespace = "com.matura.app"
    compileSdk = 36

    // Renderowanie podgladow Compose na JVM (Layoutlib). Flaga musi byc i tutaj,
    // i w gradle.properties - sama gradle.properties nie wystarcza.
    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    defaultConfig {
        applicationId = "com.matura.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 10
        versionName = "0.9.0"
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

    // Ekrany renderowane na JVM, zeby dalo sie obejrzec uklad na tablecie i skladanym
    // bez posiadania jednego i drugiego.
    screenshotTestImplementation(composeBom)
    screenshotTestImplementation("androidx.compose.ui:ui-tooling")
}
