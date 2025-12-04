package hu.blackbelt.judo.zeta.validation.annotation;

/*-
 * #%L
 * Judo :: Zeta :: Validation Core
 * %%
 * Copyright (C) 2018 - 2022 BlackBelt Technology
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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level annotation that defines the EClass type this extension method class extends.
 *
 * <p>Extension methods provide additional behavior for EClass types, similar to EOL operations.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * {@code
 * @ExtensionMethod(EntityType.class)
 * public class EntityTypeExtensions {
 *     @Cached
 *     public Collection<Class> getAllSuperTypes(EntityType self) {
 *         // Extension method logic with caching
 *     }
 * }
 * }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ExtensionMethod {
    /**
     * The EClass type that this extension method class extends.
     *
     * @return the EClass type
     */
    Class<? extends EObject> value();
}
