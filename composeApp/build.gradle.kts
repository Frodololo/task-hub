import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
    alias(libs.plugins.crashlytics)
}

// ── Release signing (keystore.properties NOT in git) ────────
val keystoreProperties = Properties().apply {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) {
        propsFile.inputStream().use { load(it) }
    }
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        moduleName = "composeApp"
        browser {
            commonWebpackConfig {
                outputFileName = "composeApp.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            // Coordenada Maven fija, no ligada a `compose-multiplatform` del
            // catálogo: material-icons-core no publica release 1.8.0 (el
            // último artefacto disponible en Maven es 1.7.3) — un intento de
            // interpolar la versión del catálogo aquí rompe la resolución de
            // dependencias (verificado: "Could not find ...material-icons-core:1.8.0").
            implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)

            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)

            implementation(libs.voyager.navigator)
            implementation(libs.voyager.screenmodel)
            implementation(libs.voyager.koin)
            implementation(libs.voyager.transitions)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.multiplatform.settings)

            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)

            implementation(libs.napier)
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
            implementation(compose.preview)
            implementation("io.github.g0dkar:qrcode-kotlin:4.3.0")

            // Firebase Cloud Messaging
            implementation("com.google.firebase:firebase-messaging-ktx:24.1.0")

            // Firebase App Check (Play Integrity) — impide que scripts externos
            // invoquen las Cloud Functions directamente sin pasar por esta app.
            implementation("com.google.firebase:firebase-appcheck-playintegrity:18.0.0")

            // Google Sign-In + Calendar
            implementation("com.google.android.gms:play-services-auth:21.2.0")

            // WorkManager para notificaciones programadas
            implementation("androidx.work:work-runtime-ktx:2.9.1")

            // Google Play Services (FCM requiere la task de los servicios de Play)
            implementation("com.google.android.gms:play-services-base:18.5.0")

            // Firebase Crashlytics — reporte de crashes
            implementation(libs.firebase.crashlytics)

            // Firebase Analytics — eventos y métricas para el marketing
            implementation(libs.firebase.analytics)

            // AdMob — anuncios (interstitial + banner preparado)
            implementation("com.google.android.gms:play-services-ads:23.5.0")

            // User Messaging Platform (UMP) — CMP compatible con TCF v2,
            // obligatorio en EEE/Reino Unido antes de inicializar AdMob (ver
            // ConsentManager.android.kt). Sin esto, la cuenta de AdMob arriesga
            // suspensión (aprobado 2026-09-26).
            implementation("com.google.android.ump:user-messaging-platform:3.1.0")

            // In-App Updates — actualización forzada (modo IMMEDIATE)
            implementation("com.google.android.play:app-update:2.1.0")

            // EncryptedSharedPreferences (Jetpack Security) — cifra los refresh
            // tokens (ver storage/SecureStore.android.kt, hallazgo B1 del panel
            // de seguridad v3).
            implementation("androidx.security:security-crypto:1.1.0")

            // Dependencia explícita del núcleo de multiplatform-settings: aunque
            // multiplatform-settings-no-arg ya lo trae transitivamente, se declara
            // aquí para que `SharedPreferencesSettings` esté garantizado en el
            // classpath de compilación (usado por SecureStore.android.kt).
            implementation("com.russhwolf:multiplatform-settings:1.2.0")
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }

        jvmMain.dependencies {
            implementation(libs.ktor.client.java)
            implementation(compose.desktop.currentOs)
        }

        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "org.taskhub"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.taskhub"
        minSdk = 26
        targetSdk = 36

        // ── Version — overridable via -P flags for CI/CD ──────
        versionCode = (project.findProperty("versionCodeOverride") as? String)?.toInt() ?: 163
        versionName = (project.findProperty("versionNameOverride") as? String) ?: "0.7.57"
    }

    // ── Release signing ──────────────────────────────────────
    signingConfigs {
        create("release") {
            if (keystoreProperties.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            // Debug uses default debug keystore automatically
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true  // enables BuildConfig.DEBUG for stripping debug code
    }
}

compose.desktop {
    application {
        mainClass = "org.taskhub.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "task-hub"
            packageVersion = "1.0.0"
        }
    }
}
