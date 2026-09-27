package dev.plsql.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names the PL/SQL argument explicitly. Without it the Java parameter name is matched
 * against the argument name, ignoring case, underscores and a one-letter type prefix
 * ({@code tenant} matches {@code NTENANT}, {@code P_TENANT} and {@code TENANT}).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface Arg {

    String value();
}
