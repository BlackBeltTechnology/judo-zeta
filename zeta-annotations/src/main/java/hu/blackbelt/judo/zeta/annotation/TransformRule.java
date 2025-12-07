package hu.blackbelt.judo.zeta.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.eclipse.emf.ecore.EObject;

/**
 * Defines a transformation rule method.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface TransformRule {
    /**
     * Unique name of the transformation rule.
     */
    String name();

    /**
     * Human-readable description of the transformation rule.
     */
    String description() default "";

    /**
     * Multiple source types for Cartesian product transformations.
     * If specified, overrides the source type from @TransformationContext.
     */
    Class<? extends EObject>[] sourceTypes() default {};

    /**
     * Multiple target types for multi-target transformations.
     * If specified, overrides the target type from @TransformationContext.
     */
    Class<? extends EObject>[] targetTypes() default {};
}
