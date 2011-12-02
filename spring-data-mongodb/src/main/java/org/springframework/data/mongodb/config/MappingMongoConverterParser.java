/*
 * Copyright (c) 2011 by the original author(s).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.data.mongodb.config;

import static org.springframework.data.mongodb.config.BeanNames.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.BeanMetadataElement;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.beans.factory.support.ManagedSet;
import org.springframework.beans.factory.xml.AbstractBeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.GenericConverter;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.data.annotation.Persistent;
import org.springframework.data.mongodb.core.convert.CustomConversions;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexCreator;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.util.xml.DomUtils;
import org.w3c.dom.Element;

/**
 * @author Jon Brisbin <jbrisbin@vmware.com>
 * @author Oliver Gierke
 */
public class MappingMongoConverterParser extends AbstractBeanDefinitionParser {

	private static final String BASE_PACKAGE = "base-package";

	@Override
	protected String resolveId(Element element, AbstractBeanDefinition definition, ParserContext parserContext)
			throws BeanDefinitionStoreException {
		String id = super.resolveId(element, definition, parserContext);
		return StringUtils.hasText(id) ? id : "mappingConverter";
	}

	@Override
	protected AbstractBeanDefinition parseInternal(Element element, ParserContext parserContext) {
		BeanDefinitionRegistry registry = parserContext.getRegistry();

		BeanDefinition conversionsDefinition = getCustomConversions(element, parserContext);
		String ctxRef = potentiallyCreateMappingContext(element, parserContext, conversionsDefinition);

		// Need a reference to a Mongo instance
		String dbFactoryRef = element.getAttribute("db-factory-ref");
		if (!StringUtils.hasText(dbFactoryRef)) {
			dbFactoryRef = DB_FACTORY;
		}

		// Converter
		BeanDefinitionBuilder converterBuilder = BeanDefinitionBuilder.genericBeanDefinition(MappingMongoConverter.class);
		converterBuilder.addConstructorArgReference(dbFactoryRef);
		converterBuilder.addConstructorArgReference(ctxRef);

		if (conversionsDefinition != null) {
			converterBuilder.addPropertyValue("customConversions", conversionsDefinition);
		}

		try {
			registry.getBeanDefinition(INDEX_HELPER);
		} catch (NoSuchBeanDefinitionException ignored) {
			if (!StringUtils.hasText(dbFactoryRef)) {
				dbFactoryRef = DB_FACTORY;
			}
			BeanDefinitionBuilder indexHelperBuilder = BeanDefinitionBuilder
					.genericBeanDefinition(MongoPersistentEntityIndexCreator.class);
			indexHelperBuilder.addConstructorArgValue(new RuntimeBeanReference(ctxRef));
			indexHelperBuilder.addConstructorArgValue(new RuntimeBeanReference(dbFactoryRef));
			registry.registerBeanDefinition(INDEX_HELPER, indexHelperBuilder.getBeanDefinition());
		}

		return converterBuilder.getBeanDefinition();
	}

	private String potentiallyCreateMappingContext(Element element, ParserContext parserContext,
			BeanDefinition conversionsDefinition) {

		String ctxRef = element.getAttribute("mapping-context-ref");
		if (!StringUtils.hasText(ctxRef)) {
			BeanDefinitionBuilder mappingContextBuilder = BeanDefinitionBuilder
					.genericBeanDefinition(MongoMappingContext.class);

			Set<String> classesToAdd = getInititalEntityClasses(element, mappingContextBuilder);
			if (classesToAdd != null) {
				mappingContextBuilder.addPropertyValue("initialEntitySet", classesToAdd);
			}

			if (conversionsDefinition != null) {
				AbstractBeanDefinition simpleTypesDefinition = new GenericBeanDefinition();
				simpleTypesDefinition.setFactoryBeanName("customConversions");
				simpleTypesDefinition.setFactoryMethodName("getSimpleTypeHolder");

				mappingContextBuilder.addPropertyValue("simpleTypeHolder", simpleTypesDefinition);
			}

			parserContext.getRegistry().registerBeanDefinition(MAPPING_CONTEXT, mappingContextBuilder.getBeanDefinition());
			ctxRef = MAPPING_CONTEXT;
		}

		return ctxRef;
	}

	private BeanDefinition getCustomConversions(Element element, ParserContext parserContext) {

		List<Element> customConvertersElements = DomUtils.getChildElementsByTagName(element, "custom-converters");

		if (customConvertersElements.size() == 1) {
			
			Element customerConvertersElement = customConvertersElements.get(0);
			ManagedList<BeanMetadataElement> converterBeans = new ManagedList<BeanMetadataElement>();
			List<Element> converterElements = DomUtils.getChildElementsByTagName(customerConvertersElement, "converter");
			
			if (converterElements != null) {
				for (Element listenerElement : converterElements) {
					converterBeans.add(parseConverter(listenerElement, parserContext));
				}
			}

			// Scan for Converter and GenericConverter beans in the given base-package
			String packageToScan = customerConvertersElement.getAttribute(BASE_PACKAGE);
			if (StringUtils.hasText(packageToScan)) {
				ClassPathScanningCandidateComponentProvider provider = new ClassPathScanningCandidateComponentProvider(true);
				provider.addExcludeFilter(new NegatingFilter(new AssignableTypeFilter(Converter.class), new AssignableTypeFilter(
						GenericConverter.class)));
	
				for (BeanDefinition candidate : provider.findCandidateComponents(packageToScan)) {
					converterBeans.add(candidate);
				}
			}

			BeanDefinitionBuilder conversionsBuilder = BeanDefinitionBuilder.rootBeanDefinition(CustomConversions.class);
			conversionsBuilder.addConstructorArgValue(converterBeans);

			AbstractBeanDefinition conversionsBean = conversionsBuilder.getBeanDefinition();
			conversionsBean.setSource(parserContext.extractSource(element));

			parserContext.getRegistry().registerBeanDefinition("customConversions", conversionsBean);

			return conversionsBean;
		}

		return null;
	}

	public Set<String> getInititalEntityClasses(Element element, BeanDefinitionBuilder builder) {

		String basePackage = element.getAttribute(BASE_PACKAGE);

		if (!StringUtils.hasText(basePackage)) {
			return null;
		}

		ClassPathScanningCandidateComponentProvider componentProvider = new ClassPathScanningCandidateComponentProvider(
				false);
		componentProvider.addIncludeFilter(new AnnotationTypeFilter(Document.class));
		componentProvider.addIncludeFilter(new AnnotationTypeFilter(Persistent.class));

		Set<String> classes = new ManagedSet<String>();
		for (BeanDefinition candidate : componentProvider.findCandidateComponents(basePackage)) {
			classes.add(candidate.getBeanClassName());
		}

		return classes;
	}

	public BeanMetadataElement parseConverter(Element element, ParserContext parserContext) {

		String converterRef = element.getAttribute("ref");
		if (StringUtils.hasText(converterRef)) {
			return new RuntimeBeanReference(converterRef);
		}
		Element beanElement = DomUtils.getChildElementByTagName(element, "bean");
		if (beanElement != null) {
			BeanDefinitionHolder beanDef = parserContext.getDelegate().parseBeanDefinitionElement(beanElement);
			beanDef = parserContext.getDelegate().decorateBeanDefinitionIfRequired(beanElement, beanDef);
			return beanDef;
		}

		parserContext.getReaderContext().error(
				"Element <converter> must specify 'ref' or contain a bean definition for the converter", element);
		return null;
	}

	/**
	 * {@link TypeFilter} that returns {@literal false} in case any of the given delegates matches.
	 *
	 * @author Oliver Gierke
	 */
	private static class NegatingFilter implements TypeFilter {

		private final Set<TypeFilter> delegates;

		/**
		 * Creates a new {@link NegatingFilter} with the given delegates.
		 * 
		 * @param filters
		 */
		public NegatingFilter(TypeFilter... filters) {
			Assert.notNull(filters);
			this.delegates = new HashSet<TypeFilter>(Arrays.asList(filters));
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.core.type.filter.TypeFilter#match(org.springframework.core.type.classreading.MetadataReader, org.springframework.core.type.classreading.MetadataReaderFactory)
		 */
		public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory) throws IOException {

			for (TypeFilter delegate : delegates) {
				if (delegate.match(metadataReader, metadataReaderFactory)) {
					return false;
				}
			}

			return true;
		}
	}
}
