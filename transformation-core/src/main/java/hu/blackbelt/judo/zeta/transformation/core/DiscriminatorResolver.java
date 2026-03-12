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

import java.util.List;

/**
 * Resolves discriminator values based on the current transformation context.
 *
 * <p>This interface enables context-aware discriminator resolution, allowing
 * the framework to automatically determine appropriate discriminator values
 * based on the call chain. This is particularly useful for achieving ETL
 * compatibility where different calling contexts require different discriminators.</p>
 *
 * <h2>Cache Modes</h2>
 * <p>The resolver can return a {@link Resolution} that specifies the cache mode:</p>
 * <ul>
 *   <li><b>SOURCE_BASED</b> (default): Cache key is (source, ruleName, discriminator).
 *       Different source objects produce different cache entries even with same discriminator.</li>
 *   <li><b>DISCRIMINATOR_ONLY</b>: Cache key is only (ruleName, discriminator).
 *       Different source objects with same discriminator share the same target instance.
 *       This matches ETL's string-based caching semantics.</li>
 * </ul>
 *
 * <h2>Use Case: ActionDefinition Sharing</h2>
 * <p>In ETL, ActionDefinitions are shared between Button and Action contexts
 * by using the same discriminator. The discriminator is based on the TransferObjectForm
 * when called from TransferObjectFormButtonGroup context, and the same TransferObjectForm
 * (via input form lookup) when called from OperationUnmappedCallOperationAction.</p>
 *
 * <h2>Example Implementation</h2>
 * <pre>{@code
 * ctx.setDiscriminatorResolver((source, ruleName, callChain) -> {
 *     if (ruleName.equals("OperationFormCallActionDefinition")) {
 *         // Check if called from TransferObjectFormButtonGroup context
 *         for (RuleInvocation inv : callChain) {
 *             if (inv.ruleName().equals("TransferObjectFormButtonGroup")) {
 *                 TransferObjectForm form = (TransferObjectForm) inv.source();
 *                 String disc = actorType.getName() + "/(esm/" + getId(form) + ")/TransferObjectForm";
 *                 // Use DISCRIMINATOR_ONLY mode for ETL-compatible sharing
 *                 return Resolution.discriminatorOnlyCache(disc);
 *             }
 *         }
 *         // Default: use OperationForm-based discriminator with source-based cache
 *         OperationForm opForm = (OperationForm) source;
 *         return Resolution.sourceBasedCache(actorType.getName() + "/(esm/" + getId(opForm) + ")/OperationForm");
 *     }
 *     return null; // Use explicit discriminator for other rules
 * });
 * }</pre>
 *
 * @see RuleInvocation
 * @see TransformationContext#setDiscriminatorResolver(DiscriminatorResolver)
 */
@FunctionalInterface
public interface DiscriminatorResolver {

    /**
     * Resolution result containing discriminator and cache mode configuration.
     *
     * <p>When {@code useDiscriminatorOnlyCache} is true, the framework uses only
     * (ruleName, discriminator) as the cache key, ignoring source object identity.
     * This enables sharing instances across different source objects that resolve
     * to the same discriminator - matching ETL's string-based cache semantics.</p>
     */
    record Resolution(String discriminator, boolean useDiscriminatorOnlyCache) {

        /**
         * Create resolution with source-based caching (default ZETA behavior).
         * Cache key: (source, ruleName, discriminator)
         *
         * @param discriminator the discriminator value
         * @return resolution with source-based caching
         */
        public static Resolution sourceBasedCache(String discriminator) {
            return new Resolution(discriminator, false);
        }

        /**
         * Create resolution with discriminator-only caching (ETL-compatible).
         * Cache key: (ruleName, discriminator) - ignores source object identity.
         *
         * <p>Use this when multiple source objects should share the same target
         * instance, identified only by the discriminator value.</p>
         *
         * @param discriminator the discriminator value
         * @return resolution with discriminator-only caching
         */
        public static Resolution discriminatorOnlyCache(String discriminator) {
            return new Resolution(discriminator, true);
        }
    }

    /**
     * Resolve the discriminator value and cache mode for a given rule invocation context.
     *
     * <p>This method is called by {@code equivalentDiscriminated()} when no explicit
     * discriminator is provided (discriminator parameter is null). The resolver can
     * inspect the call chain to determine the appropriate discriminator and cache mode
     * based on how the rule was reached.</p>
     *
     * @param source the source element being transformed
     * @param ruleName the name of the rule being invoked
     * @param callChain the current rule invocation stack (most recent first)
     *                  showing how this rule was reached
     * @return Resolution containing discriminator and cache mode, or null for default behavior
     */
    default Resolution resolve(EObject source, String ruleName, List<RuleInvocation> callChain) {
        String disc = resolveDiscriminator(source, ruleName, callChain);
        return disc != null ? Resolution.sourceBasedCache(disc) : null;
    }

    /**
     * Resolve the discriminator value for a given rule invocation context.
     *
     * <p>This method is called by {@code equivalentDiscriminated()} when no explicit
     * discriminator is provided (discriminator parameter is null). The resolver can
     * inspect the call chain to determine the appropriate discriminator based on
     * how the rule was reached.</p>
     *
     * @param source the source element being transformed
     * @param ruleName the name of the rule being invoked
     * @param callChain the current rule invocation stack (most recent first)
     *                  showing how this rule was reached
     * @return the resolved discriminator value, or null to use default behavior
     *         (which typically generates a discriminator from the source element)
     * @deprecated Use {@link #resolve(EObject, String, List)} instead for cache mode control.
     */
    @Deprecated
    String resolveDiscriminator(EObject source, String ruleName, List<RuleInvocation> callChain);
}
