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
import org.eclipse.emf.ecore.EcoreFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TransformationTrace.
 */
class TransformationTraceTest {

    private ElementResolutionCache cache;
    private TransformationTrace trace;
    private EClass sourceElement;
    private EClass targetElement;

    @BeforeEach
    void setUp() {
        cache = new ElementResolutionCache();
        trace = new TransformationTrace(cache);
        
        sourceElement = EcoreFactory.eINSTANCE.createEClass();
        sourceElement.setName("SourceEntity");
        
        targetElement = EcoreFactory.eINSTANCE.createEClass();
        targetElement.setName("TargetTable");
    }

    @Test
    void testGetEntriesEmpty() {
        Collection<ElementResolutionCache.TraceEntry> entries = trace.getEntries();
        
        assertNotNull(entries);
        assertTrue(entries.isEmpty());
    }

    @Test
    void testGetEntriesWithMappings() {
        cache.addMapping(sourceElement, "Entity2Table", targetElement, true);
        
        Collection<ElementResolutionCache.TraceEntry> entries = trace.getEntries();
        
        assertEquals(1, entries.size());
    }

    @Test
    void testToJsonContainsRequiredFields() {
        cache.addMapping(sourceElement, "Entity2Table", targetElement, true);
        
        String json = trace.toJson();
        
        assertNotNull(json);
        assertTrue(json.contains("\"traceEntries\""));
        assertTrue(json.contains("\"entryCount\""));
        assertTrue(json.contains("\"timestamp\""));
        assertTrue(json.contains("\"ruleName\""));
        assertTrue(json.contains("\"source\""));
        assertTrue(json.contains("\"target\""));
        assertTrue(json.contains("\"primary\""));
    }

    @Test
    void testToJsonContainsElementInfo() {
        cache.addMapping(sourceElement, "Entity2Table", targetElement, true);
        
        String json = trace.toJson();
        
        // Should contain type information
        assertTrue(json.contains("\"type\""));
        assertTrue(json.contains("EClass"));
        
        // Should contain name if available
        assertTrue(json.contains("\"name\""));
        assertTrue(json.contains("SourceEntity"));
        assertTrue(json.contains("TargetTable"));
    }

    @Test
    void testToJsonWithDiscriminator() {
        cache.addDiscriminatedMapping(sourceElement, targetElement, "Relation2Op", "create");
        
        String json = trace.toJson();
        
        assertTrue(json.contains("\"discriminator\""));
        assertTrue(json.contains("create"));
    }

    @Test
    void testSaveToJsonFile(@TempDir Path tempDir) throws IOException {
        cache.addMapping(sourceElement, "Entity2Table", targetElement, true);
        
        File outputFile = tempDir.resolve("trace.json").toFile();
        trace.saveToJson(outputFile);
        
        assertTrue(outputFile.exists());
        
        String content = Files.readString(outputFile.toPath());
        assertTrue(content.contains("\"traceEntries\""));
        assertTrue(content.contains("Entity2Table"));
    }

    @Test
    void testJsonIsPrettyPrinted() {
        cache.addMapping(sourceElement, "Entity2Table", targetElement, true);
        
        String json = trace.toJson();
        
        // Pretty printed JSON should contain newlines
        assertTrue(json.contains("\n"));
    }

    @Test
    void testMultipleEntries() {
        EClass source2 = EcoreFactory.eINSTANCE.createEClass();
        source2.setName("Source2");
        EClass target2 = EcoreFactory.eINSTANCE.createEClass();
        target2.setName("Target2");
        
        cache.addMapping(sourceElement, "Rule1", targetElement, true);
        cache.addMapping(source2, "Rule2", target2, false);
        
        String json = trace.toJson();
        
        assertTrue(json.contains("\"entryCount\": 2"));
    }
}
