package dev.plsql.spring.support;

import javax.sql.DataSource;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;

import dev.plsql.spring.PlsqlApiFactory;
import dev.plsql.spring.call.ArgumentDefaults;
import dev.plsql.spring.meta.SignatureSource;

/**
 * Creates the implementation of one {@code @PlsqlApi} interface; the counterpart of
 * Spring Data's {@code RepositoryFactoryBeanSupport}. Registered by {@link PlsqlApiRegistrar}.
 *
 * <p>Uses the {@link PlsqlApiFactory} bean named by {@code factoryRef}, else the only
 * {@link PlsqlApiFactory} bean, else builds one on the {@code dataSourceRef} DataSource
 * with any {@link ArgumentDefaults} and {@link SignatureSource} beans in the context.
 */
public class PlsqlApiFactoryBean<T> implements FactoryBean<T>, BeanFactoryAware, InitializingBean {

    private final Class<T> apiInterface;
    private String dataSourceRef = "dataSource";
    private String factoryRef = "";
    private BeanFactory beanFactory;
    private T instance;

    public PlsqlApiFactoryBean(Class<T> apiInterface) {
        this.apiInterface = apiInterface;
    }

    public void setDataSourceRef(String dataSourceRef) {
        this.dataSourceRef = dataSourceRef;
    }

    public void setFactoryRef(String factoryRef) {
        this.factoryRef = factoryRef;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void afterPropertiesSet() {
        instance = factory().create(apiInterface);
    }

    private PlsqlApiFactory factory() {
        if (!factoryRef.isEmpty()) {
            return beanFactory.getBean(factoryRef, PlsqlApiFactory.class);
        }
        return beanFactory.getBeanProvider(PlsqlApiFactory.class).getIfUnique(this::sharedFactory);
    }

    /**
     * One factory per DataSource for every interface of the context, registered as a
     * singleton by the first factory bean that needs it: building one per interface would
     * repeat the runtime and the NLS_CHARACTERSET query for each.
     */
    private PlsqlApiFactory sharedFactory() {
        String name = "plsqlApiFactory#" + dataSourceRef;
        if (beanFactory instanceof ConfigurableBeanFactory cbf) {
            synchronized (cbf.getSingletonMutex()) {
                if (cbf.containsSingleton(name)) {
                    return (PlsqlApiFactory) cbf.getSingleton(name);
                }
                PlsqlApiFactory f = build();
                cbf.registerSingleton(name, f);
                return f;
            }
        }
        return build();
    }

    private PlsqlApiFactory build() {
        PlsqlApiFactory.Builder b = PlsqlApiFactory.builder(beanFactory.getBean(dataSourceRef, DataSource.class));
        beanFactory.getBeanProvider(ArgumentDefaults.class).ifUnique(b::argumentDefaults);
        beanFactory.getBeanProvider(SignatureSource.class).ifUnique(b::signatureSource);
        return b.build();
    }

    @Override
    public T getObject() {
        return instance;
    }

    @Override
    public Class<?> getObjectType() {
        return apiInterface;
    }
}
