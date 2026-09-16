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

/**
 * Represents a single rule invocation in the transformation call stack.
 *
 * <p>This record captures the context of a rule execution, enabling:
 * <ul>
 *   <li>Debugging and tracing of transformation call chains</li>
 *   <li>Context-aware discriminator resolution</li>
 *   <li>Understanding how elements were created (call path)</li>
 * </ul>
 *
 * <p>Example call chain for an ActionDefinition creation:</p>
 * <pre>
 * [0] TransferObjectFormButtonGroup (source=TransferObjectForm)
 * [1] OperationFormCallButton (source=OperationForm, discriminator=...)
 * [2] OperationFormCallActionDefinition (source=OperationForm, discriminator=...)
 * </pre>
 *
 * @param ruleName the name of the rule being executed
 * @param source the source element being transformed
 * @param discriminator the discriminator value used (null if not discriminated)
 */
public record RuleInvocation(
        String ruleName,
        EObject source,
        String discriminator
) {
    /**
     * Create a rule invocation without a discriminator.
     *
     * @param ruleName the rule name
     * @param source the source element
     * @return a new RuleInvocation
     */
    public static RuleInvocation of(String ruleName, EObject source) {
        return new RuleInvocation(ruleName, source, null);
    }

    /**
     * Create a rule invocation with a discriminator.
     *
     * @param ruleName the rule name
     * @param source the source element
     * @param discriminator the discriminator value
     * @return a new RuleInvocation
     */
    public static RuleInvocation of(String ruleName, EObject source, String discriminator) {
        return new RuleInvocation(ruleName, source, discriminator);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(ruleName);
        if (source != null) {
            sb.append(" (source=").append(source.eClass().getName()).append(")");
        }
        if (discriminator != null) {
            sb.append(" [disc=").append(discriminator).append("]");
        }
        return sb.toString();
    }
}
