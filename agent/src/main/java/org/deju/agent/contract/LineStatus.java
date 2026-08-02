package org.deju.agent.contract;

/**
 * Coverage status of a single source line, as painted in the IDE:
 * <ul>
 *   <li>{@link #FULL}, green: line executed and every branch on it was taken.</li>
 *   <li>{@link #PARTIAL}, yellow: a decision point where only some branches were taken.</li>
 *   <li>{@link #NONE}, red: a line inside an entered method that never executed.</li>
 * </ul>
 * Serialized by Jackson using the enum constant name.
 */
public enum LineStatus {
    FULL,
    PARTIAL,
    NONE
}
