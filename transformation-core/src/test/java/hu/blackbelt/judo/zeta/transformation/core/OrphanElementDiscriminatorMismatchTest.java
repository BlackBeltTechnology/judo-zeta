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
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests demonstrating the orphan element problem caused by discriminator mismatch.
 *
 * <h2>Problem Statement</h2>
 * <p>When different transformation rules use different discriminators for the same logical target,
 * and one assigns to a containment reference while another assigns to a non-containment reference,
 * orphan elements appear at the resource root level.</p>
 *
 * <h2>EMF Metamodel Context</h2>
 * <p>In EMF, two types of references exist:</p>
 * <ul>
 *   <li><b>Containment (composition):</b> When an element is assigned to a containment reference,
 *       EMF automatically removes it from Resource.contents.</li>
 *   <li><b>Non-containment (reference):</b> When assigned to a non-containment reference,
 *       the element stays in Resource.contents unless contained elsewhere.</li>
 * </ul>
 */
@DisplayName("Orphan Element Discriminator Mismatch Tests")
class OrphanElementDiscriminatorMismatchTest {

    private static final Logger log = LoggerFactory.getLogger(OrphanElementDiscriminatorMismatchTest.class);

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

        ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
        ModelProvider modelProvider = new TestModelProvider();

        context = new TransformationContext(
                modelProvider, sourceResourceSet, targetResourceSet, extensionRegistry);
        context.setTargetPackage(EcorePackage.eINSTANCE);
        context.registerResource("source", sourceResourceSet);
        context.registerResource("target", targetResourceSet);

        registry = new TransformationRegistry();
    }

    // ==================== EMF Containment Behavior Tests ====================

    @Nested
    @DisplayName("EMF Containment Behavior Verification")
    class EmfContainmentBehaviorTests {

        /**
         * IMPORTANT: EPackage.eClassifiers is a PROXY-RESOLVING containment reference.
         * Since EMF 2.2, proxy-resolving containment references do NOT automatically
         * remove elements from Resource.contents when contained!
         *
         * This behavior is actually the ROOT CAUSE of the orphan element problem
         * in transformations. Elements added to Resource.contents stay there
         * even after being contained.
         */
        @Test
        @DisplayName("Proxy-resolving containment does NOT auto-remove from Resource.contents")
        void proxyResolvingContainmentDoesNotAutoRemove() {
            // Create a fresh local resource for this test
            Resource localResource = targetResourceSet.createResource(URI.createURI("test://emf-test.xmi"));

            // Create a classifier
            EClass classifier = EcoreFactory.eINSTANCE.createEClass();
            classifier.setName("MyClass");

            // Create parent package at root
            EPackage parentPkg = EcoreFactory.eINSTANCE.createEPackage();
            parentPkg.setName("ParentPackage");
            localResource.getContents().add(parentPkg);

            // Add classifier to resource root
            localResource.getContents().add(classifier);
            assertEquals(2, localResource.getContents().size(), "Should have 2 elements at root");

            // Check that eClassifiers is proxy-resolving
            EReference eClassifiersRef = EcorePackage.eINSTANCE.getEPackage_EClassifiers();
            assertTrue(eClassifiersRef.isContainment(), "eClassifiers should be containment");
            assertTrue(eClassifiersRef.isResolveProxies(), "eClassifiers should be proxy-resolving");

            // Assign to containment reference
            parentPkg.getEClassifiers().add(classifier);

            // With proxy-resolving containment, element stays at resource root!
            assertEquals(2, localResource.getContents().size(),
                    "Proxy-resolving containment does NOT auto-remove from Resource.contents");
            assertTrue(localResource.getContents().contains(classifier),
                    "Classifier STILL in Resource.contents despite having container");
            assertEquals(parentPkg, classifier.eContainer(),
                    "Classifier should be contained by ParentPkg");

            log.info("EMF proxy-resolving containment behavior verified:");
            log.info("  Element has container: {}", classifier.eContainer() != null);
            log.info("  Element still in Resource.contents: {}", localResource.getContents().contains(classifier));
            log.info("  This IS the cause of 'orphan' elements at root!");
        }

        /**
         * Demonstrates that non-containment reference keeps element in Resource.contents
         * (expected behavior).
         */
        @Test
        @DisplayName("Non-containment reference keeps element in Resource.contents")
        void nonContainmentReferenceKeepsInResource() {
            Resource localResource = targetResourceSet.createResource(URI.createURI("test://noncontainment-test.xmi"));

            // Create target at resource root
            EClass target = EcoreFactory.eINSTANCE.createEClass();
            target.setName("Target");
            localResource.getContents().add(target);

            assertEquals(1, localResource.getContents().size());
            assertNull(target.eContainer(), "Target should have no container");

            // Create annotation and add to resource
            EAnnotation annotation = EcoreFactory.eINSTANCE.createEAnnotation();
            annotation.setSource("test");
            localResource.getContents().add(annotation);

            assertEquals(2, localResource.getContents().size());

            // Assign to non-containment reference (references is containment=false)
            annotation.getReferences().add(target);

            // Element stays at resource root (no container)
            assertEquals(2, localResource.getContents().size(),
                    "Non-containment assignment should NOT remove element from root");
            assertTrue(localResource.getContents().contains(target));
            assertNull(target.eContainer(),
                    "Non-containment reference does NOT set container - element is orphan at root");
        }

        /**
         * Demonstrates that with proxy-resolving containment, even when an element
         * is contained, it can still appear at Resource.contents root.
         * The "orphan" is actually contained but appears orphaned in Resource.contents.
         */
        @Test
        @DisplayName("Contained elements appear as 'orphans' at Resource root")
        void containedElementsAppearAsOrphansAtRoot() {
            Resource localResource = targetResourceSet.createResource(URI.createURI("test://orphan-test.xmi"));

            // Create ActionDefinition-like element
            EClass actionDef = EcoreFactory.eINSTANCE.createEClass();
            actionDef.setName("ActionDef");
            localResource.getContents().add(actionDef);

            // Create Button-like container
            EPackage button = EcoreFactory.eINSTANCE.createEPackage();
            button.setName("Button");
            localResource.getContents().add(button);

            // Create Action-like element with annotation
            EAnnotation action = EcoreFactory.eINSTANCE.createEAnnotation();
            action.setSource("Action");
            localResource.getContents().add(action);

            assertEquals(3, localResource.getContents().size());

            // Action references actionDef (non-containment - no container change)
            action.getReferences().add(actionDef);
            assertEquals(3, localResource.getContents().size());
            assertNull(actionDef.eContainer(), "Non-containment doesn't set container");

            // Button contains actionDef (proxy-resolving containment)
            button.getEClassifiers().add(actionDef);

            // Key insight: actionDef STILL appears at root despite being contained!
            assertEquals(3, localResource.getContents().size(),
                    "ActionDef still at root because eClassifiers is proxy-resolving");
            assertTrue(localResource.getContents().contains(actionDef),
                    "ActionDef still in Resource.contents");
            assertEquals(button, actionDef.eContainer(),
                    "But ActionDef IS contained by Button");

            // The same element is both "orphan at root" AND "contained"
            // This is the core behavior that causes the orphan problem!
            log.info("'Orphan' element status:");
            log.info("  In Resource.contents: true");
            log.info("  Has container (Button): true");
            log.info("  This makes it appear as orphan when iterating Resource.contents!");
        }
    }

    // ==================== Discriminator Mismatch Tests ====================

    @Nested
    @DisplayName("Discriminator Mismatch Causing Orphans")
    class DiscriminatorMismatchTests {

        /**
         * EXPECTED BEHAVIOR (after fix): Same discriminator creates exactly 1 element.
         *
         * <p>When a @Lazy rule is accessed via equivalentDiscriminated(), the framework
         * should NOT add the original to the resource. Only the discriminated clone
         * should be added.</p>
         *
         * <p>With same discriminator, Button and Action share the SAME clone instance.
         * The clone is contained by Button. Action references the same instance.</p>
         */
        @Test
        @DisplayName("Same discriminator creates exactly 1 ActionDef (clone only)")
        void sameDiscriminatorCreatesOneElement() {
            EClass source = EcoreFactory.eINSTANCE.createEClass();
            source.setName("OperationForm");
            sourceResource.getContents().add(source);

            registry.register(SharedDiscriminatorTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            log.info("=== Same Discriminator Test (Expected Fixed Behavior) ===");
            logRootElements();

            // Count ActionDefinition-like elements (EDataType) at root
            List<EDataType> actionDefs = targetResource.getContents().stream()
                    .filter(e -> e instanceof EDataType)
                    .map(e -> (EDataType) e)
                    .collect(Collectors.toList());

            log.info("ActionDefinitions at root: {}", actionDefs.size());
            actionDefs.forEach(a -> log.info("  - {} (container={})", a.getName(),
                    a.eContainer() != null ? ((ENamedElement) a.eContainer()).getName() : "null"));

            // EXPECTED BEHAVIOR: Only 1 clone, no original orphan
            // - Clone is shared by Button (containment) and Action (non-containment reference)
            assertEquals(1, actionDefs.size(),
                    "Same discriminator should create exactly 1 ActionDef (the clone)");

            // The single clone should be contained by Button
            EDataType theClone = actionDefs.get(0);
            assertNotNull(theClone.eContainer(),
                    "The clone should be contained by Button");
            assertEquals("Button_OperationForm", ((ENamedElement) theClone.eContainer()).getName(),
                    "Container should be Button");

            log.info("SUCCESS: Same discriminator = 1 clone, properly contained, no orphan original");
        }

        /**
         * EXPECTED BEHAVIOR (after fix): Different discriminators create exactly 2 elements.
         *
         * <p>When Button and Action use different discriminators:
         * - Button's call: creates clone1 (contained by Button)
         * - Action's call: creates clone2 (orphan - only non-containment ref)
         * Result: 2 clones, NO original orphan</p>
         */
        @Test
        @DisplayName("Different discriminators create exactly 2 ActionDefs (clones only)")
        void differentDiscriminatorsCreateTwoElements() {
            EClass source = EcoreFactory.eINSTANCE.createEClass();
            source.setName("OperationForm");
            sourceResource.getContents().add(source);

            registry.register(MismatchedDiscriminatorTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            log.info("=== Different Discriminator Test (Expected Fixed Behavior) ===");
            logRootElements();

            // Get all ActionDefinition-like elements (EDataType) at root
            List<EDataType> allActionDefs = targetResource.getContents().stream()
                    .filter(e -> e instanceof EDataType)
                    .map(e -> (EDataType) e)
                    .collect(Collectors.toList());

            log.info("Total ActionDefinitions at root: {}", allActionDefs.size());

            // EXPECTED BEHAVIOR: 2 clones only, no original orphan
            // - Clone 1 (contained by Button) - from Button's equivalentDiscriminated("button/...")
            // - Clone 2 (orphan, no container) - from Action's equivalentDiscriminated("action/...")
            assertEquals(2, allActionDefs.size(),
                    "Different discriminators should create exactly 2 ActionDefs (clones only)");

            // Separate into contained vs orphans
            List<EDataType> containedOnes = allActionDefs.stream()
                    .filter(a -> a.eContainer() != null)
                    .collect(Collectors.toList());
            List<EDataType> orphans = allActionDefs.stream()
                    .filter(a -> a.eContainer() == null)
                    .collect(Collectors.toList());

            log.info("Contained (Button's clone): {}", containedOnes.size());
            containedOnes.forEach(a -> log.info("  - {} (container={})", a.getName(),
                    ((ENamedElement) a.eContainer()).getName()));

            log.info("Orphans (Action's clone only): {}", orphans.size());
            orphans.forEach(a -> log.info("  - {} (container=null)", a.getName()));

            // 1 contained (Button's clone) and 1 orphan (Action's clone - this is expected)
            assertEquals(1, containedOnes.size(),
                    "Should have 1 contained clone (Button's)");
            assertEquals(1, orphans.size(),
                    "Should have 1 orphan (Action's clone - expected because Action uses non-containment ref)");

            log.info("SUCCESS: Different discriminators = 2 clones only, no original orphan");
        }

        /**
         * EXPECTED BEHAVIOR (after fix): Multiple sources create 1 orphan per source.
         *
         * <p>With different discriminators:
         * - Per source: Button's clone (contained) + Action's clone (orphan)
         * - No original orphans
         * - Total orphans: 10 (Action's clones only)</p>
         */
        @Test
        @DisplayName("Multiple sources: 1 orphan per source (Action's clone only)")
        void multipleSourcesCreateCorrectOrphanCount() {
            // Create multiple source elements
            for (int i = 0; i < 10; i++) {
                EClass source = EcoreFactory.eINSTANCE.createEClass();
                source.setName("OperationForm" + i);
                sourceResource.getContents().add(source);
            }

            registry.register(MismatchedDiscriminatorTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Count total ActionDefs and orphans
            List<EDataType> allActionDefs = targetResource.getContents().stream()
                    .filter(e -> e instanceof EDataType)
                    .map(e -> (EDataType) e)
                    .collect(Collectors.toList());

            List<EDataType> orphans = allActionDefs.stream()
                    .filter(a -> a.eContainer() == null)
                    .collect(Collectors.toList());

            log.info("=== Multi-Source Test (Expected Fixed Behavior) ===");
            log.info("Source count: 10");
            log.info("Total ActionDefs at root: {}", allActionDefs.size());
            log.info("Orphan count (no container): {}", orphans.size());

            // EXPECTED BEHAVIOR:
            // - Per source: 2 clones (Button's + Action's) = 20 total ActionDefs
            // - Per source: 1 orphan (Action's clone) = 10 total orphans
            // - NO original orphans
            assertEquals(20, allActionDefs.size(),
                    "Should have 20 ActionDefs (2 clones per source)");
            assertEquals(10, orphans.size(),
                    "Should have 10 orphans (1 Action's clone per source, no original orphans)");

            log.info("SUCCESS: 10 sources × 2 clones = 20 ActionDefs, only 10 orphans (Action's clones)");
        }

        private void logRootElements() {
            log.info("Root elements ({}):", targetResource.getContents().size());
            targetResource.getContents().forEach(e -> {
                String name = e instanceof ENamedElement ? ((ENamedElement) e).getName() : "?";
                String containerName = e.eContainer() != null ?
                        ((ENamedElement) e.eContainer()).getName() : "null";
                log.info("  - {} '{}' (container={})", e.eClass().getName(), name, containerName);
            });
        }
    }

    // ==================== Test Model Provider ====================

    static class TestModelProvider implements ModelProvider {
        @Override
        @SuppressWarnings("unchecked")
        public <T extends EObject> Collection<T> getAllContents(ResourceSet resourceSet, Class<T> type) {
            List<T> results = new ArrayList<>();
            for (Resource resource : resourceSet.getResources()) {
                org.eclipse.emf.common.util.TreeIterator<EObject> iterator = resource.getAllContents();
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

    // ==================== Transformation Classes ====================

    /**
     * Transformation where both Button and Action use the SAME discriminator.
     * This is the correct behavior - same instance is shared.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class SharedDiscriminatorTransformation {

        @TransformRule(name = "CreateApplication")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> createApplication() {
            return (source, ctx) -> {
                EPackage app = ctx.createTarget(EPackage.class);
                app.setName("Application");
                ctx.addToResource(app);

                // Create Button - will contain ActionDefinition
                EPackage button = ctx.equivalentDiscriminated(source, EPackage.class,
                        "CreateButton", source.getName());
                app.getESubpackages().add(button);

                // Create Action - will reference same ActionDefinition
                EAnnotation action = ctx.equivalentDiscriminated(source, EAnnotation.class,
                        "CreateAction", source.getName());
                app.getEAnnotations().add(action);

                return app;
            };
        }

        @TransformRule(name = "CreateButton")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EPackage> createButton() {
            return (source, ctx) -> {
                EPackage button = ctx.createTarget(EPackage.class);
                button.setName("Button_" + source.getName());

                // Get ActionDefinition using SAME discriminator as Action
                String discriminator = source.getName(); // SAME
                EDataType actionDef = ctx.equivalentDiscriminated(source, EDataType.class,
                        "CreateActionDefinition", discriminator);

                // CONTAINMENT: This removes actionDef from Resource.contents
                button.getEClassifiers().add(actionDef);

                return button;
            };
        }

        @TransformRule(name = "CreateAction")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EAnnotation> createAction() {
            return (source, ctx) -> {
                EAnnotation action = ctx.createTarget(EAnnotation.class);
                action.setSource("Action_" + source.getName());

                // Get ActionDefinition using SAME discriminator as Button
                String discriminator = source.getName(); // SAME
                EDataType actionDef = ctx.equivalentDiscriminated(source, EDataType.class,
                        "CreateActionDefinition", discriminator);

                // NON-CONTAINMENT: References the same instance that Button contains
                action.getReferences().add(actionDef);

                return action;
            };
        }

        @TransformRule(name = "CreateActionDefinition")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EDataType> createActionDefinition() {
            return (source, ctx) -> {
                EDataType actionDef = ctx.createTarget(EDataType.class);
                actionDef.setName("ActionDef_" + source.getName());
                ctx.addToResource(actionDef); // Initially at root
                return actionDef;
            };
        }
    }

    /**
     * Transformation where Button and Action use DIFFERENT discriminators.
     * This causes the orphan problem.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class MismatchedDiscriminatorTransformation {

        @TransformRule(name = "CreateApplication")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> createApplication() {
            return (source, ctx) -> {
                EPackage app = ctx.createTarget(EPackage.class);
                app.setName("Application");
                ctx.addToResource(app);

                // Create Button with discriminator "button/..."
                EPackage button = ctx.equivalentDiscriminated(source, EPackage.class,
                        "CreateButton", "button/" + source.getName());
                app.getESubpackages().add(button);

                // Create Action with discriminator "action/..." (DIFFERENT!)
                EAnnotation action = ctx.equivalentDiscriminated(source, EAnnotation.class,
                        "CreateAction", "action/" + source.getName());
                app.getEAnnotations().add(action);

                return app;
            };
        }

        @TransformRule(name = "CreateButton")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EPackage> createButton() {
            return (source, ctx) -> {
                EPackage button = ctx.createTarget(EPackage.class);
                button.setName("Button_" + source.getName());

                // Get ActionDefinition using Button's discriminator pattern
                String discriminator = "button/" + source.getName();
                EDataType actionDef = ctx.equivalentDiscriminated(source, EDataType.class,
                        "CreateActionDefinition", discriminator);

                // CONTAINMENT: This removes actionDef from Resource.contents
                button.getEClassifiers().add(actionDef);

                return button;
            };
        }

        @TransformRule(name = "CreateAction")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EAnnotation> createAction() {
            return (source, ctx) -> {
                EAnnotation action = ctx.createTarget(EAnnotation.class);
                action.setSource("Action_" + source.getName());

                // Get ActionDefinition using Action's discriminator pattern (DIFFERENT!)
                String discriminator = "action/" + source.getName();
                EDataType actionDef = ctx.equivalentDiscriminated(source, EDataType.class,
                        "CreateActionDefinition", discriminator);

                // NON-CONTAINMENT: This instance is DIFFERENT from Button's
                // It stays at Resource.contents = ORPHAN!
                action.getReferences().add(actionDef);

                return action;
            };
        }

        @TransformRule(name = "CreateActionDefinition")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EDataType> createActionDefinition() {
            return (source, ctx) -> {
                EDataType actionDef = ctx.createTarget(EDataType.class);
                actionDef.setName("ActionDef_" + source.getName());
                ctx.addToResource(actionDef); // Initially at root
                return actionDef;
            };
        }
    }
}
