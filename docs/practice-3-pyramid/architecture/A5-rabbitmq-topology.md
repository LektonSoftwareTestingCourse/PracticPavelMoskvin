# А5. RabbitMQ-топология

## Диаграмма

```mermaid
flowchart LR
    subgraph P["Producers"]
        SW["Switch<br/>LoggerClient.log()<br/>convertAndSend"]
        OB["Card Management<br/>OutboxProcessor 1s →<br/>OutboxEventProcessorImpl"]
    end

    subgraph BROKER["RabbitMQ"]
        EX1{{"smp.transactions<br/>topic, durable"}}
        Q1[["transaction-log<br/>classic, durable<br/>x-dead-letter-exchange = smp.transactions.dlx<br/>x-dead-letter-routing-key = transaction-log<br/>x-max-delivery = 3 ⚠ не действует"]]
        DLX1{{"smp.transactions.dlx<br/>direct"}}
        DLQ1[["transaction-log-dlq<br/>x-message-ttl = 60000"]]

        EX2{{"smp.card-events<br/>topic, durable"}}
        Q2[["card-notifications<br/>classic, durable<br/>x-dead-letter-exchange = smp.card-events.dlx<br/>x-dead-letter-routing-key = card-notifications<br/>x-max-delivery = 3 ⚠ не действует"]]
        DLX2{{"smp.card-events.dlx<br/>direct"}}
        DLQ2[["card-notifications-dlq<br/>x-message-ttl = 60000"]]
    end

    subgraph C["Consumers"]
        LOG["Transaction Logger<br/>TransactionLogListener<br/>@RabbitListener → store()"]
        NS["Notification Service<br/>CardEventListener<br/>@RabbitListener @Transactional"]
        NONE["нет потребителя<br/>просмотр в UI :15672"]
    end

    SW -.->|"rk = transaction.log<br/>TransactionRequest JSON"| EX1
    EX1 -->|"binding transaction.log"| Q1
    Q1 -.->|"deliver"| LOG
    Q1 -->|"reject без requeue"| DLX1
    DLX1 -->|"binding transaction-log"| DLQ1
    DLQ1 -.-> NONE
    DLQ1 -->|"через 60s сообщение удаляется"| X1(("✕"))

    OB -.->|"rk = card.EventClass<br/>JSON + __TypeId__"| EX2
    EX2 -->|"binding card.*"| Q2
    Q2 -.->|"deliver"| NS
    Q2 -->|"reject без requeue"| DLX2
    DLX2 -->|"binding card-notifications"| DLQ2
    DLQ2 -.-> NONE
    DLQ2 -->|"через 60s сообщение удаляется"| X2(("✕"))
```

## Механика

### Publisher side

```mermaid
flowchart TB
    subgraph SWP["Switch: публикация транзакции"]
        A1["convertAndSend"] -->|"OK, исключения нет"| A2["logged = true<br/>(ack/nack брокера не проверяется)"]
        A1 -->|"AmqpException<br/>(нет соединения)"| A3["logged = false"]
        A3 -->|"status = APPROVED"| A4["POST /api/internal/rollback<br/>→ клиенту 96"]
        A3 -->|"status = DECLINED"| A5["вернуть DECLINED,<br/>запись в Logger потеряна"]
    end

    subgraph OBP["Card Management: outbox"]
        B0["outbox_event PENDING<br/>(в одной транзакции с изменением карты)"] --> B1["@Scheduled fixedDelay 1000ms<br/>findByStatusAndRetryCountLessThan(PENDING, 3)"]
        B1 --> B2["convertAndSend"]
        B2 -->|"OK"| B3["PROCESSED<br/>(без confirm)"]
        B2 -->|"исключение"| B4["retryCount++,<br/>lastError"]
        B4 -->|"retryCount < 3"| B0
        B4 -->|"retryCount = 3"| B5["FAILED<br/>больше не публикуется"]
    end
```

### Consumer side: судьба сообщения при ошибке

| Ситуация | Что делает Spring AMQP при текущих настройках | Куда попадает сообщение |
|---|---|---|
| Logger: тело не десериализуется в `TransactionRequest` | `MessageConversionException` считается фатальной → reject, `requeue=false` | DLX → `transaction-log-dlq`, живёт 60s |
| Logger: `store()` бросил исключение (БД недоступна, `TransactionConflictException`) | `defaultRequeueRejected = true` → nack с requeue | обратно в `transaction-log` → **бесконечная повторная доставка** (лимит 3 не работает) |
| Notification: невалидный JSON или ошибка БД | listener сам ловит исключение и бросает `RuntimeException` → requeue | обратно в `card-notifications` → бесконечная повторная доставка |
| Сообщение в DLQ дольше 60s | истёк TTL; у DLQ своего DLX нет | **удаляется безвозвратно** |

## Подтверждение кодом

| Элемент | Файл |
|---|---|
| `smp.transactions`, `transaction-log`, DLX/DLQ, TTL 60 000 | [`switch/.../config/RabbitMQConfig.java`](../../../services/switch/src/main/java/com/processing/config/RabbitMQConfig.java) |
| Объявление очереди на стороне Logger (без DLX/DLQ) | [`transaction-logger/.../config/RabbitMQConfig.java`](../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/config/RabbitMQConfig.java) |
| Публикация, `AmqpException` → `false` | [`LoggerClient.java`](../../../services/switch/src/main/java/com/processing/service/LoggerClient.java) |
| Rollback при `!logged && APPROVED` | [`RouteService.java`](../../../services/switch/src/main/java/com/processing/service/RouteService.java) |
| `publisher-confirm-type: correlated` | [`switch/application.yml`](../../../services/switch/src/main/resources/application.yml), [`card-management/application.properties`](../../../services/card-management/src/main/resources/application.properties) |
| `smp.card-events`, `card-notifications`, binding `card.*`, DLX/DLQ | [`card-management/.../config/RabbitMQConfig.java`](../../../services/card-management/src/main/java/com/processing/cardmanagement/config/RabbitMQConfig.java), [`notification-service/.../config/RabbitMQConfig.java`](../../../services/notification-service/src/main/java/com/processing/notification/config/RabbitMQConfig.java) |
| Outbox: поллер, retry, FAILED | [`OutboxProcessor.java`](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxProcessor.java), [`OutboxEventProcessorImpl.java`](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxEventProcessorImpl.java), [`OutboxEventDataStatus.java`](../../../services/card-management/src/main/java/com/processing/cardmanagement/models/OutboxEventDataStatus.java) |
| Consumers | [`TransactionLogListener.java`](../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/listener/TransactionLogListener.java), [`CardEventListener.java`](../../../services/notification-service/src/main/java/com/processing/notification/listener/CardEventListener.java) |
| Идемпотентность Logger по `id` | [`TransactionService.store`](../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/service/TransactionService.java) |
