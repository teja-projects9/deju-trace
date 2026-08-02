package org.deju.plugin.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One method invocation in the recorded call tree, the wire contract, parsed on the
 * plugin side. Nodes arrive in execution order, so {@link #seq} is both the node's
 * identity and its step number in the run.
 *
 * <p>Absent entirely from payloads produced by an agent older than this field, in which
 * case the report falls back to its per-file view.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CallNode {

    private int seq;
    private int parentSeq;
    private String className;
    private String methodName;
    private Integer callSiteLine;
    private Long totalMicros;

    /**
     * The SQL executed, when this node is a query rather than a method call.
     *
     * <p>Statement text only, with {@code ?} placeholders left as they are, bound parameter
     * values are never captured, so an exported report carries no database contents. Null on
     * every ordinary method node, and on any recording made by an agent older than 1.2.0.
     */
    private String sql;

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }

    /** Sequence number of the invocation that made this call; -1 for the trace-point method. */
    public int getParentSeq() {
        return parentSeq;
    }

    public void setParentSeq(int parentSeq) {
        this.parentSeq = parentSeq;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    /** The line in the caller that made this call, so the report can place it in the body. */
    public Integer getCallSiteLine() {
        return callSiteLine;
    }

    public void setCallSiteLine(Integer callSiteLine) {
        this.callSiteLine = callSiteLine;
    }

    /** Inclusive enter→exit time of this one invocation, µs. */
    public Long getTotalMicros() {
        return totalMicros;
    }

    public void setTotalMicros(Long totalMicros) {
        this.totalMicros = totalMicros;
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }
}
