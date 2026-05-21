import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import play.db.DB;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import javax.sql.DataSource;

/**
 * Branch coverage for DbSchedulerBootstrapJob.isH2 — the private static
 * helper that drives JDBC-customization pinning at boot. Accessed via
 * reflection since the class is in the jobs package and the method is
 * private.
 */
class DbSchedulerBootstrapJobIsH2Test extends UnitTest {

    private boolean callIsH2(DataSource ds) throws Exception {
        var cls = Class.forName("jobs.DbSchedulerBootstrapJob");
        var m = cls.getDeclaredMethod("isH2", DataSource.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, ds);
    }

    @Test
    void isH2ReturnsTrueForTestDatasource() throws Exception {
        // Test JVM runs against H2 in-memory — the real datasource should
        // return true.
        assertTrue(callIsH2(DB.datasource));
    }

    @Test
    void isH2ReturnsFalseWhenMetadataLookupFails() throws Exception {
        // Conservative-fallback branch: a DataSource whose getConnection
        // throws SQLException must surface as false (NOT throw).
        DataSource throwing = new ThrowingDataSource();
        assertFalse(callIsH2(throwing));
    }

    @Test
    void isH2ReturnsFalseForNonH2ProductName() throws Exception {
        // A datasource whose metadata advertises a non-H2 product name
        // must return false (the lowercase-contains-h2 check fails).
        DataSource pg = new FakeProductNameDataSource("PostgreSQL");
        assertFalse(callIsH2(pg));
    }

    @Test
    void isH2IsCaseInsensitive() throws Exception {
        // Implementation lowercases the product name before checking. Any
        // casing of "H2" must match.
        DataSource shoutingH2 = new FakeProductNameDataSource("H2");
        assertTrue(callIsH2(shoutingH2));
    }

    /** Throws on every getConnection() call. Exercises the SQLException catch. */
    private static class ThrowingDataSource implements DataSource {
        @Override public Connection getConnection() throws SQLException {
            throw new SQLException("simulated failure");
        }
        @Override public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException("simulated failure");
        }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    /** Returns a Connection stub whose metadata advertises a fixed product name. */
    private static class FakeProductNameDataSource implements DataSource {
        private final String productName;
        FakeProductNameDataSource(String name) { this.productName = name; }

        @Override
        public Connection getConnection() throws SQLException {
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if ("getMetaData".equals(method.getName())) {
                            return (DatabaseMetaData) java.lang.reflect.Proxy.newProxyInstance(
                                    getClass().getClassLoader(),
                                    new Class<?>[]{DatabaseMetaData.class},
                                    (p2, m2, a2) -> {
                                        if ("getDatabaseProductName".equals(m2.getName())) return productName;
                                        if ("isWrapperFor".equals(m2.getName())) return false;
                                        // Return zero-ish defaults for any other reflective call.
                                        Class<?> r = m2.getReturnType();
                                        if (r == boolean.class) return false;
                                        if (r == int.class) return 0;
                                        if (r == long.class) return 0L;
                                        return null;
                                    });
                        }
                        if ("close".equals(method.getName())
                                || "isClosed".equals(method.getName())) return false;
                        Class<?> r = method.getReturnType();
                        if (r == boolean.class) return false;
                        if (r == int.class) return 0;
                        return null;
                    });
        }
        @Override public Connection getConnection(String u, String p) throws SQLException { return getConnection(); }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
