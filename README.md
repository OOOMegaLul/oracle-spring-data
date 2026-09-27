# plsql-spring

Процедуры и пакеты PL/SQL как бины Spring, в стиле Spring Data. Вы пишете интерфейс,
реализацию библиотека создаёт сама и при старте сверяет каждый метод со словарём Oracle.

```java
@PlsqlApi(packageName = "APP_CONTEXT")
public interface TenantContext {
    void setTenant(long tenant);     // APP_CONTEXT.SET_TENANT(NTENANT => ?)
    Long getTenant();                // APP_CONTEXT.GET_TENANT
}

@PlsqlApi                            // автономные процедуры и функции
public interface Hr {
    @Procedure("F_UNIT_VERSION")
    long versionOf(String unit);     // NTENANT подставляет ArgumentDefaults

    @SqlQuery("select id, name from employees where dept_id = :deptId")
    List<Employee> employees(long deptId);
}
```

Писалось под Oracle **11.2.0.4**, где JDBC не умеет передавать `BOOLEAN`, `RECORD` и
`XMLTYPE`. На более новых версиях работает так же.

## Подключение

```xml
<dependency>
  <groupId>dev.plsql</groupId>
  <artifactId>plsql-spring</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Java 17+, Spring Framework 6.2+/7, Spring Boot 3.5+/4 (необязательно). Драйвер ojdbc
зафиксирован на ветке 19.x: драйверы 21 и 23 с сервером 11.2 официально не работают.
`orai18n.jar` подтягивается сам. Без него ojdbc не входит в базу с однобайтовой
кодировкой (CL8MSWIN1251 и т.п.) вообще: ORA-17056 уже на логине.

**Spring Boot.** Ничего включать не нужно: автоконфигурация находит `@PlsqlApi` в пакете
приложения, как Spring Data находит репозитории.

**Spring без Boot.** `@EnablePlsqlApis(basePackages = "...")` на конфигурации.

**Без Spring-контекста.**

```java
PlsqlApiFactory factory = PlsqlApiFactory.builder(dataSource)
        .argumentDefaults(ArgumentDefaults.byName(Map.of("NTENANT", ctx::tenant)))
        .build();
Hr hr = factory.create(Hr.class);
```

## Как метод находит процедуру

| Что | Правило |
|---|---|
| Имя подпрограммы | `setTenant` → `SET_TENANT`, или `@Procedure("P_X")` |
| Схема и пакет | `@PlsqlApi(schema, packageName)`; синонимы разрешаются как у компилятора PL/SQL (`DBMS_UTILITY.NAME_RESOLVE`) |
| Аргументы | по имени параметра Java без учёта регистра, подчёркиваний и префиксов `P_`, `V_` и однобуквенных типовых: `tenant` → `NTENANT`, `beginDate` → `DBEGIN_DATE`; или `@Arg("NRN")`. Две равноценные кандидатуры — ошибка при старте, а не угадывание |
| Объект-параметр | record или бин, чьи свойства — аргументы процедуры (как форма) |
| Не переданные аргументы | с `DEFAULT` — пропускаются; контекстные (`NTENANT`...) — из бина `ArgumentDefaults`; остальные — ошибка при старте, либо `NULL` с `@Procedure(nullForMissing = true)` |
| Перегрузки | выбирается та, чьи типы принимают типы параметров Java |
| Результат | возврат функции; единственный OUT; несколько OUT → поля record (каждое поле обязано совпасть с OUT-аргументом); `Optional<T>`; `void` |

Вызов — анонимный блок с именованной нотацией, собранный один раз при старте:

```sql
DECLARE
  v1 BOOLEAN;
BEGIN
  v1 := APP.PKG.FLAG(P_FLAG => (CASE ? WHEN 1 THEN TRUE WHEN 0 THEN FALSE END));
  ? := CASE WHEN v1 THEN 1 WHEN NOT v1 THEN 0 END;
END;
```

## Типы

| Тип PL/SQL | Java | Как |
|---|---|---|
| `NUMBER`, `VARCHAR2`, `DATE`, `TIMESTAMP`, `RAW` | числа, `String`, `java.time`, `byte[]` | прямой bind |
| `CLOB`, `BLOB` | `String`, `byte[]` | временный LOB, освобождается после вызова |
| `BOOLEAN` | `boolean` | через переменную блока (на 11.2 JDBC не умеет) |
| `RECORD`, `%ROWTYPE` | record / бин / `Map` | поле за полем через переменную блока; поля: скаляры, `BOOLEAN`, `XMLTYPE` |
| `XMLTYPE` | `String`, `org.w3c.dom.Document` | через `CLOB` и переменную блока; `NULL` остаётся `NULL` (на 11.2 `XMLTYPE(NULL)` падает) |
| `SYS_REFCURSOR` OUT / IN OUT / возврат | `List<record>` | строки по именам колонок: `BEGIN_DATE` → `beginDate` |
| объектный тип SQL | record / бин | `java.sql.Struct` |
| `TABLE OF` / `VARRAY` | `List` | `java.sql.Array` |
| index-by таблица `NUMBER` / `VARCHAR2` | `List` | `setPlsqlIndexTable` |

Не поддержаны: index-by таблицы записей и дат, `REF CURSOR` как входной параметр,
прочие `OPAQUE`-типы. Такие подпрограммы останавливают старт с понятной причиной.

## Сессия и пул соединений

Код, написанный под APEX или Oracle Forms, часто хранит «кто работает» и контекст в
переменных пакетов и временных таблицах сессии. В пуле соединений это утекает к
следующему пользователю: интеграционный тест `RawJdbcIT` показывает, как пользователь B
видит переменную пакета пользователя A. `SessionContextDataSource` готовит соединение
при каждой выдаче:

```java
SessionContextDataSource ds = new SessionContextDataSource(hikari, currentUser::get);
ds.setResetProbeSql(...);                         // сброс пакетов + что лежит в сессии, один round trip
ds.setScopedInitSql(tenant::get, "...", ...);     // пишется, только если в сессии другое значение
ds.setInitSql("begin apex_application.g_user := ?; end;", currentUser::get);
```

- пользователь уходит в `CLIENT_IDENTIFIER` без отдельного обращения к базе;
- сброс пакетов и запись в сессию нельзя совмещать в одном блоке: сброс применяется
  после блока и стирает записанное (проверено тестом);
- запись во временную таблицу на каждом запросе делает каждый запрос пишущим, и в конце
  он ждёт журнал. Поэтому такая запись идёт по ключу и только при расхождении;
- пул лучше держать в `autoCommit=false`. Одиночный вызов вне `@Transactional`
  библиотека фиксирует сама. Состояние сессии между двумя вызовами сохраняется только
  внутри одной транзакции.

## Прочее

- **Ошибки.** `RAISE_APPLICATION_ERROR` → `PlsqlBusinessException` с кодом и чистым
  текстом без стека ORA-06512. Остальное → стандартная иерархия Spring
  (`DuplicateKeyException`...).
- **ORA-04068.** Если пакет перекомпилировали под живой сессией, вызов повторяется один
  раз: неудачный вызов не выполнялся.
- **Кодировка.** В базе CL8MSWIN1251 символ вне кодовой страницы молча становится `?`.
  По умолчанию библиотека не отправляет такой текст:
  `UnrepresentableCharacterException: SNAME: character 'Ә' (U+04D8) at position 10 ...`.
- **Старт.** Сигнатуры читаются пачкой: пакет целиком одним запросом, автономные
  процедуры интерфейса одним запросом на 900 имён. Невалидный объект при старте
  называется невалидным, а не «не найден»; невалидное тело пакета даёт предупреждение.
- **Настройки Boot** (`plsql.*`): `charset-policy` (`FAIL`/`IGNORE`), `database-charset`,
  `index-table-max-length`, `retry-discarded-state`.
- **Лог.** `dev.plsql.spring=DEBUG` показывает сгенерированные блоки и время вызовов.

## Сборка и тесты

```bash
./mvnw verify
```

Юнит-тесты (77, база не нужна): планировщик, исполнитель на моках JDBC, прокси,
сессия, автоконфигурация, `@EnablePlsqlApis`, разбор исходника, преобразования.

```bash
./mvnw verify -Pit
```

Интеграционные тесты (29) на настоящем Oracle. Схему `PLSQL_IT` тесты создают сами и
пересоздают при каждом запуске. Где взять базу:

- по умолчанию одноразовый контейнер `gvenzl/oracle-xe:11-slim` (Testcontainers, нужен Docker);
- своя база: `-Dplsql.it.url=jdbc:oracle:thin:@//host:1521/SVC -Dplsql.it.dba.password=...`
  (DBA по умолчанию `system`, `-Dplsql.it.dba.user` меняет);
- Windows + Docker Desktop: добавьте `-Dplsql.it.port=1541` и `TESTCONTAINERS_RYUK_DISABLED=true`,
  там случайные порты контейнеров бывают недоступны.

Проверено на Oracle 11.2.0.4 EE в CL8MSWIN1251 и Oracle XE 11.2.0.2 в AL32UTF8.

## Лицензия

[Apache License 2.0](LICENSE).
