plugins {
    id("org.graalvm.buildtools.native")
    application
}

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
            buildArgs.add("-march=compatibility")
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
