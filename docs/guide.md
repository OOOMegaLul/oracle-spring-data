# Руководство: Oracle Spring Data (plsql-spring)

Как вызывать процедуры и функции PL/SQL из Spring так же просто, как репозитории Spring Data.
Руководство идёт от простого к сложному: первые три раздела хватит, чтобы начать, остальное —
по мере надобности.

Всё, что показано в «Быстром старте», лежит рабочим проектом в
[`examples/quickstart`](../examples/quickstart) и проверено на Oracle 11.2.0.4.

**Содержание**

1. [Идея в двух словах](#1-идея-в-двух-словах)
2. [Быстрый старт](#2-быстрый-старт)
3. [Как метод находит процедуру](#3-как-метод-находит-процедуру)
4. [Аргументы](#4-аргументы)
5. [Результат метода](#5-результат-метода)
6. [Типы Oracle и Java](#6-типы-oracle-и-java)
7. [Обычный SQL: `@SqlQuery`](#7-обычный-sql-sqlquery)
8. [Ошибки](#8-ошибки)
9. [Транзакции](#9-транзакции)
10. [Пул соединений и состояние сессии](#10-пул-соединений-и-состояние-сессии)
11. [Несколько баз](#11-несколько-баз)
12. [Без Spring Boot и без Spring](#12-без-spring-boot-и-без-spring)
13. [Настройки](#13-настройки)
14. [Тесты своего кода](#14-тесты-своего-кода)
15. [Ошибки при старте: что они значат](#15-ошибки-при-старте-что-они-значат)
16. [Чего библиотека не умеет](#16-чего-библиотека-не-умеет)

---

## 1. Идея в двух словах

Вы пишете интерфейс Java — по методу на процедуру или функцию:

```java
@PlsqlApi(packageName = "HR_API")
public interface HrApi {
    long hire(String name);          // HR_API.HIRE
    boolean isActive(long id);       // HR_API.IS_ACTIVE
}
```

Реализацию библиотека делает сама, как Spring Data делает реализацию репозитория. При старте
приложения она читает из словаря Oracle, какие у каждой процедуры аргументы и типы, и сверяет
их с методом. Если что-то не сходится (опечатка в имени, лишний параметр, неподходящий тип),
приложение **не стартует** и пишет, что именно не так. Ошибка видна сразу, а не когда
пользователь нажмёт кнопку.

Внутри каждый метод — анонимный блок PL/SQL, собранный один раз при старте. Поэтому работают
типы, которые обычный JDBC на Oracle 11 передать не может: `BOOLEAN`, записи (`RECORD`,
`%ROWTYPE`), `XMLTYPE`.

## 2. Быстрый старт

### 2.1. Подключить библиотеку

В `pom.xml` приложения Spring Boot:

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
  </dependency>
  <dependency>
    <groupId>com.github.OOOMegaLul.oracle-spring-data</groupId>
    <artifactId>plsql-spring</artifactId>
    <version>v0.3.0</version>
  </dependency>
</dependencies>
```

Драйвер Oracle (`ojdbc10` и `orai18n`) библиотека подтягивает сама. Нужны Java 17+ и
Spring Boot 3.5+ или 4.

> **Важно: флаг `-parameters`.** Библиотека сопоставляет параметры методов с аргументами
> процедур по именам. Имена параметров видны, только если код скомпилирован с флагом
> `-parameters`. У проекта с родителем `spring-boot-starter-parent` он включён сам. Если родителя
> нет, включите его в `maven-compiler-plugin`: `<parameters>true</parameters>`. Без флага при
> старте будет ошибка вида `parameter 'arg0' has no matching argument ... (compile with
> -parameters)`.

### 2.2. Настроить подключение

`application.properties`:

```properties
spring.datasource.url=jdbc:oracle:thin:@//db-host:1521/ORCL
spring.datasource.username=hr_demo
spring.datasource.password=hr_demo

# Oracle 11.2 не знает часовой пояс Etc/UTC (он по умолчанию в Docker и на серверах):
# без этой строки вход падает с ORA-01882.
spring.datasource.hikari.data-source-properties.oracle.jdbc.timezoneAsRegion=false

# Рекомендуется: без автоматической фиксации (см. раздел «Транзакции»).
spring.datasource.hikari.auto-commit=false
```

### 2.3. Процедуры в базе

Для примера — пакет `HR_API` (полный скрипт:
[`examples/quickstart/.../db/hr_api.sql`](../examples/quickstart/src/main/resources/db/hr_api.sql)):

```sql
create or replace package hr_api as
  procedure hire(p_name varchar2, p_hired date default sysdate, p_id out number);
  function  find_name(p_id number) return varchar2;
  function  is_active(p_id number) return boolean;
  procedure list_active(p_cur out sys_refcursor);
  procedure fire(p_id number);
end hr_api;
```

`hire` при пустом имени делает `raise_application_error(-20001, 'Имя сотрудника не задано')`.

### 2.4. Интерфейс

```java
@PlsqlApi(packageName = "HR_API")
public interface HrApi {

    record Employee(long id, String fullName, LocalDate hired) {}

    long hire(String name);             // P_NAME ← name; P_HIRED по умолчанию; P_ID (OUT) → результат
    Optional<String> findName(long id); // функция может вернуть NULL → Optional
    boolean isActive(long id);          // BOOLEAN
    List<Employee> listActive();        // курсор → список; FULL_NAME → fullName
    void fire(long id);
}
```

Больше ничего не нужно: Spring Boot сам найдёт интерфейсы с `@PlsqlApi` в пакете приложения
(как находит репозитории Spring Data) и сделает из них бины.

### 2.5. Пользоваться

```java
@Service
public class HrService {
    private final HrApi hr;

    public HrService(HrApi hr) {
        this.hr = hr;
    }

    @Transactional
    public long hireForOneDay(String name) {
        long id = hr.hire(name);
        hr.fire(id);
        return id;
    }
}
```

Пример при запуске печатает (проверено на Oracle 11.2.0.4, библиотека из JitPack):

```text
Пользователь сеанса: (не задан)
Принят сотрудник №1
Имя: Иванов Иван
Работает: true
Несуществующий №-1: нет такого
Принят и уволен №2, работает: false
В штате: Employee[id=1, fullName=Иванов Иван, hired=2026-09-27]
Ошибка из PL/SQL, код 20001: Имя сотрудника не задано
```

С `logging.level.dev.plsql.spring=DEBUG` в логе видны блоки, которые библиотека вызывает, и
время каждого вызова:

```text
HrApi.hire:
BEGIN
  HR_DEMO.HR_API.HIRE(P_NAME => ?, P_ID => ?);
END;
```

## 3. Как метод находит процедуру

**Пакет и схема** — из `@PlsqlApi`:

| Написано | Где ищется |
|---|---|
| `@PlsqlApi(packageName = "HR_API")` | пакет `HR_API` в своей схеме или по синониму |
| `@PlsqlApi(schema = "HR", packageName = "HR_API")` | пакет `HR.HR_API` |
| `@PlsqlApi` без `packageName` | автономные процедуры и функции (вне пакетов) |

Синонимы разрешаются так же, как их разрешил бы сам PL/SQL (через `DBMS_UTILITY.NAME_RESOLVE`).

**Имя процедуры** — из имени метода: слова через подчёркивание, всё в верхнем регистре.

| Метод | Процедура |
|---|---|
| `hire` | `HIRE` |
| `setTenant` | `SET_TENANT` |
| `loadXMLData` | `LOAD_XML_DATA` |
| `getURL` | `GET_URL` |

Если имя другое — `@Procedure`:

```java
@Procedure("P_EMPLOYEE_HIRE")
long hire(String name);
```

**Перегрузки.** Если в пакете несколько процедур с одним именем, выбирается та, чьи аргументы
подходят к параметрам метода по именам, а если и имена одинаковые — по типам (`long` →
`NUMBER`, `String` → `VARCHAR2`). Если подходят две одинаково — ошибка при старте, а не
угадывание.

## 4. Аргументы

### 4.1. По именам

Параметр метода находит аргумент процедуры по имени — без учёта регистра, подчёркиваний и
типовых префиксов (`P_`, `V_`, однобуквенных вроде `N`, `S`, `D`):

| Параметр Java | Подходящий аргумент |
|---|---|
| `name` | `P_NAME`, `SNAME`, `NAME` |
| `tenant` | `NTENANT`, `P_TENANT` |
| `beginDate` | `DBEGIN_DATE`, `P_BEGIN_DATE` |

Порядок параметров значения не имеет: вызов идёт с именованными аргументами
(`P_NAME => ?`). Если имя не угадывается — `@Arg`:

```java
long hire(@Arg("P_FULL_NAME") String name);
```

С `@Arg` имя сравнивается целиком, только без учёта регистра.

### 4.2. Что будет с аргументами, которых нет в методе

| Аргумент | Что происходит |
|---|---|
| с `DEFAULT` в PL/SQL | не передаётся, база подставит значение по умолчанию |
| есть в бине `ArgumentDefaults` | значение берётся оттуда (см. ниже) |
| `IN OUT` | передаётся `NULL` |
| остальные | ошибка при старте; или `NULL`, если на методе `@Procedure(nullForMissing = true)` |

### 4.3. Одинаковые для всех аргументы: `ArgumentDefaults`

Если у многих процедур есть аргумент «организация» или «пользователь», который приложение
знает само, его не нужно тащить в каждый метод. Объявите бин:

```java
@Bean
ArgumentDefaults argumentDefaults(CurrentUser user) {
    return ArgumentDefaults.byName(Map.of(
            "NTENANT", user::tenantId,      // вызывается при каждом вызове процедуры
            "SUSER",   user::login));
}
```

Теперь метод `void fire(long id)` для процедуры `FIRE(NTENANT, P_ID)` работает: `NTENANT`
подставится сам. Поставщики вызываются только при вызове процедуры, не при старте.

### 4.4. Объект вместо длинного списка параметров

У процедуры 20 аргументов? Передайте record или бин — его свойства разойдутся по аргументам:

```java
record NewEmployee(String name, LocalDate hired, @Arg("NDEPT") long department) {}

long hire(NewEmployee e);    // name → P_NAME, hired → P_HIRED, department → NDEPT
```

Каждое свойство обязано найти свой аргумент — иначе ошибка при старте.

### 4.5. Типы параметров проверяются при старте

`LocalTime` там, где процедура ждёт `DATE`, строка вместо `BLOB`, число вместо записи —
ошибка при старте. `Object` проходит: что в нём лежит, видно только при вызове.

## 5. Результат метода

| Метод | Процедура | Что вернётся |
|---|---|---|
| `long f(...)` | функция | результат функции |
| `long p(...)` | процедура с одним `OUT` | значение этого `OUT` |
| `Result p(...)` | процедура с несколькими `OUT` | record/бин, компоненты по именам `OUT`-аргументов |
| `Map<String, Object> p(...)` | несколько `OUT` | карта «имя аргумента → значение» |
| `Optional<T>` | любая | пусто вместо `NULL` |
| `List<T>`, `Set<T>` | курсор, коллекция, index-by таблица | список или множество |
| `void` | любая | ничего; `OUT`-аргументы читаются и отбрасываются |

Несколько `OUT` в record:

```java
record Totals(BigDecimal sum, @Arg("NCOUNT") int count) {}

Totals totals(long deptId);    // TOTALS(P_DEPT_ID, P_SUM OUT, NCOUNT OUT)
```

Каждый компонент record обязан совпасть с каким-то `OUT`-аргументом — иначе ошибка при
старте: компонент всегда оставался бы пустым.

**`NULL` в примитив — ошибка**, как в Spring Data. Если функция вернула `NULL`, а метод
объявлен `long`, будет `EmptyResultDataAccessException` с именем метода. Для значения,
которое может быть `NULL`, объявляйте `Long`, `Boolean` или `Optional<...>`.

## 6. Типы Oracle и Java

| PL/SQL | Java | Заметки |
|---|---|---|
| `NUMBER`, `INTEGER`, `PLS_INTEGER` | `long`, `int`, `BigDecimal`, `double`... | `0.1f` уходит как `0.1` |
| `VARCHAR2`, `CHAR`, `NVARCHAR2` | `String`, `enum` (по имени) | |
| `DATE`, `TIMESTAMP` | `LocalDate`, `LocalDateTime`, `Instant`, `OffsetDateTime`, `ZonedDateTime`, `java.util.Date` | |
| `CLOB`, `BLOB` | `String`, `byte[]` | временный LOB освобождается после вызова |
| `BOOLEAN` | `boolean`, `Boolean` | строки `Y`/`N`, `1`/`0`, `TRUE`/`FALSE`; другое — ошибка |
| `RECORD`, `%ROWTYPE` | record, бин, `Map` | поле за полем по именам; запись внутри записи — вложенный record |
| `XMLTYPE` | `String`, `org.w3c.dom.Document` | `NULL` остаётся `NULL` |
| `SYS_REFCURSOR` | `List<record>` | колонки по именам; неоткрытый курсор — пустой список |
| объектный тип SQL (`CREATE TYPE ... AS OBJECT`) | record, бин, `Map` | |
| коллекция SQL (`TABLE OF`, `VARRAY`) | `List`, массив | |
| index-by таблица `NUMBER`/`VARCHAR2` | `List`, массив | см. ниже про память |

**Записи и курсоры по именам.** Поле `FULL_NAME` ложится в компонент `fullName`, колонка
`SNAME` — в `name` (типовой префикс снимается), а `@Arg("D_START")` на компоненте задаёт имя
явно. У бина `@Arg` ставится на поле, getter или setter.

**Запись внутри записи.** Поле записи может само быть записью (или `%ROWTYPE` таблицы), на любую
глубину. В Java это вложенный record:

```sql
type addr_t   is record (city varchar2(50), street varchar2(100), verified boolean);
type person_t is record (id number, name varchar2(100), addr addr_t);
procedure save_person(p_p in out person_t);
```

```java
record Addr(String city, String street, Boolean verified) {}
record Person(Long id, String name, Addr addr) {}

Person savePerson(Person p);
```

При старте проверяется и вложенный record: если в `Addr` нет поля для `VERIFIED`, ошибка
назовёт путь `ADDR.VERIFIED`.

**Выходные index-by таблицы строк и память.** Драйвер Oracle заранее резервирует память под
`plsql.index-table-max-length` элементов (по умолчанию 10 000) по объявленной длине каждый —
сколько бы строк процедура ни вернула. Для `VARCHAR2(100)` это около 4 МБ на вызов, для
`VARCHAR2(4000)` — около 190 МБ. Если резерв получается больше 240 МБ, приложение не стартует и
подсказывает, до скольки уменьшить `plsql.index-table-max-length`.

## 7. Обычный SQL: `@SqlQuery`

Не всё надо заворачивать в процедуры. Запрос прямо на методе:

```java
@SqlQuery("select id, full_name, hired from employees where id = :id")
Optional<Employee> find(long id);

@SqlQuery("select id, full_name, hired from employees where hired >= :from order by id")
List<Employee> hiredSince(LocalDate from);

@SqlQuery("update employees set active = 0 where hired < :before")
int fireAllHiredBefore(LocalDate before);    // число изменённых строк
```

- Параметры `:id`, `:from` — по именам параметров метода (или `@Arg("имя")`). Параметр, которого
  нет у метода, — ошибка при старте.
- Строки ложатся в record по тем же правилам, что и курсоры.
- Результат: список, `Optional`, одна строка (ошибка, если строк больше одной), или число
  изменённых строк для `insert`/`update`/`delete` (`int`, `long`, `boolean`).

## 8. Ошибки

| Что случилось в базе | Что получит Java |
|---|---|
| `raise_application_error(-20001, 'текст')` | `PlsqlBusinessException`: `getErrorCode()` = 20001, `getMessage()` = `'текст'` — без стека `ORA-06512` |
| нарушение уникальности (ORA-00001) | `DuplicateKeyException` |
| прочие ошибки Oracle | стандартные исключения Spring (`DataIntegrityViolationException`, `BadSqlGrammarException`...) |
| `NULL` в примитивный результат | `EmptyResultDataAccessException` |

Все они — `DataAccessException`, то есть `RuntimeException`: в `@Transactional` откатывают
транзакцию.

```java
try {
    hr.hire(null);
} catch (PlsqlBusinessException e) {
    // e.getErrorCode() == 20001, e.getMessage() == "Имя сотрудника не задано"
}
```

**Текст, который база не сможет сохранить.** В базе с однобайтовой кодировкой
(CL8MSWIN1251 и т. п.) символ вне кодовой страницы (казахская «Ә», эмодзи) обычный JDBC молча
превращает в `?`. Библиотека такой текст не отправляет: `UnrepresentableCharacterException` с
именем аргумента и позицией символа. Отключается настройкой `plsql.charset-policy=IGNORE`.

**Пакет перекомпилировали под работающим приложением** (ORA-04068). Вне транзакции вызов
повторяется один раз сам. Внутри `@Transactional` — нет (см. следующий раздел).

## 9. Транзакции

Библиотека берёт соединение у Spring так же, как `JdbcTemplate`, поэтому `@Transactional`
работает как обычно.

**Внутри `@Transactional`** все вызовы идут через одно соединение — один сеанс Oracle. Spring
делает `commit` в конце метода или `rollback` при исключении:

```java
@Transactional
public void transfer(long from, long to) {
    hr.fire(from);
    hr.hire(nameOf(to));   // ошибка здесь → откатится и fire
}
```

- Переменные пакетов между вызовами внутри одной транзакции живут: сеанс тот же.
- Если пакет перекомпилировали посреди транзакции (ORA-04068), повтора нет: переменные пакетов,
  которые положили туда предыдущие вызовы, уже стёрты. Исключение откатывает транзакцию.

**Без `@Transactional`** каждый вызов — сам по себе. При `auto-commit=false` библиотека сама
делает `commit` после успешного вызова и `rollback` после ошибки. Соединение каждый раз может
достаться другое, поэтому переменные пакетов между двумя такими вызовами не живут.

**Ловушка.** Менеджер транзакций должен работать с тем же `DataSource`, что и библиотека. Если
библиотека получила `SessionContextDataSource` (раздел 10), а транзакции настроены на пул под
ним, вызовы молча пойдут мимо транзакции. В Spring Boot всё сходится само, если
`SessionContextDataSource` — главный (`@Primary`) бин `DataSource`.

С JPA в одной транзакции тоже работает, если `DataSource` общий.

## 10. Пул соединений и состояние сессии

Этот раздел важен, если PL/SQL писался под APEX или Oracle Forms и хранит «кто работает»
(пользователя, организацию) в переменных пакетов или во временных таблицах сессии.

**В чём проблема.** Пул выдаёт одно и то же соединение Oracle разным пользователям по очереди.
Переменные пакетов живут в сеансе, поэтому пользователь B увидит то, что положил туда
пользователь A. Это утечка данных (тест `RawJdbcIT` показывает её вживую).

**Решение — `SessionContextDataSource`.** Обёртка над пулом, которая готовит сеанс при каждой
выдаче соединения:

```java
@Configuration
public class SessionConfig {

    // Сам пул: настройки spring.datasource.hikari.* применяются к нему.
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource pool(DataSourceProperties props) {
        return props.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    // Главный DataSource приложения — обёртка над пулом. Её получают и библиотека,
    // и менеджер транзакций, и JdbcTemplate.
    @Bean
    @Primary
    SessionContextDataSource dataSource(HikariDataSource pool, CurrentUser user) {
        SessionContextDataSource ds = new SessionContextDataSource(pool, user::login);
        // Сброс состояния пакетов включён по умолчанию; initSql выполняется после него.
        ds.setInitSql("begin app_context.set_user(?); end;", user::login);
        return ds;
    }
}
```

`CurrentUser` — ваш бин, который знает текущего пользователя (например, из Spring Security).
Рабочий вариант с постоянным пользователем — в примере: `SessionConfig`, профиль `session`. С ним
первая строка вывода примера меняется на `Пользователь сеанса: DEMO_USER`: сеанс подготовлен под
пользователя, а транзакции в `HrService` идут через ту же обёртку.

Что происходит при каждой выдаче соединения:

1. сброс состояния пакетов (`DBMS_SESSION.MODIFY_PACKAGE_STATE`) — переменные предыдущего
   пользователя стёрты;
2. имя пользователя записывается в `CLIENT_IDENTIFIER` сеанса (видно администратору в
   `V$SESSION`) — без отдельного обращения к базе;
3. ваш `initSql` — например, положить пользователя в контекст приложения.

Если контекст хранится во временной таблице (запись в неё на каждом запросе заметно
медленнее — запрос становится пишущим), есть `setResetProbeSql` + `setScopedInitSql`: запись
делается, только когда в сеансе лежит другое значение. Подробности — в Javadoc
`SessionContextDataSource`.

Всё, что нужно каждому соединению **независимо** от пользователя (`ALTER SESSION SET
CURRENT_SCHEMA`, роли), задавайте в самом пуле — у HikariCP это
`spring.datasource.hikari.connection-init-sql`. Словарь при старте библиотека читает мимо
`SessionContextDataSource`: при старте пользователя ещё нет.

## 11. Несколько баз

Интерфейсы каждой базы — в своём пакете, и на каждую базу — своя конфигурация:

```java
@Configuration
@EnablePlsqlApis(basePackages = "com.example.reports", dataSourceRef = "reportsDataSource")
class ReportsConfig {
}
```

Интерфейсы из `com.example.reports` пойдут в `reportsDataSource`. Если для базы собрана своя
фабрика (например, поверх `SessionContextDataSource`), она найдётся сама; если таких фабрик две
— укажите её явно: `factoryRef = "reportsFactory"`.

## 12. Без Spring Boot и без Spring

**Spring без Boot:** `@EnablePlsqlApis(basePackages = "com.example.hr")` на конфигурации и
бин `DataSource` с именем `dataSource`.

**Совсем без Spring-контекста:**

```java
PlsqlApiFactory factory = PlsqlApiFactory.builder(dataSource)
        .argumentDefaults(ArgumentDefaults.byName(Map.of("NTENANT", () -> 1001L)))
        .build();
HrApi hr = factory.create(HrApi.class);   // здесь же проверка против словаря
```

## 13. Настройки

`application.properties`, все необязательны:

| Настройка | По умолчанию | Что делает |
|---|---|---|
| `plsql.charset-policy` | `FAIL` | `IGNORE` — не проверять, помещается ли текст в кодировку базы |
| `plsql.database-charset` | читается из базы | кодировка базы, например `CL8MSWIN1251`: не нужно лишнее обращение при старте |
| `plsql.index-table-max-length` | `10000` | сколько элементов может вернуть выходная index-by таблица |
| `plsql.retry-discarded-state` | `true` | повторять ли вызов вне транзакции после ORA-04068 |

Лог: `logging.level.dev.plsql.spring=DEBUG` — сгенерированные блоки и время каждого вызова.

## 14. Тесты своего кода

**Юнит-тесты.** `HrApi` — обычный интерфейс, его можно подменить Mockito:

```java
HrApi hr = Mockito.mock(HrApi.class);
when(hr.hire("Иванов")).thenReturn(42L);
new HrService(hr).hireForOneDay("Иванов");
verify(hr).fire(42L);
```

**Интеграционные тесты** — на настоящей базе: одноразовый Oracle в Docker через Testcontainers
(образ `gvenzl/oracle-xe:11-slim`, так тестируется сама библиотека). Проверка «интерфейс
совпадает с базой» происходит при создании контекста, поэтому даже пустой тест
`@SpringBootTest` уже ловит расхождения.

**Посмотреть блок метода:**
`PlsqlApiInvocationHandler.sqlOf(hr, "hire")` вернёт текст анонимного блока.

## 15. Ошибки при старте: что они значат

Все расхождения выводятся одним исключением, по строке на метод:

```text
com.example.hr.HrApi does not match the database:
  - HrApi.hire -> HR_API.HIRE: parameter 'fullName' has no matching argument; arguments are [P_NAME IN, P_HIRED IN DEFAULT, P_ID OUT]
  - HrApi.isActiv -> HR_API.IS_ACTIV: not found in the database
```

| Сообщение | Что делать |
|---|---|
| `not found in the database` | нет такой процедуры: проверьте имя метода (`isActiv` → `IS_ACTIV`) или `@Procedure`, пакет, схему, права (`EXECUTE`) |
| `... is INVALID in the database` | процедура есть, но не компилируется: перекомпилируйте её в базе |
| `parameter 'x' has no matching argument; arguments are [...]` | имя параметра не нашло аргумент: переименуйте параметр или поставьте `@Arg`; в конце — список настоящих аргументов |
| `(compile with -parameters)` | код собран без флага `-parameters` (раздел 2.1) |
| `'x' matches several arguments` | имя одинаково подходит к двум аргументам: `@Arg` с точным именем |
| `required argument X is not supplied` | у аргумента нет `DEFAULT`: добавьте параметр, `ArgumentDefaults` или `nullForMissing` |
| `parameter 'x' is LocalTime, which cannot be passed as DATE` | неподходящий тип параметра |
| `has no property for [F] of P_REC` | у record/бина нет свойства для поля записи: добавьте его или `nullForMissing` |
| `components [c] match no OUT argument` | компонент record-результата не совпал ни с одним `OUT`: переименуйте или `@Arg` |
| `returns long but the procedure has 2 OUT arguments` | несколько `OUT` не влезут в одно число: верните record или `Map` |
| `matches 2 overloads equally` | две перегрузки подходят одинаково: `@Arg` или другие типы параметров |
| `would reserve about N MB per call` | выходная index-by таблица слишком велика: уменьшите `plsql.index-table-max-length` |
| `not callable: ...` | форма аргумента, которую библиотека не поддерживает (раздел 16) |
| `ORA-01882` при подключении | нет `oracle.jdbc.timezoneAsRegion=false` (раздел 2.2) |
| `ORA-17056` при подключении | нет `orai18n.jar` (подтягивается сам, если не исключён вручную) |

## 16. Чего библиотека не умеет

Такие подпрограммы останавливают старт с понятной причиной (`not callable: ...`):

- **Таблица или коллекция как поле записи**: `type order_t is record (id number, lines line_tab)`.
  (Запись внутри записи поддерживается, см. раздел 6.)
- **index-by таблицы записей и дат** (`TABLE OF rec_t INDEX BY ...`): драйвер Oracle 11 их не
  передаёт.
- **Курсор на вход** (`p_cur IN SYS_REFCURSOR`): открытый курсор из Java не передать.
- **`XMLTYPE` и `ANYDATA` внутри объектного типа SQL.**
- **Вызов через database link** (синоним на процедуру в другой базе).
- **`%ROWTYPE` в пакете, зашифрованном `wrap`**: имя таблицы не прочитать из исходника.

Для таких процедур можно написать маленькую процедуру-обёртку в PL/SQL, которая принимает
простые типы, или вызвать их через `JdbcTemplate` вручную.
