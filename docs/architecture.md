# How Deju Trace actually works

Three programs, not one: a plugin that lives in **your IDE**, an agent that lives inside
**your application's JVM**, and a report that, once exported, needs neither and just
lives in **a browser**.

```
             +----------------------------------------------------------+
             | YOUR IDE -- the Deju Trace plugin                        |
             | (a JetBrains plugin)                                     |
             +----------------------------------------------------------+
                                           |
                    +----------------------+-----------------------+
                    |                                              |
                    | -javaagent flag, at JVM start                | Export: writes a self-contained
                    | AUTH -> ARM / DISARM  (down)                 | HTML report. No agent needed
                    | JSON payload           (up)                  | to open it, ever again.
                    v                                              v
+--------------------------------------+          +--------------------------------+
| YOUR APPLICATION'S JVM               |          | A BROWSER                      |
| the Deju Agent (a -javaagent)        |          | the exported report            |
+--------------------------------------+          +--------------------------------+
```

The plugin never touches your running application directly — every relationship in
that picture is either the one-time `-javaagent` attach, the live socket, or a file
written to disk. Nothing else connects these three programs.

## Your IDE — the Deju Trace plugin

A JetBrains plugin. Talks to your app through exactly one channel: a local socket to
the agent.

- **Tool Window** — `DejuToolWindowPanel`
  Connect, set a trace point, Track / Show / Export, and the run history list — Pin,
  Rename…, search, Diff…, Copy as Markdown.
- **Controller** — `DejuController`
  The seam between the tool window and everything else: owns the agent connection,
  hands a landed payload to the painter and the history store.
- **Settings** — `DejuSettings`
  Host, port, token, Includes, per-project exclusions, and history capacity (1–25) —
  Settings → Tools → Deju Trace.
- **Editor Painter** — `EditorPainter` · `TimingGutterProvider`
  Paints the executed lines — green / yellow / red — and per-line timing into the
  gutter of the open editor.
- **Source Resolver** — `SourceResolver` · `TypeNavigator`
  Maps a recorded class back to its source file across your configured source roots.
- **Exclusions** — `DejuExclusions`
  Per-project patterns — entities, DTOs, generated builders — folded away by default.
  Resolved at export time only; the agent still records everything.
- **History Store** — `DejuHistoryStore`
  The last N runs (1–25, configurable) as JSON in `.idea/deju/`, ring-buffered. A
  pinned run survives rotation; Delete still removes it.
- **Agent Client** — `AgentClient`
  Connects to the agent's token-gated localhost socket, does the `AUTH` handshake,
  sends `ARM` / `DISARM`, and reads pushed JSON payloads on a background thread.
- **Auto-Attach** — `DejuProgramPatcher`
  Adds the `-javaagent` flag to a run configuration IntelliJ launches locally, so
  nobody edits VM options by hand. Skipped if a Deju flag is already there.
- **Agent Bundle** — `DejuAgentBundle`
  Unpacks the agent jar shipped inside the plugin to a real file on disk — a
  `-javaagent` flag needs a filesystem path, not a resource inside a jar.
- **Report Generator** — `HtmlReportGenerator`
  Builds the exported file: inlines `report.css` and `report.js`, and packs the run's
  payload (gzip + base64) straight into the HTML.

## Your application's JVM — the Deju Agent

A `-javaagent`, attached at JVM start. Lives inside the process it traces and nowhere
else.

- **Agent Entry** — `DejuAgent` (premain)
  Attached with `-javaagent:deju-agent.jar=port=…,token=…,includes=…`. Wires the
  socket server and the runtime at JVM start.
- **Socket Server** — `SocketServer`
  Bound to `127.0.0.1` only by default. First line from any client must be
  `AUTH <token>`, compared in constant time; a bad token closes the socket.
- **Bytecode Instrumentation** — `AsmInstrumenter` · `CoverageClassVisitor` ·
  `CoverageMethodVisitor`
  Rewrites every class matching Includes as it loads: a probe at entry/exit, one per
  source line, one per branch. Stack-neutral — no source is touched, no class is
  loaded to compute frames.
- **Runtime + Session** — `CoverageRuntime` · `Session`
  What every inserted probe calls. Per-thread and `ThreadLocal`, so an idle probe on
  an unarmed thread costs one read. Records line hits, branch edges, the call tree,
  and CPU time (`ThreadMXBean`) for the one armed call.
- **SQL Capture** — `agent.sql`
  Wraps JDBC `execute*` calls. Statement text only — bound parameters are never read
  and stay as `?` placeholders.
- **Payload Builder** — `PayloadBuilder`
  Turns one Session's raw hit-sets into the wire `DejuPayload`: FULL / PARTIAL / NONE
  per line, the call tree, CPU microseconds.

## A browser — the exported report

One HTML file. No agent, no plugin, no IDE and no network needed to open it.

- **report.html** — self-contained
  `report.css` and `report.js` are inlined verbatim; the payload is gzipped and
  base64'd into the file itself. Nothing is fetched at open time.
- **Call Tree · Breakdown · Flame Graph · Timeline · Findings** — five tabs, one payload
  Each tab reads the same in-memory payload and is built lazily on first open, so a
  report opened on one tab never pays for the other four.

## How one trace flows

1. **Set a trace point.** Click the gutter icon on a controller/entry method, or
   right-click it → `Set as Trace Point`.
2. **Press Track.** The plugin's `AgentClient` sends `ARM <fqMethod>` to the agent
   over its local socket. Nothing is recorded yet — the agent is just watching for
   that one method to be entered.
3. **Hit the endpoint.** However your app is normally called — a browser, curl, a
   test. Deju does not send the request for you.
4. **The agent records the one call.** Bytecode probes already sit in every
   instrumented class. On the armed thread they start filling in a `Session`: every
   line, every branch taken, the exact call tree, SQL statement text, and CPU time —
   until that one call returns.
5. **The payload comes back.** Pushed over the same socket as JSON. `Show` paints it
   straight into the editor: green/yellow/red per line, timing in the gutter.
6. **Keep it, compare it, share it.** It joins the history list — pin it, rename it,
   run `Diff…` against another recording, or `Export` a self-contained HTML report
   anyone can open, no plugin required.

## What never leaves the machine

- no variable values captured
- SQL: statement text only, parameters stay as `?`
- loopback-only by default
- no telemetry, no analytics
- history + exclusions stay in your project, never committed

---
*Reflects the codebase, not a fixed release — if a class here is renamed, this file
should be too.*
