plugins {
    id("org.graalvm.buildtools.native")
    application
}

application {
    mainClass.set("com.prodigalgal.remoteconnectmcp.browser.BrowserAgentApplication")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("rcm-browser-agent")
        }
    }
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}
