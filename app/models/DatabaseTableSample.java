package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import play.db.jpa.Model;

import java.time.LocalDate;

/**
 * One table's size on one day (JF-496), written by {@code jobs.DatabaseSizeSampleJob} beside that day's
 * {@link DatabaseFileSample}; the database card's growth table compares these across days.
 */
@Entity
@Table(name = "database_table_sample",
        uniqueConstraints = @UniqueConstraint(name = "uq_dts_day_table", columnNames = {"sampled_on", "table_name"}),
        indexes = @Index(name = "ix_dts_day", columnList = "sampled_on"))
public class DatabaseTableSample extends Model {

    /** The day in the app's time zone. */
    @Column(name = "sampled_on", nullable = false)
    public LocalDate sampledOn;

    @Column(name = "table_name", nullable = false, length = 255)
    public String tableName;

    /** H2's {@code DISK_SPACE_USED}: the table's own pages, which leaves out its secondary indexes. */
    @Column(nullable = false)
    public long bytes;

    @Column(name = "row_count", nullable = false)
    public long rowCount;
}
