#!/bin/bash
# Запуск MCP-прокси.
cd "$(dirname "$0")"
if [ ! -d .venv ]; then
  echo "Создаём venv..."
  python3.12 -m venv .venv
fi
if ! .venv/bin/python -c "import uvicorn" 2>/dev/null; then
  echo "Устанавливаем зависимости..."
  .venv/bin/pip install -r requirements.txt
fi
.venv/bin/python -m uvicorn mcp_proxy:app --host 0.0.0.0 --port 8000
