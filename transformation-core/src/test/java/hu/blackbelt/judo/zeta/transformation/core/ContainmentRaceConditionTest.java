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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to reproduce EMF containment issues with autoAddRootElements.
 *
 * <h2>Discovered Issues:</h2>
 *
 * <h3>Issue 1: Sequential Mode Bug (autoAddRootElements)</h3>
 * <p>In sequential mode with autoAddRootElements=true, child elements are added to
 * Resource.contents immediately by createTarget(). When they are later added to
 * containment references, EMF does NOT automatically remove them from Resource.contents.
 * This is documented EMF behavior - see {@link EmfContainmentBehaviorTest}.</p>
 *
 * <h3>Issue 2: Parallel Mode Race Condition</h3>
 * <p>From Tatami project production usage:</p>
 * <pre>
 * Thread 1                           Thread 2
 * ────────                           ────────
 * child = createTarget(Child.class)
 *   → staged with isRootElement=true
 *   → child.eContainer() == null
 *
 *                                    parent.setChild(child)
 *                                      → EMF starts bidirectional update:
 *                                        1. Set child.eContainer = parent
 *                                        2. Add to parent's feature
 *
 * child queued for commit              → RACE: child.eContainer being set
 *                                        but staging check already passed
 *
 * During single-threaded commit:
 *   for each staged element:
 *     if (element.eContainer() == null) {  ← May see null due to race!
 *       resource.getContents().add(element)
 *     }
 *
 *   → Orphaned children added as root elements!
 * </pre>
 *
 * <h2>Test Scenarios:</h2>
 * <ul>
 *   <li>Parent rule creates parent and calls equivalent() for child</li>
 *   <li>Child rule creates child (staged as root when autoAddRootElements=true)</li>
 *   <li>Parent rule sets containment: parent.setChild(child)</li>
 *   <li>Expected: Child should NOT be in resource.contents (it's contained by parent)</li>
 *   <li>Bug: Child appears in resource.contents AND in parent</li>
 * </ul>
 *
 * <h2>Running:</h2>
 * <p>Enable with: {@code mvn test -DrunBugReproductionTests=true}</p>
 */
@DisplayName("EMF Containment Race Condition Tests")
class ContainmentRaceConditionTest {

    private static final Logger log = LoggerFactory.getLogger(ContainmentRaceConditionTest.class);

    /**
     * Number of parent elements to create (each has one child).
     */
    private static final int ELEMENT_COUNT = 1000;

    /**
     * Small chunk size to maximize thread interleaving.
     */
    private static final int SMALL_CHUNK_SIZE = 5;

    /**
     * Number of stress iterations.
     * Reduced to 2 for faster CI runs; increase to 20+ for thorough testing.
     */
    private static final int STRESS_ITERATIONS = 2;

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

        // Reset counters
        ParentPackageRule.executionCount.set(0);
        ChildClassRule.executionCount.set(0);
        ParentPackageRule.containmentSetCount.set(0);
    }

    private TransformationContext createContext(TransformationRegistry registry) {
        ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
        ModelProvider modelProvider = new ContainmentTestModelProvider();

        TransformationContext ctx = new TransformationContext(
                modelProvider, sourceResourceSet, targetResourceSet, extensionRegistry);
        ctx.setTargetPackage(EcorePackage.eINSTANCE);
        ctx.registerResource("source", sourceResourceSet);
        ctx.registerResource("target", targetResourceSet);
        ctx.setTransformationRegistry(registry);
        return ctx;
    }

    /**
     * Create source model: packages containing classes.
     */
    private void createSourceModel(int count) {
        for (int i = 0; i < count; i++) {
            EPackage pkg = EcoreFactory.eINSTANCE.createEPackage();
            pkg.setName("Package" + i);
            pkg.setNsPrefix("pkg" + i);
            pkg.setNsURI("http://test/pkg" + i);

            EClass cls = EcoreFactory.eINSTANCE.createEClass();
            cls.setName("Class" + i);
            pkg.getEClassifiers().add(cls);

            sourceResource.getContents().add(pkg);
        }
    }

    // ==================== Bug Reproduction Tests ====================

    @Nested
    @DisplayName("autoAddRootElements Bug Reproduction")
    @EnabledIfSystemProperty(named = "runBugReproductionTests", matches = "true",
            disabledReason = "Bug reproduction tests. Enable with -DrunBugReproductionTests=true")
    class AutoAddRootElementsBugReproduction {

        /**
         * This test demonstrates the sequential mode bug:
         * - With autoAddRootElements=true, createTarget() adds child to Resource.contents immediately
         * - When parent later adds child to containment, EMF does NOT remove from Resource.contents
         * - Result: children appear as both root elements AND contained elements
         *
         * This is expected to FAIL until the bug is fixed.
         */
        @Test
        @DisplayName("Sequential mode - autoAddRootElements causes duplicate root elements (BUG)")
        void sequentialModeBug() {
            createSourceModel(100);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(ParentPackageRule.class);
            registry.register(ChildClassRule.class);

            TransformationContext ctx = createContext(registry);
            ctx.setAutoAddRootElements(true);  // Enable the problematic mode

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx)
                    .parallel(false)  // Sequential
                    .build();

            executor.transform();

            // Log execution counts for debugging
            log.info("Sequential - Parent executions: {}, Child executions: {}, Containments set: {}",
                    ParentPackageRule.executionCount.get(),
                    ChildClassRule.executionCount.get(),
                    ParentPackageRule.containmentSetCount.get());

            // Count root elements in target resource
            int rootCount = targetResource.getContents().size();

            // All root elements should be packages (parents), not classes (children)
            int packageCount = 0;
            int classCount = 0;
            for (EObject obj : targetResource.getContents()) {
                if (obj instanceof EPackage) {
                    packageCount++;
                    EPackage pkg = (EPackage) obj;
                    log.debug("  Package '{}' has {} classifiers", pkg.getName(), pkg.getEClassifiers().size());
                } else if (obj instanceof EClass) {
                    classCount++;
                    EClass cls = (EClass) obj;
                    log.debug("  Class '{}' is orphaned (eContainer={})", cls.getName(), cls.eContainer());
                }
            }

            log.info("Sequential - Root elements: {}, Packages: {}, Classes (orphans): {}",
                    rootCount, packageCount, classCount);

            // BUG: This assertion currently fails because autoAddRootElements doesn't handle
            // the case where child elements are later added to containment
            assertEquals(100, packageCount, "Should have 100 packages as root elements");
            assertEquals(0, classCount,
                    "Classes should NOT be root elements - they are contained by packages. " +
                    "BUG: Found " + classCount + " orphaned classes due to autoAddRootElements issue.");
        }

        /**
         * This test attempts to reproduce the parallel mode race condition.
         * The race condition is subtle and may not manifest in every run.
         */
        @Test
        @DisplayName("Parallel mode - autoAddRootElements race condition (BUG)")
        void parallelModeRaceCondition() {
            createSourceModel(100);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(ParentPackageRule.class);
            registry.register(ChildClassRule.class);

            TransformationContext ctx = createContext(registry);
            ctx.setAutoAddRootElements(true);  // Enable the problematic mode

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx)
                    .parallel(true)
                    .parallelThreshold(10)
                    .chunkSize(10)
                    .build();

            executor.transform();

            // Count root elements in target resource
            int rootCount = targetResource.getContents().size();

            // All root elements should be packages (parents), not classes (children)
            int packageCount = 0;
            int classCount = 0;
            List<String> orphanedClasses = new ArrayList<>();
            for (EObject obj : targetResource.getContents()) {
                if (obj instanceof EPackage) {
                    packageCount++;
                } else if (obj instanceof EClass) {
                    classCount++;
                    orphanedClasses.add(((EClass) obj).getName());
                }
            }

            log.info("Parallel - Root elements: {}, Packages: {}, Classes (orphans): {}",
                    rootCount, packageCount, classCount);
            if (!orphanedClasses.isEmpty()) {
                log.warn("Orphaned classes (first 10): {}",
                        orphanedClasses.subList(0, Math.min(10, orphanedClasses.size())));
            }

            assertEquals(100, packageCount, "Should have 100 packages as root elements");
            assertEquals(0, classCount,
                    "Classes should NOT be root elements - they are contained by packages. " +
                    "Found " + classCount + " orphaned classes - indicates race condition or staging bug.");
        }
    }

    // ==================== Stress Tests ====================

    @Nested
    @DisplayName("Containment Race Condition Stress Tests")
    @EnabledIfSystemProperty(named = "runBugReproductionTests", matches = "true",
            disabledReason = "Bug reproduction tests for race condition. Enable with -DrunBugReproductionTests=true")
    class ContainmentRaceStressTests {

        /**
         * Stress test with many elements and small chunks to maximize interleaving.
         * This should trigger the race condition if it exists.
         */
        @RepeatedTest(STRESS_ITERATIONS)
        @DisplayName("Stress test - no orphaned children after parallel transformation")
        void stressTestNoOrphanedChildren() {
            createSourceModel(ELEMENT_COUNT);

            ParentPackageRule.executionCount.set(0);
            ChildClassRule.executionCount.set(0);
            ParentPackageRule.containmentSetCount.set(0);

            TransformationRegistry registry = new TransformationRegistry();
            registry.register(ParentPackageRule.class);
            registry.register(ChildClassRule.class);

            TransformationContext ctx = createContext(registry);
            ctx.setAutoAddRootElements(true);  // Enable the problematic mode

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(ctx)
                    .parallel(true)
                    .parallelThreshold(10)
                    .chunkSize(SMALL_CHUNK_SIZE)  // Small chunks = more interleaving
                    .build();

            executor.transform();

            // Count elements
            int packageCount = 0;
            int classCount = 0;
            for (EObject obj : targetResource.getContents()) {
                if (obj instanceof EPackage) {
                    packageCount++;
                } else if (obj instanceof EClass) {
                    classCount++;
                }
            }

            log.info("Stress iteration - Elements: {}, Packages: {}, Orphaned classes: {}, " +
                            "Parent executions: {}, Child executions: {}, Containments set: {}",
                    targetResource.getContents().size(), packageCount, classCount,
                    ParentPackageRule.executionCount.get(),
                    ChildClassRule.executionCount.get(),
                    ParentPackageRule.containmentSetCount.get());

            assertEquals(ELEMENT_COUNT, packageCount,
                    "Should have " + ELEMENT_COUNT + " packages as root elements");
            assertEquals(0, classCount,
                    "No classes should be orphaned as root elements. " +
                    "Found " + classCount + " orphaned classes - RACE CONDITION DETECTED!");
        }

        /**
         * Compare sequential vs parallel to detect element count differences.
         */
        @Test
        @DisplayName("Sequential vs Parallel - identical element counts")
        void compareSequentialVsParallel() {
            // First run: Sequential
            createSourceModel(ELEMENT_COUNT);
            TransformationRegistry registry1 = new TransformationRegistry();
            registry1.register(ParentPackageRule.class);
            registry1.register(ChildClassRule.class);

            TransformationContext ctx1 = createContext(registry1);
            ctx1.setAutoAddRootElements(true);

            TransformationExecutor executor1 = TransformationExecutor.builder()
                    .registry(registry1)
                    .context(ctx1)
                    .parallel(false)
                    .build();

            executor1.transform();
            int sequentialRootCount = targetResource.getContents().size();

            // Reset for second run
            setUp();

            // Second run: Parallel
            createSourceModel(ELEMENT_COUNT);
            TransformationRegistry registry2 = new TransformationRegistry();
            registry2.register(ParentPackageRule.class);
            registry2.register(ChildClassRule.class);

            TransformationContext ctx2 = createContext(registry2);
            ctx2.setAutoAddRootElements(true);

            TransformationExecutor executor2 = TransformationExecutor.builder()
                    .registry(registry2)
                    .context(ctx2)
                    .parallel(true)
                    .parallelThreshold(10)
                    .chunkSize(SMALL_CHUNK_SIZE)
                    .build();

            executor2.transform();
            int parallelRootCount = targetResource.getContents().size();

            log.info("Sequential root count: {}, Parallel root count: {}",
                    sequentialRootCount, parallelRootCount);

            assertEquals(sequentialRootCount, parallelRootCount,
                    "Sequential and parallel should produce identical root element counts. " +
                    "Difference indicates race condition or duplicate creation.");
        }
    }

    // ==================== Model Provider ====================

    static class ContainmentTestModelProvider implements ModelProvider {

        @Override
        @SuppressWarnings("unchecked")
        public <T extends EObject> Collection<T> getAllContents(ResourceSet resourceSet, Class<T> type) {
            List<T> result = new ArrayList<>();
            for (Resource resource : resourceSet.getResources()) {
                for (EObject obj : resource.getContents()) {
                    if (type.isInstance(obj)) {
                        result.add((T) obj);
                    }
                    // Also search contents recursively
                    obj.eAllContents().forEachRemaining(child -> {
                        if (type.isInstance(child)) {
                            result.add((T) child);
                        }
                    });
                }
            }
            return result;
        }
    }

    // ==================== Transformation Rules ====================

    /**
     * Parent rule: transforms EPackage to EPackage and sets up containment.
     * This rule calls equivalent() to get the child and sets the containment reference.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EPackage.class, target = EPackage.class)
    public static class ParentPackageRule {
        static final AtomicInteger executionCount = new AtomicInteger(0);
        static final AtomicInteger containmentSetCount = new AtomicInteger(0);

        @TransformRule(name = "ParentPackageRule")
        @Transform(type = EPackage.class)
        public TransformFunction<EPackage, EPackage> transformRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();

                EPackage target = ctx.createTarget(EPackage.class);
                target.setName(source.getName() + "_target");
                target.setNsPrefix(source.getNsPrefix() + "_target");
                target.setNsURI(source.getNsURI() + "/target");

                // Get the equivalent child (triggers child rule if not already transformed)
                for (EClassifier classifier : source.getEClassifiers()) {
                    if (classifier instanceof EClass) {
                        EClass sourceClass = (EClass) classifier;

                        // This triggers ChildClassRule via equivalent()
                        EClass targetClass = ctx.equivalent(sourceClass, EClass.class);

                        if (targetClass != null) {
                            // THIS IS THE RACE: setting containment while child may be staged
                            target.getEClassifiers().add(targetClass);
                            containmentSetCount.incrementAndGet();
                        }
                    }
                }

                return target;
            };
        }
    }

    /**
     * Child rule: transforms EClass to EClass.
     * With autoAddRootElements=true, this will stage the element as a root element.
     * But it should be contained by the parent, not be a root element.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class ChildClassRule {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "ChildClassRule")
        @Lazy
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EClass> transformRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();

                EClass target = ctx.createTarget(EClass.class);
                target.setName(source.getName() + "_target");

                // Don't add any delay - we want real race conditions
                return target;
            };
        }
    }
}
