import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Resolve the key relative to its private properties file, never to a checked-in path.
val uploadProperties = providers.environmentVariable("HEX_ANDROID_KEYSTORE_PROPERTIES").orNull
    ?.let(::file) ?: File(System.getProperty("user.home"), ".config/hex-android/keystore.properties")
val upload = Properties().apply {
    if (uploadProperties.isFile) uploadProperties.inputStream().use(::load)
}
val requireUploadKey by tasks.registering {
    doLast {
        check(listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !upload.getProperty(it).isNullOrBlank() } &&
            uploadProperties.parentFile.resolve(upload.getProperty("storeFile")).isFile) {
            "Release packaging requires a local upload keystore and keystore.properties."
        }
    }
}

android {
    namespace = "ly.anoma.hex"
    compileSdk = 36

    defaultConfig {
        applicationId = "ly.anoma.hex"
        minSdk = 31
        targetSdk = 36
        versionCode = 5
        versionName = "0.2.2"
    }
    signingConfigs {
        create("upload") {
            storeFile = upload.getProperty("storeFile")?.let { uploadProperties.parentFile.resolve(it) }
            storePassword = upload.getProperty("storePassword")
            keyAlias = upload.getProperty("keyAlias")
            keyPassword = upload.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("upload")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    packaging.resources.excludes += "DebugProbesKt.bin"
    buildFeatures { compose = true }
    // The keyboard engine's tests build EditorInfo and a Handler; Android's stubs return defaults.
    testOptions { unitTests.isReturnDefaultValues = true }
    androidResources { noCompress += listOf("hextm", "valid", "capitals") }
    // The same plain-text Android policy ships in Settings and in the Play listing drafts.
    sourceSets["main"].assets.srcDir(rootProject.file("play/policy"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin.compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
tasks.matching { it.name in setOf("packageRelease", "packageReleaseBundle", "signReleaseBundle", "bundleRelease", "assembleRelease") }
    .configureEach { dependsOn(requireUploadKey) }
// Unmodified Google Sans variable font (google/fonts, ofl/googlesans), the face Gboard draws its keys in.
val verifyFont by tasks.registering {
    val font = file("src/main/res/font/google_sans.ttf")
    inputs.file(font)
    doLast {
        check(MessageDigest.getInstance("SHA-256").digest(font.readBytes()).joinToString("") { "%02x".format(it) }
            == "d0a87d835a944b8b40d0e82a5651bb59ab97b936a2aeed5946eb57e7b2a3a90a") { "Google Sans checksum mismatch" }
    }
}
// Pin the licensed HXTM files. An older prototype word-pair list cannot slip into an APK.
val verifyTypingModels by tasks.registering {
    val dir = file("src/main/assets/typing")
    val names = listOf("en.hextm", "es.hextm", "de.hextm", "nb.hextm", "fr.hextm", "it.hextm", "en.valid",
        "es.valid", "de.valid", "nb.valid", "fr.valid", "it.valid",
        "en.capitals", "es.capitals", "de.capitals", "fr.capitals", "it.capitals")
    val manifest = dir.resolve("SHA256SUMS")
    inputs.files(names.map(dir::resolve) + manifest)
    doLast {
        val entries = manifest.readLines().map { it.trim().split(Regex("\\s+")) }.associate { it.last() to it.first() }
        check(entries.keys == names.toSet())
        for (name in names) {
            val actual = MessageDigest.getInstance("SHA-256").digest(dir.resolve(name).readBytes()).joinToString("") { "%02x".format(it) }
            check(entries[name] == actual) { "Unverified typing model: $name" }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyFont, verifyTypingModels) }

dependencies {
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    testImplementation("junit:junit:4.13.2")
}
