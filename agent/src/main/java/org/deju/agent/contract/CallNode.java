package org.deju.agent.contract;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One method invocation in the recorded call tree. Nodes are emitted in execution order,
 * so {@link #seq} doubles as both the node's identity and its step number in the run:
 * walking the list from 0 upwards replays the invocations exactly as they happened.
 *
 * <p>The tree is expressed as a flat list with parent pointers rather than nested objects,
 * which keeps the JSON small and lets the report rebuild the structure in one pass.
 *
 * <p>Plain POJO, no polymorphic typing, no custom deserializers.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CallNode {

    private int seq;
    private int parentSeq;
    private String className;
    private String methodName;

    /**
     * The line <em>in the caller</em> that made this call, so the report can slot the call
     * into the caller's body at the point it happened. Null for the trace-point method,
     * which has no recorded caller, and for a call whose caller line could not be resolved.
     */
    private Integer callSiteLine;

    /** Inclusive enter→exit time of this one invocation, µs; null if under a microsecond. */
    private Long totalMicros;

    /**
     * The SQL executed, when this node is a query rather than a method call.
     *
     * <p>Statement text only, with {@code ?} placeholders left as they are: bound parameter
     * values are never captured, so a report can be shared without carrying database
     * contents out with it. Null on every ordinary method node.
     */
    private String sql;

    public CallNode() {
        // Jackson.
    }

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }

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

    public Integer getCallSiteLine() {
        return callSiteLine;
    }

    public void setCallSiteLine(Integer callSiteLine) {
        this.callSiteLine = callSiteLine;
    }

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
