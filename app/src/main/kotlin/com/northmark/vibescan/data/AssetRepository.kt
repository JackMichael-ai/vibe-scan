package com.northmark.vibescan.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.northmark.vibescan.engine.Diagnosis

/**
 * AssetRepository — lightweight SQLite persistence with offline-first outbox.
 */
class AssetRepository(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE assets (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                name        TEXT    NOT NULL,
                type        TEXT    NOT NULL,
                location    TEXT,
                shaft_rpm   REAL    DEFAULT 1500,
                created_at  INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE readings (
                id                INTEGER PRIMARY KEY AUTOINCREMENT,
                asset_id          INTEGER NOT NULL,
                health            INTEGER NOT NULL,
                fault_code        INTEGER NOT NULL,
                rms               REAL,
                rms_ms2           REAL    DEFAULT 0,
                kurtosis          REAL,
                crest             REAL,
                dominant_hz       REAL,
                signal_confidence REAL    DEFAULT 0,
                iso_zone          TEXT    DEFAULT 'A',
                bpfo_energy       REAL    DEFAULT 0,
                bpfi_energy       REAL    DEFAULT 0,
                ai_reliability    INTEGER DEFAULT 100,
                ambient_temp      REAL,
                timestamp         INTEGER NOT NULL,
                FOREIGN KEY (asset_id) REFERENCES assets(id)
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE alerts (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                asset_id    INTEGER NOT NULL,
                severity    TEXT    NOT NULL,
                fault_label TEXT,
                action      TEXT,
                resolved    INTEGER DEFAULT 0,
                timestamp   INTEGER NOT NULL,
                FOREIGN KEY (asset_id) REFERENCES assets(id)
            )
        """.trimIndent())

        // V3 Sync Outboxes
        db.execSQL("""
            CREATE TABLE readings_outbox (
                id                INTEGER PRIMARY KEY AUTOINCREMENT,
                asset_id          INTEGER NOT NULL,
                health            INTEGER NOT NULL,
                fault_code        INTEGER NOT NULL,
                rms               REAL,
                rms_ms2           REAL    DEFAULT 0,
                kurtosis          REAL,
                crest             REAL,
                dominant_hz       REAL,
                signal_confidence REAL    DEFAULT 0,
                iso_zone          TEXT    DEFAULT 'A',
                bpfo_energy       REAL    DEFAULT 0,
                bpfi_energy       REAL    DEFAULT 0,
                ai_reliability    INTEGER DEFAULT 100,
                mount_grade       TEXT    DEFAULT 'unknown',
                timestamp         INTEGER NOT NULL,
                synced            INTEGER DEFAULT 0,
                retry_count       INTEGER DEFAULT 0
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE alerts_outbox (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                asset_id    INTEGER NOT NULL,
                severity    TEXT    NOT NULL,
                fault_label TEXT,
                action      TEXT,
                timestamp   INTEGER NOT NULL,
                synced      INTEGER DEFAULT 0
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX idx_readings_asset ON readings(asset_id, timestamp DESC)")
        db.execSQL("CREATE INDEX idx_outbox_unsynced ON readings_outbox(synced, timestamp ASC)")
        db.execSQL("CREATE INDEX idx_alerts_unsynced ON alerts_outbox(synced, timestamp ASC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS readings_outbox (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    asset_id    INTEGER NOT NULL,
                    health      INTEGER NOT NULL,
                    fault_code  INTEGER NOT NULL,
                    rms         REAL,
                    kurtosis    REAL,
                    crest       REAL,
                    dominant_hz REAL,
                    jitter_ms   REAL    DEFAULT 0,
                    mount_grade TEXT    DEFAULT 'unknown',
                    timestamp   INTEGER NOT NULL,
                    synced      INTEGER DEFAULT 0,
                    retry_count INTEGER DEFAULT 0
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS alerts_outbox (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    asset_id    INTEGER NOT NULL,
                    severity    TEXT    NOT NULL,
                    fault_label TEXT,
                    action      TEXT,
                    timestamp   INTEGER NOT NULL,
                    synced      INTEGER DEFAULT 0
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_outbox_unsynced ON readings_outbox(synced, timestamp ASC)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_alerts_unsynced ON alerts_outbox(synced, timestamp ASC)")
        }

        if (oldVersion < 3) {
            // Upgrade readings table
            db.execSQL("ALTER TABLE readings ADD COLUMN rms_ms2 REAL DEFAULT 0")
            db.execSQL("ALTER TABLE readings ADD COLUMN signal_confidence REAL DEFAULT 0")
            db.execSQL("ALTER TABLE readings ADD COLUMN iso_zone TEXT DEFAULT 'A'")
            db.execSQL("ALTER TABLE readings ADD COLUMN bpfo_energy REAL DEFAULT 0")
            db.execSQL("ALTER TABLE readings ADD COLUMN bpfi_energy REAL DEFAULT 0")

            db.execSQL("ALTER TABLE readings_outbox ADD COLUMN bpfo_energy REAL DEFAULT 0")
            db.execSQL("ALTER TABLE readings_outbox ADD COLUMN bpfi_energy REAL DEFAULT 0")
        }

        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE readings ADD COLUMN ai_reliability INTEGER DEFAULT 100")
            db.execSQL("ALTER TABLE readings_outbox ADD COLUMN ai_reliability INTEGER DEFAULT 100")
            // Convert existing jitter_ms to ai_reliability as a heuristic if present
            db.execSQL("UPDATE readings_outbox SET ai_reliability = 80 WHERE jitter_ms < 2 AND ai_reliability = 100")
        }
    }

    fun insertAsset(name: String, type: String, location: String, rpm: Float): Long {
        val cv = ContentValues().apply {
            put("name",       name)
            put("type",       type)
            put("location",   location)
            put("shaft_rpm",  rpm)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("assets", null, cv)
    }

    fun addAsset(name: String, type: String, rpm: Float): Long {
        return insertAsset(name, type, "", rpm)
    }

    fun getAllAssets(): List<Asset> {
        val list = mutableListOf<Asset>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, name, type, location, shaft_rpm, created_at FROM assets ORDER BY name", null)
        cursor.use {
            while (it.moveToNext()) {
                list += Asset(
                    id        = it.getLong(0),
                    name      = it.getString(1),
                    type      = it.getString(2),
                    location  = it.getString(3) ?: "",
                    shaftRpm  = it.getFloat(4),
                    createdAt = it.getLong(5)
                )
            }
        }
        return list
    }

    fun getAssetById(id: Long): Asset? {
        val cursor = readableDatabase.rawQuery(
            "SELECT id, name, type, location, shaft_rpm, created_at FROM assets WHERE id = ?",
            arrayOf(id.toString())
        )
        return cursor.use {
            if (it.moveToFirst()) {
                Asset(
                    id        = it.getLong(0),
                    name      = it.getString(1),
                    type      = it.getString(2),
                    location  = it.getString(3) ?: "",
                    shaftRpm  = it.getFloat(4),
                    createdAt = it.getLong(5)
                )
            } else null
        }
    }

    fun deleteAsset(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("assets",          "id = ?",       arrayOf(id.toString()))
            db.delete("readings",        "asset_id = ?", arrayOf(id.toString()))
            db.delete("alerts",          "asset_id = ?", arrayOf(id.toString()))
            db.delete("readings_outbox", "asset_id = ?", arrayOf(id.toString()))
            db.delete("alerts_outbox",   "asset_id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun insertReading(assetId: Long, d: Diagnosis) {
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Local history
            val cv = ContentValues().apply {
                put("asset_id",          assetId)
                put("health",            d.health)
                put("fault_code",        d.faultCode)
                put("rms",               d.rmsMms)
                put("rms_ms2",           d.rmsMs2)
                put("kurtosis",          d.kurtosis)
                put("crest",             d.crest)
                put("dominant_hz",       d.dominantHz)
                put("signal_confidence", d.signalConfidence)
                put("iso_zone",          d.isoZone.toString())
                put("bpfo_energy",       d.bpfoEnergy)
                put("bpfi_energy",       d.bpfiEnergy)
                put("ai_reliability",    d.signalConfidence.toInt())
                put("ambient_temp",      d.ambientTemp)
                put("timestamp",         now)
            }
            db.insert("readings", null, cv)

            // Cloud outbox
            val ocv = ContentValues().apply {
                put("asset_id",          assetId)
                put("health",            d.health)
                put("fault_code",        d.faultCode)
                put("rms",               d.rmsMms)
                put("rms_ms2",           d.rmsMs2)
                put("kurtosis",          d.kurtosis)
                put("crest",             d.crest)
                put("dominant_hz",       d.dominantHz)
                put("signal_confidence", d.signalConfidence)
                put("iso_zone",          d.isoZone.toString())
                put("bpfo_energy",       d.bpfoEnergy)
                put("bpfi_energy",       d.bpfiEnergy)
                put("ai_reliability",    d.signalConfidence.toInt())
                put("mount_grade",       "unknown")
                put("timestamp",         now)
                put("synced",            0)
            }
            db.insert("readings_outbox", null, ocv)

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        pruneOldReadings(assetId)
    }

    private fun pruneOldReadings(assetId: Long) {
        val now = System.currentTimeMillis()
        val cutoff = now - 7L * 24 * 60 * 60 * 1000
        writableDatabase.delete("readings",
            "asset_id = ? AND timestamp < ?",
            arrayOf(assetId.toString(), cutoff.toString()))

        // Prune synced outbox after 24h
        val syncPruneTs = now - 24L * 60 * 60 * 1000
        writableDatabase.delete("readings_outbox",
            "synced = 1 AND timestamp < ?",
            arrayOf(syncPruneTs.toString()))
    }

    fun getOutboxCount(): Int {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM readings_outbox WHERE synced = 0", null)
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun getOutboxReadings(limit: Int): List<OutboxReading> {
        val list = mutableListOf<OutboxReading>()
        val cursor = readableDatabase.rawQuery("""
            SELECT id, asset_id, health, fault_code, rms, kurtosis, crest, dominant_hz, timestamp, ai_reliability, mount_grade,
                   rms_ms2, signal_confidence, iso_zone, bpfo_energy, bpfi_energy
            FROM readings_outbox
            WHERE synced = 0
            ORDER BY timestamp ASC
            LIMIT ?
        """.trimIndent(), arrayOf(limit.toString()))
        cursor.use {
            while (it.moveToNext()) {
                list += OutboxReading(
                    outboxId         = it.getLong(0),
                    assetId          = it.getLong(1),
                    health           = it.getInt(2),
                    faultCode        = it.getInt(3),
                    rms              = it.getFloat(4),
                    kurtosis         = it.getFloat(5),
                    crest            = it.getFloat(6),
                    dominantHz       = it.getFloat(7),
                    timestamp        = it.getLong(8),
                    aiReliability    = it.getInt(9),
                    mountGrade       = it.getString(10),
                    rmsMs2           = it.getFloat(11),
                    signalConfidence = it.getFloat(12),
                    isoZone          = it.getString(13),
                    bpfoEnergy       = it.getFloat(14),
                    bpfiEnergy       = it.getFloat(15)
                )
            }
        }
        return list
    }

    fun markReadingsSynced(ids: List<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues().apply { put("synced", 1) }
            ids.forEach { id ->
                db.update("readings_outbox", cv, "id = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getOutboxAlerts(): List<OutboxAlert> {
        val list = mutableListOf<OutboxAlert>()
        val cursor = readableDatabase.rawQuery("""
            SELECT id, asset_id, severity, fault_label, action, timestamp
            FROM alerts_outbox
            WHERE synced = 0
            ORDER BY timestamp ASC
        """.trimIndent(), null)
        cursor.use {
            while (it.moveToNext()) {
                list += OutboxAlert(
                    outboxId   = it.getLong(0),
                    assetId    = it.getLong(1),
                    severity   = it.getString(2),
                    faultLabel = it.getString(3),
                    action     = it.getString(4),
                    timestamp  = it.getLong(5)
                )
            }
        }
        return list
    }

    fun markAlertsSynced(ids: List<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues().apply { put("synced", 1) }
            ids.forEach { id ->
                db.update("alerts_outbox", cv, "id = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun insertAlert(assetId: Long, d: Diagnosis) {
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Local record
            val cv = ContentValues().apply {
                put("asset_id",    assetId)
                put("severity",    d.severity.name)
                put("fault_label", d.faultLabel)
                put("action",      d.actionLabel)
                put("timestamp",   now)
            }
            db.insert("alerts", null, cv)

            // Outbox
            val ocv = ContentValues().apply {
                put("asset_id",    assetId)
                put("severity",    d.severity.name)
                put("fault_label", d.faultLabel)
                put("action",      d.actionLabel)
                put("timestamp",   now)
                put("synced",      0)
            }
            db.insert("alerts_outbox", null, ocv)

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getRecentReadings(assetId: Long, limit: Int = 100): List<Reading> {
        val list = mutableListOf<Reading>()
        val cursor = readableDatabase.rawQuery("""
            SELECT health, rms, fault_code, timestamp,
                   kurtosis, crest, dominant_hz, rms_ms2,
                   signal_confidence, iso_zone, bpfo_energy, bpfi_energy,
                   ai_reliability
            FROM readings
            WHERE asset_id = ?
            ORDER BY timestamp DESC
            LIMIT ?
        """.trimIndent(), arrayOf(assetId.toString(), limit.toString()))
        cursor.use {
            while (it.moveToNext()) {
                list += Reading(
                    health           = it.getInt(0),
                    rms              = it.getFloat(1),
                    faultCode        = it.getInt(2),
                    timestamp        = it.getLong(3),
                    kurtosis         = it.getFloat(4),
                    crest            = it.getFloat(5),
                    dominantHz       = it.getFloat(6),
                    rmsMs2           = it.getFloat(7),
                    signalConfidence = it.getFloat(8),
                    isoZone          = it.getString(9),
                    bpfoEnergy       = it.getFloat(10),
                    bpfiEnergy       = it.getFloat(11),
                    aiReliability    = it.getInt(12)
                )
            }
        }
        return list.reversed()
    }

    fun getUnresolvedAlerts(): List<AlertRecord> {
        val list = mutableListOf<AlertRecord>()
        val cursor = readableDatabase.rawQuery("""
            SELECT al.id, a.name, al.severity, al.fault_label, al.action, al.timestamp
            FROM alerts al
            JOIN assets a ON a.id = al.asset_id
            WHERE al.resolved = 0
            ORDER BY al.timestamp DESC
        """.trimIndent(), null)
        cursor.use {
            while (it.moveToNext()) {
                list += AlertRecord(
                    id         = it.getLong(0),
                    assetName  = it.getString(1),
                    severity   = it.getString(2),
                    faultLabel = it.getString(3),
                    action     = it.getString(4),
                    timestamp  = it.getLong(5)
                )
            }
        }
        return list
    }

    fun resolveAlert(id: Long) {
        val cv = ContentValues().apply { put("resolved", 1) }
        writableDatabase.update("alerts", cv, "id = ?", arrayOf(id.toString()))
    }

    companion object {
        private const val DB_NAME    = "vibescan.db"
        private const val DB_VERSION = 4
    }
}

data class Asset(
    val id:        Long,
    val name:      String,
    val type:      String,
    val location:  String,
    val shaftRpm:  Float,
    val createdAt: Long
)

data class Reading(
    val health:           Int,
    val rms:              Float,
    val faultCode:        Int,
    val timestamp:        Long,
    val kurtosis:         Float,
    val crest:            Float,
    val dominantHz:       Float,
    val rmsMs2:           Float,
    val signalConfidence: Float,
    val isoZone:          String,
    val bpfoEnergy:       Float,
    val bpfiEnergy:       Float,
    val aiReliability:    Int
)

data class AlertRecord(
    val id:         Long,
    val assetName:  String,
    val severity:   String,
    val faultLabel: String,
    val action:     String,
    val timestamp:  Long
)

data class OutboxReading(
    val outboxId:         Long,
    val assetId:          Long,
    val health:           Int,
    val faultCode:        Int,
    val rms:              Float,
    val kurtosis:         Float,
    val crest:            Float,
    val dominantHz:       Float,
    val timestamp:        Long,
    val aiReliability:    Int,
    val mountGrade:       String,
    val rmsMs2:           Float,
    val signalConfidence: Float,
    val isoZone:          String,
    val bpfoEnergy:       Float,
    val bpfiEnergy:       Float,
    val ambientTemp:      Float? = null
)

data class OutboxAlert(
    val outboxId:   Long,
    val assetId:    Long,
    val severity:   String,
    val faultLabel: String,
    val action:     String,
    val timestamp:  Long
)
