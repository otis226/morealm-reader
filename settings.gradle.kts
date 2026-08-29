// Vietnamese standalone fork: GitHub Actions checks out this branch, applies UI
// localization, then invokes Gradle. Run the functional TTS patch immediately before
// project configuration so the compiled APK includes the Edge/System voice fixes.
if (System.getenv("CI") == "true") {
    val process = ProcessBuilder("python3", "tools/fix_vietnamese_tts.py")
        .inheritIO()
        .start()
    val exitCode = process.waitFor()
    check(exitCode == 0) { "Vietnamese TTS patch failed with exit code $exitCode" }
}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "MoRealm"
include(":app")