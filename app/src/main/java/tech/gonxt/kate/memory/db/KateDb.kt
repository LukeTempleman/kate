package tech.gonxt.kate.memory.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import tech.gonxt.kate.skills.db.SkillDao
import tech.gonxt.kate.skills.db.SkillEntity
import tech.gonxt.kate.skills.db.SkillRunDao
import tech.gonxt.kate.skills.db.SkillRunEntity
import tech.gonxt.kate.sync.SyncLogDao
import tech.gonxt.kate.sync.SyncLogEntity

@Database(
    entities = [
        ConversationEntity::class,
        TurnEntity::class,
        MemoryEntity::class,
        GraphNodeEntity::class,
        GraphEdgeEntity::class,
        SkillEntity::class,
        SkillRunEntity::class,
        SyncLogEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class KateDb : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun turns(): TurnDao
    abstract fun memories(): MemoryDao
    abstract fun graph(): GraphDao
    abstract fun skills(): SkillDao
    abstract fun skillRuns(): SkillRunDao
    abstract fun syncLog(): SyncLogDao

    companion object {
        fun build(context: Context): KateDb =
            Room.databaseBuilder(context, KateDb::class.java, "kate.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
