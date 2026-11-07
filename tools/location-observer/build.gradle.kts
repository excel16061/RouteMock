plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.routemock.observer"
    compileSdk = 36

    defaultConfig {
        // Separate from RouteMock on purpose: this must be a different app to prove delivery.
        applicationId = "com.routemock.observer"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// ManifestPolicyTest reads this file directly, so changing it must rerun the tests.
tasks.withType<Test>().configureEach {
    inputs.files("src/main/AndroidManifest.xml", "src/main/res/xml")
        .withPropertyName("manifestPolicySources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// No runtime dependencies: nothing can add a permission through manifest merging.
dependencies {
    testImplementation(libs.junit)
}
