package org.deju.agent.runtime;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mutable recording state for one in-flight session, bound to the thread that
 * entered the armed target method. Only ever touched by that one thread (except
 * the immutable id/target fields), so its collections need no synchronization.
 */
final class Session {

    private static final ThreadMXBean THREAD_MX = ManagementFactory.getThreadMXBean();
    /** False on a JVM/OS combination that cannot report per-thread CPU time (rare). */
    private static final boolean CPU_TIME_SUPPORTED = enableCpuTime();

    private static boolean enableCpuTime() {
        if (!THREAD_MX.isThreadCpuTimeSupported()) {
            return false;
        }
        if (!THREAD_MX.isThreadCpuTimeEnabled()) {
            try {
                THREAD_MX.setThreadCpuTimeEnabled(true);
            } catch (RuntimeException e) {
                return false;
            }
        }
        return true;
    }

    final String sessionId = UUID.randomUUID().toString();
    final String target;
    final int targetGid;
    final String startedAtIso;
    final long startNanos;
    /** CPU nanos this thread had burned when the session opened, or -1 if unavailable. */
    final long cpuStartNanos;

    /** methodGid of every method entered during this session (drives RED marking). */
    final Set<Integer> methodsEntered = new HashSet<>();
    /** lineProbeId of every line that executed. */
    final Set<Integer> linesHit = new HashSet<>();
    /** encoded (decisionId, edgeIndex) of every branch edge taken, see {@link #edgeKey}. */
    final Set<Long> edgesHit = new HashSet<>();

    // ------------------------------------------------------------------ timing ---
    // Wall-clock timing via deltas between consecutive line/enter/exit probes on this
    // (single) thread. A line's "self time" is the gap between its own probe and the next
    // probe, so the time of an UNINSTRUMENTED callee (e.g. a JDBC/Postgres call, not in
    // includes=) is naturally attributed to its call-site line, the plprofiler behaviour.
    /** lineProbeId -> accumulated self nanos. */
    final Map<Integer, Long> lineNanos = new HashMap<>();
    /**
     * methodGid -> inclusive (enter→exit) nanos, summed across invocations — but only ever
     * the outermost invocation of a given method at any one time, never an invocation
     * reached through the method recursing into itself. A recursive call's own enter→exit
     * span sits entirely inside the span of whichever invocation called it, so adding both
     * spans would count the overlapping time twice: three self-recursive levels each really
     * costing 10ms of wall time would otherwise report as 30+20+10 = 60ms instead of the
     * 30ms actually elapsed. {@link #activeDepth} is what tells an exiting frame whether it
     * was the outermost one and so should bank its time here at all.
     */
    final Map<Integer, Long> methodNanos = new HashMap<>();
    /** How many frames of each method are currently on the stack, recursive re-entries
     *  included — see {@link #methodNanos}. */
    private final Map<Integer, Integer> activeDepth = new HashMap<>();
    /** Active call frames: each entry is {methodGid, enterNanos, callSeq, outermost (0/1)}. */
    private final Deque<long[]> frames = new ArrayDeque<>();
    private int lastLineProbe = -1;
    private long lastLineNanos;

    // -------------------------------------------------------------- call tree ---
    // The ordered record of every method invocation, which is what lets the report
    // replay the run start to end. {@link #methodsEntered} is a Set and loses both the
    // order and the caller→callee edge, so it can never answer "what called what".
    //
    // Stored as parallel primitive arrays rather than a list of objects: onEnter runs on
    // hot application paths, and this way a call costs four array stores and no allocation.

    /** Ceiling on recorded invocations, a runaway loop must not exhaust the traced app's heap. */
    private static final int MAX_CALLS = 200_000;

    /** Invocation i: which method ran. Index i is the call's sequence number (execution order). */
    int[] callMethodGid = new int[64];
    /** Invocation i: sequence number of the invocation that made this call, or -1 for the target. */
    int[] callParent = new int[64];
    /** Invocation i: line probe executing in the caller when the call was made (the call site), or -1. */
    int[] callSiteProbe = new int[64];
    /** Invocation i: inclusive enter→exit nanos for this single invocation. */
    long[] callNanos = new long[64];
    /** Invocation i: the SQL executed, when this node is a query rather than a method call. */
    String[] callSql = new String[64];
    int callCount;
    /** True once {@link #MAX_CALLS} was hit and later invocations were dropped. */
    boolean callsTruncated;

    /**
     * Depth of the call stack below the target frame. The target's own frame is
     * depth 0; the session closes when the target frame at depth 0 returns. This
     * makes normal and nested returns robust.
     */
    int depth = 0;

    Session(String target, int targetGid) {
        this.target = target;
        this.targetGid = targetGid;
        this.startedAtIso = Instant.now().toString();
        this.startNanos = System.nanoTime();
        this.cpuStartNanos = CPU_TIME_SUPPORTED ? THREAD_MX.getCurrentThreadCpuTime() : -1;
    }

    long durationMs() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * CPU time this thread has burned since the session opened, in microseconds, or -1 when
     * the JVM cannot report it. Read at session close, on the same thread that opened it —
     * {@link ThreadMXBean#getCurrentThreadCpuTime()} only ever answers for the calling
     * thread, which is exactly right for a session that never leaves the thread it started
     * on.
     */
    long cpuMicros() {
        if (cpuStartNanos < 0) {
            return -1;
        }
        long now = THREAD_MX.getCurrentThreadCpuTime();
        return now < 0 ? -1 : (now - cpuStartNanos) / 1_000L;
    }

    /** A source line began executing: close the previous line's delta and open this one. */
    void onLine(int lineProbeId, long now) {
        closeCurrentLine(now);
        lastLineProbe = lineProbeId;
        lastLineNanos = now;
        linesHit.add(lineProbeId);
    }

    /** A method was entered: freeze the caller's current line and push a timing frame. */
    void onEnter(int methodGid, long now) {
        // Read the call site BEFORE closing the line: lastLineProbe is still the caller's
        // currently-executing line, which is exactly the line that made this call.
        // closeCurrentLine() resets it to -1.
        int callSite = lastLineProbe;
        int parent = frames.isEmpty() ? -1 : (int) frames.peek()[2];
        closeCurrentLine(now);
        // Zero before this push means no frame of this method is already open below it on
        // the stack, i.e. this one is the outermost — see methodNanos.
        boolean outermost = activeDepth.merge(methodGid, 1, Integer::sum) == 1;
        frames.push(new long[] {methodGid, now, recordCall(methodGid, parent, callSite), outermost ? 1L : 0L});
    }

    /** A method returned: close its last line, pop the frame and bank its inclusive time. */
    void onExit(long now) {
        closeCurrentLine(now);
        long[] frame = frames.poll();
        if (frame != null) {
            int methodGid = (int) frame[0];
            long elapsed = now - frame[1];
            if (activeDepth.merge(methodGid, -1, Integer::sum) <= 0) {
                activeDepth.remove(methodGid); // tidy: this method is no longer on the stack at all
            }
            if (frame[3] == 1L) {
                methodNanos.merge(methodGid, elapsed, Long::sum);
            }
            int seq = (int) frame[2];
            if (seq >= 0) {
                callNanos[seq] = elapsed;
            }
        }
        lastLineNanos = now;
    }

    /**
     * A JDBC query completed: record it as a node in the call tree.
     *
     * <p>Deliberately does NOT close the current line. A query runs part-way through the
     * line that issued it and that line is still executing, so its self time must keep
     * accruing, the query's own duration is carried on the node instead.
     *
     * <p>The parent is whatever instrumented method is on the stack, which is usually the
     * service or repository method the developer is reading, not the JDBC internals in
     * between: those are outside {@code includes=} and were never instrumented.
     */
    void onSql(String sql, long startNanos, long endNanos) {
        int parent = frames.isEmpty() ? -1 : (int) frames.peek()[2];
        int seq = recordCall(SQL_METHOD_GID, parent, lastLineProbe);
        if (seq >= 0) {
            callSql[seq] = sql;
            callNanos[seq] = endNanos - startNanos;
        }
    }

    /** Method id for a query node: no {@code MethodModel} exists, so registry lookup must miss. */
    static final int SQL_METHOD_GID = -1;

    /**
     * Appends one invocation to the call tree and returns its sequence number, or -1 once
     * the cap is reached (the frame still unwinds correctly, it just banks no time).
     */
    private int recordCall(int methodGid, int parentSeq, int callSite) {
        if (callCount >= MAX_CALLS) {
            callsTruncated = true;
            return -1;
        }
        if (callCount == callMethodGid.length) {
            int n = callCount * 2;
            callMethodGid = Arrays.copyOf(callMethodGid, n);
            callParent = Arrays.copyOf(callParent, n);
            callSiteProbe = Arrays.copyOf(callSiteProbe, n);
            callNanos = Arrays.copyOf(callNanos, n);
            callSql = Arrays.copyOf(callSql, n);
        }
        int seq = callCount++;
        callMethodGid[seq] = methodGid;
        callParent[seq] = parentSeq;
        callSiteProbe[seq] = callSite;
        return seq;
    }

    private void closeCurrentLine(long now) {
        if (lastLineProbe != -1) {
            lineNanos.merge(lastLineProbe, now - lastLineNanos, Long::sum);
            lastLineProbe = -1;
        }
    }

    static long edgeKey(int decisionId, int edgeIndex) {
        return ((long) decisionId << 16) | (edgeIndex & 0xFFFFL);
    }
}
