plugins {
    alias(libs.plugins.gossip.jvm.library)
}

dependencies {
    api(libs.bouncycastle)

    testImplementation(libs.junit)
}
