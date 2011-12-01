package org.springframework.data.mongodb.crossstore;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.data.mongodb.core.MongoExceptionTranslator;
import org.springframework.orm.jpa.support.PersistenceAnnotationBeanPostProcessor;
import org.springframework.util.Assert;

/**
 * A simple {@link org.springframework.beans.factory.config.BeanFactoryPostProcessor bean factory post processor}
 * that automatically installs all the machinery you need to get the cross document store functionality working.
 *
 * @author Josh Long
 */
public class MongoDocumentBeanFactoryPostProcessor implements BeanFactoryPostProcessor {

    public MongoDocumentBacking mongoDocumentBacking() {
        MongoDocumentBacking mdb = MongoDocumentBacking.aspectOf();
        //	mdb.setChangeSetPersister(changeSetPersister());
        return mdb;
    }


//	public MongoChangeSetPersister changeSetPersister() {
//		MongoChangeSetPersister mongoChangeSetPersister = new MongoChangeSetPersister();
//		mongoChangeSetPersister.setEntityManagerFactory(localContainerEntityManagerFactoryBean().getObject());
//		mongoChangeSetPersister.setMongoTemplate(mongoTemplate());
//		return mongoChangeSetPersister;
//	}
//

    public MongoExceptionTranslator mongoExceptionTranslator() {
        return new MongoExceptionTranslator();
    }


    public PersistenceAnnotationBeanPostProcessor persistenceAnnotationBeanPostProcessor() {
        return new PersistenceAnnotationBeanPostProcessor();
    }


    public PersistenceExceptionTranslationPostProcessor persistenceExceptionTranslationPostProcessor() {
        return new PersistenceExceptionTranslationPostProcessor();

    }

    static private boolean exists(Class<?> beanClass, BeanDefinitionRegistry registry) {

        String needleClass = beanClass.getClass().getName();
        String[] beanNames = registry.getBeanDefinitionNames();
        for (String beanName : beanNames) {
            BeanDefinition beanDefinition = registry.getBeanDefinition(beanName);
            if (beanDefinition.getBeanClassName().equals(needleClass)) {
                return true;
            }
        }
        return false;
    }

    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {

        Class<BeanDefinitionRegistry> beanFactoryClass = BeanDefinitionRegistry.class;

        Assert.isInstanceOf(beanFactoryClass, beanFactory, "the bean factory must be an instance of " + beanFactoryClass.getName());
        BeanDefinitionRegistry beanDefinitionRegistry = (BeanDefinitionRegistry) beanFactory;


        //BeanDefinitionBuilder schedulerBuilder = BeanDefinitionBuilder.genericBeanDefinition(
        ///"org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler");
        // first, let's check to see if exception translation's already registered


    }

    static <T> void registerIfNotRegistered(BeanDefinitionRegistry beanDefinitionRegistry, Class<T> t, BeanDefinitionBuilderCallback<T> cb) {
        String beanName = t.getSimpleName().toLowerCase();
        Class<PersistenceExceptionTranslationPostProcessor> petpp = PersistenceExceptionTranslationPostProcessor.class;
        if (!exists(petpp, beanDefinitionRegistry)) {
            BeanDefinitionBuilder beanDefinitionBuilder = BeanDefinitionBuilder.genericBeanDefinition(petpp);
            cb.build(beanDefinitionBuilder, t);
            beanDefinitionRegistry.registerBeanDefinition(uniqueBeanName(beanDefinitionRegistry, beanName), beanDefinitionBuilder.getBeanDefinition());

        }
    }

    static interface BeanDefinitionBuilderCallback<T> {
        void build(BeanDefinitionBuilder b, Class<T> t);
    }

    static private String PPETP_BEAN_NAME = PersistenceExceptionTranslationPostProcessor.class.getSimpleName().toLowerCase();

    // utility method
    private static String uniqueBeanName(BeanDefinitionRegistry beanDefinitionRegistry, String base) {
        String name = base;
        int counter = 0;
        while (beanDefinitionRegistry.isBeanNameInUse(name))
            name = name + counter;
        return name;
    }
}
