package org.deju.agent.contract;

import java.util.ArrayList;
import java.util.List;

/**
 * The single wire contract shared (by independent implementation) between the
 * agent and the IntelliJ plugin. One payload describes one completed recording
 * session. Serialized as newline-delimited JSON over the localhost socket.
 *
 * <pre>
 * {
 *   "sessionId": "uuid",
 *   "target": "org.example.Controller#handle",
 *   "startedAtIso": "2026-01-01T10:00:00Z",
 *   "durationMs": 42,
 *   "files": [ ... ],
 *   "calls": [ ... ]
 * }
 * </pre>
 */
public class DejuPayload {

    private String sessionId;
    private String target;
    private String startedAtIso;
    /**
     * Build version of the agent that produced this payload, so the plugin can warn when a
     * traced JVM is still running an older agent than the installed plugin.
     */
    private String agentVersion;
    private long durationMs;
    private List<FileCoverage> files = new ArrayList<>();
    private List<CallNode> calls = new ArrayList<>();
    private boolean callsTruncated;

    public DejuPayload() {
        // Jackson.
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getStartedAtIso() {
        return startedAtIso;
    }

    public void setStartedAtIso(String startedAtIso) {
        this.startedAtIso = startedAtIso;
    }

    public String getAgentVersion() {
        return agentVersion;
    }

    public void setAgentVersion(String agentVersion) {
        this.agentVersion = agentVersion;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public List<FileCoverage> getFiles() {
        return files;
    }

    public void setFiles(List<FileCoverage> files) {
        this.files = files;
    }

    /**
     * Every method invocation of the session in execution order: index i of this list is
     * step i of the run. {@link FileCoverage} answers "which lines ran"; this answers
     * "in what order, and what called what".
     */
    public List<CallNode> getCalls() {
        return calls;
    }

    public void setCalls(List<CallNode> calls) {
        this.calls = calls;
    }

    /** True when the call tree hit the recording cap and later invocations were dropped. */
    public boolean isCallsTruncated() {
        return callsTruncated;
    }

    public void setCallsTruncated(boolean callsTruncated) {
        this.callsTruncated = callsTruncated;
    }
}
