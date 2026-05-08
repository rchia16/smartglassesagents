import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
    }
}
val metaWearablesApplicationId =
    System.getenv("META_WEARABLES_APPLICATION_ID")
        ?: localProperties.getProperty("meta_wearables_application_id")
        ?: "0"
val githubPackagesToken =
    System.getenv("GITHUB_TOKEN")
        ?: localProperties.getProperty("github_token")
        ?: ""

android {
    namespace = "com.example.smartglassesagents"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.smartglassesagents"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["metaWearablesApplicationId"] = metaWearablesApplicationId
    }

    flavorDimensions += "dat"
    productFlavors {
        create("mockDat") {
            dimension = "dat"
            buildConfigField("String", "DAT_MODE", "\"mock\"")
        }
        create("realDat") {
            dimension = "dat"
            buildConfigField("String", "DAT_MODE", "\"real\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

gradle.taskGraph.whenReady {
    val realDatRequested = allTasks.any { task -> task.name.contains("RealDat") }
    if (realDatRequested) {
        val missing = buildList {
            if (githubPackagesToken.isBlank()) add("GITHUB_TOKEN or local.properties github_token")
            if (metaWearablesApplicationId == "0" || metaWearablesApplicationId.isBlank()) {
                add("META_WEARABLES_APPLICATION_ID or local.properties meta_wearables_application_id")
            }
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Real DAT build is missing setup values: ${missing.joinToString()}. " +
                    "See docs/meta-dat-integration.md."
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    "realDatImplementation"(libs.androidx.exifinterface)
    "realDatImplementation"(libs.mwdat.core)
    "realDatImplementation"(libs.mwdat.camera)
    "realDatImplementation"(libs.mwdat.mockdevice)
}
