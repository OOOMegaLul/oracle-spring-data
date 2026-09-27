# Пример: Oracle Spring Data на Spring Boot

Маленькое приложение, которое вызывает пакет PL/SQL `HR_API` через интерфейс Java. Подробно
всё разобрано в [руководстве](../../docs/guide.md).

## Запуск

1. Выполните [`src/main/resources/db/hr_api.sql`](src/main/resources/db/hr_api.sql) в своей
   схеме Oracle (SQL Developer или `sqlplus`): он создаёт таблицу `EMPLOYEES` и пакеты `HR_API`,
   `APP_CONTEXT`.
2. Впишите подключение к этой схеме в
   [`application.properties`](src/main/resources/application.properties).
3. Из корня репозитория:

   ```bash
   ./mvnw -f examples/quickstart/pom.xml spring-boot:run
   ```

Приложение напечатает, что делает каждый вызов, а в логе (`DEBUG`) — блоки PL/SQL, которые
собрала библиотека.

С профилем `session` соединения из пула готовятся под пользователя
(`SessionContextDataSource`, класс `SessionConfig`):

```bash
./mvnw -f examples/quickstart/pom.xml spring-boot:run -Dspring-boot.run.profiles=session
```

## Что где

| Файл | Что показывает |
|---|---|
| `HrApi.java` | интерфейс к пакету: `OUT`-аргумент, `DEFAULT`, `BOOLEAN`, курсор → `List<record>`, `Optional` |
| `HrService.java` | два вызова в одной транзакции (`@Transactional`) |
| `HrApplication.java` | вызовы по очереди и бизнес-ошибка из `RAISE_APPLICATION_ERROR` |
| `SessionConfig.java` | пул и `SessionContextDataSource` для кода, который хранит пользователя в пакете |
| `AppContext.java` | чтение того, что записал `SessionConfig` |
