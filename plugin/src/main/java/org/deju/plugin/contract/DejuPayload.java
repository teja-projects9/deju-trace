package org.deju.plugin.contract;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One completed recording session, the wire contract, parsed on the plugin side. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DejuPayload {

    private String sessionId;
    private String target;
    private String startedAtIso;
    private String agentVersion;
    private long durationMs;
    /** -1 when the connected agent predates this field, or its JVM could not report
     *  per-thread CPU time. */
    private long cpuMicros = -1;
    private List<FileCoverage> files = new ArrayList<>();
    private List<CallNode> calls = new ArrayList<>();
    private boolean callsTruncated;

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

    /**
     * Build version of the agent that produced this payload; null for payloads from an
     * agent older than this field. Compared against the plugin version to detect a traced
     * JVM still running a stale agent.
     */
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

    /** CPU time burned during the session, or -1 when the agent didn't report it. */
    public long getCpuMicros() {
        return cpuMicros;
    }

    public void setCpuMicros(long cpuMicros) {
        this.cpuMicros = cpuMicros;
    }

    public List<FileCoverage> getFiles() {
        return files;
    }

    public void setFiles(List<FileCoverage> files) {
        this.files = files;
    }

    /**
     * Every method invocation in execution order, the call tree that drives the report's
     * default view. Empty for payloads from an agent that predates the field.
     */
    public List<CallNode> getCalls() {
        return calls;
    }

    public void setCalls(List<CallNode> calls) {
        this.calls = calls;
    }

    /** True when the recorded call tree hit the agent's cap and later invocations were dropped. */
    public boolean isCallsTruncated() {
        return callsTruncated;
    }

    public void setCallsTruncated(boolean callsTruncated) {
        this.callsTruncated = callsTruncated;
    }

    public int totalLines() {
        int n = 0;
        for (FileCoverage f : files) {
            n += f.getLines().size();
        }
        return n;
    }
}
