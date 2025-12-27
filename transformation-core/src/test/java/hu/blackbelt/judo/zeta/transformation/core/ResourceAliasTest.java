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
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for resource alias functionality in TransformationContext.
 */
@DisplayName("Resource Alias Tests")
class ResourceAliasTest {

    private TransformationContext context;
    private ResourceSet sourceResourceSet;
    private ResourceSet targetResourceSet;
    private Resource sourceResource;
    private Resource targetResource;
    private ModelProvider modelProvider;

    @BeforeEach
    void setUp() {
        sourceResourceSet = new ResourceSetImpl();
        targetResourceSet = new ResourceSetImpl();
        
        // Register XMI resource factory
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("*", new XMIResourceFactoryImpl());
        
        sourceResource = sourceResourceSet.createResource(URI.createURI("test://source.xmi"));
        targetResource = targetResourceSet.createResource(URI.createURI("test://target.xmi"));
        
        modelProvider = mock(ModelProvider.class);
        ExtensionMethodRegistry extensionRegistry = mock(ExtensionMethodRegistry.class);
        
        context = new TransformationContext(
                modelProvider,
                sourceResourceSet,
                targetResourceSet,
                extensionRegistry
        );
        context.setTargetPackage(EcorePackage.eINSTANCE);
    }

    @Nested
    @DisplayName("Default Alias Registration")
    class DefaultAliasTests {

        @Test
        @DisplayName("Should have default source alias registered")
        void shouldHaveDefaultSourceAliasRegistered() {
            ResourceSet retrieved = context.getResource("source");
            
            assertNotNull(retrieved, "Default 'source' alias should be registered");
            assertSame(sourceResourceSet, retrieved, "Source alias should point to source resource set");
        }

        @Test
        @DisplayName("Should have default target alias registered")
        void shouldHaveDefaultTargetAliasRegistered() {
            ResourceSet retrieved = context.getResource("target");
            
            assertNotNull(retrieved, "Default 'target' alias should be registered");
            assertSame(targetResourceSet, retrieved, "Target alias should point to target resource set");
        }
    }

    @Nested
    @DisplayName("Custom Alias Registration")
    class CustomAliasTests {

        @Test
        @DisplayName("Should register and retrieve custom alias")
        void shouldRegisterAndRetrieveCustomAlias() {
            ResourceSet mappingResourceSet = new ResourceSetImpl();
            
            context.registerResource("mapping", mappingResourceSet);
            ResourceSet retrieved = context.getResource("mapping");
            
            assertNotNull(retrieved, "Custom alias should be retrievable");
            assertSame(mappingResourceSet, retrieved, "Should return the registered resource set");
        }

        @Test
        @DisplayName("Should overwrite existing source alias")
        void shouldOverwriteExistingSourceAlias() {
            ResourceSet newSourceSet = new ResourceSetImpl();
            
            context.registerResource("source", newSourceSet);
            ResourceSet retrieved = context.getResource("source");
            
            assertSame(newSourceSet, retrieved, "Should overwrite the default source alias");
        }

        @Test
        @DisplayName("Should overwrite existing target alias")
        void shouldOverwriteExistingTargetAlias() {
            ResourceSet newTargetSet = new ResourceSetImpl();
            
            context.registerResource("target", newTargetSet);
            ResourceSet retrieved = context.getResource("target");
            
            assertSame(newTargetSet, retrieved, "Should overwrite the default target alias");
        }

        @Test
        @DisplayName("Should throw exception for unknown alias")
        void shouldThrowExceptionForUnknownAlias() {
            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> context.getResource("unknown")
            );
            assertTrue(exception.getMessage().contains("unknown"), 
                "Exception message should mention the unknown alias");
            assertTrue(exception.getMessage().contains("source") || exception.getMessage().contains("target"),
                "Exception message should list available aliases");
        }
    }

    @Nested
    @DisplayName("all() Method Tests")
    class AllMethodTests {

        @Test
        @DisplayName("Should get all instances from source alias")
        void shouldGetAllInstancesFromSourceAlias() {
            // Given
            EClass class1 = EcoreFactory.eINSTANCE.createEClass();
            class1.setName("Class1");
            EClass class2 = EcoreFactory.eINSTANCE.createEClass();
            class2.setName("Class2");
            sourceResource.getContents().add(class1);
            sourceResource.getContents().add(class2);
            
            when(modelProvider.getAllContents(eq(sourceResourceSet), eq(EClass.class)))
                    .thenReturn(java.util.Arrays.asList(class1, class2));

            // When
            Collection<EClass> instances = context.all("source", EClass.class);

            // Then
            assertEquals(2, instances.size(), "Should find all EClass instances from source");
            assertTrue(instances.contains(class1));
            assertTrue(instances.contains(class2));
        }

        @Test
        @DisplayName("Should get instances from custom alias")
        void shouldGetInstancesFromCustomAlias() {
            // Given
            ResourceSet mappingResourceSet = new ResourceSetImpl();
            Resource mappingResource = mappingResourceSet.createResource(URI.createURI("test://mapping.xmi"));
            EClass mappingClass = EcoreFactory.eINSTANCE.createEClass();
            mappingClass.setName("MappingClass");
            mappingResource.getContents().add(mappingClass);
            
            context.registerResource("mapping", mappingResourceSet);
            
            when(modelProvider.getAllContents(eq(mappingResourceSet), eq(EClass.class)))
                    .thenReturn(java.util.Collections.singletonList(mappingClass));

            // When
            Collection<EClass> instances = context.all("mapping", EClass.class);

            // Then
            assertEquals(1, instances.size(), "Should find EClass from mapping");
            assertTrue(instances.contains(mappingClass));
        }

        @Test
        @DisplayName("getAllSource should delegate to all with source alias")
        void getAllSourceShouldDelegateToAllWithSourceAlias() {
            // Given
            EClass class1 = EcoreFactory.eINSTANCE.createEClass();
            sourceResource.getContents().add(class1);
            
            when(modelProvider.getAllContents(eq(sourceResourceSet), eq(EClass.class)))
                    .thenReturn(java.util.Collections.singletonList(class1));

            // When
            Collection<EClass> fromGetAllSource = context.getAllSource(EClass.class);
            Collection<EClass> fromAll = context.all("source", EClass.class);

            // Then
            assertEquals(fromGetAllSource.size(), fromAll.size());
        }
    }

    @Nested
    @DisplayName("create() Method Tests")
    class CreateMethodTests {

        @Test
        @DisplayName("Should create element without containment")
        void shouldCreateElementWithoutContainment() {
            // When
            EClass created = context.create(EClass.class);

            // Then
            assertNotNull(created, "Should create element");
            assertNull(created.eContainer(), "Element should have no container");
            assertNull(created.eResource(), "Element should not be in any resource");
        }

        @Test
        @DisplayName("create() should not add to target resource")
        void createShouldNotAddToTargetResource() {
            // When
            context.create(EClass.class);
            context.create(EClass.class);

            // Then
            assertEquals(0, targetResource.getContents().size(), 
                "Target resource should remain empty after create()");
        }

        @Test
        @DisplayName("createTarget() should NOT add to target resource (ETL semantics)")
        void createTargetShouldNotAddToTargetResource() {
            // When
            EClass created = context.createTarget(EClass.class);

            // Then - ETL semantics: createTarget does NOT add to resource
            assertNotNull(created, "Should create element");
            assertEquals(0, targetResource.getContents().size(),
                "Target resource should be empty (ETL semantics)");
        }

        @Test
        @DisplayName("addToResource() should add to target resource")
        void addToResourceShouldAddToTargetResource() {
            // When
            EClass created = context.createTarget(EClass.class);
            context.addToResource(created);

            // Then
            assertNotNull(created, "Should create element");
            assertEquals(1, targetResource.getContents().size(),
                "Target resource should contain the created element");
            assertSame(created, targetResource.getContents().get(0));
        }

        @Test
        @DisplayName("Should manually set containment after create()")
        void shouldManuallySetContainmentAfterCreate() {
            // Given
            EClass parent = context.createTarget(EClass.class);
            parent.setName("Parent");

            // When
            EClass child = context.create(EClass.class);
            child.setName("Child");
            // Manually add to a container - in real use this would be parent.getChildren().add(child)
            // For EClass we can use nested classifiers
            EPackage pkg = EcoreFactory.eINSTANCE.createEPackage();
            pkg.getEClassifiers().add(child);

            // Then
            assertNotNull(child.eContainer(), "Child should now have a container");
            assertSame(pkg, child.eContainer(), "Container should be the package");
        }
    }

    @Nested
    @DisplayName("Multiple Alias Integration Tests")
    class MultipleAliasIntegrationTests {

        @Test
        @DisplayName("Should work with multiple custom aliases")
        void shouldWorkWithMultipleCustomAliases() {
            // Given
            ResourceSet mappingSet = new ResourceSetImpl();
            ResourceSet referenceSet = new ResourceSetImpl();
            
            context.registerResource("mapping", mappingSet);
            context.registerResource("reference", referenceSet);

            // When/Then
            assertSame(sourceResourceSet, context.getResource("source"));
            assertSame(targetResourceSet, context.getResource("target"));
            assertSame(mappingSet, context.getResource("mapping"));
            assertSame(referenceSet, context.getResource("reference"));
        }

        @Test
        @DisplayName("Should isolate elements by alias")
        void shouldIsolateElementsByAlias() {
            // Given
            ResourceSet mappingSet = new ResourceSetImpl();
            Resource mappingResource = mappingSet.createResource(URI.createURI("test://mapping.xmi"));
            
            EClass sourceClass = EcoreFactory.eINSTANCE.createEClass();
            sourceClass.setName("SourceClass");
            sourceResource.getContents().add(sourceClass);
            
            EClass mappingClass = EcoreFactory.eINSTANCE.createEClass();
            mappingClass.setName("MappingClass");
            mappingResource.getContents().add(mappingClass);
            
            context.registerResource("mapping", mappingSet);
            
            when(modelProvider.getAllContents(eq(sourceResourceSet), eq(EClass.class)))
                    .thenReturn(java.util.Collections.singletonList(sourceClass));
            when(modelProvider.getAllContents(eq(mappingSet), eq(EClass.class)))
                    .thenReturn(java.util.Collections.singletonList(mappingClass));

            // When
            Collection<EClass> sourceInstances = context.all("source", EClass.class);
            Collection<EClass> mappingInstances = context.all("mapping", EClass.class);

            // Then
            assertEquals(1, sourceInstances.size());
            assertEquals(1, mappingInstances.size());
            assertTrue(sourceInstances.contains(sourceClass));
            assertTrue(mappingInstances.contains(mappingClass));
            assertFalse(sourceInstances.contains(mappingClass));
            assertFalse(mappingInstances.contains(sourceClass));
        }
    }
}
