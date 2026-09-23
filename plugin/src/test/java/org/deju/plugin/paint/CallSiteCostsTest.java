package org.deju.plugin.paint;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.deju.plugin.contract.CallNode;
import org.deju.plugin.contract.DejuPayload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain-Java tests for charging a callee's cost back to the line that called it. */
class CallSiteCostsTest {

    @Test
    void chargesACallToTheCallersLine() {
        // Controller.handle line 20 calls Service.run, which costs 30 ms. The caller's own
        // line time would read as almost nothing; this is the figure the gutter needs.
        DejuPayload payload = payload(
                node(0, -1, "com.example.Controller", "handle", null, 31_000L),
                node(1, 0, "com.example.Service", "run", 20, 30_000L));

        CallSiteCosts.Site site = CallSiteCosts.of(payload).at("com.example.Controller", 20);
        assertEquals(30_000L, site.micros);
        assertEquals(1, site.calls);
        assertEquals("Service.run", site.describeTargets());
    }

    @Test
    void addsUpEveryInvocationFromOneLine() {
        // A loop body: one line, five calls. Summed, because every other per-line figure in
        // the editor is summed over the run too — a loop's line shows what the loop cost.
        List<CallNode> calls = new ArrayList<>();
        calls.add(node(0, -1, "com.example.Service", "run", null, 50_000L));
        for (int i = 1; i <= 5; i++) {
            calls.add(node(i, 0, "com.example.Repo", "findById", 42, 9_000L));
        }
        CallSiteCosts.Site site = CallSiteCosts.of(payload(calls.toArray(new CallNode[0])))
                .at("com.example.Service", 42);
        assertEquals(45_000L, site.micros);
        assertEquals(5, site.calls);
        assertEquals("Repo.findById ×5", site.describeTargets());
    }

    @Test
    void namesEveryTargetOfALineThatDispatchedSeveralWays() {
        CallSiteCosts.Site site = CallSiteCosts.of(payload(
                node(0, -1, "com.example.Service", "run", null, 10_000L),
                node(1, 0, "com.example.Pdf", "render", 7, 4_000L),
                node(2, 0, "com.example.Pdf", "render", 7, 4_000L),
                node(3, 0, "com.example.Csv", "render", 7, 1_000L)))
                .at("com.example.Service", 7);
        assertEquals(9_000L, site.micros);
        assertEquals(3, site.calls);
        assertEquals("Pdf.render ×2, Csv.render", site.describeTargets(),
                "most-called first, so the dominant target is the one that is read");
    }

    @Test
    void capsTheNamedTargetsAndCountsTheRest() {
        // A line dispatching through an interface can reach a dozen implementations; a
        // tooltip that lists all of them is one nobody reads.
        List<CallNode> calls = new ArrayList<>();
        calls.add(node(0, -1, "com.example.Service", "run", null, 10_000L));
        for (int i = 1; i <= 6; i++) {
            calls.add(node(i, 0, "com.example.Impl" + i, "handle", 9, 100L));
        }
        // Equal counts keep first-seen order, so this is deterministic rather than
        // whichever three a hash map happened to hand back.
        assertEquals("Impl1.handle, Impl2.handle, Impl3.handle and 3 others",
                CallSiteCosts.of(payload(calls.toArray(new CallNode[0])))
                        .at("com.example.Service", 9).describeTargets());
    }

    @Test
    void chargesAQueryToTheLineThatIssuedIt() {
        CallSiteCosts.Site site = CallSiteCosts.of(payload(
                node(0, -1, "com.example.Repo", "findAll", null, 20_000L),
                sql(1, 0, 15, 18_000L)))
                .at("com.example.Repo", 15);
        assertEquals(18_000L, site.micros);
        assertEquals("query", site.describeTargets(), "a SQL node carries no class of its own");
    }

    @Test
    void ignoresTheTraceRootAndCallersOutsideTheRecording() {
        CallSiteCosts costs = CallSiteCosts.of(payload(
                // The root has no call site: nothing above it was recorded.
                node(0, -1, "com.example.Controller", "handle", null, 31_000L),
                // A call whose parent is not in the tree cannot be charged to anything.
                node(9, 77, "com.example.Orphan", "run", 12, 5_000L)));
        assertNull(costs.at("com.example.Controller", 0));
        assertTrue(costs.isEmpty());
    }

    @Test
    void anAgentTooOldToSendACallTreeLeavesTheGutterAlone() {
        assertTrue(CallSiteCosts.of(new DejuPayload()).isEmpty());
        assertTrue(CallSiteCosts.of(null).isEmpty());
    }

    // ------------------------------------------------------------------ helpers ---

    private static DejuPayload payload(CallNode... nodes) {
        DejuPayload p = new DejuPayload();
        p.setCalls(List.of(nodes));
        return p;
    }

    private static CallNode node(int seq, int parent, String cls, String method,
                                 Integer callSiteLine, Long micros) {
        CallNode n = new CallNode();
        n.setSeq(seq);
        n.setParentSeq(parent);
        n.setClassName(cls);
        n.setMethodName(method);
        n.setCallSiteLine(callSiteLine);
        n.setTotalMicros(micros);
        return n;
    }

    private static CallNode sql(int seq, int parent, Integer callSiteLine, Long micros) {
        CallNode n = node(seq, parent, null, null, callSiteLine, micros);
        n.setSql("select 1");
        return n;
    }
}
