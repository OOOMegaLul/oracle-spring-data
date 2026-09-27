package dev.plsql.spring.support;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.jdbc.support.SQLExceptionTranslator;

/**
 * Превращает {@link SQLException} от драйвера Oracle в непроверяемые исключения Spring.
 *
 * <p>Ошибки ORA-20000..20999 (их поднимает PL/SQL-код через RAISE_APPLICATION_ERROR) становятся
 * {@link PlsqlBusinessException} с очищенным текстом; всё остальное проходит через стандартное
 * сопоставление кодов ошибок Oracle в Spring ({@code SQLErrorCodeSQLExceptionTranslator}):
 * например, ORA-00001 (нарушение уникальности) становится {@code DuplicateKeyException}.
 *
 * <p>Используется и для вызовов процедур, и для запросов {@code @SqlQuery}. Свой вариант можно
 * передать в {@code PlsqlApiFactory.Builder.exceptionTranslator}.
 */
public class PlsqlExceptionTranslator {

    private final SQLExceptionTranslator fallback = new SQLErrorCodeSQLExceptionTranslator("Oracle");

    /**
     * Переводит ошибку JDBC в исключение Spring.
     *
     * <p>Метод не бросает исключение, а возвращает его: бросает вызывающий код.
     *
     * @param task описание операции для текста ошибки (например, имя вызываемой процедуры)
     * @param sql  выполнявшийся SQL или PL/SQL-блок; может быть {@code null}
     * @param e    исходная ошибка JDBC
     * @return {@link PlsqlBusinessException} для кодов 20000..20999; иначе исключение из
     *         стандартной иерархии Spring, а если стандартный переводчик ничего не подобрал —
     *         {@link UncategorizedSQLException}
     */
    public RuntimeException translate(String task, String sql, SQLException e) {
        int code = e.getErrorCode();
        if (code >= 20000 && code <= 20999) {
            return new PlsqlBusinessException(code, userMessage(e.getMessage(), code), e);
        }
        DataAccessException dae = fallback.translate(task, sql, e);
        return dae != null ? dae : new UncategorizedSQLException(task, sql, e);
    }

    /**
     * Вырезает из сообщения Oracle текст, который написал PL/SQL-код.
     *
     * <p>{@code "ORA-20001: Отпуск пересекается с командировкой\nORA-06512: at ..."} превращается
     * в {@code "Отпуск пересекается с командировкой"}: отбрасывается всё до префикса
     * {@code ORA-<код>: } включительно и всё, начиная со следующей строки, которая начинается с
     * {@code ORA-} (стек ORA-06512 — строки «где произошла ошибка»). Если префикса нет, текст
     * берётся с начала сообщения. Пробелы по краям убираются.
     *
     * @param message полное сообщение исключения; может быть {@code null}
     * @param code    код ошибки Oracle, например 20001
     * @return текст для пользователя; для {@code null} — строка вида {@code ORA-20001}
     */
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

    /**
     * Проверяет, сброшено ли состояние пакета (кто-то перекомпилировал пакет), то есть можно ли
     * повторить вызов.
     *
     * <p>Повтор безопасен для данных: неудачный вызов блока — один оператор, и его изменения
     * Oracle откатывает сам. Не откатываются только автономные транзакции, значения
     * последовательностей и действия вне базы (файлы, почта), если процедура успела их сделать
     * до ошибки.
     *
     * <p>Состояние пакета — значения его переменных, которые Oracle хранит отдельно для каждой
     * сессии. После перекомпиляции пакета Oracle сбрасывает это состояние в сессиях, которые
     * пакетом уже пользовались, и первый следующий вызов в такой сессии падает с ORA-04068.
     *
     * <p>Проверяются коды 4068, 4061, 4065 и 6508 (ORA-04068, ORA-04061, ORA-04065, ORA-06508) у
     * самого исключения и у всех связанных с ним: и по цепочке следующих исключений
     * ({@code getNextException()}), и по цепочке причин ({@code getCause()}). Каждое звено
     * смотрится один раз, поэтому зацикленная цепочка не зависает.
     *
     * @param e ошибка JDBC
     * @return {@code true}, если это сброс состояния пакета и вызов можно повторить
     */
    public static boolean isStateDiscarded(SQLException e) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> todo = new ArrayDeque<>(List.of(e));
        while (!todo.isEmpty()) {
            Throwable t = todo.pop();
            if (!seen.add(t)) {
                continue;
            }
            if (t instanceof SQLException s) {
                int c = s.getErrorCode();
                if (c == 4068 || c == 4061 || c == 4065 || c == 6508) {
                    return true;
                }
                if (s.getNextException() != null) {
                    todo.push(s.getNextException());
                }
            }
            if (t.getCause() != null) {
                todo.push(t.getCause());
            }
        }
        return false;
    }
}
