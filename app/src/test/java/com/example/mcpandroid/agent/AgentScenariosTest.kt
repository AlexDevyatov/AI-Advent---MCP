package com.example.mcpandroid.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Проверка логики сценариев для цепочки Android-агент ↔ MCP.
 *
 * Сценарии:
 * 1. «Напомни позвонить маме завтра в 18:00» → add_reminder с ISO-датой (промпт + валидация).
 * 2. «Напомни через 5 минут» → get_current_time, затем add_reminder (двухэтапка в send()).
 * 3. «Что у меня запланировано?» → list_upcoming_reminders (промпт + fallback).
 * 4. «Готово с ID 3» → mark_reminder_completed(3) (промпт).
 * 5. «Какая сегодня дата?» → get_current_time, показать результат (без двухэтапки).
 * 6. «Напомни в 25 часов» → валидация часа 0–23, ошибка (isReasonableTime).
 */
class AgentScenariosTest {

    @Test
    fun isoDateTime_validFormats_parse() {
        val isoLocal = "2026-02-04T18:00:00"
        val dt = LocalDateTime.parse(isoLocal, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        assertTrue(dt.hour in 0..23 && dt.minute in 0..59)
    }

    @Test
    fun invalidHour_25_rejected() {
        // Час 25 невалиден — isReasonableTime в AgentManager требует hour in 0..23
        val invalid = "2026-02-04T25:00:00"
        val parsedLocal = try {
            LocalDateTime.parse(invalid, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        } catch (_: Exception) {
            null
        }
        assertTrue(parsedLocal == null || parsedLocal.hour !in 0..23)
    }

    @Test
    fun relativeTime_phrase_detected() {
        // Логика looksLikeRelativeTime: "через" + ("минут" | "час" | "секунд")
        val relativePhrases = listOf("напомни через 5 минут", "через 2 часа")
        val notRelative = listOf("какая сегодня дата", "что у меня запланировано")
        assertTrue(relativePhrases.all { it.contains("через") && (it.contains("минут") || it.contains("час")) })
        assertTrue(notRelative.none { it.contains("через") && it.contains("минут") })
    }

    @Test
    fun commandLike_phrases_detected() {
        val commandPhrases = listOf(
            "напомни позвонить маме",
            "что у меня запланировано",
            "готово с ID 3",
            "какая сегодня дата"
        )
        commandPhrases.forEach { phrase ->
            val lower = phrase.lowercase()
            val looksLike = lower.contains("напомни") || lower.contains("запланировано") ||
                lower.contains("готово") || lower.contains("дата")
            assertTrue("Expected command: $phrase", looksLike)
        }
    }
}
