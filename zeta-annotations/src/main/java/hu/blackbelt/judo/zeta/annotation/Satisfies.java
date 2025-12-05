package hu.blackbelt.judo.zeta.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a constraint or critique depends on other constraints being satisfied first.
 * The dependent constraints will be executed before this one.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Satisfies {
    /**
     * Names of the constraints that must be satisfied before this one executes.
     */
    String[] value();
}
