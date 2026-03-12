pluginManagement {
    repositories {
        maven(url = "https://dl.google.com/dl/android/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven(url = "https://dl.google.com/dl/android/maven2/")
        mavenCentral()
    }
}

rootProject.name = "OfflineVoiceInput"
include(":app")
include(":model_assets")
