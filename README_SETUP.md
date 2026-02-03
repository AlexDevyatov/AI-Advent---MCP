# Настройка и запуск

## Бэкенд (Python)

**Нужен Python 3.10 или новее.** Проверка: `python3 --version`.

Установка Python 3.10+ (macOS): **Homebrew** `brew install python@3.12` или **pyenv** `pyenv install 3.12`.

Используйте виртуальное окружение (чтобы не упираться в externally-managed-environment):

```bash
cd /путь/к/MCPAndroid

# 1) Создать venv и установить зависимости (один раз):
python3.12 -m venv .venv
source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt

# 2) Запустить MCP-прокси (эмулятор: http://10.0.2.2:8000):
source .venv/bin/activate
uvicorn mcp_proxy:app --host 0.0.0.0 --port 8000

# Либо одной командой (скрипт сам создаст venv при первом запуске):
./run_mcp_proxy.sh
```

Если `pip install` падает с **SSL-ошибкой** (macOS): откройте «Python 3.12» в папке приложения Python и запустите «Install Certificates.command», или выполните в терминале:  
`/Applications/Python\ 3.12/Install\ Certificates.command`

## Android

- **AndroidManifest.xml**: уже добавлены `INTERNET`, `POST_NOTIFICATIONS` и `android:name=".LauncherApp"`.
- **API Key DeepSeek**: положите ключ в `app/src/main/assets/creds.txt` (одна строка — ключ).
- **local.properties**: создаётся Android Studio автоматически (sdk.dir). Для эмулятора MCP-прокси по умолчанию: `http://10.0.2.2:8000` (настроено в `AppModule.kt`).

## Запуск

1. Запустить прокси: `uvicorn mcp_proxy:app --host 0.0.0.0 --port 8000`
2. Вписать DeepSeek API key в `app/src/main/assets/creds.txt`
3. Собрать и запустить приложение на эмуляторе или устройстве.
