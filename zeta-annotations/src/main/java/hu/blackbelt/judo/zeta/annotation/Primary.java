package hu.blackbelt.judo.zeta.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a transformation rule as primary.
 *
 * @deprecated Primary rules are deprecated and will be removed in a future version.
 *             The framework no longer uses this annotation for any processing.
 *             Use explicit rule naming with {@code equivalent(source, "RuleName")} instead
 *             of relying on primary rule selection.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Deprecated
public @interface Primary {
}
