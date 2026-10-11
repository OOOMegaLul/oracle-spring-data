package dev.plsql.spring.call;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.NClob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Struct;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.ResolvableType;
import org.springframework.util.ObjectUtils;

import dev.plsql.spring.meta.ArgKind;
import dev.plsql.spring.meta.ArgumentInfo;
import dev.plsql.spring.support.CharsetGuard;
import dev.plsql.spring.support.RowMappers;
import dev.plsql.spring.support.Values;
import oracle.jdbc.OracleCallableStatement;
import oracle.jdbc.OracleConnection;
import oracle.jdbc.OracleTypes;

/**
 * Выполняет {@link CallPlan} на соединении: привязывает входные значения, запускает
 * сгенерированный анонимный блок PL/SQL и собирает выходные значения в результат метода.
 *
 * <p>Класс знает, как каждый {@link ArgKind} привязывается и читается через ojdbc (драйвер
 * JDBC для Oracle). Привязка (bind) — передача значения на место знака {@code ?} в тексте
 * блока; каждая такая позиция называется bind-переменной и нумеруется с единицы.
 *
 * <p>Каждый созданный здесь временный LOB освобождается. Временный LOB — это значение
 * {@code CLOB} или {@code BLOB}, которое живёт не в таблице, а во временном табличном
 * пространстве сессии. Если его не освободить, он остаётся там до закрытия соединения, а
 * соединение из пула фактически не закрывается никогда (замерено: 50 вызовов — 50
 * оставшихся LOB).
 *
 * <p>Экземпляр не хранит состояния отдельного вызова, поэтому один исполнитель можно
 * использовать из разных потоков одновременно.
 */
public class CallExecutor {

    /**
     * Код ошибки ORA-24338: процедура оставила {@code IN OUT} курсор неоткрытым, и драйверу
     * нечего читать.
     */
    private static final int CURSOR_NOT_OPENED = 24338;

    /** Сколько элементов резервируется под OUT index-by таблицу (см. конструктор). */
    private final int indexTableMaxLength;
    /** Проверка, что текст можно сохранить в кодировке базы, до его отправки. */
    private final CharsetGuard charsetGuard;
    /** Сколько строк курсора забирать за одно обращение к базе; {@code 0} — как у драйвера (10). */
    private final int fetchSize;

    /** Сколько строк курсора забирается за обращение к базе, если не сказано иное. */
    public static final int DEFAULT_FETCH_SIZE = 100;

    /**
     * Создаёт исполнителя с заданной ёмкостью OUT index-by таблиц и проверкой кодировки.
     *
     * <p>Index-by таблица — массив PL/SQL вида {@code TABLE OF ... INDEX BY}, который
     * существует только внутри PL/SQL. Чтобы прочитать такую таблицу из OUT-аргумента,
     * драйвер заранее выделяет буфер на фиксированное число элементов; вернуть больше
     * элементов процедура не сможет — вызов завершится ошибкой.
     *
     * @param indexTableMaxLength ёмкость, резервируемая под OUT index-by таблицу, в элементах
     * @param charsetGuard        проверяет текст перед отправкой: в однобайтовой базе символ
     *                            вне кодовой страницы иначе молча превратился бы в {@code ?}
     */
    public CallExecutor(int indexTableMaxLength, CharsetGuard charsetGuard) {
        this(indexTableMaxLength, charsetGuard, DEFAULT_FETCH_SIZE);
    }

    /**
     * Создаёт исполнителя, как {@link #CallExecutor(int, CharsetGuard)}, с заданным размером пачки
     * строк курсора.
     *
     * <p>Драйвер Oracle по умолчанию забирает строки курсора по 10 за обращение к базе. Замер на
     * 11.2.0.4: 200 000 строк по 10 читаются 9,9 с, по 100 — 1,1 с, по 500 — 0,3 с.
     *
     * @param indexTableMaxLength ёмкость, резервируемая под OUT index-by таблицу, в элементах
     * @param charsetGuard        проверяет текст перед отправкой
     * @param fetchSize           сколько строк курсора забирать за обращение; {@code 0} — как у
     *                            драйвера
     */
    public CallExecutor(int indexTableMaxLength, CharsetGuard charsetGuard, int fetchSize) {
        this.indexTableMaxLength = indexTableMaxLength;
        this.charsetGuard = charsetGuard;
        this.fetchSize = fetchSize;
    }

    /**
     * Создаёт исполнителя без проверки кодировки ({@link CharsetGuard#none()}).
     *
     * @param indexTableMaxLength ёмкость, резервируемая под OUT index-by таблицу, в элементах
     */
    public CallExecutor(int indexTableMaxLength) {
        this(indexTableMaxLength, CharsetGuard.none());
    }

    /**
     * Выполняет план вызова на соединении и возвращает значение для метода интерфейса.
     *
     * <p>Порядок работы:
     * <ul>
     *   <li>из пулового соединения достаётся «настоящее» соединение ojdbc
     *       ({@link OracleConnection}): только оно умеет создавать LOB, {@code Struct} и
     *       {@code Array};</li>
     *   <li>входные значения вычисляются из аргументов метода, и текст (строки, {@code CLOB},
     *       {@code XMLTYPE}, строки index-by таблиц) проверяется на кодировку базы — до того,
     *       как что-либо уходит драйверу;</li>
     *   <li>текст блока готовится как {@link CallableStatement} — оператор JDBC, у которого
     *       есть не только входные, но и выходные позиции;</li>
     *   <li>для каждой позиции плана по порядку: если у неё есть источник входного
     *       значения, вычисленное значение привязывается; если у неё
     *       есть ключ выхода, позиция регистрируется как OUT. У {@code IN OUT} делается и
     *       то и другое;</li>
     *   <li>блок выполняется, после чего все OUT-позиции читаются в карту «ключ выхода —
     *       значение»;</li>
     *   <li>созданные временные LOB освобождаются в любом случае, даже при ошибке;</li>
     *   <li>поля OUT-записей ({@code ARG.FIELD}) собираются в одну карту под именем записи,
     *       а {@link CallPlan.Result} превращает выходы в тип результата метода.</li>
     * </ul>
     *
     * @param con  соединение, на котором выполняется вызов (обычно из пула, в транзакции)
     * @param plan план вызова, собранный один раз при старте
     * @param args аргументы метода интерфейса; {@code null} для метода без параметров
     * @return значение, приведённое к типу результата метода, или {@code null} для {@code void}
     * @throws SQLException если драйвер или база сообщили об ошибке, в том числе об ошибке
     *                      самой процедуры ({@code RAISE_APPLICATION_ERROR}), или если после
     *                      успешного вызова не удалось освободить временный LOB
     * @throws CharsetGuard.UnrepresentableCharacterException если текст нельзя сохранить в
     *                      кодировке базы и политика проверки — {@code FAIL}
     * @throws IllegalStateException если вид позиции нельзя привязать или прочитать
     */
    public Object execute(Connection con, CallPlan plan, Object[] args) throws SQLException {
        return execute(con, plan, args, 0);
    }

    /**
     * Выполняет план вызова, как {@link #execute(Connection, CallPlan, Object[])}, но с предельным
     * временем.
     *
     * <p>Срок передаётся драйверу через {@code Statement.setQueryTimeout}: когда он выходит, драйвер
     * просит сервер прервать вызов, и тот завершается ошибкой ORA-01013
     * ({@link java.sql.SQLTimeoutException}). Изменения данных прерванного вызова Oracle откатывает
     * сам, соединение остаётся рабочим.
     *
     * @param con            соединение, на котором выполняется вызов
     * @param plan           план вызова, собранный один раз при старте
     * @param args           аргументы метода интерфейса; {@code null} для метода без параметров
     * @param timeoutSeconds предельное время в секундах; {@code 0} — без ограничения
     * @return значение, приведённое к типу результата метода, или {@code null} для {@code void}
     * @throws SQLException если драйвер или база сообщили об ошибке, в том числе о прерывании по
     *                      сроку
     * @throws IllegalArgumentException если метод плана возвращает {@code Stream}: такой план
     *                      выполняет {@link #open}
     */
    public Object execute(Connection con, CallPlan plan, Object[] args, int timeoutSeconds) throws SQLException {
        if (plan.result().streams()) {
            throw new IllegalArgumentException(plan.target().qualifiedName()
                    + ": the method returns a Stream; call open(), which leaves the cursor open for reading");
        }
        Map<String, Object> outs = new LinkedHashMap<>();
        List<Object> temporaries = new ArrayList<>();
        Throwable failure = null;
        try (CallableStatement cs = run(con, plan, args, timeoutSeconds, temporaries)) {
            List<CallPlan.Bind> binds = plan.binds();
            for (int i = 0; i < binds.size(); i++) {
                CallPlan.Bind b = binds.get(i);
                if (b.outKey() != null) {
                    outs.put(b.outKey(), readOut(cs, i + 1, b));
                }
            }
        } catch (SQLException | RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            free(temporaries, failure);
        }
        TableSplit.fold(outs, plan.tableOuts());
        foldRecords(outs, plan.recordOuts());
        return plan.result().assemble(outs);
    }

    /**
     * Открытый курсор, строки которого ещё не прочитаны: результат метода, возвращающего
     * {@code Stream}.
     *
     * <p>Вызывающий код читает {@code rows} по строке и потом обязан закрыть {@code statement}:
     * пока он открыт, курсор держит ресурсы сессии, а соединение нельзя вернуть в пул.
     *
     * @param rows      строки курсора или {@code null}, если процедура его не открыла
     * @param statement оператор, которому принадлежит курсор; закрыть после чтения
     * @param element   тип одной строки
     */
    public record OpenCursor(ResultSet rows, java.sql.Statement statement, ResolvableType element) {
    }

    /**
     * Выполняет план метода, который возвращает {@code Stream}, и отдаёт курсор-результат
     * открытым.
     *
     * <p>Всё остальное — как у {@link #execute(Connection, CallPlan, Object[], int)}: значения
     * проверяются до отправки, временные LOB освобождаются сразу после вызова, прочие выходы
     * читаются (и освобождаются) и отбрасываются. Оператор при успехе не закрывается: его закроет
     * тот, кто дочитает курсор. При ошибке он закрывается здесь.
     *
     * @param con            соединение, на котором выполняется вызов
     * @param plan           план с результатом-{@code Stream}
     * @param args           аргументы метода интерфейса
     * @param timeoutSeconds предельное время в секундах; {@code 0} — без ограничения
     * @return открытый курсор и его оператор
     * @throws SQLException если драйвер или база сообщили об ошибке
     * @throws IllegalArgumentException если метод плана не возвращает {@code Stream}
     */
    public OpenCursor open(Connection con, CallPlan plan, Object[] args, int timeoutSeconds) throws SQLException {
        if (!plan.result().streams()) {
            throw new IllegalArgumentException(plan.target().qualifiedName()
                    + ": the method does not return a Stream; call execute()");
        }
        List<Object> temporaries = new ArrayList<>();
        Throwable failure = null;
        CallableStatement cs = null;
        try {
            cs = run(con, plan, args, timeoutSeconds, temporaries);
            List<CallPlan.Bind> binds = plan.binds();
            int cursor = -1;
            for (int i = 0; i < binds.size(); i++) {
                CallPlan.Bind b = binds.get(i);
                if (b.outKey() == null) {
                    continue;
                }
                if (b.outKey().equals(plan.result().returnKey())) {
                    cursor = i + 1;
                } else {
                    readOut(cs, i + 1, b); // LOB освобождаются при чтении; значения не нужны
                }
            }
            ResultSet rs = openedCursor(cs, cursor);
            if (rs != null && fetchSize > 0) {
                rs.setFetchSize(fetchSize);
            }
            return new OpenCursor(rs, cs, plan.result().returnType().getGeneric(0));
        } catch (SQLException | RuntimeException | Error e) {
            failure = e;
            if (cs != null) {
                try {
                    cs.close();
                } catch (SQLException c) {
                    e.addSuppressed(c);
                }
            }
            throw e;
        } finally {
            free(temporaries, failure);
        }
    }

    /**
     * Выполняет пакетный план ({@code @Procedure(batch = true)}): вызывает процедуру по разу на
     * каждый элемент параметра-списка и отправляет вызовы базе пачками по {@code batchSize}.
     *
     * <p>Для каждого элемента значения вычисляются из аргументов метода, где на месте списка стоит
     * этот элемент, и текст проверяется на кодировку базы до того, как вызов попадёт в пачку.
     * Временные LOB освобождаются после отправки своей пачки. Выходов у пакетного плана нет.
     *
     * @param con            соединение, на котором выполняется вызов
     * @param plan           пакетный план
     * @param args           аргументы метода интерфейса
     * @param timeoutSeconds предельное время одной пачки в секундах; {@code 0} — без ограничения
     * @param batchSize      сколько вызовов отправлять за одно обращение к базе, не меньше 1
     * @return число выполненных вызовов (элементов списка)
     * @throws SQLException если драйвер или база сообщили об ошибке; ошибка вызова в пачке
     *                      приходит как {@link java.sql.BatchUpdateException}
     * @throws IllegalArgumentException если план не пакетный
     */
    public long executeBatch(Connection con, CallPlan plan, Object[] args, int timeoutSeconds, int batchSize)
            throws SQLException {
        if (!plan.batch()) {
            throw new IllegalArgumentException(plan.target().qualifiedName() + ": the plan is not a batch; call execute()");
        }
        int k = plan.batchParameter();
        Object[] a = args.clone();
        Object source = a[k];
        Iterable<?> elements = source == null ? List.of()
                : source instanceof Iterable<?> it ? it : Arrays.asList(ObjectUtils.toObjectArray(source));
        OracleConnection oc = con.unwrap(OracleConnection.class);
        List<CallPlan.Bind> binds = plan.binds();
        List<Object> temporaries = new ArrayList<>();
        Throwable failure = null;
        long calls = 0;
        int pending = 0;
        try (CallableStatement cs = con.prepareCall(plan.sql())) {
            if (timeoutSeconds > 0) {
                cs.setQueryTimeout(timeoutSeconds);
            }
            Object[] values = new Object[binds.size()];
            for (Object element : elements) {
                a[k] = element;
                // Значения элемента вычисляются и проверяются до того, как вызов попадёт в пачку.
                for (int i = 0; i < binds.size(); i++) {
                    CallPlan.Bind b = binds.get(i);
                    values[i] = b.in() == null ? null : b.in().apply(a);
                    if (b.in() != null) {
                        precheck(b, values[i]);
                    }
                }
                for (int i = 0; i < binds.size(); i++) {
                    CallPlan.Bind b = binds.get(i);
                    if (b.in() != null) {
                        bindIn(cs, oc, i + 1, b, values[i], temporaries);
                    }
                }
                cs.addBatch();
                calls++;
                if (++pending == batchSize) {
                    cs.executeBatch();
                    pending = 0;
                    free(temporaries, null);
                    temporaries.clear();
                }
            }
            if (pending > 0) {
                cs.executeBatch();
            }
        } catch (SQLException | RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            free(temporaries, failure);
        }
        return calls;
    }

    /**
     * Готовит и выполняет блок плана — общая часть {@link #execute(Connection, CallPlan, Object[], int)}
     * и {@link #open}.
     *
     * <p>Значения вычисляются из аргументов метода, и текст проверяется на кодировку базы до того,
     * как что-либо уходит драйверу. Затем блок готовится как {@link CallableStatement}, получает
     * срок, у каждой позиции привязывается вход и регистрируется выход, блок выполняется. Если на
     * любом шаге после подготовки случилась ошибка, оператор закрывается здесь же.
     *
     * @param con            соединение, на котором выполняется вызов
     * @param plan           план вызова
     * @param args           аргументы метода интерфейса; {@code null} для метода без параметров
     * @param timeoutSeconds предельное время в секундах; {@code 0} — без ограничения
     * @param temporaries    сюда складываются созданные временные LOB; освобождает их вызывающий
     * @return выполненный оператор; выходы из него ещё не прочитаны
     * @throws SQLException если драйвер или база сообщили об ошибке
     */
    private CallableStatement run(Connection con, CallPlan plan, Object[] args, int timeoutSeconds,
                                  List<Object> temporaries) throws SQLException {
        Object[] a = args == null ? new Object[0] : args;
        OracleConnection oc = con.unwrap(OracleConnection.class);
        List<CallPlan.Bind> binds = plan.binds();
        Object[] values = new Object[binds.size()];
        for (int i = 0; i < binds.size(); i++) {
            CallPlan.Bind b = binds.get(i);
            if (b.in() != null) {
                values[i] = b.in().apply(a);
                precheck(b, values[i]);
            }
        }
        CallableStatement cs = con.prepareCall(plan.sql());
        try {
            if (timeoutSeconds > 0) {
                cs.setQueryTimeout(timeoutSeconds);
            }
            for (int i = 0; i < binds.size(); i++) {
                CallPlan.Bind b = binds.get(i);
                if (b.in() != null) {
                    bindIn(cs, oc, i + 1, b, values[i], temporaries);
                }
                if (b.outKey() != null) {
                    registerOut(cs, i + 1, b);
                }
            }
            cs.execute();
            return cs;
        } catch (SQLException | RuntimeException | Error e) {
            try {
                cs.close();
            } catch (SQLException c) {
                e.addSuppressed(c);
            }
            throw e;
        }
    }

    /**
     * Возвращает курсор OUT-позиции или {@code null}, если процедура его не открыла (ORA-24338).
     *
     * @param cs  выполненный вызов
     * @param idx номер позиции курсора, с единицы
     * @return строки курсора или {@code null}
     * @throws SQLException если драйвер не смог отдать курсор
     */
    private static ResultSet openedCursor(CallableStatement cs, int idx) throws SQLException {
        try {
            return (ResultSet) cs.getObject(idx);
        } catch (SQLException e) {
            if (e.getErrorCode() == CURSOR_NOT_OPENED) {
                return null;
            }
            throw e;
        }
    }

    /**
     * Освобождает временные LOB, созданные при привязке входных значений.
     *
     * <p>Освободить пытается все LOB, даже если какой-то из них не освободился; запоминается
     * только первая ошибка. Если вызов уже упал, ошибка освобождения присоединяется к этой
     * ошибке как подавленная ({@code addSuppressed}), а не заменяет её: от исходной ошибки
     * зависит, будет ли вызов повторён (например, после ORA-04068), и именно её видит
     * вызывающий код.
     *
     * @param temporaries созданные временные {@link Clob} и {@link Blob}
     * @param failure     ошибка вызова или {@code null}, если вызов прошёл успешно
     * @throws SQLException первая ошибка освобождения, если сам вызов прошёл успешно
     */
    private static void free(List<Object> temporaries, Throwable failure) throws SQLException {
        SQLException first = null;
        for (Object t : temporaries) {
            try {
                if (t instanceof Clob c) {
                    c.free();
                } else if (t instanceof Blob bl) {
                    bl.free();
                }
            } catch (SQLException e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        if (first != null) {
            if (failure != null) {
                failure.addSuppressed(first);
            } else {
                throw first;
            }
        }
    }

    // ------------------------------------------------------------------ IN: входные значения

    /**
     * Привязывает входное значение к позиции {@code ?} в блоке по виду аргумента.
     *
     * <p>{@code null} передаётся через {@code setNull} с типом JDBC, подходящим виду. Для
     * остальных значений:
     * <ul>
     *   <li>{@code NUMBER} — как {@link BigDecimal};</li>
     *   <li>{@code BOOLEAN} — как число 1 или 0: JDBC на Oracle 11.2 не умеет передавать
     *       PL/SQL {@code BOOLEAN}, поэтому блок сам превращает число в {@code TRUE} или
     *       {@code FALSE} через {@code CASE};</li>
     *   <li>{@code STRING} — как строка, после проверки кодировки;</li>
     *   <li>{@code DATE} и {@code TIMESTAMP} — как {@link java.sql.Timestamp};</li>
     *   <li>{@code CLOB} и {@code BLOB} — через временный LOB: он запоминается в
     *       {@code temporaries} ещё до заполнения и освобождается после вызова, иначе он
     *       оставался бы в сессии, пока соединение не закроется. По этой же ветке идёт и
     *       значение для {@code XMLTYPE}: план передаёт его текстом как {@code CLOB}, а в
     *       {@code XMLTYPE} его превращает сам блок;</li>
     *   <li>{@code RAW} — как массив байтов;</li>
     *   <li>{@code SQL_COLLECTION} и {@code OBJECT} — как {@link Array} и {@link Struct};
     *       даже для {@code null} драйверу нужно полное имя типа SQL;</li>
     *   <li>{@code INDEX_TABLE} — через {@code setPlsqlIndexTable}, расширение ojdbc для
     *       index-by таблиц. Элементы передаются массивом {@link BigDecimal} или строк;
     *       {@code null} превращается в пустую таблицу. Ёмкость — не меньше одного элемента,
     *       длина строкового элемента — по самой длинной строке, до 32766 (больше драйвер
     *       не принимает).</li>
     * </ul>
     *
     * @param cs          подготовленный вызов блока
     * @param oc          соединение ojdbc, умеющее создавать LOB, {@code Struct} и {@code Array}
     * @param idx         номер позиции {@code ?}, с единицы
     * @param b           описание этой позиции из плана вызова
     * @param v           значение из Java, может быть {@code null}
     * @param temporaries список, куда складываются созданные временные LOB
     * @throws SQLException если драйвер не принял значение
     * @throws IllegalStateException если вид позиции нельзя передать как входной
     *                               (например, {@code REF_CURSOR})
     * @throws CharsetGuard.UnrepresentableCharacterException если текст нельзя сохранить в
     *                               кодировке базы
     */
    private void bindIn(CallableStatement cs, OracleConnection oc, int idx, CallPlan.Bind b, Object v,
                        List<Object> temporaries) throws SQLException {
        switch (b.kind()) {
            case NUMBER -> {
                if (v == null) {
                    cs.setNull(idx, Types.NUMERIC);
                } else {
                    cs.setBigDecimal(idx, Values.toNumber(v));
                }
            }
            case BOOLEAN -> {
                Integer n = Values.toBooleanNumber(v);
                if (n == null) {
                    cs.setNull(idx, Types.NUMERIC);
                } else {
                    cs.setInt(idx, n);
                }
            }
            case STRING -> {
                // Национальная строка — через setNString: setString отправил бы текст в кодировке
                // базы, и символы вне неё пропали бы ещё до вызова.
                boolean national = isNational(b.arg());
                if (v == null) {
                    cs.setNull(idx, national ? Types.NVARCHAR : Types.VARCHAR);
                } else {
                    String s = Values.toText(v);
                    guard(b.arg(), s);
                    if (national) {
                        cs.setNString(idx, s);
                    } else {
                        cs.setString(idx, s);
                    }
                }
            }
            case DATE, TIMESTAMP -> {
                if (v == null) {
                    cs.setNull(idx, Types.TIMESTAMP);
                } else {
                    cs.setTimestamp(idx, Values.toTimestamp(v));
                }
            }
            case CLOB -> {
                // NCLOB — во временном NCLOB: временный CLOB хранит текст в кодировке базы.
                boolean national = isNational(b.arg());
                if (v == null) {
                    cs.setNull(idx, national ? Types.NCLOB : Types.CLOB);
                } else {
                    String s = Values.toText(v);
                    guard(b.arg(), s);
                    Clob c = national ? oc.createNClob() : oc.createClob();
                    temporaries.add(c);
                    c.setString(1, s);
                    if (national) {
                        cs.setNClob(idx, (NClob) c);
                    } else {
                        cs.setClob(idx, c);
                    }
                }
            }
            case BLOB -> {
                if (v == null) {
                    cs.setNull(idx, Types.BLOB);
                } else {
                    Blob bl = oc.createBlob();
                    temporaries.add(bl);
                    bl.setBytes(1, (byte[]) v);
                    cs.setBlob(idx, bl);
                }
            }
            case RAW -> {
                if (v == null) {
                    cs.setNull(idx, Types.VARBINARY);
                } else {
                    cs.setBytes(idx, (byte[]) v);
                }
            }
            case SQL_COLLECTION -> {
                if (v == null) {
                    cs.setNull(idx, Types.ARRAY, b.arg().sqlTypeName());
                } else {
                    cs.setArray(idx, toArray(oc, b.arg(), v));
                }
            }
            case OBJECT -> {
                if (v == null) {
                    cs.setNull(idx, Types.STRUCT, b.arg().sqlTypeName());
                } else {
                    cs.setObject(idx, toStruct(oc, b.arg(), v));
                }
            }
            case INDEX_TABLE -> {
                ArgumentInfo el = b.arg().children().get(0);
                Object[] values = elements(v, el, b.arg());
                OracleCallableStatement ocs = cs.unwrap(OracleCallableStatement.class);
                ocs.setPlsqlIndexTable(idx, values, Math.max(values.length, 1), values.length, oracleType(el),
                        el.kind() == ArgKind.STRING ? longest(values, b.arg()) : 0);
            }
            default -> throw new IllegalStateException("cannot bind " + b.kind() + " as IN");
        }
    }

    /**
     * Проверяет, что текст можно сохранить в кодировке базы, если аргумент её использует.
     *
     * <p>Типы, чьё имя начинается с {@code N} ({@code NVARCHAR2}, {@code NCHAR},
     * {@code NCLOB}), хранятся в национальном наборе символов базы, а не в основном, и
     * привязываются так, что текст идёт в национальной кодировке ({@code setNString},
     * временный {@code NCLOB}), поэтому для них проверка пропускается. В сообщении об ошибке указывается имя аргумента, а для
     * безымянного значения — слово {@code value}.
     *
     * @param arg описание аргумента (или поля, атрибута), куда пойдёт текст
     * @param s   проверяемый текст
     * @throws CharsetGuard.UnrepresentableCharacterException если текст нельзя сохранить
     */
    private void guard(ArgumentInfo arg, String s) {
        if (!isNational(arg)) {
            charsetGuard.check(arg.name() == null ? "value" : arg.name(), s);
        }
    }

    /**
     * Проверяет, добивает ли Oracle значение этого выхода пробелами до размера привязки: так
     * бывает у {@code OUT}-аргумента {@code CHAR} и {@code NCHAR}. У параметра {@code CHAR} без
     * длины размер берётся из фактического параметра, то есть из привязки, и {@code 'ab'} приходит
     * строкой из 32 767 символов (проверено на XE). У поля записи {@code CHAR(n)} и результата
     * функции длина своя, их это не касается.
     *
     * @param arg описание аргумента
     * @return {@code true} для аргумента верхнего уровня типа {@code CHAR} или {@code NCHAR}
     */
    private static boolean padded(ArgumentInfo arg) {
        return arg.name() != null && arg.dataLevel() == 0
                && ("CHAR".equals(arg.dataType()) || "NCHAR".equals(arg.dataType()));
    }

    /**
     * Отрезает хвостовые пробелы, которыми Oracle добил значение {@code CHAR}.
     *
     * @param s строка или {@code null}
     * @return строка без пробелов в конце; {@code null} остаётся {@code null}
     */
    private static String withoutPadding(String s) {
        if (s == null) {
            return null;
        }
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }

    /**
     * Проверяет, хранится ли текст этого типа в национальном наборе символов базы
     * ({@code NVARCHAR2}, {@code NCHAR}, {@code NCLOB}), а не в основном.
     *
     * @param arg описание аргумента, поля или элемента
     * @return {@code true} для типов, чьё имя начинается с {@code N}
     */
    private static boolean isNational(ArgumentInfo arg) {
        return arg.dataType() != null && arg.dataType().startsWith("N");
    }

    /**
     * Проверяет текст входного значения на кодировку базы до того, как вызов уходит драйверу.
     *
     * <p>Проверяются строки, {@code CLOB} (по нему же идёт текст для {@code XMLTYPE}) и строки
     * index-by таблиц. Текст внутри объектов и коллекций SQL проверяется при их сборке — тоже до
     * выполнения блока, но уже после того, как драйвер прочитал описание их типов.
     *
     * @param b позиция плана
     * @param v вычисленное значение
     * @throws CharsetGuard.UnrepresentableCharacterException если текст нельзя сохранить
     */
    private void precheck(CallPlan.Bind b, Object v) {
        if (v == null) {
            return;
        }
        switch (b.kind()) {
            case STRING, CLOB -> guard(b.arg(), Values.toText(v));
            case INDEX_TABLE -> elements(v, b.arg().children().get(0), b.arg());
            default -> {
            }
        }
    }

    /**
     * Превращает коллекцию или массив из Java в массив элементов для
     * {@code setPlsqlIndexTable}.
     *
     * <p>Для таблицы чисел получается {@code BigDecimal[]}, для остальных — {@code String[]}.
     * Каждая непустая строка проверяется на кодировку базы (кроме таблиц {@code NVARCHAR2}, у
     * которых свой, национальный набор символов); в сообщении об ошибке она называется как
     * {@code ИМЯ_ТАБЛИЦЫ[n]}, где {@code n} считается с единицы. {@code null} в элементах
     * сохраняется.
     *
     * @param v     коллекция, массив объектов или {@code null} (даёт пустой массив)
     * @param el    описание элемента таблицы
     * @param table описание самой таблицы, нужно для имени в сообщении об ошибке
     * @return массив элементов в порядке обхода коллекции
     * @throws IllegalArgumentException если {@code v} — не коллекция и не массив объектов
     */
    private Object[] elements(Object v, ArgumentInfo el, ArgumentInfo table) {
        Collection<?> c = asCollection(v);
        Object[] out = el.kind() == ArgKind.NUMBER ? new BigDecimal[c.size()]
                : el.kind() == ArgKind.DATE ? new java.sql.Timestamp[c.size()] : new String[c.size()];
        int i = 0;
        for (Object o : c) {
            if (el.kind() == ArgKind.NUMBER) {
                out[i++] = Values.toNumber(o);
            } else if (el.kind() == ArgKind.DATE) {
                out[i++] = o == null ? null : Values.toTimestamp(o);
            } else {
                String s = o == null ? null : Values.toText(o);
                if (s != null && !isNational(el)) {
                    charsetGuard.check(table.name() + "[" + (i + 1) + "]", s);
                }
                out[i++] = s;
            }
        }
        return out;
    }

    /**
     * Самая длинная строка index-by таблицы — столько драйвер резервирует под каждый элемент.
     *
     * @param values строки таблицы, среди них может быть {@code null}
     * @param table  описание таблицы, для сообщения об ошибке
     * @return длина самой длинной строки, не меньше 1
     * @throws IllegalArgumentException если строка длиннее 32766 символов: больше драйвер в
     *                                  элемент index-by таблицы не передаёт
     */
    private static int longest(Object[] values, ArgumentInfo table) {
        int max = 1;
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof String s && s.length() > max) {
                if (s.length() > MAX_PLSQL_VARCHAR) {
                    throw new IllegalArgumentException(table.name() + "[" + (i + 1) + "] is " + s.length()
                            + " characters; an index-by table element can hold at most " + MAX_PLSQL_VARCHAR);
                }
                max = s.length();
            }
        }
        return max;
    }

    /**
     * Самая длинная строка элемента index-by таблицы, которую принимает драйвер: 32766
     * (измерено на ojdbc 19; на 32767 он отвечает ORA-17053 «недопустимый размер»).
     */
    private static final int MAX_PLSQL_VARCHAR = 32766;

    /**
     * Сколько символов разрешено резервировать под одну выходную index-by таблицу строк.
     *
     * <p>Драйвер выделяет память сразу на {@code indexTableMaxLength} элементов по
     * объявленной длине каждый, сколько бы строк процедура ни вернула: на 11.2.0.4 это около
     * 4,75 байта на символ резерва (10 000 × 4000 — 190 МБ и 58 мс на вызов, 10 000 × 100 —
     * 4 МБ и 2 мс). Предел 50 млн символов — около 240 МБ; больше — ошибка при старте, а не
     * нехватка памяти под нагрузкой.
     */
    static final long MAX_INDEX_TABLE_RESERVE = 50_000_000L;

    /**
     * Проверяет при старте, что вызов по плану можно выполнить с настройками этого исполнителя.
     *
     * <p>Сейчас проверяется одно: резерв под выходные index-by таблицы строк
     * ({@code indexTableMaxLength} × объявленная длина элемента) не больше
     * {@link #MAX_INDEX_TABLE_RESERVE}.
     *
     * @param plan план вызова
     * @throws CallPlanner.PlanException если резерв слишком велик; сообщение называет аргумент,
     *                                   примерный объём памяти и допустимое
     *                                   {@code indexTableMaxLength}
     */
    public void verify(CallPlan plan) {
        // Таблица записей едет несколькими таблицами строк, по одной на поле, и резервируется
        // каждая; поэтому считается сумма по всему вызову.
        long total = 0;
        int chars = 0;
        List<String> tables = new ArrayList<>();
        for (CallPlan.Bind b : plan.binds()) {
            if (b.outKey() == null || b.kind() != ArgKind.INDEX_TABLE || b.arg().children().isEmpty()) {
                continue;
            }
            ArgumentInfo el = b.arg().children().get(0);
            if (el.kind() != ArgKind.STRING) {
                continue;
            }
            int len = declaredLength(el);
            total += (long) indexTableMaxLength * len;
            chars += len;
            tables.add((b.arg().name() == null ? "return value" : b.arg().name()) + " of " + el.dataType() + "(" + len + ")");
        }
        if (total > MAX_INDEX_TABLE_RESERVE) {
            throw new CallPlanner.PlanException("OUT index-by " + (tables.size() == 1 ? "table " : "tables ")
                    + String.join(", ", tables) + " would reserve about " + total * 19 / 4 / 1_048_576
                    + " MB per call for " + indexTableMaxLength + " elements; lower plsql.index-table-max-length to "
                    + MAX_INDEX_TABLE_RESERVE / chars + " or less");
        }
    }

    /**
     * Возвращает код типа ojdbc для элемента index-by таблицы: число, дата или строка.
     *
     * @param el описание элемента
     * @return {@code OracleTypes.NUMBER}, {@code DATE} или {@code VARCHAR}
     */
    private static int oracleType(ArgumentInfo el) {
        return switch (el.kind()) {
            case NUMBER -> OracleTypes.NUMBER;
            case DATE -> OracleTypes.DATE;
            default -> OracleTypes.VARCHAR;
        };
    }

    /**
     * Представляет значение как коллекцию: {@code null} — пустой список, коллекция —
     * как есть, массив — список его элементов (массив примитивов вроде {@code long[]}
     * копируется в массив обёрток).
     *
     * @param v значение из Java
     * @return коллекция элементов
     * @throws IllegalArgumentException если значение — не коллекция и не массив
     */
    private static Collection<?> asCollection(Object v) {
        if (v == null) {
            return List.of();
        }
        if (v instanceof Collection<?> col) {
            return col;
        }
        if (v instanceof Object[] arr) {
            return Arrays.asList(arr);
        }
        if (v.getClass().isArray()) {
            // long[], int[] и другие массивы примитивов.
            return Arrays.asList(ObjectUtils.toObjectArray(v));
        }
        throw new IllegalArgumentException("expected a collection or an array, got " + v.getClass().getName());
    }

    /**
     * Создаёт {@link Array} для коллекционного типа SQL ({@code TABLE OF} или
     * {@code VARRAY}, объявленного через {@code CREATE TYPE}).
     *
     * <p>{@code java.sql.Array} — объект JDBC, который несёт значение коллекции вместе с
     * именем её типа в базе. Создаётся через {@code createOracleArray}, расширение ojdbc,
     * по полному имени типа ({@code ВЛАДЕЛЕЦ.ИМЯ}). Каждый элемент приводится по описанию
     * элемента коллекции (см. {@link #toSqlValue}); если описания нет, элементы передаются
     * как есть.
     *
     * @param oc   соединение ojdbc
     * @param coll описание коллекции; её единственный потомок описывает элемент
     * @param v    коллекция или массив объектов из Java
     * @return готовое значение для привязки
     * @throws SQLException если драйвер не смог создать значение, например тип не найден
     */
    private Array toArray(OracleConnection oc, ArgumentInfo coll, Object v) throws SQLException {
        ArgumentInfo el = coll.children().isEmpty() ? null : coll.children().get(0);
        Collection<?> c = asCollection(v);
        Object[] out = new Object[c.size()];
        int i = 0;
        for (Object o : c) {
            out[i++] = el == null ? o : toSqlValue(oc, el, o);
        }
        return oc.createOracleArray(coll.sqlTypeName(), out);
    }

    /**
     * Создаёт {@link Struct} для объектного типа SQL ({@code CREATE TYPE ... AS OBJECT}).
     *
     * <p>{@code java.sql.Struct} — объект JDBC со значениями атрибутов объектного типа по
     * порядку их объявления. Для каждого атрибута из описания типа значение берётся из
     * record, бина или {@code Map} по имени PL/SQL ({@link Values#property}) и приводится
     * через {@link #toSqlValue}. Если подходящего свойства нет, атрибут уходит как
     * {@code NULL}, без ошибки.
     *
     * @param oc  соединение ojdbc
     * @param obj описание объектного типа; потомки — атрибуты в порядке объявления
     * @param v   record, бин или {@code Map} из Java
     * @return готовое значение для привязки
     * @throws SQLException если драйвер не смог создать значение, например тип не найден
     */
    private Struct toStruct(OracleConnection oc, ArgumentInfo obj, Object v) throws SQLException {
        Object[] attrs = new Object[obj.children().size()];
        for (int i = 0; i < attrs.length; i++) {
            ArgumentInfo f = obj.children().get(i);
            attrs[i] = toSqlValue(oc, f, Values.property(v, f.name()));
        }
        return oc.createStruct(obj.sqlTypeName(), attrs);
    }

    /**
     * Приводит одно значение (атрибут объекта или элемент коллекции) к виду, который драйвер
     * примет внутри {@code Struct} или {@code Array}.
     *
     * <p>Числа становятся {@link BigDecimal}, даты — {@link java.sql.Timestamp}, строки
     * проверяются на кодировку; вложенные объекты и коллекции собираются рекурсивно.
     * Значения остальных видов передаются драйверу как есть.
     *
     * @param oc соединение ojdbc
     * @param t  описание атрибута или элемента
     * @param v  значение из Java, может быть {@code null}
     * @return значение для драйвера или {@code null}
     * @throws SQLException если не удалось создать вложенный {@code Struct} или {@code Array}
     */
    private Object toSqlValue(OracleConnection oc, ArgumentInfo t, Object v) throws SQLException {
        if (v == null) {
            return null;
        }
        return switch (t.kind()) {
            case NUMBER -> Values.toNumber(v);
            case DATE, TIMESTAMP -> Values.toTimestamp(v);
            case STRING -> {
                String s = Values.toText(v);
                guard(t, s);
                yield s;
            }
            case OBJECT -> toStruct(oc, t, v);
            case SQL_COLLECTION -> toArray(oc, t, v);
            default -> v;
        };
    }

    // ------------------------------------------------------------------ OUT: выходные значения

    /**
     * Регистрирует позицию {@code ?} как выходную.
     *
     * <p>{@code registerOutParameter} — вызов JDBC, которым драйверу заранее сообщают тип
     * SQL выходной позиции, чтобы он знал, как принять значение после выполнения блока.
     * Соответствие видов:
     * <ul>
     *   <li>{@code NUMBER} и {@code BOOLEAN} — {@code NUMERIC} ({@code BOOLEAN} блок отдаёт
     *       числом 1 или 0);</li>
     *   <li>{@code STRING} — {@code VARCHAR} ({@code NVARCHAR2}, {@code NCHAR} — {@code NVARCHAR},
     *       чтобы текст шёл в национальной кодировке); {@code DATE} и {@code TIMESTAMP} —
     *       {@code TIMESTAMP}; {@code RAW} — {@code VARBINARY};</li>
     *   <li>{@code CLOB} и {@code XMLTYPE} — {@code CLOB} ({@code NCLOB} — {@code NCLOB};
     *       {@code XMLTYPE} блок отдаёт текстом через {@code getClobVal()}); {@code BLOB} —
     *       {@code BLOB};</li>
     *   <li>{@code REF_CURSOR} — {@code OracleTypes.CURSOR}, код типа ojdbc для курсора;</li>
     *   <li>{@code SQL_COLLECTION} и {@code OBJECT} — {@code ARRAY} и {@code STRUCT} с полным
     *       именем типа SQL;</li>
     *   <li>{@code INDEX_TABLE} — {@code registerIndexTableOutParameter}: драйвер резервирует
     *       место под {@code indexTableMaxLength} элементов, строковый элемент — по объявленной длине
     *       (см. {@code declaredLength}).</li>
     * </ul>
     *
     * @param cs  подготовленный вызов блока
     * @param idx номер позиции {@code ?}, с единицы
     * @param b   описание этой позиции из плана вызова
     * @throws SQLException если драйвер отказался регистрировать позицию
     * @throws IllegalStateException если вид позиции нельзя прочитать как выходной
     */
    private void registerOut(CallableStatement cs, int idx, CallPlan.Bind b) throws SQLException {
        switch (b.kind()) {
            case NUMBER, BOOLEAN -> cs.registerOutParameter(idx, Types.NUMERIC);
            case STRING -> cs.registerOutParameter(idx, isNational(b.arg()) ? Types.NVARCHAR : Types.VARCHAR);
            case DATE, TIMESTAMP -> cs.registerOutParameter(idx, Types.TIMESTAMP);
            case CLOB -> cs.registerOutParameter(idx, isNational(b.arg()) ? Types.NCLOB : Types.CLOB);
            case XMLTYPE -> cs.registerOutParameter(idx, Types.CLOB);
            case BLOB -> cs.registerOutParameter(idx, Types.BLOB);
            case RAW -> cs.registerOutParameter(idx, Types.VARBINARY);
            case REF_CURSOR -> cs.registerOutParameter(idx, OracleTypes.CURSOR);
            case SQL_COLLECTION -> cs.registerOutParameter(idx, Types.ARRAY, b.arg().sqlTypeName());
            case OBJECT -> cs.registerOutParameter(idx, Types.STRUCT, b.arg().sqlTypeName());
            case INDEX_TABLE -> {
                ArgumentInfo el = b.arg().children().get(0);
                cs.unwrap(OracleCallableStatement.class).registerIndexTableOutParameter(idx, indexTableMaxLength,
                        oracleType(el), el.kind() == ArgKind.STRING ? declaredLength(el) : 0);
            }
            default -> throw new IllegalStateException("cannot read " + b.kind() + " as OUT");
        }
    }

    /**
     * Читает значение выходной позиции после выполнения блока.
     *
     * <p>Здесь значение только достаётся из драйвера в простом виде; к типу метода его
     * приводит потом {@link Values#convert}. Что получается:
     * <ul>
     *   <li>{@code NUMBER} — {@link BigDecimal}; {@code BOOLEAN} — {@link Boolean}
     *       (не ноль — {@code true});</li>
     *   <li>{@code STRING} — строка (у {@code OUT}-аргумента {@code CHAR} и {@code NCHAR} — без
     *       пробелов, которыми Oracle добил её до размера привязки); {@code DATE} и {@code TIMESTAMP} —
     *       {@link java.sql.Timestamp}; {@code RAW} — массив байтов;</li>
     *   <li>{@code CLOB} и {@code XMLTYPE} — строка; LOB читается целиком и сразу
     *       освобождается;</li>
     *   <li>{@code BLOB} — массив байтов, LOB тоже освобождается;</li>
     *   <li>{@code REF_CURSOR} — список строк курсора (см. {@link #readCursor});</li>
     *   <li>{@code SQL_COLLECTION} — список; {@code OBJECT} — {@code Map} атрибутов;</li>
     *   <li>{@code INDEX_TABLE} — список элементов.</li>
     * </ul>
     * SQL {@code NULL} возвращается как {@code null}. Index-by таблица возвращается списком
     * всегда (пустым, если элементов нет); её элементы-{@code NULL} остаются {@code null}.
     *
     * @param cs  выполненный вызов блока
     * @param idx номер позиции {@code ?}, с единицы
     * @param b   описание этой позиции из плана вызова
     * @return значение позиции или {@code null}
     * @throws SQLException если драйвер не смог отдать значение
     * @throws IllegalStateException если вид позиции нельзя прочитать
     */
    private Object readOut(CallableStatement cs, int idx, CallPlan.Bind b) throws SQLException {
        return switch (b.kind()) {
            case NUMBER -> cs.getBigDecimal(idx);
            case BOOLEAN -> {
                BigDecimal n = cs.getBigDecimal(idx);
                yield n == null ? null : n.intValue() != 0;
            }
            case STRING -> padded(b.arg()) ? withoutPadding(cs.getString(idx)) : cs.getString(idx);
            case DATE, TIMESTAMP -> cs.getTimestamp(idx);
            case CLOB, XMLTYPE -> {
                Clob c = cs.getClob(idx);
                yield c == null ? null : Values.clobToString(c);
            }
            case BLOB -> {
                Blob bl = cs.getBlob(idx);
                if (bl == null) {
                    yield null;
                }
                try {
                    yield bl.getBytes(1, (int) bl.length());
                } finally {
                    bl.free();
                }
            }
            case RAW -> cs.getBytes(idx);
            case REF_CURSOR -> readCursor(cs, idx, b.outType(), fetchSize);
            case SQL_COLLECTION -> {
                Array a = cs.getArray(idx);
                yield a == null ? null : fromArray(a, b.arg());
            }
            case OBJECT -> {
                Object o = cs.getObject(idx);
                yield o == null ? null : fromStruct((Struct) o, b.arg());
            }
            case INDEX_TABLE -> {
                // Arrays.asList, а не List.of: элемент index-by таблицы может быть NULL.
                Object[] raw = (Object[]) cs.unwrap(OracleCallableStatement.class).getPlsqlIndexTable(idx);
                yield raw == null ? List.of() : TableSplit.fromTransport(Arrays.asList(raw), b.arg().children().get(0));
            }
            default -> throw new IllegalStateException("cannot read " + b.kind());
        };
    }

    /**
     * Длина строкового элемента выходной index-by таблицы: столько драйвер резервирует под
     * каждый из {@code indexTableMaxLength} элементов.
     *
     * <p>Берётся объявленная длина ({@code VARCHAR2(100)} — 100) из словаря, но не больше 32766
     * (больше драйвер не принимает); если словарь её не сообщил, — 4000.
     *
     * @param el описание элемента таблицы
     * @return длина в символах, от 1 до 32766
     */
    private static int declaredLength(ArgumentInfo el) {
        Integer n = el.charLength();
        return n == null ? 4000 : Math.max(1, Math.min(n, MAX_PLSQL_VARCHAR));
    }

    /**
     * Читает все строки выходного курсора ({@code REF CURSOR}) и закрывает его.
     *
     * <p>{@code REF CURSOR} ({@code SYS_REFCURSOR}) — ссылка на запрос, который процедура
     * открыла в базе; драйвер отдаёт его как обычный {@link ResultSet}. Строки превращаются
     * в объекты по типу элемента результата (см. {@link #elementType} и
     * {@link RowMappers#mapAll}). После чтения курсор закрывается, иначе он оставался бы
     * открытым в сессии.
     *
     * <p>Если процедура курсор не открыла (ORA-24338), строк нет: возвращается пустой список,
     * а не ошибка и не {@code null}.
     *
     * @param cs     выполненный вызов блока
     * @param idx    номер позиции {@code ?}, с единицы
     * @param target    тип Java, куда идёт значение (например, {@code List<Employee>}),
     *                  или {@code null}
     * @param fetchSize сколько строк забирать за обращение к базе; {@code 0} — как у драйвера
     * @return список строк; пустой, если курсор не открыт
     * @throws SQLException если драйвер не смог отдать курсор или прочитать строку
     */
    private static List<Object> readCursor(CallableStatement cs, int idx, ResolvableType target, int fetchSize)
            throws SQLException {
        ResultSet rs = openedCursor(cs, idx);
        if (rs == null) {
            return List.of();
        }
        try (rs) {
            if (fetchSize > 0) {
                rs.setFetchSize(fetchSize);
            }
            return RowMappers.mapAll(rs, elementType(target));
        }
    }

    /**
     * Превращает {@link Array}, прочитанный из базы, в список Java и освобождает его.
     *
     * <p>Элементы-объекты ({@link Struct}) становятся {@code Map} атрибутов, если известно
     * описание элемента; остальные элементы остаются в том виде, в каком их отдал драйвер.
     *
     * @param a    коллекция из базы
     * @param coll описание коллекционного типа; его единственный потомок описывает элемент
     * @return список элементов в исходном порядке
     * @throws SQLException если драйвер не смог отдать элементы
     */
    private List<Object> fromArray(Array a, ArgumentInfo coll) throws SQLException {
        ArgumentInfo el = coll.children().isEmpty() ? null : coll.children().get(0);
        try {
            Object[] raw = (Object[]) a.getArray();
            List<Object> out = new ArrayList<>(raw.length);
            for (Object o : raw) {
                out.add(o instanceof Struct s && el != null ? fromStruct(s, el) : o);
            }
            return out;
        } finally {
            a.free();
        }
    }

    /**
     * Превращает {@link Struct}, прочитанный из базы, в карту «имя атрибута — значение».
     *
     * <p>Атрибуты сопоставляются с описанием типа по порядку объявления; ключ — имя
     * атрибута PL/SQL. Вложенные объекты и коллекции разбираются рекурсивно, атрибуты
     * {@code CLOB} и {@code BLOB} читаются в строку и массив байтов, а сами LOB освобождаются.
     * Лишние атрибуты без описания пропускаются. В record или бин карту превращает потом
     * {@link Values#convert}.
     *
     * @param s   объект из базы
     * @param obj описание объектного типа; потомки — атрибуты в порядке объявления
     * @return карта атрибутов в порядке объявления
     * @throws SQLException если драйвер не смог отдать атрибуты
     */
    private Map<String, Object> fromStruct(Struct s, ArgumentInfo obj) throws SQLException {
        Object[] attrs = s.getAttributes();
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < attrs.length && i < obj.children().size(); i++) {
            ArgumentInfo f = obj.children().get(i);
            Object v = attrs[i];
            if (v instanceof Struct inner) {
                v = fromStruct(inner, f);
            } else if (v instanceof Array arr) {
                v = fromArray(arr, f);
            } else if (v instanceof Clob c) {
                v = Values.clobToString(c);
            } else if (v instanceof Blob bl) {
                try {
                    v = bl.getBytes(1, (int) bl.length());
                } finally {
                    bl.free();
                }
            }
            m.put(f.name(), v);
        }
        return m;
    }

    /**
     * Определяет, в какой тип превращать одну строку курсора.
     *
     * <p>Для {@code List<X>} и других коллекций это {@code X}, для {@code Optional<...>} —
     * то же правило для содержимого. Если тип неизвестен ({@code null}) или это не
     * коллекция, строка становится {@code Map}.
     *
     * @param t тип Java, куда идёт значение курсора, или {@code null}
     * @return тип одной строки
     */
    private static ResolvableType elementType(ResolvableType t) {
        if (t == null) {
            return ResolvableType.forClass(Map.class);
        }
        Class<?> raw = t.resolve(Object.class);
        if (Collection.class.isAssignableFrom(raw)) {
            return t.asCollection().getGeneric(0);
        }
        if (raw == java.util.Optional.class) {
            return elementType(t.getGeneric(0));
        }
        return ResolvableType.forClass(Map.class);
    }

    /**
     * Собирает выходы вида {@code ARG.FIELD} в одну карту под ключом {@code ARG}.
     *
     * <p>PL/SQL {@code RECORD} нельзя получить через JDBC на Oracle 11.2, поэтому блок
     * читает запись в локальную переменную и отдаёт каждое её поле отдельной позицией
     * {@code ?}. Здесь эти поля снова объединяются: ключи с префиксом записи удаляются из
     * {@code outs}, а вместо них кладётся карта «имя поля — значение» в порядке позиций.
     * Вложенная запись ({@code P_REC.ADDR}) стоит в списке раньше внешней, поэтому сначала
     * собирается она, а потом уже целой картой ложится во внешнюю.
     *
     * @param outs    выходы вызова по ключам; изменяется на месте
     * @param records ключи OUT-записей, чьи поля нужно собрать
     */
    static void foldRecords(Map<String, Object> outs, List<String> records) {
        for (String rec : records) {
            Map<String, Object> m = new LinkedHashMap<>();
            String prefix = rec + ".";
            outs.entrySet().removeIf(e -> {
                if (e.getKey().startsWith(prefix)) {
                    m.put(e.getKey().substring(prefix.length()), e.getValue());
                    return true;
                }
                return false;
            });
            outs.put(rec, m);
        }
    }
}
