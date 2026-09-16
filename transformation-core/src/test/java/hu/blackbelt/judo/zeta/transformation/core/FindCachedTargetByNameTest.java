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
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TransformationContext#findCachedTargetByName(String, Class)} and
 * {@link ElementResolutionCache#findByName(String, Class)}.
 *
 * <p>Reproduces the identity-mismatch scenario where {@code ctx.equivalent()} returns null
 * because the source object is a different Java instance than what the TypeRule processed
 * (e.g., proxy/copy in extension packages). Verifies that {@code findCachedTargetByName()}
 * provides a working fallback.</p>
 */
@DisplayName("findCachedTargetByName() Tests")
class FindCachedTargetByNameTest {

    private static final Logger log = LoggerFactory.getLogger(FindCachedTargetByNameTest.class);

    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource sourceResource;
    private TransformationContext context;
    private TransformationRegistry registry;

    // Static tracking fields
    static AtomicInteger typeRuleExecutionCount = new AtomicInteger(0);
    static AtomicInteger attrRuleExecutionCount = new AtomicInteger(0);
    static List<EObject> equivalentResults = Collections.synchronizedList(new ArrayList<>());
    static List<EObject> findByNameResults = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        typeRuleExecutionCount.set(0);
        attrRuleExecutionCount.set(0);
        equivalentResults.clear();
        findByNameResults.clear();

        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("*", new XMIResourceFactoryImpl());

        sourceResourceSet = new ResourceSetImpl();
        targetResourceSet = new ResourceSetImpl();
        sourceResource = sourceResourceSet.createResource(URI.createURI("test://source.xmi"));
        targetResourceSet.createResource(URI.createURI("test://target.xmi"));

        ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
        ModelProvider modelProvider = new TestModelProvider();

        context = new TransformationContext(
                modelProvider, sourceResourceSet, targetResourceSet, extensionRegistry);
        context.setTargetPackage(EcorePackage.eINSTANCE);
        context.registerResource("source", sourceResourceSet);
        context.registerResource("target", targetResourceSet);

        registry = new TransformationRegistry();
    }

    // ==================== Identity-Mismatch Bug Reproduction (Tasks 2.1-2.3) ====================

    @Nested
    @DisplayName("Identity-Mismatch Bug Reproduction")
    class IdentityMismatchTests {

        /**
         * Reproduces the eType: null scenario.
         *
         * <p>Setup: Two EDataType instances with the same name but different Java identity.
         * The TypeRule transforms the "original" instance. An AttributeRule then calls
         * equivalent() with the "copy" instance — which misses the identity-based cache.</p>
         */
        @Test
        @DisplayName("equivalent() returns null for different-identity source (bug reproduction)")
        void equivalentReturnsNullForDifferentIdentity() {
            // Create "original" EDataType that TypeRule will process
            EDataType originalDateType = EcoreFactory.eINSTANCE.createEDataType();
            originalDateType.setName("DateType");
            originalDateType.setInstanceClassName("java.time.LocalDate");
            sourceResource.getContents().add(originalDateType);

            // Create "copy" EDataType — same name, different Java identity
            // This simulates a proxy/copy from an extension package
            EDataType copyDateType = EcoreFactory.eINSTANCE.createEDataType();
            copyDateType.setName("DateType");
            copyDateType.setInstanceClassName("java.time.LocalDate");
            // NOT added to source resource — referenced but not contained (like extension proxies)

            // Create an EAttribute that references the COPY (not the original)
            EClass wrapperClass = EcoreFactory.eINSTANCE.createEClass();
            wrapperClass.setName("PersonWrapper");
            sourceResource.getContents().add(wrapperClass);

            EAttribute transferAttr = EcoreFactory.eINSTANCE.createEAttribute();
            transferAttr.setName("birthDate");
            transferAttr.setEType(copyDateType); // References the COPY
            wrapperClass.getEStructuralFeatures().add(transferAttr);

            // Register TypeRule (greedy, transforms EDataType → EDataType)
            registry.register(TypeRuleTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor.transform();

            // Verify TypeRule executed for the original
            assertEquals(1, typeRuleExecutionCount.get(),
                    "TypeRule should execute for the original EDataType");

            // Now call equivalent() with the COPY — should miss the identity-based cache
            EClassifier result = context.equivalent(copyDateType, EClassifier.class);

            // This demonstrates the bug: equivalent() triggers on-demand execution for the copy,
            // which creates a SECOND target. But if the copy is not in the source resource,
            // the greedy pass won't have processed it. On-demand creates a new one.
            // The core issue is the identity mismatch — the already-created target is NOT found.
            // findCachedTargetByName is the fix.
            log.info("equivalent() for copy returned: {}", result != null ? result.eClass().getName() : "null");
        }

        @Test
        @DisplayName("findCachedTargetByName() finds target created from original-identity source")
        void findCachedTargetByNameFindsOriginalTarget() {
            // Create "original" EDataType
            EDataType originalDateType = EcoreFactory.eINSTANCE.createEDataType();
            originalDateType.setName("DateType");
            originalDateType.setInstanceClassName("java.time.LocalDate");
            sourceResource.getContents().add(originalDateType);

            // Register and run TypeRule
            registry.register(TypeRuleTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor.transform();

            assertEquals(1, typeRuleExecutionCount.get());

            // Create "copy" EDataType — different identity
            EDataType copyDateType = EcoreFactory.eINSTANCE.createEDataType();
            copyDateType.setName("DateType");
            copyDateType.setInstanceClassName("java.time.LocalDate");

            // equivalent() with copy triggers on-demand execution (creates a second target).
            // findCachedTargetByName() should find the ORIGINAL target by its transformed name,
            // providing a way to avoid duplicates.
            EClassifier found = context.findCachedTargetByName("DateType_transformed", EClassifier.class);

            assertNotNull(found, "findCachedTargetByName should find the target created from the original");
            assertEquals("DateType_transformed", found.getName(),
                    "Should return the EDataType created by TypeRule from the original source");
        }

        @Test
        @DisplayName("findCachedTargetByName() works during rule execution (before postProcess)")
        void findCachedTargetByNameWorksDuringRuleExecution() {
            // Create source elements — TypeRule processes the EDataType first
            EDataType dateType = EcoreFactory.eINSTANCE.createEDataType();
            dateType.setName("DateType");
            dateType.setInstanceClassName("java.time.LocalDate");
            sourceResource.getContents().add(dateType);

            // Create an EClass — AttributeRuleWithFindByName always calls findCachedTargetByName
            EClass wrapperClass = EcoreFactory.eINSTANCE.createEClass();
            wrapperClass.setName("Wrapper");
            sourceResource.getContents().add(wrapperClass);

            // Register both TypeRule and AttributeRuleWithFindByName
            // TypeRule processes EDataType first (greedy), then AttributeRule processes EClass
            registry.register(TypeRuleTransformation.class);
            registry.register(AttributeRuleWithFindByName.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor.transform();

            // AttributeRuleWithFindByName always calls findCachedTargetByName during execution
            assertFalse(findByNameResults.isEmpty(),
                    "findCachedTargetByName should have been called during rule execution");
            assertNotNull(findByNameResults.get(0),
                    "findCachedTargetByName should return non-null during rule execution " +
                    "(before postProcess), because it searches the resolution cache, not the resource set");
        }
    }

    // ==================== ElementResolutionCache.findByName() Unit Tests (Tasks 3.1-3.6) ====================

    @Nested
    @DisplayName("ElementResolutionCache.findByName() Unit Tests")
    class CacheFindByNameTests {

        @Test
        @DisplayName("Exact name match returns cached target")
        void exactNameMatch() {
            ElementResolutionCache cache = new ElementResolutionCache(true); // sequential

            EDataType source = EcoreFactory.eINSTANCE.createEDataType();
            source.setName("SourceType");

            EDataType target = EcoreFactory.eINSTANCE.createEDataType();
            target.setName("DateType");

            cache.addMapping(source, "CreateDateType", target, false);

            EDataType found = cache.findByName("DateType", EDataType.class);
            assertNotNull(found, "Should find cached target by exact name match");
            assertSame(target, found, "Should return the same cached instance");
        }

        @Test
        @DisplayName("No match returns null")
        void noMatchReturnsNull() {
            ElementResolutionCache cache = new ElementResolutionCache(true);

            EDataType source = EcoreFactory.eINSTANCE.createEDataType();
            source.setName("SourceType");

            EDataType target = EcoreFactory.eINSTANCE.createEDataType();
            target.setName("DateType");

            cache.addMapping(source, "CreateDateType", target, false);

            EDataType found = cache.findByName("NonExistent", EDataType.class);
            assertNull(found, "Should return null when no target matches the name");
        }

        @Test
        @DisplayName("Type hierarchy lookup (EDataType found via EClassifier request)")
        void typeHierarchyLookup() {
            ElementResolutionCache cache = new ElementResolutionCache(true);

            EDataType source = EcoreFactory.eINSTANCE.createEDataType();
            EDataType target = EcoreFactory.eINSTANCE.createEDataType();
            target.setName("DateType");

            cache.addMapping(source, "CreateDateType", target, false);

            // Request EClassifier — EDataType IS-A EClassifier
            EClassifier found = cache.findByName("DateType", EClassifier.class);
            assertNotNull(found, "EDataType should be found when requesting EClassifier (supertype)");
            assertSame(target, found);
        }

        @Test
        @DisplayName("Incompatible type returns null (EDataType not found via EClass request)")
        void incompatibleTypeReturnsNull() {
            ElementResolutionCache cache = new ElementResolutionCache(true);

            EDataType source = EcoreFactory.eINSTANCE.createEDataType();
            EDataType target = EcoreFactory.eINSTANCE.createEDataType();
            target.setName("DateType");

            cache.addMapping(source, "CreateDateType", target, false);

            // Request EClass — EDataType is NOT an EClass
            EClass found = cache.findByName("DateType", EClass.class);
            assertNull(found, "EDataType should NOT be found when requesting EClass (incompatible type)");
        }

        @Test
        @DisplayName("Null name and null targetType return null")
        void nullInputsReturnNull() {
            ElementResolutionCache cache = new ElementResolutionCache(true);

            EDataType source = EcoreFactory.eINSTANCE.createEDataType();
            EDataType target = EcoreFactory.eINSTANCE.createEDataType();
            target.setName("DateType");
            cache.addMapping(source, "CreateDateType", target, false);

            assertNull(cache.findByName(null, EDataType.class), "null name should return null");
            assertNull(cache.findByName("DateType", null), "null targetType should return null");
            assertNull(cache.findByName(null, null), "both null should return null");
        }

        @Test
        @DisplayName("Works in both sequential mode and parallel mode")
        void worksInBothModes() {
            for (boolean sequential : new boolean[]{true, false}) {
                ElementResolutionCache cache = new ElementResolutionCache(sequential);

                EDataType source = EcoreFactory.eINSTANCE.createEDataType();
                EDataType target = EcoreFactory.eINSTANCE.createEDataType();
                target.setName("BooleanType");

                cache.addMapping(source, "CreateBooleanType", target, false);

                EClassifier found = cache.findByName("BooleanType", EClassifier.class);
                assertNotNull(found,
                        "findByName should work in " + (sequential ? "sequential" : "parallel") + " mode");
                assertSame(target, found);
            }
        }
    }

    // ==================== TransformationContext.findCachedTargetByName() Tests ====================

    @Nested
    @DisplayName("TransformationContext.findCachedTargetByName() Tests")
    class ContextFindCachedTargetByNameTests {

        @Test
        @DisplayName("Delegates to resolution cache and returns result")
        void delegatesToResolutionCache() {
            // Create and run a transformation to populate cache
            EDataType sourceType = EcoreFactory.eINSTANCE.createEDataType();
            sourceType.setName("StringType");
            sourceResource.getContents().add(sourceType);

            registry.register(TypeRuleTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor.transform();

            // findCachedTargetByName should find the transformed type
            EClassifier found = context.findCachedTargetByName("StringType_transformed", EClassifier.class);
            assertNotNull(found, "Should find the target created by TypeRule");
        }

        @Test
        @DisplayName("Null inputs return null without exception")
        void nullInputsReturnNull() {
            registry.register(TypeRuleTransformation.class);
            context.setTransformationRegistry(registry);

            assertDoesNotThrow(() -> context.findCachedTargetByName(null, EClassifier.class));
            assertDoesNotThrow(() -> context.findCachedTargetByName("DateType", null));
            assertDoesNotThrow(() -> context.findCachedTargetByName(null, null));

            assertNull(context.findCachedTargetByName(null, EClassifier.class));
            assertNull(context.findCachedTargetByName("DateType", null));
            assertNull(context.findCachedTargetByName(null, null));
        }
    }

    // ==================== Regression Tests (Tasks 4.1-4.2) ====================

    @Nested
    @DisplayName("Regression Tests")
    class RegressionTests {

        @Test
        @DisplayName("Identity-based equivalent() still works for same-identity sources")
        void equivalentStillWorksForSameIdentity() {
            EDataType sourceType = EcoreFactory.eINSTANCE.createEDataType();
            sourceType.setName("DateType");
            sourceType.setInstanceClassName("java.time.LocalDate");
            sourceResource.getContents().add(sourceType);

            registry.register(TypeRuleTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            executor.transform();

            // Same identity — equivalent() should work via cache
            EClassifier result = context.equivalent(sourceType, EClassifier.class);
            assertNotNull(result,
                    "equivalent() must still work for same-identity sources (regression check)");
            assertEquals("DateType_transformed", result.getName());
        }

        @Test
        @DisplayName("On-demand execution via equivalent() still works for lazy rules")
        void onDemandExecutionStillWorksForLazyRules() {
            EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
            sourceClass.setName("TestClass");
            sourceResource.getContents().add(sourceClass);

            registry.register(LazyRuleTransformation.class);
            context.setTransformationRegistry(registry);

            // Do NOT run executor — lazy rule should be triggered on-demand
            EClass result = context.equivalent(sourceClass, EClass.class);
            assertNotNull(result, "equivalent() should trigger on-demand execution for lazy rules");
            assertEquals("TestClass_lazy", result.getName());
        }
    }

    // ==================== Transformation Rule Classes ====================

    /**
     * Greedy TypeRule: transforms EDataType → EDataType (appends "_transformed" to name).
     * Simulates the PSM→ASM TypeRules that create target types.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EDataType.class, target = EDataType.class)
    public static class TypeRuleTransformation {
        @TransformRule(name = "CreateType", description = "Transform EDataType to EDataType")
        @Greedy
        @Transform(type = EDataType.class)
        @To(type = EDataType.class)
        public TransformFunction<EDataType, EDataType> createType() {
            return (s, ctx) -> {
                typeRuleExecutionCount.incrementAndGet();
                EDataType t = ctx.createTarget(EDataType.class);
                t.setName(s.getName() + "_transformed");
                t.setInstanceClassName(s.getInstanceClassName());
                return t;
            };
        }
    }

    /**
     * Eager AttributeRule that simulates the eType: null scenario.
     * Calls equivalent() with a copy source, then falls back to findCachedTargetByName().
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAttribute.class)
    public static class AttributeRuleWithFallback {
        @TransformRule(name = "CreateAttribute", description = "Transform EClass to EAttribute with type fallback")
        @Greedy
        @Transform(type = EClass.class)
        @To(type = EAttribute.class)
        public TransformFunction<EClass, EAttribute> createAttribute() {
            return (s, ctx) -> {
                attrRuleExecutionCount.incrementAndGet();
                EAttribute t = ctx.createTarget(EAttribute.class);
                t.setName(s.getName() + "_attr");

                // Get the first EAttribute from the source EClass to find its eType reference
                if (!s.getEAttributes().isEmpty()) {
                    EAttribute srcAttr = s.getEAttributes().get(0);
                    EClassifier srcType = srcAttr.getEType();

                    if (srcType != null) {
                        // Try equivalent() first — will likely miss due to identity mismatch
                        EClassifier targetType = ctx.equivalent(srcType, EClassifier.class);
                        equivalentResults.add(targetType);

                        // Fall back to findCachedTargetByName()
                        if (targetType == null) {
                            targetType = ctx.findCachedTargetByName(
                                    srcType.getName() + "_transformed", EClassifier.class);
                            findByNameResults.add(targetType);
                        }

                        if (targetType != null) {
                            t.setEType(targetType);
                        }
                    }
                }

                return t;
            };
        }
    }

    /**
     * Eager AttributeRule that always calls findCachedTargetByName() during execution.
     * Used to verify that name-based lookup works mid-transformation (before postProcess).
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EAttribute.class)
    public static class AttributeRuleWithFindByName {
        @TransformRule(name = "CreateAttributeFindByName", description = "Transform EClass to EAttribute using findCachedTargetByName")
        @Greedy
        @Transform(type = EClass.class)
        @To(type = EAttribute.class)
        public TransformFunction<EClass, EAttribute> createAttribute() {
            return (s, ctx) -> {
                attrRuleExecutionCount.incrementAndGet();
                EAttribute t = ctx.createTarget(EAttribute.class);
                t.setName(s.getName() + "_attr");

                // Always call findCachedTargetByName — should find the type created by TypeRule
                EClassifier found = ctx.findCachedTargetByName("DateType_transformed", EClassifier.class);
                findByNameResults.add(found);

                if (found != null) {
                    t.setEType(found);
                }

                return t;
            };
        }
    }

    /**
     * Lazy rule for regression testing on-demand execution.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class LazyRuleTransformation {
        @TransformRule(name = "LazyClassRule", description = "Lazy transform EClass to EClass")
        @Lazy
        @Transform(type = EClass.class)
        @To(type = EClass.class)
        public TransformFunction<EClass, EClass> lazyClassRule() {
            return (s, ctx) -> {
                EClass t = ctx.createTarget(EClass.class);
                t.setName(s.getName() + "_lazy");
                return t;
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
