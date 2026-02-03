package com.example.mcpandroid.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reminders")
data class Reminder(
    @PrimaryKey val id: Int,
    val text: String,
    val dueDatetime: String?,
    val completed: Boolean
)
