#!/usr/bin/env python3
"""
MCP-сервер напоминаний. SQLite, параметризованные запросы, ISO 8601.
Транспорт: stdio (для запуска прокси как подпроцесс).
Логи пишутся в stderr, чтобы не мешать JSON-RPC по stdin/stdout.
"""
import json
import logging
import os
import sys
import sqlite3
from datetime import datetime, timezone, timedelta
from pathlib import Path

from mcp.server.fastmcp import FastMCP

# Логи в stderr (stdio занят протоколом MCP)
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
    stream=sys.stderr,
    force=True,
)
logger = logging.getLogger("mcp_reminders")

DB_PATH = os.environ.get("REMINDERS_DB", str(Path(__file__).resolve().parent / "reminders.db"))


def get_connection() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_db() -> None:
    with get_connection() as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS reminders (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                text TEXT NOT NULL,
                due_datetime TEXT,
                completed INTEGER NOT NULL DEFAULT 0
            )
            """
        )
        conn.commit()
    logger.info("DB initialized: %s", DB_PATH)


init_db()

mcp = FastMCP("Reminders MCP", json_response=True)


def _err(text: str) -> str:
    """FIX: все ошибки как JSON с префиксом ❌ для отображения пользователю (TextContent)."""
    return json.dumps({"error": f"❌ {text}"})


@mcp.tool()
def add_reminder(text: str, due_datetime: str | None = None) -> str:
    """
    Добавить напоминание.
    text: текст напоминания (обязательно).
    due_datetime: дата/время в ISO 8601 (например 2025-02-03T14:00:00Z или 2025-02-03T14:00:00+00:00). Необязательно.
    """
    logger.info("add_reminder(text=%r, due_datetime=%r)", text, due_datetime)
    # FIX: не падать при неверных входных данных (например не строка)
    try:
        text = str(text).strip() if text is not None else ""
    except (TypeError, ValueError):
        return _err("неверный формат текста напоминания")
    if not text:
        logger.warning("add_reminder: empty text")
        return _err("текст напоминания не может быть пустым")
    due_clean = None
    if due_datetime is not None:
        try:
            due_clean = str(due_datetime).strip() or None
            if due_clean:
                datetime.fromisoformat(due_clean.replace("Z", "+00:00"))
        except (ValueError, TypeError):
            logger.warning("add_reminder: invalid due_datetime=%r", due_datetime)
            return _err("дата должна быть в формате ISO 8601 (например 2026-02-04T05:00:00)")
    try:
        with get_connection() as conn:
            cur = conn.execute(
                "INSERT INTO reminders (text, due_datetime, completed) VALUES (?, ?, 0)",
                (text, due_clean),
            )
            conn.commit()
            row_id = cur.lastrowid
        result = json.dumps({"id": row_id, "text": text, "due_datetime": due_clean})
        logger.info("add_reminder: created id=%s", row_id)
        return result
    except Exception as e:
        logger.exception("add_reminder: db error %s", e)
        return _err("ошибка сохранения напоминания")


@mcp.tool()
def list_upcoming_reminders(hours_ahead: int = 24) -> str:
    """
    Список напоминаний: не выполненные, с датой до конца окна или без даты.
    hours_ahead: окно в часах (по умолчанию 24). Большое значение (например 8760) — по сути «все».
    """
    logger.info("list_upcoming_reminders(hours_ahead=%s)", hours_ahead)
    # FIX: не падать при неверном типе (например строка вместо int)
    try:
        hours_ahead = int(hours_ahead) if hours_ahead is not None else 24
    except (TypeError, ValueError):
        hours_ahead = 24
    if hours_ahead < 0:
        hours_ahead = 0
    with get_connection() as conn:
        if hours_ahead == 0:
            # 0 = вывести все невыполненные напоминания (без фильтра по дате)
            rows = conn.execute(
                """
                SELECT id, text, due_datetime, completed
                FROM reminders
                WHERE completed = 0
                ORDER BY due_datetime IS NULL, due_datetime ASC
                """,
            ).fetchall()
        else:
            now = datetime.now(timezone.utc)
            limit_ts = now + timedelta(hours=hours_ahead)
            limit_str = limit_ts.strftime("%Y-%m-%dT%H:%M:%S") + "Z"
            rows = conn.execute(
                """
                SELECT id, text, due_datetime, completed
                FROM reminders
                WHERE completed = 0
                  AND (due_datetime IS NULL OR due_datetime <= ?)
                ORDER BY due_datetime IS NULL, due_datetime ASC
                """,
                (limit_str,),
            ).fetchall()
    items = [
        {
            "id": r["id"],
            "text": r["text"],
            "due_datetime": r["due_datetime"],
            "completed": bool(r["completed"]),
        }
        for r in rows
    ]
    logger.info("list_upcoming_reminders: found %s items", len(items))
    return json.dumps({"reminders": items})


@mcp.tool()
def mark_reminder_completed(reminder_id: int) -> str:
    """
    Отметить напоминание как выполненное.
    reminder_id: id напоминания (INTEGER).
    """
    logger.info("mark_reminder_completed(reminder_id=%s)", reminder_id)
    # FIX: не падать при неверном типе (например строка)
    try:
        rid = int(reminder_id)
    except (TypeError, ValueError):
        return _err("reminder_id должен быть числом")
    try:
        with get_connection() as conn:
            cur = conn.execute(
                "UPDATE reminders SET completed = 1 WHERE id = ?",
                (rid,),
            )
            conn.commit()
            if cur.rowcount == 0:
                logger.warning("mark_reminder_completed: not found id=%s", rid)
                return _err(f"напоминание не найдено: {rid}")
        logger.info("mark_reminder_completed: done id=%s", rid)
        return json.dumps({"id": rid, "completed": True})
    except Exception as e:
        logger.exception("mark_reminder_completed: error %s", e)
        return _err("ошибка при отметке напоминания")


@mcp.tool()
def clear_all_reminders() -> str:
    """
    Удалить все напоминания (очистить список полностью).
    args не требуются.
    """
    logger.info("clear_all_reminders()")
    try:
        with get_connection() as conn:
            cur = conn.execute("SELECT COUNT(*) FROM reminders")
            count = cur.fetchone()[0]
            conn.execute("DELETE FROM reminders")
            conn.commit()
        logger.info("clear_all_reminders: deleted %s", count)
        return json.dumps({"deleted": count, "message": f"Удалено напоминаний: {count}"})
    except Exception as e:
        logger.exception("clear_all_reminders: error %s", e)
        return _err("ошибка при очистке напоминаний")


if __name__ == "__main__":
    logger.info("Starting MCP server (stdio)")
    mcp.run(transport="stdio")
