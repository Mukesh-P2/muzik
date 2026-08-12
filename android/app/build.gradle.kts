import java.net.URI
import java.net.URISyntaxException
import java.util.Properties
import org.gradle.api.GradleException

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}
val configuredMuzikServerUrl = (
    providers.gradleProperty("MUZIK_SERVER_URL").orNull
        ?: localProperties.getProperty("MUZIK_SERVER_URL")
    )
    ?.trim()
    ?.takeIf(String::isNotEmpty)
val debugMuzikServerUrl = configuredMuzikServerUrl ?: "http://10.0.2.2:8080"
val releaseMuzikServerUrl = configuredMuzikServerUrl.orEmpty()

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val validateReleaseServerUrl = tasks.register("validateReleaseServerUrl") {
    group = "verification"
    description = "Validates the explicit HTTPS server URL required by release builds."
    inputs.property("muzikServerUrl", releaseMuzikServerUrl)

    doLast {
        val value = configuredMuzikServerUrl ?: throw GradleException(
            "Release builds require an explicit MUZIK_SERVER_URL. Set it with " +
                "-PMUZIK_SERVER_URL=https://..., ORG_GRADLE_PROJECT_MUZIK_SERVER_URL, " +
                "or android/local.properties.",
        )
        val uri = try {
            URI(value)
        } catch (_: URISyntaxException) {
            null
        }
        if (
            uri == null ||
            !uri.isAbsolute ||
            !uri.scheme.equals("https", ignoreCase = true) ||
            uri.host.isNullOrBlank()
        ) {
            throw GradleException(
                "Release MUZIK_SERVER_URL must be an absolute HTTPS URL; received '$value'.",
            )
        }
    }
}

fun releaseSigningValue(name: String): String? = providers.gradleProperty(name)
    .orElse(providers.environmentVariable(name))
    .orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)

val releaseStoreFile = releaseSigningValue("MUZIK_RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningValue("MUZIK_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningValue("MUZIK_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningValue("MUZIK_RELEASE_KEY_PASSWORD")
val releaseSigningValues = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)

if (releaseSigningValues.any { it != null } && releaseSigningValues.any { it == null }) {
    throw GradleException(
        "Release signing is only partially configured. Set all four MUZIK_RELEASE_* values.",
    )
}

android {
    namespace = "com.muzik.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.muzik.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningValues.all { it != null }) {
            create("release") {
                storeFile = rootProject.file(checkNotNull(releaseStoreFile))
                storePassword = checkNotNull(releaseStorePassword)
                keyAlias = checkNotNull(releaseKeyAlias)
                keyPassword = checkNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "SERVER_URL", buildConfigString(debugMuzikServerUrl))
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        release {
            buildConfigField("String", "SERVER_URL", buildConfigString(releaseMuzikServerUrl))
            isMinifyEnabled = true
            signingConfig = signingConfigs.findByName("release")
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

tasks.configureEach {
    if (name != validateReleaseServerUrl.name && name.contains("Release")) {
        dependsOn(validateReleaseServerUrl)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-okhttp:3.0.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-client-websockets:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
