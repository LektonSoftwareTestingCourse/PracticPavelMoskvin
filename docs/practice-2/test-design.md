# Test-design ядра СМП: Authorization и Card Management

---

## 1. Область и источники

Документ описывает применение трёх техник тест-дизайна — **классов эквивалентности**, **граничных значений** и **попарного тестирования** — к ядру СМП: сервисам Authorization и Card Management. Выбор ядра обоснован в [test-strategy.md, раздел 4](test-strategy.md#4-приоритизация-ядро-и-периферия): здесь сосредоточены риски R-01…R-05 и R-09.

Источники требований:

| Источник | Что взято |
|---|---|
| [`tz/04-authorization.md`](../../tz/04-authorization.md) | Алгоритм проверок 1–7, decline-сценарии, резервирование, `limit_usage`, RRN/authCode |
| [`tz/05-card-management.md`](../../tz/05-card-management.md) | CRUD, генератор карт, алгоритм Луна, резервирование, статусная модель |
| [`docs/api-spec.md`](../api-spec.md) | Контракт REST, модели данных, коды ответов |
| [`docker-compose.yaml`](../../docker-compose.yaml), конфигурации сервисов | Значения по умолчанию и предельные значения параметров |

Обозначения: `REQ-*` — требование, `EC-*` — класс эквивалентности, `BV-*` — граница, `PW-*` — строка попарного набора, `TC-*` — тест-кейс.

## 2. Требования

### 2.1. Authorization Service

| ID | Требование | Источник |
|---|---|---|
| REQ-AUTH-01 | `GET /health` возвращает 200 и состояние зависимости `cardManagement` | tz/04 §1 |
| REQ-AUTH-02 | `POST /api/internal/authorize` принимает `AuthorizationRequest` и возвращает `AuthorizationResponse` | tz/04 §2 |
| REQ-AUTH-03 | issuerId обогащается через Bin Lookup; при недоступности — graceful degradation (таблица BIN Switch) | tz/04 §2 |
| REQ-AUTH-04 | Карта не найдена → DECLINED, `responseCode = 14` | tz/04 §2, шаг 1 |
| REQ-AUTH-05 | Статус `INACTIVE` → DECLINED, `declineReason = CARD_INACTIVE` | tz/04 §2, шаг 2 |
| REQ-AUTH-06 | Статус `BLOCKED` → DECLINED, `declineReason = CARD_BLOCKED` | tz/04 §2, шаг 2 |
| REQ-AUTH-07 | Статус `EXPIRED` → DECLINED, `responseCode = 54` | tz/04 §2, шаг 2 |
| REQ-AUTH-08 | `expiryDate` (MMYY) раньше текущей даты → DECLINED, `responseCode = 54` | tz/04 §2, шаг 3 |
| REQ-AUTH-09 | Накопленная сумма за день + текущая ≤ `dailyLimit`; превышение → `responseCode = 61` | tz/04 §2, шаг 4 |
| REQ-AUTH-10 | Аналогично за месяц → `responseCode = 61` | tz/04 §2, шаг 5 |
| REQ-AUTH-11 | `amount ≤ availableBalance`; иначе → `responseCode = 51` | tz/04 §2, шаг 6 |
| REQ-AUTH-12 | Все проверки пройдены → APPROVED, `responseCode = 00`, выдаются RRN и authCode | tz/04 §2, шаг 7 |
| REQ-AUTH-13 | При APPROVED средства резервируются через `POST /api/cards/{pan}/reserve` | tz/04 §3 |
| REQ-AUTH-14 | Использованные лимиты учитываются в таблице `limit_usage` (день и месяц) | tz/04 §4 |
| REQ-AUTH-15 | Card Management недоступен → DECLINED, `responseCode = 05`, причина `ISSUER_TIMEOUT` | tz/04 §5 |
| REQ-AUTH-16 | RRN — 12 цифр, уникален в рамках системы | tz/04 §6 |
| REQ-AUTH-17 | authCode — 6 символов из `A-Z0-9` | tz/04 §6 |
| REQ-AUTH-18 | Проверки выполняются строго в порядке 1–7 | tz/04 §2 |
| REQ-AUTH-19 | Reversal (`mti = 0400`) возвращает сумму на баланс и снимает запись из `limit_usage` | tz/04, доп. задания |

### 2.2. Card Management Service

| ID | Требование | Источник |
|---|---|---|
| REQ-CMS-01 | `GET /health` возвращает 200 и `cardsInDatabase` | tz/05 §1 |
| REQ-CMS-02 | `POST /api/cards` создаёт карту: PAN = BIN + 9 цифр + контрольная, `expiryDate` = +3 года, `status = ACTIVE` | tz/05 §2 |
| REQ-CMS-03 | `GET /api/cards/{pan}` возвращает карту; если не найдена — 404 | tz/05 §2 |
| REQ-CMS-04 | `GET /api/cards` поддерживает `limit`, `offset`, фильтры `status` и `bin`, возвращает `total` | tz/05 §2 |
| REQ-CMS-05 | `PATCH /api/cards/{pan}` частично обновляет статус, лимиты, баланс | tz/05 §2 |
| REQ-CMS-06 | `DELETE /api/cards/{pan}` — мягкое удаление: `status = DELETED`, карта исчезает из выдач и не участвует в транзакциях | tz/05 §2 |
| REQ-CMS-07 | `POST /api/cards/generate` создаёт `count` карт, равномерно по BIN, со статусами ACTIVE 95 % / INACTIVE 3 % / BLOCKED 2 % | tz/05 §3 |
| REQ-CMS-08 | PAN проходит проверку по алгоритму Луна | tz/05 §4 |
| REQ-CMS-09 | `POST /api/cards/{pan}/reserve` уменьшает `availableBalance` на `amount` | tz/05 §5 |
| REQ-CMS-10 | Карточные события публикуются через outbox в exchange `smp.card-events` | tz/05, асинхронная часть |

## 3. Классы эквивалентности

Правила выделения: позитивные и негативные классы разделяются; каждый невалидный класс получает **отдельный** тест-кейс — смешивать невалидные значения нельзя, иначе неизвестно, какая проверка сработала. Несколько валидных значений допускается объединять в один кейс.

### 3.1. Authorization

| ID | Параметр | Валидные классы | Невалидные классы |
|---|---|---|---|
| EC-A-01 | `card.status` | `ACTIVE` | `INACTIVE` → CARD_INACTIVE; `BLOCKED` → CARD_BLOCKED; `EXPIRED` → 54; `DELETED` → карта не видна, 14 |
| EC-A-02 | `pan` | 16 цифр, карта существует | 16 цифр, карты нет → 14; длина ≠ 16 → 400; нецифровые символы → 400 |
| EC-A-03 | `expiryDate` относительно даты транзакции | месяц позже текущего; месяц равен текущему | месяц раньше текущего → 54 |
| EC-A-04 | `amount` относительно остатка дневного лимита | `amount ≤ dailyLimit − daily_amount` | `amount > dailyLimit − daily_amount` → 61 |
| EC-A-05 | `amount` относительно остатка месячного лимита | `amount ≤ monthlyLimit − monthly_amount` | `amount > monthlyLimit − monthly_amount` → 61 |
| EC-A-06 | `amount` относительно баланса | `amount ≤ availableBalance` | `amount > availableBalance` → 51 |
| EC-A-07 | `amount` как значение | положительное число | отрицательное → 400 (валидация `@PositiveOrZero`); ноль — поведение не определено ТЗ |
| EC-A-08 | `currencyCode` | ровно 3 цифры (`643`, `840`) | длина ≠ 3 → 400; нецифровые → 400 |
| EC-A-09 | `mti` | `0100` (авторизация), `0400` (reversal) | иные 4 символа → обработка не определена; длина ≠ 4 → 400 |
| EC-A-10 | Доступность Card Management | доступен | недоступен → 05 / `ISSUER_TIMEOUT` |
| EC-A-11 | Доступность Bin Lookup | доступен | недоступен → авторизация продолжается (graceful degradation) |
| EC-A-12 | `terminalType` | `POS`, `ATM`, `ECOM` — на решение не влияют | — (значение не участвует в правилах) |
| EC-A-13 | `mcc` | любой 4-значный код — на решение не влияет | длина ≠ 4 → 400 |

`terminalType` и `mcc` включены в модель попарного тестирования именно потому, что **не должны** влиять на решение: попарный набор проверяет отсутствие незапланированной зависимости.

### 3.2. Card Management

| ID | Параметр | Валидные классы | Невалидные классы |
|---|---|---|---|
| EC-C-01 | `bin` | ровно 6 цифр | 5 цифр → 400; 7 цифр → 400; нецифровые → 400; пустой → 400 |
| EC-C-02 | `cardholderName` | непустая строка из `A-Z`, пробела, `-`, `.`, `'` | пустая → 400; строчные буквы → 400; цифры → 400 |
| EC-C-03 | `currencyCode` | ровно 3 цифры | длина ≠ 3 → 400; нецифровые → 400 |
| EC-C-04 | `dailyLimit` | ≥ 0 | < 0 → 400 |
| EC-C-05 | `monthlyLimit` | ≥ 0 | < 0 → 400 |
| EC-C-06 | `initialBalance` | ≥ 0 | < 0 → ожидается 400 (в ТЗ явно не задано) |
| EC-C-07 | `limit` (пагинация) | 1…10000 | 0 → 400; отрицательный → 400; > 10000 → 400 |
| EC-C-08 | `offset` (пагинация) | ≥ 0 | < 0 → 400 |
| EC-C-09 | `status` (фильтр) | `ACTIVE`, `INACTIVE`, `BLOCKED`, `EXPIRED` | несуществующее значение → 400; `DELETED` — значение вне перечисления модели `Card` в контракте |
| EC-C-10 | `count` (генератор) | 1…10000 | 0 → 400; отрицательный → 400; > 10000 → 400 |
| EC-C-11 | `amount` (резервирование) | 0 < `amount` ≤ `availableBalance` | 0 → 400; отрицательный → 400; > баланса → отказ |
| EC-C-12 | `rrn` (резервирование) | 12 цифр | пустой → 400; длина ≠ 12 → 400 |
| EC-C-13 | PAN в пути запроса | 16 цифр, карта существует | карта отсутствует или удалена → 404; длина ≠ 16 → 400 |

## 4. Граничные значения

Для каждой границы берутся три точки: **ниже границы**, **ON** — ровно на границе, **OFF** — за границей. Базовая карта для примеров: `dailyLimit = 15 000 000` коп., `monthlyLimit = 300 000 000` коп., `availableBalance = 100 000 000` коп., `daily_amount = 0`, `monthly_amount = 0`. Текущая дата в примерах — сентябрь 2026 (`0926`).

### 4.1. Authorization

| ID | Граница | Ниже | ON (граница) | OFF (за границей) | Ожидаемое поведение |
|---|---|---|---|---|---|
| BV-A-01 | `dailyLimit` при нулевом использовании | 14 999 999 → APPROVED | 15 000 000 → APPROVED | 15 000 001 → DECLINED 61 | Сравнение `≤` |
| BV-A-02 | `dailyLimit` с накопленным использованием (`daily_amount = 10 000 000`) | 4 999 999 → APPROVED | 5 000 000 → APPROVED | 5 000 001 → DECLINED 61 | Учитывается остаток, а не полный лимит |
| BV-A-03 | `monthlyLimit` (`monthly_amount = 299 000 000`) | 999 999 → APPROVED | 1 000 000 → APPROVED | 1 000 001 → DECLINED 61 | Месячный лимит проверяется отдельно от дневного |
| BV-A-04 | `availableBalance` | 99 999 999 → APPROVED | 100 000 000 → APPROVED, баланс становится 0 | 100 000 001 → DECLINED 51 | Сравнение `≤` |
| BV-A-05 | `expiryDate` (MMYY) относительно даты транзакции | `1026` (следующий месяц) → APPROVED | `0926` (текущий месяц) → APPROVED: карта действительна до конца месяца | `0826` (прошлый месяц) → DECLINED 54 | Граница — последний день месяца expiry |
| BV-A-06 | `amount` как значение | −1 → 400 | 0 → поведение не определено ТЗ | 1 → APPROVED | Валидация `@PositiveOrZero` |
| BV-A-07 | Длина `pan` | 15 символов → 400 | 16 символов → обрабатывается | 17 символов → 400 | `@ExactSize(16)` |
| BV-A-08 | Длина `mcc` | 3 символа → 400 | 4 символа → обрабатывается | 5 символов → 400 | `@ExactSize(4)` |
| BV-A-09 | Длина RRN в ответе | — | ровно 12 цифр | 11 или 13 → дефект | REQ-AUTH-16 |
| BV-A-10 | Длина authCode | — | ровно 6 символов `A-Z0-9` | иная длина или иной алфавит → дефект | REQ-AUTH-17 |

### 4.2. Card Management

| ID | Граница | Ниже | ON (граница) | OFF (за границей) | Ожидаемое поведение |
|---|---|---|---|---|---|
| BV-C-01 | Длина `bin` | 5 цифр → 400 | 6 цифр → 201 | 7 цифр → 400 | `@Bin` |
| BV-C-02 | Длина `currencyCode` | 2 символа → 400 | 3 цифры → 201 | 4 символа → 400 | `@ExactSize(3)` + только цифры |
| BV-C-03 | `dailyLimit` / `monthlyLimit` | −1 → 400 | 0 → 201 | 1 → 201 | `@NotNegative` |
| BV-C-04 | `initialBalance` | −1 → ожидается 400 | 0 → 201 | 1 → 201 | — |
| BV-C-05 | `limit` пагинации | 0 → 400 | 1 → 200 (одна карта) | — | `@Positive` |
| BV-C-06 | `limit` пагинации, верхняя граница | 9 999 → 200 | 10 000 → 200 | 10 001 → 400 | `max-page-limit = 10000` |
| BV-C-07 | `offset` пагинации | −1 → 400 | 0 → 200 | 1 → 200 со сдвигом | `@NotNegative` |
| BV-C-08 | `count` генератора, нижняя граница | 0 → 400 | 1 → 201, карта со статусом `ACTIVE` | 2 → 201 | `@Min(1)`; распределение статусов при малом `count` в ТЗ не уточнено |
| BV-C-09 | `count` генератора, верхняя граница | 9 999 → 201 | 10 000 → 201 | 10 001 → 400 | `max-count = 10000` |
| BV-C-10 | `amount` резервирования | 0 → 400 | 1 → 200 | — | `@Positive` |
| BV-C-11 | `amount` резервирования относительно баланса | `balance − 1` → 200 | `balance` → 200, остаток 0 | `balance + 1` → отказ, баланс не изменился | Баланс не уходит в минус |
| BV-C-12 | Длина `rrn` | 11 цифр → 400 | 12 цифр → 200 | 13 цифр → 400 | `@Rrn` |
| BV-C-13 | Длина `pan` в пути | 15 → 400 | 16 → 200 / 404 | 17 → 400 | `@Pan` |

## 5. Попарное тестирование

### 5.1. Зачем и как

Решение авторизации зависит сразу от восьми факторов. Полный перебор — 7776 комбинаций; проверить их вручную невозможно, а произвольная выборка не гарантирует ничего. Попарное тестирование даёт набор, в котором **каждая пара значений любых двух параметров встречается хотя бы один раз**, — этого достаточно, чтобы обнаружить взаимодействия первого порядка.

Модель: [`pict/model-auth.txt`](pict/model-auth.txt) (Authorization) и [`pict/model-cms.txt`](pict/model-cms.txt) (Card Management). Наборы: [`pict/cases-auth.txt`](pict/cases-auth.txt), [`pict/cases-cms.txt`](pict/cases-cms.txt).

### 5.2. Модель Authorization

```text
card_status:        ACTIVE, INACTIVE, BLOCKED, EXPIRED
expiry:             valid, current_month, expired
amount_vs_daily:    below, equal, above
amount_vs_monthly:  below, equal, above
amount_vs_balance:  below, equal, above
terminal_type:      POS, ATM, ECOM
mcc:                5411, 5812, 5732, 4722
cms_availability:   up, down
```

Значения заданы не абсолютными числами, а отношением к границе: `below` — ниже границы, `equal` — точка ON, `above` — точка OFF. Так попарный набор наследует результаты анализа граничных значений из раздела 4, а не дублирует его.

**Ограничения модели** (без них генератор выдаёт недостижимые строки):

| ID | Ограничение | Обоснование |
|---|---|---|
| C1 | `IF [card_status] <> "ACTIVE" THEN [amount_vs_daily] = "below" AND [amount_vs_monthly] = "below" AND [amount_vs_balance] = "below"` | Алгоритм прерывается на шаге 2: до проверки лимитов и баланса дело не доходит |
| C2 | `IF [card_status] = "EXPIRED" THEN [expiry] = "expired"` | Согласованность данных. Обратное **не** запрещено: `ACTIVE` с истёкшим `expiryDate` — важный случай шага 3 |
| C3 | `IF [expiry] = "expired" THEN [amount_vs_balance] = "below"` | Отказ происходит на шаге 3, варьировать баланс бессмысленно |
| C4 | `IF [cms_availability] = "down" THEN [card_status] = "ACTIVE" AND [expiry] = "valid" AND` все три суммы `= "below"` | Данные карты не получены вовсе — остальные параметры на исход не влияют |

### 5.3. Результат генерации

| Показатель | Authorization | Card Management |
|---|---:|---:|
| Параметров в модели | 8 | 6 |
| Полный перебор | 7776 комбинаций | 1728 комбинаций |
| Разрешено ограничениями | 852 | 27 |
| Достижимых пар значений | 239 | 96 |
| Покрыто пар набором | **239 (100 %)** | **96 (100 %)** |
| Строк в наборе | **29** | **17** |

Сокращение для Authorization — в 268 раз относительно полного перебора при сохранении полного попарного покрытия.

### 5.4. Маппинг попарного набора в тест-кейсы: Authorization

Каждая строка набора — отдельный тест-кейс. Ожидаемый результат вычислен по порядку проверок из REQ-AUTH-18 (шаги 1→7).

| ID | card_status | expiry | vs_daily | vs_monthly | vs_balance | terminal | mcc | cms | status | code | declineReason |
|---|---|---|---|---|---|---|---|---|---|---|---|
| PW-A-01 | ACTIVE | valid | below | below | below | POS | 5411 | up | APPROVED | 00 | — |
| PW-A-02 | ACTIVE | current_month | equal | equal | equal | ATM | 5812 | up | APPROVED | 00 | — |
| PW-A-03 | ACTIVE | valid | above | above | above | ECOM | 5732 | up | DECLINED | 61 | EXCEEDS_AMOUNT_LIMIT |
| PW-A-04 | INACTIVE | expired | below | below | below | ATM | 4722 | up | DECLINED | 05 | CARD_INACTIVE |
| PW-A-05 | BLOCKED | current_month | below | below | below | ECOM | 5812 | up | DECLINED | 05 | CARD_BLOCKED |
| PW-A-06 | ACTIVE | current_month | equal | above | above | POS | 4722 | up | DECLINED | 61 | EXCEEDS_AMOUNT_LIMIT |
| PW-A-07 | ACTIVE | expired | above | equal | below | POS | 5732 | up | DECLINED | 54 | CARD_EXPIRED |
| PW-A-08 | ACTIVE | valid | below | below | below | ATM | 5732 | **down** | DECLINED | 05 | ISSUER_TIMEOUT |
| PW-A-09 | ACTIVE | valid | below | equal | equal | ECOM | 4722 | up | APPROVED | 00 | — |
| PW-A-10 | ACTIVE | current_month | above | above | equal | ATM | 5411 | up | DECLINED | 61 | EXCEEDS_AMOUNT_LIMIT |
| PW-A-11 | EXPIRED | expired | below | below | below | ECOM | 5411 | up | DECLINED | 54 | CARD_EXPIRED |
| PW-A-12 | ACTIVE | expired | equal | above | below | POS | 5812 | up | DECLINED | 54 | CARD_EXPIRED |
| PW-A-13 | ACTIVE | valid | equal | below | equal | POS | 5732 | up | APPROVED | 00 | — |
| PW-A-14 | ACTIVE | valid | above | below | above | ATM | 5812 | up | DECLINED | 61 | EXCEEDS_AMOUNT_LIMIT |
| PW-A-15 | ACTIVE | valid | equal | equal | above | ECOM | 5411 | up | DECLINED | 51 | INSUFFICIENT_FUNDS |
| PW-A-16 | INACTIVE | current_month | below | below | below | POS | 5732 | up | DECLINED | 05 | CARD_INACTIVE |
| PW-A-17 | INACTIVE | valid | below | below | below | ECOM | 5411 | up | DECLINED | 05 | CARD_INACTIVE |
| PW-A-18 | BLOCKED | valid | below | below | below | POS | 5411 | up | DECLINED | 05 | CARD_BLOCKED |
| PW-A-19 | BLOCKED | expired | below | below | below | ATM | 5732 | up | DECLINED | 05 | CARD_BLOCKED |
| PW-A-20 | ACTIVE | valid | below | below | below | POS | 5411 | **down** | DECLINED | 05 | ISSUER_TIMEOUT |
| PW-A-21 | ACTIVE | valid | below | below | below | ECOM | 5812 | **down** | DECLINED | 05 | ISSUER_TIMEOUT |
| PW-A-22 | ACTIVE | valid | below | above | above | POS | 5411 | up | DECLINED | 61 | EXCEEDS_AMOUNT_LIMIT |
| PW-A-23 | EXPIRED | expired | below | below | below | POS | 5812 | up | DECLINED | 54 | CARD_EXPIRED |
| PW-A-24 | EXPIRED | expired | below | below | below | ATM | 5732 | up | DECLINED | 54 | CARD_EXPIRED |
| PW-A-25 | ACTIVE | valid | below | below | below | POS | 4722 | **down** | DECLINED | 05 | ISSUER_TIMEOUT |
| PW-A-26 | ACTIVE | valid | above | below | below | POS | 4722 | up | DECLINED | 61 | EXCEEDS_AMOUNT_LIMIT |
| PW-A-27 | INACTIVE | valid | below | below | below | POS | 5812 | up | DECLINED | 05 | CARD_INACTIVE |
| PW-A-28 | BLOCKED | valid | below | below | below | POS | 4722 | up | DECLINED | 05 | CARD_BLOCKED |
| PW-A-29 | EXPIRED | expired | below | below | below | POS | 4722 | up | DECLINED | 54 | CARD_EXPIRED |

Строки PW-A-03, PW-A-06, PW-A-14, PW-A-22 особенно ценны: в них нарушено сразу несколько правил, и кейс проверяет не только факт отказа, но и **приоритет** проверок (REQ-AUTH-18) — ожидается код первой сработавшей проверки, а не любой из подходящих.

### 5.5. Маппинг попарного набора в тест-кейсы: Card Management

Модель `POST /api/cards`. Ограничения C1–C6 реализуют правило «не более одного невалидного значения в кейсе»: иначе по коду 400 невозможно понять, какая валидация сработала.

| ID | bin | cardholder_name | currency_code | daily_limit | monthly_limit | initial_balance | HTTP | Комментарий |
|---|---|---|---|---|---|---|:---:|---|
| PW-C-01 | valid_6 | valid_upper | valid_643 | positive | positive | positive | 201 | базовый позитивный кейс |
| PW-C-02 | valid_6 | valid_upper | valid_840 | zero | zero | zero | 201 | все валидные границы `0` одновременно |
| PW-C-03 | valid_6 | valid_upper | valid_643 | positive | positive | negative | 400 | `initialBalance` отрицательный |
| PW-C-04 | valid_6 | valid_upper | valid_643 | positive | negative | positive | 400 | `monthlyLimit` отрицательный |
| PW-C-05 | valid_6 | valid_upper | valid_643 | negative | positive | positive | 400 | `dailyLimit` отрицательный |
| PW-C-06 | valid_6 | valid_upper | wrong_length | positive | positive | positive | 400 | `currencyCode` не 3 символа |
| PW-C-07 | valid_6 | valid_upper | non_numeric | positive | positive | positive | 400 | `currencyCode` нецифровой |
| PW-C-08 | valid_6 | lowercase | valid_643 | positive | positive | positive | 400 | имя строчными буквами |
| PW-C-09 | valid_6 | empty | valid_643 | positive | positive | positive | 400 | пустое имя |
| PW-C-10 | valid_6 | with_digits | valid_643 | positive | positive | positive | 400 | цифры в имени |
| PW-C-11 | short_5 | valid_upper | valid_643 | positive | positive | positive | 400 | BIN 5 цифр |
| PW-C-12 | long_7 | valid_upper | valid_643 | positive | positive | positive | 400 | BIN 7 цифр |
| PW-C-13 | non_numeric | valid_upper | valid_643 | positive | positive | positive | 400 | BIN нецифровой |
| PW-C-14 | valid_6 | valid_upper | valid_643 | positive | zero | zero | 201 | комбинация нулевых границ |
| PW-C-15 | valid_6 | valid_upper | valid_840 | zero | positive | positive | 201 | валюта 840 с нулевым дневным лимитом |
| PW-C-16 | valid_6 | valid_upper | valid_643 | zero | positive | zero | 201 | нулевой лимит и нулевой баланс |
| PW-C-17 | valid_6 | valid_upper | valid_840 | positive | zero | positive | 201 | нулевой месячный лимит |

### 5.6. Ограничения техники

Покрытие всех пар **не** доказывает полноту: взаимодействия трёх и более параметров остаются непроверенными. Критичные тройки, известные из реестра рисков, добавлены в набор вручную отдельными тест-кейсами:

| Дополнение | Почему попарного набора мало |
|---|---|
| TC-AUTH-24 — превышены одновременно дневной лимит, месячный лимит и баланс | Проверяется приоритет трёх правил сразу (R-07) |
| TC-AUTH-21 — накопление `limit_usage` тремя последовательными транзакциями | Состояние между вызовами — вне модели, параметры модели описывают один запрос |
| TC-AUTH-26 — две параллельные авторизации на одну карту | Конкурентность (R-05) не выражается комбинацией значений |
| TC-CMS-20 — распределение статусов при генерации 100 карт | Статистическое свойство набора, а не отдельного запроса |

## 6. Тест-кейсы

Каждый кейс содержит идентификатор, связанное требование, вид (позитивный/негативный) и источник — класс эквивалентности, границу или строку попарного набора.

### 6.1. Authorization

| ID | Требование | Вид | Источник | Предусловие | Шаги | Ожидаемый результат |
|---|---|:---:|---|---|---|---|
| TC-AUTH-01 | REQ-AUTH-01 | поз. | EC-A-10 | Сервис запущен | `GET :8083/health` | 200, `status = ok`, `dependencies.cardManagement = ok` |
| TC-AUTH-02 | REQ-AUTH-02, 12 | поз. | EC-A-01, PW-A-01 | Карта `ACTIVE`, лимиты и баланс с запасом | `POST /api/internal/authorize`, `amount = 100 000` | 200, `status = APPROVED`, `responseCode = 00`, есть `rrn` и `authCode` |
| TC-AUTH-03 | REQ-AUTH-04 | нег. | EC-A-02 | PAN 16 цифр, карты в БД нет | Тот же запрос с несуществующим PAN | `DECLINED`, `responseCode = 14` |
| TC-AUTH-04 | REQ-AUTH-05 | нег. | EC-A-01, PW-A-17 | Карта переведена в `INACTIVE` через `PATCH` | Запрос авторизации | `DECLINED`, `declineReason = CARD_INACTIVE` |
| TC-AUTH-05 | REQ-AUTH-06 | нег. | EC-A-01, PW-A-18 | Карта `BLOCKED` | Запрос авторизации | `DECLINED`, `declineReason = CARD_BLOCKED` |
| TC-AUTH-06 | REQ-AUTH-07 | нег. | EC-A-01, PW-A-11 | Карта `EXPIRED` | Запрос авторизации | `DECLINED`, `responseCode = 54` |
| TC-AUTH-07 | REQ-AUTH-08 | нег. | BV-A-05 OFF | Карта `ACTIVE`, `expiryDate = 0826` (прошлый месяц) | Запрос авторизации | `DECLINED`, `responseCode = 54` |
| TC-AUTH-08 | REQ-AUTH-08 | поз. | BV-A-05 ON | Карта `ACTIVE`, `expiryDate = 0926` (текущий месяц) | Запрос авторизации | `APPROVED` — карта действительна до конца месяца |
| TC-AUTH-09 | REQ-AUTH-09 | поз. | BV-A-01 ON | `dailyLimit = 15 000 000`, `daily_amount = 0` | `amount = 15 000 000` | `APPROVED`, `responseCode = 00` |
| TC-AUTH-10 | REQ-AUTH-09 | нег. | BV-A-01 OFF | То же | `amount = 15 000 001` | `DECLINED`, `responseCode = 61` |
| TC-AUTH-11 | REQ-AUTH-09, 14 | поз. | BV-A-02 ON | `daily_amount = 10 000 000` после первой транзакции | `amount = 5 000 000` | `APPROVED`; учитывается остаток лимита |
| TC-AUTH-12 | REQ-AUTH-09, 14 | нег. | BV-A-02 OFF | То же | `amount = 5 000 001` | `DECLINED`, `responseCode = 61` |
| TC-AUTH-13 | REQ-AUTH-10 | поз. | BV-A-03 ON | `monthly_amount = 299 000 000` | `amount = 1 000 000` | `APPROVED` |
| TC-AUTH-14 | REQ-AUTH-10 | нег. | BV-A-03 OFF | То же | `amount = 1 000 001` | `DECLINED`, `responseCode = 61` |
| TC-AUTH-15 | REQ-AUTH-11 | поз. | BV-A-04 ON | `availableBalance = 100 000 000`, лимиты выше | `amount = 100 000 000` | `APPROVED`, баланс после операции = 0 |
| TC-AUTH-16 | REQ-AUTH-11 | нег. | BV-A-04 OFF, PW-A-15 | То же | `amount = 100 000 001` | `DECLINED`, `responseCode = 51`, баланс не изменился |
| TC-AUTH-17 | REQ-AUTH-13 | поз. | EC-A-06 | Карта `ACTIVE`, баланс `B` | Авторизация на `amount`, затем `GET /api/cards/{pan}` | Баланс равен `B − amount` ровно один раз |
| TC-AUTH-18 | REQ-AUTH-13, R-04 | нег. | EC-A-04 | Карта `ACTIVE`, лимит меньше суммы | Авторизация, затем `GET /api/cards/{pan}` | `DECLINED 61`; баланс не изменился, `limit_usage` не вырос |
| TC-AUTH-19 | REQ-AUTH-14 | поз. | EC-A-04 | Свежая карта, `daily_amount = 0` | Три транзакции по 5 000 000 при `dailyLimit = 15 000 000`, затем четвёртая на 1 | Первые три `APPROVED`, четвёртая `DECLINED 61` |
| TC-AUTH-20 | REQ-AUTH-15 | нег. | EC-A-10, PW-A-20 | `docker compose stop card-management` | Запрос авторизации | `DECLINED`, `responseCode = 05`, причина `ISSUER_TIMEOUT` |
| TC-AUTH-21 | REQ-AUTH-03 | поз. | EC-A-11 | `docker compose stop bin-lookup` | Запрос авторизации по валидной карте | `APPROVED` — сработала graceful degradation, ответ не дольше таймаутов 3 с / 5 с |
| TC-AUTH-22 | REQ-AUTH-16 | поз. | BV-A-09 | Карта с большим балансом | 100 последовательных успешных авторизаций | Все RRN — 12 цифр, все различны |
| TC-AUTH-23 | REQ-AUTH-17 | поз. | BV-A-10 | То же | Проверить `authCode` в ответах | Ровно 6 символов из `A-Z0-9` |
| TC-AUTH-24 | REQ-AUTH-18 | нег. | тройка сверх pairwise | `amount` превышает одновременно остаток дневного лимита, месячного и баланс | Запрос авторизации | `DECLINED`, `responseCode = 61` — срабатывает первая по порядку проверка, а не проверка баланса |
| TC-AUTH-25 | REQ-AUTH-02 | нег. | BV-A-07 | — | Запрос с `pan` из 15 цифр | 400, тело с описанием ошибки валидации |
| TC-AUTH-26 | REQ-AUTH-11, R-05 | нег. | конкурентность | Карта с балансом `B`; две параллельные авторизации на `0,6 × B` каждая | Отправить запросы одновременно | Ровно одна `APPROVED`, вторая `DECLINED 51`; итоговый баланс ≥ 0 |
| TC-AUTH-27 | REQ-AUTH-19 | поз. | EC-A-09 | Успешная авторизация с известным `rrn` | Запрос reversal `mti = 0400` | Сумма возвращена на баланс, запись в `limit_usage` уменьшена |
| TC-AUTH-28 | REQ-AUTH-02 | нег. | EC-A-07, BV-A-06 | — | Запрос с `amount = −1` | 400, авторизация не выполняется |
| TC-AUTH-29 | REQ-AUTH-02 | нег. | EC-A-01 | Карта удалена через `DELETE /api/cards/{pan}` | Запрос авторизации по её PAN | `DECLINED`, `responseCode = 14` — удалённая карта не участвует в транзакциях |

### 6.2. Card Management

| ID | Требование | Вид | Источник | Предусловие | Шаги | Ожидаемый результат |
|---|---|:---:|---|---|---|---|
| TC-CMS-01 | REQ-CMS-01 | поз. | — | Сервис запущен | `GET :8081/health` | 200, `status = ok`, поле `cardsInDatabase` присутствует |
| TC-CMS-02 | REQ-CMS-02, 08 | поз. | PW-C-01 | — | `POST /api/cards` с валидным телом | 201; PAN 16 цифр, начинается с переданного BIN, проходит проверку Луна; `expiryDate` = текущая дата + 3 года (MMYY); `status = ACTIVE` |
| TC-CMS-03 | REQ-CMS-02 | нег. | BV-C-01, PW-C-11 | — | `bin = "40000"` (5 цифр) | 400 |
| TC-CMS-04 | REQ-CMS-02 | нег. | BV-C-01, PW-C-12 | — | `bin = "4000000"` (7 цифр) | 400 |
| TC-CMS-05 | REQ-CMS-02 | нег. | EC-C-01, PW-C-13 | — | `bin = "40000A"` | 400 |
| TC-CMS-06 | REQ-CMS-02 | нег. | EC-C-02, PW-C-09 | — | `cardholderName = ""` | 400 |
| TC-CMS-07 | REQ-CMS-02 | нег. | EC-C-02, PW-C-08 | — | `cardholderName = "ivan ivanov"` | 400 |
| TC-CMS-08 | REQ-CMS-02 | нег. | EC-C-02, PW-C-10 | — | `cardholderName = "IVAN 2"` | 400 |
| TC-CMS-09 | REQ-CMS-02 | нег. | BV-C-02, PW-C-06 | — | `currencyCode = "64"` | 400 |
| TC-CMS-10 | REQ-CMS-02 | нег. | BV-C-03, PW-C-05 | — | `dailyLimit = −1` | 400 |
| TC-CMS-11 | REQ-CMS-02 | поз. | BV-C-03 ON, PW-C-02 | — | `dailyLimit = 0`, `monthlyLimit = 0`, `initialBalance = 0` | 201, карта создана |
| TC-CMS-12 | REQ-CMS-02 | нег. | BV-C-04, PW-C-03 | — | `initialBalance = −1` | 400 |
| TC-CMS-13 | REQ-CMS-03 | поз. | EC-C-13 | Карта создана | `GET /api/cards/{pan}` | 200, тело соответствует модели `Card` |
| TC-CMS-14 | REQ-CMS-03 | нег. | EC-C-13 | — | `GET` по несуществующему PAN из 16 цифр | 404 |
| TC-CMS-15 | REQ-CMS-03 | нег. | BV-C-13 | — | `GET` по PAN из 15 цифр | 400 |
| TC-CMS-16 | REQ-CMS-04 | поз. | BV-C-05 ON | В БД ≥ 2 карты | `GET /api/cards?limit=1` | 200, ровно одна карта в `cards` |
| TC-CMS-17 | REQ-CMS-04 | нег. | BV-C-05 | — | `GET /api/cards?limit=0` | 400 |
| TC-CMS-18 | REQ-CMS-04 | нег. | BV-C-06 OFF | — | `GET /api/cards?limit=10001` | 400 |
| TC-CMS-19 | REQ-CMS-04 | нег. | BV-C-07 | — | `GET /api/cards?offset=-1` | 400 |
| TC-CMS-20 | REQ-CMS-04 | поз. | EC-C-09 | Есть карты со статусами `ACTIVE` и `BLOCKED` | `GET /api/cards?status=BLOCKED` | 200, в выдаче только карты `BLOCKED` |
| TC-CMS-21 | REQ-CMS-05 | поз. | EC-C-09 | Карта `ACTIVE` | `PATCH /api/cards/{pan}` с `status = BLOCKED`, затем авторизация по карте | 200; последующая авторизация — `DECLINED`, `CARD_BLOCKED` |
| TC-CMS-22 | REQ-CMS-05 | нег. | EC-C-04 | Карта существует | `PATCH` с `dailyLimit = −1` | 400, карта не изменена |
| TC-CMS-23 | REQ-CMS-06 | поз. | EC-C-13 | Карта существует | `DELETE /api/cards/{pan}`, затем `GET /api/cards/{pan}` и `GET /api/cards` | Удаление принято; `GET` по PAN → 404; карты нет в списке |
| TC-CMS-24 | REQ-CMS-07, 08 | поз. | тройка сверх pairwise | БД пуста | `POST /api/cards/generate`, `count = 100`, 5 BIN | 201; ровно 100 карт, по 20 на каждый BIN; все PAN уникальны и проходят Луна; статусы ≈ 95 / 3 / 2 |
| TC-CMS-25 | REQ-CMS-07 | нег. | BV-C-08 | — | `count = 0` | 400 |
| TC-CMS-26 | REQ-CMS-07 | поз. | BV-C-08 ON | — | `count = 1` | 201, создана одна карта в статусе `ACTIVE` |
| TC-CMS-27 | REQ-CMS-07 | нег. | BV-C-09 OFF | — | `count = 10001` | 400 |
| TC-CMS-28 | REQ-CMS-09 | поз. | BV-C-11 ON | Карта с балансом `B` | `POST /api/cards/{pan}/reserve` с `amount = B` | 200, `availableBalance = 0` |
| TC-CMS-29 | REQ-CMS-09 | нег. | BV-C-11 OFF | То же | `amount = B + 1` | Отказ, баланс не изменился, в минус не уходит |
| TC-CMS-30 | REQ-CMS-09 | нег. | BV-C-10 | Карта существует | `amount = 0` | 400 |
| TC-CMS-31 | REQ-CMS-09 | нег. | BV-C-12 | Карта существует | `rrn = "12345"` (5 символов) | 400 |
| TC-CMS-32 | REQ-CMS-09 | нег. | EC-C-12 | Успешное резервирование с `rrn = R` | Повторить тот же запрос с тем же `rrn` | Повторное списание не происходит |
| TC-CMS-33 | REQ-CMS-10 | поз. | — | RabbitMQ запущен, очередь `card-notifications` подключена | Создать карту, посмотреть очередь | В exchange `smp.card-events` опубликовано событие с routing key `card.*` |

## 7. Матрица трассировки

### 7.1. Требование → тест-кейсы → техника

| Требование | Тест-кейсы | Техники |
|---|---|---|
| REQ-AUTH-01 | TC-AUTH-01 | чек-лист |
| REQ-AUTH-02 | TC-AUTH-02, TC-AUTH-25, TC-AUTH-28 | классы, границы |
| REQ-AUTH-03 | TC-AUTH-21 | классы |
| REQ-AUTH-04 | TC-AUTH-03, TC-AUTH-29 | классы |
| REQ-AUTH-05 | TC-AUTH-04, PW-A-04/16/17/27 | классы, pairwise |
| REQ-AUTH-06 | TC-AUTH-05, PW-A-05/18/19/28 | классы, pairwise |
| REQ-AUTH-07 | TC-AUTH-06, PW-A-11/23/24/29 | классы, pairwise |
| REQ-AUTH-08 | TC-AUTH-07, TC-AUTH-08, PW-A-07/12 | границы, pairwise |
| REQ-AUTH-09 | TC-AUTH-09…TC-AUTH-12, PW-A-03/10/14/26 | границы, pairwise |
| REQ-AUTH-10 | TC-AUTH-13, TC-AUTH-14, PW-A-06/22 | границы, pairwise |
| REQ-AUTH-11 | TC-AUTH-15, TC-AUTH-16, TC-AUTH-26, PW-A-15 | границы, pairwise, конкурентность |
| REQ-AUTH-12 | TC-AUTH-02, PW-A-01/02/09/13 | классы, границы, pairwise |
| REQ-AUTH-13 | TC-AUTH-17, TC-AUTH-18 | классы |
| REQ-AUTH-14 | TC-AUTH-11, TC-AUTH-12, TC-AUTH-19 | границы, состояние |
| REQ-AUTH-15 | TC-AUTH-20, PW-A-08/20/21/25 | классы, pairwise |
| REQ-AUTH-16 | TC-AUTH-22 | границы |
| REQ-AUTH-17 | TC-AUTH-23 | границы |
| REQ-AUTH-18 | TC-AUTH-24, PW-A-03/06/07/10/12/14/22 | pairwise, тройка вручную |
| REQ-AUTH-19 | TC-AUTH-27 | классы |
| REQ-CMS-01 | TC-CMS-01 | чек-лист |
| REQ-CMS-02 | TC-CMS-02…TC-CMS-12, PW-C-01…PW-C-17 | классы, границы, pairwise |
| REQ-CMS-03 | TC-CMS-13, TC-CMS-14, TC-CMS-15 | классы, границы |
| REQ-CMS-04 | TC-CMS-16…TC-CMS-20 | границы, классы |
| REQ-CMS-05 | TC-CMS-21, TC-CMS-22 | классы |
| REQ-CMS-06 | TC-CMS-23, TC-AUTH-29 | классы |
| REQ-CMS-07 | TC-CMS-24, TC-CMS-25, TC-CMS-26, TC-CMS-27 | границы, статистика |
| REQ-CMS-08 | TC-CMS-02, TC-CMS-24 | классы |
| REQ-CMS-09 | TC-CMS-28…TC-CMS-32 | границы, классы |
| REQ-CMS-10 | TC-CMS-33 | чек-лист |

Покрытие: **29 из 29 требований** (100 %) имеют хотя бы один тест-кейс.

### 7.2. Риск → проверки

| Риск ([стратегия](test-strategy.md#41-реестр-рисков)) | Тест-кейсы |
|---|---|
| R-01 — одобрение сверх лимита | TC-AUTH-09…TC-AUTH-14, TC-AUTH-19, PW-A-03/06/10/14/22/26 |
| R-02 — одобрение при нехватке баланса | TC-AUTH-15, TC-AUTH-16, PW-A-15 |
| R-03 — одобрение по неактивной карте | TC-AUTH-04…TC-AUTH-08, TC-AUTH-29, PW-A-04/05/11/16…19/23/24/27…29 |
| R-04 — рассогласование баланса и решения | TC-AUTH-17, TC-AUTH-18 |
| R-05 — гонка при резервировании | TC-AUTH-26 |
| R-06 — дублирование RRN | TC-AUTH-22 |
| R-07 — неверный приоритет проверок | TC-AUTH-24 |
| R-09 — некорректный PAN или коллизия | TC-CMS-02, TC-CMS-24 |
| R-10 — расхождение с контрактом | TC-AUTH-20, TC-CMS-17, TC-CMS-18 |
| R-11 — деградация Bin Lookup | TC-AUTH-21 |
