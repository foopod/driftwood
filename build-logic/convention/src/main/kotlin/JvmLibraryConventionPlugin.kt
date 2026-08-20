import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Pure Kotlin/JVM module — no AGP, no Android SDK, no Robolectric.
 *
 * The canonical message format and the crypto live in modules built with this plugin so
 * their tests run as plain JVM tests: fast, and exercising exactly the same code the
 * device runs. That property is the whole point of M0 (plan.md §8).
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(ProjectConfig.JVM_TOOLCHAIN)
        }

        tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
            useJUnit()
        }
    }
}
