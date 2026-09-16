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
import org.eclipse.emf.ecore.xmi.XMIResource;
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
 * Regression test for XMI ID collision in @Extends inheritance chains.
 *
 * <p><b>Bug:</b> When a parent rule in an {@code @Extends} chain calls
 * {@code createTarget(Class)} to get the pre-created target, the rule instance
 * counter is not incremented (because the pre-created target is returned early).
 * If the parent then creates a second element via {@code createTarget(OtherClass)},
 * the auto-generated ID uses the base format {@code (source)/RuleName} — the same
 * base as the pre-created target's overwritten ID — causing a collision.</p>
 *
 * <p><b>Fix:</b> Increment the {@code ruleInstanceCounters} when returning the
 * pre-created target early, so subsequent {@code createTarget()} calls generate
 * unique suffixed IDs.</p>
 */
class ExtendsCreateTargetIdPreservationTest {

    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource sourceResource;
    private Resource targetResource;
    private TransformationContext context;
    private TransformationRegistry registry;

    @BeforeEach
    void setUp() {
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("*", new XMIResourceFactoryImpl());

        sourceResourceSet = new ResourceSetImpl();
        targetResourceSet = new ResourceSetImpl();
        sourceResource = sourceResourceSet.createResource(URI.createURI("test://source.xmi"));
        targetResource = targetResourceSet.createResource(URI.createURI("test://target.xmi"));

        ModelProvider modelProvider = new TestModelProvider();
        ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();

        context = new TransformationContext(
                modelProvider, sourceResourceSet, targetResourceSet, extensionRegistry);
        context.setTargetPackage(EcorePackage.eINSTANCE);
        context.registerResource("source", sourceResourceSet);
        context.registerResource("target", targetResourceSet);
        context.setAutoAddRootElements(true);
        context.setUseStructuredIds(true);

        registry = new TransformationRegistry();
    }

    /**
     * Scenario A: Parent rule's createTarget(Class, source, "BaseRule") correctly
     * overwrites the pre-created target's ID. This is expected ETL semantics.
     */
    @Test
    @DisplayName("Parent rule's createTarget(Class, source, parentRuleName) overwrites child's ID (ETL semantics)")
    void scenarioA_parentCreateTargetOverwritesChildId() {
        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName("Widget");
        sourceResource.getContents().add(source);
        if (sourceResource instanceof XMIResource) {
            ((XMIResource) sourceResource).setID(source, "_widget001");
        }

        ScenarioATransformation.capturedTarget.set(null);
        registry.register(ScenarioATransformation.class);
        context.setTransformationRegistry(registry);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        EPackage target = ScenarioATransformation.capturedTarget.get();
        assertNotNull(target, "Target should have been created");

        String targetId = context.getPendingXmiId(target);
        if (targetId == null && targetResource instanceof XMIResource) {
            targetId = ((XMIResource) targetResource).getID(target);
        }

        assertNotNull(targetId, "Target should have an XMI ID");
        // In ETL semantics, parent's explicit createTarget(Class, source, "BaseRule")
        // overwrites the pre-created target's child-based ID
        assertTrue(targetId.endsWith("/BaseRule"),
                "Target ID should end with parent rule name 'BaseRule' (ETL semantics) but was: " + targetId);
    }

    /**
     * Scenario B: Parent rule gets pre-created target, then creates a second element.
     * The second element must NOT collide with the pre-created target's ID.
     */
    @Test
    @DisplayName("No XMI ID collision when parent rule creates additional element after getting pre-created target")
    void scenarioB_noIdCollisionBetweenPreCreatedTargetAndSecondElement() {
        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName("Gadget");
        sourceResource.getContents().add(source);
        if (sourceResource instanceof XMIResource) {
            ((XMIResource) sourceResource).setID(source, "_gadget001");
        }

        ScenarioBTransformation.capturedTarget.set(null);
        ScenarioBTransformation.capturedSecondElement.set(null);
        registry.register(ScenarioBTransformation.class);
        context.setTransformationRegistry(registry);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        EPackage target = ScenarioBTransformation.capturedTarget.get();
        EAnnotation secondElement = ScenarioBTransformation.capturedSecondElement.get();

        assertNotNull(target, "Pre-created target should have been captured");
        assertNotNull(secondElement, "Second element should have been created");

        String targetId = context.getPendingXmiId(target);
        if (targetId == null && targetResource instanceof XMIResource) {
            targetId = ((XMIResource) targetResource).getID(target);
        }
        String secondId = context.getPendingXmiId(secondElement);
        if (secondId == null && targetResource instanceof XMIResource) {
            secondId = ((XMIResource) targetResource).getID(secondElement);
        }

        assertNotNull(targetId, "Pre-created target should have an XMI ID");
        assertNotNull(secondId, "Second element should have an XMI ID");

        // The pre-created target gets the parent's custom ID (ETL semantics)
        assertTrue(targetId.endsWith("/BaseWithSecond"),
                "Pre-created target ID should end with parent rule name but was: " + targetId);

        // The second element must NOT have the same ID as the pre-created target
        assertNotEquals(targetId, secondId,
                "Second element must have a different XMI ID from the pre-created target. " +
                "If equal, the ruleInstanceCounter was not incremented for the pre-created target return.");
    }

    /**
     * Scenario C: Normal (non-inheritance) createTarget(Class, String) still applies custom ID.
     */
    @Test
    @DisplayName("Non-inheritance createTarget(Class, String) applies custom ID normally")
    void scenarioC_normalCreateTargetWithCustomIdWorks() {
        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName("Thing");
        sourceResource.getContents().add(source);
        if (sourceResource instanceof XMIResource) {
            ((XMIResource) sourceResource).setID(source, "_thing001");
        }

        ScenarioCTransformation.capturedTarget.set(null);
        registry.register(ScenarioCTransformation.class);
        context.setTransformationRegistry(registry);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        EPackage target = ScenarioCTransformation.capturedTarget.get();
        assertNotNull(target, "Target should have been created");

        String targetId = context.getPendingXmiId(target);
        if (targetId == null && targetResource instanceof XMIResource) {
            targetId = ((XMIResource) targetResource).getID(target);
        }

        assertNotNull(targetId, "Target should have an XMI ID");
        assertEquals("my-custom-id", targetId,
                "Non-inheritance createTarget(Class, String) should apply the custom ID");
    }

    // ==================== Transformation Classes ====================

    /**
     * Scenario A: Abstract parent calls createTarget(Class, source, "BaseRule"),
     * concrete child with @Extends.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ScenarioATransformation {
        static final AtomicReference<EPackage> capturedTarget = new AtomicReference<>();

        @TransformRule(name = "BaseRule")
        @Abstract
        public TransformFunction<EClass, EPackage> baseRule() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class, source, "BaseRule");
                pkg.setName(source.getName());
                return pkg;
            };
        }

        @TransformRule(name = "ConcreteRule")
        @Transform(type = EClass.class)
        @Extends("BaseRule")
        public TransformFunction<EClass, EPackage> concreteRule() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                capturedTarget.set(pkg);
                pkg.setNsURI("http://test/" + source.getName());
                return pkg;
            };
        }
    }

    /**
     * Scenario B: Abstract parent gets pre-created target, then creates a second
     * element (EAnnotation) via createTarget(). The second element's auto-generated
     * ID must not collide with the pre-created target's ID.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ScenarioBTransformation {
        static final AtomicReference<EPackage> capturedTarget = new AtomicReference<>();
        static final AtomicReference<EAnnotation> capturedSecondElement = new AtomicReference<>();

        @TransformRule(name = "BaseWithSecond")
        @Abstract
        public TransformFunction<EClass, EPackage> baseWithSecond() {
            return (source, ctx) -> {
                // First call: gets the pre-created target, overwrites ID to .../BaseWithSecond
                EPackage pkg = ctx.createTarget(EPackage.class, source, "BaseWithSecond");
                pkg.setName(source.getName());

                // Second call: creates a NEW EAnnotation. Without the fix, this gets
                // auto-generated ID .../BaseWithSecond (same base, totalCallsForRule=0)
                // which collides with the pre-created target's overwritten ID.
                EAnnotation second = ctx.createTarget(EAnnotation.class);
                second.setSource("second-element");
                capturedSecondElement.set(second);

                return pkg;
            };
        }

        @TransformRule(name = "ConcreteWithSecond")
        @Transform(type = EClass.class)
        @Extends("BaseWithSecond")
        public TransformFunction<EClass, EPackage> concreteWithSecond() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);
                capturedTarget.set(pkg);
                pkg.setNsURI("http://test/" + source.getName());
                return pkg;
            };
        }
    }

    /**
     * Scenario C: Normal rule (no @Extends) with createTarget(Class, String).
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ScenarioCTransformation {
        static final AtomicReference<EPackage> capturedTarget = new AtomicReference<>();

        @TransformRule(name = "NormalRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> normalRule() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class, "my-custom-id");
                capturedTarget.set(pkg);
                pkg.setName(source.getName());
                return pkg;
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
