import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.20"
    application
    id("com.gradleup.shadow") version "9.0.0"
}

group = "ch.frostnova.cli"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val jacksonVersion = "2.18.2"

dependencies {
    // CLI parsing (Clikt) + rich cross-platform terminal output (Mordant), both by ajalt
    implementation("com.github.ajalt.clikt:clikt:5.0.2")
    implementation("com.github.ajalt.mordant:mordant:3.0.1")

    // JNA (already pulled in transitively by Mordant's Windows backend) — used directly to switch the
    // Windows console output code page to UTF-8 so Unicode glyphs render instead of showing as '?'.
    implementation("net.java.dev.jna:jna:5.14.0")

    // .idxsync config in YAML (Jackson + Kotlin module)
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.assertj:assertj-core:3.27.0")
    testImplementation("io.mockk:mockk:1.13.13")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        // Limit the visible JDK API to release 17 (like javac's --release), so building on a newer JDK
        // can't slip in a method that only exists in a later release (e.g. PrintStream.charset(), Java 18+).
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Same guard for the Java compiler: verify against the JDK 17 API, not just emit 17 bytecode.
tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

application {
    mainClass = "ch.frostnova.cli.idx.sync.MainKt"
    applicationName = "idx-sync"
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName = "idx-sync"
    archiveClassifier = ""
    archiveVersion = ""
}

// The default build produces the executable fat jar; `build` always includes shadowJar.
tasks.build {
    dependsOn(tasks.shadowJar)
}

// Running `gradle` with no arguments does a clean build that produces the shadow jar.
defaultTasks("clean", "build", "shadowJar")
