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

import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks first-call semantics for ETL-compatible {@code equivalentDiscriminated} behavior.
 *
 * <p>In ETL's {@code id.eol}, the first caller of {@code equivalentDiscriminated()} for a given
 * {@code (source, ruleName)} pair receives the ORIGINAL object. Subsequent callers
 * receive CLONES of the original's current state. This class tracks which
 * {@code (source, ruleName)} pairs have been accessed for the first time.</p>
 */
public class OriginalTracker {

    private final ConcurrentHashMap<OriginalKey, String> firstCallRegistry = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<OriginalKey, String> baseIdRegistry = new ConcurrentHashMap<>();

    record OriginalKey(int sourceIdentity, String ruleName) {
        static OriginalKey of(EObject source, String ruleName) {
            return new OriginalKey(System.identityHashCode(source), ruleName);
        }
    }

    /**
     * Result of checking whether a call is the first for a given (source, ruleName) pair.
     *
     * @param isFirstCall true if this was the first call
     * @param firstDiscriminator the discriminator used by the first call
     */
    public record FirstCallResult(boolean isFirstCall, String firstDiscriminator) {}

    /**
     * Check if this is the first call for the given (source, ruleName) pair
     * and register the discriminator if so.
     *
     * @param source the source element
     * @param ruleName the rule name
     * @param discriminator the discriminator being used
     * @return result indicating if this was the first call
     */
    public FirstCallResult checkAndRegisterFirstCall(EObject source, String ruleName, String discriminator) {
        OriginalKey key = OriginalKey.of(source, ruleName);
        String existing = firstCallRegistry.putIfAbsent(key, discriminator);
        if (existing == null) {
            return new FirstCallResult(true, discriminator);
        } else {
            return new FirstCallResult(false, existing);
        }
    }

    /**
     * Store the base ID of the original before any discriminator suffix was applied.
     * Used by subsequent callers to generate their own discriminated IDs.
     */
    public void registerBaseId(EObject source, String ruleName, String baseId) {
        OriginalKey key = OriginalKey.of(source, ruleName);
        baseIdRegistry.putIfAbsent(key, baseId);
    }

    /**
     * Get the stored base ID for a (source, ruleName) pair.
     *
     * @return the base ID, or null if not registered
     */
    public String getBaseId(EObject source, String ruleName) {
        return baseIdRegistry.get(OriginalKey.of(source, ruleName));
    }

    /**
     * Reset tracking (for executor reuse).
     */
    public void clear() {
        firstCallRegistry.clear();
        baseIdRegistry.clear();
    }
}
