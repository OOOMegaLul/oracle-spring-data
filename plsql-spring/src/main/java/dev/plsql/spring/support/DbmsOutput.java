package dev.plsql.spring.support;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Переносит вывод {@code DBMS_OUTPUT.PUT_LINE} из вызова PL/SQL в журнал приложения.
 *
 * <p>{@code DBMS_OUTPUT} — буфер строк в сессии Oracle: код PL/SQL пишет в него отладочные
 * сообщения, а SQL Developer или sqlplus ({@code set serveroutput on}) показывают их после
 * вызова. Через JDBC этот буфер никто не читает, поэтому по умолчанию строки пропадают.
 *
 * <p>Чтение включается уровнем DEBUG у журнала с именем этого класса:
 * {@code logging.level.dev.plsql.spring.support.DbmsOutput=DEBUG}. Тогда перед каждым вызовом
 * буфер включается, а после вызова, в том числе неудачного, строки читаются и пишутся в журнал
 * одним сообщением. Это два дополнительных обращения к базе на вызов, поэтому без DEBUG ничего
 * не делается. После чтения буфер выключается: соединение возвращается в пул таким, каким было.
 */
final class DbmsOutput {

    /** Журнал, в который пишутся строки; его уровень DEBUG включает чтение. */
    private static final Logger log = LoggerFactory.getLogger(DbmsOutput.class);

    /** Сколько строк читается за один вызов; остальные отбрасываются вместе с буфером. */
    static final int MAX_LINES = 10_000;

    /** Включает буфер без ограничения размера ({@code NULL} — без предела, с Oracle 10g). */
    private static final String ENABLE = "BEGIN\n  DBMS_OUTPUT.ENABLE(NULL);\nEND;";

    /**
     * Забирает до {@link #MAX_LINES} строк и выключает буфер (выключение ещё и очищает его).
     * {@code SYS.DBMSOUTPUT_LINESARRAY} — готовый тип Oracle для {@code GET_LINES}: массив
     * {@code VARCHAR2(32767)}.
     */
    private static final String READ = "DECLARE\n"
            + "  l SYS.DBMSOUTPUT_LINESARRAY;\n"
            + "  n INTEGER := " + MAX_LINES + ";\n"
            + "BEGIN\n"
            + "  DBMS_OUTPUT.GET_LINES(l, n);\n"
            + "  DBMS_OUTPUT.DISABLE;\n"
            + "  ? := l;\n"
            + "  ? := n;\n"
            + "END;";

    /** Класс только со статическими методами: экземпляры не создаются. */
    private DbmsOutput() {
    }

    /**
     * Проверяет, нужно ли читать вывод: включён ли уровень DEBUG у журнала этого класса.
     *
     * @return {@code true}, если вывод читается
     */
    static boolean wanted() {
        return log.isDebugEnabled();
    }

    /**
     * Включает буфер {@code DBMS_OUTPUT} в сессии соединения перед вызовом.
     *
     * <p>Ошибка не мешает самому вызову: она пишется в журнал, а вывод этого вызова не читается.
     *
     * @param con  соединение, на котором пойдёт вызов
     * @param task название вызова для журнала
     * @return {@code true}, если буфер включён и после вызова его нужно прочитать
     */
    static boolean enable(Connection con, String task) {
        try (CallableStatement cs = con.prepareCall(ENABLE)) {
            cs.execute();
            return true;
        } catch (SQLException e) {
            log.debug("{}: DBMS_OUTPUT could not be enabled", task, e);
            return false;
        }
    }

    /**
     * Читает строки, которые вызов положил в буфер, пишет их в журнал и выключает буфер.
     *
     * <p>Все строки уходят одним сообщением уровня DEBUG: первой строкой идёт название вызова,
     * дальше строки вывода как есть ({@code PUT_LINE(NULL)} даёт пустую строку). Если строк нет,
     * в журнал ничего не пишется. Ошибка чтения (например, соединение оборвалось вместе с
     * вызовом) только пишется в журнал: наружу должна уйти ошибка самого вызова.
     *
     * @param con  соединение, на котором прошёл вызов
     * @param task название вызова для журнала
     */
    static void read(Connection con, String task) {
        try (CallableStatement cs = con.prepareCall(READ)) {
            cs.registerOutParameter(1, Types.ARRAY, "SYS.DBMSOUTPUT_LINESARRAY");
            cs.registerOutParameter(2, Types.INTEGER);
            cs.execute();
            List<String> lines = lines(cs.getArray(1), cs.getInt(2));
            if (!lines.isEmpty()) {
                log.debug("{} DBMS_OUTPUT:\n{}{}", task, String.join("\n", lines),
                        lines.size() >= MAX_LINES ? "\n(the limit of " + MAX_LINES + " lines is reached; further lines, if any, are dropped)" : "");
            }
        } catch (SQLException e) {
            log.debug("{}: DBMS_OUTPUT could not be read", task, e);
        }
    }

    /**
     * Достаёт строки из массива, который вернул {@code GET_LINES}, и освобождает массив.
     *
     * <p>Массив бывает длиннее, чем число прочитанных строк (на 11.2 в конце лишний
     * {@code NULL}), поэтому берутся только первые {@code count} элементов.
     *
     * @param array массив строк или {@code null}
     * @param count сколько строк прочитано
     * @return строки вывода; {@code NULL} превращается в пустую строку
     * @throws SQLException если массив не удалось прочитать
     */
    static List<String> lines(Array array, int count) throws SQLException {
        if (array == null) {
            return List.of();
        }
        try {
            Object[] values = (Object[]) array.getArray();
            List<String> lines = new ArrayList<>(Math.min(count, values.length));
            for (int i = 0; i < count && i < values.length; i++) {
                lines.add(values[i] == null ? "" : values[i].toString());
            }
            return lines;
        } finally {
            array.free();
        }
    }
}
