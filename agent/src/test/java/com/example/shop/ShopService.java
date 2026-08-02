package com.example.shop;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;

import com.example.jdbc.FakeJdbc;

/**
 * Stands in for the traced application: an ordinary service that runs queries.
 *
 * <p>Lives under {@code com.example} rather than beside the test, because the agent refuses
 * to instrument anything under {@code org.deju.agent.}, it must never instrument itself,
 * so a fixture in that namespace would silently record nothing.
 */
public final class ShopService {

    private final Connection connection;

    public ShopService(Connection connection) {
        this.connection = connection;
    }

    public int loadProducts() throws Exception {
        PreparedStatement ps = connection.prepareStatement(
                "SELECT p.id, p.name\n  FROM products p\n WHERE p.category_id = ?");
        ps.setInt(1, 42);
        ps.execute();
        return 1;
    }

    public int plainStatement() throws Exception {
        Statement st = connection.createStatement();
        st.execute("DELETE FROM cart WHERE session_id = 'abc'");
        return 1;
    }

    /**
     * Same query, reached through a pool proxy that delegates to the driver's statement,
     * the HikariCP shape, where {@code prepareStatement} hands back the wrapper and both
     * its {@code execute} and the driver's are instrumented.
     */
    public int viaPool() throws Exception {
        PreparedStatement pooled =
                new FakeJdbc.PoolConnection().prepareStatement("SELECT 1 FROM dual");
        pooled.execute();
        return 1;
    }

    /** Binds a value and a stream, to prove neither is ever read by the agent. */
    public int bindsSensitiveValues(java.io.InputStream stream) throws Exception {
        PreparedStatement ps = connection.prepareStatement("SELECT * FROM users WHERE email = ?");
        ps.setString(1, "someone@example.com");
        ps.setBinaryStream(2, stream);
        ps.execute();
        return 1;
    }
}
