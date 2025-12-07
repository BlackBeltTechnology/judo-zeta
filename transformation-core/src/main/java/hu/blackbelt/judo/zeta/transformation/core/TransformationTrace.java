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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Transformation trace for debugging and analysis.
 *
 * <p>Collects source-to-target mappings and can export to JSON format.</p>
 */
public class TransformationTrace {

    private final ElementResolutionCache cache;

    public TransformationTrace(ElementResolutionCache cache) {
        this.cache = cache;
    }

    /**
     * Get all trace entries.
     */
    public Collection<ElementResolutionCache.TraceEntry> getEntries() {
        return cache.getAllMappings();
    }

    /**
     * Save the trace to a JSON file.
     *
     * @param file the output file
     * @throws IOException if writing fails
     */
    public void saveToJson(File file) throws IOException {
        try (Writer writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8))) {
            saveToJson(writer);
        }
    }

    /**
     * Save the trace to a JSON writer.
     *
     * @param writer the output writer
     */
    public void saveToJson(Writer writer) {
        List<Map<String, Object>> traceData = new ArrayList<>();

        for (ElementResolutionCache.TraceEntry entry : cache.getAllMappings()) {
            Map<String, Object> entryMap = new LinkedHashMap<>();
            entryMap.put("ruleName", entry.getRuleName());
            entryMap.put("source", toElementInfo(entry.getSource()));
            entryMap.put("target", toElementInfo(entry.getTarget()));
            entryMap.put("primary", entry.isPrimary());

            if (entry.getDiscriminator() != null) {
                entryMap.put("discriminator", entry.getDiscriminator());
            }

            traceData.add(entryMap);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("traceEntries", traceData);
        root.put("entryCount", traceData.size());
        root.put("timestamp", System.currentTimeMillis());

        Gson gson = new GsonBuilder()
                .setPrettyPrinting()
                .create();
        gson.toJson(root, writer);
    }

    /**
     * Get the trace as a JSON string.
     */
    public String toJson() {
        StringWriter writer = new StringWriter();
        saveToJson(writer);
        return writer.toString();
    }

    private Map<String, Object> toElementInfo(EObject element) {
        Map<String, Object> info = new LinkedHashMap<>();

        info.put("type", element.eClass().getName());
        info.put("id", getElementId(element));

        // Try to get name if available
        EStructuralFeature nameFeature = element.eClass().getEStructuralFeature("name");
        if (nameFeature != null) {
            Object name = element.eGet(nameFeature);
            if (name != null) {
                info.put("name", name.toString());
            }
        }

        return info;
    }

    private String getElementId(EObject element) {
        Resource resource = element.eResource();
        if (resource != null) {
            String fragment = resource.getURIFragment(element);
            if (fragment != null && !fragment.startsWith("/")) {
                return fragment;
            }
        }

        // Try id attribute
        EStructuralFeature idFeature = element.eClass().getEStructuralFeature("id");
        if (idFeature != null) {
            Object id = element.eGet(idFeature);
            if (id != null) {
                return id.toString();
            }
        }

        // Fallback to identity hash
        return "obj@" + Integer.toHexString(System.identityHashCode(element));
    }
}
