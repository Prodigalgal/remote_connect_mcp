plugins {
    id("org.graalvm.buildtools.native")
    application
}

val nativeMarch = providers.gradleProperty("nativeMarch").orElse("compatibility").get()

dependencies {
    // The AWT implementation remains reachable only from this executable.
    // The command Agent can still share the protocol/client classes without
    // pulling desktop libraries into its Native Image.
    implementation(project(":agent"))
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
