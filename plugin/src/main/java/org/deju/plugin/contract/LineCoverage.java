package org.deju.plugin.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** One line's coverage. Branch counts present only for decision lines. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class LineCoverage {

    private int line;
    private LineStatus status;
    private Integer branchesCovered;
    private Integer branchesTotal;
    private Long timeMicros;
    private Long methodTotalMicros;
    private Long methodSelfMicros;
    private String methodName;
    private Boolean methodStart;

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

    /** Self time attributed to this line, microseconds (may be null). */
    public Long getTimeMicros() {
        return timeMicros;
    }

    public void setTimeMicros(Long timeMicros) {
        this.timeMicros = timeMicros;
    }

    /** On a method's first line: inclusive time of the whole method call, µs (else null). */
    public Long getMethodTotalMicros() {
        return methodTotalMicros;
    }

    public void setMethodTotalMicros(Long methodTotalMicros) {
        this.methodTotalMicros = methodTotalMicros;
    }

    /** On a method's first line: self time of the method, µs (else null). */
    public Long getMethodSelfMicros() {
        return methodSelfMicros;
    }

    public void setMethodSelfMicros(Long methodSelfMicros) {
        this.methodSelfMicros = methodSelfMicros;
    }

    /**
     * Bytecode name of the owning method, on every line, the report groups a file's
     * lines into method sections with it. Null when the payload came from an agent
     * older than this field, in which case the report simply renders no sections.
     */
    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    /** True on the owning method's declaration (first) line; null elsewhere. */
    public Boolean getMethodStart() {
        return methodStart;
    }

    public void setMethodStart(Boolean methodStart) {
        this.methodStart = methodStart;
    }
}
