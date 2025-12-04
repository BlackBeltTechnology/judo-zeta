package hu.blackbelt.judo.zeta.validation;

/*-
 * #%L
 * Judo :: Zeta :: Validation Core
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
import org.eclipse.emf.ecore.resource.ResourceSet;
import java.util.Collection;

/**
 * Provides model traversal operations for validation.
 *
 * <p>Each metamodel implements this interface to integrate with the validation framework.
 * This abstraction allows the validation core to work with any EMF-based metamodel
 * without direct dependencies on metamodel-specific utility classes.</p>
 *
 * <p>Example implementation for Zeta:</p>
 * <pre>
 * public class EsmModelProvider implements ModelProvider {
 *     {@literal @}Override
 *     public &lt;T extends EObject&gt; Collection&lt;T&gt; getAllContents(ResourceSet rs, Class&lt;T&gt; type) {
 *         return EsmUtils.getAllContents(rs, type);
 *     }
 * }
 * </pre>
 */
public interface ModelProvider {

    /**
     * Get all instances of a given type from the resource set.
     *
     * @param resourceSet the resource set containing the model
     * @param type the EObject type to find
     * @param <T> the type parameter
     * @return collection of matching instances
     */
    <T extends EObject> Collection<T> getAllContents(ResourceSet resourceSet, Class<T> type);

    /**
     * Get the name of a named element, or null if not applicable.
     * Used for error message interpolation.
     *
     * <p>Default implementation uses reflection to call getName() method.</p>
     *
     * @param element the element
     * @return element name or null
     */
    default String getName(EObject element) {
        try {
            var method = element.getClass().getMethod("getName");
            return (String) method.invoke(element);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get a human-readable type name for an element.
     * Used for error message interpolation.
     *
     * <p>Default implementation returns the EClass name.</p>
     *
     * @param element the element
     * @return type name
     */
    default String getTypeName(EObject element) {
        return element.eClass().getName();
    }
}
