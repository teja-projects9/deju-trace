// Root build script. Deliberately thin: each subproject owns its own plugins and
// dependencies so :agent (plain Java 11 baseline), :employee-api (Spring Boot) and
// :plugin (IntelliJ Platform) stay fully independent and build in isolation.

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

subprojects {
    group = rootProject.group
    version = rootProject.version
}
