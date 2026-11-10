import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from keystore.properties (kept out of git; see RELEASING.md). Without it,
// release builds are signed with the debug key so test builds keep installing over each other.
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

android {
    namespace = "io.github.friendlyglobal.jacket"
    compileSdk = 37

    defaultConfig {
        // The store identity (usefriendly.app); the code keeps its original package name.
        applicationId = "app.usefriendly.jacket"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "1.0.0-rc.3"
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("upload") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinks and optimizes the release build (about half the size).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Readium needs newer Java library APIs than minSdk 26 ships; desugaring backports them.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // Lists the app's languages for Android 13+'s per-app language setting.
        generateLocaleConfig = true
    }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.window.core.ExperimentalWindowApi",
            "org.readium.r2.shared.ExperimentalReadiumApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.window)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.palette)
    implementation(libs.webkit)
    implementation(libs.graphics.shapes)
    implementation(libs.coroutines.android)
    implementation(libs.appcompat)
    implementation(libs.fragment.compose)
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    implementation(libs.readium.tts)

    implementation(libs.media3.session)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
