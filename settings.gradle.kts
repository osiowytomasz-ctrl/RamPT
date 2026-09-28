pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // MPAndroidChart (biblioteka wykresów) hostowana jest na JitPack:
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "RamPT"
include(":app")
