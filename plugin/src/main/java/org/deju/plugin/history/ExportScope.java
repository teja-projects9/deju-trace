package org.deju.plugin.history;

import java.util.LinkedHashSet;
import java.util.Set;

import com.intellij.openapi.project.Project;

import org.deju.plugin.contract.CallNode;
import org.deju.plugin.contract.DejuPayload;
import org.deju.plugin.contract.FileCoverage;
import org.deju.plugin.contract.LineCoverage;
import org.deju.plugin.exclude.DejuExclusions;
import org.deju.plugin.exclude.TypeExclusionMatcher;

/**
 * What the exclusion list covers in one recorded run, so the export dialog can say what
 * "Essential" actually drops instead of asking the user to guess.
 */
public final class ExportScope {

    /** See {@link #of}: measured at ~330, quoted low so the real saving is never smaller. */
    private static final int BYTES_PER_LINE = 200;

    /** Number of recorded classes the project excludes. */
    public final int excludedClasses;
    /** Roughly how many bytes of source text those classes account for. */
    public final long approxBytes;

    private ExportScope(int excludedClasses, long approxBytes) {
        this.excludedClasses = excludedClasses;
        this.approxBytes = approxBytes;
    }

    /**
     * Measures a payload. Reads only what is already in memory, but the caller has to have
     * loaded the payload first, which is disk work, so not on the EDT.
     *
     * <p>The byte figure is an estimate, and has to be: the source text itself is not in the
     * payload, the report reads it from the IDE at export time, so there is nothing here
     * to measure directly. Each omitted line costs roughly 170 bytes of JSON field names and
     * per-line metadata plus the code itself, which was measured at about 330 bytes per line
     * on a real report. 200 is used as a deliberately conservative figure: a saving that
     * turns out larger than promised is the harmless direction.
     */
    public static ExportScope of(Project project, DejuPayload payload) {
        TypeExclusionMatcher matcher = DejuExclusions.getInstance(project).matcher();
        Set<String> excluded = new LinkedHashSet<>();

        for (CallNode node : payload.getCalls()) {
            if (node.getClassName() != null && matcher.isExcluded(node.getClassName())) {
                excluded.add(node.getClassName());
            }
        }
        long bytes = 0;
        int count = 0;
        for (FileCoverage file : payload.getFiles()) {
            String fq = file.getFqClassName();
            if (fq == null || !matcher.isExcluded(fq)) {
                continue;
            }
            excluded.add(fq);
            count++;
            for (LineCoverage line : file.getLines()) {
                bytes += BYTES_PER_LINE;
                if (line.getMethodName() != null) {
                    bytes += line.getMethodName().length();
                }
            }
        }
        return new ExportScope(Math.max(count, excluded.size()), bytes);
    }
}
