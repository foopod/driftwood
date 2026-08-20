import org.gradle.api.JavaVersion

/**
 * Single source for the values every module shares. Changing an SDK level or the
 * toolchain happens here and nowhere else.
 *
 * MIN_SDK is 33 by design, not convenience: Wi-Fi Direct discovery on API 32 and below
 * requires ACCESS_FINE_LOCATION, and the app is not going to ask for location permission
 * in order to talk to the phone next to it. See plan.md §7.
 */
object ProjectConfig {
    const val COMPILE_SDK = 36
    const val TARGET_SDK = 36
    const val MIN_SDK = 33

    const val JVM_TOOLCHAIN = 21
    val JAVA_VERSION: JavaVersion = JavaVersion.VERSION_21
}
