package dev.plsql.spring.boot;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import dev.plsql.spring.PlsqlApiFactory;
import dev.plsql.spring.support.CharsetGuard;

/**
 * Хранит настройки библиотеки с префиксом {@code plsql.*} из {@code application.properties}
 * или {@code application.yml}.
 *
 * <p>Класс помечен {@code @ConfigurationProperties}: Spring Boot сам заполняет его поля из
 * свойств с этим префиксом, переводя имена из вида {@code index-table-max-length} в
 * {@code indexTableMaxLength}. Используется только автоконфигурацией
 * ({@link PlsqlAutoConfiguration}) при создании фабрики.
 */
@ConfigurationProperties("plsql")
public class PlsqlProperties {

    /** Что делать с текстом, который кодировка базы не может сохранить. */
    private CharsetGuard.Policy charsetPolicy = CharsetGuard.Policy.FAIL;

    /** NLS_CHARACTERSET (кодировка базы); если не задан, читается из базы. */
    private String databaseCharset;

    /** Сколько элементов резервируется под OUT-аргументы типа index-by таблица. */
    private int indexTableMaxLength = 10_000;

    /** Повторять вызов один раз после ORA-04068 (пакет перекомпилировали под живой сессией). */
    private boolean retryDiscardedState = true;

    /** Сколько может длиться вызов или запрос, если у метода нет своего срока; пусто — без ограничения. */
    private Duration queryTimeout;

    /** Сколько строк курсора или запроса забирать за одно обращение к базе; 0 — как у драйвера (10). */
    private int fetchSize = 100;

    /**
     * Возвращает политику для текста, который кодировка базы не может сохранить
     * (свойство {@code plsql.charset-policy}).
     *
     * <p>В однобайтовой базе (например, CL8MSWIN1251) символ вне кодовой страницы молча
     * заменяется на {@code ?}. При {@code FAIL} (по умолчанию) такой текст не отправляется, а
     * вызов завершается ошибкой с именем аргумента; при {@code IGNORE} проверка отключена.
     *
     * @return политика проверки кодировки
     */
    public CharsetGuard.Policy getCharsetPolicy() {
        return charsetPolicy;
    }

    /**
     * Задаёт политику для текста, который кодировка базы не может сохранить.
     *
     * @param charsetPolicy {@code FAIL} или {@code IGNORE}
     */
    public void setCharsetPolicy(CharsetGuard.Policy charsetPolicy) {
        this.charsetPolicy = charsetPolicy;
    }

    /**
     * Возвращает кодировку базы в терминах Oracle, NLS_CHARACTERSET (свойство
     * {@code plsql.database-charset}), например {@code CL8MSWIN1251}.
     *
     * <p>Если значение не задано ({@code null}) и политика {@code FAIL}, фабрика при старте
     * читает кодировку из базы. Задать её явно имеет смысл, чтобы обойтись без этого запроса.
     *
     * @return имя кодировки Oracle или {@code null}
     */
    public String getDatabaseCharset() {
        return databaseCharset;
    }

    /**
     * Задаёт кодировку базы в терминах Oracle (NLS_CHARACTERSET).
     *
     * @param databaseCharset имя кодировки Oracle, например {@code CL8MSWIN1251}
     */
    public void setDatabaseCharset(String databaseCharset) {
        this.databaseCharset = databaseCharset;
    }

    /**
     * Возвращает, сколько элементов резервируется под OUT-аргумент типа index-by таблица
     * (свойство {@code plsql.index-table-max-length}).
     *
     * <p>Index-by таблица — ассоциативный массив PL/SQL ({@code TABLE OF ... INDEX BY}). Драйвер
     * Oracle требует заранее указать, сколько элементов может вернуться в такой OUT-аргумент;
     * по умолчанию 10 000. Значение меньше 1 фабрика отвергает при старте.
     *
     * @return максимальное число элементов
     */
    public int getIndexTableMaxLength() {
        return indexTableMaxLength;
    }

    /**
     * Задаёт, сколько элементов резервируется под OUT-аргумент типа index-by таблица.
     *
     * @param indexTableMaxLength максимальное число элементов, не меньше 1
     */
    public void setIndexTableMaxLength(int indexTableMaxLength) {
        this.indexTableMaxLength = indexTableMaxLength;
    }

    /**
     * Возвращает, повторять ли вызов один раз после ORA-04068 (свойство
     * {@code plsql.retry-discarded-state}).
     *
     * <p>ORA-04068 возникает, когда пакет перекомпилировали под живой сессией: Oracle сбрасывает
     * состояние пакета (его переменные) в этой сессии, а изменения данных неудачного вызова
     * откатывает сам, поэтому повторить его безопасно; повтор работает уже с новой версией пакета.
     * По умолчанию включено.
     *
     * @return {@code true}, если вызов повторяется
     */
    public boolean isRetryDiscardedState() {
        return retryDiscardedState;
    }

    /**
     * Задаёт, повторять ли вызов один раз после ORA-04068.
     *
     * @param retryDiscardedState {@code true}, чтобы повторять вызов
     */
    public void setRetryDiscardedState(boolean retryDiscardedState) {
        this.retryDiscardedState = retryDiscardedState;
    }

    /**
     * Возвращает, сколько может длиться вызов процедуры или запрос {@code @SqlQuery}, если у метода
     * нет своего срока (свойство {@code plsql.query-timeout}, например {@code 30s} или {@code 2m}).
     *
     * <p>Когда срок выходит, драйвер прерывает вызов, и метод завершается
     * {@code QueryTimeoutException} (ORA-01013). По умолчанию срока нет. Внутри
     * {@code @Transactional(timeout = ...)} действует меньшее из двух: этот срок или время, которое
     * осталось у транзакции.
     *
     * @return срок или {@code null}, если его нет
     */
    public Duration getQueryTimeout() {
        return queryTimeout;
    }

    /**
     * Задаёт, сколько может длиться вызов процедуры или запрос, если у метода нет своего срока.
     *
     * @param queryTimeout срок; {@code null} или ноль — без ограничения
     */
    public void setQueryTimeout(Duration queryTimeout) {
        this.queryTimeout = queryTimeout;
    }

    /**
     * Возвращает, сколько строк курсора или запроса {@code @SqlQuery} забирать за одно обращение к
     * базе (свойство {@code plsql.fetch-size}); по умолчанию 100.
     *
     * <p>Драйвер Oracle сам берёт по 10 строк. Замер на 11.2.0.4: 200 000 строк по 10 читаются
     * 9,9 с, по 100 — 1,1 с, по 500 — 0,3 с.
     *
     * @return строк за обращение; {@code 0} — как у драйвера
     */
    public int getFetchSize() {
        return fetchSize;
    }

    /**
     * Задаёт, сколько строк курсора или запроса забирать за одно обращение к базе.
     *
     * @param fetchSize строк за обращение; {@code 0} — как у драйвера
     */
    public void setFetchSize(int fetchSize) {
        this.fetchSize = fetchSize;
    }

    /**
     * Переносит настройки в построитель фабрики. Так настройки попадают и в фабрику
     * автоконфигурации, и в фабрику, которую {@code PlsqlApiFactoryBean} строит сам для
     * {@code dataSourceRef}.
     *
     * @param b построитель фабрики
     * @return тот же построитель
     * @throws IllegalArgumentException если {@code plsql.index-table-max-length} меньше 1,
     *                                  {@code plsql.query-timeout} или {@code plsql.fetch-size}
     *                                  отрицательные
     */
    public PlsqlApiFactory.Builder applyTo(PlsqlApiFactory.Builder b) {
        return b.charsetPolicy(charsetPolicy)
                .databaseCharset(databaseCharset)
                .indexTableMaxLength(indexTableMaxLength)
                .retryDiscardedState(retryDiscardedState)
                .queryTimeout(queryTimeout)
                .fetchSize(fetchSize);
    }
}
