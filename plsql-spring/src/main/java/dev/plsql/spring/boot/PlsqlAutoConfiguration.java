package dev.plsql.spring.boot;

import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;

import dev.plsql.spring.PlsqlApiFactory;
import dev.plsql.spring.call.ArgumentDefaults;
import dev.plsql.spring.meta.SignatureSource;
import dev.plsql.spring.support.PlsqlApiFactoryBean;
import dev.plsql.spring.support.PlsqlApiRegistrar;

/**
 * Spring Boot: one {@link PlsqlApiFactory} on the application's DataSource, and every
 * {@code @PlsqlApi} interface in the application's packages registered as a bean, the way
 * Boot finds Spring Data repositories without {@code @Enable...}.
 */
// Boot 4 moved DataSourceAutoConfiguration; name both so the DataSource exists first on 3.x too.
@AutoConfiguration(afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"})
@ConditionalOnClass(oracle.jdbc.OracleConnection.class)
@EnableConfigurationProperties(PlsqlProperties.class)
@Import(PlsqlAutoConfiguration.Registrar.class)
public class PlsqlAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnSingleCandidate(DataSource.class)
    PlsqlApiFactory plsqlApiFactory(DataSource dataSource, PlsqlProperties props,
                                    ObjectProvider<ArgumentDefaults> defaults,
                                    ObjectProvider<SignatureSource> signatures) {
        PlsqlApiFactory.Builder b = PlsqlApiFactory.builder(dataSource)
                .charsetPolicy(props.getCharsetPolicy())
                .databaseCharset(props.getDatabaseCharset())
                .indexTableMaxLength(props.getIndexTableMaxLength())
                .retryDiscardedState(props.isRetryDiscardedState());
        defaults.ifUnique(b::argumentDefaults);
        signatures.ifUnique(b::signatureSource);
        return b.build();
    }

    static class Registrar implements ImportBeanDefinitionRegistrar, BeanFactoryAware, ResourceLoaderAware, EnvironmentAware {

        private BeanFactory beanFactory;
        private ResourceLoader resourceLoader;
        private Environment environment;

        @Override
        public void setBeanFactory(BeanFactory beanFactory) {
            this.beanFactory = beanFactory;
        }

        @Override
        public void setResourceLoader(ResourceLoader resourceLoader) {
            this.resourceLoader = resourceLoader;
        }

        @Override
        public void setEnvironment(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
            if (!AutoConfigurationPackages.has(beanFactory)) {
                return;
            }
            if (registry instanceof org.springframework.beans.factory.ListableBeanFactory lbf
                    && lbf.getBeanNamesForType(PlsqlApiFactoryBean.class, false, false).length > 0) {
                return; // @EnablePlsqlApis already did it
            }
            List<String> packages = AutoConfigurationPackages.get(beanFactory);
            PlsqlApiRegistrar.register(registry, packages, "dataSource", "", resourceLoader, environment);
        }
    }
}
