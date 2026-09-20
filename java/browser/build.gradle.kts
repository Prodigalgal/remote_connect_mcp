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
}

application {
    mainClass.set("com.prodigalgal.remoteconnectmcp.browser.BrowserAgentApplication")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("rcm-browser-agent")
            buildArgs.add("-march=$nativeMarch")
            // Browser is a short-lived supervisor started only for a browser
            // task.  Keep its native wrapper small; browser engines remain
            // external adapter dependencies.
            buildArgs.add("-Os")
        }
    }
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}
