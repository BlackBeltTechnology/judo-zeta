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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that reproduce the discriminator mismatch problem observed in esm2ui transformations.
 *
 * <p>In esm2ui, orphan ActionDefinitions are created because:
 * <ul>
 *   <li>TransferObjectTableTransformations calls getActionDefinitionForTableNonBulk() with "tot/..." discriminator</li>
 *   <li>AccessTableTransformations calls the same helper with "access/..." discriminator</li>
 *   <li>Same source OperationForm → 2 different ActionDefinition clones</li>
 *   <li>Button contains its clone (no orphan), Action only references its clone (orphan)</li>
 * </ul>
 *
 * <p>This test simulates that pattern to verify the framework behavior and explore potential fixes.
 */
@DisplayName("esm2ui Discriminator Mismatch Tests")
class Esm2UiDiscriminatorMismatchTest {

    private static final Logger log = LoggerFactory.getLogger(Esm2UiDiscriminatorMismatchTest.class);

    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource sourceResource;
    private Resource targetResource;
    private TransformationRegistry registry;
    private TransformationContext context;

    @BeforeEach
    void setUp() {
        // Register XMI resource factory for all extensions
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("*", new XMIResourceFactoryImpl());

        // Source setup
        sourceResourceSet = new ResourceSetImpl();
        sourceResource = sourceResourceSet.createResource(URI.createURI("test://source.xmi"));

        // Target setup
        targetResourceSet = new ResourceSetImpl();
        targetResource = targetResourceSet.createResource(URI.createURI("test://target.xmi"));

        // Registry and context
        registry = new TransformationRegistry();
        ExtensionMethodRegistry extensionRegistry = new ExtensionMethodRegistry();
        ModelProvider modelProvider = new TestModelProvider();

        context = new TransformationContext(
                modelProvider, sourceResourceSet, targetResourceSet, extensionRegistry);
        context.setTargetPackage(EcorePackage.eINSTANCE);
        context.registerResource("source", sourceResourceSet);
        context.registerResource("target", targetResourceSet);
    }

    // ==================== ESM2UI Pattern Tests ====================

    /**
     * Simulates the esm2ui pattern where:
     * - OperationForm is transformed to ActionDefinition
     * - Button transformation (TransferObjectTable context) uses "tot/..." discriminator
     * - Action transformation (AccessTable context) uses "access/..." discriminator
     * - Same source, different discriminators = 2 clones, 1 orphan
     *
     * <p>Expected current behavior (with Option A fix):
     * - No original orphan (Option A prevents this)
     * - BUT: 2 clones created due to different discriminators
     * - Clone A (Button's) is contained → not orphan
     * - Clone B (Action's) is only referenced → ORPHAN
     */
    @Test
    @DisplayName("Different discriminators from shared helper create orphan clone")
    void differentDiscriminatorsFromSharedHelperCreateOrphan() {
        // Create source: OperationForm (simulated as EClass)
        EClass operationForm = EcoreFactory.eINSTANCE.createEClass();
        operationForm.setName("TestOperationForm");
        sourceResource.getContents().add(operationForm);

        // Register transformation that simulates esm2ui pattern
        registry.register(Esm2UiPatternTransformation.class);
        context.setTransformationRegistry(registry);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        log.info("=== esm2ui Discriminator Mismatch Test ===");
        logRootElements();

        // Get ActionDefinitions at root
        List<EDataType> actionDefs = targetResource.getContents().stream()
                .filter(e -> e instanceof EDataType)
                .map(e -> (EDataType) e)
                .collect(Collectors.toList());

        log.info("ActionDefinitions at root: {}", actionDefs.size());

        // Separate by container status
        List<EDataType> contained = actionDefs.stream()
                .filter(a -> a.eContainer() != null)
                .collect(Collectors.toList());
        List<EDataType> orphans = actionDefs.stream()
                .filter(a -> a.eContainer() == null)
                .collect(Collectors.toList());

        log.info("Contained: {}", contained.size());
        contained.forEach(a -> log.info("  - {} (container={})", a.getName(),
                ((ENamedElement) a.eContainer()).getName()));

        log.info("Orphans: {}", orphans.size());
        orphans.forEach(a -> log.info("  - {} (container=null)", a.getName()));

        // CURRENT BEHAVIOR: Different discriminators create 2 clones
        // - Clone for Button (contained) + Clone for Action (orphan)
        assertEquals(2, actionDefs.size(),
                "Different discriminators should create 2 ActionDefinition clones");
        assertEquals(1, contained.size(),
                "Button's clone should be contained");
        assertEquals(1, orphans.size(),
                "Action's clone should be orphan (discriminator mismatch)");

        // Verify this is the discriminator mismatch problem, not duplicate original problem
        // With Option A fix, there's no original orphan - just the clone orphan
        log.info("CONFIRMED: Orphan is due to discriminator mismatch, not duplicate original");
    }

    /**
     * When Button and Action use the SAME discriminator (correct pattern),
     * they share the same clone instance and there are no orphans.
     */
    @Test
    @DisplayName("Same discriminator from shared helper - no orphans")
    void sameDiscriminatorFromSharedHelperNoOrphans() {
        EClass operationForm = EcoreFactory.eINSTANCE.createEClass();
        operationForm.setName("TestOperationForm");
        sourceResource.getContents().add(operationForm);

        // Register transformation with aligned discriminators
        registry.register(Esm2UiAlignedDiscriminatorTransformation.class);
        context.setTransformationRegistry(registry);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        log.info("=== esm2ui Aligned Discriminator Test ===");
        logRootElements();

        List<EDataType> actionDefs = targetResource.getContents().stream()
                .filter(e -> e instanceof EDataType)
                .map(e -> (EDataType) e)
                .collect(Collectors.toList());

        List<EDataType> orphans = actionDefs.stream()
                .filter(a -> a.eContainer() == null)
                .collect(Collectors.toList());

        // With same discriminator, only 1 clone is created and shared
        assertEquals(1, actionDefs.size(),
                "Same discriminator should create exactly 1 ActionDefinition");
        assertEquals(0, orphans.size(),
                "With aligned discriminators, no orphans should exist");

        log.info("SUCCESS: Aligned discriminators = 1 clone, no orphans");
    }

    /**
     * Multiple OperationForms with discriminator mismatch accumulate orphans.
     */
    @Test
    @DisplayName("Multiple sources accumulate orphans from discriminator mismatch")
    void multipleSourcesAccumulateOrphans() {
        // Create 5 OperationForms
        for (int i = 0; i < 5; i++) {
            EClass operationForm = EcoreFactory.eINSTANCE.createEClass();
            operationForm.setName("OperationForm" + i);
            sourceResource.getContents().add(operationForm);
        }

        registry.register(Esm2UiPatternTransformation.class);
        context.setTransformationRegistry(registry);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .parallel(false)
                .build();

        executor.transform();

        log.info("=== Multiple Sources Orphan Accumulation ===");

        List<EDataType> allActionDefs = targetResource.getContents().stream()
                .filter(e -> e instanceof EDataType)
                .map(e -> (EDataType) e)
                .collect(Collectors.toList());

        List<EDataType> orphans = allActionDefs.stream()
                .filter(a -> a.eContainer() == null)
                .collect(Collectors.toList());

        log.info("Sources: 5");
        log.info("Total ActionDefs: {}", allActionDefs.size());
        log.info("Orphans: {}", orphans.size());

        // 5 sources × 2 clones (Button + Action) = 10 ActionDefs
        // 5 orphans (Action's clones)
        assertEquals(10, allActionDefs.size(),
                "5 sources × 2 clones = 10 ActionDefs");
        assertEquals(5, orphans.size(),
                "5 sources × 1 orphan (Action's clone) = 5 orphans");
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

    // ==================== Transformations ====================

    /**
     * Simulates esm2ui pattern with MISMATCHED discriminators.
     *
     * <p>This is the problematic pattern from esm2ui where:
     * - TransferObjectTableTransformations uses "tot/..." discriminator
     * - AccessTableTransformations uses "access/..." discriminator
     * - Both call the same shared helper method for ActionDefinition
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class Esm2UiPatternTransformation {

        /**
         * Shared helper method (like getActionDefinitionForTableNonBulk in esm2ui).
         * Called by both Button and Action contexts with different discriminators.
         */
        private static EDataType getActionDefinitionForOperationForm(
                EClass operationForm, String contextDiscriminator, TransformationContext ctx) {
            // In esm2ui, this method builds the discriminator from the context
            // Different contexts pass different discriminator prefixes
            String discriminator = contextDiscriminator + "/" + operationForm.getName();

            return ctx.equivalentDiscriminated(operationForm, EDataType.class,
                    "CreateActionDefinition", discriminator);
        }

        @TransformRule(name = "CreatePageDefinition")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> createPageDefinition() {
            return (source, ctx) -> {
                EPackage page = ctx.createTarget(EPackage.class);
                page.setName("PageDefinition");
                ctx.addToResource(page);

                // TransferObjectTable context creates Button
                EPackage button = ctx.equivalentDiscriminated(source, EPackage.class,
                        "CreateButton", "tot/" + source.getName());
                page.getESubpackages().add(button);

                // AccessTable context creates Action
                EAnnotation action = ctx.equivalentDiscriminated(source, EAnnotation.class,
                        "CreateAction", "access/" + source.getName());
                page.getEAnnotations().add(action);

                return page;
            };
        }

        @TransformRule(name = "CreateButton")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EPackage> createButton() {
            return (source, ctx) -> {
                EPackage button = ctx.createTarget(EPackage.class);
                button.setName("Button_" + source.getName());

                // TransferObjectTable context uses "tot" discriminator
                EDataType actionDef = getActionDefinitionForOperationForm(source, "tot", ctx);

                // CONTAINMENT: Button.actionDefinition is containment=true in UI metamodel
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

                // AccessTable context uses "access" discriminator (DIFFERENT!)
                EDataType actionDef = getActionDefinitionForOperationForm(source, "access", ctx);

                // REFERENCE ONLY: Action.actionDefinition is reference-only in UI metamodel
                // This ActionDefinition stays at Resource root = ORPHAN
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
                ctx.addToResource(actionDef);
                return actionDef;
            };
        }
    }

    /**
     * Simulates esm2ui pattern with ALIGNED discriminators.
     *
     * <p>This is the CORRECT pattern - both Button and Action use the same discriminator
     * for the same logical ActionDefinition, so they share the same instance.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class Esm2UiAlignedDiscriminatorTransformation {

        /**
         * Shared helper method with ALIGNED discriminator.
         * Uses the source ID as discriminator, not the context.
         */
        private static EDataType getActionDefinitionForOperationForm(
                EClass operationForm, TransformationContext ctx) {
            // CORRECT: Use source-based discriminator, not context-based
            String discriminator = "actionDef/" + operationForm.getName();

            return ctx.equivalentDiscriminated(operationForm, EDataType.class,
                    "CreateActionDefinition", discriminator);
        }

        @TransformRule(name = "CreatePageDefinition")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EPackage> createPageDefinition() {
            return (source, ctx) -> {
                EPackage page = ctx.createTarget(EPackage.class);
                page.setName("PageDefinition");
                ctx.addToResource(page);

                // Both use aligned discriminators
                EPackage button = ctx.equivalentDiscriminated(source, EPackage.class,
                        "CreateButton", "page/" + source.getName());
                page.getESubpackages().add(button);

                EAnnotation action = ctx.equivalentDiscriminated(source, EAnnotation.class,
                        "CreateAction", "page/" + source.getName());
                page.getEAnnotations().add(action);

                return page;
            };
        }

        @TransformRule(name = "CreateButton")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EPackage> createButton() {
            return (source, ctx) -> {
                EPackage button = ctx.createTarget(EPackage.class);
                button.setName("Button_" + source.getName());

                // Uses SAME discriminator as Action
                EDataType actionDef = getActionDefinitionForOperationForm(source, ctx);

                // CONTAINMENT
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

                // Uses SAME discriminator as Button
                EDataType actionDef = getActionDefinitionForOperationForm(source, ctx);

                // REFERENCE: References the SAME instance that Button contains
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
                ctx.addToResource(actionDef);
                return actionDef;
            };
        }
    }
}
