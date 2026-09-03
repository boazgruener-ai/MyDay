/** Room database for captured WhatsApp messages, plus its v1->v2 schema migration. */
package ch.boazgruener.myday.whatsapp

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** exportSchema=false - pragmatic for a single-developer personal app with no anticipated
 * complex migrations, not a real schema-tracking need. */
@Database(entities = [WhatsAppMessageEntity::class], version = 2, exportSchema = false)
abstract class WhatsAppDatabase : RoomDatabase() {
    abstract fun whatsAppMessageDao(): WhatsAppMessageDao
}

/** Adds notificationKey for the reply feature - a real migration rather than a destructive
 * fallback, since Boaz already has real captured WhatsApp history on his phone from the read
 * feature by the time this shipped. Old rows get notificationKey=NULL, which correctly reads as
 * "never had one stored" rather than "had one, now gone". */
val WHATSAPP_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE whatsapp_messages ADD COLUMN notificationKey TEXT")
    }
}
