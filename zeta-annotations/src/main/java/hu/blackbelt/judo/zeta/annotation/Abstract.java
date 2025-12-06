package hu.blackbelt.judo.zeta.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a transformation rule as abstract.
 * Abstract rules cannot execute independently and are only invoked
 * via inheritance when a child rule calls executeParentRule().
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Abstract {
}
