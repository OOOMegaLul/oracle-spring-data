package dev.plsql.spring.support;

import static dev.plsql.spring.test.Signatures.func;
import static dev.plsql.spring.test.Signatures.proc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import dev.plsql.spring.PlsqlApiFactory;
import dev.plsql.spring.annotation.PlsqlApi;
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
        @dev.plsql.spring.annotation.SqlQuery("/* audit */ update t set x = 1 where id = :id")
        int touch(long id);

        /**
         * {@code SELECT} одной колонки.
         *
         * @param id значение параметра {@code :id}
         * @return имя или пустой {@code Optional}, если строки нет
         */
        @dev.plsql.spring.annotation.SqlQuery("select name from t where id = :id")
        java.util.Optional<String> name(long id);
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
        @dev.plsql.spring.annotation.SqlQuery("select 1 from dual where x = :missing")
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
        return PlsqlApiFactory.builder(ds)
                .signatureSource(Signatures.source(
                        func("PKG", "NEXT", "NUMBER").in("NTENANT", "NUMBER").build(),
                        proc("PKG", "TOUCH").in("NTENANT", "NUMBER").build()))
                .databaseCharset("AL32UTF8")
                .build();
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
     * вызван дважды. Неудачный вызов не выполнялся, поэтому повторять его безопасно.
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
     * даёт имя подпрограммы в верхнем регистре с подчёркиваниями.
     */
    @Test
    void sqlOfShowsTheBlock() {
        assertThat(PlsqlApiInvocationHandler.sqlOf(api, "touch")).isEqualTo("BEGIN\n  APP.PKG.TOUCH(NTENANT => ?);\nEND;");
        assertThat(PlsqlApiInvocationHandler.sqlOf(api, "twice")).isNull();
        assertThat(PlsqlApiInvocationHandler.subprogramName(
                Api.class.getMethods()[0])).matches("[A-Z_]+");
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
}
