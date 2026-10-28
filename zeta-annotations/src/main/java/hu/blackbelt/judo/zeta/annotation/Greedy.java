package hu.blackbelt.judo.zeta.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Changes rule matching from type-of to kind-of relationship semantics.
 *
 * <p><b>Without @Greedy (type-of semantics - default):</b></p>
 * <p>Rule matches ONLY elements whose type is exactly the declared source type.
 * Subtypes are not matched. For example, Rule(A) does not match B if B extends A.</p>
 *
 * <p><b>With @Greedy (kind-of semantics):</b></p>
 * <p>Rule matches elements whose type is the declared source type OR any subtype.
 * For example, GreedyRule(A) matches B if B extends A.</p>
 *
 * <p>This matches Epsilon ETL behavior where @greedy annotation changes matching
 * from type-of (exact) to kind-of (subtype) relationship.</p>
 *
 * @see hu.blackbelt.judo.zeta.transformation.core.TransformRuleDescriptor#appliesTo(org.eclipse.emf.ecore.EObject)
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Greedy {
}
