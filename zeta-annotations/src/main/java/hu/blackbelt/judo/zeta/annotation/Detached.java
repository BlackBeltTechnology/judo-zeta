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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a lazy rule whose output should NOT be added to Resource.contents.
 * The caller is responsible for adding the object to its proper container.
 *
 * <p>Use case: Objects like Action and ActionDefinition that should only exist
 * within a parent container (e.g., PageDefinition.actions), not at the resource root.</p>
 *
 * <p>ETL semantics: In ETL, lazy rules don't automatically add to resource.
 * The caller adds the result to the appropriate container. This annotation
 * provides the same behavior in ZETA.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * @TransformRule(name = "TableRowCallAction")
 * @Lazy
 * @Detached  // Output NOT added to Resource.contents
 * public TransformFunction<OperationForm, Action> tableRowCallAction() {
 *     return (source, ctx) -> {
 *         Action target = ctx.createTarget(Action.class);
 *         // Will NOT be added to Resource because @Detached
 *         target.setName(fqName + "::TableRowCallAction");
 *         return target;
 *     };
 * }
 * }</pre>
 *
 * <p>The caller then adds to container:</p>
 * <pre>{@code
 * Action action = ctx.equivalentDiscriminated(source, Action.class,
 *     "TableRowCallAction", discriminator);
 * action.setName(action.getName() + "::" + relation.getName());
 * page.getActions().add(action);  // Caller adds to container
 * }</pre>
 *
 * @see Lazy
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface Detached {
}
