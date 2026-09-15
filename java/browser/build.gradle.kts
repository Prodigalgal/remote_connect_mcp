plugins {
    id("org.graalvm.buildtools.native")
    application
}

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
            buildArgs.add("-march=compatibility")
        }
    }
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}
