package org.deju.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import org.deju.agent.contract.CallNode;
import org.deju.agent.contract.DejuPayload;
import org.deju.agent.contract.FileCoverage;
import org.deju.agent.contract.LineCoverage;
import org.deju.agent.contract.LineStatus;
import org.deju.agent.instrument.AsmInstrumenter;
import org.deju.agent.runtime.CoverageRuntime;
import org.junit.jupiter.api.Test;

/**
 * Deterministically verifies the coverage instrumentation: the fixture class is
 * transformed in-process and loaded through a child-first classloader (so the probes
 * resolve {@code CoverageRuntime} from the shared parent), then armed and invoked.
 * This exercises {@code CoverageClassVisitor} / {@code CoverageMethodVisitor} plus the
 * runtime session and payload builder, no agent attach, no external application.
 */
class InstrumentationTest {

    private static final String FIXTURE = "org.deju.agent.probe.CoverageFixture";
    private static final String TARGET = FIXTURE + "#entry";

    /** Loads a freshly-instrumented copy of the fixture in an isolated child loader. */
    private Class<?> instrumentedFixture(AtomicReference<DejuPayload> sink) throws Exception {
        byte[] original = readClassBytes(FIXTURE);
        byte[] instrumented = AsmInstrumenter.instrument(original, getClass().getClassLoader());

        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (FIXTURE.equals(name)) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> c = findLoadedClass(name);
                        if (c == null) {
                            c = defineClass(name, instrumented, 0, instrumented.length);
                        }
                        if (resolve) {
                            resolveClass(c);
                        }
                        return c;
                    }
                }
                return super.loadClass(name, resolve);
            }
        };

        CoverageRuntime.configure(sink::set);
        return loader.loadClass(FIXTURE);
    }

    @Test
    void recordsPartialDecisionAndRedElse() throws Exception {
        AtomicReference<DejuPayload> last = new AtomicReference<>();
        Class<?> fixture = instrumentedFixture(last);
        CoverageRuntime.arm(TARGET);

        Object instance = fixture.getDeclaredConstructor().newInstance();
        Method entry = fixture.getMethod("entry", String.class);
        Object result = entry.invoke(instance, "EMEA");
        assertEquals(4, result); // "EMEA".length()

        DejuPayload p = last.get();
        assertNotNull(p, "a payload should be emitted when the target returns");
        assertEquals(TARGET, p.getTarget());

        // Only CoverageFixture had entered methods (entry + filter); all()/neverCalled did not run.
        assertEquals(1, p.getFiles().size(), "exactly one file should be covered");
        FileCoverage fc = p.getFiles().get(0);
        assertTrue(fc.getFqClassName().endsWith("CoverageFixture"), fc.getFqClassName());

        boolean sawPartial = false;
        boolean sawRed = false;
        int fullCount = 0;
        for (LineCoverage lc : fc.getLines()) {
            if (lc.getStatus() == LineStatus.PARTIAL) {
                sawPartial = true;
                // Compound `region != null && !region.isEmpty()` = two decisions = 4 edges;
                // region="EMEA" takes exactly one edge of each => 2 covered.
                assertEquals(Integer.valueOf(4), lc.getBranchesTotal(), "compound if has 4 edges");
                assertEquals(Integer.valueOf(2), lc.getBranchesCovered(), "one edge per decision taken");
            } else if (lc.getStatus() == LineStatus.NONE) {
                sawRed = true;
            } else if (lc.getStatus() == LineStatus.FULL) {
                fullCount++;
            }
        }
        assertTrue(sawPartial, "the compound if line should be PARTIAL (yellow)");
        assertTrue(sawRed, "the untaken else (count = all()) should be NONE (red)");
        assertTrue(fullCount >= 2, "entered non-decision lines should be FULL (green)");
    }

    /**
     * Every line must carry the name of the method that owns it, and exactly one line per
     * entered method must be flagged as its declaration line. This is what lets the HTML
     * report group a file into method sections; unlike the timing fields it must never be
     * gated on a method being slow enough to measure.
     */
    @Test
    void tagsEveryLineWithItsOwningMethod() throws Exception {
        AtomicReference<DejuPayload> last = new AtomicReference<>();
        Class<?> fixture = instrumentedFixture(last);
        CoverageRuntime.arm(TARGET);

        Object instance = fixture.getDeclaredConstructor().newInstance();
        fixture.getMethod("entry", String.class).invoke(instance, "EMEA");

        DejuPayload p = last.get();
        assertNotNull(p, "a payload should be emitted when the target returns");
        FileCoverage fc = p.getFiles().get(0);

        Set<String> names = new TreeSet<>();
        Map<String, Integer> starts = new TreeMap<>();
        for (LineCoverage lc : fc.getLines()) {
            assertNotNull(lc.getMethodName(), "line " + lc.getLine() + " has no owning method");
            names.add(lc.getMethodName());
            if (Boolean.TRUE.equals(lc.getMethodStart())) {
                starts.merge(lc.getMethodName(), 1, Integer::sum);
            }
        }

        // entry() called filter(); all() and neverCalled() never ran, so they contribute
        // no lines and therefore no method names.
        assertEquals(Set.of("entry", "filter"), names, "only entered methods should be named");
        assertEquals(Set.of("entry", "filter"), starts.keySet(),
                "each entered method needs a declaration line");
        assertEquals(Integer.valueOf(1), starts.get("entry"), "exactly one start line per method");
        assertEquals(Integer.valueOf(1), starts.get("filter"), "exactly one start line per method");
    }

    /**
     * The call tree must record every invocation in execution order, with the caller→callee
     * edge and the line that made each call. {@code methodsEntered} is a Set and answers
     * none of that, which is why the ordered record exists alongside it.
     */
    @Test
    void recordsCallTreeInExecutionOrderWithCallSites() throws Exception {
        AtomicReference<DejuPayload> last = new AtomicReference<>();
        Class<?> fixture = instrumentedFixture(last);
        CoverageRuntime.arm(TARGET);

        Object instance = fixture.getDeclaredConstructor().newInstance();
        fixture.getMethod("entry", String.class).invoke(instance, "EMEA");

        DejuPayload p = last.get();
        assertNotNull(p, "a payload should be emitted when the target returns");

        List<CallNode> calls = p.getCalls();
        // entry() is the armed target and calls filter(); all() and neverCalled() never run.
        assertEquals(2, calls.size(), "exactly the two entered methods should be recorded");

        CallNode root = calls.get(0);
        assertEquals(0, root.getSeq(), "the target is step 0");
        assertEquals(-1, root.getParentSeq(), "the target has no recorded caller");
        assertEquals("entry", root.getMethodName());
        assertNull(root.getCallSiteLine(), "the target has no call site");

        CallNode callee = calls.get(1);
        assertEquals(1, callee.getSeq(), "filter() is step 1, it ran second");
        assertEquals(0, callee.getParentSeq(), "filter() was called by entry()");
        assertEquals("filter", callee.getMethodName());
        assertEquals(root.getClassName(), callee.getClassName());

        // The call site is the line inside entry() that invoked filter(), so it must be a
        // real line of the traced class rather than a guess.
        Integer site = callee.getCallSiteLine();
        assertNotNull(site, "the call site line should resolve");
        Set<Integer> knownLines = new TreeSet<>();
        for (LineCoverage lc : p.getFiles().get(0).getLines()) {
            knownLines.add(lc.getLine());
        }
        assertTrue(knownLines.contains(site),
                "call site " + site + " should be one of the class's lines " + knownLines);

        assertFalse(p.isCallsTruncated(), "a two-call session is nowhere near the cap");

        // Every payload must name the agent build that produced it, so the plugin can warn
        // when a traced JVM is still running an agent older than the installed plugin.
        // Loaded from a directory here rather than the shipped jar, so "unknown" is the
        // correct answer, what matters is that the field is always populated.
        assertNotNull(p.getAgentVersion(), "payload should always carry an agent version");
        assertFalse(p.getAgentVersion().isEmpty(), "agent version should never be blank");
    }

    @Test
    void secondSessionRecordsAfterFirstCompletes() throws Exception {
        AtomicReference<DejuPayload> last = new AtomicReference<>();
        Class<?> fixture = instrumentedFixture(last);
        CoverageRuntime.arm(TARGET);

        Object instance = fixture.getDeclaredConstructor().newInstance();
        Method entry = fixture.getMethod("entry", String.class);

        entry.invoke(instance, "EMEA");
        last.set(null);
        entry.invoke(instance, (Object) null); // region == null -> else branch runs

        DejuPayload p = last.get();
        assertNotNull(p, "a second session should record after the first completed");
        assertEquals(TARGET, p.getTarget());

        boolean sawDecisionLine = false;
        for (FileCoverage fc : p.getFiles()) {
            for (LineCoverage lc : fc.getLines()) {
                if (lc.getBranchesTotal() != null && lc.getBranchesTotal() == 4) {
                    sawDecisionLine = true;
                }
            }
        }
        assertTrue(sawDecisionLine, "the compound if line should still be recorded as a decision");
    }

    private static byte[] readClassBytes(String className) throws Exception {
        String resource = className.replace('.', '/') + ".class";
        try (InputStream in = InstrumentationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "fixture class resource must be on the test classpath: " + resource);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
