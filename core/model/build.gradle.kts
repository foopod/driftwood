plugins {
    alias(libs.plugins.gossip.jvm.library)
}

dependencies {
    implementation(project(":core:crypto"))

    testImplementation(libs.junit)
}
