package dev.plsql.spring.call;

import static dev.plsql.spring.test.Signatures.arg;
import static dev.plsql.spring.test.Signatures.field;
import static dev.plsql.spring.test.Signatures.func;
import static dev.plsql.spring.test.Signatures.indexTable;
import static dev.plsql.spring.test.Signatures.proc;
import static dev.plsql.spring.test.Signatures.record;
import static dev.plsql.spring.test.Signatures.rowtype;
import static dev.plsql.spring.test.Signatures.xml;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import dev.plsql.spring.annotation.Arg;
import dev.plsql.spring.annotation.Procedure;
import dev.plsql.spring.meta.ArgKind;
import dev.plsql.spring.meta.SubprogramInfo;

/**
 * Тесты {@link CallPlanner} без базы данных: по методу Java и сигнатуре из фикстуры
 * {@code Signatures} планировщик строит анонимный блок PL/SQL и список плейсхолдеров
 * ({@link CallPlan}). Проверяется текст блока, откуда берётся значение каждого {@code ?}
 * и какие несоответствия метода и процедуры останавливают старт. Владелец всех
 * подпрограмм в фикстуре {@code APP}, поэтому вызовы выглядят как {@code APP.PKG.X(...)}.
 */
class CallPlannerTest {

    /** Планировщик без {@link ArgumentDefaults}: каждый обязательный аргумент должен прийти из метода. */
    static final CallPlanner PLANNER = new CallPlanner(ArgumentDefaults.none());

    /**
     * Методы-образцы для тестов. Каждый стоит за процедурой определённой формы; сигнатура
     * передаётся планировщику напрямую, поэтому имя метода не обязано совпадать с именем
     * процедуры.
     */
    interface Api {
        /**
         * Процедура {@code P_FOLDER_DELETE(NTENANT IN NUMBER, NRN IN NUMBER)}: только простые
         * входные аргументы.
         *
         * @param tenant идёт в {@code NTENANT}
         * @param rn     идёт в {@code NRN}
         */
        void deleteFolder(long tenant, long rn);

        /**
         * Функция {@code F_UNIT_VERSION(NMODE, NTENANT, SUNIT) RETURN NUMBER}; {@code NMODE} и
         * {@code NTENANT} в методе не объявлены и берутся из {@link ArgumentDefaults}.
         *
         * @param unit идёт в {@code SUNIT}
         * @return результат функции
         */
        long versionOf(String unit);

        /**
         * Процедура {@code PKG.WITH_DEFAULT(P_A NUMBER, P_B VARCHAR2 DEFAULT ...)}: второй аргумент
         * можно не передавать. В другом тесте тот же метод сталкивается с процедурой без
         * аргумента {@code A}.
         *
         * @param a идёт в {@code P_A}
         */
        void withDefault(long a);

        /**
         * Процедура {@code P_INSERT(SNAME IN, SNOTE IN, NRN OUT)} без {@code DEFAULT}; метод передаёт
         * только {@code SNAME}, а {@code nullForMissing} разрешает отдать в {@code SNOTE} {@code NULL}.
         *
         * @param name идёт в {@code SNAME}
         * @return значение OUT-аргумента {@code NRN}
         */
        @Procedure(nullForMissing = true)
        long create(String name);

        /**
         * Объект-параметр, чьи компоненты становятся аргументами процедуры, как поля формы.
         *
         * @param login  идёт в {@code SLOGIN} (по имени без типового префикса)
         * @param name   идёт в {@code SNAME}
         * @param folder идёт в аргумент, названный явно через {@code @Arg("NFOLDER")}
         */
        record NewUser(String login, String name, @Arg("NFOLDER") long folder) {
        }

        /**
         * Процедура {@code P_USER_INSERT(SLOGIN, SNAME, NFOLDER IN, NRN OUT)}: входные аргументы
         * берутся из свойств record-параметра.
         *
         * @param user значения входных аргументов
         * @return значение OUT-аргумента {@code NRN}
         */
        long insertUser(NewUser user);

        /**
         * Функция {@code PKG.FLAG(P_FLAG IN BOOLEAN) RETURN BOOLEAN}.
         *
         * @param flag идёт в {@code P_FLAG}
         * @return результат функции
         */
        boolean flag(boolean flag);

        /**
         * Процедура {@code PKG.TOGGLE(P_FLAG IN OUT BOOLEAN)}.
         *
         * @param flag значение {@code P_FLAG} на входе
         * @return значение {@code P_FLAG} после вызова
         */
        boolean toggle(boolean flag);

        /**
         * Java-двойник записи PL/SQL {@code PKG.REC_T} с полями {@code ID NUMBER},
         * {@code FLAG BOOLEAN} и {@code BODY XMLTYPE}.
         *
         * @param id   поле {@code ID}
         * @param flag поле {@code FLAG}
         * @param body поле {@code BODY}: XML текстом
         */
        record Rec(Long id, Boolean flag, String body) {
        }

        /**
         * Процедура {@code PKG.REC(P_REC IN OUT REC_T)}: запись на входе и на выходе.
         *
         * @param rec значение записи на входе
         * @return запись после вызова
         */
        Rec rec(Rec rec);

        /**
         * Процедура {@code PKG.ROW(P_ID IN NUMBER, P_ROW OUT EMP%ROWTYPE)}.
         *
         * @param id идёт в {@code P_ID}
         * @return поля строки {@code P_ROW} по именам колонок
         */
        Map<String, Object> row(long id);

        /**
         * Функция {@code PKG.WRAP(P_X IN XMLTYPE) RETURN XMLTYPE}; XML в обе стороны идёт текстом.
         *
         * @param x XML-текст для {@code P_X}
         * @return XML-текст результата
         */
        String wrap(String x);

        /**
         * Процедура {@code PKG.CUR(P_MIN_ID IN NUMBER, P_CUR IN OUT REF CURSOR)}; в одном тесте
         * также вариант с чисто входным {@code P_CUR}, который должен быть отвергнут.
         *
         * @param minId идёт в {@code P_MIN_ID}
         * @return строки курсора {@code P_CUR}, каждая как карта «колонка → значение»
         */
        List<Map<String, Object>> cursor(long minId);

        /**
         * Перегруженная процедура {@code PKG.OVER(P_X, P_OUT OUT VARCHAR2)}, где {@code P_X} в одной
         * перегрузке {@code NUMBER}, в другой {@code VARCHAR2}; тип {@code long} выбирает первую.
         *
         * @param x идёт в {@code P_X}
         * @return значение {@code P_OUT}
         */
        String over(@Arg("P_X") long x);

        /**
         * Те же перегрузки {@code PKG.OVER}, но параметр типа {@code Object} не подходит по типу
         * ни к одной, и выбрать перегрузку нельзя.
         *
         * @param x идёт в {@code P_X}
         * @return значение {@code P_OUT}
         */
        String overAmbiguous(@Arg("P_X") Object x);

        /**
         * Результат с двумя OUT-аргументами процедуры {@code PKG.TWO}.
         *
         * @param doubled значение {@code P_DOUBLED} (найден по имени)
         * @param label   значение {@code P_TEXT} (назван явно через {@code @Arg})
         */
        record Outs(BigDecimal doubled, @Arg("P_TEXT") String label) {
        }

        /**
         * Процедура {@code PKG.TWO(P_IN IN NUMBER, P_DOUBLED OUT NUMBER, P_TEXT OUT VARCHAR2)}.
         *
         * @param in идёт в {@code P_IN}
         * @return оба OUT-аргумента в одном record
         */
        Outs twoOuts(long in);

        /**
         * Метод с результатом для процедуры {@code P(A IN NUMBER)} без OUT-аргументов, а в
         * другом тесте для подпрограммы, которой нет в базе.
         *
         * @param a идёт в {@code A}
         * @return результат, которому у процедуры без OUT-аргументов неоткуда взяться
         */
        long noOuts(long a);

        /**
         * Процедура {@code P_X(NOPE IN NUMBER, NRN IN NUMBER)}: метод передаёт только {@code NOPE},
         * а обязательный {@code NRN} остаётся без значения.
         *
         * @param nope идёт в {@code NOPE}
         */
        void unknown(long nope);

        /**
         * Функция {@code PKG.SUM(P_VALS IN ...) RETURN NUMBER}, где {@code P_VALS} — index-by
         * таблица чисел; в том же тесте есть и вариант функции, возвращающий index-by таблицу дат.
         *
         * @param vals элементы таблицы {@code P_VALS}
         * @return результат функции
         */
        BigDecimal sum(List<Long> vals);

        /**
         * Процедура {@code P_REFRESH(NRN OUT NUMBER)}: параметр метода попадает на чисто выходной
         * аргумент, что должно быть отвергнуто.
         *
         * @param rn по имени совпадает с {@code NRN}
         */
        void refresh(long rn);

        /**
         * Неудачный результат для {@code PKG.TWO}: компонент {@code doubled} не совпадает ни с одним
         * OUT-аргументом, если аргумент называется {@code P_DOUBLE}.
         *
         * @param doubled не находит OUT-аргумента {@code P_DOUBLE}
         * @param text    значение {@code P_TEXT}
         */
        record Wrong(BigDecimal doubled, String text) {
        }

        /**
         * Процедура {@code PKG.TWO(P_IN IN NUMBER, P_DOUBLE OUT NUMBER, P_TEXT OUT VARCHAR2)} с
         * результатом {@link Wrong}.
         *
         * @param in идёт в {@code P_IN}
         * @return OUT-аргументы в record, который им не соответствует
         */
        Wrong twoOutsWrong(long in);
    }

    /**
     * Находит метод {@link Api} по имени. Перегрузок в {@code Api} нет, поэтому первый
     * найденный метод и есть нужный.
     *
     * @param name имя метода
     * @return метод интерфейса {@link Api}
     */
    static Method m(String name) {
        return Arrays.stream(Api.class.getMethods()).filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
    }

    /**
     * Проверяет, что простые аргументы ({@code NUMBER}) привязываются прямо в вызов в
     * именованной нотации ({@code NTENANT => ?}), без секции {@code DECLARE}, и что каждый
     * плейсхолдер берёт значение из своего параметра Java. Именованная нотация делает вызов
     * независимым от порядка аргументов в процедуре.
     */
    @Test
    void scalarsBindStraightIntoTheCallWithNamedNotation() {
        CallPlan p = PLANNER.plan(m("deleteFolder"),
                proc(null, "P_FOLDER_DELETE").in("NTENANT", "NUMBER").in("NRN", "NUMBER").build());
        assertThat(p.sql()).isEqualTo("BEGIN\n  APP.P_FOLDER_DELETE(NTENANT => ?, NRN => ?);\nEND;");
        assertThat(p.binds()).extracting(CallPlan.Bind::kind).containsExactly(ArgKind.NUMBER, ArgKind.NUMBER);
        assertThat(p.binds().get(0).in().apply(new Object[]{5L, 7L})).isEqualTo(5L);
        assertThat(p.binds().get(1).in().apply(new Object[]{5L, 7L})).isEqualTo(7L);
    }

    /**
     * Проверяет, что результат функции забирает первый плейсхолдер ({@code ? := ...}) под
     * ключом {@link CallPlanner#RETURN_KEY}, а контекстные аргументы, которых нет в методе
     * ({@code NMODE}, {@code NTENANT}), заполняет {@link ArgumentDefaults}: проверено значение
     * {@code NTENANT} и то, что {@code SUNIT} по-прежнему берётся из параметра. Так контекст
     * не приходится объявлять в каждом методе.
     */
    @Test
    void functionReturnComesFirstAndDefaultsFillContextArguments() {
        Supplier<Object> tenant = () -> 1001L;
        CallPlanner planner = new CallPlanner(ArgumentDefaults.byName(Map.of("NTENANT", tenant, "NMODE", () -> 0)));
        CallPlan p = planner.plan(m("versionOf"), func(null, "F_UNIT_VERSION", "NUMBER")
                .in("NMODE", "NUMBER").in("NTENANT", "NUMBER").in("SUNIT", "VARCHAR2").build());
        assertThat(p.sql()).isEqualTo("BEGIN\n  ? := APP.F_UNIT_VERSION(NMODE => ?, NTENANT => ?, SUNIT => ?);\nEND;");
        assertThat(p.binds().get(0).outKey()).isEqualTo(CallPlanner.RETURN_KEY);
        assertThat(p.binds().get(2).in().apply(new Object[]{"X"})).isEqualTo(1001L);
        assertThat(p.binds().get(3).in().apply(new Object[]{"X"})).isEqualTo("X");
    }

    /**
     * Проверяет, что аргумент с {@code DEFAULT}, для которого в методе нет параметра, не
     * попадает в вызов вовсе: значение подставит сама процедура.
     */
    @Test
    void defaultedArgumentsAreLeftOut() {
        CallPlan p = PLANNER.plan(m("withDefault"), proc("PKG", "WITH_DEFAULT").in("P_A", "NUMBER").inDefault("P_B", "VARCHAR2").build());
        assertThat(p.sql()).contains("APP.PKG.WITH_DEFAULT(P_A => ?)");
    }

    /**
     * Проверяет две ошибки планирования: параметр Java, которому не нашлось аргумента
     * (сообщение перечисляет аргументы процедуры, например {@code NTENANT IN}), и обязательный
     * аргумент без {@code DEFAULT}, который никто не передал. Обе должны останавливать старт,
     * а не превращаться в молчаливый {@code NULL}.
     */
    @Test
    void requiredArgumentMustBeSupplied() {
        SubprogramInfo sp = proc(null, "P_X").in("NTENANT", "NUMBER").in("NRN", "NUMBER").build();
        assertThatThrownBy(() -> PLANNER.plan(m("withDefault"), sp))
                .hasMessageContaining("'a' has no matching argument").hasMessageContaining("NTENANT IN");
        Method rnOnly = m("unknown");
        assertThatThrownBy(() -> PLANNER.plan(rnOnly, proc(null, "P_X").in("NOPE", "NUMBER").in("NRN", "NUMBER").build()))
                .hasMessageContaining("required argument NRN (NUMBER) is not supplied");
    }

    /**
     * Проверяет, что с {@code @Procedure(nullForMissing = true)} непереданный обязательный
     * аргумент ({@code SNOTE}) всё равно попадает в вызов и получает {@code NULL}. Это для
     * старых процедур с длинными списками аргументов без {@code DEFAULT}.
     */
    @Test
    void nullForMissingPassesNull() {
        CallPlan p = PLANNER.plan(m("create"), proc(null, "P_INSERT").in("SNAME", "VARCHAR2").in("SNOTE", "VARCHAR2")
                .out("NRN", "NUMBER").build());
        assertThat(p.sql()).contains("SNAME => ?, SNOTE => ?, NRN => ?");
        assertThat(p.binds().get(1).in().apply(new Object[]{"x"})).isNull();
    }

    /**
     * Проверяет, что record-параметр раскладывается по аргументам процедуры, как форма:
     * компонент идёт в аргумент по имени ({@code login} → {@code SLOGIN}) или по {@code @Arg}
     * ({@code folder} → {@code NFOLDER}), а единственный OUT-аргумент {@code NRN} становится
     * результатом метода.
     */
    @Test
    void recordParameterIsSpreadOverArguments() {
        CallPlan p = PLANNER.plan(m("insertUser"), proc(null, "P_USER_INSERT").in("SLOGIN", "VARCHAR2")
                .in("SNAME", "VARCHAR2").in("NFOLDER", "NUMBER").out("NRN", "NUMBER").build());
        Object[] args = {new Api.NewUser("IVANOV", "Иванов", 42)};
        assertThat(p.binds().get(0).in().apply(args)).isEqualTo("IVANOV");
        assertThat(p.binds().get(1).in().apply(args)).isEqualTo("Иванов");
        assertThat(p.binds().get(2).in().apply(args)).isEqualTo(42L);
        assertThat(p.result().returnKey()).isEqualTo("NRN");
    }

    /**
     * Проверяет передачу {@code BOOLEAN}: JDBC на Oracle 11.2 не умеет привязывать этот тип,
     * поэтому он идёт числом 1/0. Входное значение превращается в {@code TRUE}/{@code FALSE}
     * выражением {@code CASE} прямо в вызове, а результат функции и аргумент {@code IN OUT}
     * проходят через переменную блока {@code v1} и читаются обратно как 1/0.
     */
    @Test
    void booleanInIsInlinedAndBooleanOutUsesAVariable() {
        CallPlan in = PLANNER.plan(m("flag"), func("PKG", "FLAG", "PL/SQL BOOLEAN").in("P_FLAG", "PL/SQL BOOLEAN").build());
        assertThat(in.sql()).isEqualTo("""
                DECLARE
                  v1 BOOLEAN;
                BEGIN
                  v1 := APP.PKG.FLAG(P_FLAG => (CASE ? WHEN 1 THEN TRUE WHEN 0 THEN FALSE END));
                  ? := CASE WHEN v1 THEN 1 WHEN NOT v1 THEN 0 END;
                END;""");
        CallPlan inOut = PLANNER.plan(m("toggle"), proc("PKG", "TOGGLE").inOut("P_FLAG", "PL/SQL BOOLEAN").build());
        assertThat(inOut.sql()).contains("v1 := CASE ? WHEN 1 THEN TRUE WHEN 0 THEN FALSE END;")
                .contains("APP.PKG.TOGGLE(P_FLAG => v1);").contains("? := CASE WHEN v1 THEN 1 WHEN NOT v1 THEN 0 END;");
    }

    /**
     * Проверяет, что запись PL/SQL ({@code RECORD}, JDBC на 11.2 её не привязывает) идёт поле за
     * полем через переменную блока: число напрямую, {@code BOOLEAN} через {@code CASE},
     * {@code XMLTYPE} через временную переменную {@code CLOB} с проверкой на {@code NULL}. После
     * вызова каждое поле читается отдельным плейсхолдером, а аргумент попадает в
     * {@code recordOuts}, чтобы исполнитель собрал поля обратно в одну карту. Значения полей
     * на входе берутся из компонентов Java-record.
     */
    @Test
    void recordFieldsOfEveryKindTravelFieldByField() {
        SubprogramInfo sp = proc("PKG", "REC").add(record("P_REC", "IN/OUT", "PKG", "REC_T",
                field("ID", "NUMBER"), field("FLAG", "PL/SQL BOOLEAN"), field("BODY", "OPAQUE/XMLTYPE"))).build();
        CallPlan p = PLANNER.plan(m("rec"), sp);
        assertThat(p.sql()).isEqualTo("""
                DECLARE
                  v1 APP.PKG.REC_T;
                  v2 CLOB;
                BEGIN
                  v1.ID := ?;
                  v1.FLAG := CASE ? WHEN 1 THEN TRUE WHEN 0 THEN FALSE END;
                  v2 := ?; IF v2 IS NOT NULL THEN v1.BODY := XMLTYPE(v2); END IF;
                  APP.PKG.REC(P_REC => v1);
                  ? := v1.ID;
                  ? := CASE WHEN v1.FLAG THEN 1 WHEN NOT v1.FLAG THEN 0 END;
                  ? := CASE WHEN v1.BODY IS NULL THEN NULL ELSE v1.BODY.getClobVal() END;
                END;""");
        assertThat(p.recordOuts()).containsExactly("P_REC");
        Object[] args = {new Api.Rec(1L, true, "<a/>")};
        assertThat(p.binds().get(1).in().apply(args)).isEqualTo(true);
        assertThat(p.binds().get(2).in().apply(args)).isEqualTo("<a/>");
    }

    /**
     * Проверяет, что переменная для аргумента {@code %ROWTYPE} объявляется от таблицы
     * ({@code APP.EMP%ROWTYPE}), а поля строки читаются после вызова по одному.
     */
    @Test
    void rowtypeIsDeclaredFromTheTable() {
        CallPlan p = PLANNER.plan(m("row"), proc("PKG", "ROW").in("P_ID", "NUMBER")
                .add(rowtype("P_ROW", "OUT", "EMP", field("ID", "NUMBER"), field("NAME", "VARCHAR2"))).build());
        assertThat(p.sql()).contains("v1 APP.EMP%ROWTYPE;").contains("? := v1.ID;").contains("? := v1.NAME;");
    }

    /**
     * Проверяет блок для {@code XMLTYPE}: вход приходит текстом в {@code CLOB} и превращается в
     * {@code XMLTYPE} только если он не {@code NULL} (на 11.2 {@code XMLTYPE(NULL)} падает с
     * ORA-06502), а результат функции возвращается текстом через {@code getClobVal()}, причём
     * {@code NULL} остаётся {@code NULL}. Плейсхолдер входа имеет вид {@code CLOB}, плейсхолдер
     * выхода — {@code XMLTYPE}.
     */
    @Test
    void xmltypeIsNullSafe() {
        CallPlan p = PLANNER.plan(m("wrap"), func("PKG", "WRAP", xml(null, "OUT")).add(xml("P_X", "IN")).build());
        assertThat(p.sql()).isEqualTo("""
                DECLARE
                  v1 XMLTYPE;
                  v2 CLOB;
                  v3 XMLTYPE;
                BEGIN
                  v2 := ?; IF v2 IS NOT NULL THEN v1 := XMLTYPE(v2); END IF;
                  v3 := APP.PKG.WRAP(P_X => v1);
                  ? := CASE WHEN v3 IS NULL THEN NULL ELSE v3.getClobVal() END;
                END;""");
        assertThat(p.binds()).extracting(CallPlan.Bind::kind).containsExactly(ArgKind.CLOB, ArgKind.XMLTYPE);
    }

    /**
     * Проверяет, что курсор {@code IN OUT} передаётся через переменную {@code SYS_REFCURSOR} и
     * читается после вызова, а чисто входной {@code REF CURSOR} отвергается: открыть курсор
     * в Java и передать его в процедуру нельзя.
     */
    @Test
    void inOutCursorGoesThroughAVariableAndInCursorIsRejected() {
        CallPlan p = PLANNER.plan(m("cursor"), proc("PKG", "CUR").in("P_MIN_ID", "NUMBER").inOut("P_CUR", "REF CURSOR").build());
        assertThat(p.sql()).contains("v1 SYS_REFCURSOR;").contains("P_CUR => v1").contains("? := v1;");
        assertThatThrownBy(() -> PLANNER.plan(m("cursor"), proc("PKG", "CUR").in("P_MIN_ID", "NUMBER").in("P_CUR", "REF CURSOR").build()))
                .hasMessageContaining("IN REF CURSOR");
    }

    /**
     * Проверяет выбор перегрузки по типу параметра Java: {@code long} подходит к перегрузке с
     * {@code P_X NUMBER}, а не {@code VARCHAR2}. Параметр типа {@code Object} не подходит ни к
     * одной, поэтому планировщик сообщает о неоднозначности ({@code matches 2 overloads}), а не
     * угадывает.
     */
    @Test
    void overloadIsChosenByJavaType() {
        SubprogramInfo number = proc("PKG", "OVER").overload("1").in("P_X", "NUMBER").out("P_OUT", "VARCHAR2").build();
        SubprogramInfo text = proc("PKG", "OVER").overload("2").in("P_X", "VARCHAR2").out("P_OUT", "VARCHAR2").build();
        assertThat(PLANNER.plan(m("over"), List.of(number, text)).target()).isSameAs(number);
        assertThatThrownBy(() -> PLANNER.plan(m("overAmbiguous"), List.of(number, text)))
                .hasMessageContaining("matches 2 overloads");
    }

    /**
     * Проверяет, что несколько OUT-аргументов собираются в Java-record: компонент
     * {@code doubled} находит {@code P_DOUBLED} по имени, {@code label} — {@code P_TEXT} по
     * {@code @Arg}.
     */
    @Test
    void severalOutsFillAJavaRecord() {
        CallPlan p = PLANNER.plan(m("twoOuts"), proc("PKG", "TWO").in("P_IN", "NUMBER")
                .out("P_DOUBLED", "NUMBER").out("P_TEXT", "VARCHAR2").build());
        assertThat(p.result().outsToType()).isTrue();
        Object r = p.result().assemble(Map.of("P_DOUBLED", BigDecimal.TEN, "P_TEXT", "t"));
        assertThat(r).isEqualTo(new Api.Outs(BigDecimal.TEN, "t"));
    }

    /**
     * Проверяет, что метод с результатом нельзя привязать к процедуре без OUT-аргументов:
     * вернуть было бы нечего, и это ошибка старта.
     */
    @Test
    void returnValueNeedsAnOutArgument() {
        assertThatThrownBy(() -> PLANNER.plan(m("noOuts"), proc(null, "P").in("A", "NUMBER").build()))
                .hasMessageContaining("has no OUT arguments");
    }

    /**
     * Проверяет отказы по форме подпрограммы: пустой список перегрузок даёт ровно сообщение
     * {@code not found in the database}; index-by таблица записей и возвращаемая index-by
     * таблица дат не поддержаны (на 11.2 привязываются только таблицы {@code NUMBER} и
     * {@code VARCHAR2}); входная таблица {@code NUMBER} допустима и привязывается как
     * {@code INDEX_TABLE}; тип {@code OPAQUE/ANYDATA} не поддержан.
     */
    @Test
    void notFoundAndUnsupportedShapes() {
        assertThatThrownBy(() -> PLANNER.plan(m("noOuts"), List.of())).hasMessage("not found in the database");
        SubprogramInfo recs = func("PKG", "RECS", indexTable(null, "OUT", "PL/SQL RECORD")).in("P_N", "NUMBER").build();
        assertThat(CallPlanner.supportIssues(recs)).singleElement().asString().contains("index-by table of PL/SQL RECORD");
        assertThatThrownBy(() -> PLANNER.plan(m("sum"), func("PKG", "SUM", indexTable(null, "OUT", "DATE"))
                .add(indexTable("P_VALS", "IN", "NUMBER")).build()))
                .hasMessageContaining("RETURN: index-by table of DATE");
        SubprogramInfo ok = func("PKG", "SUM", "NUMBER").add(indexTable("P_VALS", "IN", "NUMBER")).build();
        assertThat(CallPlanner.supportIssues(ok)).isEmpty();
        assertThat(PLANNER.plan(m("sum"), ok).binds().get(1).kind()).isEqualTo(ArgKind.INDEX_TABLE);
        SubprogramInfo anydata = proc(null, "P").add(arg("P_X", "OPAQUE/ANYDATA", "IN")).build();
        assertThat(CallPlanner.supportIssues(anydata)).containsExactly("P_X: type OPAQUE/ANYDATA");
    }

    /**
     * Проверяет таблицу совместимости типов Java с видами аргументов, по которой выбирается
     * перегрузка: {@code long} подходит к {@code NUMBER}, а {@code String} нет; {@code Document}
     * подходит к {@code XMLTYPE}; {@code boolean} к {@code BOOLEAN}; {@code List} к коллекции
     * SQL; record к записи PL/SQL, а {@code String} нет.
     */
    @Test
    void javaTypesAcceptedPerKind() {
        assertThat(CallPlanner.accepts(ArgKind.NUMBER, long.class)).isTrue();
        assertThat(CallPlanner.accepts(ArgKind.NUMBER, String.class)).isFalse();
        assertThat(CallPlanner.accepts(ArgKind.XMLTYPE, org.w3c.dom.Document.class)).isTrue();
        assertThat(CallPlanner.accepts(ArgKind.BOOLEAN, boolean.class)).isTrue();
        assertThat(CallPlanner.accepts(ArgKind.SQL_COLLECTION, List.class)).isTrue();
        assertThat(CallPlanner.accepts(ArgKind.RECORD, Api.Rec.class)).isTrue();
        assertThat(CallPlanner.accepts(ArgKind.RECORD, String.class)).isFalse();
    }

    /**
     * Проверяет, что параметр метода или свойство record-параметра, попавшие на чисто
     * выходной аргумент, — ошибка старта: такой аргумент значения не принимает, и переданное
     * вызывающим молча пропало бы. OUT-значения возвращаются только через тип результата.
     */
    @Test
    void parameterOnAnOutOnlyArgumentIsRejected() {
        assertThatThrownBy(() -> PLANNER.plan(m("refresh"), proc(null, "P_REFRESH").out("NRN", "NUMBER").build()))
                .hasMessageContaining("parameter 'rn' maps to OUT argument NRN");
        assertThatThrownBy(() -> PLANNER.plan(m("insertUser"), proc(null, "P_USER_INSERT").in("SLOGIN", "VARCHAR2")
                .in("SNAME", "VARCHAR2").out("NFOLDER", "NUMBER").build()))
                .hasMessageContaining("property 'folder' of NewUser maps to OUT argument NFOLDER");
    }

    /**
     * Проверяет, что компонент record-результата, которому не нашлось OUT-аргумента
     * ({@code doubled} при аргументе {@code P_DOUBLE}), — ошибка старта: иначе он всегда
     * оставался бы пустым. Сообщение называет такой компонент и перечисляет OUT-аргументы.
     */
    @Test
    void resultComponentsThatNoOutFillsAreRejected() {
        assertThatThrownBy(() -> PLANNER.plan(m("twoOutsWrong"), proc("PKG", "TWO").in("P_IN", "NUMBER")
                .out("P_DOUBLE", "NUMBER").out("P_TEXT", "VARCHAR2").build()))
                .hasMessageContaining("Wrong components [doubled] match no OUT argument")
                .hasMessageContaining("[P_DOUBLE, P_TEXT]");
    }

    /**
     * Проверяет {@link ArgumentDefaults#byName}: имя аргумента ищется без учёта регистра
     * ({@code nTenant} находит {@code NTENANT}), а два ключа, различающиеся только регистром, —
     * ошибка ({@code given twice}), потому что непонятно, какое значение брать.
     */
    @Test
    void defaultsByNameIgnoreCase() {
        Supplier<Object> tenant = () -> 7L;
        ArgumentDefaults d = ArgumentDefaults.byName(Map.of("nTenant", tenant));
        SubprogramInfo sp = proc(null, "P").in("NTENANT", "NUMBER").build();
        assertThat(d.lookup(sp, sp.arguments().get(0))).isSameAs(tenant);
        assertThatThrownBy(() -> ArgumentDefaults.byName(Map.of("NTENANT", tenant, "ntenant", tenant)))
                .hasMessageContaining("given twice");
    }
}
