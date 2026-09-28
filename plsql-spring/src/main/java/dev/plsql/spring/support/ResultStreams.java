package dev.plsql.spring.support;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ResolvableType;
import org.springframework.jdbc.core.RowMapper;

/**
 * Превращает открытый {@link ResultSet} в {@link Stream}, который читает строки по одной, пока
 * вызывающий код их берёт.
 *
 * <p>Строки не копятся в памяти: в отличие от {@code List}, поток годится для выборок в миллионы
 * строк. Зато он держит курсор и соединение открытыми, пока его не закроют, поэтому поток нужно
 * закрывать, лучше всего через try-with-resources.
 */
final class ResultStreams {

    private static final Logger log = LoggerFactory.getLogger(ResultStreams.class);

    /** Класс только со статическими методами: экземпляры не создаются. */
    private ResultStreams() {
    }

    /**
     * Создаёт поток строк.
     *
     * <p>Колонки сопоставляются с типом строки один раз, на первой строке. Ошибка JDBC при
     * чтении превращается в исключение {@code translate}. При закрытии потока сначала закрывается
     * {@code ResultSet}, затем вызывается {@code onClose} с признаком «чтение сорвалось»: по нему
     * своя единица работы откатывается, а не фиксируется.
     *
     * @param rs        открытый результат или {@code null} (курсор не открыт — поток пуст)
     * @param element   тип одной строки
     * @param translate перевод ошибок JDBC в исключения Spring
     * @param onClose   что сделать при закрытии потока; получает {@code true}, если чтение
     *                  завершилось ошибкой
     * @return поток строк; его нужно закрыть
     */
    static Stream<Object> of(ResultSet rs, ResolvableType element, Function<SQLException, RuntimeException> translate,
                             Consumer<Boolean> onClose) {
        Rows rows = new Rows(rs, element, translate);
        return StreamSupport.stream(rows, false).onClose(() -> {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException e) {
                    log.debug("closing a result set failed", e);
                }
            }
            onClose.accept(rows.failed);
        });
    }

    /**
     * Источник элементов потока: по одной строке {@code ResultSet} на шаг.
     */
    private static final class Rows extends Spliterators.AbstractSpliterator<Object> {

        /** Открытый результат или {@code null}. */
        private final ResultSet rs;
        /** Тип одной строки. */
        private final ResolvableType element;
        /** Перевод ошибок JDBC. */
        private final Function<SQLException, RuntimeException> translate;
        /** Преобразователь строк; создаётся на первой строке, по её колонкам. */
        private RowMapper<?> mapper;
        /** Номер следующей строки, с нуля. */
        private int n;
        /** Чтение завершилось ошибкой. */
        private boolean failed;

        /**
         * Создаёт источник.
         *
         * @param rs        открытый результат или {@code null}
         * @param element   тип одной строки
         * @param translate перевод ошибок JDBC
         */
        Rows(ResultSet rs, ResolvableType element, Function<SQLException, RuntimeException> translate) {
            super(Long.MAX_VALUE, Spliterator.ORDERED);
            this.rs = rs;
            this.element = element;
            this.translate = translate;
        }

        /**
         * Читает следующую строку и отдаёт её потоку.
         *
         * @param action кому отдать строку
         * @return {@code false}, если строки кончились
         */
        @Override
        public boolean tryAdvance(Consumer<? super Object> action) {
            if (rs == null) {
                return false;
            }
            try {
                if (!rs.next()) {
                    return false;
                }
                if (mapper == null) {
                    mapper = RowMappers.forResult(rs.getMetaData(), element, false);
                }
                action.accept(mapper.mapRow(rs, n++));
                return true;
            } catch (SQLException e) {
                failed = true;
                throw translate.apply(e);
            } catch (RuntimeException | Error e) {
                failed = true;
                throw e;
            }
        }
    }
}
