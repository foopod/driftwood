plugins {
    `kotlin-dsl`
}

group = "com.jonoshields.gossip.buildlogic"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "gossip.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "gossip.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "gossip.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("jvmLibrary") {
            id = "gossip.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
    }
}
