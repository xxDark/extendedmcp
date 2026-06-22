# ij-mcp — Extended MCP Server for IntelliJ

IntelliJ-плагин, расширяющий встроенный JetBrains MCP-сервер дополнительными инструментами: рефакторинг, навигация по коду, Gradle, работа с типами и многое другое.

## Возможности

Плагин добавляет следующие MCP-инструменты поверх встроенных:

| Инструмент | Описание |
|---|---|
| `find_usages` | Поиск использований символа |
| `find_class` | Поиск класса по имени |
| `get_file_outline` | Структура файла (методы, поля, классы) |
| `get_implementations` | Поиск реализаций интерфейса/класса |
| `get_type_info` | Информация о типе выражения |
| `get_symbol_info` | Информация о символе |
| `go_to_declaration` | Переход к объявлению символа |
| `check_library_sources` | Просмотр исходников библиотечных классов |
| `list_package_classes` | Список классов в пакете |
| `apply_quick_fix` | Применение quick-fix из инспекций |
| `optimize_imports` | Оптимизация импортов в файле |
| `extract_method` | Рефакторинг: извлечение метода |
| `inline_method` | Рефакторинг: инлайн метода |
| `move_class` | Рефакторинг: перемещение класса |
| `rename_refactoring` | Рефакторинг: переименование |
| `change_method_signature` | Рефакторинг: изменение сигнатуры метода |
| `safe_delete` | Безопасное удаление с проверкой использований |
| `add_method` / `add_field` | Добавление метода/поля в класс |
| `add_import` | Добавление import-выражения |
| `add_documentation` / `get_documentation` | Работа с документацией |
| `run_gradle_task` | Запуск Gradle-задачи |
| `list_tools_filter` | Просмотр списка включённых/выключенных инструментов |

Также в меню **Tools** добавляются:
- **MCP Tool Filter** — UI для включения/выключения отдельных MCP-инструментов
- **MCP Tool Metrics** — просмотр статистики использования инструментов

## Требования

- IntelliJ IDEA **2025.3.3** или новее (Ultimate или Community)
- Встроенный плагин **MCP Server** должен быть включён в IDE
- JDK 21+ (для сборки)

## Установка

### 1. Сборка плагина

```bash
git clone <repo-url>
cd extendedmcp
./gradlew buildPlugin
```

Артефакт появится в `build/distributions/ij-mcp-1.0-SNAPSHOT.zip`.

### 2. Установка плагина в IntelliJ IDEA

1. Откройте **Settings** → **Plugins**
2. Нажмите шестерёнку ⚙️ → **Install Plugin from Disk...**
3. Выберите файл `build/distributions/ij-mcp-1.0-SNAPSHOT.zip`
4. Нажмите **OK** и перезапустите IDE

### 3. Включение MCP Server в IDEA

Встроенный MCP-сервер поставляется с IntelliJ IDEA 2025.3+.

1. Откройте **Settings** → **Tools** → **MCP Server**
2. Включите чекбокс **Enable MCP Server**
3. Нажмите **Apply** / **OK**

После включения сервер будет доступен по SSE (HTTP) и stdio-транспорту.

### 4. Подключение к Claude Code

Есть два способа: автоматический и ручной.

#### Способ A: Auto-Configure (рекомендуется)

1. Откройте **Settings** → **Tools** → **MCP Server**
2. В секции **Clients Auto-Configuration** найдите **Claude Code**
3. Нажмите **Auto-Configure** — IDEA сама пропишет конфиг в Claude Code
4. Перезапустите Claude Code или выполните `/mcp` для переподключения

#### Способ B: Ручная настройка через Copy Stdio Config

1. Откройте **Settings** → **Tools** → **MCP Server**
2. В секции **Manual Client Configuration** нажмите кнопку **Copy Stdio Config**
3. В Claude Code выполните `/mcp` → **Add new MCP server**
4. Введите имя (например, `intellij`) и вставьте скопированный конфиг

Либо вставьте скопированный конфиг напрямую в файл `~/.claude.json` в секцию `mcpServers`:

```json
{
  "mcpServers": {
    "intellij": <вставьте скопированный stdio-конфиг сюда>
  }
}
```

#### Способ C: Попросить Claude Code

Если Claude Code уже запущен, можно просто попросить его:

```
Добавь MCP сервер intellij со stdio конфигом: <вставить скопированный конфиг>
```

> **Важно:** IDEA должна быть запущена с открытым проектом до подключения MCP-клиента. MCP-сервер работает в контексте открытого проекта.

### 5. Проверка подключения

В Claude Code выполните `/mcp` — сервер `intellij` должен отображаться со статусом **connected** и списком доступных инструментов.

Также можно проверить работу любым инструментом, например:

```
Используй find_class чтобы найти класс Main
```

## Обновление плагина

1. Пересоберите: `./gradlew buildPlugin`
2. В IDEA: **Settings** → **Plugins** → ⚙️ → **Install Plugin from Disk...** → выберите новый zip
3. Перезапустите IDE
4. В Claude Code: `/mcp` → переподключите сервер `intellij` (или перезапустите Claude Code)

## Управление инструментами

Через меню **Tools > MCP Tool Filter** можно выборочно отключать ненужные MCP-инструменты — это уменьшает context window у AI-клиента и ускоряет работу.

## Совместимость версий

Значение `platformVersion` в `gradle.properties` **должно совпадать** с версией запущенной IDE. При несовпадении плагин может выдавать `NoClassDefFoundError` при загрузке. Текущая целевая версия: **2025.3.3**.
