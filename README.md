# Hotel Reservation Service

REST API для бронирования отелей, разработанное на Spring Boot.

## Технологии

* Java 21
* Spring Boot 3
* Spring Data JPA
* PostgreSQL
* Docker
* MapStruct
* Lombok
* Swagger/OpenAPI
* JUnit 5
* Mockito
* Testcontainers

## Функциональность

### Hotels

* CRUD операции
* Изменение рейтинга
* Фильтрация

### Rooms

* CRUD операции
* Поиск свободных комнат
* Фильтрация

### Users

* CRUD операции
* Поиск по имени
* Изменение роли

### Bookings

* Бронирование комнат
* Проверка пересечения дат бронирования
* Получение списка бронирований

## Запуск проекта

Запустить PostgreSQL:

```bash
docker-compose up -d
```

Запустить приложение:

```bash
mvn spring-boot:run
```

## Swagger

Документация API:

```text
http://localhost:8080/swagger-ui/index.html
```

OpenAPI спецификация:

```text
http://localhost:8080/v3/api-docs
```

## Тестирование

Запуск тестов:

```bash
mvn test
```

Используются:

* JUnit 5
* Mockito
* Testcontainers
