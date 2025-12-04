package hu.blackbelt.judo.zeta.validation;

/*-
 * #%L
 * Judo :: Zeta :: Validation Core
 * %%
 * Copyright (C) 2018 - 2025 BlackBelt Technology
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

import org.eclipse.emf.ecore.*;

/**
 * Factory for creating test ECore metamodel elements.
 * Uses the actual ECore metamodel (EClass, EPackage, EAttribute, EReference) for realistic test scenarios.
 */
public class TestModelFactory {

    private static final EcoreFactory ECORE = EcoreFactory.eINSTANCE;
    private static final EcorePackage ECORE_PKG = EcorePackage.eINSTANCE;

    /**
     * Create an EClass instance (represents a class in a metamodel).
     *
     * @param name the name of the EClass
     * @return the created EClass
     */
    public static EClass createEClass(String name) {
        EClass eClass = ECORE.createEClass();
        eClass.setName(name);
        return eClass;
    }

    /**
     * Create an EAttribute instance (represents an attribute/property).
     *
     * @param name the name of the attribute
     * @param type the data type of the attribute
     * @return the created EAttribute
     */
    public static EAttribute createEAttribute(String name, EDataType type) {
        EAttribute attr = ECORE.createEAttribute();
        attr.setName(name);
        attr.setEType(type);
        return attr;
    }

    /**
     * Create an EPackage instance (represents a namespace/package).
     *
     * @param name  the name of the package
     * @param nsURI the namespace URI
     * @return the created EPackage
     */
    public static EPackage createEPackage(String name, String nsURI) {
        EPackage pkg = ECORE.createEPackage();
        pkg.setName(name);
        pkg.setNsURI(nsURI);
        pkg.setNsPrefix(name);
        return pkg;
    }

    /**
     * Create an EReference instance (represents a reference to another class).
     *
     * @param name the name of the reference
     * @param type the target EClass
     * @return the created EReference
     */
    public static EReference createEReference(String name, EClass type) {
        EReference ref = ECORE.createEReference();
        ref.setName(name);
        ref.setEType(type);
        return ref;
    }

    /**
     * Create an EOperation instance (represents a method/operation).
     *
     * @param name the name of the operation
     * @return the created EOperation
     */
    public static EOperation createEOperation(String name) {
        EOperation op = ECORE.createEOperation();
        op.setName(name);
        return op;
    }

    /**
     * Create a simple test model with package, class, and attribute.
     * This is useful for quick test setup without manually creating each element.
     *
     * @return the root EPackage containing the test model
     */
    public static EPackage createSimpleTestModel() {
        EPackage pkg = createEPackage("testmodel", "http://test.model");

        EClass personClass = createEClass("Person");
        EAttribute nameAttr = createEAttribute("name", ECORE_PKG.getEString());
        EAttribute ageAttr = createEAttribute("age", ECORE_PKG.getEInt());

        personClass.getEStructuralFeatures().add(nameAttr);
        personClass.getEStructuralFeatures().add(ageAttr);
        pkg.getEClassifiers().add(personClass);

        return pkg;
    }

    /**
     * Get the EString data type (commonly used for string attributes).
     *
     * @return EString data type
     */
    public static EDataType getEString() {
        return ECORE_PKG.getEString();
    }

    /**
     * Get the EInt data type (commonly used for integer attributes).
     *
     * @return EInt data type
     */
    public static EDataType getEInt() {
        return ECORE_PKG.getEInt();
    }

    /**
     * Get the EBoolean data type (commonly used for boolean attributes).
     *
     * @return EBoolean data type
     */
    public static EDataType getEBoolean() {
        return ECORE_PKG.getEBoolean();
    }
}
