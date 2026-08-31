# Deju Trace — User Guide

Plain-English guide: what each feature does, how to set it up (normal and Docker),
and what to do when something does not work.

**In one line:** you mark one method, call your API once, and Deju shows you exactly
which lines of code that one call ran.

---

## The three pieces

You always deal with three things. Nothing else talks to anything else.

```
  +---------------------+      +----------------------+      +------------------+
  |  1. THE PLUGIN      |      |  2. THE AGENT        |      |  3. THE REPORT   |
  |  lives in IntelliJ  |<---->|  lives inside your   |      |  an HTML file    |
  |  you click buttons  |      |  running Java app    |      |  opens anywhere  |
  +---------------------+      +----------------------+      +------------------+
        you install it            you attach it once            you export it
        from Marketplace          with a -javaagent flag        when you want
```

- **Plugin** — the buttons, the colours in your editor.
- **Agent** — a small file (`deju-agent.jar`) that rides along inside your app and
  watches it. It ships inside the plugin, so both are always the same version.
- **Report** — a single HTML file you can email to anyone. No plugin needed to open it.

---

## Features, one line each

### Recording

| Feature | What it does |
|---|---|
| **Deju point** | The one method you want to watch. Click the gutter icon next to it, or right-click → *Set as Trace Point*. |
| **Track / Stop** | Start and stop watching. Press Track, then call your API. |
| **Connect / Disconnect** | Opens the line between the plugin and the agent. |
| **SQL capture** | Records the SQL text your code ran. Values stay as `?` — they are never read. |
| **CPU time** | Shows how much CPU the call really burned, next to the wall-clock time. |

### Seeing the result in the editor

| Feature | What it does |
|---|---|
| **Show** | Opens the files that ran and paints them. |
| **Green line** | This line ran, and every branch on it ran. |
| **Yellow line** | A decision point (`if`, `switch`, `&&`) where only some branches ran. |
| **Red line** | A line inside a method that was entered, but this line never ran. |
| **Timing gutter** | How long each line took, shown in the left margin. |
| **Clear highlights** | Removes all the colouring from your editors. |

### The HTML report (5 tabs)

| Tab | What it shows |
|---|---|
| **Call Tree** | Every call in the exact order it happened, with the source code. |
| **Breakdown** | Bar chart: which file (or method) burned the most time. |
| **Flow** | The run as a picture — three views, see below. |
| **Timeline** | One bar per step, laid out along the clock. Shows waiting vs working. |
| **Findings** | Deju reads the run for you: N+1 queries, one slow query, hot methods. Grouped worst-first, and each group folds away. |

**Inside the Breakdown tab**
- Drag the divider next to the file names to make that column wider (or focus it and
  use ← →). The width is remembered.
- Hover a row and press the small copy icon to copy the class (or class + method) name.

**Inside the Flow tab** — three views, switched at the top left:

| View | What it shows |
|---|---|
| **Flow Chart** *(default)* | A flowchart of the run: one box per step, bracketed by **Start** and **End**. Each box shows its share of the run, so you can see which ones mattered. |
| **Flame Graph** | The classic flame view — box width is time. Searching highlights the matches in place and tells you what percentage of the run they cover. |
| **Steps** | Every call in execution order, nested by depth. |

Flow Chart controls:
- **Left → right / Top → bottom** — switch the direction. It starts top-to-bottom;
  the button shows the direction you would switch *to*. Long rows wrap instead of
  running off the screen.
- **▶ Play** — a dot walks the run in real execution order along the actual arrows, and
  stamps `1`, `2`, `3`… on each box as it reaches it, so you can read the order after it
  finishes. **↺** puts it back to the start.
- **Speed** — 0.5× to 8×, or pick **Custom…** for a slider that goes much slower.
- **Group repeats** — a loop that called the same thing many times becomes one box with
  a count, instead of hundreds of boxes.
- **SQL** — show or hide the database steps.
- **Zoom / Fit width** — drag the zoom slider or type a percentage; **Fit width**
  scales the whole diagram to the window.
- **Save PNG / Save SVG** — save the diagram as a picture.

### Managing runs

| Feature | What it does |
|---|---|
| **History** | Keeps your last runs (1–25, you choose) inside the project. |
| **Pin** | Protects a run so it is never overwritten by a newer one. |
| **Rename…** | Give a run your own name instead of the auto one. |
| **Search box** | Filters the run list by name or trace point. |
| **Diff…** | Compare any two runs: what got slower, what ran more, what coverage changed. |
| **Export…** | Writes the self-contained HTML report. |
| **Copy as Markdown** | Puts a short text summary on your clipboard, for a PR or chat. |
| **Delete / Delete all** | Removes runs. |

### Setup helpers

| Feature | What it does |
|---|---|
| **Copy agent VM option** | Copies the whole `-javaagent:...` line, ready to paste. |
| **Auto-attach** | If the IDE starts your app, the flag is added for you — nothing to paste. |
| **Excluded types…** | Hide entities, DTOs and generated classes from the report. |
| **Refresh agent** | Re-writes the agent file on disk after a plugin update. |
| **Fix run configs** | Points this project's run configs at the current agent file. |
| **Includes** | Which packages to watch, e.g. `com.example`. **Required.** |
| **Source roots** | Where to read source from, if the IDE copy does not match the running build. |
| **What Deju records… / Clear Deju data…** | Shows every file Deju wrote; deletes all of it. |

---

## Install — normal setup (app runs on your machine)

### How it works

```
  YOUR MACHINE
  +---------------------------------------------------------------------+
  |                                                                     |
  |   IntelliJ IDEA                          Your Java app (JVM)        |
  |  +-------------------+                  +------------------------+  |
  |  |   Deju plugin     |  1. connect      |  deju agent            |  |
  |  |                   | ---------------> |  (-javaagent flag)     |  |
  |  |                   |  127.0.0.1:7391  |                        |  |
  |  |                   |  2. Track        |                        |  |
  |  |                   | ---------------> |  watches your code     |  |
  |  |                   |                  |                        |  |
  |  |  paints editor    | <--------------- |  4. sends the result   |  |
  |  +-------------------+   JSON payload   +------------------------+  |
  |                                                    ^                |
  +----------------------------------------------------|----------------+
                                                        |
                                       3. you call the endpoint yourself
                                          (browser / curl / test)
```

### Steps

1. **Install the plugin.** IntelliJ IDEA → *Settings → Plugins → Marketplace* →
   search **Deju Trace** → Install → restart. (Needs IDEA 2024.1 or newer.)

2. **Tell it your packages.** *Settings → Tools → Deju Trace* → **Includes** →
   type your base package, e.g. `com.example`.
   *Without this, Deju watches nothing.*

3. **Attach the agent.** Two ways — pick one:

   - **Easy way (IDE starts your app):** tick
     **Auto-attach agent to Java run configurations the IDE launches**. Done.
   - **Manual way (anything else):** press **Copy agent VM option** in the
     **Deju Trace** tool window (right edge of the IDE), then paste it into your app's
     VM options and start the app.

   The line you paste looks like this:

   ```
   -javaagent:/path/to/deju-agent.jar=port=7391,token=dejutoken,includes=com.example
   ```

4. **Start your app.** Look in its console for this line:

   ```
   [deju] agent ready. includes=[com.example] (unarmed)
   ```

   If you see it, the agent is alive.

5. **Connect.** Open the **Deju Trace** tool window (right edge of the IDE) →
   press **Connect**.

6. **Mark the method.** Open your controller. Click the small gutter icon next to the
   method, or right-click it → *Set as Trace Point*.

7. **Press Track.** The button turns into **Stop**.

8. **Call your API once.** Use a browser, curl, Postman, whatever you normally use.
   *Deju does not send the request for you.*

9. **Look at it.** The run appears in the list.
   **Show** paints your editor. **Export…** writes the HTML report.

---

## Install — Docker setup (app runs in a container)

Two things are different from the normal setup, and both are easy to miss:

1. The agent file must be **inside** the container.
2. The agent must listen on **all interfaces**, not just loopback — otherwise the
   published port lands on nothing.

### How it works

```
  YOUR MACHINE                              DOCKER CONTAINER
  +-------------------------+               +-----------------------------+
  |  IntelliJ IDEA          |               |  Your Java app (JVM)        |
  |  +-------------------+  |               |  +-----------------------+  |
  |  |   Deju plugin     |  |   published   |  |  deju agent           |  |
  |  |                   |  |   port        |  |  bind=0.0.0.0         |  |
  |  |  Host: 127.0.0.1  |--+---------------+->|  port=7391            |  |
  |  |  Port: 7391       |  |  7391 -> 7391 |  |                       |  |
  |  |                   |<-+---------------+--|  agent jar mounted in |  |
  |  +-------------------+  |               |  +-----------------------+  |
  +-------------------------+               +-----------------------------+
         ^                                             ^
         |                                             |
   Host stays 127.0.0.1                    the jar comes from your machine
   (Docker forwards it for you)            via a volume mount or COPY
```

### Steps

1. **Plugin + Includes** — same as steps 1 and 2 above.

2. **Tick the container box.** *Settings → Tools → Deju Trace* →
   **Traced JVM runs in a container or on another machine**.
   This makes the copied flag include `bind=0.0.0.0`.

3. **Find the agent file.** Press **Copy agent VM option** and paste it in a scratch
   file. The path in it is the agent jar on **your machine**, for example:

   ```
   ~/Library/Caches/JetBrains/<IDE>/deju-trace/deju-agent.jar     (macOS)
   ~/.cache/JetBrains/<IDE>/deju-trace/deju-agent.jar             (Linux)
   %LOCALAPPDATA%\JetBrains\<IDE>\deju-trace\deju-agent.jar       (Windows)
   ```

4. **Get that file into the container.** Either mount it (no rebuild), or copy it in.

   **docker run:**
   ```bash
   docker run \
     -v /path/on/your/machine/deju-agent.jar:/deju/deju-agent.jar \
     -p 7391:7391 \
     -p 8080:8080 \
     -e JAVA_TOOL_OPTIONS="-javaagent:/deju/deju-agent.jar=port=7391,token=dejutoken,bind=0.0.0.0,includes=com.example" \
     your-image
   ```

   **docker-compose.yml:**
   ```yaml
   services:
     app:
       image: your-image
       volumes:
         - /path/on/your/machine/deju-agent.jar:/deju/deju-agent.jar
       ports:
         - "7391:7391"     # Deju's port - do not forget this one
         - "8080:8080"     # your app's own port
       environment:
         JAVA_TOOL_OPTIONS: >-
           -javaagent:/deju/deju-agent.jar=port=7391,token=dejutoken,bind=0.0.0.0,includes=com.example
   ```

   **Dockerfile** (if you prefer baking it in):
   ```dockerfile
   COPY deju-agent.jar /deju/deju-agent.jar
   ENV JAVA_TOOL_OPTIONS="-javaagent:/deju/deju-agent.jar=port=7391,token=dejutoken,bind=0.0.0.0,includes=com.example"
   ```

   Note the path in the flag is the path **inside** the container (`/deju/...`), not
   the one on your machine.

5. **Start the container** and check its logs for:

   ```
   [deju] agent ready. includes=[com.example] (unarmed)
   [deju] NOTE: listening beyond loopback (0.0.0.0). ...
   ```

6. **Leave Host as `127.0.0.1`** in the plugin. Docker forwards the published port to
   your machine, so the plugin still connects to localhost.

7. **Connect → trace point → Track → call the API** — exactly as in the normal setup.

> **Remote machine instead of Docker?** Same as Docker, except you set **Host** to that
> machine's address, and you must trust the network — `bind=0.0.0.0` means anything
> that can reach the port can talk to the agent.

---

## Troubleshooting

### Connecting

| What you see | What it means | Fix |
|---|---|---|
| *"nothing is listening on that port"* | Your app was not started with the agent, or the port is different. | Check the app console for `[deju] agent ready`. If it is missing, re-copy the VM option and **restart the app**. |
| *"the token does not match"* | Your `-javaagent` flag has no `token=` or the wrong one. | It must be exactly `token=dejutoken`. |
| *"the port is unreachable"* | Wrong host, a firewall, or a missing Docker port mapping. | Docker: tick **Traced JVM runs in a container…**, re-copy the flag (it adds `bind=0.0.0.0`), and publish the port (`-p 7391:7391`). |
| Container starts, plugin still cannot connect | The agent is listening on loopback *inside* the container only. | Add `bind=0.0.0.0` to the flag and restart the container. |
| Agent log: *"refusing to listen … without a token"* | You used `bind=0.0.0.0` with no token. | Add `token=dejutoken` to the flag. |
| Agent log: *"could not bind socket … falling back to stdout"* | Port 7391 is already taken. | Free the port, or set a different one in Settings **and** in the flag. |

### Recording

| What you see | What it means | Fix |
|---|---|---|
| Track works, but no run appears | The trace point does not match a real method, or **Includes** does not cover its package. | Check Includes covers the class's package, then **restart the app** — the agent reads that flag only once, at startup. |
| Changed Includes, nothing changed | The `-javaagent` line is read once, at JVM start. | Restart the app. |
| *"agent already installed; ignoring duplicate"* | The `-javaagent` flag is in there twice. | Remove one of them (often auto-attach plus a hand-written one). |
| *"Recorded by agent X, but the plugin is Y"* | You updated the plugin but the app is still running the old agent. | Press **Refresh agent**, then restart your app. |

### The report / the editor

| What you see | What it means | Fix |
|---|---|---|
| Line numbers, but no source code | The IDE source does not match the running build. | Set **Source roots** to the source that matches what is deployed. |
| Report is huge or slow | Too many classes are in it. | Use **Excluded types…** to fold away entities/DTOs, or narrow **Includes**. |
| Clicking a line number does nothing | Those are `jetbrains://` links; they need JetBrains Toolbox. | Use **Copy path** instead. |
| Colours look wrong or stale | Old highlighting is still painted. | Press **Clear highlights**, then **Show** again. |

### Still stuck? Check these four, in order

1. Does the app console print `[deju] agent ready`?
2. Does **Includes** contain your package?
3. Did you **restart the app** after changing the flag or Includes?
4. Docker only: is the port published *and* is `bind=0.0.0.0` in the flag?

---

## What Deju never records

- No variable values, ever.
- SQL statement text only — the values stay as `?`.
- No telemetry, no analytics, nothing leaves your machine.
- Runs and settings live in your project's `.idea/deju/` folder.

By default the agent listens on loopback only, so nothing outside your machine can
reach it. `bind=0.0.0.0` opens that up — use it only on a network you trust.
