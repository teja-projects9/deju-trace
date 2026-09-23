package org.deju.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The agent flag is hand-written into compose files and run configurations, so what it does
 * with a number outside the sensible range matters: the JVM it is attached to may be started
 * by something nobody is watching.
 */
class AgentConfigTest {

    @Test
    void defaultsToTheShippedCap() {
        assertEquals(AgentConfig.DEFAULT_MAX_CALLS,
                AgentConfig.parse("port=7391,token=t,includes=com.example").getMaxCalls());
    }

    @Test
    void readsAnExplicitCap() {
        assertEquals(1_000_000, AgentConfig.parse("port=7391,maxCalls=1000000").getMaxCalls());
    }

    @Test
    void clampsRatherThanRefusingToStart() {
        // A clamped agent still traces and still reports the cap it used. One that refused
        // to start over a typo would look like a broken agent in someone else's container.
        assertEquals(AgentConfig.MAX_MAX_CALLS,
                AgentConfig.parse("maxCalls=999999999").getMaxCalls());
        assertEquals(AgentConfig.MIN_MAX_CALLS, AgentConfig.parse("maxCalls=1").getMaxCalls());
        assertEquals(AgentConfig.MIN_MAX_CALLS, AgentConfig.parse("maxCalls=-5").getMaxCalls());
    }

    @Test
    void keepsTheDefaultWhenTheValueIsNotANumber() {
        assertEquals(AgentConfig.DEFAULT_MAX_CALLS, AgentConfig.parse("maxCalls=lots").getMaxCalls());
    }
}
