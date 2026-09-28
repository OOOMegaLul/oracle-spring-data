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
 * строк. Зато он держит курсор и соединение открытыми, пока не дочитан до конца, не сорвался на
 * ошибке или не закрыт. Поток, который бросили недочитанным ({@code findFirst}, {@code limit}),
 * нужно закрыть, лучше всего через try-with-resources.
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
     * чтении превращается в исключение {@code translate}. Ресурсы отпускаются один раз, как только
     * строки кончились, чтение сорвалось или поток закрыли: сначала закрывается {@code ResultSet},
     * затем вызывается {@code onClose} с признаком «чтение сорвалось» — по нему своя единица работы
     * откатывается, а не фиксируется. Так дочитанный поток ({@code toList()}) возвращает соединение,
     * даже если его забыли закрыть.
     *
     * @param rs        открытый результат или {@code null} (курсор не открыт — поток пуст)
     * @param element   тип одной строки
     * @param translate перевод ошибок JDBC в исключения Spring
     * @param onClose   что сделать в конце; получает {@code true}, если чтение завершилось
     *                  ошибкой; вызывается один раз
     * @return поток строк; его нужно закрыть
     */
    static Stream<Object> of(ResultSet rs, ResolvableType element, Function<SQLException, RuntimeException> translate,
                             Consumer<Boolean> onClose) {
        Rows rows = new Rows(rs, element, translate, onClose);
        return StreamSupport.stream(rows, false).onClose(rows::release);
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
        /** Что сделать в конце; получает признак «чтение сорвалось». */
        private final Consumer<Boolean> onClose;
        /** Ресурсы уже отпущены. */
        private boolean released;
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
         * @param onClose   что сделать в конце
         */
        Rows(ResultSet rs, ResolvableType element, Function<SQLException, RuntimeException> translate,
             Consumer<Boolean> onClose) {
            super(Long.MAX_VALUE, Spliterator.ORDERED);
            this.rs = rs;
            this.element = element;
            this.translate = translate;
            this.onClose = onClose;
        }

        /**
         * Отпускает ресурсы один раз: закрывает {@code ResultSet} и вызывает {@code onClose}.
         * Следующие вызовы ничего не делают.
         */
        void release() {
            if (released) {
                return;
            }
            released = true;
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException e) {
                    log.debug("closing a result set failed", e);
                }
            }
            onClose.accept(failed);
        }

        /**
         * Отпускает ресурсы после ошибки чтения; ошибка самого отпускания присоединяется к исходной.
         *
         * @param failure ошибка чтения, которая уйдёт наружу
         */
        private void releaseAfter(Throwable failure) {
            failed = true;
            try {
                release();
            } catch (RuntimeException e) {
                failure.addSuppressed(e);
            }
        }

        /**
         * Читает следующую строку и отдаёт её потоку.
         *
         * @param action кому отдать строку
         * @return {@code false}, если строки кончились
         */
        @Override
        public boolean tryAdvance(Consumer<? super Object> action) {
            if (released) {
                return false;
            }
            if (rs == null) {
                release();
                return false;
            }
            try {
                if (!rs.next()) {
                    release();
                    return false;
                }
                if (mapper == null) {
                    mapper = RowMappers.forResult(rs.getMetaData(), element, false);
                }
                action.accept(mapper.mapRow(rs, n++));
                return true;
            } catch (SQLException e) {
                RuntimeException t = translate.apply(e);
                releaseAfter(t);
                throw t;
            } catch (RuntimeException | Error e) {
                releaseAfter(e);
                throw e;
            }
        }
    }
}
