package dev.plsql.spring.boot;

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
     * Переносит настройки в построитель фабрики. Так настройки попадают и в фабрику
     * автоконфигурации, и в фабрику, которую {@code PlsqlApiFactoryBean} строит сам для
     * {@code dataSourceRef}.
     *
     * @param b построитель фабрики
     * @return тот же построитель
     * @throws IllegalArgumentException если {@code plsql.index-table-max-length} меньше 1
     */
    public PlsqlApiFactory.Builder applyTo(PlsqlApiFactory.Builder b) {
        return b.charsetPolicy(charsetPolicy)
                .databaseCharset(databaseCharset)
                .indexTableMaxLength(indexTableMaxLength)
                .retryDiscardedState(retryDiscardedState);
    }
}
