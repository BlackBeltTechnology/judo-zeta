package hu.blackbelt.judo.zeta.validation;

/*-
 * #%L
 * Judo :: Zeta :: Validation Core
 * %%
 * Copyright (C) 2018 - 2025 BlackBelt Technology
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

import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Simple model provider for tests.
 * Provides basic model traversal operations needed by ValidationContext.
 */
public class TestModelProvider implements hu.blackbelt.judo.zeta.validation.ModelProvider {

    @Override
    public <T extends EObject> Collection<T> getAllContents(ResourceSet resourceSet, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Resource resource : resourceSet.getResources()) {
            // Iterate through all elements in the resource tree
            // Note: resource.getAllContents() DOES include root elements
            TreeIterator<EObject> iterator = resource.getAllContents();
            while (iterator.hasNext()) {
                EObject obj = iterator.next();
                if (type.isInstance(obj)) {
                    result.add(type.cast(obj));
                }
            }
        }
        return result;
    }
}
