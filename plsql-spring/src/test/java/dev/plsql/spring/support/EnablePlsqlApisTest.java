package dev.plsql.spring.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.StandardEnvironment;

import dev.plsql.spring.PlsqlApiFactory;
import dev.plsql.spring.support.enablefixture.EnableApp;

/** Без Spring Boot и без бина-фабрики все интерфейсы получают одну общую фабрику. */
class EnablePlsqlApisTest {

    /**
     * Проверяет, что {@code @EnablePlsqlApis} регистрирует бины обоих интерфейсов, а фабрика
     * {@link PlsqlApiFactory} создаётся одна на {@code DataSource} (запись {@code dataSource} в
     * служебном бине общих фабрик), а не по одной на интерфейс: иначе для каждого интерфейса
     * повторялись бы сборка окружения и запрос {@code NLS_CHARACTERSET}. Сама общая фабрика
     * бином {@code PlsqlApiFactory} не видна, чтобы не достаться интерфейсам другой базы.
     */
    @Test
    void interfacesShareOneFactory() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(EnableApp.class)) {
            assertThat(ctx.getBean(EnableApp.First.class)).isNotNull();
            assertThat(ctx.getBean(EnableApp.Second.class)).isNotNull();
            assertThat(ctx.getBeansOfType(PlsqlApiFactory.class)).isEmpty();
            assertThat(ctx.getBean(PlsqlApiFactoryBean.SHARED_FACTORIES, Map.class).keySet()).containsExactly("dataSource");
        }
    }
    /**
     * Проверяет, что два интерфейса с одинаковым простым именем, найденные через пересекающиеся
     * пакеты сканирования, регистрируются по одному разу: второй под полным именем, а повторная
     * находка того же интерфейса пропускается. Раньше повторная находка регистрировалась ещё раз
     * под полным именем и при запрете переопределения бинов (как в Spring Boot) роняла старт.
     */
    @Test
    void sameSimpleNamesInOverlappingPackagesRegisterOnce() {
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
        registry.setAllowBeanDefinitionOverriding(false);

        PlsqlApiRegistrar.register(registry, List.of("dev.plsql.spring.support.clashfixture",
                "dev.plsql.spring.support.clashfixture.a", "dev.plsql.spring.support.clashfixture.b"),
                "dataSource", "", null, new StandardEnvironment());

        assertThat(registry.getBeanDefinitionNames()).hasSize(2).contains("api");
    }
}
