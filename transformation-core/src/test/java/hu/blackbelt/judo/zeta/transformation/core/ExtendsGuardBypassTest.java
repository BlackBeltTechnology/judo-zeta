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

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for guard evaluation in @Extends inheritance.
 *
 * <p>This test reproduces a bug where parent rule guards are bypassed when invoked
 * via @Extends from a child rule. The issue occurs when:</p>
 * <ol>
 *   <li>Parent rule has @Primary and @Guard</li>
 *   <li>Child rule has @Primary and @Extends("ParentRule")</li>
 *   <li>Child's guard passes, but parent's guard should reject</li>
 *   <li>Both rules add elements to the same container</li>
 * </ol>
 *
 * <p>Expected behavior: Parent's guard should be checked before executing parent rule.</p>
 * <p>Actual behavior (bug): Parent's guard is bypassed, causing duplicate elements.</p>
 */
@DisplayName("@Extends Guard Bypass Tests")
class ExtendsGuardBypassTest {

    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource sourceResource;
    private Resource targetResource;
    private TransformationContext context;
    private TransformationRegistry registry;

    // Static tracking for test verification
    static AtomicInteger parentRuleExecutionCount = new AtomicInteger(0);
    static AtomicInteger childRuleExecutionCount = new AtomicInteger(0);
    static AtomicInteger parentGuardCallCount = new AtomicInteger(0);
    static AtomicInteger childGuardCallCount = new AtomicInteger(0);
    static List<String> executionLog = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        // Reset tracking
        parentRuleExecutionCount.set(0);
        childRuleExecutionCount.set(0);
        parentGuardCallCount.set(0);
        childGuardCallCount.set(0);
        executionLog.clear();

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

    /**
     * Create an EClass with the given name.
     */
    private EClass createEClass(String name) {
        EClass eClass = EcoreFactory.eINSTANCE.createEClass();
        eClass.setName(name);
        sourceResource.getContents().add(eClass);
        return eClass;
    }

    // ==================== Bug Reproduction Tests ====================

    @Nested
    @DisplayName("Guard Bypass Bug Reproduction")
    class GuardBypassBugReproduction {

        /**
         * Reproduces the guard bypass bug.
         *
         * <p>Scenario:</p>
         * <ul>
         *   <li>Source element: EClass named "Regular_Item"</li>
         *   <li>Parent rule: Guard accepts ONLY elements starting with "Special_"</li>
         *   <li>Child rule: Guard always returns true (accepts all)</li>
         *   <li>Child rule extends Parent rule</li>
         * </ul>
         *
         * <p>ETL Semantics (from documentation):</p>
         * <blockquote>
         *   "the element must...also satisfy the guard of the rule (and all the rules it extends)."
         * </blockquote>
         *
         * <p>Expected behavior:</p>
         * <ul>
         *   <li>For "Regular_Item": Parent's guard is evaluated FIRST</li>
         *   <li>Parent guard rejects (doesn't start with "Special_")</li>
         *   <li>Child rule should ABORT because parent guard failed</li>
         *   <li>No target element created</li>
         * </ul>
         *
         * <p>Actual behavior (bug):</p>
         * <ul>
         *   <li>For "Regular_Item": Parent's guard is NEVER evaluated</li>
         *   <li>Child executes, parent executes via @Extends, duplicate created</li>
         * </ul>
         */
        @Test
        @DisplayName("Parent guard should be evaluated when invoked via @Extends")
        void testParentGuardEvaluatedViaExtends() {
            // Create a source element that should PASS child's guard but FAIL parent's guard
            createEClass("Regular_Item");

            registry.register(ParentPrimaryRule.class);
            registry.register(ChildPrimaryRule.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Log for debugging
            System.out.println("Execution log: " + executionLog);
            System.out.println("Parent guard calls: " + parentGuardCallCount.get());
            System.out.println("Child guard calls: " + childGuardCallCount.get());
            System.out.println("Parent rule executions: " + parentRuleExecutionCount.get());
            System.out.println("Child rule executions: " + childRuleExecutionCount.get());

            // CRITICAL ASSERTION: Parent guard should have been called
            // ETL semantics: guards of all extended rules must be evaluated
            assertTrue(parentGuardCallCount.get() >= 1,
                    "Parent guard should be evaluated when child rule uses @Extends");

            // For "Regular_Item", the parent guard should REJECT (doesn't start with "Special_")
            // Therefore parent rule should NOT execute
            assertEquals(0, parentRuleExecutionCount.get(),
                    "Parent rule should NOT execute when its guard rejects the source element");

            // ETL Semantics: Child rule should ALSO abort when parent guard rejects
            // "the element must satisfy the guard of the rule AND all the rules it extends"
            assertEquals(0, childRuleExecutionCount.get(),
                    "Child rule should NOT execute when parent guard rejects (ETL semantics)");

            // No target should be created
            assertEquals(0, targetResource.getContents().size(),
                    "No target should be created when parent guard rejects");
        }

        /**
         * Test that parent guard correctly passes for valid elements.
         */
        @Test
        @DisplayName("Parent guard should pass when element matches guard condition")
        void testParentGuardPassesForValidElement() {
            // Create a source element that should PASS both guards
            createEClass("Special_Item");

            registry.register(ParentPrimaryRule.class);
            registry.register(ChildPrimaryRule.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            System.out.println("Execution log: " + executionLog);

            // For "Special_Item", both guards should pass
            assertTrue(parentGuardCallCount.get() >= 1,
                    "Parent guard should be evaluated");

            // Parent rule should execute (guard passes)
            assertEquals(1, parentRuleExecutionCount.get(),
                    "Parent rule should execute exactly once when guard passes");

            // Child rule should execute
            assertEquals(1, childRuleExecutionCount.get(),
                    "Child rule should execute exactly once");

            // Only one target should be created (child creates it, parent populates it)
            assertEquals(1, targetResource.getContents().size(),
                    "Only one target element should be created via @Extends inheritance");
        }

        /**
         * Test that simulates the real-world scenario described in the bug report:
         * Adding units to a measure where duplicate elements appear.
         */
        @Test
        @DisplayName("No duplicate elements when parent guard rejects")
        void testNoDuplicateElementsWhenParentGuardRejects() {
            // Create both types of elements
            createEClass("Special_Unit");  // Should be transformed (both guards pass)
            createEClass("Regular_Unit");  // Should NOT be transformed by parent (guard rejects)

            registry.register(ParentPrimaryRule.class);
            registry.register(ChildPrimaryRule.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            System.out.println("Execution log: " + executionLog);
            System.out.println("Target contents: " + targetResource.getContents().size());

            // Only "Special_Unit" should produce a target (parent guard passes)
            // "Regular_Unit" should NOT produce a target (parent guard rejects)
            assertEquals(1, targetResource.getContents().size(),
                    "Only elements passing BOTH guards should produce targets");

            // Verify the target is from the correct source
            EPackage target = (EPackage) targetResource.getContents().get(0);
            assertTrue(target.getName().contains("Special"),
                    "Target should be from Special_Unit, not Regular_Unit");
        }
    }

    // ==================== Test Transformation Rules ====================

    /**
     * Parent rule with @Primary, @Abstract, and @Guard.
     * The guard only accepts elements whose name starts with "Special_".
     *
     * IMPORTANT: This rule is @Abstract so it doesn't run during the eager phase.
     * It should ONLY be invoked via @Extends from the child rule.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ParentPrimaryRule {

        @TransformRule(name = "ParentRule")
        @Transform(type = EClass.class)
        @Primary
        @Abstract  // Make abstract so it only runs via @Extends
        @Guard(method = "isSpecial")
        public TransformFunction<EClass, EPackage> parentRule() {
            return (source, ctx) -> {
                parentRuleExecutionCount.incrementAndGet();
                executionLog.add("ParentRule:" + source.getName());

                EPackage pkg = ctx.createTarget(EPackage.class);
                pkg.setName("Parent_" + source.getName());
                ctx.addToResource(pkg);
                return pkg;
            };
        }

        /**
         * Guard that only accepts elements starting with "Special_".
         */
        public boolean isSpecial(EObject source, TransformationContext ctx) {
            parentGuardCallCount.incrementAndGet();
            executionLog.add("ParentGuard:" + ((EClass) source).getName());

            String name = ((EClass) source).getName();
            boolean result = name.startsWith("Special_");
            executionLog.add("ParentGuard result: " + result);
            return result;
        }
    }

    /**
     * Child rule with @Primary and @Extends("ParentRule").
     * The guard always returns true.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EPackage.class)
    public static class ChildPrimaryRule {

        @TransformRule(name = "ChildRule")
        @Transform(type = EClass.class)
        @Primary
        @Extends("ParentRule")
        @Guard(method = "alwaysAccept")
        public TransformFunction<EClass, EPackage> childRule() {
            return (source, ctx) -> {
                childRuleExecutionCount.incrementAndGet();
                executionLog.add("ChildRule:" + source.getName());

                // Execute parent rule via @Extends
                EPackage pkg = ctx.executeParentRule("ParentRule", source);
                if (pkg != null) {
                    pkg.setNsPrefix("Child_" + source.getName());
                }
                return pkg;
            };
        }

        /**
         * Guard that always accepts.
         */
        public boolean alwaysAccept(EObject source, TransformationContext ctx) {
            childGuardCallCount.incrementAndGet();
            executionLog.add("ChildGuard:" + ((EClass) source).getName());
            return true;
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
