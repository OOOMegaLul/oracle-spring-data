package dev.plsql.spring.call;

import static dev.plsql.spring.test.Signatures.func;
import static dev.plsql.spring.test.Signatures.field;
import static dev.plsql.spring.test.Signatures.indexTable;
import static dev.plsql.spring.test.Signatures.object;
import static dev.plsql.spring.test.Signatures.proc;
import static dev.plsql.spring.test.Signatures.xml;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Struct;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import dev.plsql.spring.meta.ArgumentInfo;
import dev.plsql.spring.support.CharsetGuard;
import oracle.jdbc.OracleCallableStatement;
import oracle.jdbc.OracleConnection;
import oracle.jdbc.OracleTypes;

/**
 * Тесты {@link CallExecutor} на моках ojdbc: как значения привязываются ({@code set...}), какие
 * OUT-параметры регистрируются, как читаются результаты и что освобождается после вызова.
 * План вызова строит настоящий {@link CallPlanner} по сигнатурам из фикстуры {@code Signatures}.
 */
class CallExecutorTest {

    /** Методы-образцы; каждый стоит за процедурой определённой формы из тестов ниже. */
    interface Api {
        /**
         * Процедура {@code P_INSERT(NTENANT IN NUMBER, SNAME IN VARCHAR2, NRN OUT NUMBER)}.
         *
         * @param tenant идёт в {@code NTENANT}
         * @param name   идёт в {@code SNAME}
         * @return значение OUT-аргумента {@code NRN}
         */
        long insert(long tenant, String name);

        /**
         * Функция {@code F_LEN(P_TEXT IN CLOB) RETURN NUMBER}: текст уходит через временный
         * {@code CLOB}.
         *
         * @param text идёт в {@code P_TEXT}
         * @return результат функции
         */
        long length(String text);

        /**
         * Функция {@code PKG.WRAP(P_X IN XMLTYPE) RETURN XMLTYPE}; XML в обе стороны идёт текстом.
         *
         * @param x XML-текст для {@code P_X}
         * @return XML-текст результата
         */
        String wrap(String x);

        /**
         * Процедура {@code PKG.CUR(P_MIN_ID IN NUMBER, P_CUR IN OUT REF CURSOR)}.
         *
         * @param minId идёт в {@code P_MIN_ID}
         * @return строки курсора {@code P_CUR}, каждая как карта «колонка → значение»
         */
        List<Map<String, Object>> cursor(long minId);

        /**
         * Сумма index-by таблицы чисел, переданной массивом примитивов.
         *
         * @param vals числа
         * @return сумма
         */
        BigDecimal sum(long[] vals);

        /**
         * Строки из OUT index-by таблицы.
         *
         * @param n сколько строк
         * @return строки, среди которых может быть {@code null}
         */
        List<String> items(long n);

        /**
         * Сумма длин строк index-by таблицы.
         *
         * @param vals строки
         * @return сумма длин
         */
        BigDecimal texts(List<String> vals);

        /**
         * Объект с атрибутом {@code CLOB} на выходе.
         *
         * @param id номер
         * @return атрибуты объекта
         */
        Map<String, Object> doc(long id);
    }

    /**
     * Находит метод {@link Api} по имени; перегрузок в {@code Api} нет.
     *
     * @param name имя метода
     * @return метод интерфейса {@link Api}
     */
    static Method m(String name) {
        return Arrays.stream(Api.class.getMethods()).filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
    }

    final CallPlanner planner = new CallPlanner(ArgumentDefaults.none());
    OracleConnection con;
    CallableStatement cs;

    /**
     * Готовит моки: соединение Oracle, у которого {@code unwrap(OracleConnection.class)}
     * возвращает его же, а {@code prepareCall} с любым текстом возвращает один и тот же мок
     * {@code CallableStatement}.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @BeforeEach
    void setUp() throws SQLException {
        con = mock(OracleConnection.class);
        cs = mock(CallableStatement.class);
        when(con.unwrap(OracleConnection.class)).thenReturn(con);
        when(con.prepareCall(anyString())).thenReturn(cs);
    }

    /**
     * Проверяет простой вызов целиком и порядок действий: {@code NUMBER} привязывается через
     * {@code setBigDecimal}, строка через {@code setString}, OUT-аргумент регистрируется как
     * {@code NUMERIC}, затем {@code execute()} и закрытие оператора. Прочитанный
     * {@code BigDecimal} превращается в {@code long} — тип результата метода.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void scalarsInAndOut() throws SQLException {
        CallPlan p = planner.plan(m("insert"), proc(null, "P_INSERT").in("NTENANT", "NUMBER").in("SNAME", "VARCHAR2")
                .out("NRN", "NUMBER").build());
        when(cs.getBigDecimal(3)).thenReturn(new BigDecimal("33993908"));

        Object result = new CallExecutor(100).execute(con, p, new Object[]{1001L, "Петров"});

        assertThat(result).isEqualTo(33993908L);
        InOrder order = inOrder(cs);
        order.verify(cs).setBigDecimal(1, BigDecimal.valueOf(1001));
        order.verify(cs).setString(2, "Петров");
        order.verify(cs).registerOutParameter(3, Types.NUMERIC);
        order.verify(cs).execute();
        order.verify(cs).close();
    }

    /**
     * Проверяет, что {@code null} привязывается через {@code setNull} с типом SQL, который
     * соответствует аргументу: {@code NUMERIC} для {@code NUMBER} и {@code VARCHAR} для
     * {@code VARCHAR2}.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void nullsAreTypedNulls() throws SQLException {
        CallPlan p = planner.plan(m("insert"), proc(null, "P_INSERT").in("NTENANT", "NUMBER").in("SNAME", "VARCHAR2")
                .out("NRN", "NUMBER").build());
        new CallExecutor(100).execute(con, p, new Object[]{null, null});
        verify(cs).setNull(1, Types.NUMERIC);
        verify(cs).setNull(2, Types.VARCHAR);
    }

    /**
     * Проверяет, что временный {@code CLOB}, созданный для входного текста, освобождается
     * ({@code free()}), даже когда вызов упал (здесь ORA-01013, отмена операции). Неосвобождённый
     * временный LOB остаётся во временном табличном пространстве сессии до закрытия соединения,
     * а соединение из пула практически никогда не закрывается.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void temporaryClobIsFreedEvenWhenTheCallFails() throws SQLException {
        CallPlan p = planner.plan(m("length"), func(null, "F_LEN", "NUMBER").in("P_TEXT", "CLOB").build());
        Clob clob = mock(Clob.class);
        when(con.createClob()).thenReturn(clob);
        when(cs.execute()).thenThrow(new SQLException("ORA-01013", "72000", 1013));

        assertThatThrownBy(() -> new CallExecutor(100).execute(con, p, new Object[]{"text"}))
                .isInstanceOf(SQLException.class);
        verify(clob).setString(1, "text");
        verify(cs).setClob(2, clob);
        verify(clob).free();
    }

    /**
     * Проверяет, что при политике {@code FAIL} текст, который база в CL8MSWIN1251 сохранить не
     * может (казахская буква «Ә»), отвергается с именем аргумента {@code P_TEXT} ещё до создания
     * временного {@code CLOB} и вообще до того, как что-либо ушло драйверу ({@code prepareCall} не
     * вызывался). Без этой проверки база молча заменила бы символ на {@code ?}.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void textTheDatabaseCannotStoreIsRejectedBeforeAnythingIsSent() throws SQLException {
        CallPlan p = planner.plan(m("length"), func(null, "F_LEN", "NUMBER").in("P_TEXT", "CLOB").build());
        CallExecutor strict = new CallExecutor(100, CharsetGuard.forDatabase("CL8MSWIN1251", CharsetGuard.Policy.FAIL));

        assertThatThrownBy(() -> strict.execute(con, p, new Object[]{"Әлем"}))
                .isInstanceOf(CharsetGuard.UnrepresentableCharacterException.class)
                .hasMessageContaining("P_TEXT");
        verify(con, never()).prepareCall(anyString());
        verify(con, never()).createClob();
        verify(cs, never()).execute();
    }

    /**
     * Проверяет, что {@code XMLTYPE} на выходе регистрируется как {@code CLOB} и читается
     * текстом, а оба {@code CLOB} — временный входной и полученный на выходе — освобождаются.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void xmltypeIsReadAsClobText() throws SQLException {
        CallPlan p = planner.plan(m("wrap"), func("PKG", "WRAP", xml(null, "OUT")).add(xml("P_X", "IN")).build());
        Clob in = mock(Clob.class);
        Clob out = mock(Clob.class);
        when(con.createClob()).thenReturn(in);
        when(cs.getClob(2)).thenReturn(out);
        when(out.length()).thenReturn(11L);
        when(out.getSubString(1, 11)).thenReturn("<w><a/></w>");

        Object result = new CallExecutor(100).execute(con, p, new Object[]{"<a/>"});

        assertThat(result).isEqualTo("<w><a/></w>");
        verify(cs).registerOutParameter(2, Types.CLOB);
        verify(in).free();
        verify(out).free();
    }

    /**
     * Проверяет, что курсор {@code IN OUT}, который процедура так и не открыла (при чтении
     * ORA-24338), даёт пустой список: строк нет. Не ошибку и не {@code null}, чтобы вызывающему
     * не приходилось проверять на {@code null}. OUT-параметр при этом зарегистрирован как
     * {@code OracleTypes.CURSOR}.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void cursorLeftUnopenedReadsAsNoRows() throws SQLException {
        CallPlan p = planner.plan(m("cursor"), proc("PKG", "CUR").in("P_MIN_ID", "NUMBER").inOut("P_CUR", "REF CURSOR").build());
        when(cs.getObject(2)).thenThrow(new SQLException("ORA-24338", "HY000", 24338));

        assertThat(new CallExecutor(100).execute(con, p, new Object[]{1L})).isEqualTo(List.of());
        verify(cs).registerOutParameter(2, OracleTypes.CURSOR);
    }

    /**
     * Проверяет, что остальные ошибки чтения курсора (здесь ORA-01001, недопустимый курсор) не
     * глотаются, а выходят наружу как {@code SQLException}.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void otherCursorErrorsPropagate() throws SQLException {
        CallPlan p = planner.plan(m("cursor"), proc("PKG", "CUR").in("P_MIN_ID", "NUMBER").inOut("P_CUR", "REF CURSOR").build());
        when(cs.getObject(anyInt())).thenThrow(new SQLException("ORA-01001", "HY000", 1001));
        assertThatThrownBy(() -> new CallExecutor(100).execute(con, p, new Object[]{1L})).isInstanceOf(SQLException.class);
    }

    /**
     * Проверяет {@code foldRecords}: выходы полей записи ({@code P_REC.ID}, {@code P_REC.NAME})
     * собираются в одну карту под ключом {@code P_REC}, а прочие выходы остаются как были.
     */
    @Test
    void recordOutputsAreFoldedIntoOneMap() {
        Map<String, Object> outs = new java.util.LinkedHashMap<>();
        outs.put("P_REC.ID", 1);
        outs.put("P_REC.NAME", "a");
        outs.put("P_OTHER", 2);
        CallExecutor.foldRecords(outs, List.of("P_REC"));
        assertThat(outs).containsEntry("P_REC", Map.of("ID", 1, "NAME", "a")).containsEntry("P_OTHER", 2).hasSize(2);
    }

    /**
     * Проверяет, что если упали и вызов (ORA-04068), и освобождение LOB (ORA-03113), наружу
     * выходит ошибка вызова, а ошибка освобождения прикреплена к ней как suppressed. Исходная
     * ошибка решает, повторять ли вызов (после ORA-04068 его повторяют), и именно её видит
     * вызывающий.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void failureToFreeIsAttachedToTheCallErrorNotSwappedForIt() throws SQLException {
        CallPlan p = planner.plan(m("length"), func(null, "F_LEN", "NUMBER").in("P_TEXT", "CLOB").build());
        Clob clob = mock(Clob.class);
        when(con.createClob()).thenReturn(clob);
        SQLException freeFailure = new SQLException("ORA-03113", "08006", 3113);
        org.mockito.Mockito.doThrow(freeFailure).when(clob).free();
        when(cs.execute()).thenThrow(new SQLException("ORA-04068", "72000", 4068));

        assertThatThrownBy(() -> new CallExecutor(100).execute(con, p, new Object[]{"text"}))
                .isInstanceOfSatisfying(SQLException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(4068);
                    assertThat(e.getSuppressed()).containsExactly(freeFailure);
                });
    }

    /**
     * Проверяет index-by таблицы: массив примитивов {@code long[]} передаётся как таблица
     * чисел (раньше при старте он принимался, а при вызове отвергался), а элемент-{@code NULL}
     * в OUT-таблице читается как {@code null} (раньше {@code List.of} падал на нём).
     *
     * @throws SQLException не бросается: драйвер подменён
     */
    @Test
    void indexTablesTakePrimitiveArraysAndKeepNullElements() throws SQLException {
        OracleCallableStatement ocs = mock(OracleCallableStatement.class);
        when(cs.unwrap(OracleCallableStatement.class)).thenReturn(ocs);

        CallPlan sum = planner.plan(m("sum"), func("PKG", "SUM", "NUMBER").add(indexTable("P_VALS", "IN", "NUMBER")).build());
        new CallExecutor(100).execute(con, sum, new Object[]{new long[]{1, 2}});
        verify(ocs).setPlsqlIndexTable(2, new BigDecimal[]{BigDecimal.ONE, BigDecimal.valueOf(2)}, 2, 2, OracleTypes.NUMBER, 0);

        CallPlan items = planner.plan(m("items"), proc("PKG", "ITEMS").in("P_N", "NUMBER")
                .add(indexTable("P_VALS", "OUT", "VARCHAR2")).build());
        when(ocs.getPlsqlIndexTable(2)).thenReturn(new String[]{"a", null});
        assertThat(new CallExecutor(100).execute(con, items, new Object[]{2L})).isEqualTo(Arrays.asList("a", null));
    }

    /**
     * Проверяет, что если вызов прошёл, а освободить временный LOB не удалось (ORA-22922), эта
     * ошибка не теряется, а выбрасывается из {@code execute}.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void failureToFreeAfterASuccessfulCallIsReported() throws SQLException {
        CallPlan p = planner.plan(m("length"), func(null, "F_LEN", "NUMBER").in("P_TEXT", "CLOB").build());
        Clob clob = mock(Clob.class);
        when(con.createClob()).thenReturn(clob);
        org.mockito.Mockito.doThrow(new SQLException("ORA-22922", "99999", 22922)).when(clob).free();
        assertThatThrownBy(() -> new CallExecutor(100).execute(con, p, new Object[]{"text"}))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getErrorCode()).isEqualTo(22922));
    }
    /**
     * Строит index-by таблицу строк с объявленной длиной элемента, как её читает словарь.
     *
     * @param name   имя аргумента
     * @param inOut  направление
     * @param type   тип элемента, {@code VARCHAR2} или {@code NVARCHAR2}
     * @param length объявленная длина или {@code null}
     * @return описание аргумента
     */
    static ArgumentInfo stringTable(String name, String inOut, String type, Integer length) {
        return new ArgumentInfo(name, 1, 0, "PL/SQL TABLE", null, inOut, false, "APP", "PKG", "T_TAB",
                List.of(new ArgumentInfo(null, 1, 1, type, null, inOut, false, null, null, null, null, length)), null);
    }

    /**
     * Проверяет длину строковых элементов index-by таблиц: у выходной таблицы драйвер резервирует
     * объявленную в словаре длину (раньше всегда 4000), у входной — по самой длинной строке. Строка
     * в 32766 символов проходит, а длиннее — ошибка до вызова: больше драйвер не принимает.
     *
     * @throws SQLException не бросается: драйвер подменён
     */
    @Test
    void indexTableElementsTakeTheirRealLength() throws SQLException {
        OracleCallableStatement ocs = mock(OracleCallableStatement.class);
        when(cs.unwrap(OracleCallableStatement.class)).thenReturn(ocs);

        CallPlan out = planner.plan(m("items"), proc("PKG", "ITEMS").in("P_N", "NUMBER")
                .add(stringTable("P_VALS", "OUT", "VARCHAR2", 5000)).build());
        when(ocs.getPlsqlIndexTable(2)).thenReturn(new String[0]);
        new CallExecutor(100).execute(con, out, new Object[]{1L});
        verify(ocs).registerIndexTableOutParameter(2, 100, OracleTypes.VARCHAR, 5000);

        CallPlan in = planner.plan(m("texts"), func("PKG", "TEXTS", "NUMBER")
                .add(stringTable("P_VALS", "IN", "VARCHAR2", 32767)).build());
        String longOne = "x".repeat(6000);
        new CallExecutor(100).execute(con, in, new Object[]{List.of("a", longOne)});
        verify(ocs).setPlsqlIndexTable(2, new String[]{"a", longOne}, 2, 2, OracleTypes.VARCHAR, 6000);

        String limit = "z".repeat(32_766);
        new CallExecutor(100).execute(con, in, new Object[]{List.of(limit)});
        verify(ocs).setPlsqlIndexTable(2, new String[]{limit}, 1, 1, OracleTypes.VARCHAR, 32_766);
        assertThatThrownBy(() -> new CallExecutor(100).execute(con, in, new Object[]{List.of("y".repeat(32_767))}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("P_VALS[1] is 32767 characters");
    }

    /**
     * Проверяет, что строки index-by таблицы {@code NVARCHAR2} не проверяются по основной
     * кодировке базы: у национальных типов свой набор символов (раньше проверка их отвергала).
     *
     * @throws SQLException не бросается: драйвер подменён
     */
    @Test
    void nationalIndexTablesAreNotCheckedAgainstTheDatabaseCharset() throws SQLException {
        OracleCallableStatement ocs = mock(OracleCallableStatement.class);
        when(cs.unwrap(OracleCallableStatement.class)).thenReturn(ocs);
        CallExecutor strict = new CallExecutor(100, CharsetGuard.forDatabase("CL8MSWIN1251", CharsetGuard.Policy.FAIL));
        CallPlan in = planner.plan(m("texts"), func("PKG", "TEXTS", "NUMBER")
                .add(stringTable("P_VALS", "IN", "NVARCHAR2", 100)).build());

        strict.execute(con, in, new Object[]{List.of("Әлем")});

        verify(ocs).setPlsqlIndexTable(2, new String[]{"Әлем"}, 1, 1, OracleTypes.VARCHAR, 4);
    }

    /**
     * Проверяет, что атрибут {@code CLOB} объекта, пришедшего из базы, читается в строку, а сам
     * LOB освобождается (раньше он так и оставался объектом {@code Clob} в карте).
     *
     * @throws SQLException не бросается: драйвер подменён
     */
    @Test
    void lobAttributesOfObjectsAreReadAndFreed() throws SQLException {
        CallPlan p = planner.plan(m("doc"), func("PKG", "DOC", object(null, "OUT", "DOC_T",
                field("ID", "NUMBER"), field("BODY", "CLOB"))).in("P_ID", "NUMBER").build());
        Struct struct = mock(Struct.class);
        Clob clob = mock(Clob.class);
        when(cs.getObject(1)).thenReturn(struct);
        when(struct.getAttributes()).thenReturn(new Object[]{BigDecimal.ONE, clob});
        when(clob.length()).thenReturn(3L);
        when(clob.getSubString(1, 3)).thenReturn("abc");

        assertThat(new CallExecutor(100).execute(con, p, new Object[]{1L}))
                .isEqualTo(Map.of("ID", BigDecimal.ONE, "BODY", "abc"));
        verify(clob).free();
    }
    /**
     * Проверяет, что выходная index-by таблица строк, под которую драйвер зарезервировал бы
     * сотни мегабайт на вызов, отвергается при старте с советом, до скольки снизить
     * {@code indexTableMaxLength}; при меньшем {@code indexTableMaxLength} та же таблица проходит.
     */
    @Test
    void hugeOutIndexTableReserveFailsAtStartup() {
        CallPlan out = planner.plan(m("items"), proc("PKG", "ITEMS").in("P_N", "NUMBER")
                .add(stringTable("P_VALS", "OUT", "VARCHAR2", 32767)).build());
        assertThatThrownBy(() -> new CallExecutor(10_000).verify(out))
                .isInstanceOf(CallPlanner.PlanException.class)
                .hasMessageContaining("P_VALS of VARCHAR2(32766)")
                .hasMessageContaining("lower plsql.index-table-max-length to 1525");
        new CallExecutor(1_000).verify(out);
    }
}
