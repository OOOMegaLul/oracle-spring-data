package dev.plsql.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names the PL/SQL subprogram explicitly. Without it the name is derived from the
 * method name: {@code setParam} becomes {@code SET_PARAM}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Procedure {

    String value() default "";

    /**
     * Pass NULL for required IN arguments the method does not supply. Many legacy
     * procedures declare long argument lists without DEFAULT and callers pass NULL for
     * most of them; this says so once instead of on every argument. Off by default: a
     * forgotten argument should fail the start, not silently become NULL.
     */
    boolean nullForMissing() default false;
}
