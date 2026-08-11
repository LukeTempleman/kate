package tech.gonxt.kate.memory.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConversationEntity::class,
        TurnEntity::class,
        MemoryEntity::class,
        GraphNodeEntity::class,
        GraphEdgeEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class KateDb : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun turns(): TurnDao
    abstract fun memories(): MemoryDao
    abstract fun graph(): GraphDao

    companion object {
        fun build(context: Context): KateDb =
            Room.databaseBuilder(context, KateDb::class.java, "kate.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
