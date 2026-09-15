package org.deju.agent.probe;

/**
 * Test fixture with a genuine controller-style call chain and a compound-condition
 * branch. Used by {@code InstrumentationTest} to prove the agent records line and
 * branch coverage correctly, this is a unit-test fixture, not a runnable app.
 */
public class CoverageFixture {

    /** The armed "deju point" for the test. */
    public int entry(String region) {
        int count = 0;
        if (region != null && !region.isEmpty()) { // two decisions on one line -> 4 edges
            count = filter(region);                 // taken when region is present
        } else {
            count = all();                          // the RED (untaken) branch for region="EMEA"
        }
        return count;
    }

    public int filter(String region) {
        return region.length();
    }

    /**
     * A plain-boolean {@code &&} chain with no negation/null-checks, armed as its own
     * target so a test can drive it multiple times and see {@code TRUE_ONLY} /
     * {@code FALSE_ONLY} / {@code MIXED} / {@code SKIPPED} on the two decisions,
     * independent of {@link #entry}'s own call tree.
     */
    public int loopChain(boolean[] values) {
        int hits = 0;
        for (int i = 0; i + 1 < values.length; i++) {
            if (values[i] && values[i + 1]) {
                hits++;
            }
        }
        return hits;
    }

    /**
     * A relational decision mixed with a plain boolean one, so a test can confirm the
     * relational operand comes out {@code OTHER} (excluded from true/false coloring),
     * rather than being guessed.
     */
    public boolean mixedKinds(int x, boolean flag) {
        return x > 5 && flag;
    }

    public int all() {
        return 5;
    }

    /** Never entered by the test, must not appear in the payload. */
    public int neverCalled() {
        return 42;
    }
}
