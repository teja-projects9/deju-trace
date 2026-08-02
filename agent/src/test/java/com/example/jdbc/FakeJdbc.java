package com.example.jdbc;

import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * A minimal JDBC driver, enough to prove the transformer matches real driver shapes without
 * needing a database.
 *
 * <p>Everything unimplemented is routed through a dynamic proxy so these classes stay short:
 * only the handful of methods the advice actually touches is written out.
 */
public final class FakeJdbc {

    private FakeJdbc() {
    }

    /** Stands in for a driver's own connection, e.g. {@code org.postgresql.jdbc.PgConnection}. */
    public static class FakeConnection implements Connection {

        public PreparedStatement prepareStatement(String sql) {
            return new FakePreparedStatement(sql);
        }

        public Statement createStatement() {
            return new FakeStatement();
        }

        @Override
        public boolean equals(Object o) {
            return this == o;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }

        // Everything else is unused by the advice.
        private final Connection unused = unsupported(Connection.class);

        @Override public java.sql.CallableStatement prepareCall(String sql) throws SQLException { return unused.prepareCall(sql); }
        @Override public String nativeSQL(String sql) throws SQLException { return unused.nativeSQL(sql); }
        @Override public void setAutoCommit(boolean b) throws SQLException { unused.setAutoCommit(b); }
        @Override public boolean getAutoCommit() throws SQLException { return unused.getAutoCommit(); }
        @Override public void commit() throws SQLException { unused.commit(); }
        @Override public void rollback() throws SQLException { unused.rollback(); }
        @Override public void close() throws SQLException { }
        @Override public boolean isClosed() { return false; }
        @Override public java.sql.DatabaseMetaData getMetaData() throws SQLException { return unused.getMetaData(); }
        @Override public void setReadOnly(boolean b) throws SQLException { unused.setReadOnly(b); }
        @Override public boolean isReadOnly() throws SQLException { return unused.isReadOnly(); }
        @Override public void setCatalog(String c) throws SQLException { unused.setCatalog(c); }
        @Override public String getCatalog() throws SQLException { return unused.getCatalog(); }
        @Override public void setTransactionIsolation(int l) throws SQLException { unused.setTransactionIsolation(l); }
        @Override public int getTransactionIsolation() throws SQLException { return unused.getTransactionIsolation(); }
        @Override public java.sql.SQLWarning getWarnings() throws SQLException { return unused.getWarnings(); }
        @Override public void clearWarnings() throws SQLException { unused.clearWarnings(); }
        @Override public Statement createStatement(int a, int b) throws SQLException { return unused.createStatement(a, b); }
        @Override public PreparedStatement prepareStatement(String s, int a, int b) throws SQLException { return prepareStatement(s); }
        @Override public java.sql.CallableStatement prepareCall(String s, int a, int b) throws SQLException { return unused.prepareCall(s, a, b); }
        @Override public java.util.Map<String, Class<?>> getTypeMap() throws SQLException { return unused.getTypeMap(); }
        @Override public void setTypeMap(java.util.Map<String, Class<?>> m) throws SQLException { unused.setTypeMap(m); }
        @Override public void setHoldability(int h) throws SQLException { unused.setHoldability(h); }
        @Override public int getHoldability() throws SQLException { return unused.getHoldability(); }
        @Override public java.sql.Savepoint setSavepoint() throws SQLException { return unused.setSavepoint(); }
        @Override public java.sql.Savepoint setSavepoint(String n) throws SQLException { return unused.setSavepoint(n); }
        @Override public void rollback(java.sql.Savepoint s) throws SQLException { unused.rollback(s); }
        @Override public void releaseSavepoint(java.sql.Savepoint s) throws SQLException { unused.releaseSavepoint(s); }
        @Override public Statement createStatement(int a, int b, int c) throws SQLException { return unused.createStatement(a, b, c); }
        @Override public PreparedStatement prepareStatement(String s, int a, int b, int c) throws SQLException { return prepareStatement(s); }
        @Override public java.sql.CallableStatement prepareCall(String s, int a, int b, int c) throws SQLException { return unused.prepareCall(s, a, b, c); }
        @Override public PreparedStatement prepareStatement(String s, int autoKeys) throws SQLException { return prepareStatement(s); }
        @Override public PreparedStatement prepareStatement(String s, int[] idx) throws SQLException { return prepareStatement(s); }
        @Override public PreparedStatement prepareStatement(String s, String[] names) throws SQLException { return prepareStatement(s); }
        @Override public java.sql.Clob createClob() throws SQLException { return unused.createClob(); }
        @Override public java.sql.Blob createBlob() throws SQLException { return unused.createBlob(); }
        @Override public java.sql.NClob createNClob() throws SQLException { return unused.createNClob(); }
        @Override public java.sql.SQLXML createSQLXML() throws SQLException { return unused.createSQLXML(); }
        @Override public boolean isValid(int t) { return true; }
        @Override public void setClientInfo(String k, String v) { }
        @Override public void setClientInfo(java.util.Properties p) { }
        @Override public String getClientInfo(String k) throws SQLException { return unused.getClientInfo(k); }
        @Override public java.util.Properties getClientInfo() throws SQLException { return unused.getClientInfo(); }
        @Override public java.sql.Array createArrayOf(String t, Object[] e) throws SQLException { return unused.createArrayOf(t, e); }
        @Override public java.sql.Struct createStruct(String t, Object[] a) throws SQLException { return unused.createStruct(t, a); }
        @Override public void setSchema(String s) throws SQLException { unused.setSchema(s); }
        @Override public String getSchema() throws SQLException { return unused.getSchema(); }
        @Override public void abort(java.util.concurrent.Executor e) throws SQLException { unused.abort(e); }
        @Override public void setNetworkTimeout(java.util.concurrent.Executor e, int m) throws SQLException { unused.setNetworkTimeout(e, m); }
        @Override public int getNetworkTimeout() throws SQLException { return unused.getNetworkTimeout(); }
        @Override public <T> T unwrap(Class<T> i) throws SQLException { return unused.unwrap(i); }
        @Override public boolean isWrapperFor(Class<?> i) { return false; }
    }

    /** Stands in for {@code org.postgresql.jdbc.PgPreparedStatement}. */
    public static class FakePreparedStatement extends FakeStatement implements PreparedStatement {

        /** Records what was bound, purely so the test can prove we never read it back. */
        public final java.util.List<Object> bound = new java.util.ArrayList<>();
        /** Set if anything ever consumed the stream passed to setBinaryStream. */
        public static volatile boolean streamWasRead;

        private final String sql;

        FakePreparedStatement(String sql) {
            this.sql = sql;
        }

        public String sql() {
            return sql;
        }

        @Override
        public java.sql.ResultSet executeQuery() {
            work();
            return null;
        }

        @Override
        public int executeUpdate() {
            work();
            return 1;
        }

        @Override
        public boolean execute() {
            work();
            return true;
        }

        @Override public void setString(int i, String v) { bound.add(v); }
        @Override public void setInt(int i, int v) { bound.add(v); }
        @Override public void setLong(int i, long v) { bound.add(v); }
        @Override public void setObject(int i, Object v) { bound.add(v); }

        @Override
        public void setBinaryStream(int i, InputStream v) {
            // Not read here. If the agent ever read it to capture the value, the driver
            // would find it exhausted, this is the corruption the design has to avoid.
            bound.add(v);
        }

        private final PreparedStatement unusedPs = unsupported(PreparedStatement.class);

        @Override public void setNull(int i, int t) { bound.add(null); }
        @Override public void setBoolean(int i, boolean v) { bound.add(v); }
        @Override public void setByte(int i, byte v) { bound.add(v); }
        @Override public void setShort(int i, short v) { bound.add(v); }
        @Override public void setFloat(int i, float v) { bound.add(v); }
        @Override public void setDouble(int i, double v) { bound.add(v); }
        @Override public void setBigDecimal(int i, java.math.BigDecimal v) { bound.add(v); }
        @Override public void setBytes(int i, byte[] v) { bound.add(v); }
        @Override public void setDate(int i, java.sql.Date v) { bound.add(v); }
        @Override public void setTime(int i, java.sql.Time v) { bound.add(v); }
        @Override public void setTimestamp(int i, java.sql.Timestamp v) { bound.add(v); }
        @Override public void setAsciiStream(int i, InputStream v, int l) { bound.add(v); }
        @Override @Deprecated public void setUnicodeStream(int i, InputStream v, int l) { bound.add(v); }
        @Override public void setBinaryStream(int i, InputStream v, int l) { bound.add(v); }
        @Override public void clearParameters() { bound.clear(); }
        @Override public void setObject(int i, Object v, int t) { bound.add(v); }
        @Override public void addBatch() { }
        @Override public void setCharacterStream(int i, java.io.Reader v, int l) { bound.add(v); }
        @Override public void setRef(int i, java.sql.Ref v) { bound.add(v); }
        @Override public void setBlob(int i, java.sql.Blob v) { bound.add(v); }
        @Override public void setClob(int i, java.sql.Clob v) { bound.add(v); }
        @Override public void setArray(int i, java.sql.Array v) { bound.add(v); }
        @Override public java.sql.ResultSetMetaData getMetaData() throws SQLException { return unusedPs.getMetaData(); }
        @Override public void setDate(int i, java.sql.Date v, java.util.Calendar c) { bound.add(v); }
        @Override public void setTime(int i, java.sql.Time v, java.util.Calendar c) { bound.add(v); }
        @Override public void setTimestamp(int i, java.sql.Timestamp v, java.util.Calendar c) { bound.add(v); }
        @Override public void setNull(int i, int t, String n) { bound.add(null); }
        @Override public void setURL(int i, java.net.URL v) { bound.add(v); }
        @Override public java.sql.ParameterMetaData getParameterMetaData() throws SQLException { return unusedPs.getParameterMetaData(); }
        @Override public void setRowId(int i, java.sql.RowId v) { bound.add(v); }
        @Override public void setNString(int i, String v) { bound.add(v); }
        @Override public void setNCharacterStream(int i, java.io.Reader v, long l) { bound.add(v); }
        @Override public void setNClob(int i, java.sql.NClob v) { bound.add(v); }
        @Override public void setClob(int i, java.io.Reader v, long l) { bound.add(v); }
        @Override public void setBlob(int i, InputStream v, long l) { bound.add(v); }
        @Override public void setNClob(int i, java.io.Reader v, long l) { bound.add(v); }
        @Override public void setSQLXML(int i, java.sql.SQLXML v) { bound.add(v); }
        @Override public void setObject(int i, Object v, int t, int s) { bound.add(v); }
        @Override public void setAsciiStream(int i, InputStream v, long l) { bound.add(v); }
        @Override public void setBinaryStream(int i, InputStream v, long l) { bound.add(v); }
        @Override public void setCharacterStream(int i, java.io.Reader v, long l) { bound.add(v); }
        @Override public void setAsciiStream(int i, InputStream v) { bound.add(v); }
        @Override public void setCharacterStream(int i, java.io.Reader v) { bound.add(v); }
        @Override public void setNCharacterStream(int i, java.io.Reader v) { bound.add(v); }
        @Override public void setClob(int i, java.io.Reader v) { bound.add(v); }
        @Override public void setBlob(int i, InputStream v) { bound.add(v); }
        @Override public void setNClob(int i, java.io.Reader v) { bound.add(v); }
    }

    /** Stands in for a plain {@code Statement}, where the SQL is passed to execute. */
    public static class FakeStatement implements Statement {

        static void work() {
            // Just enough elapsed time that the recorded duration is non-zero.
            long until = System.nanoTime() + 1_500_000L;
            while (System.nanoTime() < until) {
                Thread.onSpinWait();
            }
        }

        @Override
        public java.sql.ResultSet executeQuery(String sql) {
            work();
            return null;
        }

        @Override
        public int executeUpdate(String sql) {
            work();
            return 1;
        }

        @Override
        public boolean execute(String sql) {
            work();
            return true;
        }

        private final Statement unused = unsupported(Statement.class);

        @Override public void close() { }
        @Override public int getMaxFieldSize() throws SQLException { return unused.getMaxFieldSize(); }
        @Override public void setMaxFieldSize(int m) { }
        @Override public int getMaxRows() throws SQLException { return unused.getMaxRows(); }
        @Override public void setMaxRows(int m) { }
        @Override public void setEscapeProcessing(boolean b) { }
        @Override public int getQueryTimeout() throws SQLException { return unused.getQueryTimeout(); }
        @Override public void setQueryTimeout(int s) { }
        @Override public void cancel() { }
        @Override public java.sql.SQLWarning getWarnings() { return null; }
        @Override public void clearWarnings() { }
        @Override public void setCursorName(String n) { }
        @Override public java.sql.ResultSet getResultSet() { return null; }
        @Override public int getUpdateCount() { return -1; }
        @Override public boolean getMoreResults() { return false; }
        @Override public void setFetchDirection(int d) { }
        @Override public int getFetchDirection() { return 0; }
        @Override public void setFetchSize(int r) { }
        @Override public int getFetchSize() { return 0; }
        @Override public int getResultSetConcurrency() { return 0; }
        @Override public int getResultSetType() { return 0; }
        @Override public void addBatch(String sql) { }
        @Override public void clearBatch() { }
        @Override public int[] executeBatch() { work(); return new int[0]; }
        @Override public Connection getConnection() throws SQLException { return unused.getConnection(); }
        @Override public boolean getMoreResults(int c) { return false; }
        @Override public java.sql.ResultSet getGeneratedKeys() { return null; }
        @Override public int executeUpdate(String sql, int k) { work(); return 1; }
        @Override public int executeUpdate(String sql, int[] k) { work(); return 1; }
        @Override public int executeUpdate(String sql, String[] k) { work(); return 1; }
        @Override public boolean execute(String sql, int k) { work(); return true; }
        @Override public boolean execute(String sql, int[] k) { work(); return true; }
        @Override public boolean execute(String sql, String[] k) { work(); return true; }
        @Override public int getResultSetHoldability() { return 0; }
        @Override public boolean isClosed() { return false; }
        @Override public void setPoolable(boolean p) { }
        @Override public boolean isPoolable() { return false; }
        @Override public void closeOnCompletion() { }
        @Override public boolean isCloseOnCompletion() { return false; }
        @Override public <T> T unwrap(Class<T> i) throws SQLException { return unused.unwrap(i); }
        @Override public boolean isWrapperFor(Class<?> i) { return false; }
    }

    /**
     * Stands in for a pool proxy such as {@code HikariProxyPreparedStatement}: it implements
     * the same interface and delegates, so a single query passes through two instrumented
     * execute methods. Proves the de-duplication in CoverageRuntime.sqlEnter().
     */
    public static class PoolProxyPreparedStatement extends FakePreparedStatement {

        private final PreparedStatement delegate;

        public PoolProxyPreparedStatement(PreparedStatement delegate, String sql) {
            super(sql);
            this.delegate = delegate;
        }

        @Override
        public boolean execute() {
            try {
                return delegate.execute();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * The HikariCP shape: the pool hands out a proxy CONNECTION, whose
     * {@code prepareStatement} returns a proxy STATEMENT wrapping the driver's own.
     *
     * <p>Both connections are instrumented, so both statements get their SQL stapled, and
     * both {@code execute} methods are advised, which is exactly the double-count the
     * de-duplication has to absorb.
     */
    public static class PoolConnection extends FakeConnection {

        private final FakeConnection delegate = new FakeConnection();

        @Override
        public PreparedStatement prepareStatement(String sql) {
            return new PoolProxyPreparedStatement(delegate.prepareStatement(sql), sql);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T unsupported(Class<T> iface) {
        InvocationHandler h = (proxy, method, args) -> {
            throw new UnsupportedOperationException(method.getName() + " is not needed by these tests");
        };
        return (T) Proxy.newProxyInstance(FakeJdbc.class.getClassLoader(), new Class<?>[] {iface}, h);
    }

    /** Used by the "we never consume a stream" assertion. */
    public static InputStream trackingStream(byte[] data) {
        return new java.io.ByteArrayInputStream(data) {
            @Override
            public int read() {
                FakePreparedStatement.streamWasRead = true;
                return super.read();
            }

            @Override
            public int read(byte[] b, int off, int len) {
                FakePreparedStatement.streamWasRead = true;
                return super.read(b, off, len);
            }
        };
    }
}
