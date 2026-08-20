plugins {
    alias(libs.plugins.gossip.jvm.library)
}

dependencies {
    api(project(":core:model"))

    testImplementation(libs.junit)
}
