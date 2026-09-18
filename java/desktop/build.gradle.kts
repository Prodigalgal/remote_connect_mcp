plugins {
    id("org.graalvm.buildtools.native")
    application
}

// Native Image builds must select an explicit CPU baseline per architecture.
// JVM-only CI tasks still configure this project, so enforce the value only
// when a Native task is actually requested.
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
    mainClass.set("com.prodigalgal.remoteconnectmcp.desktop.DesktopCompanionApplication")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("rcm-desktop-companion")
            buildArgs.add("-march=$nativeMarch")
            // The Linux Native Image builder runs in a headless container. If
            // the default AWT property is captured from that environment, the
            // same binary reports a headless session even when the target has
            // an active X11/Wayland display. Desktop capability is opt-in and
            // already starts only in the user's session, so keep the runtime
            // property explicitly non-headless for all GUI targets.
            buildArgs.add("-Djava.awt.headless=false")
        }
    }
}

tasks.jar {
    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}
