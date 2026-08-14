package org.deju.plugin.history;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How an exported report should look when it is first opened.
 *
 * <p>Two different things are settled here, and the difference matters. {@link #tabs} decides
 * what is <b>written into the file at all</b>: an unchecked tab has its markup cut out and
 * cannot be brought back by the reader. Everything else is only a <b>starting position</b>,
 * embedded as defaults that the report's own Customize panel may override and remember per
 * browser. So the exporter decides what exists, the reader decides how to look at it.
 *
 * <p>Dropping a tab saves very little space, and it is worth being clear about why: every tab
 * is drawn from the same embedded payload, so removing one removes its markup and nothing
 * else. Use it to keep a report focused, not to make it smaller; the Essential/Full choice is
 * the one that moves the byte count.
 *
 * <p>Every default here reproduces the report's long-standing behaviour, so an export that
 * never opens the options keeps producing exactly the file it produced before.
 */
public final class ReportPrefs {

    /** Tab ids in the order they appear, and the only values {@link #tabs} may contain. */
    public static final List<String> ALL_TABS = List.of("trace", "graph", "flow", "timeline", "findings");

    /** Which tabs are written into the file. */
    public Set<String> tabs = new LinkedHashSet<>(ALL_TABS);

    /** Tab the report opens on. Falls back to the first surviving tab if it was dropped. */
    public String openTab = "trace";
    /** Code Trace sub-view to open on: {@code tree} or {@code files}. */
    public String view = "tree";
    /** Row height: {@code normal} or {@code compact}. */
    public String density = "normal";
    /** {@code auto} follows the reader's OS setting; {@code light} and {@code dark} pin it. */
    public String theme = "auto";

    public boolean showTime = true;
    public boolean showPercent = true;
    public boolean showStep = true;
    public boolean showLine = true;

    /** SQL statements shown inline in the call tree. */
    public boolean sql = true;
    /** Repeats of one call folded under their first occurrence. */
    public boolean groupRepeats = false;
    /** Call tree starts folded to its top level instead of fully open. */
    public boolean collapseTree = false;
    /** File boxes and their method sections start folded in the Files view. */
    public boolean collapseSections = false;

    /** True when this tab's markup should be written into the file. */
    public boolean hasTab(String id) {
        return tabs.contains(id);
    }

    /**
     * The report's starting tab, corrected for tabs that were dropped.
     *
     * <p>A report whose opening tab does not exist would come up blank, which reads as a
     * broken file rather than as a choice made at export time.
     */
    public String effectiveOpenTab() {
        if (tabs.contains(openTab)) {
            return openTab;
        }
        for (String id : ALL_TABS) {
            if (tabs.contains(id)) {
                return id;
            }
        }
        return "trace";
    }

    /**
     * The starting positions, as the flat map the report reads.
     *
     * <p>{@link #tabs} is deliberately absent: by the time the report runs, a dropped tab is
     * simply not in the document, and shipping a list of what used to be there would only
     * invite the script to look for elements that cannot exist.
     */
    public Map<String, Object> toModel() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("openTab", effectiveOpenTab());
        m.put("view", view);
        m.put("density", density);
        m.put("theme", theme);
        m.put("showTime", showTime);
        m.put("showPercent", showPercent);
        m.put("showStep", showStep);
        m.put("showLine", showLine);
        m.put("sql", sql);
        m.put("groupRepeats", groupRepeats);
        m.put("collapseTree", collapseTree);
        m.put("collapseSections", collapseSections);
        return m;
    }
}
