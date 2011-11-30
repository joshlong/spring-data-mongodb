package org.springframework.data.mongodb.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.data.mongodb.core.MongoExceptionTranslator;

/**
 *
 * A simple {@link org.springframework.beans.factory.config.BeanFactoryPostProcessor bean factory post processor}
 * that automatically installs all the machinery you need to get the cross document store functionality working.
 *
 * @author Josh Long
 */
public class MongoDocumentBeanFactoryPostProcessor implements BeanFactoryPostProcessor {
       @Bean
	public MongoDocumentBacking mongoDocumentBacking() {
		MongoDocumentBacking mdb = MongoDocumentBacking.aspectOf();
		mdb.setChangeSetPersister(changeSetPersister());
		return mdb;
	}

	@Bean
	public MongoChangeSetPersister changeSetPersister() {
		MongoChangeSetPersister mongoChangeSetPersister = new MongoChangeSetPersister();
		mongoChangeSetPersister.setEntityManagerFactory(localContainerEntityManagerFactoryBean().getObject());
		mongoChangeSetPersister.setMongoTemplate(mongoTemplate());
		return mongoChangeSetPersister;
	}

	@Bean
	public CrossStoreCustomerRepository crossStoreCustomerRepository() {
		return new CrossStoreCustomerRepository();
	}

	@Bean
	public MongoExceptionTranslator mongoExceptionTranslator() {
		return new MongoExceptionTranslator();
	}

	@Bean
	public  PersistenceAnnotationBeanPostProcessor persistenceAnnotationBeanPostProcessor() {
		return new PersistenceAnnotationBeanPostProcessor();
	}

	@Bean
	public PersistenceExceptionTranslationPostProcessor persistenceExceptionTranslationPostProcessor() {
		return new PersistenceExceptionTranslationPostProcessor();

	}

    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        //
    }
}
