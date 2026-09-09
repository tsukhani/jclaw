package services.telemetry;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.util.DriverDataSource;
import play.db.Configuration;
import play.db.hikaricp.HikariDataSourceFactory;

import javax.sql.DataSource;

import java.beans.PropertyVetoException;
import java.sql.SQLException;

/**
 * {@code db.factory} (JCLAW-1164): the fork's Hikari pool, drawing its connections from a
 * {@link TelemetryDataSource} so JDBC spans follow {@code otel.enabled} without a restart.
 * The pool itself stays a {@code HikariDataSource}, so pool stats and the status page are
 * unchanged. Needs the fork's PF-174 loader fix (1.13.70), pinned in {@code .play-version}.
 */
public final class OtelHikariDataSourceFactory extends HikariDataSourceFactory {

    @Override
    public DataSource createDataSource(Configuration dbConfig) throws PropertyVetoException, SQLException {
        var pool = (HikariDataSource) super.createDataSource(dbConfig);
        // What Hikari's PoolBase builds itself from the url/driver pair when no datasource is set.
        var raw = new DriverDataSource(pool.getJdbcUrl(), pool.getDriverClassName(),
                pool.getDataSourceProperties(), pool.getUsername(), pool.getPassword());
        TelemetryDataSource.install(pool, raw);
        return pool;
    }
}
