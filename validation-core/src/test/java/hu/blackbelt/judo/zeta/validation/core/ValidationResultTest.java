package hu.blackbelt.judo.zeta.validation.core;

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

import hu.blackbelt.judo.zeta.validation.TestModelFactory;
import org.eclipse.emf.ecore.EClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ValidationResult}.
 * Tests validation result creation and properties, including message verification.
 */
@DisplayName("ValidationResult Tests")
class ValidationResultTest {

    @Nested
    @DisplayName("Creation Tests")
    class CreationTests {

        @Test
        @DisplayName("Should create passing result")
        void shouldCreatePassingResult() {
            // When
            ValidationResult result = ValidationResult.pass();

            // Then
            assertTrue(result.isPassed());
            assertFalse(result.isFailed());
            assertNull(result.getMessage());
            assertNull(result.getConstraintName());
        }

        @Test
        @DisplayName("Should create failing result with message")
        void shouldCreateFailingResultWithMessage() {
            // When
            ValidationResult result = ValidationResult.fail("Error message");

            // Then
            assertFalse(result.isPassed());
            assertTrue(result.isFailed());
            assertEquals("Error message", result.getMessage());
            assertEquals(Severity.ERROR, result.getSeverity());
        }

        @Test
        @DisplayName("Should create failing result with full metadata")
        void shouldCreateFailingResultWithFullMetadata() {
            // Given
            EClass element = TestModelFactory.createEClass("TestClass");

            // When
            ValidationResult result = ValidationResult.fail(
                "TestConstraint",
                "Test error message",
                Severity.ERROR,
                element
            );

            // Then
            assertEquals("TestConstraint", result.getConstraintName());
            assertEquals("Test error message", result.getMessage());
            assertEquals(Severity.ERROR, result.getSeverity());
            assertSame(element, result.getContext());
        }

        @Test
        @DisplayName("Should create warning result")
        void shouldCreateWarningResult() {
            // When
            ValidationResult result = ValidationResult.warn("Warning message");

            // Then
            assertTrue(result.isFailed());
            assertEquals("Warning message", result.getMessage());
            assertEquals(Severity.WARNING, result.getSeverity());
        }

        @Test
        @DisplayName("Should create warning result with full metadata")
        void shouldCreateWarningResultWithFullMetadata() {
            // Given
            EClass element = TestModelFactory.createEClass("TestClass");

            // When
            ValidationResult result = ValidationResult.warn(
                "TestCritique",
                "Test warning message",
                element
            );

            // Then
            assertEquals("TestCritique", result.getConstraintName());
            assertEquals("Test warning message", result.getMessage());
            assertEquals(Severity.WARNING, result.getSeverity());
            assertSame(element, result.getContext());
        }
    }

    @Nested
    @DisplayName("State Tests")
    class StateTests {

        @Test
        @DisplayName("Passing result should have isPassed true and isFailed false")
        void passingResultShouldHaveCorrectState() {
            // Given
            ValidationResult result = ValidationResult.pass();

            // When/Then
            assertTrue(result.isPassed());
            assertFalse(result.isFailed());
        }

        @Test
        @DisplayName("Failing result should have isPassed false and isFailed true")
        void failingResultShouldHaveCorrectState() {
            // Given
            ValidationResult result = ValidationResult.fail("Error");

            // When/Then
            assertFalse(result.isPassed());
            assertTrue(result.isFailed());
        }
    }

    @Nested
    @DisplayName("Severity Tests")
    class SeverityTests {

        @Test
        @DisplayName("Error result should have ERROR severity")
        void errorResultShouldHaveErrorSeverity() {
            // Given
            ValidationResult result = ValidationResult.fail("Error message");

            // When/Then
            assertEquals(Severity.ERROR, result.getSeverity());
        }

        @Test
        @DisplayName("Warning result should have WARNING severity")
        void warningResultShouldHaveWarningSeverity() {
            // Given
            ValidationResult result = ValidationResult.warn("Warning message");

            // When/Then
            assertEquals(Severity.WARNING, result.getSeverity());
        }

        @Test
        @DisplayName("Passing result should have null severity")
        void passingResultShouldHaveNullSeverity() {
            // Given
            ValidationResult result = ValidationResult.pass();

            // When/Then
            assertNull(result.getSeverity());
        }
    }

    @Nested
    @DisplayName("Message Verification Tests")
    class MessageVerificationTests {

        @Test
        @DisplayName("Should verify exact error message")
        void shouldVerifyExactErrorMessage() {
            // When
            ValidationResult result = ValidationResult.fail(
                "TestConstraint",
                "EClass must have a name",
                Severity.ERROR,
                null
            );

            // Then
            assertEquals("EClass must have a name", result.getMessage(),
                "Message should match exactly");
        }

        @Test
        @DisplayName("Should verify exact warning message")
        void shouldVerifyExactWarningMessage() {
            // When
            ValidationResult result = ValidationResult.warn(
                "TestCritique",
                "EClass name should start with capital letter",
                null
            );

            // Then
            assertEquals("EClass name should start with capital letter", result.getMessage(),
                "Warning message should match exactly");
        }

        @Test
        @DisplayName("Should handle messages with dynamic content")
        void shouldHandleMessagesWithDynamicContent() {
            // Given
            String dynamicMessage = "Element 'TestElement' has invalid property";

            // When
            ValidationResult result = ValidationResult.fail(
                "DynamicConstraint",
                dynamicMessage,
                Severity.ERROR,
                null
            );

            // Then
            assertEquals(dynamicMessage, result.getMessage(),
                "Should preserve dynamic message content");
            assertTrue(result.getMessage().contains("TestElement"),
                "Message should contain element name");
        }
    }

    @Nested
    @DisplayName("Equality and HashCode Tests")
    class EqualityAndHashCodeTests {

        @Test
        @DisplayName("Should implement equals correctly")
        void shouldImplementEqualsCorrectly() {
            // Given
            ValidationResult result1 = ValidationResult.fail(
                "TestConstraint",
                "Error",
                Severity.ERROR,
                null
            );
            ValidationResult result2 = ValidationResult.fail(
                "TestConstraint",
                "Error",
                Severity.ERROR,
                null
            );

            // When/Then
            assertEquals(result1, result2);
        }

        @Test
        @DisplayName("Should implement hashCode correctly")
        void shouldImplementHashCodeCorrectly() {
            // Given
            ValidationResult result1 = ValidationResult.fail(
                "TestConstraint",
                "Error",
                Severity.ERROR,
                null
            );
            ValidationResult result2 = ValidationResult.fail(
                "TestConstraint",
                "Error",
                Severity.ERROR,
                null
            );

            // When/Then
            assertEquals(result1.hashCode(), result2.hashCode());
        }

        @Test
        @DisplayName("Different results should not be equal")
        void differentResultsShouldNotBeEqual() {
            // Given
            ValidationResult result1 = ValidationResult.fail("Error 1");
            ValidationResult result2 = ValidationResult.fail("Error 2");

            // When/Then
            assertNotEquals(result1, result2);
        }
    }
}
