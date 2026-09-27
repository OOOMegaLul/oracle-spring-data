# Oracle Spring Data (plsql-spring)

Процедуры и пакеты PL/SQL как бины Spring, в стиле Spring Data: вы пишете интерфейс,
реализацию библиотека создаёт сама и при старте сверяет каждый метод со словарём Oracle.
Сама библиотека (Maven-артефакт) называется `plsql-spring`.

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

**Maven через JitPack.** JitPack сам собирает библиотеку из тега на GitHub; зависимости
(ojdbc, orai18n, Spring) подтягиваются как обычно:

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.OOOMegaLul.oracle-spring-data</groupId>
  <artifactId>plsql-spring</artifactId>
  <version>v0.2.0</version>
</dependency>
```

Gradle: `maven { url 'https://jitpack.io' }` и
`implementation 'com.github.OOOMegaLul.oracle-spring-data:plsql-spring:v0.2.0'`.

**Готовые файлы.** На странице [Releases](https://github.com/OOOMegaLul/oracle-spring-data/releases)
у каждой версии лежат `plsql-spring-<версия>.jar`, исходники (`-sources.jar`) и
документация (`-javadoc.jar`). Зависимости в этом случае кладутся рядом вручную:
`ojdbc10` и `orai18n` версии 19.x, `spring-jdbc`, `spring-context`, `slf4j-api`.

**Из исходников.** `./mvnw install -pl plsql-spring -am`, затем зависимость
`dev.plsql:plsql-spring:<версия из pom.xml>`.

Java 17+, Spring Framework 6.2+/7, Spring Boot 3.5+/4 (необязательно). Драйвер ojdbc
зафиксирован на ветке 19.x: драйверы 21 и 23 с сервером 11.2 официально не работают.
`orai18n.jar` подтягивается сам. Без него ojdbc не входит в базу с однобайтовой
кодировкой (CL8MSWIN1251 и т.п.) вообще: ORA-17056 уже на логине.

**ORA-01882 при подключении к 11.2.** ojdbc передаёт базе часовой пояс JVM по имени, а
Oracle 11.2 не знает, например, `Etc/UTC` — пояс по умолчанию в Docker, Kubernetes и
на CI. Вход падает с `ORA-00604` / `ORA-01882`. Лечится свойством драйвера, тогда
передаётся смещение:

```properties
spring.datasource.hikari.data-source-properties.oracle.jdbc.timezoneAsRegion=false
```

или `-Doracle.jdbc.timezoneAsRegion=false` для всей JVM.

**Spring Boot.** Ничего включать не нужно: автоконфигурация находит `@PlsqlApi` в пакете
приложения, как Spring Data находит репозитории.

**Spring без Boot.** `@EnablePlsqlApis(basePackages = "...")` на конфигурации.

**Несколько баз.** На каждую базу своя конфигурация со своим пакетом интерфейсов:
`@EnablePlsqlApis(basePackages = "...reports", dataSourceRef = "reportsDataSource")`.
Каждая база получает свою фабрику. Своя фабрика (например, поверх `SessionContextDataSource`)
берётся для той базы, с которой она работает; две равноправные фабрики на одну базу — ошибка при
старте с советом указать `factoryRef = "reportsFactory"`. Настройки `plsql.*` действуют и на
фабрики, которые библиотека строит сама.

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
| Аргументы | по имени параметра Java без учёта регистра, подчёркиваний и префиксов `P_`, `V_` и однобуквенных типовых: `tenant` → `NTENANT`, `beginDate` → `DBEGIN_DATE`; или `@Arg("NRN")` (с ним имя сравнивается целиком, без префиксов). Две равноценные кандидатуры — ошибка при старте, а не угадывание |
| Объект-параметр | record или бин, чьи свойства — аргументы процедуры (как форма); `@Arg` работает на компоненте record и на поле, getter'е или setter'е бина |
| Типы | тип параметра Java проверяется при старте: `LocalTime` в `DATE` или строка в `BLOB` — ошибка при старте, а не при вызове. У record или бина, который идёт в `RECORD` или объектный тип, должно быть свойство на каждое поле, иначе ошибка (или `NULL` с `nullForMissing = true`) |
| Не переданные аргументы | с `DEFAULT` — пропускаются; контекстные (`NTENANT`...) — из бина `ArgumentDefaults`; остальные — ошибка при старте, либо `NULL` с `@Procedure(nullForMissing = true)` |
| Перегрузки | выбирается та, чьи типы принимают типы параметров Java |
| Результат | возврат функции; единственный OUT; несколько OUT → поля record, бина или `Map` (каждое поле record обязано совпасть с OUT-аргументом; одно число или строка для нескольких OUT — ошибка при старте); `Optional<T>`; `List`, `Set`; `void`. OUT-аргументы, которые в результат не попадают (у функции или `void`-метода), читаются и отбрасываются |

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
| `NUMBER`, `VARCHAR2`, `DATE`, `TIMESTAMP`, `RAW` | числа, `String`, `java.time` (`LocalDate`, `LocalDateTime`, `Instant`, `OffsetDateTime`, `ZonedDateTime`), `byte[]` | прямой bind; `float`/`double` уходят своей десятичной записью (`0.1f` → `0.1`) |
| `CLOB`, `BLOB` | `String`, `byte[]` | временный LOB, освобождается после вызова |
| `BOOLEAN` | `boolean` | через переменную блока (на 11.2 JDBC не умеет); строка читается одинаково в обе стороны: `Y`/`N`, `TRUE`/`FALSE`, `1`/`0`, иное — ошибка, а не «ложь» |
| `RECORD`, `%ROWTYPE` | record / бин / `Map` | поле за полем через переменную блока; поля: скаляры, `BOOLEAN`, `XMLTYPE`; `%ROWTYPE` таблицы, синонима или курсора пакета |
| `XMLTYPE` | `String`, `org.w3c.dom.Document` | через `CLOB` и переменную блока; `NULL` остаётся `NULL` (на 11.2 `XMLTYPE(NULL)` падает) |
| `SYS_REFCURSOR` OUT / IN OUT / возврат | `List<record>` | колонки ложатся на компоненты по тем же правилам, что и аргументы: `BEGIN_DATE` → `beginDate`, `SNAME` → `name`, `@Arg("D_START")`; неоткрытый курсор — пустой список. Так же и строки `@SqlQuery` |
| объектный тип SQL | record / бин | `java.sql.Struct`; атрибуты `CLOB`/`BLOB` читаются и освобождаются |
| `TABLE OF` / `VARRAY` | `List`, массив (в том числе `long[]`) | `java.sql.Array` |
| index-by таблица `NUMBER` / `VARCHAR2` | `List`, массив | `setPlsqlIndexTable`; длина строки до 32766 |

**Память под выходные index-by таблицы строк.** Драйвер сразу резервирует
`index-table-max-length` элементов по объявленной длине каждый, сколько бы строк процедура ни
вернула. Замер на 11.2.0.4: 10 000 × `VARCHAR2(100)` — 4 МБ и 2 мс на вызов, 10 000 × 4000 —
190 МБ и 58 мс (так резервировала 0.1.0 для любой таблицы). Длина берётся из объявления, а
резерв больше 50 млн символов (около 240 МБ) — ошибка при старте с подсказкой, до скольки
снизить `plsql.index-table-max-length`.

Не поддержаны: index-by таблицы записей и дат, `REF CURSOR` как входной параметр, записи внутри
записей, прочие `OPAQUE`-типы, `XMLTYPE` и `ANYDATA` как атрибут объектного типа SQL (внутри
`Struct` их без библиотек XDB не передать), вызовы через database link, `%ROWTYPE` в пакете,
зашифрованном через `wrap` (таблицу не прочитать из исходника). Такие подпрограммы
останавливают старт с понятной причиной.

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
  внутри одной транзакции;
- то, что нужно каждому соединению независимо от пользователя (`ALTER SESSION SET
  CURRENT_SCHEMA`, роли), задаётся в самом пуле (`connectionInitSql` у HikariCP), а не в
  `initSql`: словарь при старте читается мимо `SessionContextDataSource`, потому что
  пользователя при старте нет;
- ключ сессии сравнивается строго: строковые коды `"007"` и `"7"` разные, числа — как числа.

## Прочее

- **Ошибки.** `RAISE_APPLICATION_ERROR` → `PlsqlBusinessException` с кодом и чистым
  текстом без стека ORA-06512. Остальное → стандартная иерархия Spring
  (`DuplicateKeyException`...).
- **ORA-04068.** Если пакет перекомпилировали под живой сессией, вызов вне транзакции
  повторяется один раз на заново взятом соединении. Изменения данных неудачного вызова Oracle
  уже откатил сам; не откатываются только автономные транзакции, последовательности и действия
  вне базы (файлы, почта). Внутри `@Transactional` повтора нет: переменные пакетов, которые
  положили туда предыдущие вызовы транзакции (контекст пользователя), уже стёрты, и ошибка
  откатывает транзакцию.
- **Кодировка.** В базе CL8MSWIN1251 символ вне кодовой страницы молча становится `?`.
  По умолчанию библиотека не отправляет такой текст:
  `UnrepresentableCharacterException: SNAME: character 'Ә' (U+04D8) at position 10 ...`.
  Проверка знает 36 кодировок Oracle (кириллица, латиница, греческий, иврит, арабский,
  восточноазиатские); про незнакомую пишет предупреждение в лог. Текст проверяется до того, как
  что-либо уходит драйверу.
- **Старт.** Сигнатуры читаются пачкой: пакет целиком одним запросом, автономные
  процедуры интерфейса одним запросом на 900 имён. Невалидный объект при старте
  называется невалидным, а не «не найден»; невалидное или отсутствующее тело пакета даёт
  предупреждение. Словарь и кодировку фабрика читает мимо `SessionContextDataSource`: при
  старте поставщики пользователя не вызываются.
- **Настройки Boot** (`plsql.*`): `charset-policy` (`FAIL`/`IGNORE`), `database-charset`,
  `index-table-max-length`, `retry-discarded-state`.
- **Лог.** `dev.plsql.spring=DEBUG` показывает сгенерированные блоки и время вызовов и
  запросов `@SqlQuery`. `PlsqlApiInvocationHandler.sqlOf(api, "method", типы...)` отдаёт блок
  метода в тестах.

## Сборка и тесты

```bash
./mvnw verify
```

Выпуск версии: тег `vX.Y.Z` (совпадающий с версией в `pom.xml`) запускает workflow
`release`, который собирает jar, исходники и javadoc и выкладывает их в Releases.

Юнит-тесты (131, база не нужна): планировщик, исполнитель на моках JDBC, прокси,
сессия, автоконфигурация, `@EnablePlsqlApis`, разбор исходника, преобразования.

```bash
./mvnw verify -Pit
```

Интеграционные тесты (32) на настоящем Oracle. Схему `PLSQL_IT` тесты создают сами и
пересоздают при каждом запуске. Где взять базу:

- по умолчанию одноразовый контейнер `gvenzl/oracle-xe:11-slim` (Testcontainers, нужен Docker);
- своя база: `-Dplsql.it.url=jdbc:oracle:thin:@//host:1521/SVC -Dplsql.it.dba.password=...`
  (DBA по умолчанию `system`, `-Dplsql.it.dba.user` меняет);
- Windows + Docker Desktop: добавьте `-Dplsql.it.port=1541` и `TESTCONTAINERS_RYUK_DISABLED=true`,
  там случайные порты контейнеров бывают недоступны.

Проверено на Oracle 11.2.0.4 EE в CL8MSWIN1251 и Oracle XE 11.2.0.2 в AL32UTF8.

## Лицензия

[Apache License 2.0](LICENSE).
