/*
 * Copyright 2011 by the original author(s).
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
package org.springframework.data.mongodb.core.convert;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bson.types.ObjectId;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.core.CollectionFactory;
import org.springframework.core.convert.ConversionException;
import org.springframework.core.convert.support.ConversionServiceFactory;
import org.springframework.data.convert.TypeMapper;
import org.springframework.data.mapping.Association;
import org.springframework.data.mapping.AssociationHandler;
import org.springframework.data.mapping.PreferredConstructor;
import org.springframework.data.mapping.PropertyHandler;
import org.springframework.data.mapping.context.MappingContext;
import org.springframework.data.mapping.model.BeanWrapper;
import org.springframework.data.mapping.model.MappingException;
import org.springframework.data.mapping.model.ParameterValueProvider;
import org.springframework.data.mapping.model.SpELAwareParameterValueProvider;
import org.springframework.data.mongodb.MongoDbFactory;
import org.springframework.data.mongodb.core.QueryMapper;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.data.mongodb.core.mapping.MongoPersistentProperty;
import org.springframework.data.util.ClassTypeInformation;
import org.springframework.data.util.TypeInformation;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBObject;
import com.mongodb.DBRef;

/**
 * {@link MongoConverter} that uses a {@link MappingContext} to do sophisticated mapping of domain objects to
 * {@link DBObject}.
 * 
 * @author Oliver Gierke
 * @author Jon Brisbin
 */
public class MappingMongoConverter extends AbstractMongoConverter implements ApplicationContextAware, TypeKeyAware {

	@SuppressWarnings("rawtypes")
	private static final TypeInformation<Map> MAP_TYPE_INFORMATION = ClassTypeInformation.from(Map.class);
	@SuppressWarnings("rawtypes")
	private static final TypeInformation<Collection> COLLECTION_TYPE_INFORMATION = ClassTypeInformation
			.from(Collection.class);

	private static final List<Class<?>> VALID_ID_TYPES = Arrays.asList(new Class<?>[] { ObjectId.class, String.class,
			BigInteger.class, byte[].class });

	protected static final Log log = LogFactory.getLog(MappingMongoConverter.class);

	protected final MappingContext<? extends MongoPersistentEntity<?>, MongoPersistentProperty> mappingContext;
	protected final SpelExpressionParser spelExpressionParser = new SpelExpressionParser();
	protected final MongoDbFactory mongoDbFactory;
	protected final QueryMapper idMapper;
	protected ApplicationContext applicationContext;
	protected boolean useFieldAccessOnly = true;
	protected MongoTypeMapper typeMapper;

	/**
	 * Creates a new {@link MappingMongoConverter} given the new {@link MongoDbFactory} and {@link MappingContext}.
	 * 
	 * @param mongoDbFactory must not be {@literal null}.
	 * @param mappingContext must not be {@literal null}.
	 */
	public MappingMongoConverter(MongoDbFactory mongoDbFactory,
			MappingContext<? extends MongoPersistentEntity<?>, MongoPersistentProperty> mappingContext) {

		super(ConversionServiceFactory.createDefaultConversionService());

		Assert.notNull(mongoDbFactory);
		Assert.notNull(mappingContext);

		this.mongoDbFactory = mongoDbFactory;
		this.mappingContext = mappingContext;
		this.typeMapper = new DefaultMongoTypeMapper(DefaultMongoTypeMapper.DEFAULT_TYPE_KEY, mappingContext);
		this.idMapper = new QueryMapper(conversionService);
	}

	/**
	 * Configures the {@link MongoTypeMapper} to be used to add type information to {@link DBObject}s created by the
	 * converter and how to lookup type information from {@link DBObject}s when reading them. Uses a
	 * {@link DefaultMongoTypeMapper} by default. Setting this to {@literal null} will reset the {@link TypeMapper} to the
	 * default one.
	 * 
	 * @param typeMapper the typeMapper to set
	 */
	public void setTypeMapper(MongoTypeMapper typeMapper) {
		this.typeMapper = typeMapper == null ? new DefaultMongoTypeMapper(DefaultMongoTypeMapper.DEFAULT_TYPE_KEY,
				mappingContext) : typeMapper;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.mongodb.core.convert.TypeKeyAware#isTypeKey(java.lang.String)
	 */
	public boolean isTypeKey(String key) {
		return typeMapper.isTypeKey(key);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.convert.EntityConverter#getMappingContext()
	 */
	public MappingContext<? extends MongoPersistentEntity<?>, MongoPersistentProperty> getMappingContext() {
		return mappingContext;
	}

	/**
	 * Configures whether to use field access only for entity mapping. Setting this to true will force the
	 * {@link MongoConverter} to not go through getters or setters even if they are present for getting and setting
	 * property values.
	 * 
	 * @param useFieldAccessOnly
	 */
	public void setUseFieldAccessOnly(boolean useFieldAccessOnly) {
		this.useFieldAccessOnly = useFieldAccessOnly;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.context.ApplicationContextAware#setApplicationContext(org.springframework.context.ApplicationContext)
	 */
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.mongodb.core.core.MongoReader#read(java.lang.Class, com.mongodb.DBObject)
	 */
	public <S extends Object> S read(Class<S> clazz, final DBObject dbo) {
		return read(ClassTypeInformation.from(clazz), dbo);
	}

	@SuppressWarnings("unchecked")
	protected <S extends Object> S read(TypeInformation<S> type, DBObject dbo) {

		if (null == dbo) {
			return null;
		}

		TypeInformation<? extends S> typeToUse = typeMapper.readType(dbo, type);
		Class<? extends S> rawType = typeToUse.getType();

		if (conversions.hasCustomReadTarget(dbo.getClass(), rawType)) {
			return conversionService.convert(dbo, rawType);
		}
		
		if (DBObject.class.isAssignableFrom(rawType)) {
			return (S) dbo;
		}

		if (typeToUse.isCollectionLike() && dbo instanceof BasicDBList) {
			return (S) readCollectionOrArray(typeToUse, (BasicDBList) dbo);
		}

		if (typeToUse.isMap()) {
			return (S) readMap(typeToUse, dbo);
		}

		// Retrieve persistent entity info
		MongoPersistentEntity<S> persistentEntity = (MongoPersistentEntity<S>) mappingContext
				.getPersistentEntity(typeToUse);
		if (persistentEntity == null) {
			throw new MappingException("No mapping metadata found for " + rawType.getName());
		}

		return read(persistentEntity, dbo);
	}

	private <S extends Object> S read(final MongoPersistentEntity<S> entity, final DBObject dbo) {

		final StandardEvaluationContext spelCtx = new StandardEvaluationContext();
		if (null != applicationContext) {
			spelCtx.setBeanResolver(new BeanFactoryResolver(applicationContext));
		}
		if (!(dbo instanceof BasicDBList)) {
			String[] keySet = dbo.keySet().toArray(new String[dbo.keySet().size()]);
			for (String key : keySet) {
				spelCtx.setVariable(key, dbo.get(key));
			}
		}

		final List<String> ctorParamNames = new ArrayList<String>();
		final MongoPersistentProperty idProperty = entity.getIdProperty();

		ParameterValueProvider provider = new SpELAwareParameterValueProvider(spelExpressionParser, spelCtx) {
			@Override
			@SuppressWarnings("unchecked")
			public <T> T getParameterValue(PreferredConstructor.Parameter<T> parameter) {

				if (parameter.getKey() != null) {
					return super.getParameterValue(parameter);
				}

				String name = parameter.getName();
				TypeInformation<T> type = parameter.getType();
				Class<T> rawType = parameter.getRawType();
				String key = idProperty == null ? name : idProperty.getName().equals(name) ? idProperty.getFieldName() : name;
				Object obj = dbo.get(key);

				ctorParamNames.add(name);
				if (obj instanceof DBRef) {
					return read(type, ((DBRef) obj).fetch());
				} else if (obj instanceof BasicDBList) {
					BasicDBList objAsDbList = (BasicDBList) obj;
					return conversionService.convert(readCollectionOrArray(type, objAsDbList), rawType);
				} else if (obj instanceof DBObject) {
					return read(type, ((DBObject) obj));
				} else if (null != obj && obj.getClass().isAssignableFrom(rawType)) {
					return (T) obj;
				} else if (null != obj) {
					return conversionService.convert(obj, rawType);
				}

				return null;
			}
		};

		final BeanWrapper<MongoPersistentEntity<S>, S> wrapper = BeanWrapper.create(entity, provider, conversionService);

		// Set properties not already set in the constructor
		entity.doWithProperties(new PropertyHandler<MongoPersistentProperty>() {
			public void doWithPersistentProperty(MongoPersistentProperty prop) {

				boolean isConstructorProperty = ctorParamNames.contains(prop.getName());
				boolean hasValueForProperty = dbo.containsField(prop.getFieldName());

				if (!hasValueForProperty || isConstructorProperty) {
					return;
				}

				Object obj = getValueInternal(prop, dbo, spelCtx, prop.getSpelExpression());
				try {
					wrapper.setProperty(prop, obj, useFieldAccessOnly);
				} catch (IllegalAccessException e) {
					throw new MappingException(e.getMessage(), e);
				} catch (InvocationTargetException e) {
					throw new MappingException(e.getMessage(), e);
				}
			}
		});

		// Handle associations
		entity.doWithAssociations(new AssociationHandler<MongoPersistentProperty>() {
			public void doWithAssociation(Association<MongoPersistentProperty> association) {
				MongoPersistentProperty inverseProp = association.getInverse();
				Object obj = getValueInternal(inverseProp, dbo, spelCtx, inverseProp.getSpelExpression());
				try {
					wrapper.setProperty(inverseProp, obj);
				} catch (IllegalAccessException e) {
					throw new MappingException(e.getMessage(), e);
				} catch (InvocationTargetException e) {
					throw new MappingException(e.getMessage(), e);
				}
			}
		});

		return wrapper.getBean();
	}

	/**
	 * Root entry method into write conversion. Adds a type discriminator to the {@link DBObject}. Shouldn't be called for
	 * nested conversions.
	 *
     * @see MongoWriter#write(Object, Object)
	 */
	public void write(final Object obj, final DBObject dbo) {

		if (null == obj) {
			return;
		}

		boolean handledByCustomConverter = conversions.getCustomWriteTarget(obj.getClass(), DBObject.class) != null;
		TypeInformation<? extends Object> type = ClassTypeInformation.from(obj.getClass());
		
		if (!handledByCustomConverter && !(dbo instanceof BasicDBList)) {
			typeMapper.writeType(type, dbo);
		}

		writeInternal(obj, dbo, type);
	}

	/**
	 * Internal write conversion method which should be used for nested invocations.
	 * 
	 * @param obj
	 * @param dbo
	 */
	@SuppressWarnings("unchecked")
	protected void writeInternal(final Object obj, final DBObject dbo, final TypeInformation<?> typeHint) {

		if (null == obj) {
			return;
		}

		Class<?> customTarget = conversions.getCustomWriteTarget(obj.getClass(), DBObject.class);

		if (customTarget != null) {
			DBObject result = conversionService.convert(obj, DBObject.class);
			dbo.putAll(result);
			return;
		}

		if (Map.class.isAssignableFrom(obj.getClass())) {
			writeMapInternal((Map<Object, Object>) obj, dbo, MAP_TYPE_INFORMATION);
			return;
		}

		if (Collection.class.isAssignableFrom(obj.getClass())) {
			writeCollectionInternal((Collection<?>) obj, COLLECTION_TYPE_INFORMATION, (BasicDBList) dbo);
			return;
		}

		MongoPersistentEntity<?> entity = mappingContext.getPersistentEntity(obj.getClass());
		writeInternal(obj, dbo, entity);
		addCustomTypeKeyIfNecessary(typeHint, obj, dbo);
	}

	protected void writeInternal(Object obj, final DBObject dbo, MongoPersistentEntity<?> entity) {

		if (obj == null) {
			return;
		}

		if (null == entity) {
			throw new MappingException("No mapping metadata found for entity of type " + obj.getClass().getName());
		}

		final BeanWrapper<MongoPersistentEntity<Object>, Object> wrapper = BeanWrapper.create(obj, conversionService);

		// Write the ID
		final MongoPersistentProperty idProperty = entity.getIdProperty();
		if (!dbo.containsField("_id") && null != idProperty) {
			Object idObj = null;
			Class<?>[] targetClasses = new Class<?>[] { ObjectId.class, String.class, Object.class };
			for (Class<?> targetClass : targetClasses) {
				try {
					idObj = wrapper.getProperty(idProperty, targetClass, useFieldAccessOnly);
					if (null != idObj) {
						break;
					}
				} catch (ConversionException ignored) {
				} catch (IllegalAccessException e) {
					throw new MappingException(e.getMessage(), e);
				} catch (InvocationTargetException e) {
					throw new MappingException(e.getMessage(), e);
				}
			}

			if (null != idObj) {
				dbo.put("_id", idObj);
			} else {
				if (!VALID_ID_TYPES.contains(idProperty.getType())) {
					throw new MappingException("Invalid data type " + idProperty.getType().getName()
							+ " for Id property. Should be one of " + VALID_ID_TYPES);
				}
			}
		}

		// Write the properties
		entity.doWithProperties(new PropertyHandler<MongoPersistentProperty>() {
			public void doWithPersistentProperty(MongoPersistentProperty prop) {
				if (prop.equals(idProperty)) {
					return;
				}
				Object propertyObj;
				try {
					propertyObj = wrapper.getProperty(prop, prop.getType(), useFieldAccessOnly);
				} catch (IllegalAccessException e) {
					throw new MappingException(e.getMessage(), e);
				} catch (InvocationTargetException e) {
					throw new MappingException(e.getMessage(), e);
				}
				if (null != propertyObj) {
					if (!conversions.isSimpleType(propertyObj.getClass())) {
						writePropertyInternal(propertyObj, dbo, prop);
					} else {
						writeSimpleInternal(propertyObj, dbo, prop.getFieldName());
					}
				}
			}
		});

		entity.doWithAssociations(new AssociationHandler<MongoPersistentProperty>() {
			public void doWithAssociation(Association<MongoPersistentProperty> association) {
				MongoPersistentProperty inverseProp = association.getInverse();
				Class<?> type = inverseProp.getType();
				Object propertyObj;
				try {
					propertyObj = wrapper.getProperty(inverseProp, type, useFieldAccessOnly);
				} catch (IllegalAccessException e) {
					throw new MappingException(e.getMessage(), e);
				} catch (InvocationTargetException e) {
					throw new MappingException(e.getMessage(), e);
				}
				if (null != propertyObj) {
					writePropertyInternal(propertyObj, dbo, inverseProp);
				}
			}
		});
	}

	@SuppressWarnings({ "unchecked" })
	protected void writePropertyInternal(Object obj, DBObject dbo, MongoPersistentProperty prop) {

		if (obj == null) {
			return;
		}

		String name = prop.getFieldName();

		if (prop.isCollection()) {
			DBObject collectionInternal = createCollection(asCollection(obj), prop);
			dbo.put(name, collectionInternal);
			return;
		}

		TypeInformation<?> type = prop.getTypeInformation();

		if (prop.isMap()) {
			BasicDBObject mapDbObj = new BasicDBObject();
			writeMapInternal((Map<Object, Object>) obj, mapDbObj, type);
			dbo.put(name, mapDbObj);
			return;
		}

		if (prop.isDbReference()) {
			DBRef dbRefObj = createDBRef(obj, prop.getDBRef());
			if (null != dbRefObj) {
				dbo.put(name, dbRefObj);
				return;
			}
		}

		// Lookup potential custom target type
		Class<?> basicTargetType = conversions.getCustomWriteTarget(obj.getClass(), null);

		if (basicTargetType != null) {
			dbo.put(name, conversionService.convert(obj, basicTargetType));
			return;
		}

		BasicDBObject propDbObj = new BasicDBObject();
		addCustomTypeKeyIfNecessary(type, obj, propDbObj);

		MongoPersistentEntity<?> entity = isSubtype(prop.getType(), obj.getClass()) ? mappingContext
				.getPersistentEntity(obj.getClass()) : mappingContext.getPersistentEntity(type);

		writeInternal(obj, propDbObj, entity);
		dbo.put(name, propDbObj);
	}

	private boolean isSubtype(Class<?> left, Class<?> right) {
		return left.isAssignableFrom(right) && !left.equals(right);
	}

	/**
	 * Returns given object as {@link Collection}. Will return the {@link Collection} as is if the source is a
	 * {@link Collection} already, will convert an array into a {@link Collection} or simply create a single element
	 * collection for everything else.
	 * 
	 * @param source
	 * @return
	 */
	private static Collection<?> asCollection(Object source) {

		if (source instanceof Collection) {
			return (Collection<?>) source;
		}

		return source.getClass().isArray() ? CollectionUtils.arrayToList(source) : Collections.singleton(source);
	}

	/**
	 * Writes the given {@link Collection} using the given {@link MongoPersistentProperty} information.
	 * 
	 * @param collection must not be {@literal null}.
	 * @param property must not be {@literal null}.
	 * 
	 * @return
	 */
	protected DBObject createCollection(Collection<?> collection, MongoPersistentProperty property) {

		if (!property.isDbReference()) {
			return writeCollectionInternal(collection, property.getTypeInformation(), new BasicDBList());
		}

		BasicDBList dbList = new BasicDBList();

		for (Object element : collection) {

			if (element == null) {
				continue;
			}

			DBRef dbRef = createDBRef(element, property.getDBRef());
			dbList.add(dbRef);
		}

		return dbList;
	}

	/**
	 * Populates the given {@link BasicDBList} with values from the given {@link Collection}.
	 * 
	 * @param source the collection to create a {@link BasicDBList} for, must not be {@literal null}.
	 * @param type the {@link TypeInformation} to consider or {@literal null} if unknown.
	 * @param sink the {@link BasicDBList} to write to.
	 * @return
	 */
	private BasicDBList writeCollectionInternal(Collection<?> source, TypeInformation<?> type, BasicDBList sink) {

		TypeInformation<?> componentType = type == null ? null : type.getComponentType();

		for (Object element : source) {

			if (element == null) {
				continue;
			}

			Class<?> elementType = element.getClass();

			if (conversions.isSimpleType(elementType)) {
				sink.add(getPotentiallyConvertedSimpleWrite(element));
			} else if (element instanceof Collection || elementType.isArray()) {
				sink.add(writeCollectionInternal(asCollection(element), componentType, new BasicDBList()));
			} else {
				BasicDBObject propDbObj = new BasicDBObject();
				writeInternal(element, propDbObj, componentType);
				sink.add(propDbObj);
			}
		}

		return sink;
	}

	/**
	 * Writes the given {@link Map} to the given {@link DBObject} considering the given {@link TypeInformation}.
	 * 
	 * @param obj must not be {@literal null}.
	 * @param dbo must not be {@literal null}.
	 * @param propertyType must not be {@literal null}.
	 * @return
	 */
	protected DBObject writeMapInternal(Map<Object, Object> obj, DBObject dbo, TypeInformation<?> propertyType) {
		
		for (Map.Entry<Object, Object> entry : obj.entrySet()) {
			Object key = entry.getKey();
			Object val = entry.getValue();
			if (conversions.isSimpleType(key.getClass())) {
				// Don't use conversion service here as removal of ObjectToString converter results in some primitive types not
				// being convertable
				String simpleKey = key.toString();
				if (val == null || conversions.isSimpleType(val.getClass())) {
					writeSimpleInternal(val, dbo, simpleKey);
				} else if (val instanceof Collection || val.getClass().isArray()) {
					dbo.put(simpleKey,
							writeCollectionInternal(asCollection(val), propertyType.getMapValueType(), new BasicDBList()));
				} else {
					DBObject newDbo = new BasicDBObject();
					writeInternal(val, newDbo, propertyType);
					dbo.put(simpleKey, newDbo);
				}
			} else {
				throw new MappingException("Cannot use a complex object as a key value.");
			}
		}
		
		return dbo;
	}

	/**
	 * Adds custom type information to the given {@link DBObject} if necessary. That is if the value is not the same as
	 * the one given. This is usually the case if you store a subtype of the actual declared type of the property.
	 * 
	 * @param type
	 * @param value must not be {@literal null}.
	 * @param dbObject must not be {@literal null}.
	 */
	protected void addCustomTypeKeyIfNecessary(TypeInformation<?> type, Object value, DBObject dbObject) {

		TypeInformation<?> actualType = type != null ? type.getActualType() : type;
		Class<?> reference = actualType == null ? Object.class : actualType.getType();

		boolean notTheSameClass = !value.getClass().equals(reference);
		if (notTheSameClass) {
			typeMapper.writeType(value.getClass(), dbObject);
		}
	}

	/**
	 * Writes the given simple value to the given {@link DBObject}. Will store enum names for enum values.
	 * 
	 * @param value
	 * @param dbObject must not be {@literal null}.
	 * @param key must not be {@literal null}.
	 */
	private void writeSimpleInternal(Object value, DBObject dbObject, String key) {
		dbObject.put(key, getPotentiallyConvertedSimpleWrite(value));
	}

	/**
	 * Checks whether we have a custom conversion registered for the given value into an arbitrary simple Mongo type.
	 * Returns the converted value if so. If not, we perform special enum handling or simply return the value as is.
	 * 
	 * @param value
	 * @return
	 */
	private Object getPotentiallyConvertedSimpleWrite(Object value) {

		if (value == null) {
			return null;
		}

		Class<?> customTarget = conversions.getCustomWriteTarget(value.getClass(), null);

		if (customTarget != null) {
			return conversionService.convert(value, customTarget);
		} else {
			return Enum.class.isAssignableFrom(value.getClass()) ? ((Enum<?>) value).name() : value;
		}
	}

	/**
	 * Checks whether we have a custom conversion for the given simple object. Converts the given value if so, applies
	 * {@link Enum} handling or returns the value as is.
	 * 
	 * @param value
	 * @param target must not be {@literal null}.
	 * @return
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private Object getPotentiallyConvertedSimpleRead(Object value, Class<?> target) {

		if (value == null || target == null) {
			return value;
		}

		if (conversions.hasCustomReadTarget(value.getClass(), target)) {
			return conversionService.convert(value, target);
		}

		if (Enum.class.isAssignableFrom(target)) {
			return Enum.valueOf((Class<Enum>) target, value.toString());
		}

		return target.isAssignableFrom(value.getClass()) ? value : conversionService.convert(value, target);
	}

	protected DBRef createDBRef(Object target, org.springframework.data.mongodb.core.mapping.DBRef dbref) {

		MongoPersistentEntity<?> targetEntity = mappingContext.getPersistentEntity(target.getClass());
		if (null == targetEntity || null == targetEntity.getIdProperty()) {
			return null;
		}

		MongoPersistentProperty idProperty = targetEntity.getIdProperty();
		Object id = null;
		BeanWrapper<MongoPersistentEntity<Object>, Object> wrapper = BeanWrapper.create(target, conversionService);

		try {
			id = wrapper.getProperty(idProperty, Object.class, useFieldAccessOnly);
			if (null == id) {
				throw new MappingException("Cannot create a reference to an object with a NULL id.");
			}
		} catch (IllegalAccessException e) {
			throw new MappingException(e.getMessage(), e);
		} catch (InvocationTargetException e) {
			throw new MappingException(e.getMessage(), e);
		}

		String collection = dbref.collection();
		if ("".equals(collection)) {
			collection = targetEntity.getCollection();
		}

		String dbname = dbref.db();
		DB db = StringUtils.hasText(dbname) ? mongoDbFactory.getDb(dbname) : mongoDbFactory.getDb();

		return new DBRef(db, collection, idMapper.convertId(id));
	}

	@SuppressWarnings("unchecked")
	protected Object getValueInternal(MongoPersistentProperty prop, DBObject dbo, StandardEvaluationContext ctx,
			String spelExpr) {

		Object o;
		if (null != spelExpr) {
			Expression x = spelExpressionParser.parseExpression(spelExpr);
			o = x.getValue(ctx);
		} else {

			Object sourceValue = dbo.get(prop.getFieldName());

			if (sourceValue == null) {
				return null;
			}

			Class<?> propertyType = prop.getType();

			if (conversions.hasCustomReadTarget(sourceValue.getClass(), propertyType)) {
				return conversionService.convert(sourceValue, propertyType);
			}

			if (sourceValue instanceof DBRef) {
				sourceValue = ((DBRef) sourceValue).fetch();
			}



			if (sourceValue instanceof DBObject) {
				if (prop.isMap()) {
					return readMap(prop.getTypeInformation(), (DBObject) sourceValue);
				} else if (prop.isArray() && sourceValue instanceof BasicDBObject
						&& ((DBObject) sourceValue).keySet().size() == 0) {
					// It's empty
					return Array.newInstance(prop.getComponentType(), 0);
				} else if (prop.isCollection() && sourceValue instanceof BasicDBList) {
					return readCollectionOrArray((TypeInformation<? extends Collection<?>>) prop.getTypeInformation(),
							(BasicDBList) sourceValue);
				}

				TypeInformation<?> toType = typeMapper.readType((DBObject) sourceValue);

				// It's a complex object, have to read it in
				if (toType != null) {
					// TODO: why do we remove the type?
					// dbo.removeField(CUSTOM_TYPE_KEY);
					o = read(toType, (DBObject) sourceValue);
				} else {
					o = read(mappingContext.getPersistentEntity(prop.getTypeInformation()), (DBObject) sourceValue);
				}
			} else {
				o = sourceValue;
			}
		}
		return o;
	}

	/**
	 * Reads the given {@link BasicDBList} into a collection of the given {@link TypeInformation}.
	 * 
	 * @param targetType must not be {@literal null}.
	 * @param sourceValue must not be {@literal null}.
	 * @return the converted {@link Collections}, will never be {@literal null}.
	 */
	@SuppressWarnings("unchecked")
	private Collection<?> readCollectionOrArray(TypeInformation<?> targetType, BasicDBList sourceValue) {

		Assert.notNull(targetType);

		Collection<Object> items = targetType.getType().isArray() ? new ArrayList<Object>() : CollectionFactory
				.createCollection(targetType.getType(), sourceValue.size());

		for (int i = 0; i < sourceValue.size(); i++) {
			Object dbObjItem = sourceValue.get(i);
			if (dbObjItem instanceof DBRef) {
				items.add(read(targetType.getComponentType(), ((DBRef) dbObjItem).fetch()));
			} else if (dbObjItem instanceof DBObject) {
				items.add(read(targetType.getComponentType(), (DBObject) dbObjItem));
			} else {
				items.add(getPotentiallyConvertedSimpleRead(dbObjItem, targetType.getComponentType().getType()));
			}
		}

		return items;
	}

	/**
	 * Reads the given {@link DBObject} into a {@link Map}. will recursively resolve nested {@link Map}s as well.
	 * 
	 * @param type the {@link Map} {@link TypeInformation} to be used to unmarshall this {@link DBObject}.
	 * @param dbObject
	 * @return
	 */
	@SuppressWarnings("unchecked")
	protected Map<Object, Object> readMap(TypeInformation<?> type, DBObject dbObject) {

		Assert.notNull(dbObject);

		Class<?> mapType = typeMapper.readType(dbObject, type).getType();
		Map<Object, Object> map = CollectionFactory.createMap(mapType, dbObject.keySet().size());
		Map<String, Object> sourceMap = dbObject.toMap();

		for (Entry<String, Object> entry : sourceMap.entrySet()) {
			if (typeMapper.isTypeKey(entry.getKey())) {
				continue;
			}

			Object key = entry.getKey();

			TypeInformation<?> keyTypeInformation = type.getComponentType();
			if (keyTypeInformation != null) {
				Class<?> keyType = keyTypeInformation.getType();
				key = conversionService.convert(entry.getKey(), keyType);
			}

			Object value = entry.getValue();
			TypeInformation<?> valueType = type.getMapValueType();

			if (value instanceof DBObject) {
				map.put(key, read(valueType, (DBObject) value));
			} else {
				Class<?> valueClass = valueType == null ? null : valueType.getType();
				map.put(key, getPotentiallyConvertedSimpleRead(value, valueClass));
			}
		}

		return map;
	}

	protected <T> List<?> unwrapList(BasicDBList dbList, TypeInformation<T> targetType) {
		List<Object> rootList = new ArrayList<Object>();
		for (int i = 0; i < dbList.size(); i++) {
			Object obj = dbList.get(i);
			if (obj instanceof BasicDBList) {
				rootList.add(unwrapList((BasicDBList) obj, targetType.getComponentType()));
			} else if (obj instanceof DBObject) {
				rootList.add(read(targetType, (DBObject) obj));
			} else {
				rootList.add(obj);
			}
		}
		return rootList;
	}

	@SuppressWarnings("unchecked")
	public Object convertToMongoType(Object obj) {

		if (obj == null) {
			return null;
		}

		Class<?> target = conversions.getCustomWriteTarget(getClass());
		if (target != null) {
			return conversionService.convert(obj, target);
		}

		if (null != obj && conversions.isSimpleType(obj.getClass())) {
			// Doesn't need conversion
			return getPotentiallyConvertedSimpleWrite(obj);
		}

		if (obj instanceof BasicDBList) {
			return maybeConvertList((BasicDBList) obj);
		}

		if (obj instanceof DBObject) {
			DBObject newValueDbo = new BasicDBObject();
			for (String vk : ((DBObject) obj).keySet()) {
				Object o = ((DBObject) obj).get(vk);
				newValueDbo.put(vk, convertToMongoType(o));
			}
			return newValueDbo;
		}

		if (obj instanceof Map) {
			DBObject result = new BasicDBObject();
			for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) obj).entrySet()) {
				result.put(entry.getKey().toString(), convertToMongoType(entry.getValue()));
			}
			return result;
		}

		if (obj instanceof List) {
			return maybeConvertList((List<?>) obj);
		}

		if (obj.getClass().isArray()) {
			return maybeConvertList(Arrays.asList((Object[]) obj));
		}

		DBObject newDbo = new BasicDBObject();
		this.write(obj, newDbo);
		return newDbo;
	}

	public BasicDBList maybeConvertList(Iterable<?> source) {
		BasicDBList newDbl = new BasicDBList();
		for (Object element : source) {
			newDbl.add(convertToMongoType(element));
		}
		return newDbl;
	}
}
