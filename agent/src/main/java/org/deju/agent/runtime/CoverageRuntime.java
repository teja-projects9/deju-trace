package org.deju.agent.runtime;

import java.util.concurrent.atomic.AtomicBoolean;

import org.deju.agent.contract.DejuPayload;
import org.deju.agent.model.DecisionModel;
import org.deju.agent.model.MethodModel;
import org.deju.agent.model.Registry;

/**
 * The runtime target of every inserted probe. Instrumented bytecode calls these
 * public static methods with nothing but small integer ids; all interpretation
 * happens here against the {@link Registry}.
 *
 * <p><b>Design constraints.</b> Probes fire on hot application paths, so when no
 * session is active on the calling thread every probe is a single {@code ThreadLocal}
 * read that returns and does nothing. Recording state is per-thread and touched by
 * one thread only, so no locking is needed on the session collections.
 *
 * <p><b>Branch technique.</b> Rather than rewriting control flow (which would add
 * merge points needing stack-map frames), the instrumenter duplicates the operands
 * of each conditional and hands them here; this method re-evaluates the condition to
 * learn which edge was taken. That keeps instrumentation stack-neutral and frame-free.
 */
public final class CoverageRuntime {

    // --- JVM conditional-branch opcodes (hardcoded so the runtime needs no ASM). ---
    private static final int IFEQ = 153, IFNE = 154, IFLT = 155, IFGE = 156, IFGT = 157, IFLE = 158;
    private static final int IF_ICMPEQ = 159, IF_ICMPNE = 160, IF_ICMPLT = 161, IF_ICMPGE = 162,
            IF_ICMPGT = 163, IF_ICMPLE = 164;
    private static final int IF_ACMPEQ = 165, IF_ACMPNE = 166;
    private static final int IFNULL = 198, IFNONNULL = 199;

    private static final int EDGE_NOT_TAKEN = 0;
    private static final int EDGE_TAKEN = 1;

    /** The one session currently being recorded, bound to the thread that opened it. */
    private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();
    /** MVP: at most one session in flight across all threads. */
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);

    /** Fully-qualified armed target ("pkg.Class#method"); null when disarmed. */
    private static volatile String armedTarget;
    private static volatile PayloadSink sink;

    private CoverageRuntime() {
    }

    // ---------------------------------------------------------------- control ---

    public static void configure(PayloadSink payloadSink) {
        sink = payloadSink;
    }

    /** True once a sink is wired, so callers can avoid clobbering a working one. */
    public static boolean hasSink() {
        return sink != null;
    }

    /** Arm on a fully-qualified {@code pkg.Class#method}. Ignores descriptor (MVP). */
    public static void arm(String fqMethod) {
        armedTarget = fqMethod;
    }

    public static void disarm() {
        armedTarget = null;
        // Best-effort: drop any in-flight session's global lock. The stuck thread's
        // ThreadLocal is cleared on its next enter/exit (see enter()).
        IN_FLIGHT.set(false);
    }

    public static String armedTarget() {
        return armedTarget;
    }

    // ------------------------------------------------------- session lifecycle ---

    /** Method-entry probe: opens a session on the armed target, else records entry. */
    public static void enter(int methodGid) {
        Session s = CURRENT.get();
        if (s != null) {
            // If a stale session lingers after a disarm/reset, discard and re-evaluate.
            if (!IN_FLIGHT.get()) {
                CURRENT.remove();
                s = null;
            } else {
                s.methodsEntered.add(methodGid);
                s.depth++;
                s.onEnter(methodGid, System.nanoTime());
                return;
            }
        }
        String target = armedTarget;
        if (target == null) {
            return;
        }
        MethodModel m = Registry.method(methodGid);
        if (m == null || !target.equals(m.fqName())) {
            return;
        }
        // This is the armed target with no active session: try to become the one in flight.
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            // TODO(deju): another session is recording on some thread. MVP ignores
            // concurrent sessions rather than corrupting; a queue would serialize them.
            return;
        }
        Session ns = new Session(target, methodGid);
        ns.methodsEntered.add(methodGid);
        ns.onEnter(methodGid, System.nanoTime());
        CURRENT.set(ns);
    }

    /** Method-exit probe: closes and emits the session when the target frame returns. */
    public static void exit(int methodGid) {
        Session s = CURRENT.get();
        if (s == null) {
            return;
        }
        long now = System.nanoTime();
        if (methodGid == s.targetGid && s.depth == 0) {
            s.onExit(now);
            CURRENT.remove();
            DejuPayload payload = PayloadBuilder.build(s);
            IN_FLIGHT.set(false);
            PayloadSink out = sink;
            if (out != null) {
                out.accept(payload);
            }
            return;
        }
        if (s.depth > 0) {
            s.depth--;
            s.onExit(now);
        }
    }

    // ---------------------------------------------------------------- jdbc ---

    /** Nesting depth of JDBC execute calls on this thread; see {@link #sqlEnter()}. */
    private static final ThreadLocal<int[]> SQL_DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    /** Longest SQL text kept; anything past this is truncated with an ellipsis. */
    private static final int MAX_SQL_CHARS = 2000;

    /**
     * Called on entry to a JDBC {@code execute*}; returns the start time, or 0 to skip.
     *
     * <p>Only the OUTERMOST execute is timed. A pooled connection hands out a proxy
     * ({@code HikariProxyPreparedStatement}) that implements the same interface and
     * delegates to the driver's own statement, so a single query passes through two or more
     * instrumented {@code execute} methods; without this the report would show the same
     * query several times, nested inside itself.
     */
    public static long sqlEnter() {
        int[] depth = SQL_DEPTH.get();
        boolean outermost = ++depth[0] == 1;
        // 0 doubles as "do not record". System.nanoTime() may legitimately be negative, but
        // exactly 0 is a single point in an origin-arbitrary 64-bit range; at worst one
        // query goes unrecorded.
        return outermost && CURRENT.get() != null ? System.nanoTime() : 0L;
    }

    /** Called on exit from a JDBC {@code execute*}, normally or with a pending throwable. */
    public static void sqlExit(String sql, long started) {
        int[] depth = SQL_DEPTH.get();
        if (depth[0] > 0) {
            depth[0]--;
        }
        if (started == 0L) {
            return;
        }
        String text = trimSql(sql);
        if (text == null || text.isEmpty()) {
            // Nothing identifies this query, so a row for it would be a blank line the reader
            // cannot act on. Happens when a statement reaches execute without having passed
            // through prepareStatement, a pool handing out a pre-built wrapper, say.
            return;
        }
        Session s = CURRENT.get();
        if (s != null) {
            s.onSql(text, started, System.nanoTime());
        }
    }

    /** Current JDBC nesting depth on this thread. Visible for tests only. */
    public static int sqlDepthForTest() {
        return SQL_DEPTH.get()[0];
    }

    /**
     * Normalises SQL for the report: collapses the whitespace a formatted query carries and
     * caps the length, so one enormous generated statement cannot dominate the payload.
     */
    private static String trimSql(String sql) {
        if (sql == null) {
            return null;
        }
        String flat = sql.replaceAll("\\s+", " ").trim();
        return flat.length() <= MAX_SQL_CHARS ? flat : flat.substring(0, MAX_SQL_CHARS) + "…";
    }

    // --------------------------------------------------------------- line probe ---

    public static void line(int lineProbeId) {
        Session s = CURRENT.get();
        if (s != null) {
            s.onLine(lineProbeId, System.nanoTime());
        }
    }

    // ------------------------------------------------------------- branch probes ---

    /** Single int-operand conditionals (IFEQ..IFLE). */
    public static void probe1(int value, int opcode, int decisionId) {
        Session s = CURRENT.get();
        if (s == null) {
            return;
        }
        boolean taken;
        switch (opcode) {
            case IFEQ: taken = value == 0; break;
            case IFNE: taken = value != 0; break;
            case IFLT: taken = value < 0; break;
            case IFGE: taken = value >= 0; break;
            case IFGT: taken = value > 0; break;
            case IFLE: taken = value <= 0; break;
            default: return;
        }
        recordEdge(s, decisionId, taken ? EDGE_TAKEN : EDGE_NOT_TAKEN);
    }

    /** Two int-operand comparisons (IF_ICMPEQ..IF_ICMPLE). */
    public static void probe2(int a, int b, int opcode, int decisionId) {
        Session s = CURRENT.get();
        if (s == null) {
            return;
        }
        boolean taken;
        switch (opcode) {
            case IF_ICMPEQ: taken = a == b; break;
            case IF_ICMPNE: taken = a != b; break;
            case IF_ICMPLT: taken = a < b; break;
            case IF_ICMPGE: taken = a >= b; break;
            case IF_ICMPGT: taken = a > b; break;
            case IF_ICMPLE: taken = a <= b; break;
            default: return;
        }
        recordEdge(s, decisionId, taken ? EDGE_TAKEN : EDGE_NOT_TAKEN);
    }

    /** Single reference-operand null checks (IFNULL / IFNONNULL). */
    public static void probeRef1(Object ref, int opcode, int decisionId) {
        Session s = CURRENT.get();
        if (s == null) {
            return;
        }
        boolean taken;
        switch (opcode) {
            case IFNULL: taken = ref == null; break;
            case IFNONNULL: taken = ref != null; break;
            default: return;
        }
        recordEdge(s, decisionId, taken ? EDGE_TAKEN : EDGE_NOT_TAKEN);
    }

    /** Two reference-operand identity comparisons (IF_ACMPEQ / IF_ACMPNE). */
    public static void probeRef2(Object a, Object b, int opcode, int decisionId) {
        Session s = CURRENT.get();
        if (s == null) {
            return;
        }
        boolean taken;
        switch (opcode) {
            case IF_ACMPEQ: taken = a == b; break;
            case IF_ACMPNE: taken = a != b; break;
            default: return;
        }
        recordEdge(s, decisionId, taken ? EDGE_TAKEN : EDGE_NOT_TAKEN);
    }

    /** tableswitch / lookupswitch: resolve the key to a case edge (or default). */
    public static void probeSwitch(int key, int decisionId) {
        Session s = CURRENT.get();
        if (s == null) {
            return;
        }
        DecisionModel d = Registry.decision(decisionId);
        if (d == null) {
            return;
        }
        recordEdge(s, decisionId, d.switchEdgeIndex(key));
    }

    private static void recordEdge(Session s, int decisionId, int edgeIndex) {
        s.edgesHit.add(Session.edgeKey(decisionId, edgeIndex));
    }
}
