#!/usr/bin/env python3
"""
MCP-сервер для AI Challenge — День 11 + День 12.
- initialize, notifications/initialized, tools/list, tools/call.
- Инструменты: health_check, get_users, get_open_tasks (День 12 — трекер задач).
Запуск: python server.py
Эндпоинт: http://127.0.0.1:8765/mcp
"""

import json
import http.server
import socketserver
from urllib.parse import urlparse

PORT = 8765
MCP_PATH = "/mcp"
PROTOCOL_VERSION = "2025-11-25"

# Хранилище задач (in-memory, упрощённый трекер)
TASKS = []  # список dict: {"id": int, "title": str, "status": "open" | "done"}
_next_task_id = 1

# Список инструментов MCP (tools)
TOOLS = [
    {
        "name": "health_check",
        "title": "Health Check",
        "description": "Проверяет состояние сервера и доступность сервиса.",
        "inputSchema": {
            "type": "object",
            "properties": {},
            "additionalProperties": False,
        },
    },
    {
        "name": "get_users",
        "title": "Get Users",
        "description": "Возвращает список пользователей (демо-данные).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "limit": {
                    "type": "integer",
                    "description": "Максимальное количество записей",
                }
            },
            "additionalProperties": False,
        },
    },
    {
        "name": "get_open_tasks",
        "title": "Get Open Tasks",
        "description": "Возвращает количество открытых задач (упрощённый трекер).",
        "inputSchema": {
            "type": "object",
            "properties": {},
            "additionalProperties": False,
        },
    },
    {
        "name": "add_task",
        "title": "Add Task",
        "description": "Добавляет задачу с указанным названием (статус: открыта).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "title": {
                    "type": "string",
                    "description": "Название задачи",
                }
            },
            "required": ["title"],
            "additionalProperties": False,
        },
    },
]


def send_json(handler, status: int, body: dict):
    """Отправка JSON-ответа с корректными заголовками."""
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(data)))
    handler.end_headers()
    handler.wfile.write(data)


def send_202(handler):
    """Ответ 202 Accepted (для notifications)."""
    handler.send_response(202)
    handler.end_headers()


def handle_initialize(handler, payload: dict) -> dict:
    """Обработка MCP initialize — возвращаем возможности сервера."""
    req_id = payload.get("id")
    return {
        "jsonrpc": "2.0",
        "id": req_id,
        "result": {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {
                "tools": {"listChanged": True},
            },
            "serverInfo": {
                "name": "mcp-challenge-server",
                "version": "1.0.0",
                "description": "MCP server for AI Challenge Day 11",
            },
        },
    }


def handle_tools_list(handler, payload: dict) -> dict:
    """Обработка tools/list — возвращаем список инструментов."""
    req_id = payload.get("id")
    return {
        "jsonrpc": "2.0",
        "id": req_id,
        "result": {
            "tools": TOOLS,
        },
    }


def handle_tools_call(handler, payload: dict) -> dict:
    """
    Обработка tools/call — вызов инструмента агентом.
    Агент отправляет method: "tools/call", params: { name, arguments }.
    Возвращаем result в формате MCP: content (массив text/image), isError.
    """
    req_id = payload.get("id")
    params = payload.get("params") or {}
    name = params.get("name")
    arguments = params.get("arguments") or {}

    if name == "get_open_tasks":
        # Количество и список открытых задач из хранилища TASKS
        open_tasks_list = [t for t in TASKS if t.get("status") == "open"]
        result_data = {
            "open_tasks": len(open_tasks_list),
            "tasks": [{"id": t["id"], "title": t["title"], "status": t["status"]} for t in open_tasks_list],
        }
        return {
            "jsonrpc": "2.0",
            "id": req_id,
            "result": {
                "content": [
                    {"type": "text", "text": json.dumps(result_data, ensure_ascii=False)},
                ],
                "isError": False,
            },
        }
    if name == "add_task":
        # Добавить задачу: arguments["title"]
        global _next_task_id
        title = (arguments.get("title") or "").strip()
        if not title:
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {
                    "content": [{"type": "text", "text": json.dumps({"ok": False, "error": "title required"})}],
                    "isError": True,
                },
            }
        task_id = _next_task_id
        _next_task_id += 1
        TASKS.append({"id": task_id, "title": title, "status": "open"})
        result_data = {"ok": True, "id": task_id, "title": title}
        return {
            "jsonrpc": "2.0",
            "id": req_id,
            "result": {
                "content": [
                    {"type": "text", "text": json.dumps(result_data, ensure_ascii=False)},
                ],
                "isError": False,
            },
        }
    if name == "health_check":
        return {
            "jsonrpc": "2.0",
            "id": req_id,
            "result": {
                "content": [{"type": "text", "text": '{"status":"ok"}'}],
                "isError": False,
            },
        }
    if name == "get_users":
        return {
            "jsonrpc": "2.0",
            "id": req_id,
            "result": {
                "content": [{"type": "text", "text": '{"users":[]}'}],
                "isError": False,
            },
        }

    return {
        "jsonrpc": "2.0",
        "id": req_id,
        "error": {"code": -32602, "message": f"Unknown tool: {name}"},
    }


class MCPHandler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        parsed = urlparse(self.path)
        if parsed.path != MCP_PATH:
            self.send_error(404)
            return

        # Читаем тело запроса
        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length) if content_length else b""

        try:
            payload = json.loads(body.decode("utf-8"))
        except Exception:
            send_json(self, 400, {"error": "Invalid JSON"})
            return

        method = (payload.get("method") or "").strip()
        response = None

        if method == "initialize":
            response = handle_initialize(self, payload)
        elif method == "notifications/initialized":
            send_202(self)
            return
        elif method == "tools/list":
            response = handle_tools_list(self, payload)
        elif method == "tools/call":
            response = handle_tools_call(self, payload)
        else:
            print(f"[MCP] Unknown method: {repr(method)}")
            send_json(
                self,
                200,
                {
                    "jsonrpc": "2.0",
                    "id": payload.get("id"),
                    "error": {"code": -32601, "message": f"Method not found: {method}"},
                },
            )
            return

        if response:
            send_json(self, 200, response)

    def log_message(self, format, *args):
        print(f"[MCP] {args[0]}")


def main():
    # Слушаем на всех интерфейсах (0.0.0.0), чтобы эмулятор (10.0.2.2) и устройства в LAN могли подключиться
    with socketserver.TCPServer(("0.0.0.0", PORT), MCPHandler) as httpd:
        print(f"MCP server listening on http://127.0.0.1:{PORT}{MCP_PATH}")
        try:
            import socket
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            local_ip = s.getsockname()[0]
            s.close()
            print(f"For device on same Wi‑Fi use: http://{local_ip}:{PORT}{MCP_PATH}")
        except Exception:
            pass
        print("Emulator: use http://10.0.2.2:8765 in app.")
        print("Press Ctrl+C to stop.")
        httpd.serve_forever()


if __name__ == "__main__":
    main()
