# А3. C4 Level 3 — Component (Authorization, Card Management)

Обозначения на схемах: сплошная линия — синхронный вызов в процессе, HTTP или JDBC; пунктир — асинхронный (`@Scheduled`, AMQP).

---

## 1. Authorization Service

```mermaid
flowchart TB
    SW(["Switch"]) -->|"HTTP POST /api/internal/authorize, /rollback"| CTRL

    subgraph AUTH["authorization :8083"]
        CTRL["AuthControllerImpl<br/>@Valid AuthorizationRequest<br/>declineReason → HTTP 200/403/404/422/503/400"]
        HC["HealthControllerImpl → HealthServiceImpl"]
        SVC["AuthServiceImpl<br/>authorize(): шаги проверок, generateRRN(), generateAuthCode()<br/>rollback()"]
        DO["DeclineOutcome<br/>reason → responseCode"]
        CMSC["CardManagementClientImpl<br/>RestClient, HTTP-статус → исключение"]
        BINC["BinLookupClient<br/>RestClient 3s/5s, ошибка → Optional.empty"]
        REPO["LimitUsageRepository<br/>native upsert limit_usage, fetchRrnBlock()"]
        CLEAN["CleanupServiceImpl<br/>@Scheduled cron 0 0 0 2 * ?"]
        EVT["AuthorizationEventNotifierImpl → AuthorizationLogEventListenerImpl<br/>события в процессе → лог / метрики"]
    end

    CTRL --> SVC
    SVC --> DO
    SVC --> CMSC
    SVC --> BINC
    SVC -->|"@Transactional"| REPO
    SVC --> EVT
    CMSC --> EVT
    CLEAN -.->|"раз в месяц: удалить записи прошлого месяца"| REPO

    CMSC -->|"HTTP GET /api/cards/{pan}, POST /reserve, /rollback"| CMS(["Card Management"])
    BINC -->|"HTTP GET /api/bin/{bin}"| BIN(["BIN Lookup"])
    REPO -->|"JDBC"| DB[("PostgreSQL<br/>limit_usage, rrn_seq")]
    HC -->|"HTTP GET /health"| CMS
```

---

## 2. Card Management Service

```mermaid
flowchart TB
    IN(["Authorization / Gateway / Terminal Simulator"]) -->|"HTTP /api/cards/**"| CC

    subgraph CMS["card-management :8081"]
        CC["CardController<br/>CRUD, /reserve, /rollback, /bulk-update"]
        GC["CardGeneratorController<br/>POST /api/cards/generate"]
        BC["BinIssuerController"]
        GEH["GlobalExceptionHandler<br/>исключение → 400/402/404/409/413/500"]

        CS["CardServiceImpl<br/>create, patch, delete, reserve, rollback"]
        GS["CardGeneratorService<br/>статусы 95/3/2, равномерно по BIN"]
        BIS["BinIssuerServiceImpl"]
        PAN["PanGenerator + LuhnValidator"]
        RS["RetryServiceImpl<br/>повтор при коллизии PAN"]
        TR["TransactionRunnerImpl<br/>границы БД-транзакции"]
        DOM["Card / Reservation (домен)<br/>startReservation, withData, deleted, startRollback"]

        NOTIF["CardEventNotifierImpl<br/>CardOutboxEvent → outbox, остальные → listeners"]
        OEP["OutboxEventProcessorImpl<br/>save() / processSingleEvent()"]
        OP["OutboxProcessor<br/>@Scheduled fixedDelay 1000ms"]
        LST["CardServiceLogEventListener, CardMetricsEventListener"]

        REPOS["Repository adapters<br/>Card, Reservation, ReservationRollback, Outbox, BinIssuer"]
    end

    CC --> CS
    GC --> GS --> CS
    BC --> BIS
    CC -.->|"исключения"| GEH
    CS --> TR
    CS --> DOM
    CS --> PAN
    CS --> RS
    CS --> BIS
    CS --> REPOS
    CS --> NOTIF
    NOTIF -->|"в той же БД-транзакции"| OEP
    NOTIF --> LST
    OEP --> REPOS
    OP -.->|"каждую 1s: findPending(retry < 3)"| REPOS
    OP --> OEP
    OEP -.->|"AMQP convertAndSend<br/>smp.card-events / card.EventClass"| RMQ{{"RabbitMQ"}}
    REPOS -->|"JDBC, FOR UPDATE"| DB[("PostgreSQL<br/>cards, reservations, reservation_rollbacks,<br/>outbox_event, bin_issuers")]
```
