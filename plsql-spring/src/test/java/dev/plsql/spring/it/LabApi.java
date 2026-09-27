package dev.plsql.spring.it;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.w3c.dom.Document;

import dev.plsql.spring.annotation.Arg;
import dev.plsql.spring.annotation.PlsqlApi;
import dev.plsql.spring.annotation.Procedure;
import dev.plsql.spring.annotation.SqlQuery;

/**
 * Интерфейс к пакету {@code LAB_PKG} из {@code it/schema-objects.sql}: все формы аргументов, с
 * которыми неудобно работать на Oracle 11.2.
 *
 * <p>Реализацию создаёт библиотека ({@code PlsqlApiFactory.create(LabApi.class)}): при создании
 * каждый метод сверяется со словарём Oracle, а вызов идёт через анонимный блок PL/SQL с
 * именованной нотацией ({@code P_FLAG => ...}). Имя подпрограммы выводится из имени метода
 * ({@code echoBool} → {@code ECHO_BOOL}) или задаётся {@link Procedure}; параметр Java
 * сопоставляется с аргументом по имени без учёта регистра, подчёркиваний и префикса {@code P_}
 * ({@code flag} → {@code P_FLAG}) или задаётся {@link Arg}.
 *
 * <p>Методы с {@link SqlQuery} не вызывают процедуру, а выполняют SQL-запрос к таблице
 * {@code LAB_EMP}; {@code default}-метод выполняется в самом Java-коде.
 *
 * <p>Используется в {@code LabApiIT} (все методы) и {@code RawJdbcIT} (состояние пакета, CLOB).
 */
@PlsqlApi(packageName = "LAB_PKG")
public interface LabApi {

    /**
     * Java-двойник PL/SQL-записи {@code LAB_PKG.REC_T}
     * ({@code id NUMBER, name VARCHAR2(100), flag BOOLEAN, dt DATE}). Поля записи и компоненты
     * record сопоставляются по имени.
     *
     * @param id   поле {@code ID}
     * @param name поле {@code NAME}
     * @param flag поле {@code FLAG} типа PL/SQL {@code BOOLEAN}, которого нет ни в SQL, ни в JDBC 11.2
     * @param dt   поле {@code DT} (дата без времени)
     */
    record Rec(Long id, String name, Boolean flag, LocalDate dt) {
    }

    /**
     * Строка таблицы {@code LAB_EMP}. В таком виде она приходит и как {@code LAB_EMP%ROWTYPE}, и
     * из курсора, и из {@link SqlQuery}: колонки сопоставляются с компонентами по имени.
     *
     * @param id    колонка {@code ID}
     * @param name  колонка {@code NAME}
     * @param hired колонка {@code HIRED} (дата приёма)
     * @param flag  колонка {@code FLAG NUMBER(1)}: ненулевое значение даёт {@code true}, 0 —
     *              {@code false}
     */
    record Emp(long id, String name, LocalDate hired, boolean flag) {
    }

    /**
     * Java-двойник объектного SQL-типа {@code LAB_OBJ} ({@code id NUMBER, name VARCHAR2(100), dt DATE}).
     * В отличие от PL/SQL-записи, объектный тип объявлен на уровне SQL ({@code CREATE TYPE}), поэтому
     * JDBC передаёт его напрямую как {@code java.sql.Struct}, а коллекцию таких объектов — как
     * {@code java.sql.Array}.
     *
     * @param id   атрибут {@code ID}
     * @param name атрибут {@code NAME}
     * @param dt   атрибут {@code DT}
     */
    record Obj(Long id, String name, LocalDate dt) {
    }

    /**
     * Результат процедуры {@code LAB_PKG.TWO_OUTS} с двумя OUT-аргументами: каждый компонент
     * record заполняется OUT-аргументом с подходящим именем.
     *
     * @param double_ OUT-аргумент {@code P_DOUBLE}; подчёркивание в конце нужно потому, что
     *                {@code double} — ключевое слово Java, а при сопоставлении имён подчёркивания
     *                не учитываются
     * @param text    OUT-аргумент {@code P_TEXT}
     */
    record TwoOuts(BigDecimal double_, String text) {
    }

    /**
     * Java-двойник PL/SQL-записи {@code LAB_PKG.XREC_T} ({@code id NUMBER, body XMLTYPE}): запись с
     * полем {@code XMLTYPE}, которое в Java представлено текстом XML.
     *
     * @param id   поле {@code ID}
     * @param body поле {@code BODY}: текст XML или {@code null}
     */
    record XRec(Long id, String body) {
    }

    /**
     * Вызывает функцию {@code LAB_PKG.ECHO_BOOL(P_FLAG BOOLEAN) RETURN BOOLEAN}, которая
     * возвращает отрицание аргумента.
     *
     * <p>Форма: PL/SQL {@code BOOLEAN} на входе и на выходе. JDBC на 11.2 не умеет передавать
     * {@code BOOLEAN}, поэтому библиотека передаёт 1/0 и превращает их в TRUE/FALSE внутри блока
     * ({@code CASE ? WHEN 1 THEN TRUE WHEN 0 THEN FALSE END}), а результат кладёт в переменную
     * блока и читает обратно как 1/0.
     *
     * @param flag значение {@code P_FLAG}
     * @return отрицание {@code flag}
     */
    boolean echoBool(boolean flag);

    /**
     * Вызывает процедуру {@code LAB_PKG.BOOL_INOUT(P_FLAG IN OUT BOOLEAN)}, которая инвертирует
     * аргумент.
     *
     * <p>Форма: IN OUT {@code BOOLEAN}. Значение записывается в переменную блока до вызова и
     * читается из неё после; единственный OUT-аргумент становится результатом метода.
     *
     * @param flag входное значение {@code P_FLAG}
     * @return значение {@code P_FLAG} после вызова, то есть отрицание {@code flag}
     */
    boolean boolInout(boolean flag);

    /**
     * Вызывает функцию {@code LAB_PKG.MAKE_REC(P_ID, P_NAME) RETURN REC_T}. Функция заполняет
     * {@code id} и {@code name} из аргументов, {@code flag} — как {@code P_ID > 0}, а {@code dt} —
     * датой 2024-02-29.
     *
     * <p>Форма: PL/SQL {@code RECORD} с полем {@code BOOLEAN} как результат функции. Запись
     * принимается в переменную блока типа {@code LAB_PKG.REC_T} и читается поле за полем.
     *
     * @param id   значение {@code P_ID}
     * @param name значение {@code P_NAME}
     * @return запись, которую вернула функция
     */
    Rec makeRec(long id, String name);

    /**
     * Вызывает процедуру {@code LAB_PKG.REC_INOUT(P_REC IN OUT REC_T)}: она умножает {@code id} на
     * 10, переводит {@code name} в верхний регистр и инвертирует {@code flag}; {@code dt} не меняет.
     *
     * <p>Форма: IN OUT {@code RECORD}. Java-record раскладывается по полям переменной блока до
     * вызова и собирается обратно после.
     *
     * @param rec входная запись {@code P_REC}
     * @return запись {@code P_REC} после вызова
     */
    Rec recInout(Rec rec);

    /**
     * Вызывает процедуру {@code LAB_PKG.EMP_ROW(P_ID, P_ROW OUT LAB_EMP%ROWTYPE)}: читает строку
     * {@code LAB_EMP} по {@code id}.
     *
     * <p>Форма: OUT {@code %ROWTYPE} — запись со структурой строки таблицы. На 11.2
     * {@code ALL_ARGUMENTS} не называет таблицу такого аргумента, поэтому библиотека находит её в
     * тексте объявления ({@code ALL_SOURCE}) и объявляет в блоке переменную
     * {@code PLSQL_IT.LAB_EMP%ROWTYPE}. Если строки нет, процедура падает с {@code NO_DATA_FOUND}.
     *
     * @param id значение {@code P_ID}
     * @return строка таблицы
     */
    Emp empRow(long id);

    /**
     * Вызывает процедуру {@code LAB_PKG.EMP_ROW_SYN(P_ID, P_ROW OUT LAB_EMP_SYN%ROWTYPE)}: то же, что
     * {@link #empRow}, но запись объявлена через синоним {@code LAB_EMP_SYN} таблицы {@code LAB_EMP}.
     *
     * <p>Форма: {@code %ROWTYPE} синонима. Анонимный блок компилируется в схеме пользователя
     * соединения, поэтому библиотека раскрывает синоним до таблицы уже при старте.
     *
     * @param id значение {@code P_ID}
     * @return строка таблицы
     */
    Emp empRowSyn(long id);

    /**
     * Краткая строка {@code LAB_EMP}: столбцы курсора {@code LAB_PKG.C_EMP}.
     *
     * @param id   номер
     * @param name имя
     */
    record Brief(long id, String name) {
    }

    /**
     * Вызывает процедуру {@code LAB_PKG.EMP_BRIEF(P_ID, P_ROW OUT C_EMP%ROWTYPE)}: номер и имя
     * сотрудника.
     *
     * <p>Форма: {@code %ROWTYPE} курсора, объявленного в спецификации пакета. В блоке переменная
     * объявляется как {@code PLSQL_IT.LAB_PKG.C_EMP%ROWTYPE}.
     *
     * @param id значение {@code P_ID}
     * @return номер и имя
     */
    Brief empBrief(long id);

    /**
     * Вызывает функцию {@code LAB_PKG.SUM_IBT(P_VALS NUM_IBT) RETURN NUMBER}: сумму элементов.
     *
     * <p>Форма: IN index-by таблица {@code NUMBER} ({@code TABLE OF NUMBER INDEX BY PLS_INTEGER}) —
     * PL/SQL-коллекция, которой нет на уровне SQL. Передаётся вызовом драйвера Oracle
     * {@code setPlsqlIndexTable}; пустой список становится пустой таблицей.
     *
     * @param vals элементы таблицы
     * @return сумма элементов; 0 для пустого списка
     */
    BigDecimal sumIbt(List<Long> vals);

    /**
     * Вызывает процедуру {@code LAB_PKG.IBT_OUT(P_N, P_VALS OUT STR_IBT)}: заполняет таблицу
     * строками от {@code item 1} до {@code item n}.
     *
     * <p>Форма: OUT index-by таблица {@code VARCHAR2}. Под результат драйвер заранее резервирует
     * место на {@code indexTableMaxLength} элементов (настройка фабрики, по умолчанию 10 000).
     *
     * @param n сколько элементов заполнить
     * @return элементы таблицы по порядку
     */
    List<String> ibtOut(int n);

    /**
     * Вызывает функцию {@code LAB_PKG.OBJS(P_N) RETURN LAB_OBJ_TAB}: коллекцию из {@code n}
     * объектов, где у i-го объекта {@code id = i}, {@code name = 'Объект i'},
     * {@code dt = 2024-01-01 + i} дней.
     *
     * <p>Форма: SQL-коллекция ({@code TABLE OF LAB_OBJ}) объектного типа как результат функции:
     * приходит как {@code java.sql.Array} из {@code java.sql.Struct}, элементы собираются в {@link Obj}.
     *
     * @param n число элементов
     * @return объекты коллекции по порядку
     */
    List<Obj> objs(int n);

    /**
     * Вызывает функцию {@code LAB_PKG.NAME_CHARS(P_OBJS LAB_OBJ_TAB) RETURN NUMBER}: суммарную
     * длину имён объектов в символах.
     *
     * <p>Форма: IN SQL-коллекция объектов. Каждый {@link Obj} превращается в {@code Struct}, список —
     * в {@code Array} типа {@code LAB_OBJ_TAB}.
     *
     * @param objs объекты коллекции
     * @return сумма длин {@code name}; пустое имя считается за 0
     */
    long nameChars(List<Obj> objs);

    /**
     * Вызывает функцию {@code LAB_PKG.SUM_NUMS(P_NUMS LAB_NUM_TAB) RETURN NUMBER}: сумму элементов.
     *
     * <p>Форма: IN SQL-коллекция скаляров ({@code TABLE OF NUMBER}, объявлена через
     * {@code CREATE TYPE}); в отличие от index-by таблицы передаётся как {@code java.sql.Array}.
     *
     * @param nums элементы коллекции
     * @return сумма элементов
     */
    long sumNums(List<Long> nums);

    /**
     * Вызывает функцию {@code LAB_PKG.ECHO_OBJ(P_OBJ LAB_OBJ) RETURN LAB_OBJ}: возвращает новый
     * объект с {@code id + 1}, {@code name || '!'} и {@code dt} на день позже.
     *
     * <p>Форма: объектный SQL-тип на входе и на выходе ({@code java.sql.Struct}).
     *
     * @param obj входной объект
     * @return изменённая копия
     */
    Obj echoObj(Obj obj);

    /**
     * Вызывает процедуру {@code LAB_PKG.EMPS(P_MIN_ID, P_CUR OUT SYS_REFCURSOR)}: курсор по строкам
     * {@code LAB_EMP} с {@code id >= P_MIN_ID}, упорядоченным по {@code id}.
     *
     * <p>Форма: OUT {@code REF CURSOR}. Строки курсора читаются сразу и превращаются в {@link Emp}
     * по именам колонок.
     *
     * @param minId нижняя граница {@code id} включительно
     * @return строки курсора
     */
    List<Emp> emps(long minId);

    /**
     * Вызывает функцию {@code LAB_PKG.EMPS_F(P_MIN_ID) RETURN SYS_REFCURSOR} — тот же запрос, что в
     * {@link #emps}, но курсор возвращается как результат функции.
     *
     * <p>Форма: {@code REF CURSOR} как возвращаемое значение.
     *
     * @param minId нижняя граница {@code id} включительно
     * @return строки курсора
     */
    List<Emp> empsF(long minId);

    /**
     * Вызывает процедуру {@code LAB_PKG.EMPS_INOUT(P_OPEN, P_MIN_ID, P_CUR IN OUT SYS_REFCURSOR)}:
     * она открывает курсор тем же запросом, что в {@link #emps}, только если {@code P_OPEN = 1}.
     *
     * <p>Форма: IN OUT {@code REF CURSOR}. Курсор из Java передать нельзя, поэтому на вход идёт
     * неоткрытая переменная блока. Если процедура курсор не открыла, строк нет и метод возвращает
     * пустой список: ошибку ORA-24338 при чтении неоткрытого курсора библиотека перехватывает.
     *
     * @param open  1 — открыть курсор, другое значение — оставить неоткрытым
     * @param minId нижняя граница {@code id} включительно
     * @return строки курсора; пустой список, если курсор не был открыт
     */
    List<Emp> empsInout(int open, long minId);

    /**
     * Вызывает процедуру {@code LAB_PKG.SET_STATE(P_VAL)}, которая записывает значение в переменную
     * пакета {@code G_STATE}.
     *
     * <p>Переменная пакета живёт в сессии Oracle (то есть в соединении), а не в одном вызове: её
     * видят следующие вызовы в той же сессии, и она пропадает при сбросе состояния пакетов. Из-за
     * этой переменной у пакета есть состояние, и его перекомпиляция даёт в живых сессиях ORA-04068.
     *
     * @param val новое значение
     */
    void setState(String val);

    /**
     * Вызывает функцию {@code LAB_PKG.GET_STATE RETURN VARCHAR2}: текущее значение переменной
     * пакета {@code G_STATE} в этой сессии.
     *
     * <p>Форма: функция без аргументов.
     *
     * @return значение {@code G_STATE} или {@code null}, если в сессии его не задавали
     */
    String getState();

    /**
     * Вызывает процедуру {@code LAB_PKG.FAIL(P_MSG)}, которая всегда падает через
     * {@code RAISE_APPLICATION_ERROR(-20042, P_MSG)} — так PL/SQL сообщает о нарушении
     * бизнес-правила.
     *
     * <p>Библиотека превращает такую ошибку в {@code PlsqlBusinessException} с кодом 20042 и
     * сообщением {@code P_MSG} без префикса {@code ORA-20042:} и без стека {@code ORA-06512}.
     *
     * @param msg текст ошибки
     */
    void fail(String msg);

    /**
     * Вызывает функцию {@code LAB_PKG.CLOB_LEN(P_C CLOB) RETURN NUMBER}: длину CLOB в символах
     * ({@code DBMS_LOB.GETLENGTH}).
     *
     * <p>Форма: IN {@code CLOB} из {@code String}. Библиотека создаёт временный LOB и освобождает
     * его сразу после вызова.
     *
     * @param c текст для аргумента {@code P_C}
     * @return длина текста в символах
     */
    long clobLen(String c);

    /**
     * Вызывает функцию {@code LAB_PKG.BIG_CLOB(P_N) RETURN CLOB}: временный CLOB из {@code n}
     * повторов строки {@code 0123456789}.
     *
     * <p>Форма: {@code CLOB} как результат функции; читается целиком в {@code String}.
     *
     * @param n число повторов по 10 символов
     * @return текст длиной {@code 10 * n} символов
     */
    String bigClob(int n);

    /**
     * Вызывает процедуру
     * {@code LAB_PKG.WITH_DEFAULTS(P_A, P_B DEFAULT 'B', P_C DEFAULT DATE '2000-01-01', P_OUT OUT)},
     * передавая только {@code P_A}.
     *
     * <p>Форма: аргументы со значениями по умолчанию. Библиотека не упоминает {@code P_B} и
     * {@code P_C} в вызове (именованная нотация это позволяет), и PL/SQL подставляет свои значения.
     *
     * @param a значение {@code P_A}
     * @return {@code P_OUT} — строка вида {@code P_A/P_B/P_C}, здесь {@code a/B/2000-01-01}
     */
    String withDefaults(long a);

    /**
     * Вызывает ту же процедуру {@code LAB_PKG.WITH_DEFAULTS}, но с явным {@code P_B};
     * {@code P_C} по-прежнему берётся из DEFAULT.
     *
     * <p>Форма: часть аргументов с DEFAULT передаётся, часть нет. Два Java-метода с одним именем
     * вызывают одну и ту же процедуру; это перегрузка Java, а не PL/SQL.
     *
     * @param a значение {@code P_A}
     * @param b значение {@code P_B}
     * @return {@code P_OUT}, здесь {@code a/b/2000-01-01}
     */
    String withDefaults(long a, String b);

    /**
     * Вызывает перегрузку {@code LAB_PKG.OVER(P_X NUMBER, P_OUT OUT VARCHAR2)}.
     *
     * <p>Форма: перегрузки PL/SQL. У двух версий {@code OVER} одинаковые имена аргументов, поэтому
     * библиотека выбирает ту, чей тип {@code P_X} принимает тип параметра Java: {@code long} подходит
     * к {@code NUMBER}. Имя подпрограммы и аргумента заданы явно через {@link Procedure} и {@link Arg}.
     *
     * @param x значение {@code P_X}
     * @return {@code P_OUT}, например {@code number 5} для {@code x = 5}
     */
    @Procedure("OVER")
    String overNumber(@Arg("P_X") long x);

    /**
     * Вызывает перегрузку {@code LAB_PKG.OVER(P_X VARCHAR2, P_OUT OUT VARCHAR2)}.
     *
     * <p>Форма: перегрузки PL/SQL; {@code String} подходит к {@code VARCHAR2}, а не к {@code NUMBER},
     * даже если строка состоит из цифр.
     *
     * @param x значение {@code P_X}
     * @return {@code P_OUT}, например {@code varchar2 5} для {@code x = "5"}
     */
    @Procedure("OVER")
    String overString(@Arg("P_X") String x);

    /**
     * Вызывает функцию {@code LAB_PKG.ECHO_STR(P_S VARCHAR2) RETURN VARCHAR2}, которая возвращает
     * аргумент без изменений.
     *
     * <p>Форма: обычная строка. Нужна для проверки кодировки: в однобайтовой базе библиотека до
     * отправки проверяет, что каждый символ представим в кодировке базы.
     *
     * @param s строка
     * @return та же строка в том виде, в каком её сохранила база
     */
    String echoStr(String s);

    /**
     * Вызывает процедуру {@code LAB_PKG.NOOP}, которая ничего не делает.
     *
     * <p>Форма: процедура без аргументов и без результата; блок вызывает её без скобок.
     */
    void noop();

    /**
     * Вызывает процедуру {@code LAB_PKG.TWO_OUTS(P_IN, P_DOUBLE OUT NUMBER, P_TEXT OUT VARCHAR2)}:
     * {@code P_DOUBLE = P_IN * 2}, {@code P_TEXT = 'got ' || P_IN}.
     *
     * <p>Форма: несколько OUT-аргументов. Они собираются в компоненты {@link TwoOuts} по именам;
     * компонент без подходящего OUT-аргумента остановил бы создание реализации интерфейса.
     *
     * @param in значение {@code P_IN}
     * @return оба OUT-аргумента
     */
    TwoOuts twoOuts(long in);

    /**
     * Вызывает функцию {@code LAB_PKG.WRAP_XML(P_X XMLTYPE) RETURN XMLTYPE}: оборачивает документ в
     * элемент {@code <wrapped>}; для {@code NULL} возвращает {@code NULL}.
     *
     * <p>Форма: {@code XMLTYPE} на входе и на выходе в виде текста. JDBC 11.2 не передаёт
     * {@code XMLTYPE}, поэтому текст идёт через {@code CLOB} и переменную блока. {@code null}
     * остаётся {@code NULL}: вызов {@code XMLTYPE(NULL)} на 11.2 падает, и библиотека его не
     * делает. Некорректный XML даёт ошибку Oracle при разборе внутри блока.
     *
     * @param x текст XML или {@code null}
     * @return результат функции как текст XML или {@code null}
     */
    String wrapXml(String x);

    /**
     * Вызывает ту же функцию {@code LAB_PKG.WRAP_XML}, но XML передаётся и возвращается как DOM
     * ({@code org.w3c.dom.Document}). Имя подпрограммы и аргумента заданы явно.
     *
     * <p>Форма: {@code XMLTYPE} ↔ {@code Document}. Документ сериализуется в текст перед отправкой,
     * а результат разбирается обратно (с отключёнными DTD и внешними сущностями).
     *
     * @param x документ для {@code P_X}
     * @return обёрнутый документ с корневым элементом {@code wrapped}
     */
    @Procedure("WRAP_XML")
    Document wrapXmlDom(@Arg("P_X") Document x);

    /**
     * Вызывает ту же функцию {@code LAB_PKG.WRAP_XML} с результатом {@code Optional}: {@code NULL}
     * из базы становится пустым {@code Optional}.
     *
     * @param x текст XML или {@code null}
     * @return обёрнутый XML или пустой {@code Optional}
     */
    @Procedure("WRAP_XML")
    Optional<String> wrapXmlOptional(@Arg("P_X") String x);

    /**
     * Вызывает процедуру {@code LAB_PKG.XML_OUT(P_N, P_X OUT XMLTYPE)}, которая возвращает документ
     * {@code <n>P_N</n>}.
     *
     * <p>Форма: OUT {@code XMLTYPE}; значение читается из переменной блока через
     * {@code getClobVal()}.
     *
     * @param n значение {@code P_N}
     * @return текст XML, например {@code <n>5</n>}
     */
    String xmlOut(long n);

    /**
     * Вызывает процедуру {@code LAB_PKG.XREC_INOUT(P_R IN OUT XREC_T)}: увеличивает {@code id} на 1
     * и, если {@code body} не {@code NULL}, оборачивает его в {@code <wrapped>}.
     *
     * <p>Форма: IN OUT {@code RECORD} с полем {@code XMLTYPE}. Поле передаётся через {@code CLOB} так
     * же, как отдельный аргумент {@code XMLTYPE}, и {@code null} остаётся {@code NULL}.
     *
     * @param r входная запись {@code P_R}
     * @return запись {@code P_R} после вызова
     */
    XRec xrecInout(XRec r);

    /**
     * Вызывает функцию {@code LAB_PKG.XML_IS_NULL(P_X XMLTYPE) RETURN NUMBER}: 1, если аргумент
     * {@code NULL}, иначе 0.
     *
     * <p>Нужна, чтобы проверить, что {@code null} из Java доходит до PL/SQL как настоящий
     * {@code NULL}, а не как пустой документ и не как ошибка.
     *
     * @param x текст XML или {@code null}
     * @return 1 для {@code null}, 0 для документа
     */
    int xmlIsNull(String x);

    /**
     * SQL-запрос (не процедура): строка {@code LAB_EMP} по {@code id}.
     *
     * <p>Форма: {@link SqlQuery} с именованным параметром {@code :id} и результатом
     * {@code Optional}: если строки нет, возвращается пустой {@code Optional}.
     *
     * @param id значение параметра {@code :id}
     * @return строка или пустой {@code Optional}
     */
    @SqlQuery("select id, name, hired, flag from lab_emp where id = :id")
    Optional<Emp> findEmp(long id);

    /**
     * SQL-запрос {@code select count(*) from lab_emp}.
     *
     * <p>Форма: {@link SqlQuery} без параметров; единственная колонка превращается в скалярный
     * результат.
     *
     * @return число строк в {@code LAB_EMP}
     */
    @SqlQuery("select count(*) from lab_emp")
    long countEmps();

    /**
     * SQL-оператор UPDATE, который ничего не меняет ({@code flag = flag}), но возвращает число
     * затронутых строк.
     *
     * <p>Текст начинается с комментария: вернёт ли оператор строки или число изменённых строк,
     * решает JDBC при выполнении, а не первое слово запроса.
     *
     * @param id значение параметра {@code :id}
     * @return 1, если строка с таким {@code id} есть, иначе 0
     */
    @SqlQuery("/* no-op */ update lab_emp set flag = flag where id = :id")
    int touchEmp(long id);

    /**
     * SQL-запрос: строки {@code LAB_EMP} с точно совпадающим {@code name}.
     *
     * <p>Форма: {@link SqlQuery} со строковым параметром и результатом-списком. Параметры запросов
     * проходят ту же проверку кодировки, что и аргументы процедур.
     *
     * @param name имя для параметра {@code :name}
     * @return найденные строки; пустой список, если совпадений нет
     */
    @SqlQuery("select id, name, hired, flag from lab_emp where name = :name")
    List<Emp> findByName(String name);

    /**
     * {@code default}-метод интерфейса: выполняется в Java и сам вызывает {@link #findEmp}. Прокси
     * библиотеки не ищет для него процедуру в базе, а просто выполняет его тело.
     *
     * @param id {@code id} строки
     * @return имя сотрудника или {@code none}, если строки нет
     */
    default String describe(long id) {
        return findEmp(id).map(Emp::name).orElse("none");
    }
    /**
     * Вызывает функцию {@code LAB_PKG.LONG_LEN(P_VALS LONG_IBT) RETURN NUMBER}: сумму длин строк
     * index-by таблицы {@code VARCHAR2(32767)}.
     *
     * <p>Форма: IN index-by таблица длинных строк. Раньше длина элемента была зашита в 4000, и
     * строка длиннее не проходила.
     *
     * @param vals строки
     * @return сумма длин
     */
    BigDecimal longLen(List<String> vals);

    /**
     * Строка курсора {@code EMPS_PREFIXED}: колонки названы с типовыми префиксами
     * ({@code NRN}, {@code SNAME}, {@code DHIRED}).
     *
     * @param rn    номер, из {@code NRN}
     * @param name  имя, из {@code SNAME}
     * @param hired дата приёма, из {@code DHIRED}
     */
    record Brief2(long rn, String name, LocalDate hired) {
    }

    /**
     * Вызывает процедуру {@code LAB_PKG.EMPS_PREFIXED(P_CUR OUT SYS_REFCURSOR)}.
     *
     * <p>Форма: колонки курсора сопоставляются с компонентами record по тем же правилам, что и
     * аргументы: типовые префиксы отбрасываются. Раньше курсор понимал только
     * {@code BEGIN_DATE} → {@code beginDate}.
     *
     * @return строки курсора
     */
    List<Brief2> empsPrefixed();
    /**
     * Адрес — вложенная запись {@code LAB_PKG.ADDR_T}.
     *
     * @param city     город
     * @param street   улица
     * @param verified проверен ли адрес ({@code BOOLEAN})
     */
    record Addr(String city, String street, Boolean verified) {
    }

    /**
     * Человек — запись {@code LAB_PKG.PERSON_T} с вложенной записью {@code ADDR}.
     *
     * @param id   номер
     * @param name имя
     * @param addr адрес
     */
    record Person(Long id, String name, Addr addr) {
    }

    /**
     * Карточка — запись {@code LAB_PKG.EMP_CARD_T}, поле которой — строка таблицы
     * ({@code LAB_EMP%ROWTYPE}).
     *
     * @param emp  сотрудник
     * @param note примечание
     */
    record EmpCard(Emp emp, String note) {
    }

    /**
     * Вызывает функцию {@code LAB_PKG.MAKE_PERSON(P_ID) RETURN PERSON_T}.
     *
     * <p>Форма: запись внутри записи как результат функции.
     *
     * @param id номер
     * @return человек с адресом
     */
    Person makePerson(long id);

    /**
     * Вызывает процедуру {@code LAB_PKG.PERSON_INOUT(P_P IN OUT PERSON_T)}: номер + 1, город в
     * верхнем регистре, признак проверки наоборот.
     *
     * <p>Форма: запись внутри записи туда и обратно, с {@code BOOLEAN} во вложенной записи.
     *
     * @param p человек
     * @return человек после процедуры
     */
    Person personInout(Person p);

    /**
     * Вызывает функцию {@code LAB_PKG.EMP_CARD(P_ID) RETURN EMP_CARD_T}.
     *
     * <p>Форма: поле записи — {@code %ROWTYPE} таблицы.
     *
     * @param id номер сотрудника
     * @return карточка
     */
    EmpCard empCard(long id);
}
