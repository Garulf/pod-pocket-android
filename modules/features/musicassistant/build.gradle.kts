plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "au.com.shiftyjelly.pocketcasts.musicassistant"
    buildFeatures {
        buildConfig = true
        compose = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    ksp(libs.dagger.hilt.compiler)
    ksp(libs.hilt.compiler)
    ksp(libs.moshi.kotlin.codegen)

    api(libs.dagger.hilt.android)
    api(libs.okhttp)
    api(projects.modules.services.repositories)

    implementation(platform(libs.compose.bom))
    implementation(projects.modules.services.compose)
    implementation(projects.modules.services.images)
    implementation(projects.modules.services.ui)
    implementation(projects.modules.services.views)
    implementation(libs.androidx.mediarouter)
    implementation(libs.compose.material)
    implementation(libs.compose.ui)
    implementation(libs.coroutines.core)
    implementation(libs.fragment.ktx)
    implementation(libs.lifecycle.process)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.moshi)
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("androidx.startup:startup-runtime:1.2.0")

    testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit)
    testImplementation(libs.okHttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(projects.modules.services.selfhosted)
}
