package hu.blackbelt.judo.zeta.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a transformation rule for lazy evaluation.
 * Lazy rules are only executed when needed via equivalent() calls,
 * rather than during the eager transformation phase.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Lazy {
}
