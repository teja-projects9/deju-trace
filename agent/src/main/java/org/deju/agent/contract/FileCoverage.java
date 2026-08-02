package org.deju.agent.contract;

import java.util.ArrayList;
import java.util.List;

/**
 * Coverage for one source file (one class). Only files whose methods were entered
 * during the session appear in a payload.
 */
public class FileCoverage {

    private String fqClassName;
    private String sourceFileName;
    private List<LineCoverage> lines = new ArrayList<>();

    public FileCoverage() {
        // Jackson.
    }

    public FileCoverage(String fqClassName, String sourceFileName) {
        this.fqClassName = fqClassName;
        this.sourceFileName = sourceFileName;
    }

    public String getFqClassName() {
        return fqClassName;
    }

    public void setFqClassName(String fqClassName) {
        this.fqClassName = fqClassName;
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public void setSourceFileName(String sourceFileName) {
        this.sourceFileName = sourceFileName;
    }

    public List<LineCoverage> getLines() {
        return lines;
    }

    public void setLines(List<LineCoverage> lines) {
        this.lines = lines;
    }
}
