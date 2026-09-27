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
 * The database the integration tests run on, with a fresh PLSQL_IT schema.
 *
 * <ul>
 *   <li>default: a throwaway {@code gvenzl/oracle-xe:11-slim} container (Testcontainers);
 *       {@code -Dplsql.it.image=...} picks another image, {@code -Dplsql.it.port=...} a fixed host port;</li>
 *   <li>{@code -Dplsql.it.url=... -Dplsql.it.dba.password=...} (and optionally
 *       {@code -Dplsql.it.dba.user}, default {@code system}): an existing Oracle.</li>
 * </ul>
 * The schema is dropped and recreated once per JVM, so a run never depends on the last one.
 */
public final class ItDatabase {

    public static final String USER = "PLSQL_IT";
    public static final String PASSWORD = "plsql_it";

    private static final Logger log = LoggerFactory.getLogger(ItDatabase.class);
    private static String url;
    private static String charset;
    /** A failed start is not repeated for every test class (each attempt could start a container). */
    private static RuntimeException startFailure;

    private ItDatabase() {
    }

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

    /** NLS_CHARACTERSET of the test database. */
    public static synchronized String charset() {
        url();
        return charset;
    }

    public static boolean singleByteCyrillic() {
        return "CL8MSWIN1251".equals(charset());
    }

    public static Connection connect() throws SQLException {
        return DriverManager.getConnection(url(), USER, PASSWORD);
    }

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

    /** Sets {@link #url} only when the schema is ready, so a half-prepared database is never used. */
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
            // The Testcontainers reaper stops it too; the hook covers TESTCONTAINERS_RYUK_DISABLED=true.
            Runtime.getRuntime().addShutdownHook(new Thread(db::stop, "stop-oracle-it"));
            url = "jdbc:oracle:thin:@//" + db.getHost() + ":" + db.getMappedPort(1521) + "/XE";
            // gvenzl/oracle-xe:11 has no PDB; the service is XE.
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
     * {@code -Dplsql.it.port=1541}: publish the container on a fixed host port. On some
     * Windows Docker Desktop setups randomly published ports refuse connections
     * ("Cannot assign requested address") while fixed ones work.
     */
    private static void fixedPort(com.github.dockerjava.api.command.CreateContainerCmd cmd) {
        String port = System.getProperty("plsql.it.port");
        if (port != null && !port.isBlank()) {
            cmd.getHostConfig().withPortBindings(new com.github.dockerjava.api.model.PortBinding(
                    com.github.dockerjava.api.model.Ports.Binding.bindPort(Integer.parseInt(port)),
                    new com.github.dockerjava.api.model.ExposedPort(1521)));
        }
    }

    private static String single(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** Runs a script whose statements are separated by lines holding a single "/". */
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

    /** CREATE PACKAGE succeeds even with compile errors; fail loudly instead. */
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
