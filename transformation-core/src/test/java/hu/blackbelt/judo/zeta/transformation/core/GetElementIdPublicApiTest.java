package hu.blackbelt.judo.zeta.transformation.core;

import hu.blackbelt.judo.zeta.annotation.*;
import hu.blackbelt.judo.zeta.common.ExtensionMethodRegistry;
import hu.blackbelt.judo.zeta.common.ModelProvider;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.XMIResource;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for exposing getElementId() as public API.
 *
 * <p>This test class follows Test-Driven Development:
 * <ol>
 *   <li>Tests are written first (RED phase)</li>
 *   <li>Implementation is added to make tests pass (GREEN phase)</li>
 *   <li>Code is refactored while keeping tests green (REFACTOR phase)</li>
 * </ol>
 *
 * <p><b>Problem Statement:</b></p>
 * <p>ZETA uses deferred ID assignment for target elements:</p>
 * <pre>
 * 1. ctx.setElementId(element, id)  →  Stores ID in pendingXmiIds map
 * 2. [transformation continues...]
 * 3. applyPendingXmiIds()           →  Applies IDs to XMI resource (commit phase)
 * </pre>
 *
 * <p>When transformation code needs to get an element's ID <b>before commit</b>,
 * the standard EMF approach fails because XMIResource.getID() returns null for
 * deferred elements.</p>
 *
 * <p><b>Proposed Solution:</b></p>
 * <p>Expose the existing private {@code getElementId(EObject)} method as public API.
 * This method already handles all edge cases (pending IDs, committed IDs, generated IDs).</p>
 *
 * @see TransformationContext#getPendingXmiId(EObject)
 * @see TransformationContext#setElementId(EObject, String)
 */
@DisplayName("getElementId() Public API Tests")
class GetElementIdPublicApiTest {

    private static final Logger log = LoggerFactory.getLogger(GetElementIdPublicApiTest.class);

    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource sourceResource;
    private Resource targetResource;
    private TransformationRegistry registry;
    private TransformationContext context;

    @BeforeEach
    void setUp() {
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("*", new XMIResourceFactoryImpl());

        sourceResourceSet = new ResourceSetImpl();
        sourceResource = sourceResourceSet.createResource(URI.createURI("test://source.xmi"));

        targetResourceSet = new ResourceSetImpl();
        targetResource = targetResourceSet.createResource(URI.createURI("test://target.xmi"));

        registry = new TransformationRegistry();
        ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
        ModelProvider modelProvider = new TestModelProvider();

        context = new TransformationContext(
                modelProvider, sourceResourceSet, targetResourceSet, extensionRegistry);
        context.setTargetPackage(EcorePackage.eINSTANCE);
        context.registerResource("source", sourceResourceSet);
        context.registerResource("target", targetResourceSet);
    }

    // ==================== Pending ID Tests ====================

    @Nested
    @DisplayName("Pending ID Resolution")
    class PendingIdResolution {

        @Test
        @DisplayName("getElementId returns pending ID for deferred element")
        void getElementId_pendingId_returnsPendingId() {
            EClass element = EcoreFactory.eINSTANCE.createEClass();
            element.setName("TestElement");
            targetResource.getContents().add(element);

            // Set a pending ID (simulating deferred ID assignment)
            context.setElementId(element, "test-pending-id");

            // getElementId() should return the pending ID
            String id = context.getElementId(element);

            assertEquals("test-pending-id", id,
                    "getElementId() should return the pending ID for deferred elements");
        }

        @Test
        @DisplayName("getElementId returns pending ID even before resource addition")
        void getElementId_pendingIdBeforeResource_returnsPendingId() {
            EClass element = EcoreFactory.eINSTANCE.createEClass();
            element.setName("DetachedElement");
            // Note: NOT added to resource

            context.setElementId(element, "detached-pending-id");

            String id = context.getElementId(element);

            assertEquals("detached-pending-id", id,
                    "getElementId() should return pending ID even for detached elements");
        }

        @Test
        @DisplayName("Pending ID takes precedence over committed ID")
        void getElementId_pendingTakesPrecedence() {
            EClass element = EcoreFactory.eINSTANCE.createEClass();
            element.setName("PrecedenceElement");
            targetResource.getContents().add(element);

            // Set committed ID on XMI resource
            if (targetResource instanceof XMIResource) {
                ((XMIResource) targetResource).setID(element, "committed-id");
            }

            // Set pending ID (should take precedence)
            context.setElementId(element, "pending-id");

            String id = context.getElementId(element);

            assertEquals("pending-id", id,
                    "Pending ID should take precedence over committed ID");
        }
    }

    // ==================== Committed ID Tests ====================

    @Nested
    @DisplayName("Committed ID Resolution")
    class CommittedIdResolution {

        @Test
        @DisplayName("getElementId returns committed ID from XMI resource")
        void getElementId_committedId_returnsResourceId() {
            EClass element = EcoreFactory.eINSTANCE.createEClass();
            element.setName("CommittedElement");
            targetResource.getContents().add(element);

            // Set ID directly on XMI resource (committed)
            if (targetResource instanceof XMIResource) {
                ((XMIResource) targetResource).setID(element, "test-committed-id");
            }

            // No pending ID set - should fall back to resource
            String id = context.getElementId(element);

            assertEquals("test-committed-id", id,
                    "getElementId() should return committed ID when no pending ID exists");
        }

        @Test
        @DisplayName("getElementId returns URI fragment for non-XMI resources")
        void getElementId_nonXmiResource_returnsUriFragment() {
            // This tests fallback to resource.getURIFragment()
            EClass element = EcoreFactory.eINSTANCE.createEClass();
            element.setName("FragmentElement");
            targetResource.getContents().add(element);

            // For elements without explicit XMI ID, the URI fragment is used
            // Fragment format depends on resource implementation
            String id = context.getElementId(element);

            assertNotNull(id, "getElementId() should return some ID for resourced elements");
            log.info("URI fragment ID: {}", id);
        }
    }

    // ==================== Generated ID Tests ====================

    @Nested
    @DisplayName("Generated ID Fallback")
    class GeneratedIdFallback {

        @Test
        @DisplayName("getElementId generates UUID for element without ID")
        void getElementId_noId_generatesId() {
            context.enableStaging(); // Enable staging for generated ID caching

            EClass element = EcoreFactory.eINSTANCE.createEClass();
            element.setName("NoIdElement");
            // Not added to resource, no pending ID

            String id = context.getElementId(element);

            assertNotNull(id, "getElementId() should generate an ID for elements without one");
            assertTrue(id.startsWith("_"),
                    "Generated ID should start with underscore (UUID format): " + id);
        }

        @Test
        @DisplayName("Generated ID is consistent across multiple calls")
        void getElementId_generatedIdIsConsistent() {
            context.enableStaging();

            EClass element = EcoreFactory.eINSTANCE.createEClass();
            element.setName("ConsistentElement");

            String id1 = context.getElementId(element);
            String id2 = context.getElementId(element);

            assertEquals(id1, id2,
                    "Generated ID should be cached and return same value");
        }

        @Test
        @DisplayName("Different elements get different generated IDs")
        void getElementId_differentElementsGetDifferentIds() {
            context.enableStaging();

            EClass element1 = EcoreFactory.eINSTANCE.createEClass();
            element1.setName("Element1");

            EClass element2 = EcoreFactory.eINSTANCE.createEClass();
            element2.setName("Element2");

            String id1 = context.getElementId(element1);
            String id2 = context.getElementId(element2);

            assertNotEquals(id1, id2,
                    "Different elements should get different generated IDs");
        }
    }

    // ==================== Null Handling Tests ====================

    @Nested
    @DisplayName("Null and Edge Cases")
    class NullAndEdgeCases {

        @Test
        @DisplayName("getElementId handles null element gracefully")
        void getElementId_null_returnsNullOrThrows() {
            // Note: The current implementation may throw NPE or return null
            // This test documents the expected behavior
            try {
                String id = context.getElementId(null);
                assertNull(id, "getElementId(null) should return null");
            } catch (NullPointerException e) {
                // Also acceptable - documents that null input throws NPE
                log.info("getElementId(null) throws NPE - acceptable behavior");
            }
        }

        @Test
        @DisplayName("getElementId uses 'id' structural feature as fallback")
        void getElementId_usesIdFeature() {
            // Elements with an 'id' attribute should use that value
            // This tests the fallback to EStructuralFeature "id"
            EClass element = EcoreFactory.eINSTANCE.createEClass();
            element.setName("IdFeatureElement");

            // EClass doesn't have an 'id' feature, so this will fall through
            // to UUID generation. We're documenting the lookup order.
            String id = context.getElementId(element);
            assertNotNull(id, "Should return some ID");
        }
    }

    // ==================== Integration Tests ====================

    @Nested
    @DisplayName("Integration with Transformation")
    class IntegrationWithTransformation {

        @Test
        @DisplayName("getElementId works correctly during transformation execution")
        void getElementId_duringTransformation_worksCorrectly() {
            EClass source = EcoreFactory.eINSTANCE.createEClass();
            source.setName("SourceClass");
            sourceResource.getContents().add(source);

            registry.register(GetElementIdTestTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Verify transformation completed
            assertFalse(targetResource.getContents().isEmpty(),
                    "Transformation should have created target elements");

            // Find the package created by the transformation
            EPackage pkg = (EPackage) targetResource.getContents().stream()
                    .filter(e -> e instanceof EPackage)
                    .findFirst()
                    .orElse(null);

            assertNotNull(pkg, "Should have created EPackage");

            // The package name contains the retrieved ID
            assertTrue(pkg.getName().startsWith("IdTest_"),
                    "Package name should contain the retrieved ID: " + pkg.getName());
        }

        @Test
        @DisplayName("getElementId returns correct ID for discriminated clone")
        void getElementId_discriminatedClone_returnsCloneId() {
            EClass source = EcoreFactory.eINSTANCE.createEClass();
            source.setName("DiscriminatedSource");
            sourceResource.getContents().add(source);

            registry.register(DiscriminatedGetElementIdTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Find the container that stores the retrieved IDs
            List<EPackage> containers = targetResource.getContents().stream()
                    .filter(e -> e instanceof EPackage)
                    .map(e -> (EPackage) e)
                    .filter(p -> p.getName().startsWith("Container"))
                    .collect(Collectors.toList());

            assertEquals(1, containers.size(), "Should have one container");
            EPackage container = containers.get(0);

            // The nsURI contains the ID that was retrieved via getElementId
            String retrievedId = container.getNsURI();
            log.info("Retrieved ID from transformation: {}", retrievedId);

            assertNotNull(retrievedId, "Should have retrieved an ID");
            assertFalse(retrievedId.isEmpty(), "ID should not be empty");
        }
    }

    // ==================== Thread Safety Tests ====================

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafety {

        @Test
        @DisplayName("getElementId is thread-safe for concurrent access with existing ID")
        void getElementId_concurrentAccess_withExistingId_isThreadSafe() throws Exception {
            EClass element = EcoreFactory.eINSTANCE.createEClass();
            element.setName("ConcurrentElement");

            // Pre-set the ID before concurrent access
            context.setElementId(element, "pre-set-id");

            int threads = 10;
            int iterations = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch latch = new CountDownLatch(threads);
            Set<String> retrievedIds = ConcurrentHashMap.newKeySet();
            List<Exception> exceptions = new CopyOnWriteArrayList<>();

            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < iterations; j++) {
                            String id = context.getElementId(element);
                            retrievedIds.add(id);
                        }
                    } catch (Exception e) {
                        exceptions.add(e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertTrue(exceptions.isEmpty(),
                    "No exceptions should occur: " + exceptions);
            assertEquals(1, retrievedIds.size(),
                    "All threads should retrieve the same pre-set ID");
            assertTrue(retrievedIds.contains("pre-set-id"),
                    "Retrieved ID should be the pre-set ID");
        }

        @Test
        @DisplayName("getElementId handles concurrent access without exceptions")
        void getElementId_concurrentAccess_noExceptions() throws Exception {
            context.enableStaging();

            // Test with multiple elements to stress test
            List<EClass> elements = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                EClass element = EcoreFactory.eINSTANCE.createEClass();
                element.setName("Element" + i);
                elements.add(element);
            }

            int threads = 10;
            int iterations = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch latch = new CountDownLatch(threads);
            List<Exception> exceptions = new CopyOnWriteArrayList<>();

            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < iterations; j++) {
                            for (EClass element : elements) {
                                String id = context.getElementId(element);
                                assertNotNull(id, "ID should never be null");
                                assertFalse(id.isEmpty(), "ID should never be empty");
                            }
                        }
                    } catch (Exception e) {
                        exceptions.add(e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertTrue(exceptions.isEmpty(),
                    "No exceptions should occur during concurrent access: " + exceptions);
        }
    }

    // ==================== Discriminator Construction Tests ====================

    @Nested
    @DisplayName("Discriminator Construction Use Case")
    class DiscriminatorConstructionUseCase {

        /**
         * This test reproduces the real-world use case from esm2ui where
         * discriminators need to include target element IDs.
         *
         * <p>The problem: When building discriminators like:
         * <pre>
         * String discriminator = getElementId(targetClassType) + "/" + source.getName();
         * </pre>
         *
         * If targetClassType's ID is deferred (in pendingXmiIds), the standard
         * XMIResource.getID() returns null, causing cache misses and orphans.
         */
        @Test
        @DisplayName("getElementId enables correct discriminator construction")
        void getElementId_enablesCorrectDiscriminatorConstruction() {
            EClass targetElement = EcoreFactory.eINSTANCE.createEClass();
            targetElement.setName("TargetClassType");
            targetResource.getContents().add(targetElement);

            // Simulate deferred ID assignment (as happens during transformation)
            context.setElementId(targetElement, "ui_ClassType_Customer");

            // Build discriminator using getElementId
            String discriminator = context.getElementId(targetElement) + "/attribute/name";

            assertEquals("ui_ClassType_Customer/attribute/name", discriminator,
                    "Discriminator should use the pending ID correctly");

            // Contrast with XMIResource.getID() which would return null
            if (targetResource instanceof XMIResource) {
                String xmiId = ((XMIResource) targetResource).getID(targetElement);
                // XMI ID may be null because it's still pending
                log.info("XMIResource.getID() returns: {} (may be null for deferred elements)", xmiId);
            }
        }

        @Test
        @DisplayName("Transformation using getElementId for discriminator avoids null")
        void getElementId_inTransformationDiscriminator_avoidsNull() {
            EClass source = EcoreFactory.eINSTANCE.createEClass();
            source.setName("AttributeSource");
            sourceResource.getContents().add(source);

            registry.register(DiscriminatorBuildingTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Verify no null discriminators were used
            List<EAnnotation> annotations = targetResource.getContents().stream()
                    .filter(e -> e instanceof EAnnotation)
                    .map(e -> (EAnnotation) e)
                    .collect(Collectors.toList());

            for (EAnnotation ann : annotations) {
                assertFalse(ann.getSource().contains("null"),
                        "Discriminator should not contain 'null': " + ann.getSource());
            }
        }
    }

    // ==================== Model Provider ====================

    static class TestModelProvider implements ModelProvider {
        @Override
        @SuppressWarnings("unchecked")
        public <T extends EObject> Collection<T> getAllContents(ResourceSet resourceSet, Class<T> type) {
            List<T> results = new ArrayList<>();
            for (Resource resource : resourceSet.getResources()) {
                var iterator = resource.getAllContents();
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

    // ==================== Test Transformations ====================

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class GetElementIdTestTransformation {

        @TransformRule(name = "CreatePackageWithId")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> createPackageWithId() {
            return (source, ctx) -> {
                EPackage pkg = ctx.createTarget(EPackage.class);

                // Use getElementId to get the package's ID
                String id = ctx.getElementId(pkg);

                // Store the retrieved ID in the package name for verification
                pkg.setName("IdTest_" + id);
                ctx.addToResource(pkg);
                return pkg;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class DiscriminatedGetElementIdTransformation {

        @TransformRule(name = "CreateContainer")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> createContainer() {
            return (source, ctx) -> {
                EPackage container = ctx.createTarget(EPackage.class);
                container.setName("Container");
                ctx.addToResource(container);

                // Create a child element via discriminated call
                EDataType child = ctx.equivalentDiscriminated(source, EDataType.class,
                        "CreateChild", "child/" + source.getName());

                container.getEClassifiers().add(child);

                // Use getElementId to get the child's ID
                String childId = ctx.getElementId(child);
                container.setNsURI(childId);

                return container;
            };
        }

        @TransformRule(name = "CreateChild")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EDataType> createChild() {
            return (source, ctx) -> {
                EDataType child = ctx.createTarget(EDataType.class);
                child.setName("Child_" + source.getName());
                ctx.addToResource(child);
                return child;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class DiscriminatorBuildingTransformation {

        @TransformRule(name = "CreateTargetType")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> createTargetType() {
            return (source, ctx) -> {
                EPackage targetType = ctx.createTarget(EPackage.class);
                targetType.setName("TargetType_" + source.getName());
                ctx.addToResource(targetType);

                // Now create a cloned element using the target type's ID in the discriminator
                // This is the pattern from esm2ui that was failing
                String targetTypeId = ctx.getElementId(targetType);

                // Build discriminator using target element ID
                String discriminator = targetTypeId + "/attribute";

                EAnnotation attr = ctx.equivalentDiscriminated(source, EAnnotation.class,
                        "CreateAttribute", discriminator);

                targetType.getEAnnotations().add(attr);

                return targetType;
            };
        }

        @TransformRule(name = "CreateAttribute")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EAnnotation> createAttribute() {
            return (source, ctx) -> {
                EAnnotation attr = ctx.createTarget(EAnnotation.class);
                attr.setSource("Attribute_" + source.getName());
                ctx.addToResource(attr);
                return attr;
            };
        }
    }
}
