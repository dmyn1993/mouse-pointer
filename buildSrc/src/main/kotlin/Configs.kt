import org.gradle.api.JavaVersion

object Configs {
    const val compileSdk = 36
    const val minSdk = 35
    const val targetSdk = 36

    const val namespace = "com.dmyn.mousepointer"
    const val applicationId = "com.dmyn.mousepointer"
    const val versionCode = 2
    const val versionName = "1.1.0"

    val javaVersion = JavaVersion.VERSION_11
    const val jvmTarget = "11"
}
