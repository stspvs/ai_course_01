# Строка запуска под Windows в KMP (как сейчас устроено)

## Где хранится

- **Graylog:** модель [`GraylogSettings`](../composeApp/src/commonMain/kotlin/com/example/ai_develop/data/GraylogSettings.kt) и поле `startCommand`, сохранение в SqlDelight (`GraylogSettingsEntity`). UI — блок «Локальный Graylog» в [`McpServersContent.kt`](../composeApp/src/commonMain/kotlin/com/example/ai_develop/presentation/compose/McpServersContent.kt).
- **MCP:** у каждого сервера в [`McpServerRecord`](../composeApp/src/commonMain/kotlin/com/example/ai_develop/data/McpModels.kt) есть поле `startCommand`; запуск из [`McpServersViewModel.runServerStartCommand`](../composeApp/src/commonMain/kotlin/com/example/ai_develop/presentation/McpServersViewModel.kt) через тот же механизм, что и Graylog.

## Где выполняется (только desktop JVM)

В common объявлен [`expect class GraylogPlatform`](../composeApp/src/commonMain/kotlin/com/example/ai_develop/platform/GraylogPlatform.kt) с методом `runStartCommand(command: String)`.

Реальная реализация только в **desktop** — см. [`GraylogPlatform.desktop.kt`](../composeApp/src/desktopMain/kotlin/com/example/ai_develop/platform/GraylogPlatform.desktop.kt): на Windows используется `ProcessBuilder("cmd.exe", "/c", trimmed)`, иначе `ProcessBuilder("sh", "-c", trimmed)`. Stdout/stderr перенаправляются в `DISCARD`, процесс стартует в фоне.

На **Windows** вся строка из поля передаётся **одним аргументом** в `cmd.exe /c` — `cmd` парсит её как в окне «Выполнить» или в `.bat` (для путей с пробелами используйте кавычки).

На **не-Windows** используется `sh -c` с той же семантикой одной строки.

Вывод процесса в UI **не показывается**, поэтому при ошибках Docker/скрипта сообщение в приложении может быть обобщённым; детали смотрите в консоли запуска desktop-клиента или временно отключайте discard при отладке.

## KMP и другие таргеты

Сейчас есть `actual` только для **desktop**. Если появятся Android / iOS / иные таргеты, для них понадобятся свои `actual` для `GraylogPlatform` (или общий интерфейс + биндинг в DI): на Android нельзя запускать произвольную shell-строку так же, как на ПК.

## Регистрация в DI

В [`Koin.kt`](../composeApp/src/commonMain/kotlin/com/example/ai_develop/di/Koin.kt): `single<GraylogPlatform> { GraylogPlatform() }`. `GraylogSettingsViewModel` и `McpServersViewModel` получают платформу и вызывают `runStartCommand` при запуске команд.

## Gradle и `gradlew.bat` (локальный сервер в этом репозитории)

В корне проекта лежит [`gradlew.bat`](../gradlew.bat). В поле **команды запуска** (MCP или Graylog) можно указать запуск JVM-сервера через Gradle.

**Рабочий каталог процесса:** дочерний процесс наследует текущий каталог **десктоп-приложения** (часто это каталог проекта в IDE, но не гарантировано). Надёжный вариант — **не полагаться на cwd**, а явно перейти в корень репозитория и вызвать wrapper:

```bat
cd /d "C:\Users\you\AndroidStudioProjects\ai_projects\day_1" && gradlew.bat :composeApp:run
```

Подставьте свой полный путь к корню, где лежит `gradlew.bat`. Другой вариант — указать **полный путь к `gradlew.bat`** и аргументы задачи в одной строке (путь в кавычках, если есть пробелы):

```bat
"C:\Users\you\AndroidStudioProjects\ai_projects\day_1\gradlew.bat" :composeApp:run
```

Имя задачи (`:composeApp:run` и т.д.) должно совпадать с тем, что объявлено в [`composeApp/build.gradle.kts`](../composeApp/build.gradle.kts) для запуска нужного «кода сервера».

Если команда не находит `gradlew.bat` или Gradle — проверьте кавычки, путь и то, что JDK доступна той же средой, что и у ручного запуска из терминала.

## MCP по STDIO (в приложении)

В диалоге добавления/редактирования MCP-сервера можно выбрать транспорт **STDIO** вместо Streamable HTTP. В БД это поле [`McpWireKind`](../composeApp/src/commonMain/kotlin/com/example/ai_develop/data/McpModels.kt) (`wireKind` в `McpServerEntity`).

Клиент на desktop запускает **один** дочерний процесс по строке **«Команда запуска MCP (stdio)»** (через `cmd /c` на Windows), подключает [`StdioClientTransport`](https://github.com/modelcontextprotocol/kotlin-sdk) из официального Kotlin MCP SDK и держит сессию для `listTools` / `callTool`. Кнопка **«Сброс stdio»** на карточке сервера закрывает процесс; следующее **«Обновить список tools»** создаёт новый процесс.

**Не подставляйте** в stdio поле команду вида `:composeApp:run` из раздела про Graylog/boot выше — это задача **полного настольного клиента**, из‑за неё при обновлении списка tools открывается **вторая копия приложения**. Нужна отдельная Gradle-задача, которая стартует только MCP stdio-сервер.

**Важно на стороне вашего сервера:** процесс должен говорить MCP по **stdin/stdout** (в Kotlin — `StdioServerTransport` / соответствующая точка входа), а не только по HTTP. Отдельная Gradle-задача вида `:module:run` должна поднимать именно stdio-сервер; иначе клиент не завершит handshake.

Для режима **Streamable HTTP** по-прежнему используется URL и опциональная отдельная команда «boot» (как раньше).
