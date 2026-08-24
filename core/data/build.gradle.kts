plugins {
    alias(libs.plugins.driftwood.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.jonoshields.driftwood.core.data"
}

room {
    // Exported schemas are the migration history, starting from the first real schema.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:store"))
    api(project(":core:identity"))
    api(project(":core:sync"))

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    // M4.1: Room's native Paging 3 integration (DAO methods returning PagingSource<Int, T>).
    implementation(libs.androidx.room.paging)
    api(libs.androidx.paging.runtime)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.paging.testing)
}
