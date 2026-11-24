import java.security.MessageDigest
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// An update only installs over an older copy when both are signed with the same key. CI used to
// generate a fresh random debug key on every run, which caused "package conflicts with an
// existing package". The key now comes from a fixed file so every build has the same signature.
// It is a throwaway key meant for sideloading, not for publishing to a store.
val keystoreText = file("stargazer-debug.keystore.b64")
val keystoreFile = file("stargazer-debug.keystore")
if (!keystoreFile.exists()) {
    val text = keystoreText.readText().trim()
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
    check(digest == "2182b2dd4ead5865d5b6843ce6710c20c7bcb83d798a950ba4282d064902e759") {
        "stargazer-debug.keystore.b64 is damaged (checksum $digest)"
    }
    keystoreFile.writeBytes(Base64.getDecoder().decode(text))
}

android {
    namespace = "com.faststrap.dev"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.faststrap.dev"
        minSdk = 26
        targetSdk = 35
        // Each CI run gets a higher number, so a newer build always counts as an update.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("stargazer") {
            storeFile = keystoreFile
            storePassword = "stargazer"
            keyAlias = "stargazer"
            keyPassword = "stargazer"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stargazer")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stargazer")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    implementation("androidx.core:core-ktx:1.10.1")

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.3.0")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
