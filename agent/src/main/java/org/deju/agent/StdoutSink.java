package org.deju.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.deju.agent.contract.DejuPayload;
import org.deju.agent.contract.FileCoverage;
import org.deju.agent.contract.LineCoverage;
import org.deju.agent.runtime.PayloadSink;

/**
 * Phase-2 sink: prints each completed session's payload to stdout so instrumentation
 * can be proven with no plugin and no socket involved. The socket sink (phase 3)
 * replaces this at runtime.
 *
 * <p>The Jackson {@link ObjectMapper} here is a plain mapper, default/polymorphic
 * typing is never enabled.
 */
public final class StdoutSink implements PayloadSink {

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public void accept(DejuPayload payload) {
        StringBuilder header = new StringBuilder();
        header.append("[deju] session ").append(payload.getSessionId())
                .append(" target=").append(payload.getTarget())
                .append(" durationMs=").append(payload.getDurationMs());
        for (FileCoverage f : payload.getFiles()) {
            int full = 0, partial = 0, none = 0;
            for (LineCoverage l : f.getLines()) {
                switch (l.getStatus()) {
                    case FULL: full++; break;
                    case PARTIAL: partial++; break;
                    default: none++; break;
                }
            }
            header.append("\n    ").append(f.getFqClassName())
                    .append("  FULL=").append(full)
                    .append(" PARTIAL=").append(partial)
                    .append(" NONE=").append(none);
        }
        System.out.println(header);
        try {
            System.out.println(mapper.writeValueAsString(payload));
        } catch (Exception e) {
            System.err.println("[deju] failed to serialize payload: " + e.getMessage());
        }
    }
}
