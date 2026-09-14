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

include(":protocol", ":center", ":agent", ":desktop", ":browser")

project(":protocol").projectDir = file("protocol")
project(":center").projectDir = file("center")
project(":agent").projectDir = file("agent")
project(":desktop").projectDir = file("desktop")
project(":browser").projectDir = file("browser")
