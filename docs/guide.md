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
11. [Долгие вызовы: срок](#11-долгие-вызовы-срок)
12. [Отладочный вывод и метрики](#12-отладочный-вывод-и-метрики)
13. [Несколько баз](#13-несколько-баз)
14. [Без Spring Boot и без Spring](#14-без-spring-boot-и-без-spring)
15. [Настройки](#15-настройки)
16. [Тесты своего кода](#16-тесты-своего-кода)
17. [Ошибки при старте: что они значат](#17-ошибки-при-старте-что-они-значат)
18. [Чего библиотека не умеет](#18-чего-библиотека-не-умеет)
19. [Рядом со Spring Data JPA и JDBC на Oracle 11g](#19-рядом-со-spring-data-jpa-и-jdbc-на-oracle-11g)

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
<properties>
  <!-- Все артефакты Oracle — версии 19.x (см. ниже). -->
  <oracle-database.version>19.32.0.0</oracle-database.version>
</properties>

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
    <version>v0.4.0</version>
  </dependency>
</dependencies>
```

Драйвер Oracle (`ojdbc10` и `orai18n`) библиотека подтягивает сама. Нужны Java 17+ и
Spring Boot 3.5+ или 4.

Зачем `oracle-database.version`: Spring Boot назначает всем артефактам Oracle версию 23.x, а
Oracle 11.2 официально поддерживает только драйвер 19.x. Без этой строки `orai18n` приходит
версии 23.x к драйверу 19.x из библиотеки. Свой драйвер (`ojdbc11`) добавлять не нужно, даже
для JPA: `ojdbc10` из библиотеки обслуживает всё приложение. Без родителя
`spring-boot-starter-parent` свойство не действует — укажите версию `orai18n` в
`<dependencyManagement>` явно.

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
| `Stream<T>` | курсор (`OUT` или результат функции) | строки по одной, пока их берут (ниже) |
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

### Большие выборки: `Stream`

`List` собирает все строки курсора в памяти, прежде чем вернуть их: выгрузка в миллион строк —
миллион объектов в памяти разом. `Stream` читает строки по одной, пока вы их берёте:

```java
@Procedure("LIST_ACTIVE")
Stream<Employee> activeStream();              // курсор процедуры

@SqlQuery("select id, full_name, hired from employees")
Stream<Employee> everyone();                  // или обычный запрос
```

```java
try (Stream<Employee> s = hr.activeStream()) {
    s.forEach(csv::write);
}
```

Строки приходят из базы пачками по 100 (`plsql.fetch-size`); драйвер сам брал бы по 10, и
каждая пачка — отдельное обращение к базе. Замер на 11.2.0.4: 200 000 строк по 10 читаются
9,9 с, по 100 — 1,1 с, по 500 — 0,3 с. То же касается и `List`.

**Поток обязательно закрывать**, лучше через `try (...)`, как выше. Пока он открыт, он держит
курсор в базе и соединение пула. При закрытии библиотека закрывает курсор, фиксирует свою
единицу работы (или откатывает, если чтение сорвалось на ошибке) и возвращает соединение в пул.
Внутри `@Transactional` соединение принадлежит транзакции, поэтому дочитать поток нужно до её
конца.

Потоком читается только курсор, который целиком и есть результат метода. Поток вместе со
страницами (`Pageable`) или сортировкой (`Sort`) не работает.

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
- Результат: список, `Optional`, одна строка (ошибка, если строк больше одной), число
  изменённых строк для `insert`/`update`/`delete` (`int`, `long`, `boolean`) или `Stream` для
  больших выборок (раздел 5).

### Страницы и сортировка

Как в Spring Data: параметр `Pageable` или `Sort`, результат `Page`, `Slice` или `List`.

```java
@SqlQuery("select id, full_name, hired from employees where active = 1")
Page<Employee> active(Pageable pageable);

@SqlQuery("select id, full_name, hired from employees where hired >= :from")
Slice<Employee> hiredSince(LocalDate from, Pageable pageable);

@SqlQuery("select id, full_name, hired from employees")
List<Employee> all(Sort sort);
```

```java
Page<Employee> p = hr.active(PageRequest.of(1, 20, Sort.by("fullName")));
p.getContent();        // строки 21..40 по имени
p.getTotalElements();  // сколько всего
```

Oracle 11g не знает `OFFSET ... FETCH`, на котором спотыкаются Hibernate и Spring Data JDBC
(раздел 19), поэтому библиотека вырезает страницу через `ROWNUM`:

```sql
SELECT * FROM (
  SELECT q_.*, ROWNUM PLSQL_RN_ FROM (<ваш запрос> ORDER BY ...) q_ WHERE ROWNUM <= ?
) WHERE PLSQL_RN_ > ?
```

- Без сортировки Oracle не обещает порядок строк, и одна строка может попасть на две страницы
  подряд. Задайте порядок: `ORDER BY` в самом запросе или `Sort`.
- Служебная колонка `PLSQL_RN_` в результат не попадает.
- `Page` узнаёт общее число строк отдельным запросом `SELECT COUNT(*) FROM (<ваш запрос>)`, и
  только когда без него не обойтись: на последней странице число известно и так. `Slice` вместо
  подсчёта берёт одну лишнюю строку — так он узнаёт, есть ли следующая страница.
- Имена в `Sort` — колонки запроса; `fullName` превращается в `FULL_NAME`, как имя метода в имя
  процедуры. Разрешены только буквы, цифры и `_ $ #`: имя часто приходит из адреса запроса
  (`?sort=...`), и ничего другого в текст SQL не попадёт.
- Колонки запроса должны называться по-разному. Если в соединении таблиц две колонки `ID`,
  дайте им псевдонимы (`a.id emp_id, b.id dept_id`), иначе Oracle ответит ORA-00918.
- `Pageable` и `Sort` живут в Spring Data Commons. Он есть в любом приложении со
  `spring-boot-starter-data-*`, а без Spring Data подключите `spring-data-commons`.

## 8. Ошибки

| Что случилось в базе | Что получит Java |
|---|---|
| `raise_application_error(-20001, 'текст')` | `PlsqlBusinessException`: `getErrorCode()` = 20001, `getMessage()` = `'текст'` — без стека `ORA-06512` |
| нарушение уникальности (ORA-00001) | `DuplicateKeyException` |
| прочие ошибки Oracle | стандартные исключения Spring (`DataIntegrityViolationException`, `BadSqlGrammarException`...) |
| `NULL` в примитивный результат | `EmptyResultDataAccessException` |
| вызов не уложился в срок (ORA-01013) | `QueryTimeoutException` (раздел 11) |

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

## 11. Долгие вызовы: срок

Процедура может зависнуть: ждёт блокировку строки, которую держит другой сеанс, или крутит
тяжёлый запрос. Без срока поток приложения ждёт вместе с ней, пока не кончатся потоки веб-сервера.
Срок говорит драйверу: «если через N секунд вызов не закончился — прерви его».

```java
@Procedure(timeout = 30)                  // этот вызов — не дольше 30 секунд
void recalcSalaries(long deptId);

@SqlQuery(value = "select ... from big_report where ...", timeout = 60)
List<ReportRow> report(LocalDate from);
```

Срок сразу для всех методов, у которых нет своего:

```properties
plsql.query-timeout=30s
```

- `timeout = -1` (так по умолчанию) — как в `plsql.query-timeout`; `timeout = 0` — без
  ограничения, даже если общий срок задан.
- Внутри `@Transactional(timeout = 10)` действует меньшее из двух: срок метода или время, которое
  осталось у транзакции. Так же делает `JdbcTemplate`.
- Прерванный вызов бросает `QueryTimeoutException` (в базе это ORA-01013). Что процедура успела
  изменить, Oracle откатывает сам; соединение остаётся рабочим.

Замер на Oracle 11.2.0.4: вызов, который крутился бы 5 секунд, со сроком 1 секунда прервался
через 1,04 с (тест `LongCallsIT`).

**Если срок не срабатывает.** Чтобы прервать вызов, драйвер посылает серверу «срочные» данные
TCP (out-of-band). Некоторые сетевые прослойки их теряют: например, проброс портов Docker Desktop
на Windows — вызов тогда доходит до конца, хотя срок давно вышел. Помогает одна настройка
драйвера, после неё прерывание идёт обычными данными:

```properties
spring.datasource.hikari.data-source-properties.oracle.net.disableOob=true
```

Проверено: с ней вызов прерывается через 2,0 с при сроке 2 с и с Windows через Docker Desktop, и
из соседнего контейнера. Одно исключение: `DBMS_LOCK.SLEEP` замечает прерывание только когда
просыпается.

## 12. Отладочный вывод и метрики

### `DBMS_OUTPUT` в лог

Старый PL/SQL часто отлаживают через `dbms_output.put_line('...')`: SQL Developer показывает эти
строки после вызова. Через JDBC их никто не читает, и они пропадают. Библиотека может переносить
их в лог приложения:

```properties
logging.level.dev.plsql.spring.support.DbmsOutput=DEBUG
```

После каждого вызова — и после неудачного тоже, ведь вывод перед ошибкой нужнее всего — в лог
пишется одно сообщение:

```text
DEBUG dev.plsql.spring.support.DbmsOutput - HR_DEMO.HR_API.HIRE DBMS_OUTPUT:
проверяю имя
вставляю строку
```

Это два лишних обращения к базе на каждый вызов (замер: +1,1 мс на вызов), поэтому без DEBUG
вывод не читается совсем. Уровень `DEBUG` для всего `dev.plsql.spring` его тоже включает.
Читается до 10 000 строк за вызов. После чтения буфер выключается: соединение уходит обратно в
пул таким, каким было.

### Метрики: сколько вызовов, сколько длятся, сколько ошибок

Если в приложении есть Spring Boot Actuator, каждый вызов процедуры и каждый `@SqlQuery` попадает
в таймер `plsql.call`. Настраивать ничего не нужно: библиотека сообщает о вызовах через Micrometer
Observation, который и так приходит вместе со Spring, а Actuator превращает это в метрики.

| Тег | Значение |
|---|---|
| `plsql.target` | процедура (`HR_DEMO.HR_API.HIRE`) или метод запроса (`HrApi.find`) |
| `plsql.kind` | `call` — процедура, `query` — `@SqlQuery` |
| `error` | `none` или имя исключения (`PlsqlBusinessException`, `QueryTimeoutException`...) |

Посмотреть: `/actuator/metrics/plsql.call?tag=plsql.target:HR_DEMO.HR_API.HIRE`; в Prometheus это
`plsql_call_seconds_count`, `plsql_call_seconds_sum`, `plsql_call_seconds_max`. Если подключена
трассировка (Micrometer Tracing), каждый вызов становится ещё и отрезком трассы запроса.

У метода, который возвращает `Stream`, таймер меряет только открытие курсора: сколько строк и как
долго потом читал ваш код, библиотеке не видно.

Без Spring Boot реестр передаётся в построитель:
`PlsqlApiFactory.builder(ds).observationRegistry(registry)`.

## 13. Несколько баз

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

## 14. Без Spring Boot и без Spring

**Spring без Boot:** `@EnablePlsqlApis(basePackages = "com.example.hr")` на конфигурации и
бин `DataSource` с именем `dataSource`.

**Совсем без Spring-контекста:**

```java
PlsqlApiFactory factory = PlsqlApiFactory.builder(dataSource)
        .argumentDefaults(ArgumentDefaults.byName(Map.of("NTENANT", () -> 1001L)))
        .build();
HrApi hr = factory.create(HrApi.class);   // здесь же проверка против словаря
```

## 15. Настройки

`application.properties`, все необязательны:

| Настройка | По умолчанию | Что делает |
|---|---|---|
| `plsql.charset-policy` | `FAIL` | `IGNORE` — не проверять, помещается ли текст в кодировку базы |
| `plsql.database-charset` | читается из базы | кодировка базы, например `CL8MSWIN1251`: не нужно лишнее обращение при старте |
| `plsql.index-table-max-length` | `10000` | сколько элементов может вернуть выходная index-by таблица |
| `plsql.retry-discarded-state` | `true` | повторять ли вызов вне транзакции после ORA-04068 |
| `plsql.query-timeout` | нет | срок вызова или запроса, например `30s` или `2m` (раздел 11) |
| `plsql.fetch-size` | `100` | сколько строк курсора или запроса забирать за одно обращение к базе; `0` — как у драйвера (10) |

IntelliJ IDEA и VS Code подсказывают эти настройки в `application.properties` сами: имена,
значения по умолчанию и пояснения берутся из описания, которое лежит в jar библиотеки.

Лог:
- `logging.level.dev.plsql.spring=DEBUG` — сгенерированные блоки и время каждого вызова;
- `logging.level.dev.plsql.spring.support.DbmsOutput=DEBUG` — вывод `DBMS_OUTPUT` (раздел 12).

## 16. Тесты своего кода

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

## 17. Ошибки при старте: что они значат

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
| `not callable: ...` | форма аргумента, которую библиотека не поддерживает (раздел 18) |
| `ORA-01882` при подключении | нет `oracle.jdbc.timezoneAsRegion=false` (раздел 2.2) |
| `ORA-17056` при подключении | нет `orai18n.jar` (подтягивается сам, если не исключён вручную) |

## 18. Чего библиотека не умеет

Такие подпрограммы останавливают старт с понятной причиной (`not callable: ...`):

- **Таблица или коллекция как поле записи**: `type order_t is record (id number, lines line_tab)`.
  (Запись внутри записи поддерживается, см. раздел 6.)
- **index-by таблицы записей и дат** (`TABLE OF rec_t INDEX BY ...`): драйвер Oracle 11 их не
  передаёт.
- **Курсор на вход** (`p_cur IN SYS_REFCURSOR`): открытый курсор из Java не передать.
- **Страницы (`Pageable`) у процедур**: страница вырезается только у `@SqlQuery`. Курсор, который
  открыла процедура, приходит целиком.
- **`XMLTYPE` и `ANYDATA` внутри объектного типа SQL.**
- **Вызов через database link** (синоним на процедуру в другой базе).
- **`%ROWTYPE` в пакете, зашифрованном `wrap`**: имя таблицы не прочитать из исходника.

Для таких процедур можно написать маленькую процедуру-обёртку в PL/SQL, которая принимает
простые типы, или вызвать их через `JdbcTemplate` вручную.

## 19. Рядом со Spring Data JPA и JDBC на Oracle 11g

Библиотека закрывает процедуры. Таблицы многие читают через Spring Data JPA или JDBC, и на
Oracle 11g у них есть подводные камни. Проверено на Oracle 11.2.0.4 со Spring Boot 4.1.1
(Hibernate 7.4.5, Spring Data 2026.0.1):

| Что | Работает? |
|---|---|
| Драйвер 23.x, который ставит Boot | подключается и работает, но Oracle официально поддерживает с 11.2 только 19.x (раздел 2.1) |
| JPA: сохранить, `findAll()`, `count()`, ключи из последовательности | да |
| JPA: `Pageable`, `findFirst3By...`, `findTop10By...` | **нет**: ORA-00933 |
| JPA: `@GeneratedValue(strategy = IDENTITY)` | **нет**: при старте только предупреждение, падает первая вставка |
| Spring Data JDBC: `findAll()`, `Sort`, `count()`, ключи через `@Sequence` | да |
| Spring Data JDBC: `Pageable`, `findFirst3By...` | **нет**: ORA-00933 |

**Почему ORA-00933.** Постраничный вывод Hibernate и Spring Data JDBC пишут так:
`... offset ? rows fetch first ? rows only`. Это синтаксис Oracle 12c; 11g его не знает. Hibernate
при старте об этом предупреждает:

```text
HHH000511: The 11.2.0 version for [org.hibernate.dialect.OracleDialect] is no longer supported,
hence certain features may not work properly.The minimum supported version is 19.0.0.
```

**Как починить JPA.** Диалект для старых версий Oracle лежит в отдельном модуле Hibernate:

```xml
<dependency>
  <groupId>org.hibernate.orm</groupId>
  <artifactId>hibernate-community-dialects</artifactId>
</dependency>
```

```properties
spring.jpa.database-platform=org.hibernate.community.dialect.OracleLegacyDialect
```

С ним предупреждение пропадает, а постраничный вывод идёт через `ROWNUM` и `ROW_NUMBER()`:
`Pageable`, `findFirst...` и сортировка работают (проверено и с драйвером 19.32). Нужны обе части:
модуль без свойства ничего не меняет, Hibernate всё равно берёт `OracleDialect`. Модуль
поддерживают участники сообщества, а не основная команда Hibernate.

**IDENTITY.** Столбцов `GENERATED ... AS IDENTITY` в 11g нет. Используйте последовательность:
`@GeneratedValue(strategy = GenerationType.SEQUENCE)`.

**Spring Data JDBC** своего диалекта для старого Oracle не имеет: `spring.data.jdbc.dialect`
предлагает для Oracle только `ORACLE`. Страницы в нём на 11g не работают. Для постраничных
запросов остаются JPA с `OracleLegacyDialect` или `@SqlQuery` этой библиотеки (раздел 7).

Ещё две ловушки, найденные при проверке:

- **Spring Data JDBC 4.0.1 и новее: новая строка с заданным ключом молча не сохраняется.**
  `repo.save(new Person(2000L, "..."))` без `@Version` считает объект уже существующим и делает
  `UPDATE`. Тот меняет 0 строк, и исключения нет: строки в таблице так и не появится (в 4.0.0 было
  исключение, с 4.0.1 результат `UPDATE` отбрасывается). От Oracle это не зависит. Для новых строк
  с готовым ключом — `JdbcAggregateTemplate.insert(...)`, `@Version` или `Persistable.isNew()`.
- **Пакетный `UPDATE` на 11.2 не сообщает число строк.** `executeBatch()` (и
  `JdbcTemplate.batchUpdate`) возвращает `-2` («выполнено, сколько — неизвестно») и для изменённой,
  и для ненайденной строки. Так ведут себя оба драйвера, 19.32 и 23.26. Если важно знать, что
  строка нашлась, выполняйте такие `UPDATE` по одному.

**Процедуры** Spring Data JPA вызывает через `@Procedure` обычным JDBC. На 11g это значит: без
`BOOLEAN`, записей и index-by таблиц и без сверки со словарём при старте. Для них и нужна эта
библиотека. JPA и библиотека работают в одной транзакции, если у них общий `DataSource`
(раздел 9).
