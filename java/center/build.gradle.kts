plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("org.graalvm.buildtools.native")
    application
}

dependencies {
    implementation(project(":protocol"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.modelcontextprotocol.sdk:mcp:2.0.1")
    implementation("org.springframework:spring-jdbc")
    implementation("com.zaxxer:HikariCP")
    implementation("org.liquibase:liquibase-core")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

application {
    mainClass.set("com.prodigalgal.remoteconnectmcp.center.RemoteConnectCenterApplication")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("rcm-center")
        }
    }
}
