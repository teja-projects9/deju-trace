package org.deju.agent.contract;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Coverage of one source line. For decision points ({@link LineStatus#PARTIAL},
 * and any {@link LineStatus#FULL} line that had branches) the branch counts are
 * populated; for plain lines they are omitted from the JSON.
 *
 * <p>Plain POJO, no polymorphic typing, no custom deserializers.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LineCoverage {

    private int line;
    private LineStatus status;
    private Integer branchesCovered;
    private Integer branchesTotal;
    /**
     * On a line with 2+ boolean decisions (a compound {@code &&}/{@code ||} condition),
     * one entry per decision in source (left-to-right) order: {@code TRUE_ONLY},
     * {@code FALSE_ONLY}, {@code MIXED} (both observed across this run, e.g. a loop),
     * {@code SKIPPED} (never evaluated, e.g. short-circuited away every time), or
     * {@code OTHER} (a relational decision the report can't safely color true/false —
     * see the operand-coloring design notes). Null on every other line.
     */
    private List<String> operandStatus;

    /** Wall-clock time attributed to this line only, in microseconds (self time). */
    private Long timeMicros;
    /** On a method's first line only: inclusive time of the whole method call, µs. */
    private Long methodTotalMicros;
    /** On a method's first line only: self time of the method (sum of its lines), µs. */
    private Long methodSelfMicros;

    /**
     * Bytecode name of the method that owns this line ({@code <init>} for constructors,
     * {@code lambda$foo$0} for lambda bodies). Set on <em>every</em> line, which is what
     * lets the HTML report group a file's lines into method sections; unlike
     * {@link #methodTotalMicros} it is never gated on a timing threshold.
     */
    private String methodName;
    /**
     * {@code Boolean.TRUE} on the owning method's declaration (first) line, else null,
     * {@code Boolean} rather than {@code boolean} so {@code NON_NULL} keeps the common
     * false case out of the JSON.
     */
    private Boolean methodStart;

    public LineCoverage() {
        // Jackson.
    }

    public LineCoverage(int line, LineStatus status, Integer branchesCovered, Integer branchesTotal) {
        this.line = line;
        this.status = status;
        this.branchesCovered = branchesCovered;
        this.branchesTotal = branchesTotal;
    }

    public int getLine() {
        return line;
    }

    public void setLine(int line) {
        this.line = line;
    }

    public LineStatus getStatus() {
        return status;
    }

    public void setStatus(LineStatus status) {
        this.status = status;
    }

    public Integer getBranchesCovered() {
        return branchesCovered;
    }

    public void setBranchesCovered(Integer branchesCovered) {
        this.branchesCovered = branchesCovered;
    }

    public Integer getBranchesTotal() {
        return branchesTotal;
    }

    public void setBranchesTotal(Integer branchesTotal) {
        this.branchesTotal = branchesTotal;
    }

    public List<String> getOperandStatus() {
        return operandStatus;
    }

    public void setOperandStatus(List<String> operandStatus) {
        this.operandStatus = operandStatus;
    }

    public Long getTimeMicros() {
        return timeMicros;
    }

    public void setTimeMicros(Long timeMicros) {
        this.timeMicros = timeMicros;
    }

    public Long getMethodTotalMicros() {
        return methodTotalMicros;
    }

    public void setMethodTotalMicros(Long methodTotalMicros) {
        this.methodTotalMicros = methodTotalMicros;
    }

    public Long getMethodSelfMicros() {
        return methodSelfMicros;
    }

    public void setMethodSelfMicros(Long methodSelfMicros) {
        this.methodSelfMicros = methodSelfMicros;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public Boolean getMethodStart() {
        return methodStart;
    }

    public void setMethodStart(Boolean methodStart) {
        this.methodStart = methodStart;
    }
}
