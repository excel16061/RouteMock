import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The map provider can be replaced without touching tracked files. A style URL
// that embeds an API key belongs in the git-ignored local.properties, never here.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
// Fails the build rather than shipping an address that the app would have to refuse.
fun httpsProperty(name: String, default: String): String {
    val value = localProperties.getProperty(name, default)
    require(value.startsWith("https://")) { "$name must be an https:// address" }
    return value
}
fun buildConfigString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.routemock.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.routemock.app"
        minSdk = 29 // Android 10; the test phone runs Android 13 (API 33).
        targetSdk = 36
        versionCode = 11
        versionName = "0.4.7"

        buildConfigField(
            "String",
            "MAP_STYLE_URL",
            buildConfigString(
                httpsProperty("routemock.mapStyleUrl", "https://tiles.openfreemap.org/styles/liberty"),
            ),
        )
        // FOSSGIS asks that its URLs not be fixed in the app, so this can be overridden.
        buildConfigField(
            "String",
            "ROUTING_BASE_URL",
            buildConfigString(
                httpsProperty("routemock.routingBaseUrl", "https://valhalla1.openstreetmap.de"),
            ),
        )
        buildConfigField(
            "String",
            "ROUTING_API_KEY",
            buildConfigString(localProperties.getProperty("routemock.routingApiKey", "")),
        )
        buildConfigField(
            "String",
            "ROUTING_PROVIDER_NAME",
            buildConfigString(localProperties.getProperty("routemock.routingProviderName", "Valhalla (FOSSGIS public server)")),
        )
        buildConfigField(
            "String",
            "ROUTING_ATTRIBUTION",
            buildConfigString(
                localProperties.getProperty(
                    "routemock.routingAttribution",
                    "Routing: Valhalla on the FOSSGIS server. Data © OpenStreetMap contributors (ODbL), CC-BY-SA",
                ),
            ),
        )
        // Keep this false for any provider whose terms have not been checked.
        buildConfigField(
            "boolean",
            "ROUTING_ALLOWS_STORAGE",
            localProperties.getProperty(
                "routemock.routingAllowsStorage",
                (!localProperties.containsKey("routemock.routingBaseUrl")).toString(),
            ),
        )
        // Empty means the default OpenFreeMap attribution, which is shown with links.
        buildConfigField(
            "String",
            "MAP_ATTRIBUTION",
            buildConfigString(localProperties.getProperty("routemock.mapAttribution", "")),
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    testOptions {
        unitTests.isReturnDefaultValues = false
    }
}

// BackupRulesTest reads these directly, so changing them must rerun the tests.
tasks.withType<Test>().configureEach {
    inputs.files("src/main/AndroidManifest.xml", "src/main/res/xml")
        .withPropertyName("backupRuleSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.maplibre.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
