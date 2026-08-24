pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Racer"

// :core is a plain Kotlin/JVM module — all the game logic, no Android APIs — so
// it compiles and unit-tests anywhere a JDK exists.
include(":core")

// :app is the Android front end (OpenGL ES renderer, sensors, Compose UI).
// Including it requires the Android Gradle Plugin, which needs the Android SDK,
// so skip it when there is no SDK: that keeps `./gradlew :core:test` working on
// a plain JDK box. CI installs the SDK, so CI always builds the app.
val androidSdk = providers.environmentVariable("ANDROID_HOME").orNull
    ?: providers.environmentVariable("ANDROID_SDK_ROOT").orNull
    ?: file("local.properties").takeIf { it.exists() }
        ?.readLines()
        ?.firstOrNull { it.startsWith("sdk.dir=") }
        ?.substringAfter("=")

if (androidSdk != null || providers.gradleProperty("forceApp").isPresent) {
    include(":app")
} else {
    logger.lifecycle("No Android SDK found — configuring :core only. Set ANDROID_HOME to build the app.")
}
