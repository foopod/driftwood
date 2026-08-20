import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Turns on Compose for a module that already has the application or library plugin.
 * Applied alongside one of those, never on its own.
 *
 * CommonExtension is generic over half a dozen type parameters, so rather than fight it
 * we configure whichever concrete extension is actually present.
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        val application = extensions.findByType(ApplicationExtension::class.java)
        val library = extensions.findByType(LibraryExtension::class.java)

        when {
            application != null -> application.buildFeatures.compose = true
            library != null -> library.buildFeatures.compose = true
            else -> error(
                "gossip.android.compose requires gossip.android.application or " +
                    "gossip.android.library to be applied first (module: $path)"
            )
        }
    }
}
