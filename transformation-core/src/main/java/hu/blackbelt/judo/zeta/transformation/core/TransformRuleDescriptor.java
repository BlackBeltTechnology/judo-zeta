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

    private TransformFunction<EObject, EObject> cachedFunction;
    private TransformGuard cachedGuard;

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
     * Evaluate the guard for this rule.
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
     * Get the transform function (lazily initialized).
     */
    @SuppressWarnings("unchecked")
    public TransformFunction<EObject, EObject> getFunction() {
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
     * Get the guard (lazily initialized).
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
     * Execute this rule on the source element.
     *
     * @param source the source element
     * @param context the transformation context
     * @return the target element
     */
    public EObject execute(EObject source, TransformationContext context) {
        return getFunction().transform(source, context);
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
