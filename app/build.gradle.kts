plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.friendlyglobal.jacket"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.friendlyglobal.jacket"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "0.3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(libs.junit)
}
