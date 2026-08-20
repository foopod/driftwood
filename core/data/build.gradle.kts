plugins {
    alias(libs.plugins.gossip.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.jonoshields.gossip.core.data"
}

room {
    // Exported schemas are the migration history, starting from the first real schema.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:store"))
    api(project(":core:identity"))

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
