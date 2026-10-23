# Browser API contract

Канонические артефакты для frontend и CI хранятся здесь, внутри Git-репозитория:

- openapi.json — нормативная схема;
- examples.json — примеры и fixtures модульных тестов;
- acceptance-cases.json — требования к приёмке, не отчёт о пройденных тестах;
- validate_contract.py — локальная структурная/schema-проверка без внешних зависимостей.

Из корня репозитория: `python3 docs/api/validate_contract.py`.
Из frontend: `npm run generate:api`; сохраните изменённый src/lib/api-schema.d.ts вместе с контрактом. CI сравнивает результат генерации с сохранённым файлом. Генератор устанавливается через lockfile.

Подробные правила: [API_CONTRACT.md](../API_CONTRACT.md). Прежние копии артефактов во внешней папке Hotel Reservation project/docs/api оставлены как история этапа; frontend и CI их не читают. Новые изменения вносите в эти репозиторные файлы.
