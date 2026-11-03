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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TransformationContext#equivalentCached(EObject, String)} — cache-only
 * equivalent lookup that never triggers lazy rule execution.
 */
@DisplayName("equivalentCached() Tests")
class EquivalentCachedTest {

    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource sourceResource;
    private TransformationContext context;
    private TransformationRegistry registry;

    @BeforeEach
    void setUp() {
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

    @Test
    void cacheHitReturnsTarget() {
        // Create source element
        EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
        sourceClass.setName("Source1");
        sourceResource.getContents().add(sourceClass);

        // Register and run transformation to populate cache
        registry.register(EagerRuleTransformation.class);
        context.setTransformationRegistry(registry);

        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .build();
        executor.transform();

        // Now equivalentCached should find the cached result
        EClass result = context.equivalentCached(sourceClass, "EagerRule");
        assertNotNull(result, "equivalentCached should return cached target after transformation");
        assertEquals("Source1_target", result.getName(), "Should return the correct transformed element");
    }

    @Test
    void cacheMissReturnsNullWithoutTriggeringExecution() {
        // Create source element
        EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
        sourceClass.setName("Untransformed");
        sourceResource.getContents().add(sourceClass);

        // Register lazy rule but do NOT run transformation
        registry.register(LazyCountingTransformation.class);
        context.setTransformationRegistry(registry);
        LazyCountingTransformation.executionCount.set(0);

        // equivalentCached should return null and NOT trigger the lazy rule
        EClass result = context.equivalentCached(sourceClass, "LazyCountingRule");
        assertNull(result, "equivalentCached should return null for uncached element");
        assertEquals(0, LazyCountingTransformation.executionCount.get(),
                "equivalentCached must NOT trigger lazy rule execution");
    }

    @Test
    void nullParametersReturnNull() {
        registry.register(EagerRuleTransformation.class);
        context.setTransformationRegistry(registry);

        EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
        sourceClass.setName("Test");
        sourceResource.getContents().add(sourceClass);

        assertNull(context.equivalentCached(null, "EagerRule"),
                "null source should return null");
        assertNull(context.equivalentCached(sourceClass, null),
                "null ruleName should return null");
        assertNull(context.equivalentCached(null, null),
                "both null should return null");
    }

    // ========== Transformation Classes ==========

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class EagerRuleTransformation {
        @TransformRule(name = "EagerRule")
        @Transform(type = EClass.class)
        @Primary
        public TransformFunction<EClass, EClass> eagerRule() {
            return (source, ctx) -> {
                EClass target = ctx.createTarget(EClass.class);
                target.setName(source.getName() + "_target");
                return target;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class LazyCountingTransformation {
        static final AtomicInteger executionCount = new AtomicInteger(0);

        @TransformRule(name = "LazyCountingRule")
        @Transform(type = EClass.class)
        @Lazy
        public TransformFunction<EClass, EClass> lazyCountingRule() {
            return (source, ctx) -> {
                executionCount.incrementAndGet();
                EClass target = ctx.createTarget(EClass.class);
                target.setName(source.getName() + "_lazy");
                return target;
            };
        }
    }

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
