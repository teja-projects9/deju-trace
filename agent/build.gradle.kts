// :agent, the -javaagent that instruments a running Java web app and records
// line + branch coverage for one targeted call. It has ZERO dependency on any
// IntelliJ API and must stay runnable on any Java 11+ target VM.
//
// Build with a JDK 21 toolchain but compile the main sources to a Java 11 baseline
// so the produced agent attaches to Java 11 .. 21+ targets.

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    // Fat-jar packaging so ByteBuddy + Jackson travel inside the agent jar.
    // gradleup's Shadow is the maintained fork compatible with Gradle 8.x.
    id("com.gradleup.shadow") version "8.3.5"
}

val agentRelease = providers.gradleProperty("agentReleaseVersion").get().toInt()
val byteBuddyVersion = providers.gradleProperty("byteBuddyVersion").get()
val jacksonVersion = providers.gradleProperty("jacksonVersion").get()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(
            providers.gradleProperty("javaToolchainVersion").get().toInt()
        )
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Recent ByteBuddy reads Java 8..24 classfiles (covers Java 11 -> 21+ targets).
    implementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")
    // Jackson databind (pinned, no open critical CVEs). Default typing stays OFF.
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")

    // Tests self-attach the agent and instrument a fixture class to prove the
    // whole instrumentation path without any external application.
    testImplementation("net.bytebuddy:byte-buddy-agent:$byteBuddyVersion")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Main sources: Java 11 baseline (no records, no Stream.toList(), no sealed types).
tasks.named<JavaCompile>("compileJava") {
    options.release.set(agentRelease)
    // Keep line-number tables so the coverage mapping is possible in our own tests.
    options.compilerArgs.add("-g")
    options.encoding = "UTF-8"
}

tasks.named<JavaCompile>("compileTestJava") {
    // Tests may use the toolchain's full language level; only main is 11-locked.
    options.compilerArgs.add("-g")
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
}

tasks.named<ShadowJar>("shadowJar") {
    archiveFileName.set("deju-agent.jar")

    // Relocate third-party packages so the agent never clashes with the target
    // application's own copy of Jackson / ByteBuddy (the app usually ships Jackson).
    relocate("net.bytebuddy", "org.deju.agent.shaded.bytebuddy")
    relocate("com.fasterxml.jackson", "org.deju.agent.shaded.jackson")
    mergeServiceFiles()

    manifest {
        attributes(
            mapOf(
                "Premain-Class" to "org.deju.agent.DejuAgent",
                "Can-Retransform-Classes" to "true",
                "Can-Redefine-Classes" to "true",
                "Implementation-Title" to "Deju Trace Agent",
                "Implementation-Version" to project.version.toString()
            )
        )
    }
}

// `./gradlew :agent:build` should produce the runnable fat agent jar.
tasks.named("build") {
    dependsOn("shadowJar")
}

/**
 * The agent as a standalone, version-stamped artifact for a GitHub release.
 *
 * <p>The shaded jar itself must keep the fixed name `deju-agent.jar`, because that is what
 * gets packed into the plugin and what a `-javaagent` flag points at. A published asset
 * needs the opposite: the version in the file name.
 *
 * <p>Without it, a setup script that skips the download when the file already exists can
 * never notice a version bump, it would keep serving the old agent under the right name,
 * which is exactly the plugin/agent mismatch that shows up as "Connection reset".
 */
val releaseAgentJar by tasks.registering(Copy::class) {
    description = "Copies the shaded agent to build/distributions/deju-agent-<version>.jar"
    group = "distribution"
    from(tasks.named<ShadowJar>("shadowJar"))
    into(layout.buildDirectory.dir("distributions"))
    rename { "deju-agent-${project.version}.jar" }
}
