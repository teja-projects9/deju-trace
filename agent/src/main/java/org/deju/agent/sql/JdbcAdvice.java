package org.deju.agent.sql;

import net.bytebuddy.asm.Advice;

import org.deju.agent.runtime.CoverageRuntime;

/**
 * Advice inlined into JDBC driver classes so a query appears in the call tree at the line
 * that triggered it.
 *
 * <p><b>Statement text only.</b> Nothing here reads a bound parameter. Values are captured
 * by intercepting the {@code setX} family, and doing that would consume the caller's
 * {@code InputStream} on {@code setBinaryStream}, silently corrupting the write, quite
 * apart from putting customer data into an exportable HTML file. The prepared SQL, with its
 * {@code ?} placeholders intact, answers "which query ran and what did it cost" without
 * capturing a single value.
 *
 * <p><b>Every method is failure-tolerant.</b> {@code suppress = Throwable.class} means a
 * bug in this advice can never propagate into the traced application's query path.
 */
public final class JdbcAdvice {

    private JdbcAdvice() {
    }

    /** On {@code Connection.prepareStatement(sql, …)}: staple the SQL onto the statement. */
    public static final class Prepare {

        private Prepare() {
        }

        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void exit(@Advice.Argument(0) String sql, @Advice.Return Object statement) {
            if (statement instanceof SqlCarrier) {
                ((SqlCarrier) statement).setDejuSql(sql);
            }
        }
    }

    /** On {@code PreparedStatement.execute*()}: time it and read the stapled SQL back. */
    public static final class ExecutePrepared {

        private ExecutePrepared() {
        }

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static long enter() {
            return CoverageRuntime.sqlEnter();
        }

        // onThrowable so a failing query is still recorded, a query that blew up after
        // 30 seconds is usually the whole reason someone is reading the report.
        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
        public static void exit(@Advice.This Object self, @Advice.Enter long started) {
            String sql = self instanceof SqlCarrier ? ((SqlCarrier) self).getDejuSql() : null;
            CoverageRuntime.sqlExit(sql, started);
        }
    }

    /** On {@code Statement.execute*(sql)}: the SQL is right there in the argument. */
    public static final class ExecuteWithSql {

        private ExecuteWithSql() {
        }

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static long enter() {
            return CoverageRuntime.sqlEnter();
        }

        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
        public static void exit(@Advice.Argument(0) String sql, @Advice.Enter long started) {
            CoverageRuntime.sqlExit(sql, started);
        }
    }
}
