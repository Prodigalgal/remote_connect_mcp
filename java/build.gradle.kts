plugins {
    java
    id("org.springframework.boot") version "4.1.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("org.graalvm.buildtools.native") version "1.1.11" apply false
}

// The development host is intentionally not a build machine.  Keep the
// policy at the Gradle configuration boundary as well as in the convenience
// scripts, so an IDE or a direct wrapper invocation cannot start a memory-
// intensive compile by accident.  GitHub-hosted runners set GITHUB_ACTIONS;
// read-only tasks such as `tasks` and `dependencies` remain available locally.
val localBuildTasks = gradle.startParameter.taskNames.any { task ->
    task.substringAfterLast(':').lowercase() in setOf(
        "build", "check", "test", "compilejava", "compiletestjava", "compileaotjava",
        "compileaottestjava", "jar", "bootjar", "assemble", "nativecompile", "nativebuild",
        "publish"
    ) || task.substringAfterLast(':').lowercase().contains("native")
}
if (localBuildTasks && providers.environmentVariable("GITHUB_ACTIONS").orNull != "true") {
    throw org.gradle.api.GradleException(
        "Local Java/Native compilation is disabled. Push a branch or java-vX.Y.Z tag and let GitHub Actions build it."
    )
}

group = "com.prodigalgal.remoteconnectmcp"
version = providers.gradleProperty("releaseVersion").orElse("0.1.0-SNAPSHOT").get()

allprojects {
    group = rootProject.group
    version = rootProject.version
}

subprojects {
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(25))
            }
            withSourcesJar()
        }

        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release.set(25)
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            systemProperty("file.encoding", "UTF-8")
            // Keep CI failures actionable: the PostgreSQL contract tests need
            // the vendor exception message (SQLSTATE/constraint details), not
            // only Gradle's one-line test summary. This does not change test
            // behavior or production logging.
            testLogging {
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}
