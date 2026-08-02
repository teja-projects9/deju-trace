rootProject.name = "intellij-deju-trace"

// Repositories needed to resolve the IntelliJ Platform Gradle Plugin and the
// IntelliJ Platform artifacts themselves. Declared centrally so every subproject
// resolves plugins from the same, trusted set of repositories.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // Required by the IntelliJ Platform Gradle Plugin 2.x.
        maven("https://cache-redirector.jetbrains.com/plugins.gradle.org")
    }
}

dependencyResolutionManagement {
    // Each subproject declares the repositories it needs (the :plugin module
    // pulls the IntelliJ Platform from JetBrains repositories).
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
}

// :agent, the -javaagent you attach to your own running Spring Boot / Java web
//           app; it instruments and records line/branch coverage.
// :plugin, the IntelliJ IDEA plugin you install locally (tool window, editor
//           painter, HTML export) and point at your running app.
// :tools, a small command-line client that exercises the agent socket during
//           development (not shipped, not a target application).
include(":agent")
include(":plugin")
include(":tools")
