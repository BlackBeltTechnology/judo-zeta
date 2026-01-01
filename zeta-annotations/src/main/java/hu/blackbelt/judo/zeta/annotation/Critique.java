package hu.blackbelt.judo.zeta.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a critique (warning-level validation rule).
 * Critique violations are treated as warnings that do not prevent model processing.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Critique {
    /**
     * Unique name of the critique.
     */
    String name();

    /**
     * Human-readable description of the critique.
     */
    String description() default "";

    /**
     * Warning message to display when the critique is violated.
     */
    String message() default "";

    /**
     * The resource alias to read elements from for validation.
     * Defaults to "source".
     */
    String resourceAlias() default "source";
}
