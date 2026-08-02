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

    public int all() {
        return 5;
    }

    /** Never entered by the test, must not appear in the payload. */
    public int neverCalled() {
        return 42;
    }
}
