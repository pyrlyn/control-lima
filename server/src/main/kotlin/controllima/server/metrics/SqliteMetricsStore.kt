package controllima.server.metrics

import controllima.server.privateDir
import controllima.shared.TimeRange
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.sqlite.SQLiteConfig
import java.io.File
import java.sql.Connection

/**
 * Metrics history in an embedded SQLite file (docs/plan/metrics.md). The only file that knows about
 * Exposed and the SQLite driver; everything else sees [MetricsStore].
 */
class SqliteMetricsStore(
    private val file: File,
    retention: () -> Retention = { Retention() },
    clock: () -> Long = System::currentTimeMillis,
) : TieredMetricsStore(retention, clock) {
    private object Points : Table("points") {
        val target = varchar("target", 512)
        val tier = integer("tier")
        val t = long("t")
        val metric = integer("metric")
        val avg = double("avg")
        val max = double("max")
        val p95 = double("p95").nullable()

        init {
            index(false, target, tier, metric, t)
        }
    }

    private object Gaps : Table("gaps") {
        val target = varchar("target", 512).index()
        val fromMs = long("from_ms")
        val toMs = long("to_ms")
    }

    private val db: Database

    init {
        file.parentFile?.let { privateDir(it) }
        val config = SQLiteConfig().apply {
            // WAL lets chart queries read while the collector writes every 2 s.
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
            setBusyTimeout(5_000)
        }
        val url = "jdbc:sqlite:${file.absolutePath}"
        // SQLite has one writer at a time; serializable matches what it does anyway.
        db = Database.connect({ config.createConnection(url) }, DatabaseConfig { defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE })
        transaction(db) { SchemaUtils.create(Points, Gaps) }
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
    }

    override fun store(target: String, tier: Tier, records: List<MetricRecord>) {
        transaction(db) {
            Points.batchInsert(records, shouldReturnGeneratedValues = false) { r ->
                this[Points.target] = target
                this[Points.tier] = tier.ordinal
                this[Points.t] = r.t
                this[Points.metric] = r.metric
                this[Points.avg] = r.avg
                this[Points.max] = r.max
                this[Points.p95] = r.p95.takeUnless { it.isNaN() }
            }
        }
    }

    override fun load(target: String, tier: Tier, metric: Int, fromMillis: Long, toMillis: Long, onRecord: (MetricRecord) -> Unit) {
        transaction(db) {
            Points.selectAll()
                .where {
                    (Points.target eq target) and (Points.tier eq tier.ordinal) and (Points.metric eq metric) and
                        (Points.t greaterEq fromMillis) and (Points.t lessEq toMillis)
                }
                .orderBy(Points.t)
                .forEach { row -> onRecord(MetricRecord(row[Points.t], metric, row[Points.avg], row[Points.max], row[Points.p95] ?: Double.NaN)) }
        }
    }

    override fun storeGap(target: String, fromMillis: Long, toMillis: Long) {
        transaction(db) {
            Gaps.insert {
                it[Gaps.target] = target
                it[fromMs] = fromMillis
                it[toMs] = toMillis
            }
        }
    }

    override fun loadGaps(target: String, fromMillis: Long, toMillis: Long): List<TimeRange> = transaction(db) {
        Gaps.selectAll()
            .where { (Gaps.target eq target) and (Gaps.toMs greaterEq fromMillis) and (Gaps.fromMs lessEq toMillis) }
            .orderBy(Gaps.fromMs)
            .map { TimeRange(it[Gaps.fromMs], it[Gaps.toMs]) }
    }

    override fun storedTargets(): Set<String> = transaction(db) {
        Points.select(Points.target).withDistinct().map { it[Points.target] }.toSet()
    }

    override fun drop(target: String) {
        transaction(db) {
            Points.deleteWhere { Points.target eq target }
            Gaps.deleteWhere { Gaps.target eq target }
        }
    }

    override fun purge(cutoffs: Map<Tier, Long>): Set<String> = transaction(db) {
        val before = Points.select(Points.target).withDistinct().map { it[Points.target] }.toSet()
        for ((tier, cutoff) in cutoffs) Points.deleteWhere { (Points.tier eq tier.ordinal) and (Points.t less cutoff) }
        val after = Points.select(Points.target).withDistinct().map { it[Points.target] }.toSet()
        before - after
    }

    override fun sizeBytes(): Long = listOf("", "-wal", "-shm").sumOf { File(file.path + it).takeIf(File::isFile)?.length() ?: 0L }

    override fun close() {
        // The driver closes the connection with the JVM; nothing is buffered on our side.
    }
}
