package hu.blackbelt.judo.zeta.annotation;

/*-
 * #%L
 * Judo :: Zeta :: Annotations
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

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.eclipse.emf.ecore.EObject;

/**
 * Defines a source type for a transformation rule with an optional resource alias.
 * 
 * <p>This annotation is repeatable, allowing multiple source types from different
 * aliased resources. When multiple @Transform annotations are present, the rule
 * receives a Cartesian product of all source types.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * // Single source with default alias
 * &#64;TransformRule(name = "Entity2Table")
 * &#64;Transform(type = EntityType.class)
 * &#64;To(type = Table.class)
 * public TransformFunction&lt;EntityType, Table&gt; entity2Table() { ... }
 *
 * // Multiple sources from different aliases
 * &#64;TransformRule(name = "ApplyMapping")
 * &#64;Transform(alias = "asm", type = EntityType.class)
 * &#64;Transform(alias = "mapping", type = TypeMapping.class)
 * &#64;To(alias = "rdbms", type = Table.class)
 * public TransformFunction&lt;Object[], Table&gt; applyMapping() { ... }
 * </pre>
 *
 * @see To
 * @see TransformRule
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(Transforms.class)
public @interface Transform {
    /**
     * The resource alias to read source elements from.
     * Defaults to "source".
     */
    String alias() default "source";

    /**
     * The source element type.
     */
    Class<? extends EObject> type();
}
