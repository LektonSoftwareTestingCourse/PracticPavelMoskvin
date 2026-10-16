# А4. Sequence — путь транзакции: happy path, declined, reversal

Обозначения: `->>` — синхронный запрос, `-->>` — синхронный ответ, `-)` — асинхронная отправка (AMQP, WebSocket, `@Scheduled`).

---

## 1. Happy path — APPROVED

```mermaid
sequenceDiagram
    autonumber
    actor T as Терминал
    participant GW as Gateway
    participant SW as Switch
    participant AUTH as Authorization
    participant CMS as Card Management
    participant BIN as BIN Lookup
    participant MA as Merchant-Acquirer
    participant DB as PostgreSQL
    participant MQ as RabbitMQ
    participant LOG as Tx Logger
    participant NS as Notification

    T->>GW: POST /api/transactions mti 0100
    Note over GW: RateLimit, TransactionValidationFilter,<br/>CircuitBreaker
    GW->>SW: POST /api/internal/route
    Note over SW: AuthorizationRequestNormalizer<br/>RoutingService BIN → issuerId
    SW->>AUTH: POST /api/internal/authorize (retry 3, CB)
    AUTH->>CMS: GET /api/cards/{pan}
    CMS-->>AUTH: 200 CardModel ACTIVE
    AUTH->>BIN: GET /api/bin/{bin}
    BIN-->>AUTH: 200 issuerId (только в лог)
    Note over AUTH: status ACTIVE, expiry ok, amount ≤ balance
    AUTH->>DB: JDBC upsertLimitUsage (day + month)
    DB-->>AUTH: 1 row = лимиты не превышены
    Note over AUTH: generateRRN (блок из rrn_seq)
    AUTH->>CMS: POST /api/cards/{pan}/reserve {amount, rrn}
    Note over CMS: одна БД-транзакция: FOR UPDATE card,<br/>INSERT reservation RESERVED, balance -= amount,<br/>INSERT outbox_event PENDING
    CMS-->>AUTH: 200 CardModel
    AUTH-->>SW: 200 APPROVED 00 rrn authCode
    SW->>MA: POST /api/simulator/merchant/fee
    MA-->>SW: 200 acquirerFee
    SW-)MQ: convertAndSend smp.transactions / transaction.log
    Note over SW: исключения нет → logged = true
    SW-->>GW: 200 APPROVED
    GW-->>T: 200 APPROVED 00

    Note over MQ,NS: — асинхронная вилка: клиент уже получил ответ —
    par доставка в Logger
        MQ-)LOG: consume transaction-log
        LOG->>DB: INSERT transactions (идемпотентно по id)
        LOG-)LOG: WebSocket broadcast → Dashboard
    and outbox → Notification
        CMS-)CMS: OutboxProcessor каждую 1s, findPending
        CMS-)MQ: smp.card-events / card.CardServiceReserveEvent
        Note over CMS: outbox_event → PROCESSED
        MQ-)NS: consume card-notifications
        NS->>DB: INSERT card_notifications
    end
```

---

## 2. Declined — карта BLOCKED

```mermaid
sequenceDiagram
    autonumber
    actor T as Терминал
    participant GW as Gateway
    participant SW as Switch
    participant AUTH as Authorization
    participant CMS as Card Management
    participant BIN as BIN Lookup
    participant MA as Merchant-Acquirer
    participant MQ as RabbitMQ
    participant LOG as Tx Logger

    T->>GW: POST /api/transactions
    GW->>SW: POST /api/internal/route
    SW->>AUTH: POST /api/internal/authorize
    AUTH->>CMS: GET /api/cards/{pan}
    CMS-->>AUTH: 200 CardModel status BLOCKED
    AUTH->>BIN: GET /api/bin/{bin}
    Note right of AUTH: BIN Lookup вызывается ДО проверки статуса,<br/>лишний вызов при любом отказе
    BIN-->>AUTH: 200
    Note over AUTH: status != ACTIVE → DeclineOutcome.CARD_BLOCKED<br/>limit_usage, reserve, RRN не трогаются
    AUTH-->>SW: HTTP 403 DECLINED 05 CARD_BLOCKED
    Note over SW: onStatus(!2xx) игнорируется,<br/>тело разбирается как обычный ответ
    SW->>MA: POST /api/simulator/merchant/fee
    MA-->>SW: 200
    SW-)MQ: publish DECLINED → transaction-log
    SW-->>GW: HTTP 200 DECLINED (Switch всегда отвечает 200)
    GW-->>T: 200 DECLINED 05 CARD_BLOCKED
    MQ-)LOG: consume → INSERT transactions status DECLINED

    Note over T,LOG: Другие ветки отказа (по коду)
    alt BIN нет в таблице Switch
        SW-->>GW: DECLINED 14 CARD_NOT_FOUND (Authorization не вызывается)
    else Authorization недоступен после 3 попыток или CB OPEN
        SW-->>GW: DECLINED 05 "Authorization service unavailable"
    else CMS 404 / 503
        AUTH-->>SW: DECLINED 14 / 96 SERVICE_UNAVAILABLE
    else expiry < даты транзакции
        AUTH-->>SW: DECLINED 54 CARD_EXPIRED
    else amount > balance
        AUTH-->>SW: DECLINED 51 INSUFFICIENT_FUNDS
    else upsert limit_usage вернул 0
        AUTH-->>SW: DECLINED 61 EXCEEDS_AMOUNT_LIMIT
    end
```

---

## 3. Reversal — сбой публикации в RabbitMQ после APPROVED

```mermaid
sequenceDiagram
    autonumber
    actor T as Терминал
    participant GW as Gateway
    participant SW as Switch
    participant AUTH as Authorization
    participant CMS as Card Management
    participant DB as PostgreSQL
    participant MQ as RabbitMQ
    participant NS as Notification

    T->>GW: POST /api/transactions
    GW->>SW: POST /api/internal/route
    SW->>AUTH: POST /api/internal/authorize
    Note over AUTH,CMS: как в happy path: GET card, проверки,<br/>upsert limit_usage, reserve (balance -= amount)
    AUTH-->>SW: 200 APPROVED rrn
    SW-xMQ: convertAndSend → AmqpException (брокер недоступен)
    Note over SW: LoggerClient.log() == false<br/>и status == APPROVED → rollback
    SW->>AUTH: POST /api/internal/rollback {rrn, pan, amount}
    AUTH->>CMS: POST /api/cards/{pan}/rollback {rrn, pan, amount}
    Note over CMS: FOR UPDATE card и reservation,<br/>INSERT reservation_rollbacks,<br/>reservation → ROLLED_BACK, balance += amount,<br/>INSERT outbox CardServiceRollbackEvent
    CMS-->>AUTH: 200
    AUTH-->>SW: 200 rollback APPROVED
    Note over AUTH,DB: limit_usage НЕ уменьшается: rollback() вызывает только CMS
    SW-->>GW: 200 DECLINED 96 "System error"
    GW-->>T: 200 DECLINED 96

    Note over MQ,NS: — после восстановления брокера —
    CMS-)MQ: outbox: Reserve- и RollbackEvent (retryCount < 3)
    MQ-)NS: consume → card_notifications
    Note over SW,MQ: в transaction-log записи НЕТ:<br/>сообщение не было опубликовано

    opt rollback не удался (CMS недоступна, 409, таймаут)
        AUTH-->>SW: DECLINED или null
        Note over SW: только лог "Rollback failed",<br/>клиент всё равно получает 96, деньги остаются зарезервированными
    end
```
