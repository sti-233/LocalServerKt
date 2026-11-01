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
    testLogging {
        events("passed", "skipped", "failed")
    }
    // 测试在 build/test-work 下运行：服务端按工作目录写 userList.json / cache/ / logs.txt，
    // 放这里避免污染仓库根目录（build/ 已在 .gitignore 中）
    workingDir = layout.buildDirectory.dir("test-work").get().asFile
    doFirst {
        workingDir.mkdirs()
    }
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
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    // 私聊/群聊的 WS 广播去重需要真的开一条 WebSocket 连接来验（自己发给自己时只能收到一条）
    testImplementation("io.ktor:ktor-client-websockets-jvm:$ktorVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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