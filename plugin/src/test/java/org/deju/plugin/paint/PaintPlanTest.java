package org.deju.plugin.paint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import org.deju.plugin.contract.CallNode;
import org.deju.plugin.contract.DejuPayload;
import org.deju.plugin.contract.FileCoverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain-Java tests for which files "Show" opens; no IntelliJ fixture needed. */
class PaintPlanTest {

    private static final Predicate<String> NOTHING_EXCLUDED = c -> false;

    @Test
    void opensFilesInTheOrderTheCallTreeReachesThem() {
        DejuPayload payload = payload(
                files("com.example.Repo", "com.example.Controller", "com.example.Service"),
                calls("com.example.Controller", "com.example.Service", "com.example.Repo"));
        assertEquals(List.of("com.example.Controller", "com.example.Service", "com.example.Repo"),
                names(PaintPlan.of(payload, NOTHING_EXCLUDED, 0)),
                "the traced method's own file comes first, whatever order the payload listed files in");
    }

    @Test
    void repeatedFramesDoNotReorderOrDuplicateAFile() {
        DejuPayload payload = payload(
                files("com.example.Controller", "com.example.Service", "com.example.Repo"),
                calls("com.example.Controller", "com.example.Service", "com.example.Repo",
                        "com.example.Service", "com.example.Repo"));
        assertEquals(List.of("com.example.Controller", "com.example.Service", "com.example.Repo"),
                names(PaintPlan.of(payload, NOTHING_EXCLUDED, 0)));
    }

    @Test
    void capsTheNumberOfFilesAndReportsTheRest() {
        DejuPayload payload = payload(
                files("a.A", "b.B", "c.C", "d.D"),
                calls("a.A", "b.B", "c.C", "d.D"));
        PaintPlan plan = PaintPlan.of(payload, NOTHING_EXCLUDED, 2);
        assertEquals(List.of("a.A", "b.B"), names(plan));
        assertEquals(List.of("c.C", "d.D"), plan.overLimit);
        assertEquals(4, plan.recorded(), "every recorded file is still accounted for");
        assertTrue(plan.isTrimmed());
    }

    @Test
    void exclusionsFreeUpSlotsUnderTheCap() {
        // The point of the feature: hiding builders must let real classes through, not just
        // shuffle which noise fills the ten tabs.
        DejuPayload payload = payload(
                files("a.Controller", "a.OrderDto", "a.ItemDto", "a.Service"),
                calls("a.Controller", "a.OrderDto", "a.ItemDto", "a.Service"));
        Set<String> dtos = Set.of("a.OrderDto", "a.ItemDto");
        PaintPlan plan = PaintPlan.of(payload, dtos::contains, 2);
        assertEquals(List.of("a.Controller", "a.Service"), names(plan));
        assertEquals(List.of("a.OrderDto", "a.ItemDto"), plan.excluded);
        assertTrue(plan.overLimit.isEmpty());
    }

    @Test
    void zeroMeansNoLimit() {
        DejuPayload payload = payload(files("a.A", "b.B", "c.C"), calls("a.A", "b.B", "c.C"));
        PaintPlan plan = PaintPlan.of(payload, NOTHING_EXCLUDED, 0);
        assertEquals(3, plan.open.size());
        assertFalse(plan.isTrimmed(), "nothing was left out, so nothing should be reported");
    }

    @Test
    void aPatternMatchingEverythingStillOpensSomething() {
        // Otherwise "Show" would silently do nothing and read as a broken button.
        DejuPayload payload = payload(files("a.A", "b.B"), calls("a.A", "b.B"));
        PaintPlan plan = PaintPlan.of(payload, c -> true, 10);
        assertEquals(List.of("a.A", "b.B"), names(plan));
        assertTrue(plan.excluded.isEmpty());
    }

    @Test
    void filesWithNoCallNodeKeepPayloadOrderAtTheEnd() {
        DejuPayload payload = payload(
                files("a.Unvisited", "a.Controller"),
                calls("a.Controller"));
        assertEquals(List.of("a.Controller", "a.Unvisited"), names(PaintPlan.of(payload, NOTHING_EXCLUDED, 0)));
    }

    @Test
    void aPayloadWithNoCallTreeFallsBackToPayloadOrder() {
        // Recorded by an agent older than the call tree; the files are all we have.
        DejuPayload payload = payload(files("a.A", "b.B"), null);
        assertEquals(List.of("a.A", "b.B"), names(PaintPlan.of(payload, NOTHING_EXCLUDED, 0)));
    }

    @Test
    void aCallWithNoClassIsIgnored() {
        // SQL nodes carry no class name; they must not upset the ordering.
        DejuPayload payload = payload(files("a.Controller", "a.Repo"),
                calls("a.Controller", null, "a.Repo"));
        assertEquals(List.of("a.Controller", "a.Repo"), names(PaintPlan.of(payload, NOTHING_EXCLUDED, 0)));
    }

    @Test
    void aCalledClassWithNoRecordedFileIsSkipped() {
        DejuPayload payload = payload(files("a.Controller"), calls("a.Controller", "a.NoSourceHere"));
        assertEquals(List.of("a.Controller"), names(PaintPlan.of(payload, NOTHING_EXCLUDED, 0)));
    }

    @Test
    void everyRecordedFileIsPaintedEvenWhenItIsNotOpened() {
        // The point of separating the two lists: open the 11th file by hand and the coverage
        // is already on it, because painting was never capped.
        DejuPayload payload = payload(
                files("a.Controller", "a.OrderDto", "a.Service", "a.Repo"),
                calls("a.Controller", "a.OrderDto", "a.Service", "a.Repo"));
        PaintPlan plan = PaintPlan.of(payload, "a.OrderDto"::equals, 2);
        assertEquals(List.of("a.Controller", "a.Service"), names(plan));
        assertEquals(List.of("a.Controller", "a.OrderDto", "a.Service", "a.Repo"),
                plan.all.stream().map(FileCoverage::getFqClassName).collect(Collectors.toList()),
                "an excluded type and an over-limit file are both still painted");
        assertEquals(4, plan.recorded());
    }

    @Test
    void anEmptyPayloadIsSafe() {
        PaintPlan plan = PaintPlan.of(payload(new ArrayList<>(), calls()), NOTHING_EXCLUDED, 10);
        assertTrue(plan.open.isEmpty());
        assertFalse(plan.isTrimmed());
        assertEquals(0, plan.recorded());
    }

    // ------------------------------------------------------------------ helpers ---

    private static List<String> names(PaintPlan plan) {
        return plan.open.stream().map(FileCoverage::getFqClassName).collect(Collectors.toList());
    }

    private static DejuPayload payload(List<FileCoverage> files, List<CallNode> calls) {
        DejuPayload p = new DejuPayload();
        p.setFiles(files);
        p.setCalls(calls);
        return p;
    }

    private static List<FileCoverage> files(String... classNames) {
        List<FileCoverage> out = new ArrayList<>();
        for (String name : classNames) {
            FileCoverage fc = new FileCoverage();
            fc.setFqClassName(name);
            fc.setSourceFileName(name.substring(name.lastIndexOf('.') + 1) + ".java");
            out.add(fc);
        }
        return out;
    }

    private static List<CallNode> calls(String... classNames) {
        List<CallNode> out = new ArrayList<>();
        int seq = 0;
        for (String name : Arrays.asList(classNames)) {
            CallNode node = new CallNode();
            node.setSeq(seq);
            node.setParentSeq(seq == 0 ? -1 : 0);
            node.setClassName(name);
            out.add(node);
            seq++;
        }
        return out;
    }
}
