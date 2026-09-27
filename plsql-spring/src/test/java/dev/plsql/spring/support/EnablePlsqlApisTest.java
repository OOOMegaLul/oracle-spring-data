package dev.plsql.spring.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import dev.plsql.spring.PlsqlApiFactory;
import dev.plsql.spring.support.enablefixture.EnableApp;

/** Without Boot and without a factory bean, every interface shares one factory. */
class EnablePlsqlApisTest {

    @Test
    void interfacesShareOneFactory() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(EnableApp.class)) {
            assertThat(ctx.getBean(EnableApp.First.class)).isNotNull();
            assertThat(ctx.getBean(EnableApp.Second.class)).isNotNull();
            assertThat(ctx.getBeansOfType(PlsqlApiFactory.class)).hasSize(1).containsKey("plsqlApiFactory#dataSource");
        }
    }
}
