plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "au.com.shiftyjelly.pocketcasts.selfhosted"
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    ksp(libs.dagger.hilt.compiler)
    ksp(libs.hilt.compiler)

    api(libs.dagger.hilt.android)
    api(libs.okhttp)

    implementation(libs.coroutines.core)
    implementation(libs.moshi)
    implementation("androidx.lifecycle:lifecycle-viewmodel:${libs.versions.lifecycle.get()}")

    testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit)
    testImplementation(libs.okHttp.mockwebserver)
    testImplementation(libs.robolectric)
}
