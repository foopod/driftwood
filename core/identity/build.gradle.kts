plugins {
    alias(libs.plugins.driftwood.android.library)
}

android {
    namespace = "com.jonoshields.driftwood.core.identity"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:crypto"))

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
