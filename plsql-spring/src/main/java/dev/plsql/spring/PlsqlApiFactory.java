package dev.plsql.spring;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

import javax.sql.DataSource;

import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.datasource.DataSourceUtils;

import dev.plsql.spring.annotation.PlsqlApi;
import dev.plsql.spring.call.ArgumentDefaults;
import dev.plsql.spring.call.CallExecutor;
import dev.plsql.spring.call.CallPlanner;
import dev.plsql.spring.meta.DictionarySignatureSource;
import dev.plsql.spring.meta.SignatureSource;
import dev.plsql.spring.support.CharsetGuard;
import dev.plsql.spring.support.PlsqlApiInvocationHandler;
import dev.plsql.spring.support.PlsqlExceptionTranslator;
import dev.plsql.spring.support.PlsqlRuntime;

/**
 * Создаёт реализации интерфейсов с аннотацией {@link PlsqlApi}. Пользователи Spring Boot
 * получают готовую фабрику из автоконфигурации; без Spring фабрика собирается так:
 *
 * <pre>{@code
 * PlsqlApiFactory factory = PlsqlApiFactory.builder(dataSource)
 *         .argumentDefaults(ArgumentDefaults.byName(Map.of("NTENANT", ctx::tenant)))
 *         .build();
 * AppCore core = factory.create(AppCore.class);
 * }</pre>
 *
 * <p>{@link #create} читает все сигнатуры и проверяет все методы до того, как вернуть
 * результат, поэтому расхождение между интерфейсом и базой обнаруживается здесь, со списком
 * всех причин.
 *
 * <p>Фабрика хранит один {@link PlsqlRuntime} — общий для всех создаваемых реализаций набор
 * служебных объектов: источник соединений, чтение сигнатур, планировщик, исполнитель вызовов,
 * перевод ошибок и проверку кодировки.
 */
public final class PlsqlApiFactory {

    /** Общее окружение времени выполнения для всех реализаций этой фабрики. */
    private final PlsqlRuntime runtime;

    /**
     * Создаёт фабрику вокруг готового окружения. Снаружи фабрику получают через {@link #builder}.
     *
     * @param runtime общее окружение для всех создаваемых реализаций
     */
    private PlsqlApiFactory(PlsqlRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Возвращает построитель фабрики для указанного источника соединений.
     *
     * <p>{@link DataSource} — стандартный интерфейс JDBC, из которого берутся соединения с базой;
     * обычно это пул соединений (например, HikariCP), который держит открытые соединения и выдаёт
     * их по очереди. Это может быть и обёртка над пулом, например
     * {@link dev.plsql.spring.session.SessionContextDataSource}, которая готовит сессию при каждой
     * выдаче соединения.
     *
     * @param dataSource источник соединений с базой Oracle; не {@code null}
     * @return новый построитель с настройками по умолчанию
     * @throws NullPointerException если {@code dataSource} равен {@code null}
     */
    public static Builder builder(DataSource dataSource) {
        return new Builder(dataSource);
    }

    /**
     * Создаёт реализацию интерфейса {@code api}: прокси (объект, который JDK создаёт во время
     * работы программы), методы которого вызывают процедуры PL/SQL или выполняют {@code @SqlQuery}.
     *
     * <p>Все методы сверяются со словарём Oracle сразу, до возврата. Каждый вызов создаёт новый
     * прокси и заново запрашивает сигнатуры у источника, поэтому обычно реализацию создают один
     * раз при старте и хранят как бин Spring. Без аннотации {@link PlsqlApi} (или без
     * {@code packageName} в ней) методы ищутся среди автономных процедур и функций.
     *
     * @param api интерфейс, обычно с аннотацией {@link PlsqlApi}
     * @param <T> тип интерфейса
     * @return готовая реализация интерфейса
     * @throws IllegalArgumentException если {@code api} не интерфейс
     * @throws IllegalStateException    если методы интерфейса не соответствуют базе; в сообщении
     *                                  перечислены все расхождения
     */
    public <T> T create(Class<T> api) {
        return PlsqlApiInvocationHandler.create(api, runtime);
    }

    /**
     * Возвращает окружение времени выполнения, общее для всех реализаций этой фабрики: источник
     * соединений, чтение сигнатур, планировщик, исполнитель, перевод ошибок, проверку кодировки и
     * признак повтора после ORA-04068.
     *
     * @return окружение фабрики
     */
    public PlsqlRuntime runtime() {
        return runtime;
    }

    /**
     * Построитель {@link PlsqlApiFactory}: собирает настройки, а {@link #build()} создаёт фабрику.
     *
     * <p>Значения по умолчанию: контекстных аргументов нет, сигнатуры читаются из словаря данных,
     * ошибки переводит стандартный {@link PlsqlExceptionTranslator}, политика кодировки —
     * {@link CharsetGuard.Policy#FAIL}, кодировка базы читается из базы, ёмкость OUT index-by
     * таблиц — 10 000 элементов, повтор после ORA-04068 включён.
     */
    public static final class Builder {
        private final DataSource dataSource;
        private ArgumentDefaults argumentDefaults = ArgumentDefaults.none();
        private SignatureSource signatureSource;
        private PlsqlExceptionTranslator exceptionTranslator = new PlsqlExceptionTranslator();
        private CharsetGuard.Policy charsetPolicy = CharsetGuard.Policy.FAIL;
        private String databaseCharset;
        private int indexTableMaxLength = 10_000;
        private boolean retryDiscardedState = true;

        /**
         * Создаёт построитель; снаружи вызывается через {@link PlsqlApiFactory#builder}.
         *
         * @param dataSource источник соединений; не {@code null}
         * @throws NullPointerException если {@code dataSource} равен {@code null}
         */
        private Builder(DataSource dataSource) {
            this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        }

        /**
         * Задаёт значения для аргументов процедур, которых нет среди параметров методов Java
         * (NTENANT, NMODE...).
         *
         * <p>Это контекстные аргументы: например, текущая организация (tenant), которую процедура
         * ждёт в каждом вызове, но объявлять которую в каждом методе неудобно. Значение берётся у
         * поставщика при каждом вызове. По умолчанию таких значений нет
         * ({@link ArgumentDefaults#none()}).
         *
         * @param defaults источник значений; не {@code null}
         * @return этот же построитель
         */
        public Builder argumentDefaults(ArgumentDefaults defaults) {
            this.argumentDefaults = Objects.requireNonNull(defaults);
            return this;
        }

        /**
         * Задаёт, откуда брать сигнатуры подпрограмм; по умолчанию — из словаря данных базы, к
         * которой ведёт {@code dataSource}.
         *
         * <p>Сигнатура — список аргументов процедуры или функции: имена, направления
         * (IN, OUT, IN OUT) и типы. Свой источник нужен тестам и офлайн-инструментам, которые
         * работают без базы.
         *
         * @param source источник сигнатур; не {@code null}
         * @return этот же построитель
         */
        public Builder signatureSource(SignatureSource source) {
            this.signatureSource = Objects.requireNonNull(source);
            return this;
        }

        /**
         * Задаёт перевод ошибок Oracle ({@link SQLException}) в исключения Spring.
         *
         * <p>По умолчанию используется {@link PlsqlExceptionTranslator}: ошибки ORA-20000..20999
         * (их выбрасывает {@code RAISE_APPLICATION_ERROR} в PL/SQL) становятся
         * {@code PlsqlBusinessException} с чистым текстом, остальные — стандартной иерархией
         * исключений Spring ({@code DuplicateKeyException} и т. д.). Подкласс позволяет изменить
         * это поведение.
         *
         * @param translator переводчик ошибок; не {@code null}
         * @return этот же построитель
         */
        public Builder exceptionTranslator(PlsqlExceptionTranslator translator) {
            this.exceptionTranslator = Objects.requireNonNull(translator);
            return this;
        }

        /**
         * Задаёт, что делать с текстом, который кодировка базы не может сохранить; по умолчанию
         * {@link CharsetGuard.Policy#FAIL}.
         *
         * <p>Однобайтовая кодировка (например, CL8MSWIN1251) хранит не больше 256 символов — это её
         * кодовая страница. Символ вне кодовой страницы база молча превращает в {@code ?}.
         * {@code FAIL} не отправляет такой текст и бросает исключение, {@code IGNORE} оставляет
         * замену базе, как обычный JDBC.
         *
         * @param policy политика; не {@code null}
         * @return этот же построитель
         */
        public Builder charsetPolicy(CharsetGuard.Policy policy) {
            this.charsetPolicy = Objects.requireNonNull(policy);
            return this;
        }

        /**
         * Задаёт кодировку базы (NLS_CHARACTERSET), например {@code CL8MSWIN1251} или
         * {@code AL32UTF8}.
         *
         * <p>NLS_CHARACTERSET — параметр базы, который определяет, в какой кодировке хранятся
         * {@code VARCHAR2} и {@code CLOB}. Если значение не задано, а политика {@code FAIL},
         * {@link #build()} прочитает его из базы отдельным запросом.
         *
         * @param oracleCharset имя кодировки в терминах Oracle; {@code null} — прочитать из базы
         * @return этот же построитель
         */
        public Builder databaseCharset(String oracleCharset) {
            this.databaseCharset = oracleCharset;
            return this;
        }

        /**
         * Задаёт ёмкость, которая резервируется под OUT index-by таблицы.
         *
         * <p>Index-by таблица (ассоциативный массив, {@code TABLE OF ... INDEX BY}) — коллекция
         * PL/SQL. Для OUT-параметра такого типа драйвер Oracle требует заранее указать наибольшее
         * число элементов: больше этого процедура вернуть не сможет. По умолчанию 10 000.
         *
         * @param length наибольшее число элементов; не меньше 1
         * @return этот же построитель
         * @throws IllegalArgumentException если {@code length} меньше 1
         */
        public Builder indexTableMaxLength(int length) {
            if (length < 1) {
                throw new IllegalArgumentException("indexTableMaxLength must be positive");
            }
            this.indexTableMaxLength = length;
            return this;
        }

        /**
         * Включает или выключает повтор вызова после ORA-04068; по умолчанию повтор включён.
         *
         * <p>ORA-04068 возникает, когда пакет перекомпилировали, пока сессия держала его состояние
         * (значения переменных пакета). Oracle сбрасывает это состояние, а неудачный вызов не
         * выполняется, поэтому повторить его один раз безопасно.
         *
         * @param retry {@code true} — повторять один раз
         * @return этот же построитель
         */
        public Builder retryDiscardedState(boolean retry) {
            this.retryDiscardedState = retry;
            return this;
        }

        /**
         * Создаёт фабрику с заданными настройками.
         *
         * <p>Если источник сигнатур не задан, используется {@link DictionarySignatureSource} на том же
         * {@code dataSource}. При политике {@code IGNORE} проверка кодировки выключена. Иначе
         * кодировка берётся из {@link #databaseCharset(String)}, а если она не задана — читается из базы,
         * то есть сборка уже обращается к базе. Для Unicode-кодировок и кодировок, которых
         * {@link CharsetGuard} не знает, проверка тоже выключается.
         *
         * @return готовая фабрика
         * @throws UncategorizedSQLException если запрос кодировки базы завершился ошибкой
         */
        public PlsqlApiFactory build() {
            SignatureSource signatures = signatureSource != null ? signatureSource : new DictionarySignatureSource(dataSource);
            CharsetGuard guard = charsetPolicy == CharsetGuard.Policy.IGNORE
                    ? CharsetGuard.none()
                    : CharsetGuard.forDatabase(databaseCharset != null ? databaseCharset : readCharset(), charsetPolicy);
            return new PlsqlApiFactory(new PlsqlRuntime(dataSource, signatures, new CallPlanner(argumentDefaults),
                    new CallExecutor(indexTableMaxLength, guard), exceptionTranslator, guard, retryDiscardedState));
        }

        /**
         * Читает кодировку базы (NLS_CHARACTERSET) из представления {@code nls_database_parameters}.
         *
         * <p>Соединение берётся через {@link DataSourceUtils}: если сборка идёт внутри транзакции
         * Spring, используется её соединение, иначе соединение берётся из пула и сразу
         * возвращается.
         *
         * @return имя кодировки в терминах Oracle или {@code null}, если параметр не найден
         * @throws UncategorizedSQLException если запрос завершился ошибкой
         */
        private String readCharset() {
            Connection con = DataSourceUtils.getConnection(dataSource);
            try (PreparedStatement ps = con.prepareStatement(
                    "select value from nls_database_parameters where parameter = 'NLS_CHARACTERSET'");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            } catch (SQLException e) {
                throw new UncategorizedSQLException("read NLS_CHARACTERSET", null, e);
            } finally {
                DataSourceUtils.releaseConnection(con, dataSource);
            }
        }
    }
}
