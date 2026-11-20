# API-тесты СМП — отчёт

Набор реализован отдельным Maven-модулем `services/api-tests`.

## 1. Покрытие

| Область и эндпоинты | Проверяемые статус-коды | Проверяемое поведение | Статус |
|---|---|---|---|
| Gateway `GET /health` | 200 | состояние Gateway и карта downstream-зависимостей | покрыто |
| Card Management `GET /health` | 200 | `status`, `service`, `cardsInDatabase` | покрыто |
| Cards `POST /api/cards` | 201, 400 | создание, схема, PAN, исходные значения, все ограничения DTO, наблюдаемый пробел `initialBalance` | покрыто |
| Cards `GET /api/cards/{pan}` | 200, 400, 404 | существующая/несуществующая карта, невалидный PAN, заголовок `X-Cache` | покрыто |
| Cards `GET /api/cards` | 200, 400, 413, 500 | пагинация, пустая выборка, все фильтры, границы и невалидные enum | покрыто |
| Cards `PATCH/DELETE /api/cards/{pan}` | 200, 204, 400, 404, 500 | изменение статуса/лимита, soft delete, повторное удаление, запрещённый статус | покрыто |
| Cards `POST /api/cards/generate` | 201, 400 | ON/OFF-границы `count`, состав ответа | покрыто |
| Cards `POST /api/cards/{pan}/reserve` | 200, 400, 402, 404, 409 | баланс, статус карты, валидация, повтор RRN | покрыто |
| Cards `POST /api/cards/{pan}/rollback` | 200, 400, 404, 409, 422 | откат, повторный откат, несовпадение данных | покрыто |
| Cards `POST /api/cards/bulk-update` | 200 | реализационный endpoint и результат обновления | покрыто |
| BIN issuer `GET/POST/DELETE /api/bins[/{bin}]` | 200, 201, 204, 404, 409 | полный CRUD напрямую и отсутствие маршрута Gateway | покрыто |
| Authorization `GET /health` | 200 | зависимость `card-management` | покрыто |
| Authorization `POST /api/internal/authorize` | 200, 400, 403, 404, 422, 503 | все бизнес-исходы, валидация и отображение ошибок CMS | покрыто |
| Authorization `POST /api/internal/rollback` | 200, 400, 404, 409, 422, 500, 503 | успешный/повторный откат и все downstream-исходы | покрыто |
| Switch `GET /health`, `POST /api/internal/route` | 200 | прямой маршрут авторизации и формат ответа | покрыто |
| Gateway `POST /api/transactions` | 200, 400, 429, 500 | валидация, rate limit и заголовок `Retry-After`, downstream fault | покрыто |
| Logger `GET /health`, `POST /api/internal/log` | 200, 201, 400, 409 | новая, идентичная и конфликтующая доставка; невалидное тело | покрыто |
| Logger `GET /api/transactions/search` | 200, 400 | все фильтры, пагинация, пустая выборка, GPath-отбор своей записи | покрыто |
| Logger `GET /api/transactions/export` | 200, 400 | CSV, `Content-Type`, `Content-Disposition`, фильтры | покрыто |
| Dashboard `GET /api/dashboard/stats`, `/recent`, `/charts` | 200, 400 | агрегаты, размер выдачи, гранулярность и границы | покрыто |
| BIN lookup `GET /api/bin/{bin}` | 200, 404, 500 | найденный/неизвестный BIN и принудительная ошибка | покрыто |
| Terminal `GET /health`, `POST /run`, `/start-continuous`, `/stop` | 200, 400 | синхронный и непрерывный режимы, счётчики, валидация | покрыто |
| Объявленные Terminal `POST /start`, `GET /status` | 500 | фактическое отсутствие обработчиков и неверный error mapping | покрыто |
| Merchant `GET /health`, `POST /run`, `POST /fee`, `GET /merchants` | 200, 400, 404 | запуск, комиссия, валидация, список напрямую и route mismatch | покрыто |
| Notifications `GET /api/notifications` | 200 | пагинация и асинхронное появление события создания карты | покрыто |

## 2. Бизнес-отказы

| Исход | HTTP | Код | Причина | Путь достижения | Подготовка |
|---|---:|---|---|---|---|
| Успех | 200 | `00` | `null` | реальная цепочка Authorization/CMS | карта через API |
| Карта заблокирована | 403 | `05` | `CARD_BLOCKED` | ответ CMS 200 со статусом `BLOCKED` | WireMock и отдельный e2e-переход статуса |
| Карта неактивна | 403 | `05` | `CARD_INACTIVE` | ответ CMS 200 со статусом `INACTIVE` | WireMock |
| Карта истекла | 403 | `54` | `CARD_EXPIRED` | статус `EXPIRED` и прошедшая дата — два пути | WireMock |
| Карта не найдена | 404 | `14` | `CARD_NOT_FOUND` | CMS 404 | WireMock |
| Недостаточно средств | 422 | `51` | `INSUFFICIENT_FUNDS` | CMS 402 | WireMock |
| Превышен лимит | 422 | `61` | `EXCEEDS_AMOUNT_LIMIT` | дневной/месячный лимит | реальная карта через API |
| CMS недоступен | 503 | `96` | `SERVICE_UNAVAILABLE` | CMS 500/503 | параметризованный WireMock |
| Повторный rollback | 409 | — | `ALREADY_ROLLED_BACK` | CMS 409 | WireMock |


## 3. Валидация

| Область | Поля и правила | Классы и границы | Проверок |
|---|---|---|---:|
| Создание карты | `bin`, `cardholderName`, `currencyCode`, `dailyLimit`, `monthlyLimit`, `initialBalance` | 5/6/7 символов, цифры/нецифры, blank, uppercase/lowercase, −1/0, отсутствующее ограничение | 11 |
| Список карт | `limit`, `offset`, `status`, `bin`, `issuerId`, `startDate/endDate` | 0/1/10000/10001, −1/0, валидный/невалидный enum, пустая и инвертированная выборка | 10 |
| Reserve/rollback | `amount`, `rrn`, `pan` | 0/1, 5/12 символов, отсутствующая карта, повтор | 9 |
| Авторизация | все 13 полей `AuthorizationRequest` | один невалидный класс на запрос, формат/blank/null/отрицательное значение | 13 |
| Gateway | PAN, MTI, STAN, amount и JSON | параметризованные классы, malformed/unsupported значения | 8 |
| Logger read API | даты, `limit`, `offset`, `granularity` | неверный формат, инвертированный диапазон, OFF-границы | 8 |
| Симуляторы | `count`, `tps`, обязательные поля fee | 0/1, отсутствующие ресурсы и поля | 5 |

В коде семь параметризованных методов. Тестовые UUID,
STAN, RRN, PAN и IP создаются заново; подготовка состояния выполняется только по
HTTP API. Для PostgreSQL-совместимой идемпотентности временные метки тестовых
транзакций заранее ограничиваются микросекундной точностью.

## 4. Расхождения контракта и реализации

| Декларировано | Наблюдается | Источник декларации | Классификация |
|---|---|---|---|
| `POST /api/simulator/terminal/start`, `GET /status` | обработчиков нет; через Gateway возвращается 500 `terminal-simulator` | `docs/api/openapi.yaml` | отсутствующие endpoints + неверный error mapping |
| `GET /api/simulator/merchants` доступен через Gateway | контроллер отвечает 200 напрямую, Gateway не маршрутизирует plural path | OpenAPI / route config | route mismatch |
| `/api/bins` — API Card Management | CRUD есть в контроллере, но отсутствует в Gateway и OpenAPI | код контроллера | недокументированный/недоступный маршрут |
| `limit=10001` списка карт → 400 | возвращается 413 | `test-cases.md`, контракт пагинации | status-code mismatch |
| невалидный `status` списка/`DELETED` в PATCH → 400 | возвращается 500 | DTO/ожидание клиентской ошибки | необработанное преобразование enum |
| `initialBalance=-1` → 400 | карта создаётся с 201 | `test-cases.md` | отсутствующее ограничение DTO |
| `startDate > endDate` → 400 | 200 и пустой список | контракт диапазона | отсутствующая cross-field validation |
| недоступный Switch → Gateway 503 | WireMock connection reset приводит к 500 | Gateway downstream contract | неполная классификация сетевой ошибки |
| MTI ответа авторизации `0110` | реальный Authorization/Switch возвращает входной `0100` | OpenAPI `AuthorizationResponse` | ISO 8583 contract mismatch |
| BIN lookup `bankName` | код возвращает `issuerName` | ранние тестовые ожидания / DTO кода | уточнение имени поля по приоритетному источнику |
| Terminal реализует `/start-continuous` и `/stop` | этих путей нет в общем OpenAPI | код контроллера | недокументированные endpoints |
| Card Management реализует `/bulk-update`, Merchant — `/fee`, Authorization — `/rollback` | отсутствуют либо неполно представлены в общем OpenAPI | код контроллеров | недокументированные endpoints |

Тесты с `Known gap` фиксируют текущее наблюдаемое поведение, а таблица выше
сохраняет ожидаемый контракт и позволяет оформить дефекты без маскировки
расхождений.

## 5. Обоснование достаточности

Перечень получен объединением трёх источников: общего OpenAPI, Spring
контроллеров и маршрутов Gateway. Для каждого найденного HTTP endpoint выбран
хотя бы один позитивный класс и все достижимые ветви ошибок из контроллеров,
exception handlers и клиентских адаптеров. В авторизации отдельно перебраны все
доменные результаты; для неоднозначных кодов проверяется пара
`responseCode + declineReason`.

Границы входов выбраны методом ON/OFF: минимум, максимум, точки непосредственно
за границей, корректный и некорректный формат. Для фильтров проверены каждый
параметр, совместное применение и пустая выборка. Повторная доставка разделена
на новую запись (201), идентичный дубль (200) и конфликт того же UUID (409).

Требуемые приёмы присутствуют непосредственно в коде: общие
`RequestSpecification`, `given/when/then`, support builders, Hamcrest,
JSON Schema, GPath `find/findAll`, проверки `X-Cache`, `Retry-After`,
`Content-Type` и `Content-Disposition`, а также Awaitility для появления и
гарантированного отсутствия эффекта. Фиксированных `sleep` в тестах нет.

За пределами assertions оставлены внутренние SQL/RabbitMQ-детали: тесты
наблюдают их эффекты только через HTTP. Это соответствует black-box уровню;
прямой доступ к БД и очередям набору не требуется.

## 6. Доказательства работоспособности проверок

Для проверки способности тестов "падать", были проверены следующие случаи:

| Тест | Внесённое изменение | Красный результат |
|---|---|---|
| `CardApiTest#createAndReadCard` | ожидание статуса созданной карты изменено `ACTIVE → BLOCKED` | `Expected: BLOCKED, Actual: ACTIVE`, 1/1 failure |
| `AuthorizationApiTest#approvedAuthorization` | ожидаемый `responseCode` изменён `00 → 05` | `Expected: 05, Actual: 00`, 1/1 failure |
| `GatewayLoggerApiTest#internalLogIdempotencyAndConflict` | в `TransactionService` временно отключена ветка дедупликации: существующий UUID принудительно считался конфликтом | повтор вернул 409 вместо ожидаемого 200, 1/1 failure |

После восстановления выполнены три последовательных полных прогона: во всех
`Tests run: 97, Failures: 0, Errors: 0, Skipped: 0`.

## 7. Порядок запуска

Предварительные требования: Docker Compose, Java 21 и Maven. Файл `.env`
создаётся из `.env.example` только при его отсутствии.

```bash
make api-test
```

Команда поднимает основной Compose-стенд и test override с WireMock, ожидает
готовность сервисов и запускает Maven-профиль `api-tests`.

Для проверки стабильности:

```bash
API_TEST_RUNS=3 ./scripts/run-api-tests.sh
```

CI job `api-tests` выполняет три прогона, сохраняет Surefire reports, при
ошибке печатает логи сервисов и всегда удаляет Compose volumes.
