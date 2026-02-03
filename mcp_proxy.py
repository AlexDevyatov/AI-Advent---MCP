#!/usr/bin/env python3
"""
HTTP-прокси для MCP. POST /mcp — запускает MCP-сервер как подпроцесс (stdio), возвращает JSON-RPC ответ.
Логи запросов и ответов выводятся в терминал.
"""
import json
import logging
import os
import subprocess
import sys
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] mcp_proxy: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
    stream=sys.stderr,
    force=True,
)
logger = logging.getLogger("mcp_proxy")
# Сброс буфера после каждой записи, чтобы логи сразу были в терминале
for h in logging.root.handlers:
    h.setLevel(logging.INFO)

MCP_SCRIPT = Path(__file__).resolve().parent / "mcp_reminders.py"
MCP_TIMEOUT_SEC = int(os.environ.get("MCP_TIMEOUT", "30"))

app = FastAPI(title="MCP Reminders Proxy")


@app.on_event("startup")
def on_startup():
    msg = "MCP proxy ready. Логи запросов появятся при POST /mcp (отправьте сообщение из приложения)."
    logger.info(msg)
    # Явный вывод в stderr, чтобы увидеть при старте (uvicorn может буферизовать logging)
    print(f"[mcp_proxy] {msg}", file=sys.stderr, flush=True)


INITIALIZE = {
    "jsonrpc": "2.0",
    "id": 0,
    "method": "initialize",
    "params": {
        "protocolVersion": "2024-11-05",
        "capabilities": {},
        "clientInfo": {"name": "mcp-proxy", "version": "1.0.0"},
    },
}
INITIALIZED = {"jsonrpc": "2.0", "method": "notifications/initialized"}


def run_mcp_request(payload: dict) -> dict:
    """Запуск MCP-сервера как подпроцесс: initialize, initialized, затем запрос пользователя."""
    method = payload.get("method", "?")
    req_id = payload.get("id", "?")
    params = payload.get("params", {})
    tool_name = params.get("name", "?") if isinstance(params, dict) else "?"
    logger.info("MCP request: method=%s id=%s tool=%s", method, req_id, tool_name)
    print(f"[mcp_proxy] MCP request: method={method} id={req_id} tool={tool_name}", file=sys.stderr, flush=True)

    env = os.environ.copy()
    env["REMINDERS_DB"] = str(Path(__file__).resolve().parent / "reminders.db")
    stdin_lines = [
        json.dumps(INITIALIZE) + "\n",
        json.dumps(INITIALIZED) + "\n",
        json.dumps(payload) + "\n",
    ]
    request_input = "".join(stdin_lines)
    try:
        proc = subprocess.Popen(
            [sys.executable, str(MCP_SCRIPT)],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=None,  # stderr в консоль — логи MCP-сервера видны в терминале
            text=True,
            cwd=str(MCP_SCRIPT.parent),
            env=env,
        )
        try:
            out, _ = proc.communicate(input=request_input, timeout=MCP_TIMEOUT_SEC)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()
            logger.warning("MCP timeout id=%s", payload.get("id"))
            return {"jsonrpc": "2.0", "error": {"code": -32603, "message": "MCP timeout"}, "id": payload.get("id")}
    except Exception as e:
        logger.exception("MCP subprocess error: %s", e)
        return {"jsonrpc": "2.0", "error": {"code": -32603, "message": str(e)}, "id": payload.get("id")}

    if proc.returncode != 0:
        logger.warning("MCP exit code %s id=%s", proc.returncode, payload.get("id"))
        return {
            "jsonrpc": "2.0",
            "error": {"code": -32603, "message": f"MCP exit code {proc.returncode}"},
            "id": payload.get("id"),
        }
    lines = [s for s in out.strip().split("\n") if s.strip()]
    # Ответ на initialize, на initialized (нет), ответ на наш запрос — берём последний с id.
    for line in reversed(lines):
        try:
            obj = json.loads(line)
            if "id" in obj and obj.get("id") == payload.get("id"):
                has_error = "error" in obj
                status = "ERROR" if has_error else "OK"
                logger.info("MCP response: id=%s %s", req_id, status)
                print(f"[mcp_proxy] MCP response: id={req_id} {status}", file=sys.stderr, flush=True)
                return obj
        except json.JSONDecodeError:
            continue
    if lines:
        try:
            obj = json.loads(lines[-1])
            logger.info("MCP response: id=%s OK (fallback)", req_id)
            return obj
        except json.JSONDecodeError:
            pass
    logger.warning("MCP empty response id=%s", payload.get("id"))
    return {"jsonrpc": "2.0", "error": {"code": -32603, "message": "Empty MCP response"}, "id": payload.get("id")}


@app.post("/mcp")
async def mcp_endpoint(request: Request):
    """Принимает JSON-RPC тело, прокидывает в MCP (stdio), возвращает JSON-RPC ответ."""
    logger.info("POST /mcp")
    print("[mcp_proxy] POST /mcp", file=sys.stderr, flush=True)
    try:
        body = await request.json()
    except Exception as e:
        logger.warning("Parse error: %s", e)
        return JSONResponse(
            status_code=400,
            content={"jsonrpc": "2.0", "error": {"code": -32700, "message": "Parse error"}, "id": None},
        )
    if not isinstance(body, dict):
        logger.warning("Invalid request: body is not dict")
        return JSONResponse(
            status_code=400,
            content={"jsonrpc": "2.0", "error": {"code": -32600, "message": "Invalid Request"}, "id": None},
        )
    result = run_mcp_request(body)
    return JSONResponse(content=result)
