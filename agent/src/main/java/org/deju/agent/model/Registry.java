package org.deju.agent.model;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Global, append-only registry of instrumentation metadata. Ids are allocated
 * during class transformation (which may run on several threads) and never change,
 * so lookups at probe time and at session end are lock-free reads.
 *
 * <p>The inserted bytecode carries only these small integer ids; all names, lines
 * and branch structure live here, nothing derived from a socket message is ever
 * used to allocate or look up an id.
 */
public final class Registry {

    private static final AtomicInteger METHOD_IDS = new AtomicInteger();
    private static final AtomicInteger LINE_IDS = new AtomicInteger();
    private static final AtomicInteger DECISION_IDS = new AtomicInteger();

    private static final ConcurrentMap<Integer, MethodModel> METHODS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Integer, LineProbe> LINES = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Integer, DecisionModel> DECISIONS = new ConcurrentHashMap<>();

    private Registry() {
    }

    public static int newMethodId() {
        return METHOD_IDS.getAndIncrement();
    }

    public static int newLineProbeId() {
        return LINE_IDS.getAndIncrement();
    }

    public static int newDecisionId() {
        return DECISION_IDS.getAndIncrement();
    }

    public static void putMethod(MethodModel model) {
        METHODS.put(model.getMethodGid(), model);
    }

    public static void putLineProbe(int lineProbeId, LineProbe probe) {
        LINES.put(lineProbeId, probe);
    }

    public static void putDecision(DecisionModel decision) {
        DECISIONS.put(decision.getDecisionId(), decision);
    }

    public static MethodModel method(int methodGid) {
        return METHODS.get(methodGid);
    }

    public static LineProbe lineProbe(int lineProbeId) {
        return LINES.get(lineProbeId);
    }

    public static DecisionModel decision(int decisionId) {
        return DECISIONS.get(decisionId);
    }
}
