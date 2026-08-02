// :plugin, the IntelliJ IDEA plugin. Built with the IntelliJ Platform Gradle Plugin
// 2.x. Language is Java. It shares only the JSON payload contract with :agent and has
// no dependency on :agent.

import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType


plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

val jacksonVersion = providers.gradleProperty("jacksonVersion").get()
val pluginSinceBuild = providers.gradleProperty("pluginSinceBuild").get()
val platformType = providers.gradleProperty("intellijPlatformType").get()
val platformVersion = providers.gradleProperty("intellijPlatformVersion").get()

// Professional artifact name: deju-trace-<version>.zip (not plugin-*.zip).
base {
    archivesName = "deju-trace"
}

java {
    toolchain {
        // IntelliJ IDEA 2024.3 (243) runs on JDK 21.
        languageVersion = JavaLanguageVersion.of(
            providers.gradleProperty("javaToolchainVersion").get().toInt()
        )
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(IntelliJPlatformType.fromCode(platformType), platformVersion)
        // Java PSI (PsiMethod/PsiClass, line-marker on methods) comes from this plugin.
        bundledPlugin("com.intellij.java")

        pluginVerifier()
        zipSigner()
        // No testFramework(...) here on purpose. The platform fixture registers
        // com.intellij.tests.JUnit5TestSessionListener as a JUnit Platform service, which
        // fails to instantiate without JUnit 4 on the classpath and takes the whole test
        // JVM down with it. Nothing we test needs a live Project, so the plain JUnit 5
        // classpath below is both sufficient and far quicker. Add it back, together with
        // a junit:junit dependency, if a test ever genuinely needs an IDE fixture.
    }

    // Parse the agent's JSON payload. Plain POJOs; default typing never enabled.
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")

    // Logic that needs no IDE fixture (exclusion globs, report model shaping) is tested as
    // plain Java; see the note above about the platform fixture.
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    pluginConfiguration {
        name = "Deju Trace"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = pluginSinceBuild
            // No upper bound: supports 2024.1 and every newer IDE build.
            untilBuild = provider { null }
        }
    }

    // Plugin Verifier (`./gradlew verifyPlugin`) checks binary compatibility across
    // the whole supported range, oldest, the branches in between, and newest.
    //
    // The plugin supports 2024.1 and EVERY newer IDE, including 2026+, because
    // untilBuild is left open (null) above, nothing here caps it. This list is only the
    // set of RELEASED builds we actively test against; add each new line (e.g. "2026.1")
    // here once JetBrains ships it, so the newest available is always verified.
    // NOTE on 2026+: the plugin INSTALLS on 2026+ already (untilBuild is null). Actively
    // VERIFYING against 2026.x needs a newer IntelliJ Platform Gradle Plugin, the 2.5.0
    // used here resolves verifier IDEs from a repo whose newest ideaIC is 2025.2.6. Bump the
    // Gradle plugin (top of this file) to add "2026.1.x"/"2026.2.x" here.
    pluginVerification {
        ides {
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2024.1.7")   // floor / compile target
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2024.2.5")
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2024.3.5")
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2025.1")
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.6")   // newest resolvable here
        }
    }

    // Marketplace signing (`./gradlew signPlugin`). Material is read from the
    // environment so nothing secret lives in the repo; see README.
    signing {
        certificateChainFile = providers.environmentVariable("DEJU_CERTIFICATE_CHAIN_FILE").map { file(it) }
        privateKeyFile = providers.environmentVariable("DEJU_PRIVATE_KEY_FILE").map { file(it) }
        password = providers.environmentVariable("DEJU_PRIVATE_KEY_PASSWORD")
    }

    // Marketplace publishing (`./gradlew publishPlugin`). The token is a Marketplace
    // "Personal Access Token" read from the environment; nothing secret in the repo.
    // publishPlugin depends on signPlugin, so a published build is always signed.
    //
    // Channel is derived from the version: a pre-release like 0.2.0-eap.1 publishes to
    // the "eap" channel (installed only by users who add that channel), while a stable
    // 0.2.0 goes to the "default" channel, the public listing.
    publishing {
        token = providers.environmentVariable("DEJU_PUBLISH_TOKEN")
        val suffix = project.version.toString().substringAfter('-', "").substringBefore('.')
        channels = listOf(suffix.ifEmpty { "default" })
    }
}

// Bundle the agent fat-jar inside the plugin at /agent/deju-agent.jar so the plugin can
// auto-attach it via -javaagent (DejuProgramPatcher). Plugin and agent are always the
// same build, no separate download, no path to configure.
// Write the plugin version into a resource the runtime can read.
//
// The obvious way to learn your own version at runtime is
// PluginManagerCore.getPlugin(PluginId), but that is annotated @ApiStatus.Internal and the
// Marketplace verifier reports it as a problem on 2026.x builds, while every IDE we can
// verify against locally (<= 2025.2) accepts it silently. A generated resource has no such
// exposure: it cannot be deprecated, made internal, or removed.
val generateVersionResource by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/deju-resources")
    val pluginVer = project.version.toString()
    inputs.property("pluginVersion", pluginVer)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("deju/version.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=$pluginVer\n")
    }
}

sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("generated/deju-resources"))

tasks.named<ProcessResources>("processResources") {
    dependsOn(generateVersionResource)
    into("agent") {
        from(project(":agent").tasks.named("shadowJar"))
        rename { "deju-agent.jar" }
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Java 17 bytecode so the plugin loads on 2024.1's JBR 17 as well as newer JDK 21 IDEs.
    options.release = providers.gradleProperty("pluginTargetJavaVersion").get().toInt()
}

// The IntelliJ Platform buildPlugin/signPlugin Zip tasks name themselves after the
// Gradle module; force the professional artifact name deju-trace-<version>.zip.
tasks.named<Zip>("buildPlugin") {
    archiveBaseName = "deju-trace"
}

