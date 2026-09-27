package dev.plsql.spring.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import dev.plsql.spring.annotation.EnablePlsqlApis;
import dev.plsql.spring.annotation.PlsqlApi;

/**
 * Finds {@code @PlsqlApi} interfaces and registers a {@link PlsqlApiFactoryBean} for each.
 */
public class PlsqlApiRegistrar implements ImportBeanDefinitionRegistrar, ResourceLoaderAware, EnvironmentAware {

    private ResourceLoader resourceLoader;
    private Environment environment;

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
        Map<String, Object> attrs = metadata.getAnnotationAttributes(EnablePlsqlApis.class.getName());
        List<String> packages = new ArrayList<>();
        String dataSourceRef = "dataSource";
        String factoryRef = "";
        if (attrs != null) {
            packages.addAll(Arrays.asList((String[]) attrs.get("basePackages")));
            dataSourceRef = (String) attrs.get("dataSourceRef");
            factoryRef = (String) attrs.get("factoryRef");
        }
        if (packages.isEmpty()) {
            packages.add(ClassUtils.getPackageName(metadata.getClassName()));
        }
        register(registry, packages, dataSourceRef, factoryRef, resourceLoader, environment);
    }

    /** Registers one factory bean per {@code @PlsqlApi} interface found under {@code packages}. */
    public static void register(BeanDefinitionRegistry registry, List<String> packages, String dataSourceRef,
                                String factoryRef, ResourceLoader resourceLoader, Environment environment) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false, environment) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition bd) {
                return bd.getMetadata().isInterface() && bd.getMetadata().isIndependent();
            }
        };
        if (resourceLoader != null) {
            scanner.setResourceLoader(resourceLoader);
        }
        scanner.addIncludeFilter(new AnnotationTypeFilter(PlsqlApi.class));
        ClassLoader cl = resourceLoader == null ? ClassUtils.getDefaultClassLoader() : resourceLoader.getClassLoader();
        for (String pkg : packages) {
            for (BeanDefinition candidate : scanner.findCandidateComponents(pkg)) {
                Class<?> api = ClassUtils.resolveClassName(candidate.getBeanClassName(), cl);
                String beanName = ClassUtils.getShortNameAsProperty(api);
                if (registry.containsBeanDefinition(beanName)) {
                    BeanDefinition existing = registry.getBeanDefinition(beanName);
                    if (api.equals(existing.getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE))) {
                        continue; // the same interface reached through two scanned packages
                    }
                    beanName = api.getName(); // two interfaces with one simple name
                }
                BeanDefinitionBuilder b = BeanDefinitionBuilder.genericBeanDefinition(PlsqlApiFactoryBean.class)
                        .addConstructorArgValue(api)
                        .addPropertyValue("dataSourceRef", dataSourceRef)
                        .addPropertyValue("factoryRef", factoryRef);
                AbstractBeanDefinition bd = b.getBeanDefinition();
                // Lets @Autowired find the bean by interface type before the factory runs.
                bd.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE, api);
                registry.registerBeanDefinition(beanName, bd);
            }
        }
    }
}
