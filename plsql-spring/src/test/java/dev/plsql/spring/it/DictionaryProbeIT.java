package dev.plsql.spring.it;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

/**
 * Временная разведка (только в ветке compat): выгружает строки словаря для типов PL/SQL
 * тестовой схемы в {@code target/probe.txt}, чтобы сравнить словарь 11g и 18c+.
 */
class DictionaryProbeIT {

    /**
     * Выгружает описания типов PL/SQL, процедуры без аргументов и {@code BOOLEAN} в словаре.
     *
     * @throws Exception если не удалось записать файл
     */
    @Test
    void dump() throws Exception {
        StringBuilder out = new StringBuilder();
        try (Connection c = DriverManager.getConnection(ItDatabase.url(), ItDatabase.USER, ItDatabase.PASSWORD)) {
            query(c, out, "select version from product_component_version where product like 'Oracle%' and rownum = 1");
            query(c, out, "select object_name n, data_type t, pls_type p from user_arguments where package_name = 'LAB_PKG'"
                    + " and object_name in ('ECHO_BOOL', 'NOOP')");
            query(c, out, "select procedure_name n, subprogram_id s, overload o from user_procedures"
                    + " where object_name = 'LAB_PKG' and procedure_name in ('NOOP', 'OVER', 'ECHO_BOOL')");
            query(c, out, "select package_name p, type_name t, typecode c from user_plsql_types"
                    + " where package_name in ('LAB_PKG', 'LAB_TAB', 'LAB_TAB2', 'LAB_LOB', 'LAB_NCHAR', 'LAB_BAG')"
                    + " order by 1, 2");
            query(c, out, "select package_name p, type_name t, attr_no n, attr_name a, attr_type_mod m, attr_type_owner o,"
                    + " attr_type_name tn, attr_type_package tp, length l, precision pr, scale sc, character_set_name cs,"
                    + " char_used cu from user_plsql_type_attrs"
                    + " where package_name in ('LAB_PKG', 'LAB_TAB', 'LAB_TAB2', 'LAB_LOB', 'LAB_NCHAR', 'LAB_BAG')"
                    + " order by 1, 2, 3");
            query(c, out, "select * from user_plsql_coll_types"
                    + " where package_name in ('LAB_PKG', 'LAB_TAB', 'LAB_TAB2', 'LAB_LOB', 'LAB_NCHAR', 'LAB_BAG')"
                    + " order by package_name, type_name");
        }
        Files.writeString(Path.of("target", "probe.txt"), out);
    }

    /**
     * Выполняет запрос и дописывает строки результата: только непустые столбцы, {@code имя=значение}.
     *
     * @param c   соединение
     * @param out куда писать
     * @param sql запрос
     */
    private static void query(Connection c, StringBuilder out, String sql) {
        out.append("## ").append(sql, 7, Math.min(sql.length(), 40)).append('\n');
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    String v = rs.getString(i);
                    String name = md.getColumnName(i);
                    if (v != null && !name.equals("OWNER") && !name.equals("TYPE_OID") && !name.equals("PACKAGE_NAME")) {
                        row.append(name.toLowerCase()).append('=').append(v).append(' ');
                    }
                }
                out.append(row.toString().trim()).append('\n');
            }
        } catch (SQLException e) {
            out.append("ERR ").append(e.getMessage().split("\n")[0]).append('\n');
        }
    }
}
