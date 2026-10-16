# Пирамида тестирования СМП: новые тест-кейсы, распределение, пропорции

## 1. Новые тест-кейсы (часть б)

### 1.1. Дополнительные требования

Требования REQ-AUTH-\* и REQ-CMS-\* взяты из [практики 2](../practice-2/test-design.md#2-требования). Для sync- и async-путей, которые выпали из ядра практики 2, добавлены требования:

| ID | Требование | Источник |
|---|---|---|
| REQ-GW-01 | Gateway отклоняет невалидный запрос (`mti ≠ 0100`, `pan` не 16 цифр, `amount ≤ 0`, `mcc` не 4 цифры) кодом 400 и не передаёт его в Switch | [`tz/02-gateway.md`](../../tz/02-gateway.md), [`TransactionRequestValidator`](../../services/gateway/src/main/java/com/processing/gateway/validation/TransactionRequestValidator.java) |
| REQ-SW-01 | Switch определяет `issuerId` по BIN из своей таблицы; неизвестный BIN → DECLINED 14 без вызова Authorization | [`tz/03-switch.md`](../../tz/03-switch.md), [`RoutingService`](../../services/switch/src/main/java/com/processing/service/RoutingService.java) |
| REQ-SW-02 | Каждая обработанная транзакция публикуется в `smp.transactions` с ключом `transaction.log` | [`architecture.md`](../architecture.md) «Вариант A», [`LoggerClient`](../../services/switch/src/main/java/com/processing/service/LoggerClient.java) |
| REQ-SW-03 | Если публикация APPROVED-транзакции не удалась, Switch откатывает резервирование и возвращает DECLINED 96 | [`architecture.md`](../architecture.md) «Publisher Confirms», [`RouteService`](../../services/switch/src/main/java/com/processing/service/RouteService.java) |
| REQ-LOG-01 | Logger сохраняет транзакцию из очереди `transaction-log` идемпотентно по `id` | [`tz/08-transaction-logger.md`](../../tz/08-transaction-logger.md), [`TransactionService.store`](../../services/transaction-logger/src/main/java/com/processing/transactionlogger/service/TransactionService.java) |
| REQ-CMS-11 | Outbox-событие публикуется не более 3 раз; после 3 неудач — `FAILED` | [`OutboxEventProcessorImpl`](../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxEventProcessorImpl.java), `app.outbox.max-retry-count=3` |
| REQ-NS-01 | Notification сохраняет каждое карточное событие из `card-notifications` в `card_notifications` | [`CardEventListener`](../../services/notification-service/src/main/java/com/processing/notification/listener/CardEventListener.java) |
| REQ-MQ-01 | Сообщение, которое потребитель не может обработать, уходит в DLQ и хранится там 60 с | [`architecture.md`](../architecture.md) «Dead Letter Strategy», [`RabbitMQConfig` Switch](../../services/switch/src/main/java/com/processing/config/RabbitMQConfig.java) |

### 1.2. Тест-кейсы

| ID | Связанное требование | Уровень пирамиды | Вид | Предусловие | Шаги | Ожидаемый результат | Источник |
|---|---|:---:|:---:|---|---|---|---|
| TC-P3-01 | REQ-AUTH-18, REQ-AUTH-09, REQ-AUTH-11 | **unit** | нег. | `AuthServiceImpl`. Mock `CardManagementClient.getCard` → карта `ACTIVE`, срок валиден, `availableBalance = 1 000`, `dailyLimit = 500`. Mock `LimitUsageRepository.upsertLimitUsage` → 0 | `authorize(amount = 2 000)` | `DECLINED`, `responseCode = 61`, `EXCEEDS_AMOUNT_LIMIT`: по ТЗ лимит проверяется раньше баланса. `reserve` не вызывался. | граничное значение (приоритет проверок, тройка из TC-AUTH-24) |
| TC-P3-02 | REQ-AUTH-08 | **unit** | поз. / нег. | Mock CMS → карта `ACTIVE`, `expiryDate = 2026-09`, баланс и лимиты с запасом; mock repo → 1 | (а) `transmissionDateTime = 2026-09-30T23:59:59Z`; (б) `2026-10-01T00:00:00Z` | (а) `APPROVED`; (б) `DECLINED 54 CARD_EXPIRED`. Граница считается в UTC (`atZone(ZoneOffset.UTC)`) | граничное значение (BV-A-05 с точностью до секунды) |
| TC-P3-03 | REQ-SW-01 | **unit** | поз. / нег. | `RoutingService` с таблицей из `switch/application.yml` (BIN `400000`…`400004`) | `getIssuerIdByPan` для: `4000001234567890`, `4000041234567890`, `4000051234567890`, `40000`, `null` | `ISS001`; `ISS005`; `UnknownBinException`; `UnknownBinException("null-or-short")`; `UnknownBinException("null-or-short")` | класс эквивалентности (известный BIN / неизвестный / короткий / null), граничное значение (крайние BIN таблицы) |
| TC-P3-04 | REQ-SW-03 | **unit** | нег. | `RouteService`; mock `RoutingService` → `ISS001`; mock `AuthorizationClient.authorize` → `APPROVED`, `rrn = R`; mock `LoggerClient.log` → `false` | `route(request)`; повторить с `authorize` → `DECLINED 51` и `log` → `false` | (1) ответ `DECLINED`, `responseCode = 96`, `declineReason = "System error"`; `authorizationClient.rollback(request, R)` вызван ровно 1 раз. (2) возвращён исходный `DECLINED 51`, `rollback` не вызывался | сценарий (таблица решений `status × logged`) |
| TC-P3-05 | REQ-CMS-11 | **unit** | нег. | `OutboxEventProcessorImpl`; mock `RabbitTemplate.convertAndSend` бросает `AmqpConnectException`; `maxRetryCount = 3`; событие `PENDING`, `retryCount = 0` | вызвать `processSingleEvent` 3 раза подряд, сохраняя результат; затем для нового события mock публикует успешно | после 1-го и 2-го вызова — `PENDING`, `retryCount` = 1, 2, `lastError` заполнен; после 3-го — `FAILED`; успешная публикация → `PROCESSED` | граничное значение (`maxRetryCount`: 2 → `PENDING`, 3 → `FAILED`) |
| TC-P3-06 | REQ-CMS-09, REQ-CMS-05 | **unit** | поз. / нег. | `Card` с `availableBalance = 1 000`, статус `ACTIVE`; та же карта в статусе `BLOCKED` | `startReservation(1 000, R)`; `startReservation(1 001, R)`; `BLOCKED.startReservation(1, R)`; `withData(ACTIVE, daily = 10, monthly = 5, 0)` | `Reservation` со статусом `RESERVED`; `InsufficientFundsException`; `IllegalStateException`; `IllegalArgumentException` («Daily limit can not be larger than monthly») | граничное значение (баланс ON / OFF), состояние (A6) |
| TC-P3-07 | REQ-GW-01 | **unit** | нег. | `TransactionRequestValidator` и эталонный валидный `AuthorizationRequest` | Провалидировать эталон, затем 4 копии, в каждой ровно одно невалидное поле: `mti = "0400"`, `amount = 0`, `pan` из 15 цифр, `mcc = "54A1"` | Эталон проходит; каждая копия → `TransactionValidationException`, в сообщении указано имя поля. Reversal `0400` через Gateway невозможен | класс эквивалентности (по одному невалидному классу на кейс), граничное значение (`amount = 0`) |
| TC-P3-08 | REQ-AUTH-02, REQ-AUTH-04, REQ-AUTH-06, REQ-AUTH-11 | **api** | нег. | Authorization поднят со своей БД; CMS заменён WireMock: PAN A → 200 `BLOCKED`, PAN B → 404, PAN C → 200 `ACTIVE` с `availableBalance = 100` | `POST /api/internal/authorize` с `amount = 1 000` для A, B, C | A: HTTP **403**, `status = DECLINED`, `responseCode = 05`, `declineReason = CARD_BLOCKED`. B: **404** / `14` / `CARD_NOT_FOUND`. C: **422** / `51` / `INSUFFICIENT_FUNDS`. Во всех случаях `rrn = null`, `authCode = null`, тело соответствует схеме `AuthorizationResponse` | контракт |
| TC-P3-09 | REQ-GW-01, REQ-SW-01 | **api** | нег. / поз. | Gateway поднят; на месте Switch — WireMock, отвечает `DECLINED 14` | (1) `POST /api/transactions` с `pan` из 15 цифр; (2) валидный запрос | (1) 400, тело `ErrorResponse`; WireMock не получил ни одного вызова. (2) 200, тело Switch проксировано без изменений; WireMock получил запрос на `/api/internal/route` | контракт |
| TC-P3-10 | REQ-CMS-09, REQ-AUTH-19 | **api** | поз. / нег. | CMS + своя БД; карта `ACTIVE`, баланс `B` | (1) `reserve {amount 100, rrn R}`; (2) повторить (1); (3) `rollback {R, pan, 100}`; (4) повторить (3); (5) `rollback` с неизвестным `rrn` | (1) 200, баланс `B − 100`; (2) **409**, баланс не изменился; (3) 200, баланс `B`; (4) **409**; (5) **404** | контракт, состояние БД (резервирование RESERVED → ROLLED_BACK) |
| TC-P3-11 | REQ-CMS-05, REQ-CMS-06 | **api** | поз. / нег. | CMS + своя БД; карта X `ACTIVE`, карта Y `ACTIVE` | По карте X: `PATCH {status: BLOCKED}` → `reserve` → `PATCH {status: ACTIVE}` → `reserve` → `DELETE` → `DELETE`. По карте Y: `PATCH {status: "DELETED"}` | 200; **409** (карта не ACTIVE); 200; 200; **204**; **404**: удалённая карта скрыта `@SQLRestriction`. Для Y — 400 (значения нет в контракте). | состояние (таблица переходов A6) |
| TC-P3-12 | REQ-AUTH-09, REQ-AUTH-10, REQ-AUTH-14 | **integration** | поз. / нег. | `LimitUsageRepository` + PostgreSQL 16 (Testcontainers), таблица пуста; `dailyLimit = 15 000 000`, `monthlyLimit = 20 000 000`; дни d1 < d2 < d3 одного месяца | `upsert(10 000 000, d1)` → `upsert(5 000 000, d1)` → `upsert(1, d1)` → `upsert(4 000 000, d2)` → `upsert(1 000 001, d3)` | Результаты 1, 1, **0**, 1, **0**. После 3-го шага `daily_amount(d1) = 15 000 000` (не 15 000 001). Для d2 новая строка: `daily = 4 000 000`, `monthly = 19 000 000` (месяц перенесён). 5-й шаг: `monthly` стал бы 20 000 001 → 0, строка d3 не создана | граничное значение (ON / OFF дневного и месячного лимита), состояние БД |
| TC-P3-13 | REQ-SW-02, REQ-LOG-01 | **integration** | поз. | Logger + RabbitMQ + PostgreSQL (Testcontainers); топология объявлена | Опубликовать `TransactionRequest(id = U, pan = P)` в `smp.transactions` / `transaction.log` | В течение ≤ 5 с `GET /api/transactions/search?pan=P` → `total = 1`, очередь `transaction-log` пуста. Идемпотентность и конфликт по `id` вынесены в unit TC-P3-19 | состояние БД (eventual consistency) |
| TC-P3-14 | REQ-CMS-10, REQ-CMS-11, REQ-NS-01 | **integration** | поз. / нег. | CMS + Notification + RabbitMQ + PostgreSQL | (1) `POST /api/cards`; (2) `docker stop` RabbitMQ, ещё раз `POST /api/cards`, подождать 5 с; (3) запустить RabbitMQ, подождать 5 с | (1) строка в `outbox_event`: `PENDING` → `PROCESSED` за ≤ 2 с; в `card_notifications`: `event_type = CardServiceCreationEvent`, `routing_key = card.CardServiceCreationEvent`; `GET :8097/api/notifications` её возвращает. (2) `retry_count` растёт 1 → 3, затем `FAILED`, `last_error` заполнен, уведомления нет. (3) `FAILED`-событие **не** переотправляется: потеря события фиксируется | состояние БД, граничное значение (`max-retry-count = 3`) |
| TC-P3-15 | REQ-MQ-01 | **integration** | нег. | RabbitMQ; Switch запущен (объявил DLX/DLQ); Logger запущен | Опубликовать в `smp.transactions` / `transaction.log` тело `not-json` (`content_type = application/json`); проверить очереди через Management API (`:15672/api/queues`) через 1 с и через 65 с | Через 1 с: `transaction-log-dlq` содержит 1 сообщение, `transaction-log` пуст, в `transactions` новых записей нет (`MessageConversionException` → reject без requeue). Через 65 с: DLQ пуста, сработал TTL 60 000 мс | граничное значение (TTL DLQ), сценарий |
| TC-P3-16 | REQ-SW-03, REQ-AUTH-19 | **integration** | нег. | Реальные Switch + Authorization + CMS + PostgreSQL; RabbitMQ **остановлен**; карта `ACTIVE`, баланс `B`, `limit_usage` за сегодня пуст | `POST :8082/api/internal/route`, `amount = 1 000` | Ответ `DECLINED 96`. В `reservations` строка с `rrn` в статусе `ROLLED_BACK`; в `reservation_rollbacks` 1 строка; баланс = `B`; `limit_usage.daily_amount = 0` (REQ-AUTH-19). | сценарий (reversal, A4.3), состояние БД |
| TC-P3-17 | REQ-AUTH-12, REQ-AUTH-13, REQ-SW-02, REQ-LOG-01, REQ-NS-01 | **e2e** | поз. | `docker compose up` (весь стенд); карта `ACTIVE`, баланс `B`, создана через `POST :8080/api/cards` | (1) `POST :8080/api/transactions`; (2) polling до 10 с: `GET :8080/api/transactions/search?pan=…&status=APPROVED`; (3) `GET :8097/api/notifications`; (4) `GET :8081/api/cards/{pan}` **напрямую в CMS**: через Gateway ответ закэширован | (1) 200, `APPROVED`, `00`, `rrn` — 12 цифр, `authCode` — 6 символов `A-Z0-9`. (2) запись `APPROVED` с тем же `rrn`, заполнены `issuerId` и `acquirerFee`. (3) есть `CardServiceReserveEvent` с этим `rrn`. (4) баланс = `B − amount` | сценарий (happy path, A4.1) |
| TC-P3-18 | REQ-AUTH-06, REQ-CMS-05 | **e2e** | нег. | Стенд поднят; карта `ACTIVE`, баланс `B` | (1) `PATCH :8080/api/cards/{pan} {status: BLOCKED}`; (2) `POST :8080/api/transactions`; (3) polling Logger `?pan=…&status=DECLINED`; (4) `GET :8081/api/cards/{pan}` | (1) 200. (2) 200, `DECLINED`, `05`, `CARD_BLOCKED`. (3) запись `DECLINED` с `declineReason = CARD_BLOCKED`. (4) баланс = `B`; в `reservations` по карте ничего нет | состояние (переход ACTIVE → BLOCKED, A6), сценарий (declined, A4.2) |
| TC-P3-19 | REQ-LOG-01 | **unit** | поз. / нег. | `TransactionService`; mock `TransactionRepository`, `TransactionMapper`, `WebSocketManager` | `store(request id = U)` при трёх ответах `findById(U)`: (1) пусто; (2) запись, для которой `mapper.matches` → true; (3) запись, для которой `matches` → false | (1) `saveAndFlush` и `broadcast` по 1 разу, `created = true`. (2) `saveAndFlush` и `broadcast` не вызывались, `created = false`. (3) `TransactionConflictException`, `saveAndFlush` не вызывался | класс эквивалентности (новая / дубль / конфликт) |
| TC-P3-20 | REQ-CMS-02, REQ-CMS-09, REQ-AUTH-02 | **unit** | поз. / нег. | `Validation.buildDefaultValidatorFactory().getValidator()`, без Spring; эталонные валидные `CreateCardRequest`, `ReserveRequest`, `AuthorizationRequest` | `validate(эталон)`, затем копии с одним изменённым полем: `bin` = 5 / 6 / 7 цифр и `40000A`; `currencyCode` = `64` / `643` / `6430`; `dailyLimit` = −1 / 0; `initialBalance` = −1; `rrn` = 11 / 12 / 13 цифр; `amount` резерва = 0 / 1; `amount` авторизации = −1 / 0 | Эталон и ON-точки — 0 нарушений. Каждая OFF-точка — ровно 1 нарушение, `propertyPath` равен изменённому полю. `initialBalance = −1` — ожидается 1 нарушение (по требованию) | граничное значение, класс эквивалентности |
| TC-P3-21 | REQ-CMS-09 | **unit** | нег. | `CardServiceImpl` с mock-репозиториями; `TransactionRunner` выполняет лямбду сразу; карта `ACTIVE`, баланс 1 000; `reservationRepository.isUnique(R, pan)` → false | `reserve(pan, 100, R)` | `ReservationAlreadyExistsException`; `reservationRepository.save`, `cardRepository.update`, `eventNotifier.notifyListeners` не вызывались | класс эквивалентности (повторный rrn) |

---

## 2. Распределение тест-кейсов практики 2 (часть в)

### 2.1. Authorization (TC-AUTH-01…29)

| ID (пр. 2) | Название | Что проверяет тест | Уровень | Обоснование |
|---|---|---|:---:|---|
| TC-AUTH-01 | Health-check | контракт `GET /health`: статус и поле `dependencies.cardManagement` | api | это ответ эндпоинта, логики нет; нужен поднятый сервис и stub CMS |
| TC-AUTH-02 | Базовое одобрение | контракт ответа APPROVED: статус 200, `responseCode`, наличие `rrn` и `authCode` | api | проверяется сериализация и HTTP-статус контроллера; ветка решения отдельно покрыта PW-A-01 / unit |
| TC-AUTH-03 | Карта не найдена → 14 | маппинг «CMS 404 → `CARD_NOT_FOUND`, 14» | unit | решение принимает `AuthServiceImpl` по исключению `CardNotFoundException`; mock клиента достаточно |
| TC-AUTH-04 | INACTIVE → `CARD_INACTIVE` | ветку `switch (status)` в `AuthServiceImpl` | unit | чистая логика; статус приходит из mock CMS. PATCH-предусловие практики 2 на этом уровне не нужно |
| TC-AUTH-05 | BLOCKED → `CARD_BLOCKED` | то же, ветка BLOCKED | unit | то же |
| TC-AUTH-06 | EXPIRED → 54 | то же, ветка EXPIRED | unit | то же |
| TC-AUTH-07 | expiryDate прошлого месяца → 54 | сравнение `expiryDate.atEndOfMonth()` с датой транзакции | unit | граница вычисляется в Java (`YearMonth`, `LocalDate`); дата транзакции задаётся в запросе, часы мокать не нужно |
| TC-AUTH-08 | expiryDate текущего месяца → APPROVED | точку ON той же границы | unit | то же |
| TC-AUTH-09 | dailyLimit ON | `daily_amount + amount ≤ dailyLimit` при нулевом использовании | integration | сравнение выполняется в SQL `upsertLimitUsage`; unit с mock repo проверил бы только mock |
| TC-AUTH-10 | dailyLimit OFF → 61 | точку OFF той же границы | integration | то же |
| TC-AUTH-11 | Остаток дневного лимита ON | учёт уже накопленного `daily_amount` (`ON CONFLICT DO UPDATE … WHERE`) | integration | состояние в таблице `limit_usage` между вызовами |
| TC-AUTH-12 | Остаток дневного лимита OFF | то же, точка OFF | integration | то же |
| TC-AUTH-13 | monthlyLimit ON | месячную сумму через `DATE_TRUNC('month', …)` и `MAX(usage_date)` | integration | PG-специфичный SQL, проверяется только на настоящем PostgreSQL |
| TC-AUTH-14 | monthlyLimit OFF → 61 | то же, точка OFF | integration | то же |
| TC-AUTH-15 | Баланс ON → APPROVED | сравнение `amount > availableBalance` (`compareTo`) | unit | сравнение в Java. Постусловие «баланс = 0» — ответственность CMS, оно покрыто TC-CMS-28 (api) и здесь не дублируется |
| TC-AUTH-16 | Баланс OFF → 51 | точку OFF и то, что `reserve` не вызван | unit | `verify(cardManagementClient, never()).reserve(...)` |
| TC-AUTH-17 | Резервирование ровно один раз | что при APPROVED `reserve(amount, rrn, pan)` вызывается ровно один раз и с тем же `rrn`, что в ответе | unit | `verify(cardManagementClient, times(1)).reserve(...)`. Уменьшение баланса и защита от повторного списания — сторона CMS, они покрыты TC-CMS-28 и TC-CMS-32 (api) |
| TC-AUTH-18 | Отказ без побочных эффектов | что при DECLINED 61 `reserve` не вызывается | unit | mock repo → 0, `verify(..., never()).reserve(...)`. То, что `limit_usage` при превышении не растёт, — свойство SQL-условия `WHERE`, оно проверено в TC-P3-12 (шаг 3) |
| TC-AUTH-19 | Накопление лимита 3 + 1 | накопление `limit_usage` последовательными транзакциями | integration | состояние БД между запросами |
| TC-AUTH-20 | CMS недоступен → 05 | реакцию на отказ зависимости: HTTP 503, тело DECLINED. | api | WireMock CMS с fault / 503; проверяется контракт ответа Authorization |
| TC-AUTH-21 | BIN Lookup недоступен → APPROVED | graceful degradation и время ответа в пределах таймаутов 3 с / 5 с | api | реальные HTTP-таймауты `RestClient` проверяются только через сетевой stub (WireMock с задержкой) |
| TC-AUTH-22 | Уникальность RRN | формат (`год % 10` + день года + 8 цифр = 12 цифр) и различие RRN, в том числе на границе блока в 1 000 | unit | `generateRRN()` с mock `fetchRrnBlock()`, отдающим 1, 2, …; 1 001 вызов пересекает границу блока. Последовательность `rrn_seq` сама по себе — гарантия PostgreSQL, её отдельно не тестируем |
| TC-AUTH-23 | Формат authCode | 6 символов из `A-Z0-9` | unit | `generateAuthCode()` — чистая функция |
| TC-AUTH-24 | Приоритет проверок (тройка) | порядок шагов при одновременном нарушении трёх правил | unit | порядок `if` в `AuthServiceImpl`; mock repo → 0. |
| TC-AUTH-25 | PAN 15 цифр → 400 | что `@Valid` на `authorize` включён и нарушение превращается в 400 | api | одна проверка подключения валидации на эндпоинт. Сами правила `AuthorizationRequest` проверяются unit-тестом TC-P3-20 |
| TC-AUTH-26 | Две параллельные авторизации | гонку за баланс: ровно одна APPROVED, баланс ≥ 0 | integration | защита — `SELECT … FOR UPDATE` в CMS и атомарный upsert; нужны реальные БД и конкурентные HTTP-вызовы |
| TC-AUTH-27 | Reversal | что `AuthServiceImpl.rollback` вызывает откат в CMS и уменьшает `limit_usage` | unit | mock `CardManagementClient` и `LimitUsageRepository`, `verify` обоих вызовов. Возврат баланса — сторона CMS, покрыт TC-P3-10 (api); сквозной откат — TC-P3-16 (integration) |
| TC-AUTH-28 | amount = −1 → 400 | правило `@PositiveOrZero` в `AuthorizationRequest` | unit | `Validator.validate(request)` → 1 нарушение на `amount`. Подключение `@Valid` проверяет TC-AUTH-25 |
| TC-AUTH-29 | Удалённая карта → 14 | soft delete в CMS (`@SQLRestriction`) + маппинг 404 → 14 в Authorization | integration | взаимодействие двух сервисов через состояние карты в БД CMS |

### 2.2. Card Management (TC-CMS-01…33)

| ID (пр. 2) | Название | Что проверяет тест | Уровень | Обоснование |
|---|---|---|:---:|---|
| TC-CMS-01 | Health-check | контракт `GET /health`, поле `cardsInDatabase` | api | ответ эндпоинта |
| TC-CMS-02 | Создание карты | ответ 201 и тело: PAN 16 цифр с BIN, Луна, `expiryDate` + 3 года, `ACTIVE` | api | кейс сформулирован как контракт `POST /api/cards`. Алгоритм Луна отдельно — unit `LuhnValidator` |
| TC-CMS-03 | BIN 5 цифр → 400 | что `@Valid` на `POST /api/cards` включён и нарушение превращается в 400 с `ErrorResponse` | api | представитель `CreateCardRequest` для проверки подключения валидации. Остальные правила DTO (TC-CMS-04…12) — unit |
| TC-CMS-04 | BIN 7 цифр → 400 | правило `@Bin`, OFF-граница сверху | unit | правило аннотации на DTO проверяется `jakarta.validation.Validator` без Spring (см. TC-P3-20); то, что `@Valid` включён в контроллере, проверяет TC-CMS-03 (api) |
| TC-CMS-05 | BIN нецифровой → 400 | правило `@Bin`, невалидный класс | unit | правило аннотации на DTO проверяется `jakarta.validation.Validator` без Spring (см. TC-P3-20); то, что `@Valid` включён в контроллере, проверяет TC-CMS-03 (api) |
| TC-CMS-06 | Пустое имя → 400 | `@NotBlank` на `cardholderName` | unit | правило аннотации на DTO проверяется `jakarta.validation.Validator` без Spring (см. TC-P3-20); то, что `@Valid` включён в контроллере, проверяет TC-CMS-03 (api) |
| TC-CMS-07 | Строчные буквы → 400 | `@Pattern` на `cardholderName` | unit | правило аннотации на DTO проверяется `jakarta.validation.Validator` без Spring (см. TC-P3-20); то, что `@Valid` включён в контроллере, проверяет TC-CMS-03 (api) |
| TC-CMS-08 | Цифры в имени → 400 | то же | unit | правило аннотации на DTO проверяется `jakarta.validation.Validator` без Spring (см. TC-P3-20); то, что `@Valid` включён в контроллере, проверяет TC-CMS-03 (api) |
| TC-CMS-09 | currencyCode 2 символа → 400 | `@ExactSize(3)` | unit | правило аннотации на DTO проверяется `jakarta.validation.Validator` без Spring (см. TC-P3-20); то, что `@Valid` включён в контроллере, проверяет TC-CMS-03 (api) |
| TC-CMS-10 | dailyLimit −1 → 400 | `@NotNegative` | unit | правило аннотации на DTO проверяется `jakarta.validation.Validator` без Spring (см. TC-P3-20); то, что `@Valid` включён в контроллере, проверяет TC-CMS-03 (api) |
| TC-CMS-11 | Нулевые лимиты и баланс → 201 | ON-границу `0`: нарушений нет | unit | правило аннотации на DTO проверяется `jakarta.validation.Validator` без Spring (см. TC-P3-20); то, что `@Valid` включён в контроллере, проверяет TC-CMS-03 (api) |
| TC-CMS-12 | initialBalance −1 → 400 | наличие ограничения на `initialBalance` | unit | `Validator` не найдёт нарушения: на поле в `CreateCardRequest` нет ни одной аннотации, поэтому тест упадёт. Это дешёвый способ поймать дефект без поднятия сервиса |
| TC-CMS-13 | GET по PAN | контракт тела `CardModel` | api | эндпоинт + сериализация |
| TC-CMS-14 | GET несуществующего → 404 | `CardNotFoundException` → 404 в `GlobalExceptionHandler` | api | маппинг исключений — часть контракта |
| TC-CMS-15 | GET PAN 15 цифр → 400 | `@Pan` на path-переменной | api | контракт |
| TC-CMS-16 | `limit = 1` | пагинацию: в выдаче ровно одна карта | api | параметр запроса → ответ; данные из своей БД сервиса |
| TC-CMS-17 | `limit = 0` → 400 | `@Positive` | api | контракт |
| TC-CMS-18 | `limit = 10001` → 400 | верхнюю границу `max-page-limit`. | api | маппинг исключения в статус |
| TC-CMS-19 | `offset = −1` → 400 | `@NotNegative` | api | контракт |
| TC-CMS-20 | Фильтр `status = BLOCKED` | корректность Criteria-запроса по статусу | integration | фильтр строится в `CardCriteriaBuilderJpaRepositoryImpl` и выполняется в SQL; нужен PostgreSQL с набором карт разных статусов |
| TC-CMS-21 | PATCH BLOCKED → авторизация отклонена | сквозное следствие смены состояния карты для транзакции | e2e | бизнес-сценарий через два сервиса и вход системы; в практике 3 развёрнут в TC-P3-18 |
| TC-CMS-22 | PATCH dailyLimit −1 → 400 | валидацию PATCH | api | контракт |
| TC-CMS-23 | DELETE → 404 и нет в списке | soft delete виден через API | api | проверяется внешнее поведение эндпоинтов; механизм `@SQLRestriction` прозрачен |
| TC-CMS-24 | Генерация 100 карт | алгоритм: 20 на BIN, статусы ≈ 95/3/2, PAN по Луне | unit | распределение и генерацию PAN считает `CardGeneratorService` / `PanGenerator` детерминированно, без БД; это быстрее и точнее, чем через API. Уникальность PAN в БД покрыта индексом `uk_cards_pan` |
| TC-CMS-25 | `count = 0` → 400 | `@Min(1)` | api | контракт |
| TC-CMS-26 | `count = 1` → 201 | ON-границу генератора | api | контракт |
| TC-CMS-27 | `count = 10001` → 400 | верхнюю границу `max-count` | api | контракт (`CardGenerationLimitException` → 400) |
| TC-CMS-28 | reserve = баланс | ON-границу резервирования: баланс становится 0 | api | эндпоинт `/reserve` своего сервиса; результат виден в ответе |
| TC-CMS-29 | reserve = баланс + 1 | OFF-границу: 402, баланс не изменился | api | контракт `InsufficientFundsException` → 402 |
| TC-CMS-30 | reserve amount = 0 → 400 | что `@Valid` на `/reserve` включён, `@Positive` → 400 | api | представитель `ReserveRequest` для проверки подключения валидации |
| TC-CMS-31 | rrn 5 символов → 400 | правило `@Rrn` в `ReserveRequest` | unit | `Validator` без Spring; подключение `@Valid` на `/reserve` проверяет TC-CMS-30 (api) |
| TC-CMS-32 | Повтор того же rrn | идемпотентность резервирования: 409, повторного списания нет | api | наблюдается через ответ эндпоинта (409) и `GET` баланса |
| TC-CMS-33 | Публикация события в outbox | что событие ушло в `smp.card-events` с ключом `card.*` | integration | связь с настоящим брокером; в практике 3 развёрнут в TC-P3-14 |

---

## 3. Анализ пропорций (часть г)

### 3.1. Сводная таблица

| Уровень | Старые (пр. 2) | Новые (пр. 3) | Итого | Доля |
|---|---:|---:|---:|---:|
| unit | 26 | 10 | 36 | 43,4 % |
| api / integration | 35 | 9 | 44 | 53,0 % |
| — в т. ч. api | 24 | 4 | 28 | 33,7 % |
| — в т. ч. integration | 11 | 5 | 16 | 19,3 % |
| e2e | 1 | 2 | 3 | 3,6 % |
| **Итого** | **62** | **21** | **83** | 100 % |

Пары `PW-A-*` и `PW-C-*` не считаются отдельно. Это параметризация уже учтённых ТК: PW-A-\* — unit-тест `AuthServiceImpl` с `@ParameterizedTest`, PW-C-\* — один api-тест `POST /api/cards` с набором данных.

### 3.2. Почему пирамида такая

Получилось 43 / 53 / 4 вместо ориентира 70 / 20 / 10: средний слой толще, unit и e2e тоньше.

- **Риск СМП сосредоточен на стыках.** Пять сервисов связаны HTTP-контрактами и двумя асинхронными цепочками через RabbitMQ. Каждая такая связь проверяется api- или integration-тестом, а не unit.
- **Часть логики живёт в SQL.** Дневной и месячный лимиты считаются в native-запросе `upsertLimitUsage` (`INSERT … ON CONFLICT … WHERE`). Mock репозитория вернёт то, что ему сказали, поэтому TC-AUTH-09…14 и TC-P3-12 могут быть только интеграционными.
- **Card Management — в основном CRUD.** Чистой логики мало: инварианты `Card` / `Reservation`, генератор, алгоритм Луна. Всё это уже вынесено на unit.
- **E2E — 3 кейса,** перекоса в «рожок мороженого» нет. Каждый сценарий Sequence (happy path, declined, reversal) разложен на нижние уровни, сквозными оставлены только happy path и declined. Reversal целиком в e2e не вынесен: остановка брокера внутри полного стенда сделала бы тест нестабильным, поэтому он проверяется на integration (TC-P3-16) и unit (TC-P3-04).
- **Дефицита контрактных проверок нет.** У каждой синхронной связи на схеме A2 есть хотя бы один api-кейс.
