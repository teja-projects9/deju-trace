// :tools, a tiny developer command-line client for the agent socket. It is not
// shipped and is not a target application; it exists to exercise and demonstrate the
// AUTH / ARM / payload protocol during development. Pure JDK, no dependencies.

plugins {
    java
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(
            providers.gradleProperty("javaToolchainVersion").get().toInt()
        )
    }
}

application {
    mainClass.set("org.deju.tools.DejuCli")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
