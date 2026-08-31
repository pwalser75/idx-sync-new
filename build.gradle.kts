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
        jvmTarget = JvmTarget.JVM_21
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
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
