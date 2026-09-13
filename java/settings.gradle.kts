pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "remote-connect-mcp-java"

include(":protocol", ":center", ":agent")

project(":protocol").projectDir = file("protocol")
project(":center").projectDir = file("center")
project(":agent").projectDir = file("agent")
