package dev.plsql.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Явно говорит, что запрос {@link SqlQuery} работает без обязательного ограничения строк.
 *
 * <p>Нужно, когда ограничения обязательны ({@code plsql.require-restrictions=true} или
 * {@code PlsqlApiFactory.Builder.requireRestrictions(true)}): тогда каждый запрос, который
 * возвращает строки, должен быть помечен {@link Restricted} или {@code @Unrestricted}, иначе
 * приложение не стартует. Так ограничение нельзя забыть, а исключение видно на ревью. На методе
 * перекрывает {@link Restricted} интерфейса.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Unrestricted {

    /**
     * Возвращает причину, по которой ограничение не нужно, — для того, кто читает код.
     *
     * @return причина, например «справочник, общий для всех»
     */
    String value() default "";
}
