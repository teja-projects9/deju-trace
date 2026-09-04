package org.deju.plugin.paint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The percentage the timing column appends to each line's duration. */
class TimingGutterPercentTest {

    @Test
    void dropsTheDecimalOnceTheFigureIsAHeadline() {
        assertEquals(" 50%", TimingGutterProvider.suffixPercent(500, 1000));
        assertEquals(" 100%", TimingGutterProvider.suffixPercent(1000, 1000));
        assertEquals(" 10%", TimingGutterProvider.suffixPercent(100, 1000));
    }

    @Test
    void keepsOneDecimalWhereTheDifferenceStillMatters() {
        assertEquals(" 9.9%", TimingGutterProvider.suffixPercent(99, 1000));
        assertEquals(" 0.1%", TimingGutterProvider.suffixPercent(1, 1000));
    }

    /** A hundred trivial lines reading "0.0%" would say less than one that admits it is small. */
    @Test
    void collapsesAnythingUnderATenthOfAPercent() {
        assertEquals(" <0.1%", TimingGutterProvider.suffixPercent(1, 100_000));
        assertEquals(" 0%", TimingGutterProvider.suffixPercent(0, 1000));
    }

    /**
     * No run length means no denominator. Falling back to some other total would put a
     * confident-looking number in the gutter that is a percentage of nothing in particular.
     */
    @Test
    void saysNothingWhenThereIsNoRunToDivideBy() {
        assertEquals("", TimingGutterProvider.suffixPercent(500, 0));
        assertEquals("", TimingGutterProvider.suffixPercent(500, -1));
    }

    /** The compact gutter/report label: percent first, the duration bracketed after it. */
    @Test
    void bracketsTheDurationAfterTheLeadingPercent() {
        assertEquals("50% [500 µs]", TimingGutterProvider.formatWithPercent(500, 1000));
        assertEquals("<0.1% [1 µs]", TimingGutterProvider.formatWithPercent(1, 100_000));
    }

    /** With no run length there is no percent to lead with — just the bare duration, unbracketed. */
    @Test
    void fallsBackToTheBareDurationWithNoRunLength() {
        assertEquals("500 µs", TimingGutterProvider.formatWithPercent(500, 0));
    }
}
