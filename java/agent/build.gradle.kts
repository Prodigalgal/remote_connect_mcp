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
    // Keep a self-contained JVM diagnostic artifact for CI inspection only.
    // Production installation and upgrade consume the Native Image bundle.
    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}
