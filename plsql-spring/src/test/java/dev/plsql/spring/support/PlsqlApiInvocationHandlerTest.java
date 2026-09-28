package dev.plsql.spring.support;

import static dev.plsql.spring.test.Signatures.func;
import static dev.plsql.spring.test.Signatures.proc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import dev.plsql.spring.PlsqlApiFactory;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import dev.plsql.spring.annotation.PlsqlApi;
import dev.plsql.spring.annotation.Procedure;
import dev.plsql.spring.annotation.SqlQuery;
import dev.plsql.spring.test.Signatures;
import oracle.jdbc.OracleConnection;

/**
 * Поведение прокси {@link PlsqlApiInvocationHandler} на моках: проверка интерфейса при
 * создании, единица работы (фиксация и откат), повтор вызова, преобразование ошибок и методы
 * {@code @SqlQuery}. Сигнатуры приходят из фикстуры {@code Signatures}, база не нужна.
 */
class PlsqlApiInvocationHandlerTest {

    /** Рабочий интерфейс пакета {@code PKG}; сигнатуры его подпрограмм задаёт {@code factory()}. */
    @PlsqlApi(packageName = "PKG")
    interface Api {
        /**
         * Функция {@code PKG.NEXT(NTENANT IN NUMBER) RETURN NUMBER}.
         *
         * @param tenant идёт в {@code NTENANT}
         * @return результат функции
         */
        long next(long tenant);

        /**
         * Процедура {@code PKG.TOUCH(NTENANT IN NUMBER)}.
         *
         * @param tenant идёт в {@code NTENANT}
         */
        void touch(long tenant);

        /**
         * Default-метод: процедуру для него не ищут, прокси выполняет его тело как обычный код
         * Java, а тело само вызывает {@link #next}.
         *
         * @param tenant передаётся в {@link #next}
         * @return удвоенный результат {@link #next}
         */
        default long twice(long tenant) {
            return next(tenant) * 2;
        }
    }

    /** Методы {@code @SqlQuery}: обычный SQL с именованными параметрами вместо вызова процедуры. */
    @PlsqlApi(packageName = "PKG")
    interface Queries {
        /**
         * {@code UPDATE}, текст которого начинается с комментария, поэтому по первому слову вид
         * запроса не определить.
         *
         * @param id значение параметра {@code :id}
         * @return число изменённых строк
         */
        @SqlQuery("/* audit */ update t set x = 1 where id = :id")
        int touch(long id);

        /**
         * {@code SELECT} одной колонки.
         *
         * @param id значение параметра {@code :id}
         * @return имя или пустой {@code Optional}, если строки нет
         */
        @SqlQuery("select name from t where id = :id")
        java.util.Optional<String> name(long id);
    }

    /** Два метода с одним именем: какой из них показать, {@code sqlOf} не угадывает. */
    @PlsqlApi(packageName = "PKG")
    interface Overloaded {
        /**
         * {@code PKG.NEXT} с числом.
         *
         * @param tenant идёт в {@code NTENANT}
         * @return результат функции
         */
        long next(long tenant);

        /**
         * {@code PKG.NEXT} со строкой, которая разбирается как число.
         *
         * @param tenant идёт в {@code NTENANT}
         * @return результат функции
         */
        long next(String tenant);
    }

    /** Методы, которые при старте должны быть отвергнуты или при вызове сообщить понятную ошибку. */
    @PlsqlApi(packageName = "PKG")
    interface Misused {
        /**
         * {@code UPDATE}, но метод ждёт список строк.
         *
         * @return строки; число изменённых строк в них не превратить
         */
        @SqlQuery("update t set x = 1")
        java.util.List<java.util.Map<String, Object>> update();
    }

    /** Метод и с {@code @SqlQuery}, и с {@code @Procedure}. */
    @PlsqlApi(packageName = "PKG")
    interface Both {
        /**
         * Непонятно, что вызывать: запрос или процедуру.
         *
         * @return результат
         */
        @SqlQuery("select 1 from dual")
        @Procedure("NEXT")
        long both();
    }

    /** Запрос ссылается на параметр {@code :missing}, которого у метода нет. */
    @PlsqlApi(packageName = "PKG")
    interface BadQuery {
        /**
         * Метод с параметром {@code other}, а не {@code missing}.
         *
         * @param other параметр, о котором SQL ничего не знает
         * @return результат запроса; до вызова дело не доходит
         */
        @SqlQuery("select 1 from dual where x = :missing")
        int q(long other);
    }

    /** Интерфейс, который расходится с базой сразу в двух методах. */
    @PlsqlApi(packageName = "PKG")
    interface Broken {
        /**
         * Функция {@code PKG.MISSING}, которой в фикстуре нет.
         *
         * @return результат функции; до вызова дело не доходит
         */
        long missing();

        /**
         * Функция {@code PKG.NEXT} существует, но параметр {@code wrongName} не совпадает с её
         * аргументом {@code NTENANT}.
         *
         * @param wrongName параметр без пары среди аргументов
         * @return результат функции; до вызова дело не доходит
         */
        long next(String wrongName);
    }

    /** Сроки вызова: свой у метода, срок фабрики и явное «без ограничения». */
    @PlsqlApi(packageName = "PKG")
    interface Timed {
        /**
         * {@code PKG.TOUCH} со сроком 7 секунд.
         *
         * @param tenant идёт в {@code NTENANT}
         */
        @Procedure(value = "TOUCH", timeout = 7)
        void limited(long tenant);

        /**
         * {@code PKG.TOUCH} со сроком фабрики.
         *
         * @param tenant идёт в {@code NTENANT}
         */
        void touch(long tenant);

        /**
         * {@code PKG.TOUCH} без ограничения, даже если у фабрики срок есть.
         *
         * @param tenant идёт в {@code NTENANT}
         */
        @Procedure(value = "TOUCH", timeout = 0)
        void unlimited(long tenant);

        /**
         * Запрос со сроком 4 секунды.
         *
         * @param id значение параметра {@code :id}
         * @return число изменённых строк
         */
        @SqlQuery(value = "update t set x = 1 where id = :id", timeout = 4)
        int update(long id);
    }

    /** Срок меньше {@code -1} — ошибка в аннотации. */
    @PlsqlApi(packageName = "PKG")
    interface BadTimeout {
        /**
         * {@code PKG.TOUCH} с отрицательным сроком.
         *
         * @param tenant идёт в {@code NTENANT}
         */
        @Procedure(value = "TOUCH", timeout = -5)
        void touch(long tenant);
    }

    DataSource ds;
    OracleConnection con;
    CallableStatement cs;
    Api api;

    /**
     * Готовит моки: {@code DataSource} выдаёт соединение Oracle, {@code prepareCall} с любым
     * текстом возвращает один и тот же {@code CallableStatement}, соединение по умолчанию в
     * режиме {@code autoCommit=true}. Затем создаёт прокси {@link Api}.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @BeforeEach
    void setUp() throws SQLException {
        ds = mock(DataSource.class);
        con = mock(OracleConnection.class);
        cs = mock(CallableStatement.class);
        when(ds.getConnection()).thenReturn(con);
        when(con.unwrap(OracleConnection.class)).thenReturn(con);
        when(con.prepareCall(anyString())).thenReturn(cs);
        when(con.getAutoCommit()).thenReturn(true);
        api = factory().create(Api.class);
    }

    /**
     * Фабрика на моке {@code DataSource} с сигнатурами {@code PKG.NEXT} (функция) и
     * {@code PKG.TOUCH} (процедура). Кодировка базы AL32UTF8 задана явно: фабрика не читает
     * {@code NLS_CHARACTERSET} из базы, а проверка символов для Unicode не нужна.
     *
     * @return новая фабрика
     */
    PlsqlApiFactory factory() {
        return builder().build();
    }

    /**
     * Построитель той же фабрики, что {@link #factory()}, — для тестов, которым нужны свои настройки.
     *
     * @return построитель с сигнатурами {@code PKG.NEXT} и {@code PKG.TOUCH}
     */
    PlsqlApiFactory.Builder builder() {
        return PlsqlApiFactory.builder(ds)
                .signatureSource(Signatures.source(
                        func("PKG", "NEXT", "NUMBER").in("NTENANT", "NUMBER").build(),
                        proc("PKG", "TOUCH").in("NTENANT", "NUMBER").build()))
                .databaseCharset("AL32UTF8");
    }

    /**
     * Проверяет, что метод прокси исполняет заранее построенный блок
     * ({@code ? := APP.PKG.NEXT(NTENANT => ?)}) и возвращает результат функции, default-метод
     * {@code twice} работает поверх него, {@code toString} называет интерфейс, а {@code equals}
     * сравнивает по ссылке.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void callsGoThroughThePlannedBlock() throws SQLException {
        when(cs.getBigDecimal(1)).thenReturn(BigDecimal.TEN);
        assertThat(api.next(1001)).isEqualTo(10);
        assertThat(api.twice(1)).isEqualTo(20);
        verify(con, atLeastOnce()).prepareCall("BEGIN\n  ? := APP.PKG.NEXT(NTENANT => ?);\nEND;");
        assertThat(api.toString()).contains(Api.class.getName());
        assertThat(api).isEqualTo(api).isNotEqualTo(new Object());
    }

    /**
     * Проверяет, что все расхождения интерфейса с базой сообщаются одним исключением при
     * создании, а не при первом вызове: и отсутствующая функция, и параметр без пары. Класс,
     * который не является интерфейсом, отвергается с {@code IllegalArgumentException}.
     */
    @Test
    void allMismatchesAreReportedAtCreation() {
        assertThatThrownBy(() -> factory().create(Broken.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Broken.missing -> PKG.MISSING: not found in the database")
                .hasMessageContaining("Broken.next -> PKG.NEXT: parameter 'wrongName' has no matching argument");
        assertThatThrownBy(() -> factory().create(String.class)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Проверяет, что ошибка {@code RAISE_APPLICATION_ERROR} (ORA-20001) становится
     * {@link PlsqlBusinessException} с чистым текстом, без стека ORA-06512, и что соединение
     * при этом закрывается, то есть возвращается в пул.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void applicationErrorBecomesBusinessException() throws SQLException {
        when(cs.execute()).thenThrow(new SQLException("ORA-20001: Нельзя\nORA-06512: at line 1", "72000", 20001));
        assertThatThrownBy(() -> api.touch(1)).isInstanceOf(PlsqlBusinessException.class).hasMessage("Нельзя");
        verify(con).close();
    }

    /**
     * Проверяет, что после ORA-04068 (состояние пакета сброшено, например пакет
     * перекомпилировали под живой сессией) вызов повторяется и повтор проходит: {@code execute}
     * вызван дважды. Изменения данных неудачного вызова Oracle уже откатил, поэтому повтор безопасен.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void discardedPackageStateIsRetriedOnce() throws SQLException {
        when(cs.execute())
                .thenThrow(new SQLException("ORA-04068", "72000", 4068))
                .thenReturn(false);
        api.touch(1);
        verify(cs, times(2)).execute();
    }

    /**
     * Проверяет, что повтор только один: если ORA-04068 пришла и во второй раз, ошибка выходит
     * наружу, а {@code execute} вызван ровно дважды.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void secondDiscardIsNotRetried() throws SQLException {
        when(cs.execute()).thenThrow(new SQLException("ORA-04068", "72000", 4068));
        assertThatThrownBy(() -> api.touch(1)).hasMessageContaining("ORA-04068");
        verify(cs, times(2)).execute();
    }

    /**
     * Проверяет, что вне транзакции Spring при {@code autoCommit=false} каждый вызов — своя
     * единица работы: успешный фиксируется ({@code commit}), неудачный (ORA-00001, он же
     * {@code DuplicateKeyException}) откатывается ({@code rollback}). Иначе незафиксированную
     * работу откатил бы пул при возврате соединения.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void withoutATransactionAndAutoCommitOffTheCallIsItsOwnUnitOfWork() throws SQLException {
        when(con.getAutoCommit()).thenReturn(false);
        api.touch(1);
        verify(con).commit();

        when(cs.execute()).thenThrow(new SQLException("ORA-00001", "23000", 1));
        assertThatThrownBy(() -> api.touch(1)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        verify(con).rollback();
    }

    /**
     * Проверяет, что на соединении с {@code autoCommit=true} прокси сам не вызывает ни
     * {@code commit}, ни {@code rollback}.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void autoCommitConnectionsAreLeftAlone() throws SQLException {
        api.touch(1);
        verify(con, never()).commit();
        verify(con, never()).rollback();
    }

    /**
     * Проверяет, что внутри транзакции Spring вызовы ничего не фиксируют сами: оба вызова идут
     * через одно соединение (из {@code DataSource} оно берётся один раз), а {@code commit}
     * один раз делает менеджер транзакций в конце.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void insideASpringTransactionNothingIsCommittedPerCall() throws SQLException {
        when(con.getAutoCommit()).thenReturn(false);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.executeWithoutResult(s -> {
            api.touch(1);
            api.touch(2);
            try {
                verify(con, never()).commit();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        verify(con, times(1)).commit(); // делает менеджер транзакций, один раз
        verify(ds, times(1)).getConnection();
    }

    /**
     * Проверяет служебные методы: {@code sqlOf} показывает сгенерированный блок метода и
     * возвращает {@code null} для default-метода, у которого блока нет; {@code subprogramName}
     * даёт имя подпрограммы в верхнем регистре.
     *
     * @throws NoSuchMethodException не бросается: метод {@code touch} есть
     */
    @Test
    void sqlOfShowsTheBlock() throws NoSuchMethodException {
        assertThat(PlsqlApiInvocationHandler.sqlOf(api, "touch")).isEqualTo("BEGIN\n  APP.PKG.TOUCH(NTENANT => ?);\nEND;");
        assertThat(PlsqlApiInvocationHandler.sqlOf(api, "twice")).isNull();
        assertThat(PlsqlApiInvocationHandler.subprogramName(Api.class.getMethod("touch", long.class))).isEqualTo("TOUCH");
    }

    /**
     * Проверяет, что сигнатуры всех методов интерфейса запрашиваются одним пакетным вызовом
     * {@code findAll} (с именами {@code NEXT} и {@code TOUCH}), а поштучный {@code find} не
     * вызывается ни разу. На настоящей базе это экономит запросы к словарю при старте.
     */
    @Test
    void signaturesOfAnInterfaceAreReadInOneBatch() {
        java.util.concurrent.atomic.AtomicInteger single = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger batch = new java.util.concurrent.atomic.AtomicInteger();
        dev.plsql.spring.meta.SignatureSource fixture = Signatures.source(
                func("PKG", "NEXT", "NUMBER").in("NTENANT", "NUMBER").build(),
                proc("PKG", "TOUCH").in("NTENANT", "NUMBER").build());
        dev.plsql.spring.meta.SignatureSource counting = new dev.plsql.spring.meta.SignatureSource() {
            /**
             * Считает поштучные запросы и отдаёт сигнатуры из фикстуры.
             *
             * @param schema схема или {@code null}
             * @param pkg    пакет или {@code null}
             * @param name   имя подпрограммы
             * @return перегрузки из фикстуры
             */
            @Override
            public java.util.List<dev.plsql.spring.meta.SubprogramInfo> find(String schema, String pkg, String name) {
                single.incrementAndGet();
                return fixture.find(schema, pkg, name);
            }

            /**
             * Считает пакетные запросы, проверяет, что запрошены ровно {@code NEXT} и
             * {@code TOUCH}, и отдаёт их сигнатуры из фикстуры.
             *
             * @param schema схема или {@code null}
             * @param pkg    пакет или {@code null}
             * @param names  имена подпрограмм
             * @return карта «имя → перегрузки»
             */
            @Override
            public java.util.Map<String, java.util.List<dev.plsql.spring.meta.SubprogramInfo>> findAll(
                    String schema, String pkg, java.util.Collection<String> names) {
                batch.incrementAndGet();
                assertThat(names).containsExactlyInAnyOrder("NEXT", "TOUCH");
                java.util.Map<String, java.util.List<dev.plsql.spring.meta.SubprogramInfo>> m = new java.util.HashMap<>();
                names.forEach(n -> m.put(n, fixture.find(schema, pkg, n)));
                return m;
            }
        };
        PlsqlApiFactory.builder(ds).signatureSource(counting).databaseCharset("AL32UTF8").build().create(Api.class);
        assertThat(batch).hasValue(1);
        assertThat(single).hasValue(0);
    }

    /**
     * Проверяет, что именованный параметр SQL {@code :missing}, которого нет у метода,
     * обнаруживается при создании прокси, и сообщение называет метод и параметр.
     */
    @Test
    void queryParametersAreCheckedAtCreation() {
        assertThatThrownBy(() -> factory().create(BadQuery.class))
                .hasMessageContaining("BadQuery.q").hasMessageContaining("missing");
    }

    /**
     * Проверяет, что вид результата {@code @SqlQuery} определяет JDBC, а не первое слово
     * запроса: {@code execute()} вернул {@code false} — метод получает число изменённых строк
     * (здесь для {@code UPDATE}, который начинается с комментария), {@code true} — строки
     * результата ({@code SELECT} одной колонки в {@code Optional}). Заодно видно, что
     * {@code :id} в тексте заменяется на {@code ?}.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void queryResultKindComesFromJdbcNotFromTheFirstWord() throws SQLException {
        java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
        when(con.prepareStatement(anyString())).thenReturn(ps);
        Queries q = factory().create(Queries.class);

        when(ps.execute()).thenReturn(false);
        when(ps.getUpdateCount()).thenReturn(1);
        assertThat(q.touch(5)).isEqualTo(1);
        verify(con).prepareStatement("/* audit */ update t set x = 1 where id = ?");

        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
        java.sql.ResultSetMetaData md = mock(java.sql.ResultSetMetaData.class);
        when(ps.execute()).thenReturn(true);
        when(ps.getResultSet()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getMetaData()).thenReturn(md);
        when(md.getColumnCount()).thenReturn(1);
        when(rs.getString(1)).thenReturn("Иванов");
        assertThat(q.name(1)).contains("Иванов");
    }
    /**
     * Проверяет, что {@code sqlOf} для перегруженных методов требует типы параметров, а не
     * отдаёт блок случайного из них.
     */
    @Test
    void sqlOfNeedsTypesForOverloads() {
        Overloaded o = factory().create(Overloaded.class);
        assertThatThrownBy(() -> PlsqlApiInvocationHandler.sqlOf(o, "next"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2 methods are named next");
        assertThat(PlsqlApiInvocationHandler.sqlOf(o, "next", long.class)).contains("APP.PKG.NEXT");
    }

    /**
     * Проверяет, что внутри транзакции Spring после ORA-04068 вызов не повторяется: соединение то
     * же, а контекст, который предыдущие вызовы положили в переменные пакетов, уже стёрт, и повтор
     * молча работал бы без него. Ошибка уходит наружу, транзакция откатывается.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void discardedStateInsideATransactionIsNotRetried() throws SQLException {
        when(con.getAutoCommit()).thenReturn(false);
        when(cs.execute()).thenThrow(new SQLException("ORA-04068", "72000", 4068));
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> api.touch(1))).hasMessageContaining("ORA-04068");

        verify(cs, times(1)).execute();
        verify(con).rollback();
    }

    /**
     * Проверяет, что {@code UPDATE} в методе, который ждёт список строк, — понятная ошибка с именем
     * метода, а не странное приведение числа к списку.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void updateCountCannotBecomeRows() throws SQLException {
        java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
        when(con.prepareStatement(anyString())).thenReturn(ps);
        when(ps.execute()).thenReturn(false);
        when(ps.getUpdateCount()).thenReturn(3);
        Misused m = factory().create(Misused.class);

        assertThatThrownBy(m::update).isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("Misused.update").hasMessageContaining("update count");
    }

    /**
     * Проверяет, что ошибка SQL в {@code @SqlQuery} называет метод, а не внутреннюю метку
     * {@code JdbcTemplate}.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void queryErrorsNameTheMethod() throws SQLException {
        java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
        when(con.prepareStatement(anyString())).thenReturn(ps);
        when(ps.execute()).thenThrow(new SQLException("ORA-00942: table or view does not exist", "42000", 942));
        Queries q = factory().create(Queries.class);

        assertThatThrownBy(() -> q.touch(1)).hasMessageContaining("Queries.touch");
    }

    /** Проверяет, что метод с {@code @SqlQuery} и {@code @Procedure} сразу отвергается при создании. */
    @Test
    void queryAndProcedureOnOneMethodIsRejected() {
        assertThatThrownBy(() -> factory().create(Both.class))
                .hasMessageContaining("Both.both: has both @SqlQuery and @Procedure");
    }

    /**
     * Проверяет перевод имени метода в имя процедуры: слова через подчёркивание, аббревиатура
     * отделяется от следующего слова ({@code loadXMLData} → {@code LOAD_XML_DATA}), в конце
     * остаётся целой, цифра не разрывает слово.
     */
    @Test
    void methodNamesBecomeOracleNames() {
        assertThat(PlsqlApiInvocationHandler.oracleName("setTenant")).isEqualTo("SET_TENANT");
        assertThat(PlsqlApiInvocationHandler.oracleName("loadXMLData")).isEqualTo("LOAD_XML_DATA");
        assertThat(PlsqlApiInvocationHandler.oracleName("getHTTPStatus")).isEqualTo("GET_HTTP_STATUS");
        assertThat(PlsqlApiInvocationHandler.oracleName("getURL")).isEqualTo("GET_URL");
        assertThat(PlsqlApiInvocationHandler.oracleName("parseXML2Json")).isEqualTo("PARSE_XML2_JSON");
        assertThat(PlsqlApiInvocationHandler.oracleName("version2Of")).isEqualTo("VERSION2_OF");
        assertThat(PlsqlApiInvocationHandler.oracleName("noop")).isEqualTo("NOOP");
    }

    /**
     * Проверяет, что срок доходит до драйвера: срок метода (7), срок фабрики (30) для метода без
     * своего, никакого вызова {@code setQueryTimeout} для {@code timeout = 0}, срок
     * {@code @SqlQuery} (4). Без срока у фабрики и метода драйверу ничего не передаётся.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void timeoutsReachTheDriver() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        when(con.prepareStatement(anyString())).thenReturn(ps);
        when(ps.getUpdateCount()).thenReturn(1);
        Timed t = builder().queryTimeout(Duration.ofSeconds(30)).build().create(Timed.class);

        t.limited(1);
        verify(cs).setQueryTimeout(7);
        t.touch(1);
        verify(cs).setQueryTimeout(30);
        clearInvocations(cs);
        t.unlimited(1);
        verify(cs, never()).setQueryTimeout(anyInt());
        t.update(1);
        verify(ps).setQueryTimeout(4);

        clearInvocations(cs);
        api.touch(1);
        verify(cs, never()).setQueryTimeout(anyInt());
    }

    /**
     * Проверяет срок транзакции: внутри {@code TransactionTemplate} с таймаутом 5 секунд драйвер
     * получает остаток срока транзакции (4–5 секунд) и для метода без срока, и для метода со сроком
     * 7; метод со сроком меньше остатка сохраняет свой.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void transactionDeadlineLimitsTheCall() throws SQLException {
        when(con.getAutoCommit()).thenReturn(false);
        Timed t = builder().build().create(Timed.class);
        Timed shortOne = builder().queryTimeout(Duration.ofSeconds(2)).build().create(Timed.class);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.setTimeout(5);
        List<Integer> seen = new ArrayList<>();
        doAnswer(inv -> seen.add(inv.getArgument(0))).when(cs).setQueryTimeout(anyInt());

        tx.executeWithoutResult(s -> {
            t.touch(1);
            t.limited(1);
            shortOne.touch(1);
        });

        assertThat(seen).hasSize(3);
        assertThat(seen.get(0)).isBetween(4, 5);
        assertThat(seen.get(1)).isBetween(4, 5);
        assertThat(seen.get(2)).isEqualTo(2);
    }

    /**
     * Проверяет, что срок меньше {@code -1} в аннотации останавливает создание реализации с
     * понятным сообщением, а отрицательный срок фабрики отвергает построитель.
     */
    @Test
    void negativeTimeoutsAreRejected() {
        assertThatThrownBy(() -> factory().create(BadTimeout.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BadTimeout.touch: timeout = -5");
        assertThatThrownBy(() -> builder().queryTimeout(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Проверяет, что прерывание по сроку (ORA-01013, драйвер бросает {@code SQLTimeoutException})
     * становится {@code QueryTimeoutException} Spring.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void cancelledCallBecomesQueryTimeout() throws SQLException {
        when(cs.execute()).thenThrow(new SQLTimeoutException(
                "ORA-01013: user requested cancel of current operation", "72000", 1013));
        assertThatThrownBy(() -> api.touch(1)).isInstanceOf(QueryTimeoutException.class);
    }

    /**
     * Проверяет {@code DBMS_OUTPUT}: при DEBUG у журнала {@code DbmsOutput} буфер включается до
     * вызова и читается после него, и после неудачного вызова тоже; при INFO лишних обращений к
     * базе нет.
     *
     * @throws SQLException формально: так объявлены методы JDBC, которые настраиваются на моках
     */
    @Test
    void dbmsOutputIsReadOnlyWhenItsLogIsOn() throws SQLException {
        Logger output = (Logger) LoggerFactory.getLogger(DbmsOutput.class);
        try {
            output.setLevel(Level.DEBUG);
            api.touch(1);
            verify(con).prepareCall(contains("DBMS_OUTPUT.ENABLE"));
            verify(con).prepareCall(contains("DBMS_OUTPUT.GET_LINES"));

            // Включение буфера, сам вызов (падает), чтение.
            when(cs.execute()).thenReturn(false)
                    .thenThrow(new SQLException("ORA-20001: Нельзя", "72000", 20001))
                    .thenReturn(false);
            assertThatThrownBy(() -> api.touch(1)).isInstanceOf(PlsqlBusinessException.class);
            verify(con, times(2)).prepareCall(contains("DBMS_OUTPUT.GET_LINES"));

            output.setLevel(Level.INFO);
            reset(cs);
            clearInvocations(con);
            api.touch(1);
            verify(con, never()).prepareCall(contains("DBMS_OUTPUT"));
        } finally {
            output.setLevel(Level.INFO);
        }
    }
}
