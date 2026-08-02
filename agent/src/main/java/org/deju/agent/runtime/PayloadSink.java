package org.deju.agent.runtime;

import org.deju.agent.contract.DejuPayload;

/**
 * Destination for a completed session's payload. Phase 2 prints to stdout; the
 * socket layer (phase 3) pushes one line of JSON to the connected plugin.
 */
public interface PayloadSink {
    void accept(DejuPayload payload);
}
