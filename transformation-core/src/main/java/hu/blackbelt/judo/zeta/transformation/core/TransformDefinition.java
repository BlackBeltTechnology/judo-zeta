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

import java.util.Objects;

/**
 * Represents a source type definition for a transformation rule.
 * Contains the resource alias and element type from a @Transform annotation.
 */
public final class TransformDefinition {
    private final String alias;
    private final Class<? extends EObject> type;

    /**
     * Create a new TransformDefinition.
     *
     * @param alias the resource alias
     * @param type the element type
     */
    public TransformDefinition(String alias, Class<? extends EObject> type) {
        this.alias = Objects.requireNonNull(alias, "alias cannot be null");
        this.type = Objects.requireNonNull(type, "type cannot be null");
    }

    /**
     * Get the resource alias.
     *
     * @return the alias
     */
    public String getAlias() {
        return alias;
    }

    /**
     * Get the element type.
     *
     * @return the type
     */
    public Class<? extends EObject> getType() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TransformDefinition that = (TransformDefinition) o;
        return Objects.equals(alias, that.alias) && Objects.equals(type, that.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(alias, type);
    }

    @Override
    public String toString() {
        return alias + "!" + type.getSimpleName();
    }
}
