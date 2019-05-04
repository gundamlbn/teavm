/*
 *  Copyright 2016 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.metaprogramming.impl.reflect;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.teavm.metaprogramming.ReflectClass;
import org.teavm.metaprogramming.impl.MetaprogrammingImpl;
import org.teavm.metaprogramming.reflect.ReflectField;
import org.teavm.metaprogramming.reflect.ReflectMethod;
import org.teavm.metaprogramming.reflect.ReflectType;
import org.teavm.model.AccessLevel;
import org.teavm.model.ClassReader;
import org.teavm.model.ElementModifier;
import org.teavm.model.FieldReader;
import org.teavm.model.GenericTypeParameter;
import org.teavm.model.GenericValueType;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodReader;
import org.teavm.model.ValueType;

public class ReflectClassImpl<T> implements ReflectClass<T>, ReflectParameterizedMemberImpl {
    public final ValueType type;
    private ReflectContext context;
    public ClassReader classReader;
    private boolean resolved;
    private Map<String, ReflectFieldImpl> declaredFields = new HashMap<>();
    private ReflectField[] fieldsCache;
    private Map<MethodDescriptor, ReflectMethodImpl> methods = new HashMap<>();
    private Map<String, ReflectMethodImpl> declaredMethods = new HashMap<>();
    private ReflectMethod[] methodsCache;
    private ReflectAnnotatedElementImpl annotations;
    private Map<String, ReflectTypeVariableImpl> typeVariableMap;
    private String[] enumConstants;

    ReflectClassImpl(ValueType type, ReflectContext context) {
        this.type = type;
        this.context = context;
    }

    ReflectContext getReflectContext() {
        return context;
    }

    @Override
    public boolean isPrimitive() {
        return type instanceof ValueType.Primitive || type == ValueType.VOID;
    }

    @Override
    public boolean isInterface() {
        resolve();
        return classReader != null && classReader.readModifiers().contains(ElementModifier.INTERFACE);
    }

    @Override
    public boolean isArray() {
        return type instanceof ValueType.Array;
    }

    @Override
    public boolean isAnnotation() {
        resolve();
        return classReader != null && classReader.readModifiers().contains(ElementModifier.ANNOTATION);
    }

    @Override
    public boolean isEnum() {
        resolve();
        return classReader != null && classReader.readModifiers().contains(ElementModifier.ENUM);
    }

    @Override
    public String[] getEnumConstants() {
        resolve();
        if (classReader == null || !classReader.hasModifier(ElementModifier.ENUM)) {
            return null;
        }
        if (enumConstants == null) {
            List<String> result = new ArrayList<>();
            for (FieldReader field : classReader.getFields()) {
                if (field.hasModifier(ElementModifier.ENUM)) {
                    result.add(field.getName());
                }
            }
            enumConstants = result.toArray(new String[0]);
        }
        return enumConstants;
    }

    @Override
    public int getModifiers() {
        resolve();
        if (classReader == null) {
            return 0;
        }
        return ReflectContext.getModifiers(classReader);
    }

    @Override
    public ReflectClass<?> getComponentType() {
        if (!(type instanceof ValueType.Array)) {
            return null;
        }
        ValueType componentType = ((ValueType.Array) type).getItemType();
        return context.getClass(componentType);
    }

    @Override
    public String getName() {
        if (type instanceof ValueType.Object) {
            return ((ValueType.Object) type).getClassName();
        } else if (type instanceof ValueType.Void) {
            return "void";
        } else if (type instanceof ValueType.Primitive) {
            switch (((ValueType.Primitive) type).getKind()) {
                case BOOLEAN:
                    return "boolean";
                case BYTE:
                    return "byte";
                case SHORT:
                    return "short";
                case CHARACTER:
                    return "char";
                case INTEGER:
                    return "int";
                case LONG:
                    return "long";
                case FLOAT:
                    return "float";
                case DOUBLE:
                    return "double";
                default:
                    return "";
            }
        } else if (type instanceof ValueType.Array) {
            return type.toString().replace('/', '.');
        } else {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public ReflectClassImpl<? super T> getSuperclass() {
        resolve();
        if (classReader == null || classReader.getParent() == null) {
            return null;
        }
        return (ReflectClassImpl<? super T>) context.getClass(new ValueType.Object(classReader.getParent()));
    }

    @Override
    public ReflectType getGenericSuperclass() {
        resolve();
        if (classReader == null || classReader.getParent() == null) {
            return null;
        }

        GenericValueType.Object genericParent = classReader.getGenericParent();
        if (genericParent == null) {
            return context.getRawGenericType(getSuperclass());
        }

        return context.getGenericType(genericParent, getTypeVariableMap());
    }

    @SuppressWarnings("unchecked")
    @Override
    public ReflectClass<? super T>[] getInterfaces() {
        resolve();
        if (classReader == null) {
            return (ReflectClass<? super T>[]) Array.newInstance(ReflectClassImpl.class, 0);
        }
        return classReader.getInterfaces().stream()
                .map(iface -> context.getClass(new ValueType.Object(iface)))
                .toArray(sz -> (ReflectClass<? super T>[]) Array.newInstance(ReflectClassImpl.class, sz));
    }

    @Override
    public ReflectType[] getGenericInterfaces() {
        resolve();
        if (classReader == null || classReader.getGenericInterfaces().isEmpty()) {
            return ReflectContext.EMPTY_TYPES;
        }
        return classReader.getGenericInterfaces().stream()
                .map(iface -> context.getGenericType(iface, getTypeVariableMap()))
                .toArray(ReflectType[]::new);
    }

    @Override
    public boolean isInstance(Object obj) {
        throw new IllegalStateException("Don't call this method from compile domain");
    }

    @Override
    public T cast(Object obj) {
        throw new IllegalStateException("Don't call this method from compile domain");
    }

    @SuppressWarnings("unchecked")
    @Override
    public <U> ReflectClass<U> asSubclass(Class<U> cls) {
        ReflectClass<U> reflectClass = context.findClass(cls);
        if (!reflectClass.isAssignableFrom(this)) {
            throw new IllegalArgumentException(cls.getName() + " is not subclass of " + getName());
        }
        return (ReflectClass<U>) this;
    }

    @Override
    public boolean isAssignableFrom(ReflectClass<?> cls) {
        return cls == this
                || cls.getSuperclass() != null && this.isAssignableFrom(cls.getSuperclass())
                || Arrays.stream(cls.getInterfaces()).anyMatch(this::isAssignableFrom);
    }

    @Override
    public boolean isAssignableFrom(Class<?> cls) {
        return isAssignableFrom(MetaprogrammingImpl.findClass(cls));
    }

    @Override
    public ReflectMethod[] getDeclaredMethods() {
        resolve();
        if (classReader == null) {
            return new ReflectMethod[0];
        }
        return classReader.getMethods().stream()
                .filter(method -> !method.getName().equals("<clinit>"))
                .map(method -> getDeclaredMethod(method.getDescriptor()))
                .toArray(ReflectMethod[]::new);
    }

    @Override
    public ReflectMethod[] getMethods() {
        resolve();
        if (classReader == null) {
            return new ReflectMethod[0];
        }
        if (methodsCache == null) {
            Set<String> visited = new HashSet<>();
            methodsCache = context.getClassSource().getAncestors(classReader.getName())
                    .flatMap(cls -> cls.getMethods().stream())
                    .filter(method -> !method.getName().equals("<clinit>"))
                    .filter(method -> visited.add(method.getDescriptor().toString()))
                    .map(method -> context.getClass(ValueType.object(method.getOwnerName()))
                            .getDeclaredMethod(method.getDescriptor()))
                    .filter(Objects::nonNull)
                    .toArray(ReflectMethod[]::new);
        }
        return methodsCache.clone();
    }

    @Override
    public ReflectMethod getDeclaredMethod(String name, ReflectClass<?>... parameterTypes) {
        resolve();
        if (classReader == null) {
            return null;
        }

        ValueType[] internalParameterTypes = Arrays.stream(parameterTypes)
                .map(type -> ((ReflectClassImpl<?>) type).type)
                .toArray(ValueType[]::new);
        String key = name + "(" + ValueType.manyToString(internalParameterTypes) + ")";
        return declaredMethods.computeIfAbsent(key, k -> {
            MethodReader candidate = null;
            for (MethodReader method : classReader.getMethods()) {
                if (!method.getName().equals(name)) {
                    continue;
                }
                if (!Arrays.equals(method.getParameterTypes(), internalParameterTypes)) {
                    continue;
                }
                if (candidate == null) {
                    candidate = method;
                } else {
                    boolean moreSpecial = context.getHierarchy().isSuperType(candidate.getResultType(),
                            method.getResultType(), false);
                    if (moreSpecial) {
                        candidate = method;
                    }
                }
            }

            return candidate != null ? getDeclaredMethod(candidate.getDescriptor()) : null;
        });
    }

    private ReflectMethodImpl getDeclaredMethod(MethodDescriptor method) {
        resolve();
        return methods.computeIfAbsent(method, m -> {
            MethodReader methodReader = classReader.getMethod(m);
            return methodReader != null ? new ReflectMethodImpl(this, methodReader) : null;
        });
    }

    @Override
    public ReflectMethod getDeclaredJMethod(String name, Class<?>... parameterTypes) {
        ReflectClass<?>[] mappedParamTypes = Arrays.stream(parameterTypes)
                .map(MetaprogrammingImpl::findClass)
                .toArray(ReflectClass[]::new);
        return getDeclaredMethod(name, mappedParamTypes);
    }

    @Override
    public ReflectMethod getJMethod(String name, Class<?>... parameterTypes) {
        ReflectClass<?>[] mappedParamTypes = Arrays.stream(parameterTypes)
                .map(MetaprogrammingImpl::findClass)
                .toArray(ReflectClass[]::new);
        return getMethod(name, mappedParamTypes);
    }

    @Override
    public ReflectMethod getMethod(String name, ReflectClass<?>... parameterTypes) {
        resolve();
        if (classReader == null) {
            return null;
        }

        Iterable<ClassReader> ancestors = () -> context.getClassSource().getAncestors(classReader.getName())
                .iterator();
        for (ClassReader cls : ancestors) {
            ReflectClassImpl<?> reflectClass = context.getClass(ValueType.object(cls.getName()));
            ReflectMethod method = reflectClass.getDeclaredMethod(name, parameterTypes);
            if (method != null && Modifier.isPublic(method.getModifiers())) {
                return method;
            }
        }
        return null;
    }

    @Override
    public ReflectField[] getDeclaredFields() {
        resolve();
        if (classReader == null) {
            return new ReflectField[0];
        }
        return classReader.getFields().stream()
                .map(fld -> getDeclaredField(fld.getName()))
                .toArray(ReflectField[]::new);
    }

    @Override
    public ReflectField[] getFields() {
        if (fieldsCache == null) {
            resolve();
            if (classReader == null) {
                fieldsCache = new ReflectField[0];
            } else {
                Set<String> visited = new HashSet<>();
                fieldsCache = context
                  .getClassSource()
                  .getAncestors(classReader.getName())
                  .flatMap(cls -> cls.getFields().stream().filter(fld -> fld.getLevel() == AccessLevel.PUBLIC))
                  .filter(fld -> visited.add(fld.getName()))
                  .map(fld -> context.getClass(ValueType.object(fld.getOwnerName())).getDeclaredField(fld.getName()))
                  .toArray(ReflectField[]::new);
            }
        }
        return fieldsCache.clone();
    }

    @Override
    public ReflectField getDeclaredField(String name) {
        resolve();
        return declaredFields.computeIfAbsent(name, n -> {
            FieldReader fld = classReader.getField(n);
            return fld != null ? new ReflectFieldImpl(this, fld) : null;
        });
    }

    @Override
    public ReflectField getField(String name) {
        resolve();
        if (classReader == null) {
            return null;
        }
        FieldReader fieldReader = classReader.getField(name);
        return fieldReader != null && fieldReader.getLevel() == AccessLevel.PUBLIC
                ? getDeclaredField(name)
                : null;
    }

    @Override
    public <S extends Annotation> S getAnnotation(Class<S> type) {
        resolve();
        if (classReader == null) {
            return null;
        }
        if (annotations == null) {
            annotations = new ReflectAnnotatedElementImpl(context, classReader.getAnnotations());
        }
        return annotations.getAnnotation(type);
    }

    public void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        if (!(type instanceof ValueType.Object)) {
            return;
        }

        String className = ((ValueType.Object) type).getClassName();
        classReader = context.getClassSource().get(className);
    }

    @Override
    public String toString() {
        if (isArray()) {
            return getComponentType().toString() + "[]";
        } else {
            return getName();
        }
    }

    @Override
    public T[] createArray(int size) {
        throw new IllegalStateException("Don't call this method from compile domain");
    }

    @Override
    public T getArrayElement(Object array, int index) {
        throw new IllegalStateException("Don't call this method from compile domain");
    }

    @Override
    public int getArrayLength(Object array) {
        throw new IllegalStateException("Don't call this method from compile domain");
    }

    @Override
    public Class<T> asJavaClass() {
        throw new IllegalStateException("Don't call this method from compile domain");
    }

    @Override
    public Map<String, ReflectTypeVariableImpl> getTypeVariableMap() {
        if (typeVariableMap == null) {
            resolve();
            GenericTypeParameter[] parameters = classReader.getGenericParameters();
            if (parameters.length == 0) {
                typeVariableMap = ReflectContext.EMPTY_TYPE_VARIABLES;
            } else {
                typeVariableMap = new LinkedHashMap<>();
                for (GenericTypeParameter parameter : parameters) {
                    GenericValueType.Reference[] interfaceBounds = parameter.getInterfaceBounds();
                    GenericValueType.Reference classBound = parameter.getClassBound();
                    GenericValueType.Reference[] bounds;
                    if (classBound != null) {
                        bounds = new GenericValueType.Reference[interfaceBounds.length + 1];
                        bounds[0] = classBound;
                        System.arraycopy(interfaceBounds, 0, bounds, 1, interfaceBounds.length);
                    } else {
                        bounds = interfaceBounds;
                    }
                    ReflectTypeVariableImpl variable = new ReflectTypeVariableImpl(context, this,
                            parameter.getName(), bounds);
                    typeVariableMap.put(parameter.getName(), variable);
                }
            }
        }
        return typeVariableMap;
    }

    @Override
    public ReflectClassImpl<?> getDeclaringClass() {
        resolve();
        if (classReader == null) {
            return null;
        }
        String ownerName = classReader.getOwnerName();
        if (ownerName == null) {
            return null;
        }
        return context.getClass(ValueType.object(ownerName));
    }
}
