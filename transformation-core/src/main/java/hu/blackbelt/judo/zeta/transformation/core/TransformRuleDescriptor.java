package hu.blackbelt.judo.zeta.transformation.core;

/*-
 * #%L
 * Judo :: Zeta :: Transformation Core
 * %%
 * Copyright (C) 2018 - 2024 BlackBelt Technology
 * %%
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * with the GNU Classpath Exception which is
 * available at https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 * #L%
 */

import org.eclipse.emf.ecore.EObject;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * Metadata descriptor for a transformation rule.
 *
 * <p>Holds information about the rule annotation, guard method,
 * dependencies, and provides rule invocation capability.</p>
 */
public class TransformRuleDescriptor {

    private final Object instance;
    private final Method ruleMethod;
    private final String name;
    private final String description;
    private final Class<? extends EObject> sourceType;
    private final Class<? extends EObject> targetType;
    private final Method guardMethod;
    private final boolean isLazy;
    private final boolean isAbstract;
    private final boolean isPrimary;
    private final boolean isGreedy;
    private final List<String> extendsRules;

    /**
     * List of source type definitions from @Transform annotations.
     */
    private final List<TransformDefinition> transforms;

    /**
     * List of target type definitions from @To annotations.
     */
    private final List<ToDefinition> tos;

    private TransformFunction<EObject, EObject> cachedFunction;
    private MultiSourceTransformFunction<EObject> cachedMultiSourceFunction;
    private TransformGuard cachedGuard;
    private MultiSourceTransformGuard cachedMultiSourceGuard;
    private Boolean isMultiSourceGuard;

    public TransformRuleDescriptor(
            Object instance,
            Method ruleMethod,
            String name,
            String description,
            Class<? extends EObject> sourceType,
            Class<? extends EObject> targetType,
            Method guardMethod,
            boolean isLazy,
            boolean isAbstract,
            boolean isPrimary,
            boolean isGreedy,
            List<String> extendsRules
    ) {
        this(instance, ruleMethod, name, description, sourceType, targetType, guardMethod,
                isLazy, isAbstract, isPrimary, isGreedy, extendsRules,
                Collections.emptyList(), Collections.emptyList());
    }

    public TransformRuleDescriptor(
            Object instance,
            Method ruleMethod,
            String name,
            String description,
            Class<? extends EObject> sourceType,
            Class<? extends EObject> targetType,
            Method guardMethod,
            boolean isLazy,
            boolean isAbstract,
            boolean isPrimary,
            boolean isGreedy,
            List<String> extendsRules,
            List<TransformDefinition> transforms,
            List<ToDefinition> tos
    ) {
        this.instance = instance;
        this.ruleMethod = ruleMethod;
        this.name = name;
        this.description = description;
        this.sourceType = sourceType;
        this.targetType = targetType;
        this.guardMethod = guardMethod;
        this.isLazy = isLazy;
        this.isAbstract = isAbstract;
        this.isPrimary = isPrimary;
        this.isGreedy = isGreedy;
        this.extendsRules = extendsRules;
        this.transforms = transforms != null ? transforms : Collections.emptyList();
        this.tos = tos != null ? tos : Collections.emptyList();

        ruleMethod.setAccessible(true);
        if (guardMethod != null) {
            guardMethod.setAccessible(true);
        }
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Class<? extends EObject> getSourceType() {
        return sourceType;
    }

    public Class<? extends EObject> getTargetType() {
        return targetType;
    }

    public boolean isLazy() {
        return isLazy;
    }

    public boolean isAbstract() {
        return isAbstract;
    }

    public boolean isPrimary() {
        return isPrimary;
    }

    public boolean isGreedy() {
        return isGreedy;
    }

    public List<String> getExtendsRules() {
        return extendsRules;
    }

    /**
     * Get the list of source type definitions from @Transform annotations.
     *
     * @return list of TransformDefinition (may be empty for backward compatibility)
     */
    public List<TransformDefinition> getTransforms() {
        return transforms;
    }

    /**
     * Get the list of target type definitions from @To annotations.
     *
     * @return list of ToDefinition (may be empty for backward compatibility)
     */
    public List<ToDefinition> getTos() {
        return tos;
    }

    /**
     * Check if this rule uses multiple source types (Cartesian product).
     *
     * <p>A rule is multi-source when it has more than one @Transform annotation,
     * requiring the executor to generate a Cartesian product of elements from
     * each source alias.</p>
     *
     * @return true if the rule has multiple @Transform annotations
     */
    public boolean isMultiSource() {
        return transforms.size() > 1;
    }

    /**
     * Get the number of source types for this rule.
     *
     * <p>For single-source rules (backward compatible), returns 1.
     * For multi-source rules, returns the number of @Transform annotations.</p>
     *
     * @return the number of source types
     */
    public int getSourceCount() {
        return transforms.isEmpty() ? 1 : transforms.size();
    }

    /**
     * Check if this rule applies to the given source element.
     */
    public boolean appliesTo(EObject source) {
        if (isGreedy) {
            // Kind-of semantics: check if source is instance of sourceType or subtype
            return sourceType.isInstance(source);
        } else {
            // Type-of semantics: exact type match only
            return sourceType.equals(source.getClass()) || 
                   sourceType.isInstance(source) && source.getClass().getSuperclass() != null;
        }
    }

    /**
     * Evaluate the guard for this rule (single-source).
     *
     * @param source the source element
     * @param context the transformation context
     * @return true if the guard passes (or no guard), false otherwise
     */
    public boolean evaluateGuard(EObject source, TransformationContext context) {
        TransformGuard guard = getGuard();
        return guard == null || guard.evaluate(source, context);
    }

    /**
     * Evaluate the guard for this rule (multi-source).
     *
     * <p>For multi-source rules with Cartesian product, passes all source elements
     * to the guard for evaluation. The guard can decide based on the combination
     * of all sources.</p>
     *
     * @param sources array of source elements from Cartesian product tuple
     * @param context the transformation context
     * @return true if the guard passes (or no guard), false otherwise
     */
    public boolean evaluateGuard(EObject[] sources, TransformationContext context) {
        if (guardMethod == null) {
            return true;
        }
        
        // Check if guard supports multi-source signature
        if (isMultiSourceGuard()) {
            MultiSourceTransformGuard guard = getMultiSourceGuard();
            return guard.evaluate(sources, context);
        } else {
            // Fall back to single-source guard with first element
            TransformGuard guard = getGuard();
            return guard == null || guard.evaluate(sources[0], context);
        }
    }

    /**
     * Check if the guard method supports multi-source signature.
     *
     * <p>Detects guard signature by checking parameter types:
     * <ul>
     *   <li>{@code (EObject[], TransformationContext)} → multi-source</li>
     *   <li>{@code (EObject, TransformationContext)} → single-source</li>
     * </ul></p>
     *
     * @return true if the guard accepts EObject[] as first parameter
     */
    private boolean isMultiSourceGuard() {
        if (isMultiSourceGuard == null) {
            if (guardMethod == null) {
                isMultiSourceGuard = false;
            } else {
                Class<?>[] paramTypes = guardMethod.getParameterTypes();
                isMultiSourceGuard = paramTypes.length >= 1 && paramTypes[0].isArray();
            }
        }
        return isMultiSourceGuard;
    }

    /**
     * Get the transform function for single-source rules (lazily initialized).
     *
     * @throws IllegalStateException if called on a multi-source rule
     */
    @SuppressWarnings("unchecked")
    public TransformFunction<EObject, EObject> getFunction() {
        if (isMultiSource()) {
            throw new IllegalStateException(
                    "Cannot get single-source function for multi-source rule: " + name + 
                    ". Use getMultiSourceFunction() instead.");
        }
        if (cachedFunction == null) {
            try {
                cachedFunction = (TransformFunction<EObject, EObject>) ruleMethod.invoke(instance);
            } catch (Exception e) {
                throw new RuntimeException("Failed to get transform function: " + name, e);
            }
        }
        return cachedFunction;
    }

    /**
     * Get the transform function for multi-source rules (lazily initialized).
     *
     * <p>Used when the rule has multiple @Transform annotations and receives
     * an array of source elements from the Cartesian product.</p>
     *
     * @return the multi-source transform function
     * @throws IllegalStateException if called on a single-source rule
     */
    @SuppressWarnings("unchecked")
    public MultiSourceTransformFunction<EObject> getMultiSourceFunction() {
        if (!isMultiSource()) {
            throw new IllegalStateException(
                    "Cannot get multi-source function for single-source rule: " + name + 
                    ". Use getFunction() instead.");
        }
        if (cachedMultiSourceFunction == null) {
            try {
                cachedMultiSourceFunction = (MultiSourceTransformFunction<EObject>) ruleMethod.invoke(instance);
            } catch (Exception e) {
                throw new RuntimeException("Failed to get multi-source transform function: " + name, e);
            }
        }
        return cachedMultiSourceFunction;
    }

    /**
     * Get the guard for single-source rules (lazily initialized).
     */
    public TransformGuard getGuard() {
        if (guardMethod == null) {
            return null;
        }

        if (cachedGuard == null) {
            cachedGuard = (source, ctx) -> {
                try {
                    return (Boolean) guardMethod.invoke(instance, source, ctx);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to evaluate guard for: " + name, e);
                }
            };
        }

        return cachedGuard;
    }

    /**
     * Get the guard for multi-source rules (lazily initialized).
     *
     * <p>Used when the guard method signature accepts {@code EObject[]} as the first parameter.</p>
     *
     * @return the multi-source guard, or null if no guard method
     */
    public MultiSourceTransformGuard getMultiSourceGuard() {
        if (guardMethod == null) {
            return null;
        }

        if (cachedMultiSourceGuard == null) {
            cachedMultiSourceGuard = (sources, ctx) -> {
                try {
                    return (Boolean) guardMethod.invoke(instance, sources, ctx);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to evaluate multi-source guard for: " + name, e);
                }
            };
        }

        return cachedMultiSourceGuard;
    }

    /**
     * Execute this rule on a single source element.
     *
     * @param source the source element
     * @param context the transformation context
     * @return the target element
     * @throws IllegalStateException if called on a multi-source rule
     */
    public EObject execute(EObject source, TransformationContext context) {
        return getFunction().transform(source, context);
    }

    /**
     * Execute this rule on multiple source elements (Cartesian product tuple).
     *
     * @param sources array of source elements from Cartesian product
     * @param context the transformation context
     * @return the target element
     * @throws IllegalStateException if called on a single-source rule
     */
    public EObject execute(EObject[] sources, TransformationContext context) {
        return getMultiSourceFunction().transform(sources, context);
    }

    @Override
    public String toString() {
        return "TransformRuleDescriptor{" +
                "name='" + name + '\'' +
                ", sourceType=" + sourceType.getSimpleName() +
                ", targetType=" + targetType.getSimpleName() +
                ", isLazy=" + isLazy +
                ", isAbstract=" + isAbstract +
                ", isPrimary=" + isPrimary +
                ", isGreedy=" + isGreedy +
                '}';
    }
}
