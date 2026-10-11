package dev.plsql.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Добавляет к запросам {@link SqlQuery} обязательное ограничение строк
 * ({@link dev.plsql.spring.query.QueryRestriction}) с этим именем. На интерфейсе — ко всем его
 * запросам, на методе — к одному; можно указать несколько ограничений.
 *
 * <p>Запрос оборачивается подзапросом, и условие ограничения проверяется по его колонкам, поэтому
 * колонка, по которой ограничиваются строки (например, организация записи), должна быть в
 * результате запроса. Ограничение действует на каждый вызов: список, страница, подсчёт строк для
 * {@code Page}, {@code Stream}, одна строка. Если ограничения с таким именем нет, приложение не
 * стартует. Только для запросов, которые возвращают строки ({@code SELECT}, {@code WITH}).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(Restricted.List.class)
public @interface Restricted {

    /**
     * Возвращает имя ограничения: имя бина {@code QueryRestriction} или имя, под которым его
     * передали в {@code PlsqlApiFactory.Builder.restriction}.
     *
     * @return имя ограничения
     */
    String value();

    /**
     * Возвращает поле запроса, по которому ограничиваются строки; по умолчанию — поле самого
     * ограничения ({@code QueryRestriction.defaultField()}).
     *
     * @return имя свойства или колонки; пусто — поле ограничения
     */
    String field() default "";

    /**
     * Несколько {@link Restricted} на одном интерфейсе или методе.
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface List {

        /**
         * Возвращает ограничения.
         *
         * @return ограничения
         */
        Restricted[] value();
    }
}
