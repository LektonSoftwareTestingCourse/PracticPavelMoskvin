# Чек-листы само-приёмки — СМП

> **Как использовать:** перед сдачей каждого артефакта пройдите соответствующий чек-лист. Отмечайте пройденные пункты `[x]`. Если что-то не работает — чините до сдачи. Куратор проверяет только красные пункты.
>
> Чек-листы переформулированы по модели «тестируем готовую систему»: СМП уже реализован (11 сервисов + PostgreSQL + RabbitMQ), вы проверяете его поведение, а не разрабатываете.

---

## Артефакт 1 — Запуск СМП + smoke-тесты

Этот чек-лист идентичен чек-листу практики 1 и сверен со [`scripts/smoke-test.sh`](../scripts/smoke-test.sh).

- [x] `docker compose up -d` поднимает все 11 сервисов + PostgreSQL + RabbitMQ без ошибок
- [x] `docker compose ps` — все контейнеры `Up` (healthy)
- [x] Health-check Gateway: `curl http://localhost:8080/health` → 200
- [x] Health-check Card Management: `curl http://localhost:8081/health` → 200
- [x] Health-check Switch: `curl http://localhost:8082/health` → 200
- [x] Health-check Authorization: `curl http://localhost:8083/health` → 200
- [x] Health-check Terminal Simulator: `curl http://localhost:8085/health` → 200
- [x] Health-check Merchant Simulator: `curl http://localhost:8084/health` → 200
- [x] Health-check Transaction Logger: `curl http://localhost:8088/health` → 200
- [x] Health-check Bin Lookup: `curl http://localhost:8096/actuator/health` → 200
- [x] Health-check Notification Service: `curl http://localhost:8097/actuator/health` → 200
- [x] RabbitMQ Management UI доступен: `http://localhost:15672` (логин `smp`, пароль `smp`)
- [x] Web Dashboard доступен: `http://localhost:3000`
- [x] `./scripts/smoke-test.sh` завершается `🎉 ALL CHECKS PASSED`
- [x] Генерация карт: `POST /api/cards/generate` отрабатывает (≥ 20 карт)
- [x] Симулятор терминалов: `POST /api/simulator/terminal/run` отправляет транзакции (50 submitted)

---

## Артефакт 2 — Тест-дизайн и баг-репорты

- [ ] Разобраны ТЗ [`tz/04-authorization.md`](../tz/04-authorization.md) и [`tz/05-card-management.md`](../tz/05-card-management.md)
- [ ] Определены классы эквивалентности для статусов карт (ACTIVE/INACTIVE/BLOCKED/EXPIRED)
- [ ] Определены граничные значения для dailyLimit/monthlyLimit и суммы транзакции
- [ ] Построена decision table (успех + все decline-причины)
- [ ] Оформлены баг-репорты с шагами воспроизведения, ожидаемым и фактическим результатом
- [ ] Тест-кейсы привязаны к требованиям (прослеживаемость)
- [ ] Артефакт размещён по пути сдачи (см. [`submission-guide.md`](submission-guide.md))

---

## Артефакт 3 — Unit-тесты на JUnit 5

- [ ] Покрыты целевые классы бизнес-логики (Gateway, Switch, Authorization, Card Management)
- [ ] Используются assertions (проверяются значения, а не только «не упало»)
- [ ] Используются Mockito test doubles для изоляции зависимостей
- [ ] Покрыты ветвления (`if`/`else`, границы лимитов и статусов)
- [ ] Применены параметризованные тесты для классов эквивалентности
- [ ] Тесты проходят локально (`mvn test`)
- [ ] CI зелёный по сервисам с тестами

---

## Артефакт 4 — API и интеграционные тесты

- [ ] Покрыты все endpoint'ы целевых сервисов (позитивные + негативные)
- [ ] Проверены все decline-коды (responseCode + declineReason)
- [ ] Тесты с БД используют контейнеризованную PostgreSQL
- [ ] Проверена цепочка сервисов (сквозной проход транзакции)
- [ ] Тесты проходят локально и в CI

---

## Артефакт 5 — E2E бизнес-сценарии

- [ ] Реализованы сценарии: покупка, возврат, declined-кейсы
- [ ] Каждый сценарий проверяет полную цепочку сервисов
- [ ] Используются синтетические тестовые данные
- [ ] E2E-набор стабилен (нет flaky-тестов без объяснения)
- [ ] Отчёт оформлен (см. [`skill-2-e2e-report`](../../materials/llm/skills/skill-2-e2e-report.md))

---

## Артефакт 6 — CI/CD и нагрузочный smoke

- [ ] CI-пайплайн зелёный (сборка → тесты → отчёты)
- [ ] Настроены quality gates
- [ ] Локально выполнен нагрузочный прогон 500+ транзакций
- [ ] Собраны метрики (p50/p95/p99, throughput, error rate)
- [ ] Test summary report оформлен (см. [`skill-3-test-summary`](../../materials/llm/skills/skill-3-test-summary.md))

---

## Артефакт 7 — Отчётность и метрики

- [ ] Allure-отчёт сгенерирован и доступен
- [ ] Прослежены coverage trends
- [ ] Метрики покрытия собраны и проинтерпретированы
- [ ] Сделаны выводы о качестве (что покрыто, что осталось под риском)

---

## Артефакт 8 — Финальная тестовая стратегия

- [ ] Составлена coverage map (что протестировано по уровням пирамиды)
- [ ] Выделено ≥ 5 рисков с приоритизацией
- [ ] Описан план тестирования (уровни, инструменты, критерии выхода)
- [ ] Подготовлена защита (5–7 слайдов + ответы на вопросы)
