plugins {
    id("org.graalvm.buildtools.native")
    application
}

// The release workflow selects an explicit baseline per architecture.  Keep
// the compatibility alias as the default for other CI callers.
val nativeMarch = providers.gradleProperty("nativeMarch").orElse("compatibility").get()

dependencies {
    implementation(project(":protocol"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

application {
    mainClass.set("com.prodigalgal.remoteconnectmcp.agent.RemoteConnectAgentApplication")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("rcm-agent")
            // Target the broad x86-64 baseline so older hosts without AVX2
            // (for example Sandy Bridge) can run the release binary.
            buildArgs.add("-march=$nativeMarch")
        }
    }
}

tasks.jar {
    // The Agent is intentionally Spring-free, so its release JAR must carry
    // the small shared protocol module and run with `java -jar` on a clean
    // host. Native Image packaging can consume the same runtime classpath.
    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}
