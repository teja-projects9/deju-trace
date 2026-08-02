# Deju Trace

**Trace one real API call through a running Java web app and paint its executed source,
line by line, branch by branch, back into IntelliJ IDEA.**

Like JUnit's coverage gutter, but for a *single live request* instead of a test suite.
Mark one entry method (a controller handler, say), hit the endpoint for real, and Deju
colours exactly the lines and branches that request travelled, down through the services
and DAOs it called.

```
green    line executed, and every branch on it was taken
yellow   a decision point (if / switch / ?: / && / ||) where only SOME branches ran
red      a line inside an entered method that never executed (e.g. the untaken else)
```

It also records the call tree in exact execution order and the SQL each line issued, and
exports the whole thing as a self-contained HTML report.

> **Out of scope by design.** Deju records *which lines and branches ran*, never variable
> values, never debugger frames. There is no telemetry. The one exception is SQL: an
> executed query's statement text is recorded so it can appear in the call tree, with its
> `?` placeholders intact. Bound parameter values are never read.

## What is what

| Path | What it is |
|---|---|
| `agent/` | the `-javaagent` you attach to **your** running app, Java 11 baseline, no IntelliJ API |
| `plugin/` | the IntelliJ IDEA plugin: tool window, editor painter, HTML report |
| `tools/` | a throwaway CLI that speaks the socket protocol, development only |
| `scripts/` | build helpers, the API audit and the demo-report generator |
| `docs/` | the demo report |
| `assets/` | logo source |

The agent is bundled inside the plugin, so plugin and agent are always the same build.
The two sides share only a JSON payload shape, and each keeps its own copy of the contract
classes, so neither can drag the other's dependencies in.

## Install

From the JetBrains Marketplace, search **Deju Trace**. Requires IntelliJ IDEA 2024.1 or
newer.

## Use

1. Attach the agent to the JVM you want to trace. *Settings, Tools, Deju Trace,*
   **Copy agent VM option** gives you the full `-javaagent` argument with a token.
2. Start your app, then connect from the Deju tool window.
3. Put a **deju point** on a controller or entry method.
4. Hit the endpoint.
5. **Show** paints the executed lines in the editor. **Export** writes the HTML report.

The agent listens on loopback only by default. Reaching it from a container or another
machine takes an explicit `bind=`, and it refuses to start on a non-loopback address
unless a token is set.

## Build

```bash
./gradlew build          # plugin zip + agent jar, with tests
./gradlew verifyPlugin   # JetBrains Plugin Verifier across supported IDEs
./gradlew runIde         # sandbox IDE with the plugin loaded
```

## License

Apache-2.0. See [LICENSE](LICENSE).
