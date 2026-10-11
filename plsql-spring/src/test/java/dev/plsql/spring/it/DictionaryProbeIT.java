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
 * Временная разведка (только в ветке compat): выгружает строки словаря для подпрограмм и типов
 * тестовой схемы в {@code target/probe.txt}, чтобы сравнить словарь 11g и 18c+.
 */
class DictionaryProbeIT {

    /**
     * Выгружает версию базы, {@code ALL_ARGUMENTS} нужных подпрограмм и описания типов PL/SQL.
     *
     * @throws Exception если не удалось записать файл
     */
    @Test
    void dump() throws Exception {
        StringBuilder out = new StringBuilder();
        try (Connection c = DriverManager.getConnection(ItDatabase.url(), ItDatabase.USER, ItDatabase.PASSWORD)) {
            query(c, out, "select version from product_component_version where product like 'Oracle%' and rownum = 1");
            query(c, out, "select package_name, object_name, overload, position, sequence, data_level, argument_name,"
                    + " data_type, pls_type, in_out, type_owner, type_name, type_subname, char_length from user_arguments"
                    + " where (package_name, object_name) in (('LAB_PKG','EMP_ROW'),('LAB_PKG','NOOP'),('LAB_PKG','REC_INOUT'),"
                    + "('LAB_PKG','ECHO_BOOL'),('LAB_PKG','RECS'),('LAB_PKG','EMPS_INOUT'),('LAB_TAB','ORDER_ECHO'),"
                    + "('LAB_TAB2','EMPS'),('LAB_TAB2','NAMED'),('LAB_NCHAR','REC_ECHO'),('LAB_BAG','BAG_ECHO'),"
                    + "('LAB_TAB','STAMPS'))"
                    + " order by package_name, object_name, overload, sequence");
            query(c, out, "select * from user_plsql_types order by package_name, type_name");
            query(c, out, "select * from user_plsql_type_attrs order by package_name, type_name, attr_no");
            query(c, out, "select * from user_plsql_coll_types order by package_name, type_name");
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
        out.append("## ").append(sql, 0, Math.min(sql.length(), 60)).append('\n');
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    String v = rs.getString(i);
                    if (v != null && !md.getColumnName(i).equals("OWNER") && !md.getColumnName(i).equals("TYPE_OID")) {
                        row.append(md.getColumnName(i).toLowerCase()).append('=').append(v).append(' ');
                    }
                }
                out.append(row.toString().trim()).append('\n');
            }
        } catch (SQLException e) {
            out.append("ERR ").append(e.getMessage().split("\n")[0]).append('\n');
        }
    }
}
