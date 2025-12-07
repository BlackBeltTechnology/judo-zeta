package hu.blackbelt.judo.zeta.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.eclipse.emf.ecore.EObject;

/**
 * Marks a class as containing transformation rules for specific source/target types.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface TransformationContext {
    /**
     * The source EObject type for transformations in this context.
     */
    Class<? extends EObject> source();

    /**
     * The target EObject type for transformations in this context.
     */
    Class<? extends EObject> target();
}
