package dev.plsql.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Import;

import dev.plsql.spring.support.PlsqlApiRegistrar;

/**
 * Scans for {@link PlsqlApi} interfaces and registers an implementation for each,
 * like {@code @EnableJdbcRepositories}. With Spring Boot this is not needed: the
 * auto-configuration scans the application's package.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Import(PlsqlApiRegistrar.class)
public @interface EnablePlsqlApis {

    /** Packages to scan. Empty means the package of the annotated class. */
    String[] basePackages() default {};

    /** Bean name of the DataSource, used when no {@code PlsqlApiFactory} bean exists. */
    String dataSourceRef() default "dataSource";

    /** Bean name of the {@code PlsqlApiFactory} to use; for several databases in one application. */
    String factoryRef() default "";
}
