package org.deju.agent.sql;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import net.bytebuddy.agent.ByteBuddyAgent;

import org.deju.agent.DejuAgent;
import org.deju.agent.contract.CallNode;
import org.deju.agent.contract.DejuPayload;
import org.deju.agent.runtime.CoverageRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the real JDBC transformer against a hand-written driver: the SQL reaches the
 * call tree, a pooled proxy does not double-count, and, the one that matters most, nothing
 * the agent does reads a bound parameter.
 *
 * <p>Fixtures are loaded through {@link FixtureLoader} rather than named directly, because a
 * class already on the test classpath is loaded before any transformer is installed.
 */
class JdbcCaptureTest {

    private static final String SERVICE = "com.example.shop.ShopService";
    private static final String CONNECTION = "com.example.jdbc.FakeJdbc$FakeConnection";
    private static final String STATEMENT = "com.example.jdbc.FakeJdbc$FakePreparedStatement";

    private static ClassLoader fixtures;

    @BeforeAll
    static void attach() {
        // The production entry point, so this covers the shipped matchers rather than a
        // test-only reimplementation of them.
        // includes covers the APPLICATION only. A real includes= never names the JDBC
        // driver, and instrumenting it here would make execute() itself a frame, so the
        // query would hang off the driver rather than off the service that ran it.
        DejuAgent.premain("port=0,token=t,includes=com.example.shop", ByteBuddyAgent.install());
        fixtures = new FixtureLoader(JdbcCaptureTest.class.getClassLoader());
    }

    @AfterEach
    void disarm() {
        CoverageRuntime.disarm();
    }

    // ------------------------------------------------------------------ helpers ---

    private static Object newService() throws Exception {
        Object connection = fixtures.loadClass(CONNECTION).getConstructor().newInstance();
        return fixtures.loadClass(SERVICE).getConstructor(Connection.class).newInstance(connection);
    }

    private static DejuPayload record(Object service, String method, Object... args) throws Exception {
        AtomicReference<DejuPayload> captured = new AtomicReference<>();
        CoverageRuntime.configure(captured::set);
        CoverageRuntime.arm(SERVICE + "#" + method);
        Class<?>[] types = args.length == 0 ? new Class<?>[0] : new Class<?>[] {InputStream.class};
        Method m = service.getClass().getMethod(method, types);
        m.invoke(service, args);
        CoverageRuntime.disarm();
        return captured.get();
    }

    private static List<CallNode> queries(DejuPayload payload) {
        assertNotNull(payload, "no payload was produced, was the fixture instrumented?");
        return payload.getCalls().stream().filter(c -> c.getSql() != null).toList();
    }

    // -------------------------------------------------------------------- tests ---

    @Test
    void theTransformerActuallyAppliedToTheDriver() throws Exception {
        Object connection = fixtures.loadClass(CONNECTION).getConstructor().newInstance();
        Object statement = connection.getClass()
                .getMethod("prepareStatement", String.class)
                .invoke(connection, "SELECT 1");
        // A positive control: if this fails, every assertion below would fail for a reason
        // that has nothing to do with the behaviour being tested.
        assertTrue(statement instanceof SqlCarrier,
                "the prepared statement should have been given a field to carry its SQL");
        assertEquals("SELECT 1", ((SqlCarrier) statement).getDejuSql());
    }

    @Test
    void preparedStatementSqlReachesTheCallTree() throws Exception {
        DejuPayload payload = record(newService(), "loadProducts");

        List<CallNode> sql = queries(payload);
        assertEquals(1, sql.size(), "expected exactly one query node");
        assertEquals("SELECT p.id, p.name FROM products p WHERE p.category_id = ?",
                sql.get(0).getSql(), "newlines and runs of spaces are collapsed");
        assertNotNull(sql.get(0).getTotalMicros(), "the query must carry its duration");
        assertTrue(sql.get(0).getTotalMicros() > 0);
    }

    @Test
    void theQueryHangsOffTheMethodThatRanIt() throws Exception {
        DejuPayload payload = record(newService(), "loadProducts");

        CallNode query = queries(payload).get(0);
        assertTrue(query.getParentSeq() >= 0, "a query must hang off the calling frame");
        assertEquals("loadProducts", payload.getCalls().get(query.getParentSeq()).getMethodName());
        assertNotNull(query.getCallSiteLine(), "the query must point at the line that issued it");
    }

    @Test
    void aQueryNodeCarriesNoClassOrMethodName() throws Exception {
        CallNode query = queries(record(newService(), "loadProducts")).get(0);
        // This is how the report tells a query apart from a method call.
        assertNull(query.getClassName());
        assertNull(query.getMethodName());
    }

    @Test
    void plainStatementSqlIsTakenFromTheArgument() throws Exception {
        List<CallNode> sql = queries(record(newService(), "plainStatement"));
        assertEquals(1, sql.size());
        assertEquals("DELETE FROM cart WHERE session_id = 'abc'", sql.get(0).getSql());
    }

    @Test
    void aPooledProxyDoesNotRecordTheSameQueryTwice() throws Exception {
        // Two instrumented execute() methods run, the pool's proxy and the driver's
        // statement, but the developer issued one query and must see one row.
        assertEquals(1, queries(record(newService(), "viaPool")).size(),
                "a pooled statement must not appear twice, nested inside itself");
    }

    @Test
    void boundParameterValuesAreNeverCaptured() throws Exception {
        Class<?> stmtClass = fixtures.loadClass(STATEMENT);
        stmtClass.getField("streamWasRead").set(null, false);

        Object stream = fixtures.loadClass("com.example.jdbc.FakeJdbc")
                .getMethod("trackingStream", byte[].class)
                .invoke(null, (Object) new byte[] {1, 2, 3});

        DejuPayload payload = record(newService(), "bindsSensitiveValues", stream);

        assertFalse((Boolean) stmtClass.getField("streamWasRead").get(null),
                "the agent must never consume a caller's stream, doing so corrupts the write");

        List<CallNode> sql = queries(payload);
        assertEquals(1, sql.size(), "the query itself must still be recorded");
        assertFalse(sql.get(0).getSql().contains("someone@example.com"),
                "a bound value must never appear in the payload");
        assertTrue(sql.get(0).getSql().contains("?"), "placeholders stay as placeholders");
    }

    @Test
    void nestedExecutesAreTimedOnlyAtTheOutermostLevel() {
        long outer = CoverageRuntime.sqlEnter();
        long inner = CoverageRuntime.sqlEnter();
        assertEquals(0L, inner, "an execute nested inside another must not be timed");
        CoverageRuntime.sqlExit("SELECT 1", inner);
        CoverageRuntime.sqlExit("SELECT 1", outer);
        // If depth drifted upwards, every later query on this thread would look nested and
        // go unrecorded for the life of the thread.
        assertEquals(0, CoverageRuntime.sqlDepthForTest(),
                "enter/exit must be balanced or the thread stops recording queries");
    }

    @Test
    void nothingIsRecordedWhileDisarmed() {
        CoverageRuntime.disarm();
        long started = CoverageRuntime.sqlEnter();
        assertEquals(0L, started, "with no session open the advice must do no work");
        CoverageRuntime.sqlExit("SELECT 1", started);
        assertEquals(0, CoverageRuntime.sqlDepthForTest());
    }
}
