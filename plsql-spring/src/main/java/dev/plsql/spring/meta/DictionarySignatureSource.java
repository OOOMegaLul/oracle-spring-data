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
 * Reads signatures from ALL_ARGUMENTS and friends through {@link DictionaryReader}.
 *
 * <p>{@link #findAll} reads a whole package with one ALL_ARGUMENTS query and the
 * standalone subprograms of an interface with one query per 900 names, falling back to
 * DBMS_UTILITY.NAME_RESOLVE per name only for what is not found directly (synonyms, other
 * schemas). One by one, a subprogram costs 2 to 5 round trips, which adds up at startup
 * for an interface of hundreds of methods.
 */
public class DictionarySignatureSource implements SignatureSource {

    private static final Logger log = LoggerFactory.getLogger(DictionarySignatureSource.class);

    /** ORA-06564: object does not exist (from DBMS_UTILITY.NAME_RESOLVE). */
    private static final int NOT_FOUND = 6564;

    private final DataSource dataSource;
    private final DictionaryReader reader = new DictionaryReader();

    public DictionarySignatureSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<SubprogramInfo> find(String schema, String packageName, String name) {
        return findAll(schema, packageName, List.of(name)).get(name);
    }

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
        if ("INVALID".equals(reader.packageBodyStatus(con, p.owner(), p.packageName()))) {
            log.warn("{}.{}: the package body is INVALID; calls will fail with ORA-04063 until it compiles",
                    p.owner(), p.packageName());
        }
        for (String n : names) {
            out.put(n, all.getOrDefault(n.toUpperCase(Locale.ROOT), List.of()));
        }
        return out;
    }

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

    /** Synonyms, procedures without arguments, invalid code: the careful path. */
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

    private static String currentSchema(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("select sys_context('USERENV', 'CURRENT_SCHEMA') from dual");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }
}
