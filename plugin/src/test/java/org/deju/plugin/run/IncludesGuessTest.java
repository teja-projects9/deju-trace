package org.deju.plugin.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Plain-Java tests for the includes= guess; no IntelliJ fixture needed. */
class IncludesGuessTest {

    @Test
    void takesTheMainClasssOwnPackage() {
        assertEquals("com.example", IncludesGuess.fromMainClass("com.example.Application"));
    }

    @Test
    void takesTheNearestPackageForANestedMainClass() {
        // A Spring Boot app's @SpringBootApplication class often lives in the root package
        // directly; this covers a main class nested one level down instead.
        assertEquals("com.example.app", IncludesGuess.fromMainClass("com.example.app.Main"));
    }

    @Test
    void saysNothingForADefaultPackageMainClass() {
        // No dot at all: instrumenting an empty prefix would match every class on the
        // classpath, JDK included, which is worse than guessing nothing.
        assertEquals("", IncludesGuess.fromMainClass("Main"));
    }

    @Test
    void saysNothingWithNoMainClassToGuessFrom() {
        assertEquals("", IncludesGuess.fromMainClass(null));
    }
}
