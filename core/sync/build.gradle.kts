plugins {
    alias(libs.plugins.gossip.jvm.library)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:store"))

    // The port is suspending: a real store does I/O, and the session must not pretend
    // otherwise just because the fake happens to be instant.
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
