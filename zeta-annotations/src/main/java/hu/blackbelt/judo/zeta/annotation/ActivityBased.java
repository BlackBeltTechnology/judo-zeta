package hu.blackbelt.judo.zeta.annotation;

/*-
 * #%L
 * Judo :: Zeta :: Annotations
 * %%
 * Copyright (C) 2018 - 2024 BlackBelt Technology
 * %%
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * with the GNU Classpath Exception which is
 * available at https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 * #L%
 */

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables activity-based processing for {@code @Greedy @Lazy} rules.
 *
 * <p>Activity-based rules only process elements that are "activated" via
 * {@code equivalent()} calls during transformation. Elements that are never
 * referenced are never processed, matching Epsilon ETL's implicit
 * {@code @greedy @lazy} filtering behavior.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * @Greedy
 * @Lazy
 * @ActivityBased
 * public TransformFunction<TransferObjectType, ClassType> classType() {
 *     return (source, ctx) -> {
 *         // Only executed for elements referenced via equivalent()
 *         ClassType target = ctx.createTarget(ClassType.class);
 *         target.setName(source.getName());
 *         return target;
 *     };
 * }
 * }</pre>
 *
 * <h3>Requirements</h3>
 * <p>This annotation must be used together with both {@code @Greedy} and {@code @Lazy}.
 * Using {@code @ActivityBased} without these annotations has no effect and will
 * generate a warning during rule registration.</p>
 *
 * <h3>How It Works</h3>
 * <ol>
 *   <li><b>Phase 1:</b> Activity-based rules are skipped during eager execution</li>
 *   <li><b>Activation:</b> When {@code equivalent()} is called, the source element
 *       is recorded as "activated" for the matching activity-based rule</li>
 *   <li><b>Phase 2:</b> After eager execution completes, activity-based rules
 *       process only their activated elements</li>
 * </ol>
 *
 * <h3>Alternative: ETL Compatibility Mode</h3>
 * <p>Instead of annotating each rule, you can enable activity-based processing
 * for all {@code @Greedy @Lazy} rules via the executor:</p>
 * <pre>{@code
 * TransformationExecutor.builder()
 *     .registry(registry)
 *     .context(context)
 *     .etlCompatibilityMode(true)  // Applies activity-based to all @Greedy @Lazy
 *     .build();
 * }</pre>
 *
 * @see Greedy
 * @see Lazy
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ActivityBased {
}
