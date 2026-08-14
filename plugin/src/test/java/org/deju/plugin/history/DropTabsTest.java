package org.deju.plugin.history;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cutting a tab's markup out of the report template.
 *
 * <p>Worth pinning rather than trusting: this is the one place the exporter edits markup
 * instead of substituting into it, and getting the bounds wrong takes neighbouring tabs out
 * with it, which is the kind of damage that only shows up when somebody opens the file.
 */
class DropTabsTest {

    /** A stand-in with the same shape as the real template: several regions per tab. */
    private static final String TEMPLATE =
            "<head></head>"
            + "<!--tab:trace--><button id=\"tabTrace\"></button><!--/tab:trace-->"
            + "<!--tab:graph--><button id=\"tabGraph\"></button><!--/tab:graph-->"
            + "<!--tab:flow--><button id=\"tabFlow\"></button><!--/tab:flow-->"
            + "<!--tab:graph--><span id=\"graphControls\"></span><!--/tab:graph-->"
            + "<!--tab:trace--><div id=\"tracePanel\"><section></section></div><!--/tab:trace-->"
            + "<!--tab:graph--><section id=\"graphPanel\"></section><!--/tab:graph-->"
            + "<footer></footer>";

    private static ReportPrefs keeping(String... tabs) {
        ReportPrefs p = new ReportPrefs();
        p.tabs = new LinkedHashSet<>(Set.of(tabs));
        return p;
    }

    @Test
    void keepsEverythingWhenEveryTabIsIncluded() {
        assertEquals(TEMPLATE, HtmlReportGenerator.dropTabs(TEMPLATE, new ReportPrefs()));
    }

    /** Every region belonging to the tab goes, not just the first one found. */
    @Test
    void removesAllOfADroppedTabsRegions() {
        String out = HtmlReportGenerator.dropTabs(TEMPLATE, keeping("trace", "flow"));
        assertFalse(out.contains("tabGraph"));
        assertFalse(out.contains("graphControls"));
        assertFalse(out.contains("graphPanel"));
        assertFalse(out.contains("tab:graph"));
    }

    @Test
    void leavesTheSurvivingTabsIntact() {
        String out = HtmlReportGenerator.dropTabs(TEMPLATE, keeping("trace", "flow"));
        assertTrue(out.contains("<button id=\"tabTrace\"></button>"));
        assertTrue(out.contains("<button id=\"tabFlow\"></button>"));
        assertTrue(out.contains("<div id=\"tracePanel\"><section></section></div>"));
        assertTrue(out.contains("<head></head>"));
        assertTrue(out.contains("<footer></footer>"));
    }

    @Test
    void cutsSeveralTabsInOnePass() {
        String out = HtmlReportGenerator.dropTabs(TEMPLATE, keeping("trace"));
        assertFalse(out.contains("tabGraph"));
        assertFalse(out.contains("tabFlow"));
        assertTrue(out.contains("tabTrace"));
        assertTrue(out.contains("tracePanel"));
    }

    /**
     * A template edited into an unbalanced state must not take the rest of the document with
     * it. Losing one tab's markup is a bug; emitting a file that stops halfway is a disaster.
     */
    @Test
    void leavesAnUnbalancedRegionAlone() {
        String broken = "<head></head><!--tab:graph--><button></button><footer></footer>";
        assertEquals(broken, HtmlReportGenerator.dropTabs(broken, keeping("trace")));
    }

    /**
     * The shipped template, not a stand-in.
     *
     * <p>Every check above passes against markup written to satisfy it. This is the one that
     * fails when somebody edits {@code report.html} and moves, renames or drops half of a
     * marker pair, which would otherwise be found by a user opening an exported report with
     * a tab's panel missing and its button still there.
     */
    @Test
    void theShippedTemplateHasBalancedMarkers() throws Exception {
        String template;
        try (java.io.InputStream in = HtmlReportGenerator.class.getResourceAsStream("/report/report.html")) {
            assertTrue(in != null, "report.html missing from the classpath");
            template = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        for (String id : ReportPrefs.ALL_TABS) {
            int opens = count(template, "<!--tab:" + id + "-->");
            int closes = count(template, "<!--/tab:" + id + "-->");
            assertTrue(opens > 0, "no regions marked for tab " + id);
            assertEquals(opens, closes, "unbalanced markers for tab " + id);
        }
        // Cutting one tab must leave the others' markup, and the placeholders, untouched.
        String cut = HtmlReportGenerator.dropTabs(template, keeping("trace", "graph", "timeline", "findings"));
        assertFalse(cut.contains("id=\"flowPanel\""));
        assertFalse(cut.contains("id=\"tabFlow\""));
        assertTrue(cut.contains("id=\"tracePanel\""));
        assertTrue(cut.contains("id=\"timelinePanel\""));
        assertTrue(cut.contains("__PAYLOAD_BLOCK__"));
        assertTrue(cut.contains("__PREFS_BLOCK__"));
        assertTrue(cut.contains("__SCRIPT__"));
        assertTrue(cut.contains("__STYLES__"));
    }

    /** Non-overlapping occurrences of a literal. */
    private static int count(String haystack, String needle) {
        int n = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            n++;
        }
        return n;
    }
}
