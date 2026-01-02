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

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ElementResolutionCache.
 */
class ElementResolutionCacheTest {

    private ElementResolutionCache cache;
    private EObject sourceElement;
    private EObject targetElement1;
    private EObject targetElement2;

    @BeforeEach
    void setUp() {
        cache = new ElementResolutionCache();
        
        // Create test EObjects using Ecore metamodel
        sourceElement = EcoreFactory.eINSTANCE.createEClass();
        ((EClass) sourceElement).setName("SourceClass");
        
        targetElement1 = EcoreFactory.eINSTANCE.createEClass();
        ((EClass) targetElement1).setName("TargetClass1");
        
        targetElement2 = EcoreFactory.eINSTANCE.createEClass();
        ((EClass) targetElement2).setName("TargetClass2");
    }

    @Test
    void testAddAndGetByRule() {
        cache.addMapping(sourceElement, "Rule1", targetElement1, false);
        
        EObject result = cache.getByRule(sourceElement, "Rule1");
        
        assertNotNull(result);
        assertSame(targetElement1, result);
    }

    @Test
    void testGetByRuleReturnsNullForUnknownRule() {
        cache.addMapping(sourceElement, "Rule1", targetElement1, false);
        
        EObject result = cache.getByRule(sourceElement, "UnknownRule");
        
        assertNull(result);
    }

    @Test
    void testGetByRuleReturnsNullForUnknownSource() {
        cache.addMapping(sourceElement, "Rule1", targetElement1, false);
        
        EObject unknownSource = EcoreFactory.eINSTANCE.createEClass();
        EObject result = cache.getByRule(unknownSource, "Rule1");
        
        assertNull(result);
    }

    @Test
    void testGetEquivalentReturnsPrimaryFirst() {
        cache.addMapping(sourceElement, "Rule1", targetElement1, false);
        cache.addMapping(sourceElement, "Rule2", targetElement2, true); // primary
        
        EClass result = cache.getEquivalent(sourceElement, EClass.class);
        
        assertNotNull(result);
        assertSame(targetElement2, result); // Primary should be returned
    }

    @Test
    void testGetEquivalentReturnsFirstWhenNoPrimary() {
        cache.addMapping(sourceElement, "Rule1", targetElement1, false);
        cache.addMapping(sourceElement, "Rule2", targetElement2, false);
        
        EClass result = cache.getEquivalent(sourceElement, EClass.class);
        
        assertNotNull(result);
        assertSame(targetElement1, result); // First added should be returned
    }

    @Test
    void testGetEquivalents() {
        cache.addMapping(sourceElement, "Rule1", targetElement1, false);
        cache.addMapping(sourceElement, "Rule2", targetElement2, false);
        
        List<EClass> results = cache.getEquivalents(sourceElement, EClass.class);
        
        assertEquals(2, results.size());
        assertTrue(results.contains(targetElement1));
        assertTrue(results.contains(targetElement2));
    }

    @Test
    void testGetEquivalentsReturnsEmptyListForUnknownSource() {
        EObject unknownSource = EcoreFactory.eINSTANCE.createEClass();
        
        List<EClass> results = cache.getEquivalents(unknownSource, EClass.class);
        
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    void testDiscriminatedMapping() {
        cache.addDiscriminatedMapping(sourceElement, targetElement1, "Rule1", "create");
        cache.addDiscriminatedMapping(sourceElement, targetElement2, "Rule1", "update");
        
        EClass createResult = cache.getEquivalentDiscriminated(sourceElement, EClass.class, "Rule1", "create");
        EClass updateResult = cache.getEquivalentDiscriminated(sourceElement, EClass.class, "Rule1", "update");
        
        assertSame(targetElement1, createResult);
        assertSame(targetElement2, updateResult);
    }

    @Test
    void testDiscriminatedMappingReturnsNullForUnknownDiscriminator() {
        cache.addDiscriminatedMapping(sourceElement, targetElement1, "Rule1", "create");
        
        EClass result = cache.getEquivalentDiscriminated(sourceElement, EClass.class, "Rule1", "delete");
        
        assertNull(result);
    }

    @Test
    void testGetAllMappings() {
        cache.addMapping(sourceElement, "Rule1", targetElement1, true);
        cache.addMapping(sourceElement, "Rule2", targetElement2, false);
        cache.addDiscriminatedMapping(sourceElement, targetElement1, "Rule3", "disc1");
        
        Collection<ElementResolutionCache.TraceEntry> mappings = cache.getAllMappings();
        
        assertEquals(3, mappings.size());
    }

    @Test
    void testClear() {
        cache.addMapping(sourceElement, "Rule1", targetElement1, false);
        cache.addDiscriminatedMapping(sourceElement, targetElement2, "Rule2", "disc");
        
        cache.clear();
        
        assertNull(cache.getByRule(sourceElement, "Rule1"));
        assertNull(cache.getEquivalentDiscriminated(sourceElement, EClass.class, "Rule2", "disc"));
        assertTrue(cache.getAllMappings().isEmpty());
    }

    @Test
    void testTraceEntryProperties() {
        cache.addMapping(sourceElement, "TestRule", targetElement1, true);
        
        Collection<ElementResolutionCache.TraceEntry> mappings = cache.getAllMappings();
        ElementResolutionCache.TraceEntry entry = mappings.iterator().next();
        
        assertSame(sourceElement, entry.getSource());
        assertSame(targetElement1, entry.getTarget());
        assertEquals("TestRule", entry.getRuleName());
        assertTrue(entry.isPrimary());
        assertNull(entry.getDiscriminator());
    }

    @Test
    void testDiscriminatedTraceEntryProperties() {
        cache.addDiscriminatedMapping(sourceElement, targetElement1, "DiscRule", "myDisc");

        Collection<ElementResolutionCache.TraceEntry> mappings = cache.getAllMappings();
        ElementResolutionCache.TraceEntry entry = mappings.iterator().next();

        assertSame(sourceElement, entry.getSource());
        assertSame(targetElement1, entry.getTarget());
        assertEquals("DiscRule", entry.getRuleName());
        assertEquals("myDisc", entry.getDiscriminator());
        assertFalse(entry.isPrimary());
    }

    // ==================== Null Parameter Handling Tests ====================

    @Test
    void testAddDiscriminatedMappingWithNullSourceDoesNotThrow() {
        // Should not throw NPE - ConcurrentHashMap doesn't allow null keys
        assertDoesNotThrow(() ->
            cache.addDiscriminatedMapping(null, targetElement1, "Rule1", "disc1"));

        // Should not be cached
        assertNull(cache.getEquivalentDiscriminated(null, EClass.class, "Rule1", "disc1"));
    }

    @Test
    void testAddDiscriminatedMappingWithNullTargetDoesNotThrow() {
        // Should not throw NPE - null targets are silently ignored
        assertDoesNotThrow(() ->
            cache.addDiscriminatedMapping(sourceElement, null, "Rule1", "disc1"));

        // Should not be cached
        assertNull(cache.getEquivalentDiscriminated(sourceElement, EClass.class, "Rule1", "disc1"));
    }

    @Test
    void testAddDiscriminatedMappingWithNullRuleNameDoesNotThrow() {
        // Should not throw NPE
        assertDoesNotThrow(() ->
            cache.addDiscriminatedMapping(sourceElement, targetElement1, null, "disc1"));

        // Should not be cached under null rule name
        assertNull(cache.getEquivalentDiscriminated(sourceElement, EClass.class, null, "disc1"));
    }

    @Test
    void testAddDiscriminatedMappingWithNullDiscriminatorDoesNotThrow() {
        // Should not throw NPE
        assertDoesNotThrow(() ->
            cache.addDiscriminatedMapping(sourceElement, targetElement1, "Rule1", null));

        // Should not be cached under null discriminator
        assertNull(cache.getEquivalentDiscriminated(sourceElement, EClass.class, "Rule1", null));
    }

    @Test
    void testAddDiscriminatedMappingWithAllNullsDoesNotThrow() {
        // Should not throw NPE even with all nulls
        assertDoesNotThrow(() ->
            cache.addDiscriminatedMapping(null, null, null, null));
    }

    @Test
    void testAddMappingWithNullSourceDoesNotThrow() {
        // Should not throw NPE
        assertDoesNotThrow(() ->
            cache.addMapping(null, "Rule1", targetElement1, false));
    }

    @Test
    void testAddMappingWithNullTargetDoesNotThrow() {
        // Should not throw NPE
        assertDoesNotThrow(() ->
            cache.addMapping(sourceElement, "Rule1", null, false));
    }

    @Test
    void testAddMappingWithNullRuleNameDoesNotThrow() {
        // Should not throw NPE
        assertDoesNotThrow(() ->
            cache.addMapping(sourceElement, null, targetElement1, false));
    }

    @Test
    void testGetByRuleWithNullsReturnsNull() {
        cache.addMapping(sourceElement, "Rule1", targetElement1, false);

        assertNull(cache.getByRule(null, "Rule1"));
        assertNull(cache.getByRule(sourceElement, null));
        assertNull(cache.getByRule(null, null));
    }

    @Test
    void testGetEquivalentDiscriminatedWithNullsReturnsNull() {
        cache.addDiscriminatedMapping(sourceElement, targetElement1, "Rule1", "disc1");

        assertNull(cache.getEquivalentDiscriminated(null, EClass.class, "Rule1", "disc1"));
        assertNull(cache.getEquivalentDiscriminated(sourceElement, EClass.class, null, "disc1"));
        assertNull(cache.getEquivalentDiscriminated(sourceElement, EClass.class, "Rule1", null));
    }
}
