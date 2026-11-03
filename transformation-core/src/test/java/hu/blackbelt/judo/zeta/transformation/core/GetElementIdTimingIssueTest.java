package hu.blackbelt.judo.zeta.transformation.core;

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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to prove the timing issue with getElementId() on newly created targets.
 *
 * <p><b>Problem Statement:</b></p>
 * <p>When a rule calls {@code ctx.createTarget()} and then immediately calls
 * {@code ctx.getElementId(target)}, the element may not have its structured ID
 * set yet (because {@code setElementId()} is called later by the framework or
 * another rule). In this case, {@code getElementId()} generates and caches a
 * new UUID, which doesn't match the element's eventual structured ID.</p>
 *
 * <p><b>Consequence:</b></p>
 * <p>If a discriminator is built using this premature UUID, cache lookups using
 * the correct structured ID will miss, potentially causing duplicate elements
 * or orphans.</p>
 *
 * <p><b>Timeline:</b></p>
 * <pre>
 * 1. Rule calls createTarget() → element created, no ID yet
 * 2. Rule calls getElementId(target) → UUID generated and cached in pendingXmiIds
 * 3. Framework calls setElementId(target, structuredId) → overwrites cached UUID
 * 4. Discriminator built with UUID doesn't match element's actual structuredId
 * </pre>
 */
@DisplayName("getElementId() Timing Issue Tests")
class GetElementIdTimingIssueTest {

    private static final Logger log = LoggerFactory.getLogger(GetElementIdTimingIssueTest.class);

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

    // ==================== Timing Issue Tests ====================

    @Test
    @DisplayName("getElementId on createTarget before setElementId returns generated UUID")
    void getElementId_beforeSetElementId_returnsGeneratedUuid() {
        context.enableStaging();

        // Simulate what happens inside a rule:
        // 1. createTarget creates element
        EClass target = EcoreFactory.eINSTANCE.createEClass();
        target.setName("TestTarget");

        // 2. Immediately call getElementId - no ID has been set yet
        String idBeforeSet = context.getElementId(target);

        log.info("ID before setElementId: {}", idBeforeSet);

        // This should be a generated UUID (starts with underscore)
        assertTrue(idBeforeSet.startsWith("_"),
                "getElementId before setElementId should return generated UUID: " + idBeforeSet);

        // 3. Now simulate what the framework does later - set the structured ID
        String structuredId = "(source/abc123)/MyRule";
        context.setElementId(target, structuredId);

        // 4. Get the ID again
        String idAfterSet = context.getElementId(target);

        log.info("ID after setElementId: {}", idAfterSet);

        // The ID should now be the structured ID
        assertEquals(structuredId, idAfterSet,
                "getElementId after setElementId should return structured ID");

        // PROBLEM: The first call returned a different ID than what the element actually has!
        assertNotEquals(idBeforeSet, idAfterSet,
                "TIMING ISSUE CONFIRMED: getElementId returned different IDs before/after setElementId");

        log.warn("TIMING ISSUE: First getElementId returned '{}', but element's actual ID is '{}'",
                idBeforeSet, idAfterSet);
    }

    @Test
    @DisplayName("Discriminator built with premature ID doesn't match element's actual ID")
    void discriminatorWithPrematureId_doesNotMatchActualId() {
        context.enableStaging();

        // Create target element
        EClass target = EcoreFactory.eINSTANCE.createEClass();
        target.setName("TargetType");

        // Build discriminator using premature getElementId call
        String prematureId = context.getElementId(target);
        String discriminatorWithPrematureId = prematureId + "/attribute/name";

        log.info("Discriminator with premature ID: {}", discriminatorWithPrematureId);

        // Now the framework sets the actual structured ID
        String structuredId = "(source/xyz)/CreateTargetType";
        context.setElementId(target, structuredId);

        // Build the "correct" discriminator (what it should have been)
        String discriminatorWithActualId = structuredId + "/attribute/name";

        log.info("Discriminator with actual ID: {}", discriminatorWithActualId);

        // PROBLEM: These discriminators don't match!
        assertNotEquals(discriminatorWithPrematureId, discriminatorWithActualId,
                "MISMATCH CONFIRMED: Discriminator built with premature ID doesn't match");

        log.error("CACHE MISS SCENARIO: Lookup with '{}' won't find element cached under '{}'",
                discriminatorWithActualId, discriminatorWithPrematureId);
    }

    @Test
    @DisplayName("Real transformation scenario: getElementId inside rule before framework sets ID")
    void realTransformationScenario_getElementIdInsideRule() {
        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName("SourceClass");
        sourceResource.getContents().add(source);

        // Capture IDs for analysis
        AtomicReference<String> idInsideRule = new AtomicReference<>();
        AtomicReference<String> idAfterTransform = new AtomicReference<>();

        registry.register(TimingIssueTransformation.class);
        TimingIssueTransformation.capturedIdInsideRule = idInsideRule;

        context.setTransformationRegistry(registry);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        // Get the target element
        EPackage target = (EPackage) targetResource.getContents().stream()
                .filter(e -> e instanceof EPackage)
                .findFirst()
                .orElse(null);

        assertNotNull(target, "Should have created target");

        // Get the ID after transformation completes
        idAfterTransform.set(context.getElementId(target));

        log.info("ID captured inside rule: {}", idInsideRule.get());
        log.info("ID after transformation: {}", idAfterTransform.get());

        // Check if the IDs match
        if (idInsideRule.get() != null && idAfterTransform.get() != null) {
            if (!idInsideRule.get().equals(idAfterTransform.get())) {
                log.error("TIMING ISSUE IN REAL TRANSFORMATION:");
                log.error("  - ID inside rule:       {}", idInsideRule.get());
                log.error("  - ID after transform:   {}", idAfterTransform.get());

                // This assertion documents the issue
                fail("TIMING ISSUE: ID captured inside rule (" + idInsideRule.get() +
                        ") doesn't match ID after transform (" + idAfterTransform.get() + ")");
            } else {
                log.info("IDs match - no timing issue in this scenario");
            }
        }
    }

    @Test
    @DisplayName("Cross-rule scenario: Rule A creates target, calls getElementId, Rule B uses same element")
    void crossRuleScenario_differentIdsObserved() {
        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName("CrossRuleSource");
        sourceResource.getContents().add(source);

        // Capture IDs from both rules
        AtomicReference<String> idFromRuleA = new AtomicReference<>();
        AtomicReference<String> idFromRuleB = new AtomicReference<>();

        registry.register(CrossRuleTimingTransformation.class);
        CrossRuleTimingTransformation.capturedIdFromRuleA = idFromRuleA;
        CrossRuleTimingTransformation.capturedIdFromRuleB = idFromRuleB;

        context.setTransformationRegistry(registry);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        log.info("ID from Rule A (creator): {}", idFromRuleA.get());
        log.info("ID from Rule B (consumer): {}", idFromRuleB.get());

        // Both rules should see the same ID for the same element
        if (idFromRuleA.get() != null && idFromRuleB.get() != null) {
            assertEquals(idFromRuleA.get(), idFromRuleB.get(),
                    "Both rules should see the same ID for the same element");
        }
    }

    @Test
    @DisplayName("Equivalent call uses structured ID, getElementId may return different")
    void equivalentCall_usesStructuredId_getElementIdMayDiffer() {
        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName("EquivalentSource");
        sourceResource.getContents().add(source);

        AtomicReference<String> idFromGetElementId = new AtomicReference<>();
        AtomicReference<String> structuredIdFromEquivalent = new AtomicReference<>();

        registry.register(EquivalentIdComparisonTransformation.class);
        EquivalentIdComparisonTransformation.capturedGetElementId = idFromGetElementId;
        EquivalentIdComparisonTransformation.capturedStructuredId = structuredIdFromEquivalent;

        context.setTransformationRegistry(registry);
        context.setUseStructuredIds(true);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        log.info("ID from getElementId (inside rule): {}", idFromGetElementId.get());
        log.info("Structured ID (from getPendingXmiId after rule): {}", structuredIdFromEquivalent.get());

        // Document whether they match or not
        if (idFromGetElementId.get() != null && structuredIdFromEquivalent.get() != null) {
            if (idFromGetElementId.get().equals(structuredIdFromEquivalent.get())) {
                log.info("SUCCESS: getElementId returns the structured ID");
            } else {
                log.warn("MISMATCH: getElementId returned '{}' but structured ID is '{}'",
                        idFromGetElementId.get(), structuredIdFromEquivalent.get());
            }
        }
    }

    @Test
    @DisplayName("esm2ui pattern: Use target element ID in discriminator for equivalentDiscriminated")
    void esm2uiPattern_useTargetIdInDiscriminator() {
        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName("TransferObjectType");
        sourceResource.getContents().add(source);

        AtomicReference<String> targetTypeIdUsedInDiscriminator = new AtomicReference<>();
        AtomicReference<String> targetTypeActualId = new AtomicReference<>();
        AtomicReference<String> discriminatorUsed = new AtomicReference<>();

        registry.register(Esm2UiPatternTransformation.class);
        Esm2UiPatternTransformation.capturedTargetTypeId = targetTypeIdUsedInDiscriminator;
        Esm2UiPatternTransformation.capturedTargetTypeActualId = targetTypeActualId;
        Esm2UiPatternTransformation.capturedDiscriminator = discriminatorUsed;

        context.setTransformationRegistry(registry);
        context.setUseStructuredIds(true);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        log.info("=== esm2ui Pattern Test Results ===");
        log.info("Target type ID used in discriminator: {}", targetTypeIdUsedInDiscriminator.get());
        log.info("Target type actual ID (after transform): {}", targetTypeActualId.get());
        log.info("Discriminator used: {}", discriminatorUsed.get());

        // Check if the ID used matches the actual ID
        if (targetTypeIdUsedInDiscriminator.get() != null && targetTypeActualId.get() != null) {
            if (targetTypeIdUsedInDiscriminator.get().equals(targetTypeActualId.get())) {
                log.info("SUCCESS: IDs match - discriminator uses correct ID");
            } else {
                log.error("TIMING ISSUE CONFIRMED:");
                log.error("  ID used in discriminator: {}", targetTypeIdUsedInDiscriminator.get());
                log.error("  Actual ID of target:      {}", targetTypeActualId.get());
                log.error("  Cache lookups with actual ID will MISS!");

                // This is the bug - the discriminator has wrong ID
                fail("TIMING ISSUE: Discriminator built with ID '" + targetTypeIdUsedInDiscriminator.get() +
                        "' but target's actual ID is '" + targetTypeActualId.get() + "'");
            }
        }
    }

    @Test
    @DisplayName("Race condition PREVENTED: setElementId after external read throws exception")
    void raceCondition_getElementIdBetweenCreateAndSetOverride_throwsException() {
        // This test verifies that the race condition is PREVENTED by throwing an exception
        // when setElementId is called after another rule has read the ID.

        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName("RaceSource");
        sourceResource.getContents().add(source);

        registry.register(RaceConditionTransformation.class);
        RaceConditionTransformation.capturedIdAfterCreateTarget = new AtomicReference<>();
        RaceConditionTransformation.capturedIdAfterSetElementId = new AtomicReference<>();
        RaceConditionTransformation.capturedIdSeenByOtherRule = new AtomicReference<>();
        RaceConditionTransformation.capturedCustomId = new AtomicReference<>();

        context.setTransformationRegistry(registry);
        context.setUseStructuredIds(true);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        // The transformation should throw because setElementId is called after external read
        TransformationException ex = assertThrows(TransformationException.class, executor::transform);

        log.info("=== Race Condition Prevention Test ===");
        log.info("Exception caught as expected: {}", ex.getMessage());

        // Verify exception message contains helpful info
        assertTrue(ex.getMessage().contains("read by another rule") ||
                        ex.getCause().getMessage().contains("read by another rule"),
                "Exception should explain the race condition prevention: " + ex.getMessage());
    }

    @Test
    @DisplayName("Parallel execution PREVENTED: setElementId after other rule reads throws exception")
    void parallelExecution_customIdSetAfterOtherRuleReadsId_throwsException() {
        // This test verifies that race conditions are PREVENTED when Rule A tries to
        // set a custom ID after Rule B has already read the initial ID.

        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName("ParallelSource");
        sourceResource.getContents().add(source);

        registry.register(ParallelCustomIdTransformation.class);
        ParallelCustomIdTransformation.capturedIdSetByRuleA = new AtomicReference<>();
        ParallelCustomIdTransformation.capturedIdSeenByRuleB = new AtomicReference<>();

        context.setTransformationRegistry(registry);
        context.setUseStructuredIds(true);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)  // Sequential to control timing
                .build();

        // The transformation should throw because setElementId is called after external read
        TransformationException ex = assertThrows(TransformationException.class, executor::transform);

        log.info("=== Parallel Custom ID Prevention Test ===");
        log.info("Exception caught as expected: {}", ex.getMessage());

        // Verify exception contains helpful guidance
        assertTrue(ex.getMessage().contains("read by another rule") ||
                        ex.getCause().getMessage().contains("read by another rule"),
                "Exception should explain the issue: " + ex.getMessage());
    }

    @Test
    @DisplayName("Discriminated call: getElementId returns ID before discriminated clone ID is set")
    void discriminatedCall_getElementIdBeforeCloneIdSet() {
        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName("DiscriminatedSource");
        sourceResource.getContents().add(source);

        AtomicReference<String> originalTargetId = new AtomicReference<>();
        AtomicReference<String> cloneIdFromGetElementId = new AtomicReference<>();
        AtomicReference<String> cloneActualId = new AtomicReference<>();

        registry.register(DiscriminatedCloneIdTransformation.class);
        DiscriminatedCloneIdTransformation.capturedOriginalId = originalTargetId;
        DiscriminatedCloneIdTransformation.capturedCloneIdFromGetElementId = cloneIdFromGetElementId;
        DiscriminatedCloneIdTransformation.capturedCloneActualId = cloneActualId;

        context.setTransformationRegistry(registry);
        context.setUseStructuredIds(true);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        log.info("=== Discriminated Clone ID Test Results ===");
        log.info("Original target ID: {}", originalTargetId.get());
        log.info("Clone ID from getElementId: {}", cloneIdFromGetElementId.get());
        log.info("Clone actual ID (from getPendingXmiId): {}", cloneActualId.get());

        // Check if getElementId returns the discriminated ID for the clone
        if (cloneIdFromGetElementId.get() != null && cloneActualId.get() != null) {
            if (cloneIdFromGetElementId.get().equals(cloneActualId.get())) {
                log.info("SUCCESS: getElementId returns correct discriminated clone ID");
            } else {
                log.error("MISMATCH: getElementId returned wrong ID for clone");
                log.error("  getElementId returned: {}", cloneIdFromGetElementId.get());
                log.error("  Actual clone ID: {}", cloneActualId.get());
            }
        }
    }

    @Test
    @DisplayName("Circular dependency: Rule A uses getElementId of in-progress Rule B target")
    void circularDependency_getElementIdOnInProgressTarget() {
        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName("CircularSource");
        sourceResource.getContents().add(source);

        AtomicReference<String> idFromRuleA = new AtomicReference<>();
        AtomicReference<String> idFromRuleB = new AtomicReference<>();
        AtomicReference<String> idOfRuleBTargetSeenByA = new AtomicReference<>();

        registry.register(CircularDependencyTransformation.class);
        CircularDependencyTransformation.capturedIdFromRuleA = idFromRuleA;
        CircularDependencyTransformation.capturedIdFromRuleB = idFromRuleB;
        CircularDependencyTransformation.capturedIdOfRuleBTargetSeenByA = idOfRuleBTargetSeenByA;

        context.setTransformationRegistry(registry);
        context.setUseStructuredIds(true);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        log.info("=== Circular Dependency Test Results ===");
        log.info("ID from Rule A (own target): {}", idFromRuleA.get());
        log.info("ID from Rule B (own target): {}", idFromRuleB.get());
        log.info("ID of Rule B's target as seen by Rule A: {}", idOfRuleBTargetSeenByA.get());

        // Check if Rule A sees the correct ID for Rule B's target
        if (idFromRuleB.get() != null && idOfRuleBTargetSeenByA.get() != null) {
            if (idFromRuleB.get().equals(idOfRuleBTargetSeenByA.get())) {
                log.info("SUCCESS: Rule A sees correct ID for Rule B's target");
            } else {
                log.error("TIMING ISSUE IN CIRCULAR DEPENDENCY:");
                log.error("  Rule B's target actual ID: {}", idFromRuleB.get());
                log.error("  Rule A saw ID: {}", idOfRuleBTargetSeenByA.get());
                fail("TIMING ISSUE: Rule A saw different ID for Rule B's target");
            }
        }
    }

    // ==================== TDD Tests for Race Condition Fix ====================

    @Test
    @DisplayName("createTarget with custom ID: ID is set immediately")
    void createTargetWithCustomId_setsIdImmediately() {
        context.enableStaging();

        // Create target with custom ID upfront
        EClass target = context.createTarget(EClass.class, "custom/my-element/id");

        // ID should be available immediately
        String id = context.getElementId(target);

        assertEquals("custom/my-element/id", id,
                "createTarget(type, customId) should set ID immediately");
    }

    @Test
    @DisplayName("createTarget with custom ID: Other rules see custom ID via getElementId")
    void createTargetWithCustomId_otherRulesSeeCorrectId() {
        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName("CustomIdSource");
        sourceResource.getContents().add(source);

        AtomicReference<String> idSeenByOtherRule = new AtomicReference<>();
        AtomicReference<String> customIdUsed = new AtomicReference<>();

        registry.register(CreateTargetWithCustomIdTransformation.class);
        CreateTargetWithCustomIdTransformation.capturedCustomId = customIdUsed;
        CreateTargetWithCustomIdTransformation.capturedIdSeenByOtherRule = idSeenByOtherRule;

        context.setTransformationRegistry(registry);
        context.setUseStructuredIds(true);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        log.info("=== createTarget with Custom ID Test Results ===");
        log.info("Custom ID used at creation: {}", customIdUsed.get());
        log.info("ID seen by other rule: {}", idSeenByOtherRule.get());

        // The other rule should see the CUSTOM id, not a generated one
        assertEquals(customIdUsed.get(), idSeenByOtherRule.get(),
                "Other rule should see custom ID set at creation time");
    }

    @Test
    @DisplayName("createTarget with null customId: uses auto-generated ID")
    void createTargetWithNullCustomId_usesAutoGeneratedId() {
        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName("NullCustomIdSource");
        sourceResource.getContents().add(source);

        AtomicReference<String> idFromCreateTarget = new AtomicReference<>();

        registry.register(CreateTargetWithNullCustomIdTransformation.class);
        CreateTargetWithNullCustomIdTransformation.capturedId = idFromCreateTarget;

        context.setTransformationRegistry(registry);
        context.setUseStructuredIds(true);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        log.info("=== createTarget with null customId Test Results ===");
        log.info("ID from createTarget(type, null): {}", idFromCreateTarget.get());

        // Should have auto-generated structured ID (contains source info)
        assertNotNull(idFromCreateTarget.get(), "Should have auto-generated ID");
        assertTrue(idFromCreateTarget.get().contains("NullCustomIdSource") ||
                        idFromCreateTarget.get().contains("source"),
                "Auto-generated ID should contain source information: " + idFromCreateTarget.get());
    }

    @Test
    @DisplayName("setElementId after external read: throws IllegalStateException")
    void setElementId_afterExternalRead_throwsIllegalStateException() {
        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName("ExternalReadSource");
        sourceResource.getContents().add(source);

        AtomicReference<IllegalStateException> caughtException = new AtomicReference<>();
        AtomicReference<String> idBeforeException = new AtomicReference<>();

        registry.register(SetElementIdAfterExternalReadTransformation.class);
        SetElementIdAfterExternalReadTransformation.caughtException = caughtException;
        SetElementIdAfterExternalReadTransformation.capturedIdBeforeException = idBeforeException;

        context.setTransformationRegistry(registry);
        context.setUseStructuredIds(true);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        log.info("=== setElementId after external read Test Results ===");
        log.info("ID seen by external rule: {}", idBeforeException.get());
        log.info("Exception caught: {}", caughtException.get() != null ? caughtException.get().getMessage() : "none");

        // Should have thrown IllegalStateException
        assertNotNull(caughtException.get(),
                "setElementId after external read should throw IllegalStateException");
        assertTrue(caughtException.get().getMessage().contains("read by another rule") ||
                        caughtException.get().getMessage().contains("external"),
                "Exception message should explain the issue: " + caughtException.get().getMessage());
    }

    @Test
    @DisplayName("setElementId before external read: succeeds")
    void setElementId_beforeExternalRead_succeeds() {
        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName("BeforeExternalReadSource");
        sourceResource.getContents().add(source);

        AtomicReference<String> customIdSet = new AtomicReference<>();
        AtomicReference<String> idSeenByOtherRule = new AtomicReference<>();
        AtomicReference<Exception> anyException = new AtomicReference<>();

        registry.register(SetElementIdBeforeExternalReadTransformation.class);
        SetElementIdBeforeExternalReadTransformation.capturedCustomId = customIdSet;
        SetElementIdBeforeExternalReadTransformation.capturedIdSeenByOtherRule = idSeenByOtherRule;
        SetElementIdBeforeExternalReadTransformation.caughtException = anyException;

        context.setTransformationRegistry(registry);
        context.setUseStructuredIds(true);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        log.info("=== setElementId before external read Test Results ===");
        log.info("Custom ID set: {}", customIdSet.get());
        log.info("ID seen by other rule: {}", idSeenByOtherRule.get());

        // No exception should be thrown
        assertNull(anyException.get(),
                "setElementId before external read should not throw exception");

        // Other rule should see the custom ID
        assertEquals(customIdSet.get(), idSeenByOtherRule.get(),
                "Other rule should see custom ID set before external read");
    }

    @Test
    @DisplayName("Same rule can change ID multiple times without exception")
    void sameRuleCanChangeIdMultipleTimes() {
        context.enableStaging();

        // Create element
        EClass target = EcoreFactory.eINSTANCE.createEClass();

        // Set ID multiple times within same "rule" context
        context.setElementId(target, "id-version-1");
        assertEquals("id-version-1", context.getElementId(target));

        context.setElementId(target, "id-version-2");
        assertEquals("id-version-2", context.getElementId(target));

        context.setElementId(target, "final-id");
        assertEquals("final-id", context.getElementId(target),
                "Same rule should be able to change ID multiple times");
    }

    @Test
    @DisplayName("External read tracking is per-element")
    void externalReadTracking_isPerElement() {
        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName("PerElementSource");
        sourceResource.getContents().add(source);

        AtomicReference<Boolean> t1SetSucceeded = new AtomicReference<>();
        AtomicReference<Boolean> t2SetSucceeded = new AtomicReference<>();

        registry.register(PerElementExternalReadTransformation.class);
        PerElementExternalReadTransformation.t1SetSucceeded = t1SetSucceeded;
        PerElementExternalReadTransformation.t2SetSucceeded = t2SetSucceeded;

        context.setTransformationRegistry(registry);
        context.setUseStructuredIds(true);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        log.info("=== Per-element external read tracking Test Results ===");
        log.info("T1 setElementId succeeded: {}", t1SetSucceeded.get());
        log.info("T2 setElementId succeeded: {}", t2SetSucceeded.get());

        // T1 was read externally, so setElementId should fail
        assertFalse(t1SetSucceeded.get(),
                "setElementId for T1 (read externally) should fail");

        // T2 was NOT read externally, so setElementId should succeed
        assertTrue(t2SetSucceeded.get(),
                "setElementId for T2 (not read externally) should succeed");
    }

    @Test
    @DisplayName("Exception message provides actionable guidance")
    void exceptionMessage_providesActionableGuidance() {
        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName("ErrorMessageSource");
        sourceResource.getContents().add(source);

        AtomicReference<IllegalStateException> caughtException = new AtomicReference<>();
        AtomicReference<String> readingRuleName = new AtomicReference<>();

        registry.register(ErrorMessageTransformation.class);
        ErrorMessageTransformation.caughtException = caughtException;
        ErrorMessageTransformation.readingRuleName = readingRuleName;

        context.setTransformationRegistry(registry);
        context.setUseStructuredIds(true);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        assertNotNull(caughtException.get(), "Should have caught exception");

        String message = caughtException.get().getMessage();
        log.info("=== Exception message test ===");
        log.info("Exception message: {}", message);

        // Message should contain actionable information
        assertTrue(message.contains("createTarget") ||
                        message.contains("customId"),
                "Message should suggest using createTarget(type, customId): " + message);
    }

    @Test
    @DisplayName("Cache lookup scenario: discriminator built with getElementId vs actual ID")
    void cacheLookupScenario_discriminatorMismatch() {
        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName("CacheLookupSource");
        sourceResource.getContents().add(source);

        AtomicReference<String> firstDiscriminator = new AtomicReference<>();
        AtomicReference<String> secondDiscriminator = new AtomicReference<>();
        AtomicReference<Boolean> cacheHit = new AtomicReference<>(false);

        registry.register(CacheLookupScenarioTransformation.class);
        CacheLookupScenarioTransformation.capturedFirstDiscriminator = firstDiscriminator;
        CacheLookupScenarioTransformation.capturedSecondDiscriminator = secondDiscriminator;
        CacheLookupScenarioTransformation.capturedCacheHit = cacheHit;

        context.setTransformationRegistry(registry);
        context.setUseStructuredIds(true);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        log.info("=== Cache Lookup Scenario Results ===");
        log.info("First discriminator (creation): {}", firstDiscriminator.get());
        log.info("Second discriminator (lookup): {}", secondDiscriminator.get());
        log.info("Cache hit: {}", cacheHit.get());

        // Both discriminators should be the same for cache hit
        if (firstDiscriminator.get() != null && secondDiscriminator.get() != null) {
            assertEquals(firstDiscriminator.get(), secondDiscriminator.get(),
                    "Both lookups should use the same discriminator for cache hit");
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
    public static class TimingIssueTransformation {

        static AtomicReference<String> capturedIdInsideRule;

        @TransformRule(name = "CreateTarget")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> createTarget() {
            return (source, ctx) -> {
                EPackage target = ctx.createTarget(EPackage.class);
                target.setName("Target_" + source.getName());

                // Immediately call getElementId - this is the timing issue scenario
                String id = ctx.getElementId(target);
                if (capturedIdInsideRule != null) {
                    capturedIdInsideRule.set(id);
                }

                ctx.addToResource(target);
                return target;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class CrossRuleTimingTransformation {

        static AtomicReference<String> capturedIdFromRuleA;
        static AtomicReference<String> capturedIdFromRuleB;

        @TransformRule(name = "RuleA")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> ruleA() {
            return (source, ctx) -> {
                EPackage target = ctx.createTarget(EPackage.class);
                target.setName("TargetA_" + source.getName());

                // Get ID inside rule A
                String id = ctx.getElementId(target);
                if (capturedIdFromRuleA != null) {
                    capturedIdFromRuleA.set(id);
                }

                ctx.addToResource(target);

                // Call Rule B which will also get the ID
                ctx.equivalent(source, EAnnotation.class, "RuleB");

                return target;
            };
        }

        @TransformRule(name = "RuleB")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EAnnotation> ruleB() {
            return (source, ctx) -> {
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("Annotation_" + source.getName());

                // Get ID of Rule A's target from Rule B's perspective
                // This requires getting the target from Rule A first
                EPackage ruleATarget = ctx.equivalent(source, EPackage.class, "RuleA");
                if (ruleATarget != null) {
                    String id = ctx.getElementId(ruleATarget);
                    if (capturedIdFromRuleB != null) {
                        capturedIdFromRuleB.set(id);
                    }
                }

                ctx.addToResource(ann);
                return ann;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class EquivalentIdComparisonTransformation {

        static AtomicReference<String> capturedGetElementId;
        static AtomicReference<String> capturedStructuredId;

        @TransformRule(name = "MainRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> mainRule() {
            return (source, ctx) -> {
                EPackage target = ctx.createTarget(EPackage.class);
                target.setName("Main_" + source.getName());

                // Get ID immediately after createTarget
                String immediateId = ctx.getElementId(target);
                if (capturedGetElementId != null) {
                    capturedGetElementId.set(immediateId);
                }

                ctx.addToResource(target);

                // After addToResource, check if pendingXmiId was set differently
                String pendingId = ctx.getPendingXmiId(target);
                if (capturedStructuredId != null) {
                    capturedStructuredId.set(pendingId);
                }

                return target;
            };
        }
    }

    /**
     * Simulates esm2ui pattern where:
     * 1. Main rule creates a ClassType target
     * 2. Main rule uses getElementId(classType) to build discriminator
     * 3. Main rule calls equivalentDiscriminated with that discriminator
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class Esm2UiPatternTransformation {

        static AtomicReference<String> capturedTargetTypeId;
        static AtomicReference<String> capturedTargetTypeActualId;
        static AtomicReference<String> capturedDiscriminator;

        @TransformRule(name = "CreateClassType")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> createClassType() {
            return (source, ctx) -> {
                // Create target ClassType
                EPackage classType = ctx.createTarget(EPackage.class);
                classType.setName("ClassType_" + source.getName());
                ctx.addToResource(classType);

                // Get the ID to use in discriminator (THIS IS THE CRITICAL CALL)
                String classTypeId = ctx.getElementId(classType);
                if (capturedTargetTypeId != null) {
                    capturedTargetTypeId.set(classTypeId);
                }

                // Build discriminator using target element ID
                String discriminator = classTypeId + "/attribute/name";
                if (capturedDiscriminator != null) {
                    capturedDiscriminator.set(discriminator);
                }

                // Call equivalentDiscriminated with this discriminator
                EDataType attr = ctx.equivalentDiscriminated(source, EDataType.class,
                        "CreateAttributeType", discriminator);

                classType.getEClassifiers().add(attr);

                // Capture actual ID after everything
                if (capturedTargetTypeActualId != null) {
                    capturedTargetTypeActualId.set(ctx.getElementId(classType));
                }

                return classType;
            };
        }

        @TransformRule(name = "CreateAttributeType")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EDataType> createAttributeType() {
            return (source, ctx) -> {
                EDataType attr = ctx.createTarget(EDataType.class);
                attr.setName("AttributeType_" + source.getName());
                ctx.addToResource(attr);
                return attr;
            };
        }
    }

    /**
     * Tests cache lookup when two different calls use the same discriminator.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class CacheLookupScenarioTransformation {

        static AtomicReference<String> capturedFirstDiscriminator;
        static AtomicReference<String> capturedSecondDiscriminator;
        static AtomicReference<Boolean> capturedCacheHit;

        @TransformRule(name = "MainRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> mainRule() {
            return (source, ctx) -> {
                EPackage container = ctx.createTarget(EPackage.class);
                container.setName("Container_" + source.getName());
                ctx.addToResource(container);

                // First call - creates the element
                String discriminator1 = "shared/" + source.getName();
                if (capturedFirstDiscriminator != null) {
                    capturedFirstDiscriminator.set(discriminator1);
                }

                EDataType first = ctx.equivalentDiscriminated(source, EDataType.class,
                        "CreateSharedElement", discriminator1);

                container.getEClassifiers().add(first);

                // Second call - should hit cache
                String discriminator2 = "shared/" + source.getName();
                if (capturedSecondDiscriminator != null) {
                    capturedSecondDiscriminator.set(discriminator2);
                }

                EDataType second = ctx.equivalentDiscriminated(source, EDataType.class,
                        "CreateSharedElement", discriminator2);

                // Check if same instance (cache hit)
                if (capturedCacheHit != null) {
                    capturedCacheHit.set(first == second);
                }

                return container;
            };
        }

        @TransformRule(name = "CreateSharedElement")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EDataType> createSharedElement() {
            return (source, ctx) -> {
                EDataType element = ctx.createTarget(EDataType.class);
                element.setName("Shared_" + source.getName());
                ctx.addToResource(element);
                return element;
            };
        }
    }

    /**
     * Tests circular dependency scenario where:
     * 1. Rule A starts, creates target, calls Rule B
     * 2. Rule B starts, creates target, calls back to Rule A
     * 3. Rule A (callback) tries to get ID of Rule B's in-progress target
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class CircularDependencyTransformation {

        static AtomicReference<String> capturedIdFromRuleA;
        static AtomicReference<String> capturedIdFromRuleB;
        static AtomicReference<String> capturedIdOfRuleBTargetSeenByA;

        @TransformRule(name = "RuleA")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> ruleA() {
            return (source, ctx) -> {
                EPackage targetA = ctx.createTarget(EPackage.class);
                targetA.setName("TargetA_" + source.getName());

                // Capture own ID
                String myId = ctx.getElementId(targetA);
                if (capturedIdFromRuleA != null) {
                    capturedIdFromRuleA.set(myId);
                }

                ctx.addToResource(targetA);

                // Call Rule B (lazy)
                EDataType targetB = ctx.equivalent(source, EDataType.class, "RuleB");

                // Try to get ID of Rule B's target
                if (targetB != null) {
                    String ruleBTargetId = ctx.getElementId(targetB);
                    if (capturedIdOfRuleBTargetSeenByA != null) {
                        capturedIdOfRuleBTargetSeenByA.set(ruleBTargetId);
                    }
                }

                return targetA;
            };
        }

        @TransformRule(name = "RuleB")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EDataType> ruleB() {
            return (source, ctx) -> {
                EDataType targetB = ctx.createTarget(EDataType.class);
                targetB.setName("TargetB_" + source.getName());

                // Capture own ID
                String myId = ctx.getElementId(targetB);
                if (capturedIdFromRuleB != null) {
                    capturedIdFromRuleB.set(myId);
                }

                ctx.addToResource(targetB);

                // Don't call back to Rule A to avoid infinite loop
                // The test checks if Rule A can see Rule B's ID after Rule B completes

                return targetB;
            };
        }
    }

    /**
     * Tests race condition where:
     * 1. Rule creates target (gets initial structured ID)
     * 2. Rule calls lazy rule which reads target's ID via getElementId
     * 3. Rule then calls setElementId to override with custom ID
     * 4. The lazy rule saw the INITIAL id, not the custom one
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class RaceConditionTransformation {

        static AtomicReference<String> capturedIdAfterCreateTarget;
        static AtomicReference<String> capturedIdAfterSetElementId;
        static AtomicReference<String> capturedIdSeenByOtherRule;
        static AtomicReference<String> capturedCustomId;

        @TransformRule(name = "MainRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> mainRule() {
            return (source, ctx) -> {
                // Step 1: createTarget sets initial structured ID
                EPackage target = ctx.createTarget(EPackage.class);
                target.setName("Target_" + source.getName());

                String initialId = ctx.getElementId(target);
                if (capturedIdAfterCreateTarget != null) {
                    capturedIdAfterCreateTarget.set(initialId);
                }

                ctx.addToResource(target);

                // Step 2: Call lazy rule BEFORE setting custom ID
                // This simulates another rule reading the ID
                EDataType helper = ctx.equivalent(source, EDataType.class, "HelperRule");

                // Step 3: NOW set a custom ID (override the initial structured ID)
                String customId = "custom/" + source.getName() + "/special";
                if (capturedCustomId != null) {
                    capturedCustomId.set(customId);
                }
                ctx.setElementId(target, customId);

                String finalId = ctx.getElementId(target);
                if (capturedIdAfterSetElementId != null) {
                    capturedIdAfterSetElementId.set(finalId);
                }

                return target;
            };
        }

        @TransformRule(name = "HelperRule")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EDataType> helperRule() {
            return (source, ctx) -> {
                EDataType helper = ctx.createTarget(EDataType.class);
                helper.setName("Helper_" + source.getName());

                // Get the main target and read its ID
                // This happens BETWEEN createTarget and setElementId in MainRule
                EPackage mainTarget = ctx.equivalent(source, EPackage.class, "MainRule");
                if (mainTarget != null) {
                    String seenId = ctx.getElementId(mainTarget);
                    if (capturedIdSeenByOtherRule != null) {
                        capturedIdSeenByOtherRule.set(seenId);
                    }
                }

                ctx.addToResource(helper);
                return helper;
            };
        }
    }

    /**
     * Tests parallel scenario where Rule A sets custom ID but Rule B reads before that.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ParallelCustomIdTransformation {

        static AtomicReference<String> capturedIdSetByRuleA;
        static AtomicReference<String> capturedIdSeenByRuleB;

        @TransformRule(name = "RuleA")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> ruleA() {
            return (source, ctx) -> {
                EPackage target = ctx.createTarget(EPackage.class);
                target.setName("TargetA_" + source.getName());
                ctx.addToResource(target);

                // Call Rule B first - it will read the current ID
                ctx.equivalent(source, EDataType.class, "RuleB");

                // NOW set a custom ID
                String customId = "ruleA/custom/" + source.getName();
                ctx.setElementId(target, customId);

                if (capturedIdSetByRuleA != null) {
                    capturedIdSetByRuleA.set(customId);
                }

                return target;
            };
        }

        @TransformRule(name = "RuleB")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EDataType> ruleB() {
            return (source, ctx) -> {
                EDataType target = ctx.createTarget(EDataType.class);
                target.setName("TargetB_" + source.getName());

                // Read Rule A's target ID - this happens BEFORE Rule A sets custom ID
                EPackage ruleATarget = ctx.equivalent(source, EPackage.class, "RuleA");
                if (ruleATarget != null) {
                    String seenId = ctx.getElementId(ruleATarget);
                    if (capturedIdSeenByRuleB != null) {
                        capturedIdSeenByRuleB.set(seenId);
                    }
                }

                ctx.addToResource(target);
                return target;
            };
        }
    }

    /**
     * Tests if getElementId returns correct ID for discriminated clones.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class DiscriminatedCloneIdTransformation {

        static AtomicReference<String> capturedOriginalId;
        static AtomicReference<String> capturedCloneIdFromGetElementId;
        static AtomicReference<String> capturedCloneActualId;

        @TransformRule(name = "MainRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> mainRule() {
            return (source, ctx) -> {
                EPackage container = ctx.createTarget(EPackage.class);
                container.setName("Container_" + source.getName());
                ctx.addToResource(container);

                // Get a discriminated clone
                String discriminator = "clone/" + source.getName();
                EDataType clone = ctx.equivalentDiscriminated(source, EDataType.class,
                        "CloneableRule", discriminator);

                container.getEClassifiers().add(clone);

                // Get the clone's ID via getElementId
                String cloneId = ctx.getElementId(clone);
                if (capturedCloneIdFromGetElementId != null) {
                    capturedCloneIdFromGetElementId.set(cloneId);
                }

                // Get the clone's actual pending ID
                String actualId = ctx.getPendingXmiId(clone);
                if (capturedCloneActualId != null) {
                    capturedCloneActualId.set(actualId);
                }

                return container;
            };
        }

        @TransformRule(name = "CloneableRule")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EDataType> cloneableRule() {
            return (source, ctx) -> {
                EDataType target = ctx.createTarget(EDataType.class);
                target.setName("Cloneable_" + source.getName());

                // Capture the original target's ID
                String originalId = ctx.getElementId(target);
                if (capturedOriginalId != null) {
                    capturedOriginalId.set(originalId);
                }

                ctx.addToResource(target);
                return target;
            };
        }
    }

    // ==================== TDD Test Transformations for Race Condition Fix ====================

    /**
     * Tests createTarget(type, customId) overload - creates target with custom ID upfront.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class CreateTargetWithCustomIdTransformation {

        static AtomicReference<String> capturedCustomId;
        static AtomicReference<String> capturedIdSeenByOtherRule;

        @TransformRule(name = "MainRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> mainRule() {
            return (source, ctx) -> {
                // Create target with custom ID upfront - NO race condition possible
                String customId = "custom/" + source.getName() + "/main";
                if (capturedCustomId != null) {
                    capturedCustomId.set(customId);
                }

                EPackage target = ctx.createTarget(EPackage.class, customId);
                target.setName("Target_" + source.getName());
                ctx.addToResource(target);

                // Call lazy rule which will read the ID
                ctx.equivalent(source, EDataType.class, "ReaderRule");

                return target;
            };
        }

        @TransformRule(name = "ReaderRule")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EDataType> readerRule() {
            return (source, ctx) -> {
                EDataType helper = ctx.createTarget(EDataType.class);
                helper.setName("Helper_" + source.getName());

                // Read the main target's ID
                EPackage mainTarget = ctx.equivalent(source, EPackage.class, "MainRule");
                if (mainTarget != null) {
                    String seenId = ctx.getElementId(mainTarget);
                    if (capturedIdSeenByOtherRule != null) {
                        capturedIdSeenByOtherRule.set(seenId);
                    }
                }

                ctx.addToResource(helper);
                return helper;
            };
        }
    }

    /**
     * Tests createTarget(type, null) - should use auto-generated structured ID.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class CreateTargetWithNullCustomIdTransformation {

        static AtomicReference<String> capturedId;

        @TransformRule(name = "MainRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> mainRule() {
            return (source, ctx) -> {
                // Create target with null customId - should use auto-generated ID
                EPackage target = ctx.createTarget(EPackage.class, (String) null);
                target.setName("Target_" + source.getName());

                String id = ctx.getElementId(target);
                if (capturedId != null) {
                    capturedId.set(id);
                }

                ctx.addToResource(target);
                return target;
            };
        }
    }

    /**
     * Tests that setElementId throws after external read.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class SetElementIdAfterExternalReadTransformation {

        static AtomicReference<IllegalStateException> caughtException;
        static AtomicReference<String> capturedIdBeforeException;

        @TransformRule(name = "MainRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> mainRule() {
            return (source, ctx) -> {
                // Create target with auto-generated ID
                EPackage target = ctx.createTarget(EPackage.class);
                target.setName("Target_" + source.getName());
                ctx.addToResource(target);

                // Call lazy rule which will read the ID (creating external read)
                ctx.equivalent(source, EDataType.class, "ExternalReaderRule");

                // NOW try to set custom ID - should throw!
                try {
                    ctx.setElementId(target, "custom/after-read/id");
                } catch (IllegalStateException e) {
                    if (caughtException != null) {
                        caughtException.set(e);
                    }
                }

                return target;
            };
        }

        @TransformRule(name = "ExternalReaderRule")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EDataType> externalReaderRule() {
            return (source, ctx) -> {
                EDataType helper = ctx.createTarget(EDataType.class);
                helper.setName("Helper_" + source.getName());

                // Read the main target's ID - this creates an "external read"
                EPackage mainTarget = ctx.equivalent(source, EPackage.class, "MainRule");
                if (mainTarget != null) {
                    String seenId = ctx.getElementId(mainTarget);
                    if (capturedIdBeforeException != null) {
                        capturedIdBeforeException.set(seenId);
                    }
                }

                ctx.addToResource(helper);
                return helper;
            };
        }
    }

    /**
     * Tests that setElementId succeeds when called before any external read.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class SetElementIdBeforeExternalReadTransformation {

        static AtomicReference<String> capturedCustomId;
        static AtomicReference<String> capturedIdSeenByOtherRule;
        static AtomicReference<Exception> caughtException;

        @TransformRule(name = "MainRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> mainRule() {
            return (source, ctx) -> {
                // Create target with auto-generated ID
                EPackage target = ctx.createTarget(EPackage.class);
                target.setName("Target_" + source.getName());
                ctx.addToResource(target);

                // Set custom ID BEFORE calling other rules
                String customId = "custom/before-read/" + source.getName();
                try {
                    ctx.setElementId(target, customId);
                    if (capturedCustomId != null) {
                        capturedCustomId.set(customId);
                    }
                } catch (Exception e) {
                    if (caughtException != null) {
                        caughtException.set(e);
                    }
                }

                // NOW call lazy rule which will read the ID
                ctx.equivalent(source, EDataType.class, "ReaderAfterSetRule");

                return target;
            };
        }

        @TransformRule(name = "ReaderAfterSetRule")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EDataType> readerAfterSetRule() {
            return (source, ctx) -> {
                EDataType helper = ctx.createTarget(EDataType.class);
                helper.setName("Helper_" + source.getName());

                // Read the main target's ID
                EPackage mainTarget = ctx.equivalent(source, EPackage.class, "MainRule");
                if (mainTarget != null) {
                    String seenId = ctx.getElementId(mainTarget);
                    if (capturedIdSeenByOtherRule != null) {
                        capturedIdSeenByOtherRule.set(seenId);
                    }
                }

                ctx.addToResource(helper);
                return helper;
            };
        }
    }

    /**
     * Tests that external read tracking is per-element:
     * - T1: read externally, setElementId should fail
     * - T2: NOT read externally, setElementId should succeed
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class PerElementExternalReadTransformation {

        static AtomicReference<Boolean> t1SetSucceeded;
        static AtomicReference<Boolean> t2SetSucceeded;

        @TransformRule(name = "MainRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> mainRule() {
            return (source, ctx) -> {
                // Create TWO targets
                EPackage t1 = ctx.createTarget(EPackage.class);
                t1.setName("T1_" + source.getName());
                ctx.addToResource(t1);

                EPackage t2 = ctx.createTarget(EPackage.class);
                t2.setName("T2_" + source.getName());
                ctx.addToResource(t2);

                // Call lazy rule which reads ONLY T1's ID
                ctx.equivalent(source, EDataType.class, "T1OnlyReaderRule");

                // Try to set ID on T1 (should fail - read externally)
                try {
                    ctx.setElementId(t1, "custom/t1/id");
                    if (t1SetSucceeded != null) {
                        t1SetSucceeded.set(true);
                    }
                } catch (IllegalStateException e) {
                    if (t1SetSucceeded != null) {
                        t1SetSucceeded.set(false);
                    }
                }

                // Try to set ID on T2 (should succeed - NOT read externally)
                try {
                    ctx.setElementId(t2, "custom/t2/id");
                    if (t2SetSucceeded != null) {
                        t2SetSucceeded.set(true);
                    }
                } catch (IllegalStateException e) {
                    if (t2SetSucceeded != null) {
                        t2SetSucceeded.set(false);
                    }
                }

                return t1;
            };
        }

        @TransformRule(name = "T1OnlyReaderRule")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EDataType> t1OnlyReaderRule() {
            return (source, ctx) -> {
                EDataType helper = ctx.createTarget(EDataType.class);
                helper.setName("Helper_" + source.getName());

                // Read ONLY T1's ID (T2 is not read)
                EPackage t1 = ctx.equivalent(source, EPackage.class, "MainRule");
                if (t1 != null) {
                    ctx.getElementId(t1);  // This marks T1 as externally read
                }
                // Note: T2 is NOT accessed here

                ctx.addToResource(helper);
                return helper;
            };
        }
    }

    /**
     * Tests that error message provides actionable guidance.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ErrorMessageTransformation {

        static AtomicReference<IllegalStateException> caughtException;
        static AtomicReference<String> readingRuleName;

        @TransformRule(name = "MainRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> mainRule() {
            return (source, ctx) -> {
                EPackage target = ctx.createTarget(EPackage.class);
                target.setName("Target_" + source.getName());
                ctx.addToResource(target);

                // Call lazy rule which will read the ID
                if (readingRuleName != null) {
                    readingRuleName.set("ErrorReaderRule");
                }
                ctx.equivalent(source, EDataType.class, "ErrorReaderRule");

                // Try to set ID after external read
                try {
                    ctx.setElementId(target, "custom/error-test/id");
                } catch (IllegalStateException e) {
                    if (caughtException != null) {
                        caughtException.set(e);
                    }
                }

                return target;
            };
        }

        @TransformRule(name = "ErrorReaderRule")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EDataType> errorReaderRule() {
            return (source, ctx) -> {
                EDataType helper = ctx.createTarget(EDataType.class);
                helper.setName("Helper_" + source.getName());

                // Read the main target's ID
                EPackage mainTarget = ctx.equivalent(source, EPackage.class, "MainRule");
                if (mainTarget != null) {
                    ctx.getElementId(mainTarget);  // Creates external read
                }

                ctx.addToResource(helper);
                return helper;
            };
        }
    }
}
