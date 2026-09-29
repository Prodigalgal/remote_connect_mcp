plugins {
    id("org.graalvm.buildtools.native")
    application
}

val nativeTaskRequested = gradle.startParameter.taskNames.any { task ->
    task.substringAfterLast(':').lowercase().contains("native")
}
val nativeMarch = providers.gradleProperty("nativeMarch").orNull
    ?: if (nativeTaskRequested) error("nativeMarch is required; the CI matrix must select an architecture baseline") else "unused"

dependencies {
    implementation(project(":protocol"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

application {
    mainClass.set("com.prodigalgal.remoteconnectmcp.updater.UpdaterApplication")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("rcm-updater")
            buildArgs.add("-march=$nativeMarch")
            buildArgs.add("-Os")
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
