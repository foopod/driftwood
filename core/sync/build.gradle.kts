plugins {
    alias(libs.plugins.gossip.jvm.library)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:store"))

    testImplementation(libs.junit)
}
