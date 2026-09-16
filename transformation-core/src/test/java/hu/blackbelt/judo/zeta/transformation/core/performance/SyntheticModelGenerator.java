package hu.blackbelt.judo.zeta.transformation.core.performance;

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
 * Licenses when the conditions for this availability set forth in the
 * Eclipse Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * with the GNU Classpath Exception which is
 * available at https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 * #L%
 */

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Generator for synthetic PSM-style models with configurable complexity.
 *
 * <p>Creates EMF models with packages, classes, attributes, references, and operations
 * to simulate realistic transformation workloads. Uses a fixed random seed for
 * reproducible model generation.</p>
 *
 * <p>Builder pattern configuration:</p>
 * <pre>{@code
 * ResourceSet model = new SyntheticModelGenerator.Builder()
 *     .packageCount(5)
 *     .classesPerPackage(20)
 *     .attributesPerClass(10)
 *     .referencesPerClass(3)
 *     .operationsPerClass(2)
 *     .randomSeed(42L)
 *     .build()
 *     .generate();
 * }</pre>
 */
public class SyntheticModelGenerator {

    private final Config config;
    private final Random random;
    private final ResourceSet resourceSet;

    private SyntheticModelGenerator(Config config) {
        this.config = config;
        this.random = new Random(config.randomSeed);
        this.resourceSet = new ResourceSetImpl();
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("*", new XMIResourceFactoryImpl());
    }

    /**
     * Generate a synthetic model based on the configuration.
     *
     * @return the ResourceSet containing the generated model
     */
    public ResourceSet generate() {
        Resource resource = resourceSet.createResource(URI.createURI("synthetic://model.xmi"));

        List<EPackage> packages = new ArrayList<>();
        List<EClass> allClasses = new ArrayList<>();

        // Generate packages
        for (int i = 0; i < config.packageCount; i++) {
            EPackage pkg = EcoreFactory.eINSTANCE.createEPackage();
            pkg.setName("pkg" + i);
            pkg.setNsURI("http://synthetic/" + i);
            packages.add(pkg);
            resource.getContents().add(pkg);
        }

        // Distribute classes across packages
        int classIndex = 0;
        for (EPackage pkg : packages) {
            int classesInThisPackage = config.classesPerPackage;
            if (config.typeDistribution != null && !config.typeDistribution.isEmpty()) {
                classesInThisPackage = distributeByTypeDistribution(classesInThisPackage);
            }

            for (int i = 0; i < classesInThisPackage; i++) {
                EClass clazz = generateClass(classIndex++, pkg);
                pkg.getEClassifiers().add(clazz);
                allClasses.add(clazz);
            }
        }

        // Add attributes, references, operations to each class
        for (EClass clazz : allClasses) {
            addAttributes(clazz);
            addReferences(clazz, allClasses);
            addOperations(clazz, allClasses);
        }

        // Add guard markers if configured
        if (config.guardRejectionRate > 0) {
            addGuardMarkers(allClasses);
        }

        return resourceSet;
    }

    private EClass generateClass(int index, EPackage pkg) {
        EClass clazz = EcoreFactory.eINSTANCE.createEClass();
        clazz.setName("Class" + index);
        return clazz;
    }

    private void addAttributes(EClass clazz) {
        for (int i = 0; i < config.attributesPerClass; i++) {
            EAttribute attr = EcoreFactory.eINSTANCE.createEAttribute();
            attr.setName(clazz.getName() + "_attr" + i);
            attr.setEType(EcorePackage.Literals.ESTRING); // Default type

            // Assign type from shared types if configured
            if (config.sharedTypes != null && !config.sharedTypes.isEmpty()) {
                EDataType type = config.sharedTypes.get(random.nextInt(config.sharedTypes.size()));
                attr.setEType(type);
            }

            clazz.getEStructuralFeatures().add(attr);
        }
    }

    private void addReferences(EClass clazz, List<EClass> allClasses) {
        for (int i = 0; i < config.referencesPerClass; i++) {
            EReference ref = EcoreFactory.eINSTANCE.createEReference();
            ref.setName(clazz.getName() + "_ref" + i);

            // Select target based on pattern
            EClass target = selectTargetClass(clazz, allClasses, config.referencePattern);
            if (target != null) {
                ref.setEType(target);
                ref.setContainment(config.referencePattern == ReferencePattern.HIERARCHICAL && i == 0);
            }

            clazz.getEStructuralFeatures().add(ref);
        }
    }

    private void addOperations(EClass clazz, List<EClass> allClasses) {
        for (int i = 0; i < config.operationsPerClass; i++) {
            EOperation op = EcoreFactory.eINSTANCE.createEOperation();
            op.setName(clazz.getName() + "_op" + i);
            clazz.getEOperations().add(op);
        }
    }

    private void addGuardMarkers(List<EClass> allClasses) {
        // Add EAnnotation as guard marker for configured percentage of classes
        int guardCount = (int) (allClasses.size() * config.guardRejectionRate);
        List<EClass> shuffled = new ArrayList<>(allClasses);
        Collections.shuffle(shuffled, random);

        for (int i = 0; i < guardCount; i++) {
            EAnnotation annotation = EcoreFactory.eINSTANCE.createEAnnotation();
            annotation.setSource("guard-marker");
            annotation.getDetails().put("reject", "true");
            shuffled.get(i).getEAnnotations().add(annotation);
        }
    }

    private EClass selectTargetClass(EClass source, List<EClass> allClasses, ReferencePattern pattern) {
        if (allClasses.isEmpty()) return null;

        switch (pattern) {
            case RANDOM:
                return allClasses.get(random.nextInt(allClasses.size()));
            case HIERARCHICAL:
                // Prefer classes with lower index (parent-first)
                int maxIndex = allClasses.indexOf(source);
                if (maxIndex <= 0) return null;
                return allClasses.get(random.nextInt(maxIndex));
            case CIRCULAR:
                // Create mutual references between pairs
                int pairIndex = (allClasses.indexOf(source) + 1) % allClasses.size();
                return allClasses.get(pairIndex);
            default:
                return allClasses.get(random.nextInt(allClasses.size()));
        }
    }

    private int distributeByTypeDistribution(int total) {
        // Simplified: return total for now
        // Full implementation would split by entity/transferObject/enum ratios
        return total;
    }

    /**
     * Get the generated ResourceSet.
     */
    public ResourceSet getResourceSet() {
        return resourceSet;
    }

    /**
     * Get the total number of generated elements.
     */
    public int getTotalElementCount() {
        int total = config.packageCount;
        total += config.packageCount * config.classesPerPackage;
        total += config.packageCount * config.classesPerPackage * config.attributesPerClass;
        total += config.packageCount * config.classesPerPackage * config.referencesPerClass;
        total += config.packageCount * config.classesPerPackage * config.operationsPerClass;
        return total;
    }

    // ==================== Builder ====================

    /**
     * Builder for configuring the synthetic model generator.
     */
    public static class Builder {
        private final Config config = new Config();

        public Builder() {
            // Defaults
            config.packageCount = 1;
            config.classesPerPackage = 10;
            config.attributesPerClass = 5;
            config.referencesPerClass = 2;
            config.operationsPerClass = 1;
            config.randomSeed = 42L;
            config.guardRejectionRate = 0.0;
            config.referencePattern = ReferencePattern.RANDOM;
        }

        public Builder packageCount(int count) {
            config.packageCount = count;
            return this;
        }

        public Builder classesPerPackage(int count) {
            config.classesPerPackage = count;
            return this;
        }

        public Builder attributesPerClass(int count) {
            config.attributesPerClass = count;
            return this;
        }

        public Builder referencesPerClass(int count) {
            config.referencesPerClass = count;
            return this;
        }

        public Builder operationsPerClass(int count) {
            config.operationsPerClass = count;
            return this;
        }

        public Builder randomSeed(long seed) {
            config.randomSeed = seed;
            return this;
        }

        public Builder guardRejectionRate(double rate) {
            config.guardRejectionRate = Math.max(0.0, Math.min(1.0, rate));
            return this;
        }

        public Builder referencePattern(ReferencePattern pattern) {
            config.referencePattern = pattern;
            return this;
        }

        public Builder sharedTypes(List<EDataType> types) {
            config.sharedTypes = types;
            return this;
        }

        public Builder typeDistribution(Map<String, Integer> distribution) {
            config.typeDistribution = distribution;
            return this;
        }

        /**
         * Build the generator instance.
         */
        public SyntheticModelGenerator build() {
            return new SyntheticModelGenerator(config);
        }
    }

    // ==================== Configuration ====================

    private static class Config {
        int packageCount;
        int classesPerPackage;
        int attributesPerClass;
        int referencesPerClass;
        int operationsPerClass;
        long randomSeed;
        double guardRejectionRate;
        ReferencePattern referencePattern;
        List<EDataType> sharedTypes;
        Map<String, Integer> typeDistribution;
    }

    /**
     * Reference generation patterns.
     */
    public enum ReferencePattern {
        /** Random cross-references between any classes */
        RANDOM,
        /** Parent-child containment relationships (hierarchical) */
        HIERARCHICAL,
        /** Mutual references between class pairs */
        CIRCULAR
    }
}
