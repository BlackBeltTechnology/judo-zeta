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

import hu.blackbelt.judo.zeta.common.ExtensionMethodRegistry;
import hu.blackbelt.judo.zeta.common.ModelProvider;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Abstract base class for Ecore-based transformation tests.
 *
 * <p>Provides common setup/teardown, helper methods for creating Ecore elements,
 * and assertion utilities for verifying transformation results.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * class MyTransformationTest extends AbstractEcoreTransformationTest {
 *     @Test
 *     void testMyTransformation() {
 *         EClass source = createEClass("Person");
 *         EAttribute attr = createEAttribute("name", EcorePackage.eINSTANCE.getEString());
 *         source.getEStructuralFeatures().add(attr);
 *
 *         // ... run transformation ...
 *
 *         assertEClassHasAttribute(target, "name", EcorePackage.eINSTANCE.getEString());
 *     }
 * }
 * }</pre>
 */
public abstract class AbstractEcoreTransformationTest {

    protected TransformationContext context;
    protected TransformationRegistry registry;
    protected ResourceSet sourceResourceSet;
    protected ResourceSet targetResourceSet;
    protected Resource sourceResource;
    protected Resource targetResource;

    @BeforeEach
    void setUpBase() {
        sourceResourceSet = new ResourceSetImpl();
        targetResourceSet = new ResourceSetImpl();

        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("*", new XMIResourceFactoryImpl());

        sourceResource = sourceResourceSet.createResource(URI.createURI("test://source.xmi"));
        targetResource = targetResourceSet.createResource(URI.createURI("test://target.xmi"));

        ModelProvider modelProvider = new TestModelProvider();
        ExtensionMethodRegistry extensionRegistry = mock(ExtensionMethodRegistry.class);

        context = new TransformationContext(
                modelProvider,
                sourceResourceSet,
                targetResourceSet,
                extensionRegistry
        );
        context.setTargetPackage(EcorePackage.eINSTANCE);
        context.setAutoAddRootElements(false);

        registry = new TransformationRegistry();
    }

    @AfterEach
    void tearDownBase() {
        if (sourceResourceSet != null) {
            sourceResourceSet.getResources().clear();
        }
        if (targetResourceSet != null) {
            targetResourceSet.getResources().clear();
        }
    }

    // ==================== EClass Creation Helpers ====================

    /**
     * Creates an EClass with the given name and adds it to the source resource.
     */
    protected EClass createEClass(String name) {
        EClass eClass = EcoreFactory.eINSTANCE.createEClass();
        eClass.setName(name);
        sourceResource.getContents().add(eClass);
        return eClass;
    }

    /**
     * Creates an abstract EClass with the given name.
     */
    protected EClass createAbstractEClass(String name) {
        EClass eClass = createEClass(name);
        eClass.setAbstract(true);
        return eClass;
    }

    /**
     * Creates an EClass with supertypes.
     */
    protected EClass createEClassWithSupertypes(String name, EClass... supertypes) {
        EClass eClass = createEClass(name);
        for (EClass supertype : supertypes) {
            eClass.getESuperTypes().add(supertype);
        }
        return eClass;
    }

    // ==================== EAttribute Creation Helpers ====================

    /**
     * Creates an EAttribute with the given name and type.
     */
    protected EAttribute createEAttribute(String name, EDataType type) {
        EAttribute attr = EcoreFactory.eINSTANCE.createEAttribute();
        attr.setName(name);
        attr.setEType(type);
        return attr;
    }

    /**
     * Creates an EAttribute with name, type, and cardinality.
     */
    protected EAttribute createEAttribute(String name, EDataType type, int lowerBound, int upperBound) {
        EAttribute attr = createEAttribute(name, type);
        attr.setLowerBound(lowerBound);
        attr.setUpperBound(upperBound);
        return attr;
    }

    /**
     * Creates a String-typed EAttribute.
     */
    protected EAttribute createStringAttribute(String name) {
        return createEAttribute(name, EcorePackage.eINSTANCE.getEString());
    }

    /**
     * Creates an Integer-typed EAttribute.
     */
    protected EAttribute createIntegerAttribute(String name) {
        return createEAttribute(name, EcorePackage.eINSTANCE.getEInt());
    }

    /**
     * Creates a Boolean-typed EAttribute.
     */
    protected EAttribute createBooleanAttribute(String name) {
        return createEAttribute(name, EcorePackage.eINSTANCE.getEBoolean());
    }

    // ==================== EReference Creation Helpers ====================

    /**
     * Creates an EReference with the given name and target type.
     */
    protected EReference createEReference(String name, EClass targetType) {
        EReference ref = EcoreFactory.eINSTANCE.createEReference();
        ref.setName(name);
        ref.setEType(targetType);
        return ref;
    }

    /**
     * Creates an EReference with cardinality.
     */
    protected EReference createEReference(String name, EClass targetType, int lowerBound, int upperBound) {
        EReference ref = createEReference(name, targetType);
        ref.setLowerBound(lowerBound);
        ref.setUpperBound(upperBound);
        return ref;
    }

    /**
     * Creates a containment EReference.
     */
    protected EReference createContainmentReference(String name, EClass targetType) {
        EReference ref = createEReference(name, targetType);
        ref.setContainment(true);
        return ref;
    }

    /**
     * Creates a bidirectional reference pair and returns the source reference.
     * The opposite reference is automatically set.
     */
    protected EReference createBidirectionalReference(
            String sourceRefName, EClass sourceType,
            String targetRefName, EClass targetType,
            int sourceLower, int sourceUpper,
            int targetLower, int targetUpper) {

        EReference sourceRef = createEReference(sourceRefName, targetType, sourceLower, sourceUpper);
        EReference targetRef = createEReference(targetRefName, sourceType, targetLower, targetUpper);

        sourceRef.setEOpposite(targetRef);
        targetRef.setEOpposite(sourceRef);

        return sourceRef;
    }

    // ==================== EOperation Creation Helpers ====================

    /**
     * Creates an EOperation with the given name and return type.
     */
    protected EOperation createEOperation(String name, EClassifier returnType) {
        EOperation op = EcoreFactory.eINSTANCE.createEOperation();
        op.setName(name);
        op.setEType(returnType);
        return op;
    }

    /**
     * Creates an EOperation with a parameter.
     */
    protected EOperation createEOperationWithParam(String name, EClassifier returnType,
                                                    String paramName, EClassifier paramType) {
        EOperation op = createEOperation(name, returnType);
        EParameter param = EcoreFactory.eINSTANCE.createEParameter();
        param.setName(paramName);
        param.setEType(paramType);
        op.getEParameters().add(param);
        return op;
    }

    // ==================== EPackage Creation Helpers ====================

    /**
     * Creates an EPackage with the given name and nsURI.
     */
    protected EPackage createEPackage(String name, String nsURI) {
        EPackage pkg = EcoreFactory.eINSTANCE.createEPackage();
        pkg.setName(name);
        pkg.setNsURI(nsURI);
        pkg.setNsPrefix(name.toLowerCase());
        sourceResource.getContents().add(pkg);
        return pkg;
    }

    // ==================== Assertion Helpers ====================

    /**
     * Asserts that an EClass has the expected name.
     */
    protected void assertEClassName(EClass eClass, String expectedName) {
        assertNotNull(eClass, "EClass should not be null");
        assertEquals(expectedName, eClass.getName(),
                "EClass name should be '" + expectedName + "'");
    }

    /**
     * Asserts that an EClass is abstract or concrete.
     */
    protected void assertEClassAbstract(EClass eClass, boolean expectedAbstract) {
        assertNotNull(eClass, "EClass should not be null");
        assertEquals(expectedAbstract, eClass.isAbstract(),
                "EClass abstract flag should be " + expectedAbstract);
    }

    /**
     * Asserts that an EClass has the expected number of attributes.
     */
    protected void assertEClassAttributeCount(EClass eClass, int expectedCount) {
        assertNotNull(eClass, "EClass should not be null");
        assertEquals(expectedCount, eClass.getEAttributes().size(),
                "EClass should have " + expectedCount + " attributes");
    }

    /**
     * Asserts that an EClass has an attribute with the given name.
     */
    protected void assertEClassHasAttribute(EClass eClass, String attrName) {
        assertNotNull(eClass, "EClass should not be null");
        assertTrue(eClass.getEAttributes().stream()
                        .anyMatch(a -> attrName.equals(a.getName())),
                "EClass should have attribute named '" + attrName + "'");
    }

    /**
     * Asserts that an EClass has an attribute with the given name and type.
     */
    protected void assertEClassHasAttribute(EClass eClass, String attrName, EDataType expectedType) {
        assertNotNull(eClass, "EClass should not be null");
        EAttribute attr = eClass.getEAttributes().stream()
                .filter(a -> attrName.equals(a.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(attr, "EClass should have attribute named '" + attrName + "'");
        assertEquals(expectedType, attr.getEType(),
                "Attribute '" + attrName + "' should have type " + expectedType.getName());
    }

    /**
     * Asserts that an EClass has a reference with the given name.
     */
    protected void assertEClassHasReference(EClass eClass, String refName) {
        assertNotNull(eClass, "EClass should not be null");
        assertTrue(eClass.getEReferences().stream()
                        .anyMatch(r -> refName.equals(r.getName())),
                "EClass should have reference named '" + refName + "'");
    }

    /**
     * Asserts that an EClass has a reference with the given name and target type.
     */
    protected void assertEClassHasReference(EClass eClass, String refName, EClass expectedTargetType) {
        assertNotNull(eClass, "EClass should not be null");
        EReference ref = eClass.getEReferences().stream()
                .filter(r -> refName.equals(r.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(ref, "EClass should have reference named '" + refName + "'");
        assertEquals(expectedTargetType, ref.getEType(),
                "Reference '" + refName + "' should have target type " + expectedTargetType.getName());
    }

    /**
     * Asserts reference cardinality.
     */
    protected void assertReferenceCardinality(EReference ref, int expectedLower, int expectedUpper) {
        assertNotNull(ref, "Reference should not be null");
        assertEquals(expectedLower, ref.getLowerBound(),
                "Reference lower bound should be " + expectedLower);
        assertEquals(expectedUpper, ref.getUpperBound(),
                "Reference upper bound should be " + expectedUpper);
    }

    /**
     * Asserts that a reference is containment or association.
     */
    protected void assertReferenceContainment(EReference ref, boolean expectedContainment) {
        assertNotNull(ref, "Reference should not be null");
        assertEquals(expectedContainment, ref.isContainment(),
                "Reference containment flag should be " + expectedContainment);
    }

    /**
     * Asserts that two references are bidirectional opposites.
     */
    protected void assertBidirectionalPair(EReference ref1, EReference ref2) {
        assertNotNull(ref1, "First reference should not be null");
        assertNotNull(ref2, "Second reference should not be null");
        assertEquals(ref2, ref1.getEOpposite(),
                "ref1.eOpposite should be ref2");
        assertEquals(ref1, ref2.getEOpposite(),
                "ref2.eOpposite should be ref1");
    }

    /**
     * Asserts that an EClass has the expected supertypes.
     */
    protected void assertEClassSupertypes(EClass eClass, EClass... expectedSupertypes) {
        assertNotNull(eClass, "EClass should not be null");
        assertEquals(expectedSupertypes.length, eClass.getESuperTypes().size(),
                "EClass should have " + expectedSupertypes.length + " supertypes");
        for (EClass expected : expectedSupertypes) {
            assertTrue(eClass.getESuperTypes().contains(expected),
                    "EClass should have " + expected.getName() + " as supertype");
        }
    }

    /**
     * Asserts that an EClass has access to inherited features (via getEAllAttributes/getEAllReferences).
     */
    protected void assertEClassHasInheritedFeature(EClass eClass, String featureName) {
        assertNotNull(eClass, "EClass should not be null");
        boolean found = eClass.getEAllStructuralFeatures().stream()
                .anyMatch(f -> featureName.equals(f.getName()));
        assertTrue(found, "EClass should have access to inherited feature '" + featureName + "'");
    }

    /**
     * Asserts that an EClass does NOT have access to a feature.
     */
    protected void assertEClassDoesNotHaveFeature(EClass eClass, String featureName) {
        assertNotNull(eClass, "EClass should not be null");
        boolean found = eClass.getEStructuralFeatures().stream()
                .anyMatch(f -> featureName.equals(f.getName()));
        assertFalse(found, "EClass should NOT have feature '" + featureName + "'");
    }

    /**
     * Asserts the target resource has the expected number of root elements.
     */
    protected void assertTargetSize(int expectedSize) {
        assertEquals(expectedSize, targetResource.getContents().size(),
                "Target resource should have " + expectedSize + " root elements");
    }

    /**
     * Gets the first root element from target resource as the specified type.
     */
    @SuppressWarnings("unchecked")
    protected <T extends EObject> T getTargetRoot(int index, Class<T> type) {
        assertTrue(targetResource.getContents().size() > index,
                "Target resource should have at least " + (index + 1) + " elements");
        EObject obj = targetResource.getContents().get(index);
        assertTrue(type.isInstance(obj),
                "Target element at index " + index + " should be of type " + type.getSimpleName());
        return (T) obj;
    }

    /**
     * Gets the first root element from target resource.
     */
    protected EObject getFirstTargetRoot() {
        return getTargetRoot(0, EObject.class);
    }

    // ==================== Model Provider ====================

    /**
     * Standard ModelProvider implementation for Ecore-based tests.
     */
    protected static class TestModelProvider implements ModelProvider {
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
