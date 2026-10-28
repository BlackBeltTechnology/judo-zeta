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
 * Runtime exception thrown when a transformation fails.
 *
 * <p>This exception wraps transformation failures with contextual information
 * about the failed element and rule for easier debugging.</p>
 */
public class TransformationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient EObject failedElement;
    private final String ruleName;

    /**
     * Create a transformation exception with a message.
     *
     * @param message the error message
     */
    public TransformationException(String message) {
        super(message);
        this.failedElement = null;
        this.ruleName = null;
    }

    /**
     * Create a transformation exception with a message and cause.
     *
     * @param message the error message
     * @param cause the underlying cause
     */
    public TransformationException(String message, Throwable cause) {
        super(message, cause);
        this.failedElement = null;
        this.ruleName = null;
    }

    /**
     * Create a transformation exception with full context.
     *
     * @param message the error message
     * @param cause the underlying cause
     * @param failedElement the element that caused the failure
     * @param ruleName the rule that failed
     */
    public TransformationException(String message, Throwable cause, EObject failedElement, String ruleName) {
        super(message, cause);
        this.failedElement = failedElement;
        this.ruleName = ruleName;
    }

    /**
     * Create a transformation exception with element and rule context.
     *
     * @param message the error message
     * @param failedElement the element that caused the failure
     * @param ruleName the rule that failed
     */
    public TransformationException(String message, EObject failedElement, String ruleName) {
        super(message);
        this.failedElement = failedElement;
        this.ruleName = ruleName;
    }

    /**
     * Get the element that caused the failure, if available.
     *
     * @return the failed element, or null if not available
     */
    public EObject getFailedElement() {
        return failedElement;
    }

    /**
     * Get the rule name that failed, if available.
     *
     * @return the rule name, or null if not available
     */
    public String getRuleName() {
        return ruleName;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(super.toString());
        if (ruleName != null) {
            sb.append(" [rule: ").append(ruleName).append("]");
        }
        if (failedElement != null) {
            sb.append(" [element: ").append(failedElement.eClass().getName()).append("]");
        }
        return sb.toString();
    }
}
