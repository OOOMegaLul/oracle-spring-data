package dev.plsql.spring.meta;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.datasource.DataSourceUtils;

/**
 * Читает сигнатуры подпрограмм из словаря данных Oracle ({@code ALL_ARGUMENTS} и соседних
 * представлений) через {@link DictionaryReader}.
 *
 * <p>{@link #findAll} читает весь пакет одним запросом к {@code ALL_ARGUMENTS}, а автономные
 * подпрограммы интерфейса (объявленные вне пакета) — одним запросом на каждые 900 имён.
 * Поштучное разрешение имени через {@code DBMS_UTILITY.NAME_RESOLVE} остаётся только для
 * того, что напрямую не нашлось (синонимы, другие схемы). Синоним — это дополнительное имя
 * объекта базы, часто ведущее в другую схему. Если читать по одной, подпрограмма стоит от 2
 * до 5 обращений к базе (round trip, запрос и ответ по сети), и для интерфейса из сотен
 * методов это заметно удлиняет старт.
 */
public class DictionarySignatureSource implements SignatureSource {

    private static final Logger log = LoggerFactory.getLogger(DictionarySignatureSource.class);

    /**
     * Код ошибки ORA-06564 «объект не существует»: так {@code DBMS_UTILITY.NAME_RESOLVE}
     * сообщает, что имя не удалось разрешить.
     */
    private static final int NOT_FOUND = 6564;

    private final DataSource dataSource;
    private final DictionaryReader reader = new DictionaryReader();

    /**
     * Создаёт источник, который читает словарь через соединения из {@code dataSource}.
     *
     * @param dataSource источник соединений с базой, где лежат вызываемые подпрограммы
     */
    public DictionarySignatureSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Возвращает все перегрузки одной процедуры или функции.
     *
     * <p>Вызывает {@link #findAll} со списком из одного имени.
     *
     * @param schema      схема-владелец или {@code null}, чтобы имя разрешалось от имени
     *                    подключённого пользователя (с учётом синонимов)
     * @param packageName пакет или {@code null} для автономной подпрограммы
     * @param name        имя подпрограммы
     * @return перегрузки; пустой список, если такой подпрограммы нет
     * @throws UncategorizedSQLException при ошибке обращения к словарю
     * @throws DictionaryReader.InvalidObjectException если объект существует, но не компилируется
     */
    @Override
    public List<SubprogramInfo> find(String schema, String packageName, String name) {
        return findAll(schema, packageName, List.of(name)).get(name);
    }

    /**
     * Возвращает сигнатуры нескольких подпрограмм одного пакета или нескольких автономных,
     * тратя на всех несколько запросов к словарю.
     *
     * <p>Соединение берётся через {@link DataSourceUtils}: если в текущем потоке идёт
     * транзакция Spring, используется её соединение, иначе берётся новое и после чтения
     * возвращается. Ошибки SQL заворачиваются в {@link UncategorizedSQLException}.
     *
     * @param schema      схема-владелец или {@code null}, чтобы имена разрешались от имени
     *                    подключённого пользователя
     * @param packageName пакет или {@code null} для автономных подпрограмм
     * @param names       имена подпрограмм; регистр не важен
     * @return {@code Map} «имя, как его передали, → перегрузки», по записи на каждое имя;
     *         пустой список означает, что подпрограммы нет
     * @throws UncategorizedSQLException при ошибке обращения к словарю
     * @throws DictionaryReader.InvalidObjectException если пакет или автономная подпрограмма
     *                                                 существует, но не компилируется
     */
    @Override
    public Map<String, List<SubprogramInfo>> findAll(String schema, String packageName, Collection<String> names) {
        Connection con = DataSourceUtils.getConnection(dataSource);
        try {
            return packageName != null
                    ? fromPackage(con, schema, packageName, names)
                    : standalone(con, schema, names);
        } catch (SQLException e) {
            throw new UncategorizedSQLException("read signatures of " + (packageName != null ? packageName : names), null, e);
        } finally {
            DataSourceUtils.releaseConnection(con, dataSource);
        }
    }

    /**
     * Читает подпрограммы одного пакета: разрешает имя пакета (с учётом синонимов), читает
     * пакет целиком одним запросом и раскладывает результат по запрошенным именам.
     *
     * <p>Пакет в Oracle состоит из спецификации (объявления, видимые снаружи) и тела
     * (реализация); они компилируются отдельно. Если пакета нет (ORA-06564), каждому имени
     * достаётся пустой список. Если тело пакета в статусе {@code INVALID} (не компилируется) или
     * тела нет вовсе, сигнатуры всё равно читаются по спецификации, но в лог пишется
     * предупреждение: вызовы будут падать с ORA-04063 или ORA-04067, пока тело не
     * скомпилируется или не будет создано. Об отсутствии тела предупреждение пишется только для
     * пакета своей схемы: тело чужого пакета не видно тому, у кого есть лишь право EXECUTE.
     *
     * @param con    соединение, на котором читается словарь
     * @param schema схема-владелец или {@code null}
     * @param pkg    имя пакета или синонима пакета
     * @param names  имена подпрограмм; ищутся в верхнем регистре
     * @return {@code Map} «имя, как его передали, → перегрузки»
     * @throws SQLException при ошибке обращения к словарю, кроме «объект не существует»
     * @throws DictionaryReader.InvalidObjectException если спецификация пакета не компилируется
     */
    private Map<String, List<SubprogramInfo>> fromPackage(Connection con, String schema, String pkg,
                                                         Collection<String> names) throws SQLException {
        Map<String, List<SubprogramInfo>> out = new LinkedHashMap<>();
        DictionaryReader.Resolved p;
        try {
            p = reader.resolvePackage(con, schema, pkg);
        } catch (SQLException e) {
            if (e.getErrorCode() != NOT_FOUND) {
                throw e;
            }
            names.forEach(n -> out.put(n, List.of()));
            return out;
        }
        Map<String, List<SubprogramInfo>> all = reader.readPackage(con, p.owner(), p.packageName());
        String body = reader.packageBodyStatus(con, p.owner(), p.packageName());
        if ("INVALID".equals(body)) {
            log.warn("{}.{}: the package body is INVALID; calls will fail with ORA-04063 until it compiles",
                    p.owner(), p.packageName());
        } else if (body == null && !all.isEmpty() && java.util.Objects.equals(p.owner(), con.getMetaData().getUserName())) {
            // Чужое тело пакета в ALL_OBJECTS не видно тому, у кого есть только EXECUTE
            // (проверено на 11.2.0.4), поэтому о пропавшем теле можно судить только в своей схеме.
            log.warn("{}.{}: the package has no body; calls will fail with ORA-04067 until it is created",
                    p.owner(), p.packageName());
        }
        for (String n : names) {
            out.put(n, all.getOrDefault(n.toUpperCase(Locale.ROOT), List.of()));
        }
        return out;
    }

    /**
     * Читает автономные подпрограммы: сначала напрямую в схеме-владельце, пачками по 900 имён
     * на запрос, затем по одной — то, что так не нашлось.
     *
     * <p>Схема-владелец — {@code schema} в верхнем регистре или, если она не задана, текущая
     * схема сессии. Имена без кавычек Oracle хранит в словаре в верхнем регистре, поэтому
     * имена тоже приводятся к нему. Не найденные напрямую имена (синонимы, процедуры без
     * аргументов, невалидный код) идут в {@link #oneByOne}.
     *
     * @param con    соединение, на котором читается словарь
     * @param schema схема-владелец или {@code null}
     * @param names  имена подпрограмм
     * @return {@code Map} «имя, как его передали, → перегрузки»
     * @throws SQLException при ошибке обращения к словарю
     * @throws DictionaryReader.InvalidObjectException если подпрограмма существует, но не компилируется
     */
    private Map<String, List<SubprogramInfo>> standalone(Connection con, String schema, Collection<String> names)
            throws SQLException {
        String owner = schema != null ? schema.toUpperCase(Locale.ROOT) : currentSchema(con);
        List<String> upper = names.stream().map(n -> n.toUpperCase(Locale.ROOT)).distinct().toList();
        Map<String, List<SubprogramInfo>> direct = reader.readStandalone(con, owner, upper);
        Map<String, List<SubprogramInfo>> out = new LinkedHashMap<>();
        for (String n : names) {
            List<SubprogramInfo> found = direct.get(n.toUpperCase(Locale.ROOT));
            out.put(n, found != null ? found : oneByOne(con, schema, n));
        }
        return out;
    }

    /**
     * Читает одну автономную подпрограмму аккуратным, но медленным путём: для синонимов,
     * процедур без аргументов и невалидного кода.
     *
     * <p>Имя разрешается через {@code DBMS_UTILITY.NAME_RESOLVE}, как это сделал бы компилятор
     * PL/SQL, а затем читается через {@link DictionaryReader#read}, который отличает
     * отсутствующий объект от некомпилирующегося.
     *
     * @param con    соединение, на котором читается словарь
     * @param schema схема-владелец или {@code null}
     * @param name   имя подпрограммы или синонима
     * @return перегрузки; пустой список, если имя не разрешилось (ORA-06564) или подпрограммы нет
     * @throws SQLException при прочих ошибках обращения к базе
     * @throws DictionaryReader.InvalidObjectException если объект существует, но не компилируется
     */
    private List<SubprogramInfo> oneByOne(Connection con, String schema, String name) throws SQLException {
        try {
            return reader.read(con, reader.resolve(con, schema, null, name));
        } catch (SQLException e) {
            if (e.getErrorCode() == NOT_FOUND) {
                return List.of();
            }
            throw e;
        }
    }

    /**
     * Возвращает текущую схему сессии: {@code SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')}.
     *
     * <p>Именно в ней Oracle ищет объекты, записанные без схемы. Обычно она совпадает с
     * именем пользователя, но её можно сменить командой
     * {@code ALTER SESSION SET CURRENT_SCHEMA}. {@code DUAL} — служебная таблица из одной
     * строки для запросов, которым не нужна настоящая таблица.
     *
     * @param con соединение, чью сессию надо спросить
     * @return имя текущей схемы
     * @throws SQLException при ошибке запроса
     */
    private static String currentSchema(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("select sys_context('USERENV', 'CURRENT_SCHEMA') from dual");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }
}
