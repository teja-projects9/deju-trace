package org.deju.plugin.paint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.deju.plugin.contract.CallNode;
import org.deju.plugin.contract.DejuPayload;
import org.deju.plugin.contract.FileCoverage;

/**
 * Splits a run's files into the ones "Show" <b>paints</b> and the ones it <b>opens</b>.
 *
 * <p>Those are deliberately not the same set. Every recorded file is painted, because
 * coverage markup belongs to the document and is free until someone looks at it. Only a
 * capped, filtered subset gets an editor tab: a single traced request routinely touches
 * dozens of classes once builders, mappers and response wrappers are counted, and opening a
 * tab for each buries the file the developer was looking at under a wall of generated code.
 *
 * <p><b>Order is execution order</b>, the order the call tree first enters each class,
 * which puts the traced method's own file first and then follows the report top-to-bottom.
 * Ranking by time or by line count would sort the tab strip by a signal the user did not
 * ask about, and could push the method they clicked Track on out of the visible set.
 *
 * <p>Deliberately free of IntelliJ Platform imports so it can be unit-tested as plain Java;
 * {@link EditorPainter} supplies the exclusion rule and opens the result.
 */
public final class PaintPlan {

    /**
     * Every recorded file, in execution order, what gets <b>painted</b>.
     *
     * <p>Neither the exclusion list nor the cap applies here. Coverage markup lives on the
     * document, not on an editor tab, so painting a file costs nothing until the user
     * actually opens it, and when they do open one by hand, it is because they want to see
     * it. Filtering this too would leave the 11th file, or any excluded one, blank with no
     * visible reason.
     */
    public final List<FileCoverage> all;
    /** Files to open as editor tabs, already filtered, ordered and capped. */
    public final List<FileCoverage> open;
    /** Class names not opened because the project excludes their type. */
    public final List<String> excluded;
    /** Class names not opened because the limit was already reached. */
    public final List<String> overLimit;

    private PaintPlan(List<FileCoverage> all, List<FileCoverage> open,
                      List<String> excluded, List<String> overLimit) {
        this.all = List.copyOf(all);
        this.open = List.copyOf(open);
        this.excluded = List.copyOf(excluded);
        this.overLimit = List.copyOf(overLimit);
    }

    /** Total files the run recorded, opened or not. */
    public int recorded() {
        return all.size();
    }

    /** True when something was left out and the user should be told why. */
    public boolean isTrimmed() {
        return !excluded.isEmpty() || !overLimit.isEmpty();
    }

    /**
     * Builds the plan for one payload.
     *
     * @param isExcluded the project's exclusion rule, applied to fully-qualified class names
     * @param maxFiles   how many files may be opened; zero or less means no limit
     */
    public static PaintPlan of(DejuPayload payload, Predicate<String> isExcluded, int maxFiles) {
        List<FileCoverage> ordered = inExecutionOrder(payload);

        List<FileCoverage> keep = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        for (FileCoverage fc : ordered) {
            if (isExcluded.test(fc.getFqClassName())) {
                excluded.add(fc.getFqClassName());
            } else {
                keep.add(fc);
            }
        }
        // A pattern broad enough to match everything would otherwise turn Show into a
        // no-op with no visible cause. Better to show the run and let the cap trim it.
        if (keep.isEmpty() && !ordered.isEmpty()) {
            keep = ordered;
            excluded.clear();
        }

        List<String> overLimit = new ArrayList<>();
        if (maxFiles > 0 && keep.size() > maxFiles) {
            for (FileCoverage fc : keep.subList(maxFiles, keep.size())) {
                overLimit.add(fc.getFqClassName());
            }
            keep = keep.subList(0, maxFiles);
        }
        return new PaintPlan(ordered, keep, excluded, overLimit);
    }

    /**
     * The run's files ordered by when the call tree first enters each class.
     *
     * <p>Files with coverage but no call node, and every file in a payload recorded by an
     * agent too old to send a call tree, keep their original payload order at the end, so
     * nothing is ever lost by having no frame.
     */
    private static List<FileCoverage> inExecutionOrder(DejuPayload payload) {
        Map<String, FileCoverage> byClass = new LinkedHashMap<>();
        for (FileCoverage fc : payload.getFiles()) {
            if (fc.getFqClassName() != null) {
                byClass.putIfAbsent(fc.getFqClassName(), fc);
            }
        }
        List<FileCoverage> ordered = new ArrayList<>(byClass.size());
        Set<String> placed = new LinkedHashSet<>();
        List<CallNode> calls = payload.getCalls();
        if (calls != null) {
            for (CallNode node : calls) {
                String name = node.getClassName();
                // A SQL node carries no class, and a class may be called before or without
                // ever being resolved to a file.
                if (name == null || !placed.add(name)) {
                    continue;
                }
                FileCoverage fc = byClass.get(name);
                if (fc != null) {
                    ordered.add(fc);
                }
            }
        }
        for (Map.Entry<String, FileCoverage> e : byClass.entrySet()) {
            if (!placed.contains(e.getKey())) {
                ordered.add(e.getValue());
            }
        }
        return ordered;
    }
}
