package dev.plsql.spring.support;

import java.sql.SQLException;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.jdbc.support.SQLExceptionTranslator;

/**
 * ORA-20000..20999 become {@link PlsqlBusinessException} with a clean message;
 * everything else goes through Spring's standard Oracle error-code mapping.
 */
public class PlsqlExceptionTranslator {

    private final SQLExceptionTranslator fallback = new SQLErrorCodeSQLExceptionTranslator("Oracle");

    public RuntimeException translate(String task, String sql, SQLException e) {
        int code = e.getErrorCode();
        if (code >= 20000 && code <= 20999) {
            return new PlsqlBusinessException(code, userMessage(e.getMessage(), code), e);
        }
        DataAccessException dae = fallback.translate(task, sql, e);
        return dae != null ? dae : new UncategorizedSQLException(task, sql, e);
    }

    /** "ORA-20001: Отпуск пересекается с командировкой\nORA-06512: at ..." -> "Отпуск пересекается с командировкой" */
    static String userMessage(String message, int code) {
        if (message == null) {
            return "ORA-" + code;
        }
        String prefix = "ORA-" + code + ": ";
        int start = message.indexOf(prefix);
        String s = start >= 0 ? message.substring(start + prefix.length()) : message;
        int stack = s.indexOf("\nORA-");
        if (stack >= 0) {
            s = s.substring(0, stack);
        }
        return s.strip();
    }

    /** Package state was discarded (someone recompiled a package): the call never ran and can be repeated. */
    public static boolean isStateDiscarded(SQLException e) {
        for (Throwable t = e; t instanceof SQLException s; t = s.getNextException() != null ? s.getNextException() : s.getCause()) {
            int c = s.getErrorCode();
            if (c == 4068 || c == 4061 || c == 4065 || c == 6508) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
