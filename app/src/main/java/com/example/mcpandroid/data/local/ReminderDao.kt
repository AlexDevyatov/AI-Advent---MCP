package com.example.mcpandroid.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY dueDatetime IS NULL, dueDatetime ASC")
    fun allReminders(): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders WHERE completed = 0 AND (dueDatetime IS NULL OR dueDatetime >= :fromIso AND dueDatetime <= :toIso) ORDER BY dueDatetime IS NULL, dueDatetime ASC")
    fun upcoming(fromIso: String, toIso: String): Flow<List<Reminder>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(reminders: List<Reminder>)

    @Query("UPDATE reminders SET completed = 1 WHERE id = :id")
    suspend fun markCompleted(id: Int)

    @Query("DELETE FROM reminders")
    suspend fun deleteAll()
}
