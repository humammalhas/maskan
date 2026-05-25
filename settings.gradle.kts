pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex(""".*google.*""")
                includeGroupByRegex(""".*android.*""")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    @Suppress("UnstableApiUsage")
    repositories {
        google {
            content {
                includeGroupByRegex(""".*google.*""")
                includeGroupByRegex(""".*android.*""")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "Maskan"
include(":app")

