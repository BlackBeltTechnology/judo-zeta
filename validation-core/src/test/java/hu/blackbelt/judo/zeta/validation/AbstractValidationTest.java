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

import hu.blackbelt.judo.zeta.validation.core.ExtensionMethodRegistry;
import hu.blackbelt.judo.zeta.validation.core.ValidationContext;
import hu.blackbelt.judo.zeta.validation.core.ValidationRegistry;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;

/**
 * Abstract base class for validation tests.
 * Provides common setup for ResourceSet, ValidationRegistry, ValidationContext, and ExtensionMethodRegistry.
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
public abstract class AbstractValidationTest {

    protected ResourceSet resourceSet;
    protected Resource testResource;
    protected ValidationRegistry registry;
    protected ExtensionMethodRegistry extensionRegistry;
    protected ValidationContext context;

    /**
     * Set up test fixtures before each test.
     * Creates ResourceSet, Resource, ValidationRegistry, ExtensionMethodRegistry, and ValidationContext.
     */
    @BeforeEach
    public void setUp() {
        // Initialize EMF resource set
        resourceSet = new ResourceSetImpl();
        
        // Register a resource factory for test URIs
        resourceSet.getResourceFactoryRegistry().getProtocolToFactoryMap().put(
            "test",
            new org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl()
        );
        
        testResource = resourceSet.createResource(
            URI.createURI("test://test-model")
        );

        // Initialize validation components
        registry = new ValidationRegistry();
        extensionRegistry = new ExtensionMethodRegistry();
        context = new ValidationContext(
            new TestModelProvider(),
            resourceSet,
            extensionRegistry
        );
        context.setValidationRegistry(registry);
    }

    /**
     * Clean up test fixtures after each test.
     * Clears resources and nullifies references to prevent test pollution.
     */
    @AfterEach
    public void tearDown() {
        // Clean up resources
        if (testResource != null) {
            testResource.getContents().clear();
        }
        if (resourceSet != null) {
            resourceSet.getResources().clear();
        }
        resourceSet = null;
        testResource = null;
        context = null;
        registry = null;
        extensionRegistry = null;
    }

    /**
     * Add an element to the test model resource.
     * This makes the element part of the ResourceSet for querying and validation.
     *
     * @param element the element to add
     * @param <T>     the type of element
     * @return the same element (for fluent API usage)
     */
    protected <T extends EObject> T addToModel(T element) {
        testResource.getContents().add(element);
        return element;
    }
}
