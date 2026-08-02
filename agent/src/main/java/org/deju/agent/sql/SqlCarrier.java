package org.deju.agent.sql;

/**
 * Implemented on the fly by every instrumented {@code PreparedStatement}, to carry the SQL
 * text from {@code Connection.prepareStatement(sql)} through to {@code execute()}.
 *
 * <p>JDBC has no way to ask a prepared statement what SQL it holds, and by execute time the
 * string argument is long gone. The alternative, a map from statement to SQL, is a trap:
 * a {@link java.util.WeakHashMap} keys on {@code equals}, which a driver is free to
 * override, and an identity map would pin statements in memory. Adding a field to the
 * statement class itself has neither problem, and costs one field read at execute time.
 *
 * <p>This interface is loaded by the agent jar on the system class path, so driver classes
 * in any application class loader can see it.
 */
public interface SqlCarrier {

    String getDejuSql();

    void setDejuSql(String sql);
}
