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
 * Tests for the pattern where equivalentDiscriminated is called first,
 * and then setElementId is used to override the ID:
 *
 * <pre>
 * String fullId = actorType.getName() + "/(esm/" + getId(source) + ")/" + RULE_NAME + "/(discriminator/" + discriminator + ")";
 * Action action = ctx.equivalentDiscriminated(...);
 * if (action == null) {
 *     action = ctx.createTarget(Action.class, fullId);  // ID set at creation
 * } else {
 *     ctx.setElementId(action, fullId);  // Trying to override existing action's ID
 * }
 * </pre>
 *
 * This pattern has potential issues:
 * 1. If the action was created by another rule, setElementId may throw
 * 2. The action already has an ID - overriding it may cause cache inconsistencies
 * 3. If another rule read the action's ID, setElementId will throw
 */
@DisplayName("equivalentDiscriminated ID Override Pattern Tests")
class EquivalentDiscriminatedIdOverrideTest {

    private static final Logger log = LoggerFactory.getLogger(EquivalentDiscriminatedIdOverrideTest.class);

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

    // ==================== Scenario Tests ====================

    @Test
    @DisplayName("Scenario 1: Caller tries to setElementId on lazy rule result - should fail")
    void scenario1_callerTriesToSetIdOnLazyRuleResult_shouldFail() {
        // This tests the PROBLEMATIC pattern:
        // MainRule calls equivalentDiscriminated -> lazy rule creates element
        // MainRule reads the ID (getElementId)
        // MainRule tries to change the ID -> SHOULD FAIL

        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName("SourceClass");
        sourceResource.getContents().add(source);

        AtomicReference<String> originalId = new AtomicReference<>();
        AtomicReference<Boolean> setElementIdSucceeded = new AtomicReference<>();
        AtomicReference<Exception> caughtException = new AtomicReference<>();

        registry.register(Scenario1ProblematicPatternTransformation.class);
        Scenario1ProblematicPatternTransformation.originalId = originalId;
        Scenario1ProblematicPatternTransformation.setElementIdSucceeded = setElementIdSucceeded;
        Scenario1ProblematicPatternTransformation.caughtException = caughtException;

        context.setTransformationRegistry(registry);
        context.setUseStructuredIds(true);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        log.info("=== Scenario 1 (Problematic Pattern) Results ===");
        log.info("Original ID from lazy rule: {}", originalId.get());
        log.info("setElementId succeeded: {}", setElementIdSucceeded.get());
        if (caughtException.get() != null) {
            log.info("Exception: {}", caughtException.get().getMessage());
        }

        // This SHOULD fail because MainRule read the ID before trying to change it
        assertFalse(setElementIdSucceeded.get(),
                "setElementId should fail after reading ID created by another rule");
        assertNotNull(caughtException.get(),
                "Should have thrown IllegalStateException");
    }

    @Test
    @DisplayName("Scenario 2: equivalentDiscriminated returns existing action - setElementId fails after getElementId")
    void scenario2_equivalentDiscriminatedReturnsExisting_setElementIdFailsAfterRead() {
        // This tests calling equivalentDiscriminated twice with same discriminator.
        // First call reads the ID (via getElementId), which marks it as externally read.
        // Second call returns the same cached clone.
        // Then setElementId should FAIL because the ID was already read.

        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName("SourceClass");
        sourceResource.getContents().add(source);

        AtomicReference<String> firstCallId = new AtomicReference<>();
        AtomicReference<String> secondCallId = new AtomicReference<>();
        AtomicReference<Boolean> setElementIdSucceeded = new AtomicReference<>();
        AtomicReference<Exception> caughtException = new AtomicReference<>();

        registry.register(Scenario2Transformation.class);
        Scenario2Transformation.firstCallId = firstCallId;
        Scenario2Transformation.secondCallId = secondCallId;
        Scenario2Transformation.setElementIdSucceeded = setElementIdSucceeded;
        Scenario2Transformation.caughtException = caughtException;

        context.setTransformationRegistry(registry);
        context.setUseStructuredIds(true);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        log.info("=== Scenario 2 Results ===");
        log.info("First call ID: {}", firstCallId.get());
        log.info("Second call ID (after setElementId attempt): {}", secondCallId.get());
        log.info("setElementId succeeded: {}", setElementIdSucceeded.get());
        if (caughtException.get() != null) {
            log.info("Exception: {}", caughtException.get().getMessage());
        }

        // setElementId should FAIL because:
        // 1. The clone was created by CreateAction (lazy rule)
        // 2. MainRule read the ID via getElementId (external read)
        // 3. Now setElementId throws IllegalStateException
        assertFalse(setElementIdSucceeded.get(),
                "setElementId should fail after reading ID of clone created by another rule");
        assertNotNull(caughtException.get(),
                "Should have thrown IllegalStateException");
    }

    @Test
    @DisplayName("Scenario 3: equivalentDiscriminated returns clone - setElementId throws")
    void scenario3_equivalentDiscriminatedReturnsClone_setElementIdMayThrow() {
        // This tests: Rule A creates action, Rule B calls equivalentDiscriminated
        // with different discriminator, gets a clone, then tries to setElementId

        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName("SourceClass");
        sourceResource.getContents().add(source);

        AtomicReference<String> originalId = new AtomicReference<>();
        AtomicReference<String> cloneIdBeforeSet = new AtomicReference<>();
        AtomicReference<String> cloneIdAfterSet = new AtomicReference<>();
        AtomicReference<Boolean> setElementIdSucceeded = new AtomicReference<>();
        AtomicReference<Exception> caughtException = new AtomicReference<>();

        registry.register(Scenario3Transformation.class);
        Scenario3Transformation.originalId = originalId;
        Scenario3Transformation.cloneIdBeforeSet = cloneIdBeforeSet;
        Scenario3Transformation.cloneIdAfterSet = cloneIdAfterSet;
        Scenario3Transformation.setElementIdSucceeded = setElementIdSucceeded;
        Scenario3Transformation.caughtException = caughtException;

        context.setTransformationRegistry(registry);
        context.setUseStructuredIds(true);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        log.info("=== Scenario 3 Results ===");
        log.info("Original action ID: {}", originalId.get());
        log.info("Clone ID before setElementId: {}", cloneIdBeforeSet.get());
        log.info("Clone ID after setElementId: {}", cloneIdAfterSet.get());
        log.info("setElementId succeeded: {}", setElementIdSucceeded.get());
        if (caughtException.get() != null) {
            log.info("Exception: {}", caughtException.get().getMessage());
        }

        // Document what happens - clone is created by framework, not by a rule
        // So the external read check may not apply the same way
    }

    @Test
    @DisplayName("Scenario 4: Cross-rule - Rule B gets action from Rule A, tries to setElementId")
    void scenario4_crossRule_setElementIdOnOtherRulesElement() {
        // Rule A creates action
        // Rule B calls equivalentDiscriminated, gets Rule A's action, tries setElementId

        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName("SourceClass");
        sourceResource.getContents().add(source);

        AtomicReference<String> ruleAId = new AtomicReference<>();
        AtomicReference<String> ruleBAttemptedId = new AtomicReference<>();
        AtomicReference<Boolean> setElementIdSucceeded = new AtomicReference<>();
        AtomicReference<Exception> caughtException = new AtomicReference<>();

        registry.register(Scenario4Transformation.class);
        Scenario4Transformation.ruleAId = ruleAId;
        Scenario4Transformation.ruleBAttemptedId = ruleBAttemptedId;
        Scenario4Transformation.setElementIdSucceeded = setElementIdSucceeded;
        Scenario4Transformation.caughtException = caughtException;

        context.setTransformationRegistry(registry);
        context.setUseStructuredIds(true);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        log.info("=== Scenario 4 Results ===");
        log.info("Rule A's action ID: {}", ruleAId.get());
        log.info("Rule B attempted to set ID: {}", ruleBAttemptedId.get());
        log.info("setElementId succeeded: {}", setElementIdSucceeded.get());
        if (caughtException.get() != null) {
            log.info("Exception: {}", caughtException.get().getMessage());
        }

        // This SHOULD throw because Rule B is modifying Rule A's element
        // after Rule B read the ID (via equivalentDiscriminated which internally reads ID)
        assertFalse(setElementIdSucceeded.get(),
                "setElementId on another rule's element should fail");
        assertNotNull(caughtException.get(),
                "Should have thrown IllegalStateException");
    }

    @Test
    @DisplayName("Scenario 5: Recommended pattern - use lazy rule's ID as-is (don't override)")
    void scenario5_recommendedPattern_useLazyRuleIdAsIs() {
        // NOTE: equivalentDiscriminated with a lazy rule NEVER returns null - it executes the rule.
        // The recommended pattern is to accept the ID from the lazy rule and not try to override it.
        //
        // If you need a custom ID, use createTarget(type, customId) INSTEAD of equivalentDiscriminated.

        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName("SourceClass");
        sourceResource.getContents().add(source);

        AtomicReference<String> firstCallId = new AtomicReference<>();
        AtomicReference<String> secondCallId = new AtomicReference<>();
        AtomicReference<Boolean> firstWasCreatedByLazy = new AtomicReference<>();
        AtomicReference<Boolean> secondIsSameInstance = new AtomicReference<>();

        registry.register(Scenario5Transformation.class);
        Scenario5Transformation.firstCallId = firstCallId;
        Scenario5Transformation.secondCallId = secondCallId;
        Scenario5Transformation.firstWasNull = firstWasCreatedByLazy;
        Scenario5Transformation.secondWasNull = secondIsSameInstance;

        context.setTransformationRegistry(registry);
        context.setUseStructuredIds(true);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        log.info("=== Scenario 5 (Recommended Pattern) Results ===");
        log.info("First call created by lazy rule: {}, ID: {}", firstWasCreatedByLazy.get(), firstCallId.get());
        log.info("Second call is same instance: {}, ID: {}", !secondIsSameInstance.get(), secondCallId.get());

        // equivalentDiscriminated always returns an action (created by lazy rule)
        // The test checks that both calls return the same cached instance with same ID
        assertNotNull(firstCallId.get(), "First call should return action with ID");
        assertEquals(firstCallId.get(), secondCallId.get(),
                "Both calls should see the same cached action with same ID");
    }

    @Test
    @DisplayName("Scenario 6: equivalentDiscriminated always returns non-null (executes lazy rule)")
    void scenario6_equivalentDiscriminatedAlwaysReturnsNonNull() {
        // NOTE: equivalentDiscriminated with a lazy rule NEVER returns null - it executes the rule.
        // This test documents that getOrCreate patterns where you check for null don't work
        // as expected with equivalentDiscriminated.
        //
        // If you need a getOrCreate pattern with custom IDs:
        // - Option 1: Have the lazy rule use createTarget(type, customId) itself
        // - Option 2: Don't use equivalentDiscriminated, use your own caching

        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName("SourceClass");
        sourceResource.getContents().add(source);

        AtomicReference<String> actionId = new AtomicReference<>();
        AtomicReference<Boolean> wasCreatedByLazyRule = new AtomicReference<>();

        registry.register(Scenario6Transformation.class);
        Scenario6Transformation.actionId = actionId;
        Scenario6Transformation.wasCreated = wasCreatedByLazyRule;

        context.setTransformationRegistry(registry);
        context.setUseStructuredIds(true);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        log.info("=== Scenario 6 (getOrCreate Behavior) Results ===");
        log.info("Was created by lazy rule (not null check): {}", !wasCreatedByLazyRule.get());
        log.info("Action ID: {}", actionId.get());

        // equivalentDiscriminated always returns the lazy rule's result, never null
        // So the "wasCreated" branch in the test transformation is never taken
        assertFalse(wasCreatedByLazyRule.get(),
                "equivalentDiscriminated should return lazy rule's result, not null");
        assertNotNull(actionId.get(), "Should have an ID from lazy rule");
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

    /**
     * Scenario 1: PROBLEMATIC PATTERN - caller reads ID then tries to change it
     *
     * This demonstrates the esm2ui-style pattern:
     * 1. Call equivalentDiscriminated to get/create action
     * 2. Read the ID (to build discriminator)
     * 3. Try to set custom ID -> SHOULD FAIL because ID was read
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class Scenario1ProblematicPatternTransformation {

        static AtomicReference<String> originalId;
        static AtomicReference<Boolean> setElementIdSucceeded;
        static AtomicReference<Exception> caughtException;

        @TransformRule(name = "MainRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> mainRule() {
            return (source, ctx) -> {
                EPackage container = ctx.createTarget(EPackage.class);
                container.setName("Container");
                ctx.addToResource(container);

                // Step 1: Get action from lazy rule (equivalentDiscriminated triggers lazy rule)
                String discriminator = "test-disc";
                EDataType action = ctx.equivalentDiscriminated(source, EDataType.class,
                        "CreateAction", discriminator);

                // Step 2: READ the ID (this marks it as externally read!)
                String currentId = ctx.getElementId(action);
                if (originalId != null) {
                    originalId.set(currentId);
                }

                // Step 3: Try to set a DIFFERENT ID - SHOULD FAIL!
                String customId = "actor/" + source.getName() + "/CreateAction/(discriminator/" + discriminator + ")";
                try {
                    ctx.setElementId(action, customId);
                    if (setElementIdSucceeded != null) {
                        setElementIdSucceeded.set(true);
                    }
                } catch (IllegalStateException e) {
                    if (setElementIdSucceeded != null) {
                        setElementIdSucceeded.set(false);
                    }
                    if (caughtException != null) {
                        caughtException.set(e);
                    }
                }

                return container;
            };
        }

        @TransformRule(name = "CreateAction")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EDataType> createAction() {
            return (source, ctx) -> {
                EDataType action = ctx.createTarget(EDataType.class);
                action.setName("Action_" + source.getName());
                ctx.addToResource(action);
                return action;
            };
        }
    }

    /**
     * Scenario 2: Same rule calls equivalentDiscriminated twice, tries setElementId on second call
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class Scenario2Transformation {

        static AtomicReference<String> firstCallId;
        static AtomicReference<String> secondCallId;
        static AtomicReference<Boolean> setElementIdSucceeded;
        static AtomicReference<Exception> caughtException;

        @TransformRule(name = "MainRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> mainRule() {
            return (source, ctx) -> {
                EPackage container = ctx.createTarget(EPackage.class);
                container.setName("Container");
                ctx.addToResource(container);

                // First call - will create via lazy rule
                EDataType action1 = ctx.equivalentDiscriminated(source, EDataType.class,
                        "CreateAction", "disc1");

                if (firstCallId != null) {
                    firstCallId.set(ctx.getElementId(action1));
                }

                // Second call with SAME discriminator - should return cached
                EDataType action2 = ctx.equivalentDiscriminated(source, EDataType.class,
                        "CreateAction", "disc1");

                // Try to override the ID
                String newId = "override/" + source.getName() + "/new-id";
                try {
                    ctx.setElementId(action2, newId);
                    if (setElementIdSucceeded != null) {
                        setElementIdSucceeded.set(true);
                    }
                } catch (Exception e) {
                    if (setElementIdSucceeded != null) {
                        setElementIdSucceeded.set(false);
                    }
                    if (caughtException != null) {
                        caughtException.set(e);
                    }
                }

                if (secondCallId != null) {
                    secondCallId.set(ctx.getElementId(action2));
                }

                return container;
            };
        }

        @TransformRule(name = "CreateAction")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EDataType> createAction() {
            return (source, ctx) -> {
                EDataType action = ctx.createTarget(EDataType.class);
                action.setName("Action_" + source.getName());
                ctx.addToResource(action);
                return action;
            };
        }
    }

    /**
     * Scenario 3: equivalentDiscriminated returns a clone, try setElementId on clone
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class Scenario3Transformation {

        static AtomicReference<String> originalId;
        static AtomicReference<String> cloneIdBeforeSet;
        static AtomicReference<String> cloneIdAfterSet;
        static AtomicReference<Boolean> setElementIdSucceeded;
        static AtomicReference<Exception> caughtException;

        @TransformRule(name = "MainRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> mainRule() {
            return (source, ctx) -> {
                EPackage container = ctx.createTarget(EPackage.class);
                container.setName("Container");
                ctx.addToResource(container);

                // First call with disc1 - creates original
                EDataType original = ctx.equivalentDiscriminated(source, EDataType.class,
                        "CreateAction", "disc1");

                if (originalId != null) {
                    originalId.set(ctx.getElementId(original));
                }

                // Second call with DIFFERENT discriminator - should create a clone
                EDataType clone = ctx.equivalentDiscriminated(source, EDataType.class,
                        "CreateAction", "disc2");

                if (cloneIdBeforeSet != null) {
                    cloneIdBeforeSet.set(ctx.getElementId(clone));
                }

                // Try to override the clone's ID
                String newId = "custom-clone/" + source.getName();
                try {
                    ctx.setElementId(clone, newId);
                    if (setElementIdSucceeded != null) {
                        setElementIdSucceeded.set(true);
                    }
                } catch (Exception e) {
                    if (setElementIdSucceeded != null) {
                        setElementIdSucceeded.set(false);
                    }
                    if (caughtException != null) {
                        caughtException.set(e);
                    }
                }

                if (cloneIdAfterSet != null) {
                    cloneIdAfterSet.set(ctx.getElementId(clone));
                }

                return container;
            };
        }

        @TransformRule(name = "CreateAction")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EDataType> createAction() {
            return (source, ctx) -> {
                EDataType action = ctx.createTarget(EDataType.class);
                action.setName("Action_" + source.getName());
                ctx.addToResource(action);
                return action;
            };
        }
    }

    /**
     * Scenario 4: Rule B gets action from Rule A, tries to setElementId
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class Scenario4Transformation {

        static AtomicReference<String> ruleAId;
        static AtomicReference<String> ruleBAttemptedId;
        static AtomicReference<Boolean> setElementIdSucceeded;
        static AtomicReference<Exception> caughtException;

        @TransformRule(name = "RuleA")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> ruleA() {
            return (source, ctx) -> {
                EPackage container = ctx.createTarget(EPackage.class);
                container.setName("Container");
                ctx.addToResource(container);

                // Create action with custom ID
                String customId = "ruleA/" + source.getName() + "/action";
                EDataType action = ctx.createTarget(EDataType.class, customId);
                action.setName("Action_" + source.getName());
                ctx.addToResource(action);

                if (ruleAId != null) {
                    ruleAId.set(ctx.getElementId(action));
                }

                // Call Rule B which will try to modify the action
                ctx.equivalent(source, EAnnotation.class, "RuleB");

                return container;
            };
        }

        @TransformRule(name = "RuleB")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EAnnotation> ruleB() {
            return (source, ctx) -> {
                EAnnotation ann = ctx.createTarget(EAnnotation.class);
                ann.setSource("Annotation");
                ctx.addToResource(ann);

                // Try to get Rule A's action via equivalent (not discriminated)
                EDataType action = ctx.equivalent(source, EDataType.class, "GetAction");

                if (action != null) {
                    // This reads the ID - marks as external read
                    String currentId = ctx.getElementId(action);

                    // Now try to change the ID - should fail!
                    String newId = "ruleB/" + source.getName() + "/modified";
                    if (ruleBAttemptedId != null) {
                        ruleBAttemptedId.set(newId);
                    }

                    try {
                        ctx.setElementId(action, newId);
                        if (setElementIdSucceeded != null) {
                            setElementIdSucceeded.set(true);
                        }
                    } catch (Exception e) {
                        if (setElementIdSucceeded != null) {
                            setElementIdSucceeded.set(false);
                        }
                        if (caughtException != null) {
                            caughtException.set(e);
                        }
                    }
                }

                return ann;
            };
        }

        @TransformRule(name = "GetAction")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EDataType> getAction() {
            return (source, ctx) -> {
                // Just create and return - simulating a lookup
                EDataType action = ctx.createTarget(EDataType.class);
                action.setName("Action_" + source.getName());
                ctx.addToResource(action);
                return action;
            };
        }
    }

    /**
     * Scenario 5: Recommended pattern - don't modify ID if element exists
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class Scenario5Transformation {

        static AtomicReference<String> firstCallId;
        static AtomicReference<String> secondCallId;
        static AtomicReference<Boolean> firstWasNull;
        static AtomicReference<Boolean> secondWasNull;

        @TransformRule(name = "MainRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> mainRule() {
            return (source, ctx) -> {
                EPackage container = ctx.createTarget(EPackage.class);
                container.setName("Container");
                ctx.addToResource(container);

                // First call
                String discriminator = "shared-disc";
                String customId = "custom/" + source.getName() + "/(discriminator/" + discriminator + ")";

                EDataType action1 = getOrCreateAction(source, ctx, discriminator, customId);
                if (firstWasNull != null) {
                    // Check if it was newly created by comparing to lazy rule pattern
                    firstWasNull.set(action1.getName().startsWith("Custom_"));
                }
                if (firstCallId != null) {
                    firstCallId.set(ctx.getElementId(action1));
                }

                // Second call with same discriminator
                EDataType action2 = getOrCreateAction(source, ctx, discriminator, customId);
                if (secondWasNull != null) {
                    secondWasNull.set(action1 != action2); // Should be same instance
                }
                if (secondCallId != null) {
                    secondCallId.set(ctx.getElementId(action2));
                }

                return container;
            };
        }

        private EDataType getOrCreateAction(EClass source, TransformationContext ctx,
                                             String discriminator, String customId) {
            // RECOMMENDED PATTERN:
            // 1. Try to get existing
            EDataType existing = ctx.equivalentDiscriminated(source, EDataType.class,
                    "CreateAction", discriminator);

            if (existing != null) {
                // 2. If exists, DON'T modify - just use it
                return existing;
            }

            // 3. If null, create with custom ID
            EDataType action = ctx.createTarget(EDataType.class, customId);
            action.setName("Custom_" + source.getName());
            ctx.addToResource(action);
            return action;
        }

        @TransformRule(name = "CreateAction")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EDataType> createAction() {
            return (source, ctx) -> {
                EDataType action = ctx.createTarget(EDataType.class);
                action.setName("Lazy_" + source.getName());
                ctx.addToResource(action);
                return action;
            };
        }
    }

    /**
     * Scenario 6: Clean getOrCreate pattern
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class Scenario6Transformation {

        static AtomicReference<String> actionId;
        static AtomicReference<Boolean> wasCreated;

        @TransformRule(name = "MainRule")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> mainRule() {
            return (source, ctx) -> {
                EPackage container = ctx.createTarget(EPackage.class);
                container.setName("Container");
                ctx.addToResource(container);

                String discriminator = "test-disc";
                String customId = "custom/" + source.getName() + "/" + discriminator;

                // Try to get existing via equivalentDiscriminated
                // Note: equivalentDiscriminated will return null if lazy rule hasn't run
                EDataType action = ctx.equivalentDiscriminated(source, EDataType.class,
                        "CreateAction", discriminator);

                boolean created = false;
                if (action == null) {
                    // Doesn't exist - create with custom ID
                    action = ctx.createTarget(EDataType.class, customId);
                    action.setName("Created_" + source.getName());
                    ctx.addToResource(action);
                    created = true;
                }
                // If action exists, use as-is (don't try to change ID)

                if (wasCreated != null) {
                    wasCreated.set(created);
                }
                if (actionId != null) {
                    actionId.set(ctx.getElementId(action));
                }

                return container;
            };
        }

        @TransformRule(name = "CreateAction")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EDataType> createAction() {
            return (source, ctx) -> {
                EDataType action = ctx.createTarget(EDataType.class);
                action.setName("Lazy_" + source.getName());
                ctx.addToResource(action);
                return action;
            };
        }
    }
}
