package dev.plsql.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface whose methods are PL/SQL subprograms.
 *
 * <pre>
 * &#64;PlsqlApi(packageName = "APP_CONTEXT")
 * interface SessionApi {
 *     void setParam(String name, String value);   // APP_CONTEXT.SET_PARAM(SNAME, SVALUE)
 * }
 *
 * &#64;PlsqlApi                                   // standalone procedures and functions
 * interface CatalogApi {
 *     &#64;Procedure("P_FOLDER_DELETE")
 *     void deleteFolder(long tenant, long rn);
 * }
 * </pre>
 *
 * The implementation is generated at startup, the same way Spring Data generates
 * repositories. Every method is checked against ALL_ARGUMENTS before the context
 * finishes starting, so a renamed parameter fails the start, not the first call.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PlsqlApi {

    /** Package name. Empty means the methods map to standalone procedures and functions. */
    String packageName() default "";

    /** Owning schema. Empty means the connecting user's schema, resolved through synonyms. */
    String schema() default "";
}
