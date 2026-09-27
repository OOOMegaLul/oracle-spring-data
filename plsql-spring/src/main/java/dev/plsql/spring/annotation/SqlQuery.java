package dev.plsql.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A plain SELECT on the same interface, like {@code @Query} in Spring Data.
 * Parameters are bound by name ({@code :rn}); rows map to records or classes by
 * column name, {@code BEGIN_DATE} to {@code beginDate}.
 *
 * <p>Return types: {@code List<T>}, {@code Optional<T>}, {@code T}, or a scalar.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SqlQuery {

    String value();
}
