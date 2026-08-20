import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")

        extensions.configure<ApplicationExtension> {
            compileSdk = ProjectConfig.COMPILE_SDK

            defaultConfig {
                minSdk = ProjectConfig.MIN_SDK
                targetSdk = ProjectConfig.TARGET_SDK
                versionCode = 1
                versionName = "1.0"
            }

            compileOptions {
                sourceCompatibility = ProjectConfig.JAVA_VERSION
                targetCompatibility = ProjectConfig.JAVA_VERSION
            }

            buildFeatures {
                aidl = false
                buildConfig = false
                shaders = false
            }

            packaging {
                resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
            }
        }

        // AGP 9 has built-in Kotlin, so org.jetbrains.kotlin.android is neither needed
        // nor allowed — but the extension it registers is still the JetBrains one.
        extensions.configure<KotlinAndroidProjectExtension> {
            jvmToolchain(ProjectConfig.JVM_TOOLCHAIN)
        }
    }
}
