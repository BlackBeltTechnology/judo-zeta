package hu.blackbelt.judo.zeta.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a constraint (error-level validation rule).
 * Constraint violations are treated as errors that prevent model processing.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Constraint {
    /**
     * Unique name of the constraint.
     */
    String name();

    /**
     * Human-readable description of the constraint.
     */
    String description() default "";

    /**
     * Error message to display when the constraint is violated.
     */
    String message() default "";
}
