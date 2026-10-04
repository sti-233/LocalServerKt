plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    application
}

group = "lib.server.local"
version = "1.0"

repositories {
    mavenCentral()
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(23)
}

application {
    mainClass.set("localserver.ServerKt")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "localserver.ServerKt"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}

val ktorVersion = "3.1.1"

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-resources:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-sse:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("ch.qos.logback:logback-classic:1.5.26")
}