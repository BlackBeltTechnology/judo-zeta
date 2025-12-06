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
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Method-level annotation that enables caching for extension methods.
 *
 * <p>Cached extension methods have their results stored based on the target object (self)
 * and method arguments. This is equivalent to EOL's {@code @cached} operation annotation.</p>
 *
 * <p>Cache keys are computed from:</p>
 * <ul>
 *   <li>EObject: XMI ID (via Resource or EcoreUtil.getURI)</li>
 *   <li>Primitives: value directly (String, Number, Boolean, Enum)</li>
 *   <li>Collection: ordered list of key parts from elements</li>
 *   <li>Map: sorted by key, then process entries as key-value pairs</li>
 * </ul>
 *
 * <p>Example usage:</p>
 * <pre>
 * {@code
 * @ExtensionMethod(EntityType.class)
 * public class EntityTypeExtensions {
 *
 *     @Cached
 *     public Collection<Class> getAllSuperTypes(EntityType self) {
 *         // Expensive computation cached per EntityType instance
 *         return self.getGeneralizations().stream()
 *             .map(Generalization::getTarget)
 *             .flatMap(t -> Stream.concat(Stream.of(t), getAllSuperTypes(t).stream()))
 *             .collect(Collectors.toList());
 *     }
 * }
 * }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Cached {
}
