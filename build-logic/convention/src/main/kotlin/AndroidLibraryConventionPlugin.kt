import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")

        extensions.configure<LibraryExtension> {
            compileSdk = ProjectConfig.COMPILE_SDK

            defaultConfig { minSdk = ProjectConfig.MIN_SDK }

            compileOptions {
                sourceCompatibility = ProjectConfig.JAVA_VERSION
                targetCompatibility = ProjectConfig.JAVA_VERSION
            }

            buildFeatures {
                aidl = false
                buildConfig = false
                shaders = false
            }
        }

        extensions.configure<KotlinAndroidProjectExtension> {
            jvmToolchain(ProjectConfig.JVM_TOOLCHAIN)
        }
    }
}
