package dev.plsql.spring.support;

import org.springframework.dao.NonTransientDataAccessException;

/**
 * Сообщает об ошибке, которую PL/SQL-код поднял через RAISE_APPLICATION_ERROR: бизнес-правило
 * запретило операцию.
 *
 * <p>RAISE_APPLICATION_ERROR — встроенная процедура Oracle, которой PL/SQL-код сообщает о
 * прикладной ошибке со своим кодом из диапазона 20000..20999 и текстом, например
 * {@code RAISE_APPLICATION_ERROR(-20001, 'Папка не пуста')}. Клиент получает её как ORA-20001.
 *
 * <p>{@link #getMessage()} — текст, который написал PL/SQL-код, без префикса
 * {@code ORA-20001:} и без стека ORA-06512 (строк «где произошла ошибка») под ним, то есть то,
 * что можно показать пользователю. Исходное {@code SQLException} доступно через
 * {@code getCause()}.
 *
 * <p>Наследует {@code NonTransientDataAccessException} из иерархии исключений Spring:
 * «непреходящая» ошибка: повтор той же операции снова завершится ошибкой, пока не устранена её
 * причина.
 */
public class PlsqlBusinessException extends NonTransientDataAccessException {

    private final int errorCode;

    /**
     * Создаёт исключение с кодом и очищенным текстом ошибки.
     *
     * @param errorCode код ошибки Oracle, 20000..20999
     * @param message   текст для пользователя, без префикса {@code ORA-NNNNN:} и стека
     * @param cause     исходное исключение JDBC
     */
    public PlsqlBusinessException(int errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * Возвращает код ошибки, переданный в RAISE_APPLICATION_ERROR, без знака минус.
     *
     * @return код ошибки, 20000..20999
     */
    public int getErrorCode() {
        return errorCode;
    }
}
