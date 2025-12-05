package hu.blackbelt.judo.zeta.common;

/*-
 * #%L
 * Judo :: Zeta :: Common
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

import java.util.List;
import java.util.Objects;

/**
 * Immutable cache key for extension method results and satisfies() checks.
 *
 * <p>Cache keys are composed of multiple parts representing the target object,
 * method name, and arguments. The parts are computed using type-specific strategies
 * in {@link CacheKeyBuilder}.</p>
 */
public final class CacheKey {
    private final List<Object> parts;
    private final int hashCode;

    public CacheKey(List<Object> parts) {
        this.parts = List.copyOf(parts);
        this.hashCode = Objects.hash(parts);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CacheKey cacheKey = (CacheKey) o;
        return parts.equals(cacheKey.parts);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return "CacheKey" + parts;
    }
}
