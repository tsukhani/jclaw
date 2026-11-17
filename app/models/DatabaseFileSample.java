package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.jspecify.annotations.Nullable;
import play.db.jpa.Model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * The data file's size on one day (JF-496). Its row is what marks the day as sampled, so it is written with that
 * day's {@link DatabaseTableSample} rows in one transaction.
 */
@Entity
@Table(name = "database_file_sample",
        uniqueConstraints = @UniqueConstraint(name = "uq_dfs_day", columnNames = "sampled_on"))
public class DatabaseFileSample extends Model {

    /** The day in the app's time zone. */
    @Column(name = "sampled_on", nullable = false)
    public LocalDate sampledOn;

    @Column(name = "taken_at", nullable = false)
    public Instant takenAt;

    /** H2's figures for the file, null when the database reported none. */
    @Column(name = "file_bytes")
    public @Nullable Long fileBytes;

    @Column(name = "live_bytes")
    public @Nullable Long liveBytes;

    @Column(name = "reclaimable_bytes")
    public @Nullable Long reclaimableBytes;
}
