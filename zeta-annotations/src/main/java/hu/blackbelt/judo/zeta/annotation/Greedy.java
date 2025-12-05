package hu.blackbelt.judo.zeta.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables broader type matching using kind-of relationship.
 * Without @Greedy, rules match only the exact source type (type-of semantics).
 * With @Greedy, rules match the source type AND all subtypes (kind-of semantics).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Greedy {
}
