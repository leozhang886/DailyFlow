pluginManagement { repositories { mavenCentral(); google(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google { content { includeGroupByRegex("androidx\\..*"); includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google\\.android.*") } }
    }
}
rootProject.name = "DailyFlow"
include(":app")
