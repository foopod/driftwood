plugins {
    alias(libs.plugins.gossip.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.jonoshields.gossip.core.database"
}

room {
    // Exported schemas are the migration history. Starting it now means M1's first real
    // schema has a baseline to migrate from rather than being version 1 forever.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
}
