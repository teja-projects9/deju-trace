package org.deju.agent.model;

/**
 * Static metadata about one instrumented class, captured once at transform time.
 * Immutable after construction.
 */
public final class ClassModel {

    private final String className;      // dotted, e.g. org.example.MyService
    private final String sourceFileName; // e.g. MyService.java (may be null)

    public ClassModel(String className, String sourceFileName) {
        this.className = className;
        this.sourceFileName = sourceFileName;
    }

    public String getClassName() {
        return className;
    }

    public String getSourceFileName() {
        return sourceFileName;
    }
}
