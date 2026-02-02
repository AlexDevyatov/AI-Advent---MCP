# День 11. Подключение MCP — AI Challenge

Минимальная реализация: **MCP-клиент на Android (Kotlin + Compose)** и **MCP-сервер (Python)**. Приложение подключается к серверу, запрашивает `tools/list` и отображает список инструментов.

---

## 1. Рекомендуемая архитектура

- **Локальный MCP-сервер** (Python, HTTP на порту 8765) — запускается на компьютере.
- **Android-приложение** — MCP-клиент по **Streamable HTTP**: отправляет JSON-RPC POST на `http://<host>:8765/mcp`.
- Для **эмулятора** хост = `10.0.2.2` (это ваш компьютер). Для **реального устройства** — IP компьютера в той же Wi‑Fi сети (вводится в приложении в поле «URL MCP-сервера»).

**Реальное устройство:** вверху экрана есть поле «URL MCP-сервера». Введите `http://<IP_вашего_ПК>:8765` (IP смотрите в выводе сервера при запуске) и нажмите «Подключиться». URL сохраняется и используется при следующих запусках.

Удалённый MCP (сервер в интернете) возможен тем же кодом — укажите HTTPS-URL в поле URL.

---

## 2. Структура проекта

```
MCPAndroid/
├── app/
│   └── src/main/java/com/example/mcpandroid/
│       ├── MainActivity.kt              # Точка входа, Compose UI
│       ├── mcp/
│       │   ├── McpClient.kt             # MCP-клиент (HTTP, initialize, tools/list)
│       │   ├── McpConfig.kt             # URL сервера (10.0.2.2:8765 для эмулятора)
│       │   ├── McpTool.kt               # Модель инструмента (name, description)
│       │   └── ToolsViewModel.kt         # Загрузка tools при старте
│       └── ui/
│           └── ToolsScreen.kt           # Экран со списком инструментов
├── mcp_server/
│   ├── server.py                        # MCP-сервер (initialize, tools/list, 2 tools)
│   └── requirements.txt
└── README_MCP_CHALLENGE.md
```

---

## 3. Код

### 3.1 MCP-сервер (Python)

Файл: `mcp_server/server.py`

- Один HTTP endpoint: `POST /mcp`.
- Обрабатывает JSON-RPC: `initialize`, `notifications/initialized`, `tools/list`.
- Два инструмента: **health_check**, **get_users** (только метаданные для списка).

Зависимости: только стандартная библиотека Python 3.

### 3.2 MCP-клиент (Kotlin)

- **McpClient** — выполняет цепочку: `initialize` → `notifications/initialized` → `tools/list`; возвращает `Result<List<McpTool>>`.
- **McpTool** — данные инструмента (name, title, description).
- Заголовки: `Accept`, `Content-Type`, `MCP-Protocol-Version: 2025-11-25`.

### 3.3 UI (Jetpack Compose)

- **ToolsScreen** — по состоянию показывает: загрузка, список карточек (имя + описание) или ошибку с кнопкой «Повторить».
- **ToolsViewModel** — при создании вызывает загрузку tools в `Dispatchers.IO`, обновляет `ToolsUiState`.

---

## 4. Запуск

### 4.1 Запуск MCP-сервера

На компьютере (в каталоге проекта):

```bash
cd mcp_server
python3 server.py
```

Ожидаемый вывод:

```
MCP server listening on http://127.0.0.1:8765/mcp
Press Ctrl+C to stop.
```

### 4.2 Запуск Android-приложения

1. Открыть проект в Android Studio.
2. Запустить приложение на **эмуляторе** (или устройстве в той же сети).
3. По умолчанию используется `http://10.0.2.2:8765` (эмулятор → хост). Для устройства изменить URL в `McpConfig.kt` на `http://<IP вашего ПК>:8765`.

---

## 5. Ожидаемый результат на экране

- Сначала: индикатор «Подключение к MCP…».
- После успешного ответа: заголовок **MCP Tools** и два инструмента:
  1. **Health Check** — «Проверяет состояние сервера и доступность сервиса.»
  2. **Get Users** — «Возвращает список пользователей (демо-данные).»
- При ошибке (сервер не запущен, нет сети): экран с текстом ошибки и кнопкой «Повторить».

---

## 6. MCP-интеграция (кратко)

- **Протокол:** JSON-RPC 2.0, транспорт — Streamable HTTP (POST с JSON телом).
- **Жизненный цикл:** сначала `initialize`, затем уведомление `notifications/initialized`, затем запросы вроде `tools/list`.
- **Метод списка инструментов:** `tools/list` (не `list_tools`); в ответе — `result.tools` (массив с полями name, title, description, inputSchema).

Код сознательно минимальный: без авторизации, без вызова инструментов (tools/call), только подключение и отображение списка tools для челленджа.
