plugins {
    `java-library`
}

dependencies {
    api("com.fasterxml.jackson.core:jackson-annotations:2.21")
    api("tools.jackson.core:jackson-databind:3.1.4")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}
