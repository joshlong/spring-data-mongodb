package org.springframework.data.mongodb.crossstore;

import javax.persistence.EntityManagerFactory;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.data.mongodb.core.MongoExceptionTranslator;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.orm.jpa.support.PersistenceAnnotationBeanPostProcessor;
import org.springframework.util.Assert;

/**
 * A simple
 * {@link org.springframework.beans.factory.config.BeanFactoryPostProcessor bean
 * factory post processor} that automatically installs all the machinery you
 * need to get the cross document store functionality working.
 * 
 * @author Josh Long
 */
public class MongoDocumentBeanFactoryPostProcessor implements BeanFactoryPostProcessor {

	static private Log log = LogFactory.getLog(MongoDocumentBeanFactoryPostProcessor.class);

	public void postProcessBeanFactory(final ConfigurableListableBeanFactory beanFactory) throws BeansException {
		Assert.isInstanceOf(BeanDefinitionRegistry.class, beanFactory);
		final BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;

		// registerIfNotRegistered( registry, );

		registerIfNotRegistered(registry, MongoChangeSetPersister.class, new BeanDefinitionBuilderCallback<MongoChangeSetPersister>() {
			public void build(BeanDefinitionBuilder b, Class<MongoChangeSetPersister> t) {
				// b.setFactoryMethod("aspectOf");

				String[] beans = null;

				// / first, find the EMF
				Class<EntityManagerFactory> emfClass = EntityManagerFactory.class;
				BeanFactoryUtils.beanOfType(beanFactory, emfClass);
				beans = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(beanFactory, emfClass);
				Assert.isTrue(null != beans && beans.length == 1, "you must register a bean of type " + emfClass.getName());
				b.addPropertyReference("setEntityManagerFactory", beans[0]);

				// then find the MongoTemplate
				Class<MongoTemplate> mt = MongoTemplate.class;
				BeanFactoryUtils.beanOfType(beanFactory, EntityManagerFactory.class);
				beans = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(beanFactory, mt);
				Assert.isTrue(null != beans && beans.length == 1, "you must register a bean of type " + mt.getName());
				b.addPropertyReference("mongoTemplate", beans[0]);
			}
		});
		registerIfNotRegistered(registry, PersistenceAnnotationBeanPostProcessor.class);
		registerIfNotRegistered(registry, MongoExceptionTranslator.class);
		registerIfNotRegistered(registry, PersistenceExceptionTranslationPostProcessor.class);
		/*
		 * registerIfNotRegistered(registry, MongoDocumentBacking.class, new
		 * BeanDefinitionBuilderCallback<MongoDocumentBacking>() { public void
		 * build(BeanDefinitionBuilder b, Class<MongoDocumentBacking> t) {
		 * b.setFactoryMethod("aspectOf"); Class<ChangeSetPersister> mt =
		 * ChangeSetPersister.class; BeanFactoryUtils.beanOfType(beanFactory,
		 * EntityManagerFactory.class); String[] beans =
		 * BeanFactoryUtils.beanNamesForTypeIncludingAncestors(beanFactory, mt);
		 * Assert.isTrue(null != beans && beans.length == 1,
		 * "you must register a bean of type " + mt.getName());
		 * b.addPropertyReference("changeSetPersister", beans[0]); } });
		 */

	}

	static <T> void registerIfNotRegistered(BeanDefinitionRegistry clfb, Class<T> t) {
		registerIfNotRegistered(clfb, t, new BeanDefinitionBuilderCallback<T>() {
			public void build(BeanDefinitionBuilder b, Class<T> t) { // noop
			}
		});
	}

	static <T> void registerIfNotRegistered(BeanDefinitionRegistry clfb, Class<T> t, BeanDefinitionBuilderCallback<T> cb) {
		String beanName = t.getSimpleName().toLowerCase();
		String[] beanNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors((ListableBeanFactory) clfb, t);
		if (beanNames != null && beanNames.length == 1) {
			BeanDefinitionBuilder beanDefinitionBuilder = BeanDefinitionBuilder.genericBeanDefinition(t);
			cb.build(beanDefinitionBuilder, t);
			String beanNameFinal = uniqueBeanName(clfb, beanName);
			System.out.println("registering a bean of type " + t.getName() + " with bean name '" + beanNameFinal + "'");
			clfb.registerBeanDefinition(beanNameFinal, beanDefinitionBuilder.getBeanDefinition());
		}
	}

	static interface BeanDefinitionBuilderCallback<T> {
		void build(BeanDefinitionBuilder b, Class<T> t);
	}

	private static String uniqueBeanName(BeanDefinitionRegistry beanDefinitionRegistry, String base) {
		String name = base;
		int counter = 0;
		while (beanDefinitionRegistry.isBeanNameInUse(name))
			name = name + counter;
		return name;
	}
}
