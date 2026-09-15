plugins {
    id("org.graalvm.buildtools.native")
    application
}

val nativeMarch = providers.gradleProperty("nativeMarch").orElse("compatibility").get()

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
        }
    }
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}
