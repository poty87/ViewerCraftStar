import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.poty87.viewercraftstar"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.poty87.viewercraftstar"
        minSdk = 33 // Android 13: permesso READ_MEDIA_IMAGES
        targetSdk = 35
        versionCode = 85
        versionName = "0.41.0"
        // librerie native (ONNX Runtime per la ricerca IA): solo telefoni a 64 bit (tutti quelli con Android 13+)
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // firma delle release: chiave e password in keystore.properties (fuori dal repository, mai pubblicati)
    val keys = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }
    }
    signingConfigs {
        if (keys != null) create("release") {
            storeFile = rootProject.file(keys.getProperty("storeFile"))
            storePassword = keys.getProperty("storePassword")
            keyAlias = keys.getProperty("keyAlias")
            keyPassword = keys.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            if (keys != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; aidl = true }
    packaging {
        // librerie native compresse nell'APK: il download è molto più piccolo (si estraggono all'installazione)
        jniLibs { useLegacyPackaging = true }
        resources { excludes += setOf("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    // maschere «Oggetto (IA)»: MagicTouch con LiteRT (GPU del telefono, CPU se la GPU non parte)
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    implementation("com.google.ai.edge.litert:litert-gpu:1.4.2")
    implementation("com.google.ai.edge.litert:litert-gpu-api:1.4.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // SMB2/3 verso il NAS (Apache 2.0)
    implementation("com.hierynomus:smbj:0.13.0")
    // elenco delle cartelle condivise del NAS (servizio srvsvc via SMB), per sceglierle invece di scriverle a mano
    implementation("com.rapid7.client:dcerpc:0.12.13")
    // ricerca per soggetti: la parte "testo" del modello IA sul telefono (MIT)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.32.0")
    testImplementation("junit:junit:4.13.2")
    // org.json vero nei test sul PC (quello di Android lì è solo uno stub)
    testImplementation("org.json:json:20240303")
}
