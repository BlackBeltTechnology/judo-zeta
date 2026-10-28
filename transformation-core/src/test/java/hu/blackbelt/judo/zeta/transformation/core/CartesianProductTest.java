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

import hu.blackbelt.judo.zeta.annotation.Guard;
import hu.blackbelt.judo.zeta.annotation.Transform;
import hu.blackbelt.judo.zeta.annotation.TransformRule;
import hu.blackbelt.judo.zeta.annotation.To;
import hu.blackbelt.judo.zeta.common.ExtensionMethodRegistry;
import hu.blackbelt.judo.zeta.common.ModelProvider;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Cartesian product execution with multiple @Transform annotations.
 *
 * <p>When a rule has multiple @Transform annotations, the executor generates
 * a Cartesian product of all source elements and executes the rule for each tuple.</p>
 */
class CartesianProductTest {

    private ResourceSet sourceResourceSet;
    private ResourceSet mappingResourceSet;
    private ResourceSet targetResourceSet;
    private Resource sourceResource;
    private Resource mappingResource;
    private Resource targetResource;
    private hu.blackbelt.judo.zeta.transformation.core.TransformationContext context;
    private TransformationRegistry registry;

    // Static fields for test tracking (reset in setUp)
    static AtomicInteger executionCount;
    static List<String> executedPairs;
    static List<EObject[]> capturedSources;

    @BeforeEach
    void setUp() {
        // Reset static tracking fields
        executionCount = new AtomicInteger(0);
        executedPairs = Collections.synchronizedList(new ArrayList<>());
        capturedSources = Collections.synchronizedList(new ArrayList<>());

        // Register XMI resource factory for all extensions
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("*", new XMIResourceFactoryImpl());

        // Create source resource set with EClasses
        sourceResourceSet = new ResourceSetImpl();
        sourceResource = sourceResourceSet.createResource(URI.createURI("test://source.xmi"));

        // Create mapping resource set with EAnnotations
        mappingResourceSet = new ResourceSetImpl();
        mappingResource = mappingResourceSet.createResource(URI.createURI("test://mapping.xmi"));

        // Create target resource set
        targetResourceSet = new ResourceSetImpl();
        targetResource = targetResourceSet.createResource(URI.createURI("test://target.xmi"));

        // Create context with Ecore package as target
        ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
        ModelProvider modelProvider = new TestModelProvider();
        context = new hu.blackbelt.judo.zeta.transformation.core.TransformationContext(
                modelProvider, sourceResourceSet, targetResourceSet, extensionRegistry);
        context.setTargetPackage(EcorePackage.eINSTANCE);

        // Register resource aliases
        context.registerResource("source", sourceResourceSet);
        context.registerResource("mapping", mappingResourceSet);
        context.registerResource("target", targetResourceSet);

        registry = new TransformationRegistry();
    }

    // Helper methods
    private EClass createEClass(String name) {
        EClass eClass = EcoreFactory.eINSTANCE.createEClass();
        eClass.setName(name);
        sourceResource.getContents().add(eClass);
        return eClass;
    }

    private EAnnotation createMapping(String source, String targetName) {
        EAnnotation annotation = EcoreFactory.eINSTANCE.createEAnnotation();
        annotation.setSource(source);
        annotation.getDetails().put("targetName", targetName);
        mappingResource.getContents().add(annotation);
        return annotation;
    }

    // Cartesian Product Execution Tests

    @Test
    @DisplayName("should execute rule for each tuple in Cartesian product")
    void shouldExecuteRuleForEachTupleInCartesianProduct() {
        // Setup: 3 classes, 2 mappings -> 6 executions
        createEClass("Entity1");
        createEClass("Entity2");
        createEClass("Entity3");
        
        createMapping("typeA", "TableA");
        createMapping("typeB", "TableB");

        // Register multi-source transformation
        registry.register(MultiSourceTransformation.class);

        // Execute
        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        // Verify: 3 classes × 2 mappings = 6 executions
        assertEquals(6, executionCount.get(), 
                "Expected 6 executions for 3×2 Cartesian product");
        assertEquals(6, executedPairs.size());

        // Verify all combinations were executed
        assertTrue(executedPairs.contains("Entity1+typeA"));
        assertTrue(executedPairs.contains("Entity1+typeB"));
        assertTrue(executedPairs.contains("Entity2+typeA"));
        assertTrue(executedPairs.contains("Entity2+typeB"));
        assertTrue(executedPairs.contains("Entity3+typeA"));
        assertTrue(executedPairs.contains("Entity3+typeB"));
    }

    @Test
    @DisplayName("should skip execution when one source is empty")
    void shouldSkipExecutionWhenOneSourceIsEmpty() {
        // Setup: 3 classes, 0 mappings -> 0 executions
        createEClass("Entity1");
        createEClass("Entity2");
        createEClass("Entity3");
        // No mappings created

        registry.register(MultiSourceTransformation.class);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        // Verify: no executions when mapping is empty
        assertEquals(0, executionCount.get(), 
                "Expected 0 executions when one source is empty");
    }

    @Test
    @DisplayName("should pass source elements in correct order")
    void shouldPassSourceElementsInCorrectOrder() {
        createEClass("TestEntity");
        createMapping("testType", "TestTable");

        registry.register(SourceOrderCapture.class);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        assertEquals(1, capturedSources.size());
        EObject[] sources = capturedSources.get(0);
        assertEquals(2, sources.length);
        
        // First element should be EClass (from first @Transform)
        assertTrue(sources[0] instanceof EClass);
        assertEquals("TestEntity", ((EClass) sources[0]).getName());
        
        // Second element should be EAnnotation (from second @Transform)
        assertTrue(sources[1] instanceof EAnnotation);
        assertEquals("testType", ((EAnnotation) sources[1]).getSource());
    }

    // Multi-Source Guard Tests

    @Test
    @DisplayName("should filter tuples based on multi-source guard")
    void shouldFilterTuplesBasedOnMultiSourceGuard() {
        // Setup: 2 classes, 2 mappings
        createEClass("Entity1");
        createEClass("Entity2");
        
        // Only Entity1 should match with Entity1 mapping, Entity2 with Entity2
        createMapping("Entity1", "Table1");
        createMapping("Entity2", "Table2");

        registry.register(GuardedMultiSourceTransformation.class);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        // Guard filters to only matching pairs: (Entity1, Entity1), (Entity2, Entity2)
        assertEquals(2, executionCount.get(), 
                "Expected 2 executions where entity name matches mapping source");
    }

    @Test
    @DisplayName("should support single-source guard fallback for multi-source rule")
    void shouldSupportSingleSourceGuardFallback() {
        createEClass("ValidEntity");
        createEClass("InvalidEntity");
        
        createMapping("typeA", "TableA");

        registry.register(SingleGuardMultiSourceTransformation.class);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        // Only ValidEntity should pass the guard (based on first element)
        assertEquals(1, executionCount.get());
    }

    // Test transformation classes - must have no-arg constructors for registry

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class MultiSourceTransformation {
        public MultiSourceTransformation() {}

        @TransformRule(name = "MultiSourceRule")
        @Transform(alias = "source", type = EClass.class)
        @Transform(alias = "mapping", type = EAnnotation.class)
        @To(alias = "target", type = EClass.class)
        public MultiSourceTransformFunction<EClass> multiSourceRule() {
            return (sources, ctx) -> {
                executionCount.incrementAndGet();
                EClass entity = (EClass) sources[0];
                EAnnotation mapping = (EAnnotation) sources[1];
                executedPairs.add(entity.getName() + "+" + mapping.getSource());
                
                EClass result = EcoreFactory.eINSTANCE.createEClass();
                result.setName(entity.getName() + "_" + mapping.getSource());
                return result;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class SourceOrderCapture {
        public SourceOrderCapture() {}

        @TransformRule(name = "CaptureOrder")
        @Transform(alias = "source", type = EClass.class)
        @Transform(alias = "mapping", type = EAnnotation.class)
        @To(alias = "target", type = EClass.class)
        public MultiSourceTransformFunction<EClass> captureOrder() {
            return (sources, ctx) -> {
                capturedSources.add(sources.clone());
                return EcoreFactory.eINSTANCE.createEClass();
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class GuardedMultiSourceTransformation {
        public GuardedMultiSourceTransformation() {}

        @TransformRule(name = "GuardedMultiSource")
        @Transform(alias = "source", type = EClass.class)
        @Transform(alias = "mapping", type = EAnnotation.class)
        @To(alias = "target", type = EClass.class)
        @Guard(method = "matchingGuard")
        public MultiSourceTransformFunction<EClass> guardedRule() {
            return (sources, ctx) -> {
                executionCount.incrementAndGet();
                return EcoreFactory.eINSTANCE.createEClass();
            };
        }

        // Multi-source guard: entity name must match mapping source
        public boolean matchingGuard(EObject[] sources, 
                hu.blackbelt.judo.zeta.transformation.core.TransformationContext ctx) {
            EClass entity = (EClass) sources[0];
            EAnnotation mapping = (EAnnotation) sources[1];
            return entity.getName().equals(mapping.getSource());
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class SingleGuardMultiSourceTransformation {
        public SingleGuardMultiSourceTransformation() {}

        @TransformRule(name = "SingleGuardMultiSource")
        @Transform(alias = "source", type = EClass.class)
        @Transform(alias = "mapping", type = EAnnotation.class)
        @To(alias = "target", type = EClass.class)
        @Guard(method = "validEntityGuard")
        public MultiSourceTransformFunction<EClass> singleGuardRule() {
            return (sources, ctx) -> {
                executionCount.incrementAndGet();
                return EcoreFactory.eINSTANCE.createEClass();
            };
        }

        // Single-source guard fallback: only checks first element
        public boolean validEntityGuard(EObject source, 
                hu.blackbelt.judo.zeta.transformation.core.TransformationContext ctx) {
            EClass entity = (EClass) source;
            return entity.getName().startsWith("Valid");
        }
    }

    /**
     * Simple ModelProvider implementation for tests using Ecore metamodel.
     */
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
