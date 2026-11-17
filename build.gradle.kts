import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("com.gradleup.shadow") version "9.6.1"
    application
}

group = "dev.lecturetubemcp"
// Set by the release workflow: ./gradlew allJars -PreleaseVersion=1.2.3
version = providers.gradleProperty("releaseVersion").getOrElse("0.0.0-dev")

repositories { mavenCentral() }

dependencies {
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.9.1")
    implementation("io.github.givimad:whisper-jni:1.7.1") // bundled whisper.cpp for speech-to-text (runs in a child JVM)
    implementation("org.apache.pdfbox:pdfbox:3.0.8") // TUWEL: read PDFs from courses and ZIP archives
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17") // logs go to stderr; stdout is reserved for MCP
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
java { targetCompatibility = JavaVersion.VERSION_17 }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

application { mainClass.set("dev.lecturetubemcp.MainKt") }

// LectureTube MCP server → build/libs/lecturetube-mcp.jar
tasks.shadowJar {
    archiveFileName.set("lecturetube-mcp.jar")
    manifest { attributes["Implementation-Version"] = project.version }
    mergeServiceFiles()
}

// TUWEL MCP server → build/libs/tuwel-mcp.jar (same sources, different entry point)
val tuwelJar by tasks.registering(com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar::class) {
    group = "build"
    description = "Builds the TUWEL MCP server as a self-contained jar."
    archiveFileName.set("tuwel-mcp.jar")
    from(sourceSets.main.get().output)
    configurations.set(listOf(project.configurations.runtimeClasspath.get()))
    manifest { attributes("Main-Class" to "dev.tuwelmcp.MainKt", "Implementation-Version" to project.version) }
    mergeServiceFiles()
}

tasks.register("allJars") {
    group = "build"
    description = "Builds both MCP server jars."
    dependsOn(tasks.shadowJar, tuwelJar)
}
tasks.test {
    useJUnitPlatform()
    // Tests must never touch the real session, models or transcripts.
    systemProperty("lecturetubemcp.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
}
