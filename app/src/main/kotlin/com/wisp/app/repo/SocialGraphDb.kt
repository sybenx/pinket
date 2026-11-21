package com.wisp.app.repo

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import android.util.Log

class SocialGraphDb(private val context: Context, initialPubkeyHex: String? = null) {

    companion object {
        private const val TAG = "SocialGraphDb"
        private const val LEGACY_DB_NAME = "social_graph.db"

        private fun dbName(pubkeyHex: String?): String =
            if (pubkeyHex != null) "social_graph_$pubkeyHex.db" else LEGACY_DB_NAME
    }

    private class InternalHelper(ctx: Context, dbName: String) :
        SQLiteOpenHelper(ctx, dbName, null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE followed_by (
                    pubkey TEXT NOT NULL,
                    follower TEXT NOT NULL,
                    PRIMARY KEY (pubkey, follower)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX idx_followed_by_pubkey ON followed_by(pubkey)")
            createMutedBy(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) createMutedBy(db)
        }

        /** Who among the user's follows has [pubkey] in their public mute list (kind 10000). */
        private fun createMutedBy(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS muted_by (
                    pubkey TEXT NOT NULL,
                    muter TEXT NOT NULL,
                    PRIMARY KEY (pubkey, muter)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_muted_by_pubkey ON muted_by(pubkey)")
        }
    }

    @Volatile private var helper: InternalHelper =
        migrateAndOpen(initialPubkeyHex)

    /**
     * One-time migration: if the legacy `social_graph.db` exists AND the target
     * per-account file does not yet exist, rename legacy -> per-account so that
     * existing single-account users retain their computed graph after upgrade.
     * If no pubkey is provided OR the target already exists, the helper is
     * opened against the target filename as-is (no migration).
     */
    private fun migrateAndOpen(pubkeyHex: String?): InternalHelper {
        if (pubkeyHex != null) {
            val legacyFile = context.getDatabasePath(LEGACY_DB_NAME)
            val targetFile = context.getDatabasePath(dbName(pubkeyHex))
            if (legacyFile.exists() && !targetFile.exists()) {
                try {
                    targetFile.parentFile?.mkdirs()
                    val renamed = legacyFile.renameTo(targetFile)
                    // Also move the -journal and -wal/-shm sidecars if present.
                    for (suffix in listOf("-journal", "-wal", "-shm")) {
                        val srcSide = context.getDatabasePath(LEGACY_DB_NAME + suffix)
                        val dstSide = context.getDatabasePath(dbName(pubkeyHex) + suffix)
                        if (srcSide.exists() && !dstSide.exists()) srcSide.renameTo(dstSide)
                    }
                    Log.i(TAG, "Migrated legacy social_graph.db -> ${dbName(pubkeyHex)} (renamed=$renamed)")
                } catch (e: Exception) {
                    Log.e(TAG, "Legacy social graph migration failed; using empty per-account DB", e)
                }
            }
        }
        return InternalHelper(context, dbName(pubkeyHex))
    }

    /** Swap to a different account's DB file. Closes the current helper. */
    @Synchronized
    fun reload(newPubkeyHex: String?) {
        try { helper.close() } catch (_: Exception) {}
        helper = migrateAndOpen(newPubkeyHex)
    }

    fun insertBatch(rows: List<Pair<String, String>>) {
        if (rows.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val stmt: SQLiteStatement =
                db.compileStatement("INSERT OR IGNORE INTO followed_by (pubkey, follower) VALUES (?, ?)")
            for ((pubkey, follower) in rows) {
                stmt.bindString(1, pubkey)
                stmt.bindString(2, follower)
                stmt.executeInsert()
                stmt.clearBindings()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Replace the whole muted_by table with [rows] (muted pubkey, muter) in one transaction. */
    fun replaceMutedBy(rows: List<Pair<String, String>>) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM muted_by")
            val stmt: SQLiteStatement =
                db.compileStatement("INSERT OR IGNORE INTO muted_by (pubkey, muter) VALUES (?, ?)")
            for ((pubkey, muter) in rows) {
                stmt.bindString(1, pubkey)
                stmt.bindString(2, muter)
                stmt.executeInsert()
                stmt.clearBindings()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getMutedByCount(pubkey: String): Int {
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM muted_by WHERE pubkey = ?", arrayOf(pubkey)
        ).use { cursor -> return if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    /** pubkey -> follower count, only for pubkeys with at least [minCount] rows. */
    fun getFollowerCountsAtLeast(minCount: Int): Map<String, Int> =
        countsAtLeast("followed_by", minCount)

    /** pubkey -> muted-by count, only for pubkeys with at least [minCount] rows. */
    fun getMutedByCountsAtLeast(minCount: Int): Map<String, Int> =
        countsAtLeast("muted_by", minCount)

    private fun countsAtLeast(table: String, minCount: Int): Map<String, Int> {
        val result = HashMap<String, Int>()
        helper.readableDatabase.rawQuery(
            // minCount is inlined: a bound arg would be TEXT and never compare >= an integer.
            "SELECT pubkey, COUNT(*) AS cnt FROM $table GROUP BY pubkey HAVING cnt >= ${minCount.toInt()}",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) result[cursor.getString(0)] = cursor.getInt(1)
        }
        return result
    }

    fun getFollowers(pubkey: String): List<String> {
        val result = mutableListOf<String>()
        helper.readableDatabase.rawQuery(
            "SELECT follower FROM followed_by WHERE pubkey = ?", arrayOf(pubkey)
        ).use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.getString(0))
        }
        return result
    }

    fun getFollowerCount(pubkey: String): Int {
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM followed_by WHERE pubkey = ?", arrayOf(pubkey)
        ).use { cursor -> return if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    fun getTopByFollowerCount(limit: Int, fromPubkeys: Set<String>): List<Pair<String, Int>> {
        if (fromPubkeys.isEmpty()) return emptyList()
        val result = mutableListOf<Pair<String, Int>>()
        val placeholders = fromPubkeys.joinToString(",") { "?" }
        helper.readableDatabase.rawQuery(
            "SELECT pubkey, COUNT(*) as cnt FROM followed_by WHERE follower IN ($placeholders) GROUP BY pubkey ORDER BY cnt DESC LIMIT ?",
            fromPubkeys.toTypedArray() + limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.getString(0) to cursor.getInt(1))
        }
        return result
    }

    /** Explicit wipe of the current DB's contents. NOT called during account
     *  switch — only invoked by a future explicit "recompute from scratch" path. */
    fun clearAll() {
        try {
            helper.writableDatabase.execSQL("DELETE FROM followed_by")
            helper.writableDatabase.execSQL("DELETE FROM muted_by")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear social graph", e)
        }
    }
}
