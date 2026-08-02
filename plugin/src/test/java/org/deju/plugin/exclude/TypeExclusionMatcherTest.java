package org.deju.plugin.exclude;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain-Java tests for the exclusion glob rules; no IntelliJ Platform fixture needed. */
class TypeExclusionMatcherTest {

    private static TypeExclusionMatcher globs(String... patterns) {
        return new TypeExclusionMatcher(List.of(patterns), List.of(), List.of());
    }

    @Test
    void suffixGlobIgnoresCase() {
        TypeExclusionMatcher m = globs("*dto");
        assertTrue(m.isExcluded("com.example.product.dto.ProductDto"));
        assertTrue(m.isExcluded("com.example.order.OrderDTO"), "acronym casing must not matter");
        assertTrue(m.isExcluded("com.example.order.orderdto"));
        assertFalse(m.isExcluded("com.example.product.ProductService"));
    }

    @Test
    void starSpansDotsSoAPackageGlobWorks() {
        TypeExclusionMatcher m = globs("*.dto.*");
        assertTrue(m.isExcluded("com.example.product.dto.Anything"));
        assertTrue(m.isExcluded("com.example.dto.deep.Nested"));
        assertFalse(m.isExcluded("com.example.product.ProductDto"), "no dto package segment");
    }

    @Test
    void dotsInAPatternAreLiteralNotRegexWildcards() {
        TypeExclusionMatcher m = globs("com.example.legacy.*");
        assertTrue(m.isExcluded("com.example.legacy.OldThing"));
        // Would match if '.' were left as a regex any-character.
        assertFalse(m.isExcluded("comXexampleXlegacyXOldThing"));
    }

    @Test
    void patternIsAnchoredAtBothEnds() {
        TypeExclusionMatcher m = globs("*entity");
        assertTrue(m.isExcluded("com.example.product.entity.ProductEntity"));
        assertFalse(m.isExcluded("com.example.EntityResolverFactory"), "suffix glob must not match mid-name");
    }

    @Test
    void innerClassesAreMatchable() {
        assertTrue(globs("*dto*").isExcluded("com.example.ProductDto$Builder"));
        assertTrue(globs("com.example.Product$*").isExcluded("com.example.Product$Builder"));
    }

    @Test
    void questionMarkMatchesExactlyOneCharacter() {
        TypeExclusionMatcher m = globs("com.example.V?");
        assertTrue(m.isExcluded("com.example.V1"));
        assertFalse(m.isExcluded("com.example.V12"));
    }

    @Test
    void explicitKeepOutranksAMatchingPattern() {
        TypeExclusionMatcher m = new TypeExclusionMatcher(
                List.of("*data"), List.of(), List.of("com.example.DataMigrationService"));
        assertFalse(m.isExcluded("com.example.DataMigrationService"));
        assertTrue(m.isExcluded("com.example.CustomerData"), "the pattern still applies elsewhere");
        assertNull(m.matchingPattern("com.example.DataMigrationService"),
                "a kept class reports no responsible pattern");
    }

    @Test
    void keepIsCaseInsensitiveToo() {
        TypeExclusionMatcher m = new TypeExclusionMatcher(
                List.of("*dto"), List.of(), List.of("com.example.ProductDTO"));
        assertFalse(m.isExcluded("com.example.productdto"));
    }

    @Test
    void anIndividuallyTickedClassIsExcludedWithoutAnyPattern() {
        TypeExclusionMatcher m = new TypeExclusionMatcher(
                List.of(), List.of("com.example.PriceRules"), List.of());
        assertTrue(m.isExcluded("com.example.PriceRules"));
        assertFalse(m.isExcluded("com.example.PriceRulesEngine"));
    }

    @Test
    void matchingPatternNamesTheResponsibleGlob() {
        TypeExclusionMatcher m = globs("*dto", "*entity");
        assertEquals("*entity", m.matchingPattern("com.example.ProductEntity"));
        assertNull(m.matchingPattern("com.example.ProductService"));
    }

    @Test
    void blankAndNullPatternsAreIgnoredRatherThanMatchingEverything() {
        TypeExclusionMatcher m = globs("", "   ");
        assertFalse(m.isExcluded("com.example.Anything"));
    }

    @Test
    void filterExcludedPreservesInputOrder() {
        TypeExclusionMatcher m = globs("*dto");
        List<String> hit = m.filterExcluded(List.of(
                "com.example.AaDto", "com.example.Service", "com.example.BbDto"));
        assertEquals(List.of("com.example.AaDto", "com.example.BbDto"), hit);
    }

    @Test
    void patternTextSplitsOnNewlinesCommasAndSemicolons() {
        assertEquals(List.of("com.example.legacy.*", "*Snapshot", "*Audit"),
                TypeExclusionMatcher.parsePatternText("com.example.legacy.*\n*Snapshot ; *Audit"));
        assertEquals(List.of(), TypeExclusionMatcher.parsePatternText("  \n \n"));
        assertEquals(List.of(), TypeExclusionMatcher.parsePatternText(null));
    }

    @Test
    void defaultGenericPatternsAreASubsetOfWhatTheUiOffers() {
        assertTrue(TypeExclusionMatcher.GENERIC_PATTERNS
                .containsAll(TypeExclusionMatcher.DEFAULT_GENERIC_PATTERNS));
        // The riskier suffixes ship unticked; see the field comment for why.
        for (String off : List.of("*model", "*bean", "*record", "*form", "*.model.*", "*.domain.*")) {
            assertFalse(TypeExclusionMatcher.DEFAULT_GENERIC_PATTERNS.contains(off), off);
            assertTrue(TypeExclusionMatcher.GENERIC_PATTERNS.contains(off), off);
        }
    }

    @Test
    void packagePatternsCatchEntitiesNamedWithoutASuffix() {
        TypeExclusionMatcher m = new TypeExclusionMatcher(
                TypeExclusionMatcher.DEFAULT_GENERIC_PATTERNS, List.of(), List.of());
        // The whole point: JPA entities are called Product and Order, not ProductEntity.
        assertTrue(m.isExcluded("com.example.product.entity.Product"));
        assertTrue(m.isExcluded("com.example.order.entity.Order"));
        assertTrue(m.isExcluded("com.example.product.dto.Price"));
        assertEquals("*.entity.*", m.matchingPattern("com.example.product.entity.Product"));
    }

    @Test
    void aPackageGlobNeedsAWholeSegment() {
        TypeExclusionMatcher m = globs("*.entity.*");
        assertTrue(m.isExcluded("com.example.entity.Product"));
        // 'entityresolver' merely starts with 'entity', it is a different package.
        assertFalse(m.isExcluded("com.example.entityresolver.EntityResolver"));
        assertFalse(m.isExcluded("com.example.myentity.Thing"));
    }

    @Test
    void serviceLayerSurvivesTheExpandedDefaults() {
        TypeExclusionMatcher m = new TypeExclusionMatcher(
                TypeExclusionMatcher.DEFAULT_GENERIC_PATTERNS, List.of(), List.of());
        for (String keep : List.of(
                "com.example.product.controller.ProductController",
                "com.example.product.service.ProductService",
                "com.example.product.service.impl.ProductServiceImpl",
                "com.example.product.repository.ProductRepository",
                "com.example.product.mapper.ProductMapper",
                "com.example.common.DataMigrationService",
                "com.example.product.model.PricingModel",
                "com.example.config.AppConfig")) {
            assertFalse(m.isExcluded(keep), keep + " must stay visible");
        }
    }

    // ------------------------------------------------------- accessor shape ---

    @Test
    void accessorShapeNeedsAnUpperCaseOrUnderscoreAfterThePrefix() {
        assertTrue(TypeExclusionMatcher.isAccessorShaped("getName"));
        assertTrue(TypeExclusionMatcher.isAccessorShaped("setPrice"));
        assertTrue(TypeExclusionMatcher.isAccessorShaped("isActive"));
        assertTrue(TypeExclusionMatcher.isAccessorShaped("hasItems"));
        assertTrue(TypeExclusionMatcher.isAccessorShaped("get_id"));
        // Ordinary verbs that merely start with the same letters are not accessors.
        assertFalse(TypeExclusionMatcher.isAccessorShaped("getaway"));
        assertFalse(TypeExclusionMatcher.isAccessorShaped("settle"));
        assertFalse(TypeExclusionMatcher.isAccessorShaped("issue"));
        assertFalse(TypeExclusionMatcher.isAccessorShaped("get"));
        assertFalse(TypeExclusionMatcher.isAccessorShaped("calculateTotal"));
        assertFalse(TypeExclusionMatcher.isAccessorShaped(null));
        assertFalse(TypeExclusionMatcher.isAccessorShaped(""));
    }

    @Test
    void objectBoilerplateCountsAsAnAccessor() {
        for (String m : List.of("toString", "hashCode", "equals", "builder", "<init>")) {
            assertTrue(TypeExclusionMatcher.isAccessorShaped(m), m);
        }
    }

    // ------------------------------------------------------------ decisions ---

    @Test
    void onlyDisagreementsWithThePatternsAreStored() {
        TypeExclusionMatcher patterns = globs("*dto");
        Map<String, Boolean> ticked = new LinkedHashMap<>();
        ticked.put("com.example.ProductDto", true);       // agrees with *dto -> store nothing
        ticked.put("com.example.ProductService", false);  // agrees (not excluded) -> nothing
        ticked.put("com.example.PriceRules", true);       // ticked, no pattern -> explicit exclude
        ticked.put("com.example.OrderDto", false);        // unticked despite *dto -> explicit keep

        TypeExclusionMatcher.Decisions d = TypeExclusionMatcher.decisions(ticked, patterns);
        assertEquals(List.of("com.example.PriceRules"), d.excluded);
        assertEquals(List.of("com.example.OrderDto"), d.kept);
    }

    @Test
    void storedDecisionsSurviveAddingAPatternLater() {
        // The user ticks nothing beyond what *dto already covers...
        Map<String, Boolean> ticked = new LinkedHashMap<>();
        ticked.put("com.example.ProductEntity", false);
        TypeExclusionMatcher.Decisions d = TypeExclusionMatcher.decisions(ticked, globs("*dto"));
        assertEquals(List.of(), d.kept, "an unticked class no pattern covers is not a 'keep'");

        // ...so when *entity is added afterwards, nothing blocks it.
        TypeExclusionMatcher later = new TypeExclusionMatcher(
                List.of("*dto", "*entity"), d.excluded, d.kept);
        assertTrue(later.isExcluded("com.example.ProductEntity"));
    }

    @Test
    void anExplicitKeepStillBlocksAPatternAddedLater() {
        Map<String, Boolean> ticked = new LinkedHashMap<>();
        ticked.put("com.example.DataMigrationService", false);
        TypeExclusionMatcher.Decisions d = TypeExclusionMatcher.decisions(ticked, globs("*data*"));
        assertEquals(List.of("com.example.DataMigrationService"), d.kept);

        TypeExclusionMatcher later = new TypeExclusionMatcher(
                List.of("*data*", "*service"), d.excluded, d.kept);
        assertFalse(later.isExcluded("com.example.DataMigrationService"),
                "a deliberate keep must outlive later pattern changes");
    }

    @Test
    void theDefaultSetMatchesOrdinaryDomainClassNames() {
        TypeExclusionMatcher m = new TypeExclusionMatcher(
                TypeExclusionMatcher.DEFAULT_GENERIC_PATTERNS, List.of(), List.of());
        assertTrue(m.isExcluded("com.example.product.dto.ProductDto"));
        assertTrue(m.isExcluded("com.example.product.entity.Product_Entity".replace("_", "")));
        assertTrue(m.isExcluded("com.example.order.CreateOrderRequest"));
        assertTrue(m.isExcluded("com.example.order.OrderResponse"));
        // Things the user is actually reading must survive the defaults.
        assertFalse(m.isExcluded("com.example.product.controller.ProductController"));
        assertFalse(m.isExcluded("com.example.product.ProductService"));
        assertFalse(m.isExcluded("com.example.product.ProductRepository"));
        assertFalse(m.isExcluded("com.example.product.ProductMapper"));
    }

    @Test
    void aNestedClassFollowsTheOuterClassItLivesIn() {
        // The whole reason the feature exists: Lombok's builder for an excluded ApiResponse
        // is reported as ApiResponse$ApiResponseBuilder, whose name ends in "Builder", so
        // *response folded the wrapper and left every .field(…) step on its builder behind.
        TypeExclusionMatcher m = globs("*response");
        assertTrue(m.isExcluded("com.example.common.ApiResponse"));
        assertTrue(m.isExcluded("com.example.common.ApiResponse$ApiResponseBuilder"));
        assertTrue(m.isExcluded("com.example.common.ApiResponse$PaginationMeta"));
        assertTrue(m.isExcluded("com.example.common.ApiResponse$PaginationMeta$PaginationMetaBuilder"),
                "nesting can go deeper than one level");
    }

    @Test
    void aNestedClassInsideAnOrdinaryClassIsStillShown() {
        TypeExclusionMatcher m = globs("*response");
        assertFalse(m.isExcluded("com.example.order.OrderService$Tally"),
                "an inner helper of a business class is business code");
    }

    @Test
    void theNestedClassesOwnRuleWinsOverTheOuterOne() {
        TypeExclusionMatcher m = new TypeExclusionMatcher(
                List.of("*dto"), List.of(), List.of("com.example.OrderDto$Validator"));
        assertTrue(m.isExcluded("com.example.OrderDto"));
        assertFalse(m.isExcluded("com.example.OrderDto$Validator"),
                "an explicit keep on the nested class is the more specific instruction");
    }

    @Test
    void anExplicitExclusionOfAnOuterClassAlsoHidesItsNestedClasses() {
        TypeExclusionMatcher m = new TypeExclusionMatcher(
                List.of(), List.of("com.example.common.ApiResponse"), List.of());
        assertTrue(m.isExcluded("com.example.common.ApiResponse$ApiResponseBuilder"));
    }

    @Test
    void aNestedClassNamesTheRuleThatActuallyHidIt() {
        // The report's "ⓘ *response" hint has to name a pattern the user recognises, and
        // reporting nothing for a class that plainly vanished is worse than naming the outer
        // class's rule.
        assertEquals("*response", globs("*response")
                .matchingPattern("com.example.common.ApiResponse$ApiResponseBuilder"));
        assertNull(globs("*response").matchingPattern("com.example.order.OrderService$Tally"));
    }

    @Test
    void aDollarInATopLevelNameIsNotTreatedAsNesting() {
        // A leading '$' is legal in a Java identifier; it encloses nothing.
        assertFalse(globs("*response").isExcluded("$Odd"));
    }
}
