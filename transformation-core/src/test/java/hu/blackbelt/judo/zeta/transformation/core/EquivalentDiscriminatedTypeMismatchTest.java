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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the type mismatch issue in equivalentDiscriminated calls.
 *
 * This reproduces the orphan ActionDefinition problem where:
 * - Action creation uses: equivalentDiscriminated(source, TypeA.class, ruleName, disc)
 * - Button creation uses: equivalentDiscriminated(source, TypeB.class, ruleName, disc)
 *
 * Even with the same discriminator, different target types cause different instances
 * to be created, leading to orphan elements.
 *
 * Real-world example from esm2ui:
 * - TabularReferenceFieldActionUtils creates ParameterlessCallOperationActionDefinition
 * - TabularReferenceFieldTableTransformations creates OpenOperationInputFormActionDefinition
 * - Same OperationForm source, same discriminator, but different types = orphan!
 */
@DisplayName("equivalentDiscriminated Type Mismatch Tests")
class EquivalentDiscriminatedTypeMismatchTest {

    private static final Logger log = LoggerFactory.getLogger(EquivalentDiscriminatedTypeMismatchTest.class);

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

    // ==================== Issue Reproduction Tests ====================

    @Test
    @DisplayName("ISSUE: Type mismatch in equivalentDiscriminated causes orphan elements")
    void typeMismatchCausesOrphanElements() {
        // This test reproduces the orphan ActionDefinition issue:
        // 1. CreateAction rule calls equivalentDiscriminated with EDataType (wrong type)
        // 2. CreateButton rule calls equivalentDiscriminated with EEnum (correct type)
        // 3. Same source, same discriminator, but DIFFERENT types
        // 4. Result: Two different instances created, EDataType becomes orphan

        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName("OperationFormWithInput");
        sourceResource.getContents().add(source);

        // Simulate: hasInputParameter = true (like operations WITH input parameters)
        source.setAbstract(true);  // We'll use 'abstract' flag to simulate hasInputParameter

        AtomicReference<EObject> actionDefinitionFromAction = new AtomicReference<>();
        AtomicReference<EObject> actionDefinitionFromButton = new AtomicReference<>();
        AtomicReference<Boolean> typesMatch = new AtomicReference<>();

        registry.register(TypeMismatchTransformation.class);
        TypeMismatchTransformation.actionDefinitionFromAction = actionDefinitionFromAction;
        TypeMismatchTransformation.actionDefinitionFromButton = actionDefinitionFromButton;
        TypeMismatchTransformation.typesMatch = typesMatch;

        context.setTransformationRegistry(registry);
        context.setUseStructuredIds(true);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        log.info("=== Type Mismatch Issue Results ===");
        log.info("ActionDefinition from Action: {} (type: {})",
                actionDefinitionFromAction.get(),
                actionDefinitionFromAction.get() != null ? actionDefinitionFromAction.get().eClass().getName() : "null");
        log.info("ActionDefinition from Button: {} (type: {})",
                actionDefinitionFromButton.get(),
                actionDefinitionFromButton.get() != null ? actionDefinitionFromButton.get().eClass().getName() : "null");
        log.info("Types match: {}", typesMatch.get());
        log.info("Same instance: {}", actionDefinitionFromAction.get() == actionDefinitionFromButton.get());

        // Count orphans: elements at resource root that are NOT containers
        // ActionDefinitions should be contained by Buttons, not at root
        List<EObject> orphanActionDefs = targetResource.getContents().stream()
                .filter(e -> e instanceof EDataType || e instanceof EEnum)
                .filter(e -> e.eContainer() == null || e.eContainer() instanceof Resource)
                .collect(Collectors.toList());

        log.info("Orphan ActionDefinition count: {}", orphanActionDefs.size());
        orphanActionDefs.forEach(o -> log.info("  Orphan: {} ({})", o.eClass().getName(),
                ((ENamedElement) o).getName()));

        // The actionDef (EDataType) is orphaned because:
        // 1. Action created EDataType via equivalentDiscriminated
        // 2. Button created EEnum via equivalentDiscriminated (different type!)
        // 3. Button contains the EEnum, but EDataType has no container = ORPHAN
        log.info("Action's ActionDef has container: {}",
                actionDefinitionFromAction.get().eContainer() != null);
        log.info("Button's ActionDef has container: {}",
                actionDefinitionFromButton.get().eContainer() != null);

        // This demonstrates the BUG:
        // - Both Action and Button request ActionDefinition with same discriminator
        // - But they request DIFFERENT types (EDataType vs EEnum)
        // - Result: TWO different instances, one becomes orphan
        assertFalse(typesMatch.get(),
                "Types should NOT match - this demonstrates the bug");
        assertNotSame(actionDefinitionFromAction.get(), actionDefinitionFromButton.get(),
                "Different types create different instances - this is the bug");

        // The Action's ActionDef is orphaned (no container, or only Resource as container)
        // The Button's ActionDef is contained (inside EPackage)
        EObject actionDefContainer = actionDefinitionFromAction.get().eContainer();
        boolean actionDefIsOrphan = actionDefContainer == null || actionDefContainer instanceof Resource;
        assertTrue(actionDefIsOrphan,
                "Action's ActionDef should be orphan (EDataType not contained by Button)");
    }

    @Test
    @DisplayName("FIXED: Same type in equivalentDiscriminated prevents orphans")
    void sameTypePreventsOrphans() {
        // This test shows the CORRECT behavior:
        // Both Action and Button use the SAME type based on hasInputParameter

        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName("OperationFormWithInput");
        sourceResource.getContents().add(source);
        source.setAbstract(true);  // hasInputParameter = true

        AtomicReference<EObject> actionDefinitionFromAction = new AtomicReference<>();
        AtomicReference<EObject> actionDefinitionFromButton = new AtomicReference<>();
        AtomicReference<Boolean> typesMatch = new AtomicReference<>();

        registry.register(FixedTypeMismatchTransformation.class);
        FixedTypeMismatchTransformation.actionDefinitionFromAction = actionDefinitionFromAction;
        FixedTypeMismatchTransformation.actionDefinitionFromButton = actionDefinitionFromButton;
        FixedTypeMismatchTransformation.typesMatch = typesMatch;

        context.setTransformationRegistry(registry);
        context.setUseStructuredIds(true);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        log.info("=== Fixed Type Mismatch Results ===");
        log.info("ActionDefinition from Action: {} (type: {})",
                actionDefinitionFromAction.get(),
                actionDefinitionFromAction.get() != null ? actionDefinitionFromAction.get().eClass().getName() : "null");
        log.info("ActionDefinition from Button: {} (type: {})",
                actionDefinitionFromButton.get(),
                actionDefinitionFromButton.get() != null ? actionDefinitionFromButton.get().eClass().getName() : "null");
        log.info("Types match: {}", typesMatch.get());
        log.info("Same instance: {}", actionDefinitionFromAction.get() == actionDefinitionFromButton.get());

        // Count orphan ActionDefinitions (should be ZERO when fixed)
        List<EObject> orphanActionDefs = targetResource.getContents().stream()
                .filter(e -> e instanceof EDataType || e instanceof EEnum)
                .filter(e -> e.eContainer() == null || e.eContainer() instanceof Resource)
                .collect(Collectors.toList());

        log.info("Orphan ActionDefinition count: {}", orphanActionDefs.size());

        // When BOTH use the same type based on hasInputParameter:
        // - Both get the SAME instance (cached by equivalentDiscriminated)
        // - No orphans because there's only ONE instance, and it's contained
        assertTrue(typesMatch.get(),
                "Types should match when both check hasInputParameter");
        assertSame(actionDefinitionFromAction.get(), actionDefinitionFromButton.get(),
                "Both should get the SAME cached instance");
        assertEquals(0, orphanActionDefs.size(),
                "No orphan ActionDefinitions when types match");
    }

    @Test
    @DisplayName("ISSUE: Without input parameter - no type mismatch")
    void noTypeMismatchWithoutInputParameter() {
        // When hasInputParameter = false, both should use ParameterlessCallOperationActionDefinition
        // This case worked correctly even before the fix

        EClass source = EcoreFactory.eINSTANCE.createEClass();
        source.setName("OperationFormWithoutInput");
        sourceResource.getContents().add(source);
        source.setAbstract(false);  // hasInputParameter = false

        AtomicReference<EObject> actionDefinitionFromAction = new AtomicReference<>();
        AtomicReference<EObject> actionDefinitionFromButton = new AtomicReference<>();
        AtomicReference<Boolean> typesMatch = new AtomicReference<>();

        registry.register(TypeMismatchTransformation.class);
        TypeMismatchTransformation.actionDefinitionFromAction = actionDefinitionFromAction;
        TypeMismatchTransformation.actionDefinitionFromButton = actionDefinitionFromButton;
        TypeMismatchTransformation.typesMatch = typesMatch;

        context.setTransformationRegistry(registry);
        context.setUseStructuredIds(true);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        log.info("=== No Input Parameter Results ===");
        log.info("ActionDefinition from Action type: {}",
                actionDefinitionFromAction.get() != null ? actionDefinitionFromAction.get().eClass().getName() : "null");
        log.info("ActionDefinition from Button type: {}",
                actionDefinitionFromButton.get() != null ? actionDefinitionFromButton.get().eClass().getName() : "null");
        log.info("Types match: {}", typesMatch.get());

        // Without input parameter, both should use the same type (EDataType)
        // No type mismatch expected
        assertTrue(typesMatch.get(),
                "Types should match when hasInputParameter=false");
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
     * Transformation that REPRODUCES the bug:
     * - CreateAction always creates EDataType (simulating ParameterlessCallOperationActionDefinition)
     * - CreateButton checks hasInputParameter and creates EEnum when true
     *   (simulating OpenOperationInputFormActionDefinition)
     *
     * This mismatch causes orphan elements.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class TypeMismatchTransformation {

        static AtomicReference<EObject> actionDefinitionFromAction;
        static AtomicReference<EObject> actionDefinitionFromButton;
        static AtomicReference<Boolean> typesMatch;

        @TransformRule(name = "CreateContainer")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> createContainer() {
            return (source, ctx) -> {
                EPackage container = ctx.createTarget(EPackage.class);
                container.setName("Container");
                ctx.addToResource(container);

                String discriminator = "tableRow/" + source.getName();

                // Simulate Action creation (TabularReferenceFieldActionUtils)
                // BUG: Always uses EDataType regardless of hasInputParameter
                EDataType actionDef = ctx.equivalentDiscriminated(source, EDataType.class,
                        "CreateActionDefinition", discriminator);

                if (actionDefinitionFromAction != null) {
                    actionDefinitionFromAction.set(actionDef);
                }

                // Simulate Button creation (TabularReferenceFieldTableTransformations)
                // CORRECT: Checks hasInputParameter (simulated by 'abstract' flag)
                EObject buttonActionDef;
                if (source.isAbstract()) {  // hasInputParameter = true
                    // Would be OpenOperationInputFormActionDefinition
                    buttonActionDef = ctx.equivalentDiscriminated(source, EEnum.class,
                            "CreateOpenInputActionDefinition", discriminator);
                } else {
                    // ParameterlessCallOperationActionDefinition
                    buttonActionDef = ctx.equivalentDiscriminated(source, EDataType.class,
                            "CreateActionDefinition", discriminator);
                }

                if (actionDefinitionFromButton != null) {
                    actionDefinitionFromButton.set(buttonActionDef);
                }

                // Check if types match
                if (typesMatch != null) {
                    typesMatch.set(actionDef.eClass() == buttonActionDef.eClass());
                }

                // Button "contains" its action definition
                container.getEClassifiers().add((EClassifier) buttonActionDef);

                // Action just "references" its action definition (doesn't contain it)
                // If actionDef != buttonActionDef, actionDef becomes orphan!

                return container;
            };
        }

        @TransformRule(name = "CreateActionDefinition")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EDataType> createActionDefinition() {
            return (source, ctx) -> {
                // Simulates ParameterlessCallOperationActionDefinition
                EDataType actionDef = ctx.createTarget(EDataType.class);
                actionDef.setName("ParameterlessActionDef_" + source.getName());
                ctx.addToResource(actionDef);
                return actionDef;
            };
        }

        @TransformRule(name = "CreateOpenInputActionDefinition")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EEnum> createOpenInputActionDefinition() {
            return (source, ctx) -> {
                // Simulates OpenOperationInputFormActionDefinition
                EEnum actionDef = ctx.createTarget(EEnum.class);
                actionDef.setName("OpenInputActionDef_" + source.getName());
                ctx.addToResource(actionDef);
                return actionDef;
            };
        }
    }

    /**
     * Transformation that FIXES the bug:
     * - Both Action and Button check hasInputParameter
     * - Both use the same type based on the check
     * - No orphans because they get the same cached instance
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class FixedTypeMismatchTransformation {

        static AtomicReference<EObject> actionDefinitionFromAction;
        static AtomicReference<EObject> actionDefinitionFromButton;
        static AtomicReference<Boolean> typesMatch;

        @TransformRule(name = "CreateContainer")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> createContainer() {
            return (source, ctx) -> {
                EPackage container = ctx.createTarget(EPackage.class);
                container.setName("Container");
                ctx.addToResource(container);

                String discriminator = "tableRow/" + source.getName();

                // FIXED Action creation: Now checks hasInputParameter
                EObject actionDef;
                if (source.isAbstract()) {  // hasInputParameter = true
                    actionDef = ctx.equivalentDiscriminated(source, EEnum.class,
                            "CreateOpenInputActionDefinition", discriminator);
                } else {
                    actionDef = ctx.equivalentDiscriminated(source, EDataType.class,
                            "CreateActionDefinition", discriminator);
                }

                if (actionDefinitionFromAction != null) {
                    actionDefinitionFromAction.set(actionDef);
                }

                // Button creation: Same logic as Action (both check hasInputParameter)
                EObject buttonActionDef;
                if (source.isAbstract()) {  // hasInputParameter = true
                    buttonActionDef = ctx.equivalentDiscriminated(source, EEnum.class,
                            "CreateOpenInputActionDefinition", discriminator);
                } else {
                    buttonActionDef = ctx.equivalentDiscriminated(source, EDataType.class,
                            "CreateActionDefinition", discriminator);
                }

                if (actionDefinitionFromButton != null) {
                    actionDefinitionFromButton.set(buttonActionDef);
                }

                // Check if types match
                if (typesMatch != null) {
                    typesMatch.set(actionDef.eClass() == buttonActionDef.eClass());
                }

                // Button contains its action definition
                container.getEClassifiers().add((EClassifier) buttonActionDef);

                // When both use same type + discriminator, they get the same instance
                // No orphans!

                return container;
            };
        }

        @TransformRule(name = "CreateActionDefinition")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EDataType> createActionDefinition() {
            return (source, ctx) -> {
                EDataType actionDef = ctx.createTarget(EDataType.class);
                actionDef.setName("ParameterlessActionDef_" + source.getName());
                ctx.addToResource(actionDef);
                return actionDef;
            };
        }

        @TransformRule(name = "CreateOpenInputActionDefinition")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EEnum> createOpenInputActionDefinition() {
            return (source, ctx) -> {
                EEnum actionDef = ctx.createTarget(EEnum.class);
                actionDef.setName("OpenInputActionDef_" + source.getName());
                ctx.addToResource(actionDef);
                return actionDef;
            };
        }
    }
}
