package org.deju.plugin.history;

/**
 * Lightweight index record for one stored execution. The payload itself lives in
 * {@code .idea/deju/exec-<slot>.json}; this is just what the tool-window list shows.
 *
 * <p>Public fields so the IDE bean serializer can persist it in the workspace file.
 */
public final class ExecutionEntry {

    /** Fixed ring-buffer slot 1..5, also the filename number. Never derived from payload data. */
    public int slot;
    public String target = "";
    public String startedAtIso = "";
    public long savedAtMillis;
    public int fileCount;
    public int lineCount;
    /**
     * Version of the agent that produced this run. Held in the index so the tool window can
     * show it without reading a payload off disk on the EDT. Empty for runs indexed before
     * this field existed, which necessarily came from a pre-1.1.0 agent.
     */
    public String agentVersion = "";

    public ExecutionEntry() {
        // bean
    }

    public ExecutionEntry(int slot, String target, String startedAtIso,
                          long savedAtMillis, int fileCount, int lineCount,
                          String agentVersion) {
        this.slot = slot;
        this.target = target;
        this.startedAtIso = startedAtIso;
        this.savedAtMillis = savedAtMillis;
        this.fileCount = fileCount;
        this.lineCount = lineCount;
        this.agentVersion = agentVersion == null ? "" : agentVersion;
    }
}
