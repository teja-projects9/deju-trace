package org.deju.agent.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link Session#methodNanos} sums each invocation's own enter&rarr;exit span, which is
 * correct for sequential calls but would double-count a self-recursive one: an outer
 * invocation's span already contains its inner recursive calls' spans, so adding both counts
 * the overlap twice. These drive {@link Session} directly with synthetic nanosecond
 * timestamps rather than through the ASM/classloader fixture {@link InstrumentationTest}
 * uses, since the bug lives entirely in this bookkeeping and needs no real bytecode to
 * reproduce.
 */
class SessionRecursionTest {

    private static final int METHOD_GID = 1;

    @Test
    void nonRecursiveCallsStillSumNormally() {
        Session s = new Session("t", 0);
        s.onEnter(METHOD_GID, 0);
        s.onExit(100);      // first call: 100ns
        s.onEnter(METHOD_GID, 200);
        s.onExit(250);      // second, unrelated call: 50ns

        assertEquals(150L, s.methodNanos.get(METHOD_GID),
                "two back-to-back, non-overlapping calls should simply add up");
    }

    @Test
    void selfRecursionCountsTheOutermostSpanOnce() {
        Session s = new Session("t", 0);
        s.onEnter(METHOD_GID, 0);      // level 1 enters
        s.onEnter(METHOD_GID, 1_000);  // level 2 (recursive) enters
        s.onEnter(METHOD_GID, 2_000);  // level 3 (recursive) enters
        s.onExit(3_000);               // level 3 exits: 1_000ns of its own, but nested
        s.onExit(4_000);               // level 2 exits: 3_000ns of its own, but nested
        s.onExit(5_000);               // level 1 exits: 5_000ns total wall time

        // The naive sum of every exit's own elapsed (1_000 + 3_000 + 5_000 = 9_000) would
        // count the two inner levels' time a second time, once on their own and once again
        // as part of the level enclosing them. Only the outermost span reflects what the
        // method actually cost the thread.
        assertEquals(5_000L, s.methodNanos.get(METHOD_GID),
                "recursive levels must not each add their own span on top of the outer one");
    }

    @Test
    void recursionDoesNotDisturbEachInvocationsOwnCallTreeTiming() {
        Session s = new Session("t", 0);
        s.onEnter(METHOD_GID, 0);
        s.onEnter(METHOD_GID, 1_000);
        s.onExit(3_000);   // inner call: 2_000ns of its own
        s.onExit(5_000);   // outer call: 5_000ns of its own

        // callNanos is per-invocation (used for the call tree's own per-step timing) and
        // must keep reporting each call's real, individual span regardless of the
        // methodNanos fix above. Index is call order, not exit order: seq 0 is the outer
        // call (entered first), seq 1 the inner recursive one — the outer's own span
        // genuinely spans the full 5_000ns end to end, inner nested inside it at 2_000ns.
        assertEquals(5_000L, s.callNanos[0]);
        assertEquals(2_000L, s.callNanos[1]);
    }

    @Test
    void aMethodNeverCalledRecordsNoTime() {
        Session s = new Session("t", 0);
        assertNull(s.methodNanos.get(METHOD_GID));
    }
}
