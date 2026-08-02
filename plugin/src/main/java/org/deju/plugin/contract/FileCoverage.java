package org.deju.plugin.contract;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Coverage for one source file (one class). */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FileCoverage {

    private String fqClassName;
    private String sourceFileName;
    private List<LineCoverage> lines = new ArrayList<>();

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
