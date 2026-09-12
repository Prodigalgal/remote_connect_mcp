plugins {
    id("org.graalvm.buildtools.native")
    application
}

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
