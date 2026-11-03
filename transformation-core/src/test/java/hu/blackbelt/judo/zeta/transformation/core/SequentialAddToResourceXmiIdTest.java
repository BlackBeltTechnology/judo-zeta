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
import org.eclipse.emf.ecore.xmi.XMIResource;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for recursive XMI ID assignment in sequential mode addToResource().
 *
 * <p>Verifies that when targets with contained children are added to a resource in
 * sequential mode, pending XMI IDs are applied to ALL elements (root + children),
 * not just the root element. This is the fix from commit 8bff98e.</p>
 */
@DisplayName("Sequential addToResource XMI ID Tests")
class SequentialAddToResourceXmiIdTest {

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
        // Structured IDs are enabled by default — no need to set explicitly

        registry = new TransformationRegistry();
    }

    @Test
    void rootElementGetsXmiIdInSequentialMode() {
        EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
        sourceClass.setName("RootSource");
        sourceResource.getContents().add(sourceClass);

        registry.register(SimpleRuleTransformation.class);
        context.setTransformationRegistry(registry);

        // Sequential mode (parallel=false is default)
        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .build();
        executor.transform();

        // Verify root element has XMI ID
        Resource targetResource = targetResourceSet.getResources().get(0);
        assertFalse(targetResource.getContents().isEmpty(), "Target resource should have elements");

        XMIResource xmiResource = (XMIResource) targetResource;
        EObject rootTarget = targetResource.getContents().get(0);
        String rootId = xmiResource.getID(rootTarget);
        assertNotNull(rootId, "Root element should have an XMI ID in sequential mode");
    }

    @Test
    void containedChildElementsGetXmiIdsInSequentialMode() {
        // Create source: EPackage containing an EClass
        EPackage sourcePackage = EcoreFactory.eINSTANCE.createEPackage();
        sourcePackage.setName("TestPkg");
        sourcePackage.setNsURI("test://TestPkg");
        sourcePackage.setNsPrefix("testpkg");
        sourceResource.getContents().add(sourcePackage);

        EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
        sourceClass.setName("ChildClass");
        sourcePackage.getEClassifiers().add(sourceClass);

        registry.register(ParentChildTransformation.class);
        context.setTransformationRegistry(registry);

        // Sequential mode (default — parallel=false)
        TransformationExecutor executor = TransformationExecutor.builder()
                .registry(registry)
                .context(context)
                .build();
        executor.transform();

        // Find the parent target (EPackage)
        Resource targetResource = targetResourceSet.getResources().get(0);
        XMIResource xmiResource = (XMIResource) targetResource;

        EPackage targetPackage = null;
        for (EObject obj : targetResource.getContents()) {
            if (obj instanceof EPackage) {
                targetPackage = (EPackage) obj;
                break;
            }
        }
        assertNotNull(targetPackage, "Should have a target EPackage");
        String parentId = xmiResource.getID(targetPackage);
        assertNotNull(parentId, "Parent element should have an XMI ID");

        // Verify contained child also has XMI ID (this is the regression from 8bff98e)
        assertFalse(targetPackage.getEClassifiers().isEmpty(),
                "Target package should contain child classifiers");

        for (EClassifier child : targetPackage.getEClassifiers()) {
            String childId = xmiResource.getID(child);
            assertNotNull(childId,
                    "Contained child element '" + child.getName() + "' should have an XMI ID " +
                    "in sequential mode (recursive propagation via applyPendingIdsRecursively)");
        }
    }

    // ========== Transformation Classes ==========

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class SimpleRuleTransformation {
        @TransformRule(name = "SimpleRule")
        @Transform(type = EClass.class)
        @Primary
        public TransformFunction<EClass, EClass> simpleRule() {
            return (source, ctx) -> {
                EClass target = ctx.createTarget(EClass.class);
                target.setName(source.getName() + "_target");
                ctx.addToResource(target);
                return target;
            };
        }
    }

    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EPackage.class, target = EPackage.class)
    public static class ParentChildTransformation {
        @TransformRule(name = "PackageRule")
        @Transform(type = EPackage.class)
        @Primary
        public TransformFunction<EPackage, EPackage> packageRule() {
            return (source, ctx) -> {
                EPackage targetPkg = ctx.createTarget(EPackage.class);
                targetPkg.setName(source.getName() + "_target");
                targetPkg.setNsURI(source.getNsURI() + "/target");
                targetPkg.setNsPrefix(source.getNsPrefix() + "_target");

                // Create child elements with their own pending XMI IDs
                for (EClassifier classifier : source.getEClassifiers()) {
                    if (classifier instanceof EClass srcClass) {
                        EClass targetClass = ctx.createTarget(EClass.class);
                        targetClass.setName(srcClass.getName() + "_target");
                        // Add as contained element — this is key for the recursive fix
                        targetPkg.getEClassifiers().add(targetClass);
                    }
                }

                ctx.addToResource(targetPkg);
                return targetPkg;
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
