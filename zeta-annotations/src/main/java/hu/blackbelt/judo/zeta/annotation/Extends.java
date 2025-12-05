package hu.blackbelt.judo.zeta.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares rule inheritance.
 * The annotated rule extends the specified parent rules.
 * Parent rules can be executed via executeParentRule() in the transformation context.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Extends {
    /**
     * Names of parent rules that this rule extends.
     */
    String[] value();
}
