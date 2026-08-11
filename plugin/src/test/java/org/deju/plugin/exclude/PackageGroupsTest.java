package org.deju.plugin.exclude;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain-Java tests for the exclusion dialog's "By package" tab; no IntelliJ fixture needed. */
class PackageGroupsTest {

    @Test
    void packagesAreOrderedTheWayTheRunReachedThem() {
        // The tab has to read top-to-bottom like the class list and the editor tabs do, and a
        // package is reached when the first of its classes is.
        List<PackageGroups.Group> groups = PackageGroups.of(List.of(
                type("com.acme.dto.OrderDto", 4, 2),
                type("com.acme.web.OrderController", 1, 1),
                type("com.acme.dto.ItemDto", 9, 5)));

        assertEquals(List.of("com.acme.web", "com.acme.dto"), names(groups));
        assertEquals(2, groups.get(1).firstOrder, "the earliest of its classes places it");
    }

    @Test
    void packagesTheLatestRunMissedSortLastAndBusiestFirst() {
        List<PackageGroups.Group> groups = PackageGroups.of(List.of(
                type("com.acme.quiet.A", 1, ObservedTypes.NO_ORDER),
                type("com.acme.busy.B", 30, ObservedTypes.NO_ORDER),
                type("com.acme.web.C", 2, 1)));

        assertEquals(List.of("com.acme.web", "com.acme.busy", "com.acme.quiet"), names(groups));
    }

    @Test
    void classesAndInvocationsAreRolledUp() {
        List<PackageGroups.Group> groups = PackageGroups.of(List.of(
                type("com.acme.dto.OrderDto", 4, 1),
                type("com.acme.dto.ItemDto", 9, 2)));

        assertEquals(1, groups.size());
        assertEquals(List.of("com.acme.dto.OrderDto", "com.acme.dto.ItemDto"), groups.get(0).classes);
        assertEquals(13, groups.get(0).invocations);
    }

    @Test
    void nestedClassesCountTowardsTheirOuterClassesPackage() {
        // The agent sends binary names, so the builder Lombok generated inside OrderDto
        // arrives with a '$'. It lives in the same package its outer class does.
        List<PackageGroups.Group> groups = PackageGroups.of(List.of(
                type("com.acme.dto.OrderDto$OrderDtoBuilder", 7, 1)));

        assertEquals(List.of("com.acme.dto"), names(groups));
    }

    @Test
    void theDefaultPackageIsNotOffered() {
        // There is no name to write a rule against, and '*' as a package glob would hide the
        // entire recording, so the class tab stays the only honest way to exclude these.
        List<PackageGroups.Group> groups = PackageGroups.of(List.of(
                type("Scratch", 3, 1), type("com.acme.web.C", 1, 2)));

        assertEquals(List.of("com.acme.web"), names(groups));
    }

    @Test
    void aPackageGlobExcludesThePackageAndEverythingUnderIt() {
        TypeExclusionMatcher matcher = new TypeExclusionMatcher(
                List.of(PackageGroups.globFor("com.acme.dto")), List.of(), List.of());

        assertTrue(matcher.isExcluded("com.acme.dto.OrderDto"));
        assertTrue(matcher.isExcluded("com.acme.dto.internal.Cursor"),
                "ticking a parent package has to stand in for ticking the leaves under it");
        assertTrue(matcher.isExcluded("com.acme.dto.OrderDto$Builder"));
    }

    @Test
    void aPackageGlobStopsAtTheSegmentBoundary() {
        // Otherwise excluding com.acme.dto would silently take com.acme.dtoutils with it.
        TypeExclusionMatcher matcher = new TypeExclusionMatcher(
                List.of(PackageGroups.globFor("com.acme.dto")), List.of(), List.of());

        assertEquals(false, matcher.isExcluded("com.acme.dtoutils.Helper"));
        assertEquals(false, matcher.isExcluded("com.acme.Order"));
    }

    @Test
    void packageOfHandlesNamesWithNothingToStripOff() {
        assertEquals("", PackageGroups.packageOf("Scratch"));
        assertEquals("", PackageGroups.packageOf(null));
        assertEquals("com.acme", PackageGroups.packageOf("com.acme.Order"));
        assertEquals("com.acme", PackageGroups.packageOf("com.acme.Order$1"));
    }

    // ------------------------------------------------------------------ helpers ---

    private static List<String> names(List<PackageGroups.Group> groups) {
        return groups.stream().map(g -> g.name).collect(Collectors.toList());
    }

    private static ObservedTypes.Type type(String fqName, int invocations, int order) {
        return new ObservedTypes.Type(fqName, invocations, false, false, order,
                null, null, ObservedTypes.NO_LINE);
    }
}
