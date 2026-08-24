plugins {
    alias(libs.plugins.driftwood.jvm.library)
}

dependencies {
    api(libs.bouncycastle)

    testImplementation(libs.junit)
}
