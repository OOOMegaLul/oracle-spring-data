package dev.plsql.spring.it;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * База данных, на которой идут интеграционные тесты, со свежей схемой {@code PLSQL_IT}.
 *
 * <p>Откуда берётся база:
 * <ul>
 *   <li>по умолчанию — одноразовый контейнер {@code gvenzl/oracle-xe:11-slim} (Testcontainers, нужен
 *       Docker); {@code -Dplsql.it.image=...} выбирает другой образ, {@code -Dplsql.it.port=...} —
 *       фиксированный порт на хосте;</li>
 *   <li>{@code -Dplsql.it.url=... -Dplsql.it.dba.password=...} (и при необходимости
 *       {@code -Dplsql.it.dba.user}, по умолчанию {@code system}) — уже существующая база Oracle.</li>
 * </ul>
 *
 * <p>Схема удаляется и создаётся заново один раз на JVM, поэтому прогон никогда не зависит от
 * предыдущего. Сначала под DBA выполняется {@code it/schema-dba.sql} (пользователь {@code PLSQL_IT}
 * и его права), затем под самим {@code PLSQL_IT} — {@code it/schema-objects.sql} (таблица
 * {@code LAB_EMP}, SQL-типы и пакет {@code LAB_PKG}). Все методы статические; база поднимается
 * лениво, при первом обращении к {@link #url()}.
 */
public final class ItDatabase {

    /** Имя тестовой схемы (пользователя Oracle), в которой создаются все объекты тестов. */
    public static final String USER = "PLSQL_IT";
    /** Пароль пользователя {@link #USER}; тот же, что задан в {@code it/schema-dba.sql}. */
    public static final String PASSWORD = "plsql_it";

    static {
        // При входе ojdbc передаёт серверу часовой пояс JVM по имени региона. Oracle 11.2
        // не знает "Etc/UTC" (пояс по умолчанию на CI-раннерах и в большинстве контейнеров)
        // и отказывает во входе с ORA-01882; смещение от UTC работает с любым файлом часовых
        // поясов. Свойство выставляется, только если его не задали явно (например, через -D).
        if (System.getProperty("oracle.jdbc.timezoneAsRegion") == null) {
            System.setProperty("oracle.jdbc.timezoneAsRegion", "false");
        }
    }

    /** Журнал: сообщает, на какой базе идут тесты. */
    private static final Logger log = LoggerFactory.getLogger(ItDatabase.class);
    /** JDBC URL готовой базы; {@code null}, пока схема не подготовлена. */
    private static String url;
    /** {@code NLS_CHARACTERSET} тестовой базы, прочитанный при подготовке схемы. */
    private static String charset;
    /**
     * Ошибка первого запуска. Неудачный старт не повторяется для каждого тестового класса
     * (каждая попытка могла бы запускать новый контейнер).
     */
    private static RuntimeException startFailure;

    /** Класс только со статическими методами: экземпляры не создаются. */
    private ItDatabase() {
    }

    /**
     * Возвращает JDBC URL тестовой базы, при первом обращении поднимая базу и создавая схему.
     *
     * <p>Первый вызов выполняет {@link #start()}: запускает контейнер (или берёт внешнюю базу) и
     * пересоздаёт схему {@code PLSQL_IT}; следующие вызовы сразу возвращают готовый URL. Если
     * первый запуск упал, ошибка запоминается, и все последующие вызовы (в том числе из других
     * тестовых классов) сразу бросают исключение с исходной причиной, не пытаясь стартовать
     * заново. Метод синхронизирован, так что база поднимается ровно один раз.
     *
     * @return JDBC URL вида {@code jdbc:oracle:thin:@//host:port/service}
     * @throws IllegalStateException если база не поднялась при одном из предыдущих вызовов; при
     *                               первой неудаче пробрасывается исходное исключение
     */
    public static synchronized String url() {
        if (startFailure != null) {
            throw new IllegalStateException("test database failed to start earlier", startFailure);
        }
        if (url == null) {
            try {
                start();
            } catch (RuntimeException e) {
                startFailure = e;
                throw e;
            }
        }
        return url;
    }

    /**
     * Возвращает {@code NLS_CHARACTERSET} тестовой базы — кодировку, в которой она хранит
     * {@code VARCHAR2} и {@code CLOB} (например {@code AL32UTF8} или {@code CL8MSWIN1251}).
     * При необходимости сначала поднимает базу через {@link #url()}.
     *
     * @return имя кодировки базы в терминах Oracle
     */
    public static synchronized String charset() {
        url();
        return charset;
    }

    /**
     * Проверяет, что тестовая база в однобайтовой кириллической кодировке {@code CL8MSWIN1251}.
     * В такой базе символ вне кодовой страницы Windows-1251 (например казахская {@code Ә})
     * молча заменяется на {@code ?}, поэтому тесты кодировки ожидают там другое поведение, чем в
     * базе с Unicode.
     *
     * @return {@code true}, если {@code NLS_CHARACTERSET} равен {@code CL8MSWIN1251}
     */
    public static boolean singleByteCyrillic() {
        return "CL8MSWIN1251".equals(charset());
    }

    /**
     * Открывает новое отдельное соединение (без пула) под пользователем {@link #USER}; autoCommit
     * включён, как по умолчанию в JDBC. Закрывать соединение должен вызывающий код, обычно через
     * try-with-resources.
     *
     * @return новое соединение JDBC с тестовой схемой
     * @throws SQLException если соединиться не удалось
     */
    public static Connection connect() throws SQLException {
        return DriverManager.getConnection(url(), USER, PASSWORD);
    }

    /**
     * Создаёт пул соединений HikariCP к тестовой схеме.
     *
     * <p>Пул держит минимум одно простаивающее соединение и включает у драйвера неявный кэш
     * подготовленных операторов на 50 штук ({@code oracle.jdbc.implicitStatementCacheSize}), как
     * это обычно делают в приложениях. Закрыть пул должен вызывающий код.
     *
     * @param size       максимальное число соединений в пуле
     * @param autoCommit режим autoCommit, в котором пул отдаёт соединения
     * @return новый пул; его нужно закрыть после тестов
     */
    public static HikariDataSource pool(int size, boolean autoCommit) {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(url());
        c.setUsername(USER);
        c.setPassword(PASSWORD);
        c.setMaximumPoolSize(size);
        c.setMinimumIdle(1);
        c.setAutoCommit(autoCommit);
        c.addDataSourceProperty("oracle.jdbc.implicitStatementCacheSize", "50");
        return new HikariDataSource(c);
    }

    /**
     * Поднимает базу и готовит схему. Записывает {@link #url} только когда схема готова, поэтому
     * наполовину подготовленная база никогда не используется (локальная переменная {@code url}
     * намеренно скрывает статическое поле до последней строки).
     *
     * <p>Порядок:
     * <ol>
     *   <li>без {@code -Dplsql.it.url} запускается контейнер (образ из {@code -Dplsql.it.image}, по
     *       умолчанию {@code gvenzl/oracle-xe:11-slim}); готовность — строка
     *       {@code DATABASE IS READY TO USE!} в журнале контейнера, ждём её до 10 минут; DBA —
     *       {@code system} с паролем, заданным контейнеру. С {@code -Dplsql.it.url} используется
     *       внешняя база, и пароль DBA обязателен;</li>
     *   <li>под DBA читается {@code NLS_CHARACTERSET}, удаляется старый пользователь {@link #USER}
     *       вместе со всеми его объектами ({@code drop user ... cascade}), если он есть, и
     *       выполняется {@code /it/schema-dba.sql};</li>
     *   <li>под {@link #USER} выполняется {@code /it/schema-objects.sql}.</li>
     * </ol>
     *
     * @throws IllegalStateException если для внешней базы не задан пароль DBA или не удалось
     *                               выполнить скрипты схемы
     */
    @SuppressWarnings("resource")
    private static void start() {
        String url = System.getProperty("plsql.it.url");
        String image = System.getProperty("plsql.it.image", "gvenzl/oracle-xe:11-slim");
        String dbaUser;
        String dbaPassword;
        if (url == null || url.isBlank()) {
            GenericContainer<?> db = new GenericContainer<>(image)
                    .withEnv("ORACLE_PASSWORD", "it_password")
                    .withExposedPorts(1521)
                    .withCreateContainerCmdModifier(cmd -> fixedPort(cmd))
                    .waitingFor(Wait.forLogMessage(".*DATABASE IS READY TO USE!.*\\n", 1)
                            .withStartupTimeout(Duration.ofMinutes(10)));
            db.start();
            // Контейнер останавливает и «чистильщик» Testcontainers (Ryuk); хук нужен на случай
            // TESTCONTAINERS_RYUK_DISABLED=true, когда Ryuk выключен.
            Runtime.getRuntime().addShutdownHook(new Thread(db::stop, "stop-oracle-it"));
            url = "jdbc:oracle:thin:@//" + db.getHost() + ":" + db.getMappedPort(1521) + "/XE";
            // В gvenzl/oracle-xe:11 нет PDB (подключаемых баз, они появились в 12c): сервис — XE.
            dbaUser = "system";
            dbaPassword = "it_password";
        } else {
            dbaUser = System.getProperty("plsql.it.dba.user", "system");
            dbaPassword = System.getProperty("plsql.it.dba.password");
            if (dbaPassword == null) {
                throw new IllegalStateException("-Dplsql.it.url needs -Dplsql.it.dba.password: the tests create their own schema");
            }
        }
        log.info("integration tests on {}", url);
        try (Connection dba = DriverManager.getConnection(url, dbaUser, dbaPassword)) {
            charset = single(dba, "select value from nls_database_parameters where parameter = 'NLS_CHARACTERSET'");
            if (single(dba, "select count(*) from dba_users where username = '" + USER + "'").equals("1")) {
                try (Statement s = dba.createStatement()) {
                    s.execute("drop user " + USER + " cascade");
                }
            }
            run(dba, "/it/schema-dba.sql");
        } catch (SQLException e) {
            throw new IllegalStateException("cannot prepare the test schema on " + url, e);
        }
        try (Connection c = DriverManager.getConnection(url, USER, PASSWORD)) {
            run(c, "/it/schema-objects.sql");
        } catch (SQLException e) {
            throw new IllegalStateException("cannot create test objects on " + url, e);
        }
        ItDatabase.url = url;
    }

    /**
     * {@code -Dplsql.it.port=1541}: публикует порт 1521 контейнера на фиксированном порту хоста. В
     * некоторых установках Docker Desktop под Windows случайно выбранные порты отказывают в
     * соединении ("Cannot assign requested address"), а фиксированные работают. Без свойства метод
     * ничего не меняет, и Testcontainers выбирает случайный порт.
     *
     * @param cmd команда создания контейнера Docker, в которую добавляется привязка порта
     */
    private static void fixedPort(com.github.dockerjava.api.command.CreateContainerCmd cmd) {
        String port = System.getProperty("plsql.it.port");
        if (port != null && !port.isBlank()) {
            cmd.getHostConfig().withPortBindings(new com.github.dockerjava.api.model.PortBinding(
                    com.github.dockerjava.api.model.Ports.Binding.bindPort(Integer.parseInt(port)),
                    new com.github.dockerjava.api.model.ExposedPort(1521)));
        }
    }

    /**
     * Выполняет запрос и возвращает первую колонку первой строки как строку. Запрос должен вернуть
     * хотя бы одну строку.
     *
     * @param c   соединение
     * @param sql текст запроса
     * @return значение первой колонки первой строки
     * @throws SQLException при ошибке запроса
     */
    private static String single(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    /**
     * Выполняет скрипт из ресурсов, в котором операторы разделены строками из одного символа
     * {@code /} (как в SQL*Plus). Операторы выполняются по одному; после каждого
     * {@code CREATE OR REPLACE PACKAGE} или {@code CREATE OR REPLACE TYPE} проверяются ошибки
     * компиляции ({@link #checkCompiled}). При ошибке к сообщению добавляется первая строка
     * упавшего оператора.
     *
     * @param c        соединение, под которым выполняется скрипт
     * @param resource путь к ресурсу в classpath, например {@code /it/schema-objects.sql}
     * @throws SQLException если оператор упал или код не скомпилировался
     */
    static void run(Connection c, String resource) throws SQLException {
        for (String stmt : statements(resource)) {
            try (Statement s = c.createStatement()) {
                s.execute(stmt);
            } catch (SQLException e) {
                throw new SQLException(e.getMessage() + " in: " + stmt.lines().findFirst().orElse(""), e);
            }
            checkCompiled(c, stmt);
        }
    }

    /**
     * {@code CREATE PACKAGE} (как и {@code CREATE TYPE}) проходит без исключения даже с ошибками
     * компиляции: Oracle просто сохраняет объект в состоянии INVALID. Этот метод вместо этого
     * громко падает.
     *
     * <p>Проверка срабатывает только для операторов, начинающихся с
     * {@code CREATE OR REPLACE PACKAGE} или {@code CREATE OR REPLACE TYPE}, и читает все строки
     * {@code USER_ERRORS} схемы, а не только ошибки последнего объекта.
     *
     * @param c    соединение со схемой, где создан объект
     * @param stmt только что выполненный оператор
     * @throws SQLException со списком ошибок компиляции, если они есть
     */
    private static void checkCompiled(Connection c, String stmt) throws SQLException {
        String head = stmt.toUpperCase(Locale.ROOT);
        if (!head.startsWith("CREATE OR REPLACE PACKAGE") && !head.startsWith("CREATE OR REPLACE TYPE")) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "select name || ' ' || type || ' ' || line || ': ' || text from user_errors order by name, type, sequence");
             ResultSet rs = ps.executeQuery()) {
            List<String> errors = new ArrayList<>();
            while (rs.next()) {
                errors.add(rs.getString(1));
            }
            if (!errors.isEmpty()) {
                throw new SQLException("compile errors: " + errors);
            }
        }
    }

    /**
     * Читает скрипт из ресурсов (в UTF-8) и режет его на операторы по строкам, где стоит один
     * {@code /}.
     *
     * <p>Строки комментариев {@code --} перед началом оператора отбрасываются, внутри оператора
     * сохраняются. Каждый кусок проходит через {@link #add}: пустые пропускаются, у обычных
     * SQL-операторов снимается завершающая {@code ;}.
     *
     * @param resource путь к ресурсу в classpath
     * @return операторы в порядке следования в скрипте
     * @throws IllegalStateException если ресурс не найден или не читается
     */
    static List<String> statements(String resource) {
        String text;
        try (InputStream in = ItDatabase.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " not found");
            }
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : text.split("\\R")) {
            if (line.trim().equals("/")) {
                add(out, cur.toString());
                cur.setLength(0);
            } else if (!line.stripLeading().startsWith("--") || cur.length() > 0) {
                cur.append(line).append('\n');
            }
        }
        add(out, cur.toString());
        return out;
    }

    /**
     * Добавляет оператор в список, подготовив его к выполнению через JDBC.
     *
     * <p>Пробелы по краям обрезаются, пустой оператор пропускается. Завершающая {@code ;}
     * снимается у обычного SQL ({@code CREATE TABLE}, {@code INSERT}, {@code COMMIT}...): через
     * JDBC оператор SQL передаётся без неё. У блоков PL/SQL ({@code CREATE OR REPLACE PACKAGE},
     * {@code PROCEDURE}, {@code FUNCTION}, анонимных {@code BEGIN}/{@code DECLARE}) точка с
     * запятой — часть синтаксиса и остаётся.
     *
     * @param out  список, куда добавляется оператор
     * @param stmt текст оператора как он есть в скрипте
     */
    private static void add(List<String> out, String stmt) {
        String s = stmt.strip();
        if (s.isEmpty()) {
            return;
        }
        String up = s.toUpperCase(Locale.ROOT);
        boolean plsql = up.startsWith("CREATE OR REPLACE PACKAGE") || up.startsWith("CREATE OR REPLACE PROCEDURE")
                || up.startsWith("CREATE OR REPLACE FUNCTION") || up.startsWith("BEGIN") || up.startsWith("DECLARE");
        if (!plsql && s.endsWith(";")) {
            s = s.substring(0, s.length() - 1);
        }
        out.add(s);
    }
}
