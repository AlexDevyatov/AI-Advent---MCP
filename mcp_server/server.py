#!/usr/bin/env python3
"""
Минимальный MCP-сервер для AI Challenge — День 11.
Реализует протокол MCP (Model Context Protocol) через Streamable HTTP:
- initialize — согласование версии и возможностей
- notifications/initialized — уведомление о готовности клиента
- tools/list — список доступных инструментов (health_check, get_users)
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

# Список инструментов MCP (tools) — демо для челленджа
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

        method = payload.get("method")
        response = None

        if method == "initialize":
            response = handle_initialize(self, payload)
        elif method == "notifications/initialized":
            send_202(self)
            return
        elif method == "tools/list":
            response = handle_tools_list(self, payload)
        else:
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
