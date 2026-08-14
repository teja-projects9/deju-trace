package org.deju.plugin.history;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the export writes into the report as its starting position. */
class ReportPrefsTest {

    /**
     * The whole point of the defaults: an export that never opens the options has to keep
     * producing the file it produced before the options existed.
     */
    @Test
    void defaultsAreTodaysBehaviour() {
        ReportPrefs p = new ReportPrefs();
        assertEquals(Set.copyOf(ReportPrefs.ALL_TABS), Set.copyOf(p.tabs));
        assertEquals("trace", p.effectiveOpenTab());
        assertTrue(p.showTime && p.showPercent && p.showStep && p.showLine);
        assertTrue(p.sql);
        assertFalse(p.groupRepeats || p.collapseTree || p.collapseSections);
        assertEquals("normal", p.density);
        assertEquals("auto", p.theme);
    }

    /** A report that opens on a tab nobody wrote into it would come up blank. */
    @Test
    void fallsBackWhenTheOpeningTabWasDropped() {
        ReportPrefs p = new ReportPrefs();
        p.tabs = new LinkedHashSet<>(Set.of("timeline", "findings"));
        p.openTab = "graph";
        assertEquals("timeline", p.effectiveOpenTab(), "first surviving tab in toolbar order");
    }

    @Test
    void keepsTheOpeningTabWhenItSurvived() {
        ReportPrefs p = new ReportPrefs();
        p.tabs = new LinkedHashSet<>(Set.of("trace", "findings"));
        p.openTab = "findings";
        assertEquals("findings", p.effectiveOpenTab());
    }

    /**
     * The model carries starting positions only. Shipping the tab list too would invite the
     * report's script to look for elements that, by then, are not in the document.
     */
    @Test
    void modelCarriesTheCorrectedOpenTabAndNoTabList() {
        ReportPrefs p = new ReportPrefs();
        p.tabs = new LinkedHashSet<>(Set.of("timeline"));
        p.openTab = "trace";
        p.density = "compact";
        assertEquals("timeline", p.toModel().get("openTab"));
        assertEquals("compact", p.toModel().get("density"));
        assertFalse(p.toModel().containsKey("tabs"));
    }

    @Test
    void hasTabReportsWhatWasIncluded() {
        ReportPrefs p = new ReportPrefs();
        p.tabs = new LinkedHashSet<>(Set.of("trace", "flow"));
        assertTrue(p.hasTab("flow"));
        assertFalse(p.hasTab("timeline"));
    }
}
