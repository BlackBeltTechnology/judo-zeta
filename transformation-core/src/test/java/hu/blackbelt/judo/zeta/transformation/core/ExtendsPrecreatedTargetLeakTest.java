package hu.blackbelt.judo.zeta.transformation.core;

/*-
 * #%L
 * Judo :: Zeta :: Transformation Core
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

import hu.blackbelt.judo.zeta.annotation.*;
import hu.blackbelt.judo.zeta.common.ExtensionMethodRegistry;
import hu.blackbelt.judo.zeta.common.ModelProvider;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the preCreatedTarget leak bug.
 *
 * <p><b>Bug scenario:</b> A rule decorated with {@code @Extends} calls
 * {@code ctx.executeParentRule(name, differentSource, null)} for a different source element
 * while the {@code @Extends} inheritance chain is still active ({@code inInheritanceExecution=true},
 * {@code preCreatedTarget=actorEPackage}). Because the condition in {@code executeParentRule}
 * only cleared the inheritance state for lazy rules ({@code && parentRule.isLazy()}),
 * non-lazy rules inherited the outer chain's {@code preCreatedTarget}. When the inner rule
 * called {@code createTarget(EPackage.class)}, it returned the outer rule's pre-created
 * {@code EPackage} instead of creating a fresh one — silently corrupting the model.</p>
 *
 * <p><b>Fix:</b> Remove {@code && parentRule.isLazy()} from the inheritance-clear condition
 * in {@code TransformationContext.executeParentRule()}. When called with {@code target=null},
 * all rules (lazy or not) must get a fresh execution context.</p>
 */
class ExtendsPrecreatedTargetLeakTest {

    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource sourceResource;
    private Resource targetResource;

    @BeforeEach
    void setUp() {
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("*", new XMIResourceFactoryImpl());

        sourceResourceSet = new ResourceSetImpl();
        targetResourceSet = new ResourceSetImpl();
        sourceResource = sourceResourceSet.createResource(URI.createURI("test://source.xmi"));
        targetResource = targetResourceSet.createResource(URI.createURI("test://target.xmi"));
    }

    /**
     * Core regression test.
     *
     * <p>Sets up an {@code EAnnotation} (actor) and an {@code EClass} (principal).
     * {@code CreateMappedActorType} extends {@code CreateMappedTransferObjectType} and
     * processes the actor annotation. During its transform, it calls
     * {@code executeParentRule("CreateMappedTransferObjectType", principalClass, null)}.
     * The returned principal package MUST be a distinct instance from the actor's package.</p>
     */
    @Test
    @DisplayName("executeParentRule inside @Extends chain creates fresh target for different source (non-lazy rule)")
    void testNonLazyExecuteParentRuleInsideExtendsChainGetsFreshTarget() {
        // Create source elements
        EAnnotation actorAnnotation = EcoreFactory.eINSTANCE.createEAnnotation();
        actorAnnotation.setSource("Actor");
        sourceResource.getContents().add(actorAnnotation);

        EClass principalClass = EcoreFactory.eINSTANCE.createEClass();
        principalClass.setName("Principal");
        sourceResource.getContents().add(principalClass);

        // Store the principal source so the transformation can access it
        ActorTransformation.principalSource = principalClass;
        ActorTransformation.capturedActorPkg.set(null);
        ActorTransformation.capturedPrincipalPkg.set(null);

        TransformationRegistry registry = new TransformationRegistry();
        registry.register(ActorTransformation.class);

        ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
        ModelProvider modelProvider = new TestModelProvider();

        TransformationContext ctx = new TransformationContext(
                modelProvider, sourceResourceSet, targetResourceSet, extensionRegistry);
        ctx.setTargetPackage(EcorePackage.eINSTANCE);
        ctx.registerResource("source", sourceResourceSet);
        ctx.registerResource("target", targetResourceSet);
        ctx.setTransformationRegistry(registry);
        ctx.setAutoAddRootElements(false);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(ctx)
                .parallel(false)
                .build();

        executor.transform();

        EPackage actorPkg = ActorTransformation.capturedActorPkg.get();
        EPackage principalPkg = ActorTransformation.capturedPrincipalPkg.get();

        assertNotNull(actorPkg, "Actor EPackage should have been created");
        assertNotNull(principalPkg, "Principal EPackage should have been created via executeParentRule");

        // THE KEY ASSERTION: executeParentRule for the principal must return a DIFFERENT
        // instance from the pre-created actor target.
        // With the bug, principalPkg == actorPkg (same object), causing the next assertion to fail.
        assertNotSame(actorPkg, principalPkg,
                "executeParentRule(name, principalClass, null) inside an @Extends chain must " +
                "create a fresh EPackage for the principal, not reuse the pre-created actor target. " +
                "If this fails, the preCreatedTarget leaked from the @Extends chain into " +
                "executeParentRule — remove '&& parentRule.isLazy()' from the condition in " +
                "TransformationContext.executeParentRule().");

        // The actor's package must retain its own name ("EAnnotation") — not be overwritten by
        // the principal rule execution. With the bug, the principal rule modifies actorPkg's name.
        assertEquals("EAnnotation", actorPkg.getName(),
                "Actor package name was corrupted (overwritten by principal rule execution). " +
                "This indicates the principal rule reused the actor's pre-created target.");

        // The principal's package should have the principal's type name
        assertEquals("EClass", principalPkg.getName(),
                "Principal package name should be 'EClass' (the principal EClass's eClass name)");
    }

    // ==================== Transformation Classes ====================

    /**
     * Simulates the ActorType / TransferObjectType inheritance pattern from the bug report.
     *
     * <ul>
     *   <li>{@code CreateMappedTransferObjectType} — abstract base rule, creates {@code EPackage}
     *       and sets its name from the source's eClass name.</li>
     *   <li>{@code CreateMappedActorType} — extends the base, processes {@code EAnnotation}
     *       sources. During its transform, it calls
     *       {@code ctx.executeParentRule("CreateMappedTransferObjectType", principalSource, null)}
     *       to obtain the principal's mapped package.</li>
     * </ul>
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EObject.class, target = EPackage.class)
    public static class ActorTransformation {

        /** Set before each test run to provide the principal source element. */
        static volatile EClass principalSource;

        /** Captures the actor's pre-created EPackage (from inside the actor transform). */
        static final AtomicReference<EPackage> capturedActorPkg = new AtomicReference<>();

        /** Captures the EPackage returned by executeParentRule for the principal. */
        static final AtomicReference<EPackage> capturedPrincipalPkg = new AtomicReference<>();

        /**
         * Abstract base rule analogous to {@code CreateMappedTransferObjectType}.
         * Non-lazy, non-greedy, abstract — only invoked via @Extends chain or executeParentRule.
         */
        @TransformRule(name = "CreateMappedTransferObjectType")
        @Abstract
        public TransformFunction<EObject, EPackage> createMappedTransferObjectType() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                // Use the source EObject's runtime eClass name as the package name.
                // EAnnotation source → name = "EAnnotation"
                // EClass source → name = "EClass"
                pkg.setName(source.eClass().getName());
                return pkg;
            };
        }

        /**
         * Child rule analogous to {@code CreateMappedActorType}.
         * Extends the base rule; processes {@code EAnnotation} sources.
         * During its transform, calls executeParentRule for the principal.
         */
        @TransformRule(name = "CreateMappedActorType")
        @Transform(type = EAnnotation.class)
        @Extends("CreateMappedTransferObjectType")
        public TransformFunction<EAnnotation, EPackage> createMappedActorType() {
            return (source, ctx) -> {
                // createTarget returns the pre-created EPackage for this actor
                EPackage actorPkg = ctx.createTarget(EPackage.class);
                capturedActorPkg.set(actorPkg);

                // This is the bug trigger:
                // Call executeParentRule for the PRINCIPAL (a different source element, EClass).
                // Correct behavior: creates a NEW EPackage for principalSource.
                // Buggy behavior: returns actorPkg (the actor's pre-created target) because
                //   inInheritanceExecution=true and EPackage.class.isInstance(actorPkg)=true.
                EPackage principalResult = ctx.executeParentRule(
                        "CreateMappedTransferObjectType", principalSource, null);
                capturedPrincipalPkg.set(principalResult);

                ctx.addToResource(actorPkg);
                return actorPkg;
            };
        }
    }

    // ==================== Model Provider ====================

    static class TestModelProvider implements ModelProvider {
        @Override
        @SuppressWarnings("unchecked")
        public <T extends EObject> Collection<T> getAllContents(ResourceSet resourceSet, Class<T> type) {
            List<T> results = new ArrayList<>();
            for (Resource resource : resourceSet.getResources()) {
                TreeIterator<EObject> iterator = resource.getAllContents();
                while (iterator.hasNext()) {
                    EObject obj = iterator.next();
                    if (type.isInstance(obj)) {
                        results.add((T) obj);
                    }
                }
            }
            return results;
        }
    }
}
