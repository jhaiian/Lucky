import com.jhaiian.lucky.gradle.CargoNdkBuild
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { stream ->
        localProperties.load(stream)
    }
}

val skipMinify = providers.gradleProperty("skipMinify").isPresent

val luckyMinSdk = 26
val luckyNdkVersion = "30.0.16248370"
val luckyNdkFromEnv = providers.environmentVariable("ANDROID_NDK_HOME")
    .orElse(providers.environmentVariable("ANDROID_NDK_ROOT"))
val luckyAbis = (providers.gradleProperty("luckyAbis").orNull ?: "arm64-v8a,armeabi-v7a")
    .split(",")
    .map { it.trim() }
    .filter { it.isNotEmpty() }

android {
    namespace = "com.jhaiian.lucky"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    ndkVersion = luckyNdkVersion

    defaultConfig {
        applicationId = "com.jhaiian.lucky"
        minSdk = luckyMinSdk
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    val hasSigningConfig = localProperties.getProperty("signingConfig.storePassword") != null

    if (hasSigningConfig) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(localProperties.getProperty("signingConfig.storeFile", "app/release_keystore.jks"))
                storePassword = localProperties.getProperty("signingConfig.storePassword")
                keyAlias = localProperties.getProperty("signingConfig.keyAlias")
                keyPassword = localProperties.getProperty("signingConfig.keyPassword")
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*luckyAbis.toTypedArray())
            isUniversalApk = luckyAbis.size > 1
        }
    }

    packaging {
        jniLibs {
            excludes += listOf("lib/x86/**", "lib/x86_64/**", "lib/riscv64/**")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    flavorDimensions += "distribution"

    productFlavors {
        create("github") {
            dimension = "distribution"
            buildConfigField("boolean", "IS_FOSS", "false")
        }
        create("foss") {
            dimension = "distribution"
            buildConfigField("boolean", "IS_FOSS", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = !skipMinify
            isShrinkResources = !skipMinify
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigningConfig) {
                signingConfig = signingConfigs["release"]
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    bundle {
        language {
            enableSplit = false
        }
    }
}

val cargoBuildLuckyEngine = tasks.register<CargoNdkBuild>("cargoBuildLuckyEngine") {
    group = "build"
    description = "Builds the Lucky Rust engine for the selected Android ABIs."
    abis.set(luckyAbis)
    apiLevel.set(luckyMinSdk)
    engineDir.set(rootProject.layout.projectDirectory.dir("engine"))
    ndkPath.set(luckyNdkFromEnv)
    if (!luckyNdkFromEnv.isPresent) {
        ndkDir.set(androidComponents.sdkComponents.ndkDirectory)
    }
    outputDir.set(layout.buildDirectory.dir("rustJniLibs"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoBuildLuckyEngine, CargoNdkBuild::getOutputDir)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material3.adaptive:adaptive")
    implementation("androidx.compose.material3.adaptive:adaptive-layout")
    implementation("androidx.compose.material3.adaptive:adaptive-navigation")
    implementation("androidx.activity:activity-compose:1.13.0")
}
