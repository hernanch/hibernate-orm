/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * Copyright (c) 2010, Red Hat Inc. or third-party contributors as
 * indicated by the @author tags or express copyright attribution
 * statements applied by the authors.  All third-party contributions are
 * distributed under license by Red Hat Inc.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.hibernate.type;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;

import org.hibernate.EntityMode;
import org.hibernate.FetchMode;
import org.hibernate.Filter;
import org.hibernate.HibernateException;
import org.hibernate.MappingException;
import org.hibernate.TransientObjectException;
import org.hibernate.engine.internal.ForeignKeys;
import org.hibernate.engine.spi.CascadeStyle;
import org.hibernate.engine.spi.CascadeStyles;
import org.hibernate.engine.spi.Mapping;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.internal.util.collections.ArrayHelper;
import org.hibernate.metamodel.spi.relational.Size;
import org.hibernate.persister.entity.Joinable;
import org.hibernate.proxy.HibernateProxyHelper;

/**
 * Handles "any" mappings
 * 
 * @author Gavin King
 */
public class AnyType extends AbstractType implements CompositeType, AssociationType {
	private final Type identifierType;
	private final Type metaType;

	public AnyType(Type metaType, Type identifierType) {
		this.identifierType = identifierType;
		this.metaType = metaType;
	}
	@Override
	public Object deepCopy(Object value, SessionFactoryImplementor factory)
	throws HibernateException {
		return value;
	}
	@Override
	public boolean isMethodOf(Method method) {
		return false;
	}
	@Override
	public boolean isSame(Object x, Object y) throws HibernateException {
		return x==y;
	}
	@Override
	public int compare(Object x, Object y) {
		return 0; //TODO: entities CAN be compared, by PK and entity name, fix this!
	}
	@Override
	public int getColumnSpan(Mapping session)
	throws MappingException {
		return 2;
	}
	@Override
	public String getName() {
		return "object";
	}
	@Override
	public boolean isMutable() {
		return false;
	}
	@Override
	public Object nullSafeGet(ResultSet rs,	String name, SessionImplementor session, Object owner)
	throws HibernateException, SQLException {

		throw new UnsupportedOperationException("object is a multicolumn type");
	}
	@Override
	public Object nullSafeGet(ResultSet rs,	String[] names,	SessionImplementor session,	Object owner)
	throws HibernateException, SQLException {
		return resolveAny(
				(String) metaType.nullSafeGet(rs, names[0], session, owner),
				(Serializable) identifierType.nullSafeGet(rs, names[1], session, owner),
				session
			);
	}
	@Override
	public Object hydrate(ResultSet rs,	String[] names,	SessionImplementor session,	Object owner)
	throws HibernateException, SQLException {
		String entityName = (String) metaType.nullSafeGet(rs, names[0], session, owner);
		Serializable id = (Serializable) identifierType.nullSafeGet(rs, names[1], session, owner);
		return new ObjectTypeCacheEntry(entityName, id);
	}
	@Override
	public Object resolve(Object value, SessionImplementor session, Object owner)
	throws HibernateException {
		ObjectTypeCacheEntry holder = (ObjectTypeCacheEntry) value;
		return resolveAny(holder.entityName, holder.id, session);
	}
	@Override
	public Object semiResolve(Object value, SessionImplementor session, Object owner)
	throws HibernateException {
		throw new UnsupportedOperationException("any mappings may not form part of a property-ref");
	}
	
	private Object resolveAny(String entityName, Serializable id, SessionImplementor session)
	throws HibernateException {
		return entityName==null || id==null ?
				null : session.internalLoad( entityName, id, false, false );
	}
	@Override
	public void nullSafeSet(PreparedStatement st, Object value,	int index, SessionImplementor session)
	throws HibernateException, SQLException {
		nullSafeSet(st, value, index, null, session);
	}
	@Override
	public void nullSafeSet(PreparedStatement st, Object value,	int index, boolean[] settable, SessionImplementor session)
	throws HibernateException, SQLException {

		Serializable id;
		String entityName;
		if (value==null) {
			id=null;
			entityName=null;
		}
		else {
			entityName = session.bestGuessEntityName(value);
			id = ForeignKeys.getEntityIdentifierIfNotUnsaved(entityName, value, session);
		}
		
		// metaType is assumed to be single-column type
		if ( settable==null || settable[0] ) {
			metaType.nullSafeSet(st, entityName, index, session);
		}
		if (settable==null) {
			identifierType.nullSafeSet(st, id, index+1, session);
		}
		else {
			boolean[] idsettable = new boolean[ settable.length-1 ];
			System.arraycopy(settable, 1, idsettable, 0, idsettable.length);
			identifierType.nullSafeSet(st, id, index+1, idsettable, session);
		}
	}
	@Override
	public Class getReturnedClass() {
		return Object.class;
	}
	@Override
	public int[] sqlTypes(Mapping mapping) throws MappingException {
		return ArrayHelper.join(
				metaType.sqlTypes( mapping ),
				identifierType.sqlTypes( mapping )
		);
	}

	@Override
	public Size[] dictatedSizes(Mapping mapping) throws MappingException {
		return ArrayHelper.join(
				metaType.dictatedSizes( mapping ),
				identifierType.dictatedSizes( mapping )
		);
	}

	@Override
	public Size[] defaultSizes(Mapping mapping) throws MappingException {
		return ArrayHelper.join(
				metaType.defaultSizes( mapping ),
				identifierType.defaultSizes( mapping )
		);
	}
	@Override
	public String toLoggableString(Object value, SessionFactoryImplementor factory) 
	throws HibernateException {
		//TODO: terrible implementation!
		return value == null
				? "null"
				: factory.getTypeHelper()
						.entity( HibernateProxyHelper.getClassWithoutInitializingProxy( value ) )
						.toLoggableString( value, factory );
	}

	public static final class ObjectTypeCacheEntry implements Serializable {
		String entityName;
		Serializable id;
		ObjectTypeCacheEntry(String entityName, Serializable id) {
			this.entityName = entityName;
			this.id = id;
		}
	}
	@Override
	public Object assemble(
		Serializable cached,
		SessionImplementor session,
		Object owner)
	throws HibernateException {

		ObjectTypeCacheEntry e = (ObjectTypeCacheEntry) cached;
		return e==null ? null : session.internalLoad(e.entityName, e.id, false, false);
	}
	@Override
	public Serializable disassemble(Object value, SessionImplementor session, Object owner)
	throws HibernateException {
		return value==null ?
			null :
			new ObjectTypeCacheEntry(
						session.bestGuessEntityName(value),
						ForeignKeys.getEntityIdentifierIfNotUnsaved( 
								session.bestGuessEntityName(value), value, session 
							)
					);
	}
	@Override
	public boolean isAnyType() {
		return true;
	}
	@Override
	public Object replace(
			Object original, 
			Object target,
			SessionImplementor session, 
			Object owner, 
			Map copyCache)
	throws HibernateException {
		if (original==null) {
			return null;
		}
		else {
			String entityName = session.bestGuessEntityName(original);
			Serializable id = ForeignKeys.getEntityIdentifierIfNotUnsaved(
					entityName,
					original,
					session
			);
			return session.internalLoad( 
					entityName, 
					id, 
					false, 
					false
				);
		}
	}
	@Override
	public CascadeStyle getCascadeStyle(int i) {
		return CascadeStyles.NONE;
	}
	@Override
	public FetchMode getFetchMode(int i) {
		return FetchMode.SELECT;
	}

	private static final String[] PROPERTY_NAMES = { "class", "id" };
	@Override
	public String[] getPropertyNames() {
		return PROPERTY_NAMES;
	}
	@Override
	public Object getPropertyValue(Object component, int i, SessionImplementor session)
		throws HibernateException {

		return i==0 ?
				session.bestGuessEntityName(component) :
				getIdentifier(component, session);
	}
	@Override
	public Object[] getPropertyValues(Object component, SessionImplementor session)
		throws HibernateException {

		return new Object[] { session.bestGuessEntityName(component), getIdentifier(component, session) };
	}

	private Serializable getIdentifier(Object value, SessionImplementor session) throws HibernateException {
		try {
			return ForeignKeys.getEntityIdentifierIfNotUnsaved( session.bestGuessEntityName(value), value, session );
		}
		catch (TransientObjectException toe) {
			return null;
		}
	}
	@Override
	public Type[] getSubtypes() {
		return new Type[] { metaType, identifierType };
	}
	@Override
	public void setPropertyValues(Object component, Object[] values, EntityMode entityMode)
		throws HibernateException {

		throw new UnsupportedOperationException();

	}
	@Override
	public Object[] getPropertyValues(Object component, EntityMode entityMode) {
		throw new UnsupportedOperationException();
	}
	@Override
	public boolean isComponentType() {
		return true;
	}
	@Override
	public ForeignKeyDirection getForeignKeyDirection() {
		//return AssociationType.TO_PARENT; //this is better but causes a transient object exception...
		return ForeignKeyDirection.FROM_PARENT;
	}
	@Override
	public boolean isAssociationType() {
		return true;
	}
	@Override
	public boolean useLHSPrimaryKey() {
		return false;
	}
	@Override
	public Joinable getAssociatedJoinable(SessionFactoryImplementor factory) {
		throw new UnsupportedOperationException("any types do not have a unique referenced persister");
	}
	@Override
	public boolean isModified(Object old, Object current, boolean[] checkable, SessionImplementor session)
	throws HibernateException {
		if (current==null) return old!=null;
		if (old==null) return current!=null;
		ObjectTypeCacheEntry holder = (ObjectTypeCacheEntry) old;
		boolean[] idcheckable = new boolean[checkable.length-1];
		System.arraycopy(checkable, 1, idcheckable, 0, idcheckable.length);
		return ( checkable[0] && !holder.entityName.equals( session.bestGuessEntityName(current) ) ) ||
				identifierType.isModified(holder.id, getIdentifier(current, session), idcheckable, session);
	}
	@Override
	public String getAssociatedEntityName(SessionFactoryImplementor factory)
		throws MappingException {
		throw new UnsupportedOperationException("any types do not have a unique referenced persister");
	}
	@Override
	public boolean[] getPropertyNullability() {
		return null;
	}
	@Override
	public String getOnCondition(String alias, SessionFactoryImplementor factory, Map<String, Filter> enabledFilters)
	throws MappingException {
		throw new UnsupportedOperationException();
	}
	
	@Override
	public String getRHSUniqueKeyPropertyName() {
		return null;
	}
	@Override
	public String getLHSPropertyName() {
		return null;
	}
	@Override
	public boolean isAlwaysDirtyChecked() {
		return false;
	}
	@Override
	public boolean[] toColumnNullness(Object value, Mapping mapping) {
		boolean[] result = new boolean[ getColumnSpan(mapping) ];
		if (value!=null) Arrays.fill(result, true);
		return result;
	}
	@Override
	public boolean isDirty(Object old, Object current, boolean[] checkable, SessionImplementor session) 
	throws HibernateException {
		//TODO!!!
		return isDirty(old, current, session);
	}
	@Override
	public boolean isEmbedded() {
		return false;
	}
}
