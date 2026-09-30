package com.cutetodo.app

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val note: String = "",
    val dueAt: Long? = null,      // waktu pengingat (epoch ms)
    val repeat: Int = 0,          // 0 sekali, 1 harian, 2 mingguan
    val emoji: String = "📌",
    val starred: Boolean = false,
    val done: Boolean = false,
    val subs: String = "",
    @ColumnInfo(defaultValue = "''") val photo: String = "",   // nama file foto di penyimpanan aplikasi
    @ColumnInfo(defaultValue = "''") val sound: String = "",   // suara pengingat: "" bawaan, key di Sounds, atau "custom"
    @ColumnInfo(defaultValue = "''") val voice: String = "",   // nama file catatan suara (m4a) di penyimpanan aplikasi
    @ColumnInfo(defaultValue = "''") val uid: String = UUID.randomUUID().toString(),  // id global, sama di semua device (untuk sinkronisasi)
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0                          // kapan terakhir diubah (epoch ms), untuk menentukan versi terbaru
)

/** Catatan tugas yang sudah dihapus, supaya penghapusan ikut menyebar ke device lain saat sinkronisasi. */
@Entity(tableName = "tombstones")
data class Tomb(@PrimaryKey val uid: String, val deletedAt: Long)

@Dao
abstract class TaskDao {
    @Query("SELECT * FROM tasks ORDER BY done ASC, starred DESC, CASE WHEN dueAt IS NULL THEN 1 ELSE 0 END, dueAt ASC")
    abstract fun all(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    abstract fun get(id: Long): Task?

    @Query("SELECT * FROM tasks WHERE done = 0 AND dueAt > :now")
    abstract fun upcoming(now: Long): List<Task>

    @Query("SELECT * FROM tasks WHERE done = 0 AND dueAt IS NOT NULL")
    abstract fun withDue(): List<Task>

    @Query("SELECT COUNT(*) FROM tasks WHERE done = 0 AND dueAt <= :endOfDay")
    abstract fun pendingUntil(endOfDay: Long): Int

    @Query("SELECT * FROM tasks") abstract fun list(): List<Task>

    // ---- tulis mentah (dipakai sinkronisasi: updatedAt dari cloud dipertahankan) ----
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract fun insertRaw(t: Task): Long
    @Delete abstract fun deleteRaw(t: Task)
    @Query("DELETE FROM tasks") abstract fun wipeRaw()

    @Query("SELECT * FROM tombstones") abstract fun tombs(): List<Tomb>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract fun putTomb(t: Tomb)
    @Query("DELETE FROM tombstones") abstract fun clearTombs()

    // ---- tulis biasa: otomatis mencatat waktu ubah / penghapusan ----
    open fun upsert(t: Task): Long = insertRaw(t.copy(updatedAt = System.currentTimeMillis()))

    open fun delete(t: Task) {
        deleteRaw(t)
        putTomb(Tomb(t.uid, System.currentTimeMillis()))
    }

    @Transaction
    open fun wipe() { list().forEach { delete(it) } }

    @Transaction
    open fun clearDone() { list().filter { it.done }.forEach { delete(it) } }
}

@Database(entities = [Task::class, Tomb::class], version = 6, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): TaskDao

    companion object {
        /** v2 -> v3: tambah kolom foto. Tugas lama tetap aman. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN photo TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v3 -> v4: tambah kolom suara pengingat. Tugas lama tetap aman (pakai suara bawaan). */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN sound TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v4 -> v5: tambah kolom catatan suara. Tugas lama tetap aman. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN voice TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v5 -> v6: id global + waktu ubah untuk sinkronisasi Google Drive. Tugas lama diberi uid acak. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tasks ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE tasks SET uid = lower(hex(randomblob(16))), updatedAt = ${System.currentTimeMillis()}")
                db.execSQL("CREATE TABLE IF NOT EXISTS tombstones (uid TEXT NOT NULL, deletedAt INTEGER NOT NULL, PRIMARY KEY(uid))")
            }
        }

        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, Db::class.java, "todo.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .fallbackToDestructiveMigration().build().also { inst = it }
        }
    }
}
