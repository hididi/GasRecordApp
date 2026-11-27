package com.hididi.gaslog.data.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class GasDatabaseSnapshot(
    val readings: List<MeterReadingEntity>,
    val tiers: List<TariffTierEntity>,
    val payments: List<PaymentEntity>,
)

/** Small platform SQLite store; JSON is retained only for backup import/export and migration. */
class GasDatabase(context: Context) : SQLiteOpenHelper(context.applicationContext, "gaslog.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE readings (
            id INTEGER PRIMARY KEY, epochMillis INTEGER NOT NULL, readingMilli INTEGER NOT NULL,
            photoPath TEXT, latitude REAL, longitude REAL, locationName TEXT, tempMinC REAL, tempMaxC REAL,
            weatherStatus TEXT NOT NULL, source TEXT NOT NULL, ocrRaw TEXT, note TEXT, createdAt INTEGER NOT NULL
        )""".trimIndent())
        db.execSQL("CREATE TABLE tiers (tierNo INTEGER PRIMARY KEY, capM3 REAL, priceCentsPerM3 INTEGER NOT NULL)")
        db.execSQL("""CREATE TABLE payments (
            id INTEGER PRIMARY KEY, paidAtMillis INTEGER NOT NULL, readingMilli INTEGER NOT NULL,
            amountCents INTEGER, tierNo INTEGER NOT NULL, remainingM3 REAL, note TEXT, createdAt INTEGER NOT NULL
        )""".trimIndent())
        db.execSQL("CREATE TABLE sun_times (cacheKey TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun readSnapshot(): GasDatabaseSnapshot = GasDatabaseSnapshot(
        readings = readableDatabase.query("readings", null, null, null, null, null, "epochMillis").use { c ->
            buildList { while (c.moveToNext()) add(c.reading()) }
        },
        tiers = readableDatabase.query("tiers", null, null, null, null, null, "tierNo").use { c ->
            buildList { while (c.moveToNext()) add(c.tier()) }
        },
        payments = readableDatabase.query("payments", null, null, null, null, null, "paidAtMillis").use { c ->
            buildList { while (c.moveToNext()) add(c.payment()) }
        },
    )

    fun replaceSnapshot(snapshot: GasDatabaseSnapshot) = writableDatabase.inTransaction {
        delete("readings", null, null)
        delete("tiers", null, null)
        delete("payments", null, null)
        snapshot.readings.forEach { insertOrThrow("readings", null, it.values()) }
        snapshot.tiers.forEach { insertOrThrow("tiers", null, it.values()) }
        snapshot.payments.forEach { insertOrThrow("payments", null, it.values()) }
    }

    /** 通用键值缓存（引用同一张 cache 表），单键读写，不影响其它缓存条目 */
    fun cacheValue(key: String): String? = readableDatabase.query(
        "sun_times", arrayOf("value"), "cacheKey=?", arrayOf(key), null, null, null,
    ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun putCacheValue(key: String, value: String) {
        writableDatabase.insertWithOnConflict(
            "sun_times",
            null,
            ContentValues().apply { put("cacheKey", key); put("value", value) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun readSunTimes(): Map<String, String> = readableDatabase.query(
        "sun_times", arrayOf("cacheKey", "value"), null, null, null, null, null,
    ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) } }

    fun replaceSunTimes(values: Map<String, String>) = writableDatabase.inTransaction {
        delete("sun_times", null, null)
        values.forEach { (key, value) ->
            insertOrThrow("sun_times", null, ContentValues().apply { put("cacheKey", key); put("value", value) })
        }
    }

    private fun android.database.Cursor.reading() = MeterReadingEntity(
        id = getLong(column("id")), epochMillis = getLong(column("epochMillis")),
        readingMilli = getLong(column("readingMilli")), photoPath = string("photoPath"),
        latitude = double("latitude"), longitude = double("longitude"), locationName = string("locationName"),
        tempMinC = double("tempMinC"), tempMaxC = double("tempMaxC"), weatherStatus = getString(column("weatherStatus")),
        source = getString(column("source")), ocrRaw = string("ocrRaw"), note = string("note"),
        createdAt = getLong(column("createdAt")),
    )

    private fun android.database.Cursor.tier() = TariffTierEntity(
        tierNo = getInt(column("tierNo")), capM3 = double("capM3"), priceCentsPerM3 = getLong(column("priceCentsPerM3")),
    )

    private fun android.database.Cursor.payment() = PaymentEntity(
        id = getLong(column("id")), paidAtMillis = getLong(column("paidAtMillis")), readingMilli = getLong(column("readingMilli")),
        amountCents = long("amountCents"), tierNo = getInt(column("tierNo")), remainingM3 = double("remainingM3"),
        note = string("note"), createdAt = getLong(column("createdAt")),
    )

    private fun android.database.Cursor.column(name: String) = getColumnIndexOrThrow(name)
    private fun android.database.Cursor.string(name: String) = column(name).let { if (isNull(it)) null else getString(it) }
    private fun android.database.Cursor.double(name: String) = column(name).let { if (isNull(it)) null else getDouble(it) }
    private fun android.database.Cursor.long(name: String) = column(name).let { if (isNull(it)) null else getLong(it) }

    private fun MeterReadingEntity.values() = ContentValues().apply {
        put("id", id); put("epochMillis", epochMillis); put("readingMilli", readingMilli); put("photoPath", photoPath)
        put("latitude", latitude); put("longitude", longitude); put("locationName", locationName); put("tempMinC", tempMinC)
        put("tempMaxC", tempMaxC); put("weatherStatus", weatherStatus); put("source", source); put("ocrRaw", ocrRaw)
        put("note", note); put("createdAt", createdAt)
    }
    private fun TariffTierEntity.values() = ContentValues().apply {
        put("tierNo", tierNo); put("capM3", capM3); put("priceCentsPerM3", priceCentsPerM3)
    }
    private fun PaymentEntity.values() = ContentValues().apply {
        put("id", id); put("paidAtMillis", paidAtMillis); put("readingMilli", readingMilli); put("amountCents", amountCents)
        put("tierNo", tierNo); put("remainingM3", remainingM3); put("note", note); put("createdAt", createdAt)
    }
}

private inline fun SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> Unit) {
    beginTransaction()
    try {
        block()
        setTransactionSuccessful()
    } finally {
        endTransaction()
    }
}
