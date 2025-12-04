package hu.blackbelt.judo.zeta.validation.core;

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

import java.util.Objects;

/**
 * Immutable value object representing the result of a validation rule.
 *
 * <p>A validation result indicates whether validation passed or failed,
 * and includes metadata about the constraint and any failure message.</p>
 */
public final class ValidationResult {
    private final boolean passed;
    private final String constraintName;
    private final String message;
    private final Severity severity;
    private final EObject context;

    private ValidationResult(boolean passed, String constraintName, String message, Severity severity, EObject context) {
        this.passed = passed;
        this.constraintName = constraintName;
        this.message = message;
        this.severity = severity;
        this.context = context;
    }

    /**
     * Create a passing validation result.
     *
     * @return a passing result
     */
    public static ValidationResult pass() {
        return new ValidationResult(true, null, null, null, null);
    }

    /**
     * Create a failing validation result with an error message.
     *
     * @param message the error message
     * @return a failing result
     */
    public static ValidationResult fail(String message) {
        return new ValidationResult(false, null, message, Severity.ERROR, null);
    }

    /**
     * Create a failing validation result with full metadata.
     *
     * @param constraintName the name of the failed constraint
     * @param message the error message
     * @param severity the severity level
     * @param context the element that failed validation
     * @return a failing result
     */
    public static ValidationResult fail(String constraintName, String message, Severity severity, EObject context) {
        return new ValidationResult(false, constraintName, message, severity, context);
    }

    /**
     * Create a warning validation result.
     *
     * @param message the warning message
     * @return a warning result
     */
    public static ValidationResult warn(String message) {
        return new ValidationResult(false, null, message, Severity.WARNING, null);
    }

    /**
     * Create a warning validation result with full metadata.
     *
     * @param constraintName the name of the critique
     * @param message the warning message
     * @param context the element that triggered the warning
     * @return a warning result
     */
    public static ValidationResult warn(String constraintName, String message, EObject context) {
        return new ValidationResult(false, constraintName, message, Severity.WARNING, context);
    }

    public boolean isPassed() {
        return passed;
    }

    public boolean isFailed() {
        return !passed;
    }

    public String getConstraintName() {
        return constraintName;
    }

    public String getMessage() {
        return message;
    }

    public Severity getSeverity() {
        return severity;
    }

    public EObject getContext() {
        return context;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ValidationResult that = (ValidationResult) o;
        return passed == that.passed &&
                Objects.equals(constraintName, that.constraintName) &&
                Objects.equals(message, that.message) &&
                severity == that.severity &&
                Objects.equals(context, that.context);
    }

    @Override
    public int hashCode() {
        return Objects.hash(passed, constraintName, message, severity, context);
    }

    @Override
    public String toString() {
        if (passed) {
            return "ValidationResult{passed=true}";
        }
        return "ValidationResult{" +
                "passed=false" +
                ", constraintName='" + constraintName + '\'' +
                ", message='" + message + '\'' +
                ", severity=" + severity +
                ", context=" + context +
                '}';
    }
}
