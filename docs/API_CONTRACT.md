# Целевой browser API Hotel Reservation

Контракт 0.2.0-local, обновлён 22 октября 2026: основной v2 API и управление каталогом/фотографиями реализованы локально. Проверены backend и HTTP-адаптер frontend; публичная регистрация остаётся закрытой. Это нормативный контракт, а не автоматически выгруженный Springdoc. Полная браузерная приёмка ещё не завершена; актуальные проверки — в PROJECT_STATUS.md.

Нормативные артефакты: [OpenAPI 3.1 JSON](api/openapi.json), [JSON-примеры](api/examples.json), [сценарии приёмки](api/acceptance-cases.json), [локальная проверка](api/validate_contract.py). Этот документ уточняет транзакции, сроки и переход. Расхождение между этими файлами — дефект контракта, а не свобода реализации. Источники требований: PRODUCT_BRIEF.md, UX_SPEC.md, MVP_BACKLOG.md и текущий master backend.

Рабочие defaults: Hotel Reservation, ru-RU, RUB, вымышленные российские отели, одна физическая комната на бронь. Название/язык/валюта изменяемы до реализации. Нет платежей, возвратов, отзывов, промокодов, сложных тарифов, нескольких комнат или детских тарифов. Примеры иллюстративные, относительно `2026-10-20T09:00:00Z`; это не живые предложения, аккаунты или токены.

## 1. Версия, HTTP и область

База — **`/api/v2` на том же origin**, без отдельного CORS-домена. Так явно отделяется session API от существующего `/api/v1` с Basic, иной моделью брони и DTO. Более ранние `/api/v1/...` в MVP_BACKLOG — предложения до API-01; новые browser-семантики определяются v2. Реализация находится в web/v2; OpenAPI не выдаётся за выгрузку текущего Springdoc.

Основные операции принимают UTF-8 JSON, тела до 16 KiB; JSON управления каталогом — до 128 KiB. Неизвестные query-параметры, повтор одного scalar query/header, неизвестные JSON-поля и повтор JSON-ключей отклоняются 400. GET/HEAD не меняют бизнес-данные; CSRF GET может создать только защитную анонимную сессию. POST JSON принимает `application/json` с необязательным `charset=utf-8`; form/text/multipart не являются альтернативой. Отдельное исключение — описанная ниже загрузка фотографии. No-body POST logout/cancel/fence и replay create допускают отсутствие Content-Type. POST с неожиданным телом там, где тела нет, — 400.

Ответы, включая публичный каталог, auth, ошибки и 204: `Cache-Control: private, no-store`, `Vary: Cookie`, `X-Trace-Id: <32 lowercase hex>`. Для первой версии API общий CDN/browser/service-worker cache выключен; это также исключает кеширование устаревшей доступности и PII. Никаких session/token/паролей в URL, JSON-логах, аналитике, error details или localStorage. Frontend очищает персональные данные при logout/смене пользователя; кабинет не восстанавливается из общего SSR/route cache. Ответы auth не возвращают `WWW-Authenticate: Basic`, редирект или произвольный returnUrl.

### Карта операций

| Метод и путь после `/api/v2` | Доступ | Результат |
|---|---|---|
| GET `/cities` | Все | Поддерживаемые city codes, TZ и серверные границы дат |
| GET `/hotels` | Все | Пагинированный поиск отелей по подходящим комнатам |
| GET `/hotels/{slug}` | Все | Публичная карточка отеля |
| GET `/hotels/{slug}/rooms` | Все | Комнаты с фильтрами и состоянием доступности |
| GET `/rooms/{roomId}` | Все | Публичная выбранная комната/состояние для дат |
| GET `/auth/csrf` | Все | CSRF для анонимной/текущей сессии и режим регистрации |
| POST `/auth/login` | CSRF | Сессия; 200, затем новый CSRF GET |
| POST `/auth/logout` | CSRF | Завершение сессии; 204 |
| GET `/auth/me` | Сессия | Только свой профиль и сроки сессии |
| POST `/auth/register` | CSRF + серверный TEST_ONLY gate | 201 только в ограниченной тестовой среде; публично 403 |
| POST `/bookings/quote` | Сессия + CSRF + Idempotency-Key | 201 quote без удержания комнаты |
| POST `/bookings` | Сессия + CSRF + Idempotency-Key | 201 неизменяемая квитанция создания |
| GET `/me/booking-operations/result` | Сессия + Idempotency-Key header | PROCESSING / SUCCEEDED / FAILED; без quote |
| POST `/me/booking-operations/fence` | Сессия + CSRF + Idempotency-Key | Безопасная развязка неизвестного/непринятого запроса |
| GET `/me/bookings` | Сессия | Только свои записи, включая legacy |
| GET `/me/bookings/{bookingId}` | Владелец | Текущее состояние своей брони |
| POST `/me/bookings/{bookingId}/cancel` | Владелец + CSRF | 200 CANCELLED, повторяемый результат |
| GET `/admin/bookings`, GET `/admin/bookings/{bookingId}` | ADMIN | Явный read-only scope всех броней |

`/me` у ADMIN также означает только его собственные данные. Чужая и несуществующая бронь/операция дают одинаковый 404 NOT_FOUND; роль ADMIN не даёт права отменять чужую бронь. Отсутствие ADMIN на `/admin` — 403. В этом пакете нет admin CRUD фото/каталога, изменения тарифов и скрытия: это последующие ADM-01/BE-04 контракты; существующие v1 операции не объявляются browser API. Будущие записи должны сохранять описанные здесь блокировки и snapshot.

## 2. Идентификаторы, деньги и даты

- ID сущностей — положительные decimal strings без ведущих нулей, в пределах Java Long, например `"101"`. Это исключает потерю точности JavaScript. Сортировка id числовая, не лексикографическая.
- Slug отеля — уникальный неизменяемый lowercase `[a-z0-9-]`, до 100 символов. Numeric legacy path не трактуется как slug; скрытый/отсутствующий объект — 404. Переименование slug/alias не входит в MVP.
- Суммы — **строки** base-10 с ровно двумя знаками, без exponent, float, пробелов или `₽`: `"5000.00"`. Валюта явно `"RUB"`; тариф за комнату, не за гостя. Максимум nightly catalog price — `9999999999.99`; формат Money позволяет итог до `999999999999.99`. Расчёт Decimal/BigDecimal exact: nightly × nights, без промежуточного float/скрытого округления. Дробь больше двух знаков отклоняется при настройке тарифа. Доплаты/скидки/платежи отсутствуют.
- `checkInDate`/`checkOutDate` — ISO LocalDate `YYYY-MM-DD`. Нельзя превращать их в midnight браузерного/UTC времени. Ночи — разница календарных дат, включая DST; не секунды/86400.
- Отель имеет проверенный IANA TZ. `hotelToday = LocalDate(serverClock in hotelTZ)`. Заезд ≥ tomorrow, выезд > заезда, 1–30 ночей, выезд ≤ hotelToday + 365 days (включительно). Из этого следует допустимый максимум заезда — на день раньше максимального выезда. Гости — integer 1..capacity; технический предел комнаты/запроса 100, без отдельного тарифа по числу гостей.
- Моменты создания, quote expiry и отмены — UTC RFC3339 `...Z`. UI переводит deadline в **сохранённый TZ отеля**, не пользователя. Все проверки используют управляемые серверные часы; клиентские часы не влияют на право записи.
- Deadline — `checkInDate.atStartOfDay(savedHotelZone)` → UTC instant, сохранённый при создании. Если в правилах TZ нет midnight, берётся первый допустимый момент дня; при overlap — более ранний offset, как atStartOfDay. Сохраняется вычисленный instant, его не пересчитывают задним числом при обновлении tzdb или каталожного TZ. Quote/commit согласуют версию условий при изменении TZ/rules.

## 3. Поиск без ложной доступности

Пример: `GET /api/v2/hotels?city=moscow&checkInDate=2026-11-10&checkOutDate=2026-11-12&guests=2&priceMin=3000.00&priceMax=6000.00&page=0&size=20&sort=priceAsc`.

`city` необязателен, это код из `/cities`, не поиск произвольной подстроки. Неизвестный code — 400. Даты либо обе отсутствуют, либо обе заданы. `guests` default 1; price bounds включительны и относятся к **одной ночи**, `priceMin ≤ priceMax`. Пропущенные параметры не эквивалентны пустой строке/null. Неверный диапазон дат/цены — 400 с fieldErrors; условия не заменяются молча.

С датами условие отеля: EXISTS одна опубликованная продаваемая комната с capacity ≥ guests, в диапазоне nightly price и без CONFIRMED overlap `existing.checkIn < requested.checkOut AND existing.checkOut > requested.checkIn`. Только CONFIRMED занимает даты. «От» и сортировка цены вычисляются **по этому же набору подходящих свободных комнат**, а не по самой дешёвой комнате отеля вне фильтра. `fromPrice.totalPrice` — минимум nightly × одинаковое число ночей. `matchedRoomCount` не сообщает чужие брони или гостей.

Без дат вычисляются capacity/price, `availabilityEvaluated=false`, `fromPrice.nights=1`, `totalPrice=nightlyPrice`. UI называет это ценой за ночь, не итогом проживания и не обещанием наличия. Пустой набор подходящих комнат не образует карточку с выдуманной нулевой ценой.

Список rooms на странице отеля возвращает комнаты, подходящие по capacity/цене, включая занятые с `availability=UNAVAILABLE`; без дат — NOT_EVALUATED. Search hotels включает только отели с хотя бы одной AVAILABLE. Single room GET может дать UNAVAILABLE/CAPACITY_EXCEEDED, но не конфликтующую бронь/даты чужого гостя. Деталь отеля доступна и при отсутствии доступных комнат; её DTO не содержит цену/доступность — их возвращает запрос списка комнат.

Между просмотром и записью нет hold. Клиент игнорирует запоздалый ответ старого поиска; сервер отдаёт `evaluatedAt`. Публичные DTO не содержат userId, email, телефон, PII, внутренние пометки, список бронирований или `unavailableDates` конкретных броней.

Поиск без города может охватить разные TZ: структурные ошибки/число ночей проверяются глобально, hotel-local horizon — у каждого кандидата. Отели с недопустимыми локальными датами исключаются с явным `warnings=[{code:DATE_OUT_OF_RANGE_FOR_SOME_HOTELS,timeZones:[...]}]`, без изменения дат. Если даты не подходят ни одной зоне опубликованных кандидатов — 400 DATE_RANGE_INVALID с `details.dateLimits`; если подходят, но свободных/подходящих комнат нет — 200 empty. При заданном городе тот же принцип по его реальным TZ; нельзя молча назначить всем Europe/Moscow. `/cities` даёт TZ/dateLimits для UI.

Пагинация: page ≥ 0, size 1..50, default 0/20. Ответ: items, page, size, totalElements, totalPages, sort, evaluatedAt; пустая/слишком дальняя страница — 200 пустой список. Hotel sort: `priceAsc` = minimum eligible nightly ASC, id ASC; `priceDesc` = price DESC, id ASC; `idAsc` = id ASC. Room sort аналогичен по своей цене. Count и items рассчитываются в одном read snapshot; между страницами живого каталога данные могут измениться, snapshot pagination не обещается. Для своих/admin броней — `createdDesc` default (legacy createdAt null last, id DESC), либо `checkInAsc` (дата ASC,id ASC), status optional CONFIRMED/CANCELLED.

## 4. Browser auth, CSRF и регистрационный gate

Целевая сессия серверная, долговечное общее хранилище между экземплярами приложения: idle **30 минут**, absolute **12 часов** от login. Идентификатор в `__Host-hotel-session; Secure; HttpOnly; SameSite=Lax; Path=/`, без Domain. Неавторизованная CSRF-сессия живёт максимум 30 минут. Токены/пароли/session ID не записываются в web storage. HTTPS обязателен для целевого размещения; локальный mock-прототип к этому backend не подключён.

Последовательность: GET csrf → POST login с cookie и X-CSRF-TOKEN → server rotates session ID и CSRF → GET fresh csrf → GET me. CSRF живёт **30 минут**, не продлевается quote/retry, привязан к анонимной/авторизованной сессии. GET повторно возвращает текущий token до истечения; после истечения выдаёт новый, после logout/login старый всегда недействителен. CsrfInfo.expiresAt — точный expiry этого токена. Header name `X-CSRF-TOKEN`; отдельной читаемой XSRF cookie в v2 нет. Token только в памяти страницы. SameSite не заменяет CSRF.

Все unsafe v2, включая anonymous login/register/logout и no-body replay/fence/cancel, требуют CSRF независимо от JSON/Basic/Origin. Same-origin Origin проверяется, когда присутствует; чужой Origin отклоняется, отсутствующий не освобождает от токена. CORS/credentialed cross-origin доступ не включается. Для private операций отсутствие/истечение сессии даёт 401 AUTHENTICATION_REQUIRED; для авторизованного запроса с неверным/истёкшим CSRF — 403 CSRF_INVALID. Получить новый CSRF можно без повторного логина, если session ещё валидна. GET csrf при потере session создаёт только анонимную, не восстанавливает login.

POST login принимает username/password, не email по умолчанию; session fixation защита, общий INVALID_CREDENTIALS без account enumeration. Лимиты: 5 неуспехов на normalized username+source за 15 минут и 30 на source за 15 минут, 429 Retry-After seconds. Source берётся из доверенной сети/proxy configuration, не произвольного X-Forwarded-For. Ограничители используют короткоживущие keyed hashes (не сырые credentials), счётчики удаляются через 24h; пароли/ввод не логируются. При 429 нет автоматического обхода повтором. Logout требует валидный CSRF (анонимный допустим), всегда 204 после успешной проверки; инвалидирует серверную session и cookie. Повтор через новый anonymous csrf — снова 204. UI очищает персональный cache; безопасные search params можно оставить.

**Публичная регистрация CLOSED до готовности подтверждения email и восстановления пароля.** Вариант TEST_ONLY допустим только в отдельно ограниченной тестовой среде, включается сервером, не параметром клиента и не общим admin-аккаунтом. `/auth/csrf.registrationMode` управляет объяснением UI; это не право. Public backend в CLOSED отвечает 403 REGISTRATION_CLOSED и не проверяет существование email. В TEST_ONLY: username 3..50, password 6..72 символа и ≤72 UTF-8 bytes, email ≤254; server sets USER, emailVerified=false, без авто-login/обещания письма. 3 попытки/source/hour; duplicate account — общий 409 ACCOUNT_CONFLICT, без указания занятого поля. OPEN/signup email-ссылки/reset не входят в этот контракт: AUTH-01 должен их определить до снятия gate.

Права проверяются сервером каждый раз. Удаление/блокировка пользователя и изменение роли инвалидируют соответствующие v2 sessions (security-version/revocation), включая изменения из legacy admin API; нельзя ждать 12 часов со старым ADMIN. Login не принимает returnUrl; frontend использует allowlist внутренних целей `booking-review`/`account-bookings` и валидированный несекретный выбор. Повторный вход не отправляет бронь автоматически.

## 5. Quote и явное согласие с условиями

POST `/bookings/quote` требует вошедшего пользователя. Гостевой просмотр использует публичную комнату/поиск; после входа quote получается заново. В заголовке UUIDv4 Idempotency-Key в scope **(userId, QUOTE, key)**; payload: roomId, обе даты, guests. Ответ 201 содержит owner-bound случайный opaque quoteToken, stay, Decimal pricing, snapshot для review, termsVersion, createdAt/expiresAt, cancellationDeadlineAt, holdsRoom=false.

Quote действительно **10 минут от первого создания**, без скользящего продления. Durable quote record и mapping ключ→payload/token зашифрованного at-rest recovery сохраняются **24 часа**; для validation token хранится hash, исходный token не логируется. Повтор того же QUOTE key и payload в эти 24h возвращает тот же quote/token и первоначальный expiry, если он действителен. Если истёк — 409 QUOTE_EXPIRED; новый расчёт запрашивается новым QUOTE key и снова показывается пользователю. Другой payload с тем же живым mapping — 409 IDEMPOTENCY_CONFLICT. Принятые синтаксически корректные QUOTE failures также привязаны к payload/key на 24h и повторяют исходную ошибку; новый расчёт после такого отказа требует нового QUOTE key. Синтаксические/auth/CSRF/rate-limit отказы key не потребляют. Неизвестный сетевой исход QUOTE можно повторить тем же key и тем же телом — это никогда не создаёт бронь. По истечении 24h quote records удаляются, такой QUOTE key может лишь создать новый расчёт, не бронь. CREATE_BOOKING scope с тем же UUID не смешивается с QUOTE, но клиенту рекомендуются разные ключи.

Quote не резервирует комнату и не фиксирует цену до commit. termsVersion охватывает цену/валюту, capacity, TZ/deadline policy, идентичность и snapshot-поля отеля/комнаты, продаваемость. Присланная цена никогда не источник истины; create принимает quoteToken и выбор, а не деньги. Token проверяется по hash, owner и точному selection, не содержит возможности читать чужую бронь. Чужой/поддельный/удалённый token → одинаковый QUOTE_INVALID; известный истёкший в 24h record → QUOTE_EXPIRED. Не раскрывать владельца.

У свежей операции: проверяется срок quote, затем под room lock — актуальные правила дат/вместимость/продаваемость/overlap, потом цена/terms. Сроки expiry проверяются по часам **после ожидания lock**, не только в начале HTTP-запроса. Неверный выбор для token — 409 QUOTE_SELECTION_MISMATCH. Занятость — 409 ROOM_UNAVAILABLE без сведений о другом пользователе. Изменённая capacity — 409 CAPACITY_EXCEEDED, скрытая комната — ROOM_NOT_BOOKABLE. Если цена/итог изменились — 409 PRICE_CHANGED, `details.replacementQuote` на новые условия (новый token, новый 10-min expiry, та же selection при валидности). Иное изменение snapshot/TZ/cancellation terms — TERMS_CHANGED аналогично. Цена имеет приоритет над прочим terms-change, но доступность/вместимость проверяются раньше: для занятой комнаты новый quote не обещается.

**Ни PRICE_CHANGED, ни QUOTE_EXPIRED не создают бронь.** Frontend показывает новый итог/условия и требует явного подтверждения. Истёкший quote запрашивается заново; даже совпавшая цена не разрешает скрытую отправку создания после восстановления session. После известного terminal failure используется новый CREATE key для нового подтверждения; повтор старого CREATE key с новым token — IDEMPOTENCY_CONFLICT, а не способ изменить уже принятую операцию. replacementQuote из старого error/replay может сам истечь: проверить expiresAt и получить новый, не доверять cached quote.

## 6. Создание, idempotency и неизвестный результат

### Durable registry и сроки

| Запись | Срок | После очистки |
|---|---|---|
| QUOTE token/payload/replay mapping | 24h от первого создания, validity 10min | Можно только рассчитать новый quote; права на бронь нет |
| CREATE key scope, fingerprint, bookingId, success receipt | Весь срок существования истории; в MVP **без TTL/удаления** | Ключ не становится свободным из-за expiry quote/session |
| CREATE terminal failure full error/details | 7 суток от completion | Остаются user+key+operation, fingerprint, FAILED, errorCode, исходный httpStatus и время; `error=null` в result |
| CREATE terminal binding/failure/fence tombstone | В MVP **без TTL/повторного использования** | Нельзя очистить key так, чтобы поздний запрос создал дубль |
| PROCESSING lease | 30 секунд | Это повод серверной reconciliation, не признание неуспеха |

Идемпотентность — в PostgreSQL/эквивалентном durable store, не в памяти процесса, Redis-only TTL или session. UNIQUE `(userId, operation, key)`; success receipt и booking link не теряются при рестарте/перелогине. Другая учётная запись с тем же ключом независима и не узнаёт исходный результат. Ключ UUIDv4 header, не auth credential; не в URL/referrer/аналитике. Клиент временно хранит key одной отправки в sessionStorage вкладки; quoteToken только в памяти. При смене пользователя прежний operation context очищается.

Fingerprint CREATE включает **roomId, checkInDate, checkOutDate, guests, quoteToken**. QUOTE fingerprint включает первые четыре. Канонизация: разрешённые поля, id/date/token строки строго по schema; integer guests; ключи JSON в ASCII alphabetic order, UTF-8, без пробелов/escape differences → SHA-256. Неизвестные поля/дубликаты до канонизации отклоняются; отсутствующий и null не взаимозаменяемы. Даже новый quote на те же даты/цену с другим token — другой CREATE payload. Server сохраняет fingerprint независимо от очистки quote; повтор с телом сравнивается без необходимости читать уже удалённый quote record.

### Порядок и атомарность

1. Проверить session, scope, CSRF и синтаксис header. Найти registry `(owner, CREATE_BOOKING, key)` **до** validation текущей доступности/expiry/продаваемости/дат quote. Если body дан, безопасно распарсить/сверить canonical fingerprint. Другой body → 409 IDEMPOTENCY_CONFLICT. Если key — fence без payload, он всегда terminal REQUEST_ABORTED.
2. SUCCEEDED → вернуть исходный **201 BookingCreationReceipt**, Location и `Idempotency-Replayed:true`. **Можно отправить только Idempotency-Key, без тела, roomId, дат или истёкшего/потерянного quoteToken.** Выигравшая запись не перепроверяет quote, будущую цену, новые даты или отмену — новая бронь не создаётся. Receipt содержит `statusAtCreation=CONFIRMED`, а не обещание текущего статуса; GET bookingUrl покажет CANCELLED после отмены.
3. FAILED → повтор с тем же payload/без тела даёт terminal error; после 7d сохраняются status/code с безопасным общим message, без истёкшего replacementQuote. Не выполнять создание заново. PROCESSING → 409 OPERATION_IN_PROGRESS + Retry-After:2, result URL без ключа в URL.
4. Новый key без тела → 400 BODY_REQUIRED и никакой новой брони. Полное синтаксически валидное тело позволяет durably зарезервировать PROCESSING (unique insert, самостоятельный короткий transaction). Синтаксические 400, auth/CSRF/limit rejection ключ не потребляют. После резервирования известные domain failures, включая изменившиеся даты, становятся terminal FAILED.
5. Рабочая транзакция сначала блокирует operation row, затем Room; READ_COMMITTED. **Сразу после operation lock обработчик обязан перечитать state и attempt/lease из БД.** SUCCEEDED возвращает receipt, FAILED — terminal error; в обоих случаях создание прекращается. Продолжать можно только при PROCESSING, совпадающем worker attemptToken и неистёкшем 30s lease на момент старта рабочей транзакции. При несовпавшем attemptToken worker не меняет чужое состояние/lease и возвращает текущий outcome либо OPERATION_IN_PROGRESS. При собственном PROCESSING с уже истёкшим lease, когда operation lock свободен, фиксирует FAILED REQUEST_ABORTED без создания; stale worker не продлевает lease и не переводит terminal state обратно в PROCESSING. После начала под lock допустимая рабочая транзакция может завершаться после lease, recovery ждёт её outcome; одному elapsed timeout нельзя доверять. После lock перепроверяет quote expiry, dates, guests/capacity, sale state, overlap и price/terms. Записывает booking CONFIRMED с полным snapshot, outbox и SUCCEEDED+неизменяемый receipt **одним commit**. Невозможно committed booking без успешного registry result. Error paths освобождают lock; known no-commit outcome с исходным HTTP status фиксируется FAILED отдельно, не вместе с откатываемой транзакцией.
6. Если обработчик упал между reservation и commit, lease recovery через 30s пытается взять тот же operation row lock. При активной рабочей транзакции результат остаётся PROCESSING; нельзя объявить FAILED по одному elapsed timeout. После освобождения lock перечитать результат: success уже сохранён атомарно либо доказан rollback/no-booking, тогда FAILED REQUEST_ABORTED. Recovery не запускает новую бронь в фоне. Terminal состояния SUCCEEDED/FAILED монотонны: ни worker, отложенный до recovery, ни retry не могут их перезаписать; success/failed update проверяет state=PROCESSING и тот же attemptToken под operation lock. Недоступная БД оставляет исход неизвестным до восстановления.

Все booking writers, cancel и будущие price/capacity/sale mutations синхронизируются на той же Room row. Cancel не блокирует чужой operation row; admin multi-room операции берут room ids по возрастанию. Delete берёт Hotel перед Room, booking не пытается взять Hotel после Room. Только CONFIRMED учитывается в overlap и status-aware PostgreSQL constraint; CANCELLED освобождает диапазон. Ограничение в БД — страховка, не замена полного transactional recheck.

### Recovery в браузере

- Timeout/offline/5xx после POST — **UNKNOWN**, не сообщение «бронь не создана». Сохранить исходный key, запросить `GET /me/booking-operations/result` с ним в header. Отсутствующий session → login, затем lookup **того же owner/key**, без автосоздания.
- SUCCEEDED → receipt → GET bookingUrl, показать реальное текущее состояние. PROCESSING → не чаще 2 секунд polling до 30 секунд, затем остановить автоматический polling и дать «Проверить снова/В кабинет»; 30 секунд не превращаются в неуспех. FAILED означает известное отсутствие commit; новый выбор/quote/подтверждение могут получить новый key.
- 404 result означает только «registry ещё не виден для owner/key». Исходный HTTP request может ещё прийти позже; **нельзя создать новый key на основании 404**. Если исходное тело сохранилось, повторить его с тем же key. Если потерян quote после refresh, header-only POST безопасно replay-ит уже успешный результат, но при ещё отсутствующем key вернёт BODY_REQUIRED.
- Для конечного выхода из «не найдено/потерян контекст» — явное действие `POST /me/booking-operations/fence` с исходным header key и CSRF, без quote/тела. Атомарно с unique reservation: если record существует — вернуть его состояние; если нет — записать durable FAILED REQUEST_ABORTED tombstone. Поздний первоначальный POST того же key больше не может создать бронь. Это **не отмена** существующей/выполняющейся брони: PROCESSING остаётся PROCESSING; SUCCEEDED остаётся SUCCEEDED. Только после server-confirmed FAILED можно безопасно получить новый quote и спросить новое подтверждение с новым key. Поэтому неизвестный результат разрешается без придумывания исходной цены или хранения token в browser storage.
- Если key потерян целиком (storage запрещён/очищен), никакая UI-эвристика не доказывает отсутствие брони. Войти в кабинет и проверить собственную историю; не отправлять новую запись автоматически. Несекретные room/date/guests можно восстановить из allowlisted URL.

## 7. Снимок, статусы, отмена и legacy

Новая V2 booking неизменно сохраняет room/date/guests, nightly price/итог/RUB/число ночей, UTC createdAt, полный snapshot hotelId/name/slug/address/city, roomId/name, IANA TZ и вычисленный cancellationDeadlineAt. Эти факты не зависят от будущего каталога. Только status/cancelledAt меняются; тариф/название/адрес нельзя подменять сегодняшними при чтении старого подтверждения.

CONFIRMED блокирует [заезд,выезд), CANCELLED не блокирует. «Прошедшая поездка» вычисляется из checkOut в сохранённой TZ, не новая DB enum. Только владелец отменяет; ADMIN чужую запись не отменяет. Под room lock сервер перечитывает booking: уже CANCELLED → 200, тот же cancelledAt, даже после дедлайна. Иначе проверяет `serverNow < savedDeadlineInstant`; в **точно 00:00** checkIn и позже — 409 CANCELLATION_CLOSED. Успех атомарно записывает CANCELLED, cancelledAt и outbox cancellation event; будущий event contract должен быть versioned, без выдуманной оплаты/выручки. Временный canCancel на GET — подсказка, POST всегда проверяет заново. Удаление/восстановление брони не вводится.

Миграция должна сохранять каждую старую строку и FK/дату. Для существующих v1 записей:

- `origin=LEGACY`, первоначально CONFIRMED — они продолжают блокировать даты. Никаких «истёкших автоотмен» или удаления истории.
- `pricing=null`, `snapshot=null`, `guests=null`, `createdAt=null`, если они не были достоверно сохранены. Сегодняшняя цена/room.capacity/название не доказательство прошлого. Для MVP legacy схема намеренно не позволяет подставить такую цену даже с approximate flag. UI: «Стоимость не сохранена», «Число гостей не сохранено». Наличие отдельного надёжного архивного источника требует отдельной проверенной миграции/ревизии, а не runtime guess.
- Необязательный currentCatalogDisplay помечен `source=CURRENT_CATALOG` и не является историческим snapshot. Если объекта нет — null, без выдуманных данных.
- До документированной проверки TZ конкретной старой брони: cancellation.timeZone/deadlineAt=null, canCancel=false, reason=LEGACY_TIMEZONE_UNKNOWN. POST cancel → 409 LEGACY_TIMEZONE_UNKNOWN. Не назначать TZ по браузеру/имени города и не применять UTC preview window как TZ отеля.
- Операторская миграция может явно назначить проверенную IANA TZ старому record с audit provenance `MIGRATION_VERIFIED` (источник/кто/когда), вычислить и сохранить deadline, не сочиняя стоимость/создание/names snapshot. После этого владелец отменяет по тому же now<deadline правилу. Если дедлайн уже прошёл — отмена закрыта. Без этой проверки self-cancel не включается. Самого operator endpoint в MVP нет.
- Новые отели/комнаты не публикуются до выбора TZ и корректного каталога; legacy без проверенного TZ не предлагаются для новых v2 броней. Новые writes legacy-формата после cutover запрещены.

Отель/комната с любой историей (включая CANCELLED) не удаляются физически. Скрытие исключает новые продажи, но оставляет доступ владельцу к своему сохранённому подтверждению. Проверяются cancel/create/price/delete races и backfill на копии данных. Рабочая БД/volumes не очищаются, ограничения не обходятся через ddl-auto.

## 8. Стабильные ошибки

Каждый error JSON: `status`, `code`, безопасный ru message, `fieldErrors:[]`, `traceId`, `details:{}`. `fieldErrors` только field/code/message, **без rejectedValue**. HTTP status совпадает с body.status; traceId совпадает с X-Trace-Id текущей попытки (на replay ошибки генерируется новый traceId, старый сохраняется только внутренне). Error.details — закрытый allowlist, не произвольный exception map. Полный URL/query/ключи/token/чужие ID не включаются. Message можно менять/локализовать, code стабилен.

| HTTP | Коды | Действие |
|---|---|---|
| 400 | VALIDATION_FAILED, MALFORMED_JSON, DATE_RANGE_INVALID, IDEMPOTENCY_KEY_REQUIRED, BODY_REQUIRED | Исправить поля; BODY_REQUIRED после unknown не даёт права создать новый key |
| 401 | AUTHENTICATION_REQUIRED, INVALID_CREDENTIALS | Вход/повторный вход, не автоматическая запись |
| 403 | CSRF_INVALID, FORBIDDEN, REGISTRATION_CLOSED | Обновить csrf/объяснить права/gate; не повторять login циклом |
| 404 | NOT_FOUND | Чужой и отсутствующий объект одинаковы; operation 404 не доказательство rollback |
| 409 | ROOM_UNAVAILABLE, ROOM_NOT_BOOKABLE, CAPACITY_EXCEEDED | Обновить выбор; никаких данных чужих броней |
| 409 | QUOTE_EXPIRED, QUOTE_INVALID, QUOTE_SELECTION_MISMATCH, PRICE_CHANGED, TERMS_CHANGED | Новый расчёт/review; новое явное подтверждение, не скрытая запись |
| 409 | IDEMPOTENCY_CONFLICT, OPERATION_IN_PROGRESS | Сверить исходную операцию; PROCESSING → Retry-After:2 |
| 409 | CANCELLATION_CLOSED, LEGACY_TIMEZONE_UNKNOWN, ACCOUNT_CONFLICT | Текущее правило/история/общее сообщение о регистрации |
| 413 / 415 | PAYLOAD_TOO_LARGE / UNSUPPORTED_MEDIA_TYPE | Не повторять тот же неверный payload |
| 429 | RATE_LIMITED | Retry-After в секундах; без автоматического обхода |
| 500 / 503 | INTERNAL_ERROR / SERVICE_UNAVAILABLE | После создания исход неизвестен, нужна сверка |
| 503 | REQUEST_ABORTED | Только доказан no-commit terminal outcome; новая операция после review допустима |

`QUOTE_EXPIRED` проверяется после lookup существующего CREATE success; **expiry не отменяет гарантию replay**. Ошибка PRICE_CHANGED может содержать replacementQuote, прочие ошибки не обязаны иметь details. 409 retry в том же key не меняет payload; операция с другим содержимым явно новая только после выяснения предыдущего результата.

## 9. Переход с реально существующего `/api/v1`

Проверено по текущим контроллерам, Booking entity, DTO, SecurityConfig и BookingService в master; приложение не запускалось.

| Сейчас реализовано | Разрыв / целевой переход |
|---|---|
| v1 GET hotels, hotels/filter, hotels/{numericId}, rooms/filter и rooms/{id}; GET требуют auth | v2 публичные allowlist DTO/slug/city+room-aware hotel search; v1 numeric DTO не переименовываются молча |
| v1 hotel/filter без room/date/guests aggregation; room/filter capacity уже >=, есть half-open overlap | Не объявлять существующий hotel filter целевым поиском; объединённый доступный минимум ещё предстоит реализовать |
| v1 room DTO содержит unavailableDates и UTC preview window | Не отдавать его публично и не выдавать preview UTC за hotel timezone; в v2 только selection availability |
| v1 GET auth/csrf и POST auth/register; stateless Basic, browser-aware CSRF exemption для REST | Отдельный session v2 login/logout/me/строгий CSRF; существующий csrf JSON/cookie/header v1 не использовать как v2 |
| v1 register публично создаёт USER без email verification | До публичной frontend-публикации закрыть также legacy register ingress; иначе gate v2 легко обходится старым URI |
| v1 POST bookings с owner из auth, locked Room, no guests/price/status/idempotency | Сохранённые строки legacy; v2 только новый complete snapshot/quote/registry путь; создание v1 прекратить до включения v2 writes |
| v1 GET bookings USER свои, ADMIN все; чужая booking сейчас 403 | v2 /me owner-only в том числе ADMIN, 404 concealment; отдельный /admin read scope |
| v1 cancel/quote/slug/login/logout/me/operation recovery отсутствуют | Весь соответствующий OpenAPI — target, не обещание работающего endpoint |
| v1 error: status/error/message/path/LocalDateTime timestamp | v2 stable code/fieldErrors/traceId; старым REST-клиентам не подменять format без version switch |

### Безопасная граница filter chains

Будущая конфигурация должна иметь два **непересекающихся matcher** и явный порядок: v2 chain сначала на `/api/v2/**`, v1 chain на `/api/v1/**`; unmatched private routes deny. v2 загружает только server session context, **не** HTTP Basic, с CSRF на всех unsafe; v1 — explicit Basic и NullSecurityContextRepository/STATELESS, не читает v2 session даже если обе path-группы получают одну browser cookie. Нельзя включить shared HttpSessionSecurityContextRepository в fallback chain или делать «если Basic не пришёл — взять session».

Для периода совместимости v1 сохраняется только как явно ограниченный legacy REST/admin канал (network policy/доверенный доступ); browser frontend не вызывает его. На внешнем ingress legacy `/auth/register` закрыт. Browser unsafe v1 (browser headers **или наличие v2 cookie**) отклоняются 403, а не получают освобождение от CSRF по legacy REST matcher. Текущий BrowserCsrfMatcher сам по себе этого нового cookie-правила ещё не реализует: это обязательная работа cutover. Non-browser legacy Basic без session cookie остаётся только в согласованном переходном канале. При reverse proxy browser metadata не счищается; даже их отсутствие не позволяет cookie-only запросу стать authenticated на v1. HTTPBasic credentials browser-а не отправляются frontend-ом вообще. Никакого permissive CORS на обеих версиях.

Обязательные тесты границы: v2 cookie без Basic → v1 private 401, mutation не выполняется; v2 cookie с Basic → browser v1 unsafe 403; Origin/Referer/Sec-Fetch-Site unsafe v1 →403; v1 Basic без session не логинит v2; v2 JSON без csrf →403 независимо от Basic; v1 и v2 register gates нельзя обойти другой версией. Legacy admin изменение роли отзывает v2 sessions.

### Поэтапный cutover

1. Реализовать/проверить v2 за закрытым feature gate на изолированной среде. Пока это только файлы контракта; новый API не считается доступным.
2. Проверенные Flyway additions, сохранение legacy rows, явный TZ backfill, nullable unknown snapshots, status-aware overlap constraint и все читающие queries. Нельзя одновременно считать CANCELLED свободным в v2 и занятой записью в legacy filter: до включения отмены общий availability query должен учитывать status во всех используемых read paths. Проверка миграции/backup — на копии, без baseline на рабочей БД в этой задаче.
3. Перед любыми публичными v2 writes закрыть внешнюю v1 регистрацию и **прекратить v1 POST bookings**: после объявленного перехода 410 Gone/инструкция перейти на v2 (с отдельным согласованным legacy ответом), без reinterpret старого body. Старый запрос не содержит guests/quote/явного согласия с новой ценой, потому безопасный «прозрачный adapter» не придумывается. Если старым клиентам нужен grace period, v2 writes ещё закрыты; dual write без единой модели не допускается.
4. Сохранить согласованные legacy read/admin URI и Basic формат в ограниченном канале на время миграции клиентов, либо закрыть ingress целиком. Срок/окончательный sunset требует решения Main, в этой документации не назначен произвольной датой. Старая история доступна через owner-scoped v2; новые записи читаются без потери своей цены.
5. Только после HTTP/E2E/security/infra acceptance переключить frontend на v2 и публиковать заявленные функции. Отдельно Main утверждает изменение v1 поведения и deployment. Здесь ни source/config/migration, ни Git/БД не менялись.

## 10. Конкретные обмены и приёмка

Полные JSON request/response для комнаты, quote, создания, receipt, CONFIRMED/CANCELLED, legacy, session, csrf и ошибок лежат в [examples.json](api/examples.json) и OpenAPI response examples. `A...A`/`B...B` — заведомо демонстрационные токены.

```http
POST /api/v2/bookings/quote
Cookie: __Host-hotel-session=<opaque>
X-CSRF-TOKEN: <fresh-token>
Idempotency-Key: 26de0f52-8d65-4cb2-9fd3-c30979db4bb5
Content-Type: application/json

{"roomId":"101","checkInDate":"2026-11-10","checkOutDate":"2026-11-12","guests":2}
```

201 quote: nightlyPrice `"5000.00"`, nights 2, totalPrice `"10000.00"`, RUB, `holdsRoom:false`, deadline `2026-11-09T21:00:00Z` = 10 ноября 00:00 Europe/Moscow, expiry через 10 минут. В следующий POST добавляется ровно полученный quoteToken:

```http
POST /api/v2/bookings
X-CSRF-TOKEN: <fresh-token>
Idempotency-Key: b084e56f-7abc-4e84-b9ae-493d102a38b9
Content-Type: application/json

{"roomId":"101","checkInDate":"2026-11-10","checkOutDate":"2026-11-12","guests":2,"quoteToken":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
```

201 + Location `/api/v2/me/bookings/9001` возвращает immutable receipt (см. JSON fixture). Header-only повтор того же POST с session/CSRF/Idempotency-Key через час после quote expiry вернёт тот же receipt и `Idempotency-Replayed:true`. GET `/me/bookings/9001` — текущий статус. Browser refresh читает деталь, не создаёт запись заново.

```http
GET /api/v2/me/booking-operations/result
Cookie: __Host-hotel-session=<opaque>
Idempotency-Key: b084e56f-7abc-4e84-b9ae-493d102a38b9
```

200 SUCCEEDED содержит receipt, PROCESSING содержит nextCheckAfterSeconds=2, FAILED — safe terminal errorCode. Никакого quote в этом запросе и ключа в query/path. При 404 и потере token явный POST `/me/booking-operations/fence` с теми же headers + CSRF закрывает непринятый key или возвращает существующий исход; новый key допустим только после FAILED.

При изменении цены: 409 PRICE_CHANGED, `details.replacementQuote.pricing.totalPrice="11000.00"`, ни одной новой booking. Старый CREATE key остаётся FAILED; новый quote/token надо показать и подтвердить с новым CREATE key. Простая замена token в старом key даёт IDEMPOTENCY_CONFLICT.

Приёмка зафиксирована как данные в [acceptance-cases.json](api/acceptance-cases.json): это **будущие тестовые требования**, не выполненные backend tests. Локальная команда проверяет синтаксис JSON, internal refs, уникальные operationId, параметры путей/security и schema/examples/арифметику/legacy/deadline; не запускает приложение:

```sh
python3 docs/api/validate_contract.py
```

Stdlib validator намеренно не называется полной сертификацией OpenAPI: внешняя meta-schema validation/генератор клиента, будущие implementation tests и сквозная browser-приёмка остаются последующими проверками. Результат опубликованного backend 142/142 не доказывает реализацию этого target API.

## Локальное управление каталогом и фотографиями — 0.2.0-local

Все `/api/v2/admin/**` требуют JDBC-сессию с ролью ADMIN; POST дополнительно CSRF и тот же Origin. Basic не заменяет browser-сессию. GET hotels и hotel/{id}/rooms используют page/size (по умолчанию 0/20, size максимум 100); идентификаторы — decimal strings.

- GET/POST `/admin/hotels`: список и создание (201).
- GET/POST `/admin/hotels/{id}`: чтение и редактирование (200).
- GET/POST `/admin/hotels/{id}/rooms`: список и создание номера (201).
- POST `/admin/rooms/{id}`: редактирование номера (200).
- POST `/admin/media`: multipart/form-data с полем file, ответ 201 `{url}`.
- GET `/media/{filename}`: публичные JPEG/PNG. Frontend также отдаёт этот файл по `/media/{filename}` того же origin.

Форматы DTO определяет OpenAPI: при создании version=null, при редактировании текущая целая version; устаревшая версия или нарушение уникальности — 409 RESOURCE_CONFLICT. Slug после назначения неизменен. Цена номера — положительная decimal string с двумя знаками; снимки ранее созданных броней не переписываются. Объекты создаются как demo=true. Скрытие published=false исключает их из публичного каталога без удаления истории.

Фото: JPEG/PNG до 5 MiB, multipart request до 6 MiB. Проверяются реальные байты, размеры (до 6000 по стороне и 20 млн пикселей), декодирование и перекодирование без исходных метаданных/хвостов, выход до 10 MiB. Сервер генерирует имя UUID; внешние URL, SVG и symlink-пути не разрешены. Фото привязывается отдельным сохранением hotel/room с alt/credit/license; удаление из формы снимает привязку, файл автоматически не удаляет. Непривязанные загрузки сохраняются до отдельной операторской политики очистки.

Исключение к общему no-store: успешный публичный media GET отдаёт image/jpeg или image/png, nosniff и Cache-Control public,max-age=86400,immutable. Cookies через frontend media gateway не передаются и Set-Cookie не выдаётся. Остальные API сохраняют private,no-store. Ошибки административных форм не раскрывают сообщения БД. Таймаут мутации не вызывает автоматическую повторную отправку: UI требует перечитать результат.
