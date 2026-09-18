plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
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
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.modelcontextprotocol.sdk:mcp:2.0.1")
    implementation("org.springframework:spring-jdbc")
    implementation("com.zaxxer:HikariCP")
    implementation("org.liquibase:liquibase-core")
    // The wake bridge uses PGConnection LISTEN/NOTIFY in addition to JDBC
    // queries, so the PostgreSQL driver is on the compile classpath and is
    // retained by Native Image.  It is still only activated in postgres mode.
    implementation("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

application {
    mainClass.set("com.prodigalgal.remoteconnectmcp.center.RemoteConnectCenterApplication")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("rcm-center")
            buildArgs.add("-march=$nativeMarch")
        }
    }
}
