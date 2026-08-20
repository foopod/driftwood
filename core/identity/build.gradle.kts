plugins {
    alias(libs.plugins.gossip.android.library)
}

android {
    namespace = "com.jonoshields.gossip.core.identity"
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
