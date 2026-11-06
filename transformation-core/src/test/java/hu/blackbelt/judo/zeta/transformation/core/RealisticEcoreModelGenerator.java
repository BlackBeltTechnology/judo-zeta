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
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License, v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception which is
 * available at https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 * #L%
 */

import lombok.Builder;
import lombok.Getter;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;

import java.util.*;

/**
 * Generates realistic Ecore models with configurable structure for transformation testing.
 *
 * <p>Creates models with:</p>
 * <ul>
 *   <li>Multiple packages with cross-references</li>
 *   <li>Class hierarchies with configurable inheritance depth</li>
 *   <li>Mixed attribute types (String, Integer, Boolean, Date, Double)</li>
 *   <li>Various reference cardinalities (single, collection)</li>
 *   <li>Containment and association references</li>
 *   <li>Bidirectional reference pairs</li>
 * </ul>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * // Generate a medium-sized model
 * ResourceSet rs = RealisticEcoreModelGenerator.builder()
 *     .packageCount(3)
 *     .classesPerPackage(20)
 *     .attributesPerClass(5)
 *     .referencesPerClass(3)
 *     .inheritanceDepth(3)
 *     .seed(42L)
 *     .build()
 *     .generate();
 *
 * // Generate scaled model for performance testing
 * ResourceSet rs = RealisticEcoreModelGenerator.scaled(500);
 * }</pre>
 */
public class RealisticEcoreModelGenerator {

    /**
     * Configuration for model generation.
     */
    @Builder
    @Getter
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class Config {
        /** Number of EPackages to generate */
        @Builder.Default
        private int packageCount = 3;
        /** Number of EClasses per package */
        @Builder.Default
        private int classesPerPackage = 20;
        /** Number of EAttributes per class */
        @Builder.Default
        private int attributesPerClass = 5;
        /** Number of EReferences per class */
        @Builder.Default
        private int referencesPerClass = 3;
        /** Number of EOperations per class */
        @Builder.Default
        private int operationsPerClass = 2;
        /** Maximum inheritance depth (1 = no inheritance) */
        @Builder.Default
        private int inheritanceDepth = 3;
        /** Ratio of abstract classes (0.0 - 1.0) */
        @Builder.Default
        private double abstractRatio = 0.1;
        /** Ratio of bidirectional references (0.0 - 1.0) */
        @Builder.Default
        private double bidirectionalRatio = 0.3;
        /** Ratio of containment references (0.0 - 1.0) */
        @Builder.Default
        private double containmentRatio = 0.3;
        /** Random seed for reproducibility */
        @Builder.Default
        private long seed = System.currentTimeMillis();

        /**
         * Creates a default configuration with sensible defaults.
         */
        public static Config defaults() {
            return Config.builder()
                    .packageCount(3)
                    .classesPerPackage(20)
                    .attributesPerClass(5)
                    .referencesPerClass(3)
                    .operationsPerClass(2)
                    .inheritanceDepth(3)
                    .abstractRatio(0.1)
                    .bidirectionalRatio(0.3)
                    .containmentRatio(0.3)
                    .seed(System.currentTimeMillis())
                    .build();
        }

        /**
         * Creates a scaled configuration targeting approximately the given total element count.
         *
         * @param targetElements approximate number of total elements to generate
         * @return configuration scaled to produce approximately targetElements
         */
        public static Config scaled(int targetElements) {
            // Estimate: packages + classes + attrs + refs + ops
            // For simplicity, use 1 package, calculate classes based on target
            int elementsPerClass = 1 + 5 + 3 + 2; // class + attrs + refs + ops (approx)
            int classes = Math.max(10, targetElements / elementsPerClass);

            return Config.builder()
                    .packageCount(1)
                    .classesPerPackage(classes)
                    .attributesPerClass(5)
                    .referencesPerClass(3)
                    .operationsPerClass(2)
                    .inheritanceDepth(3)
                    .abstractRatio(0.1)
                    .bidirectionalRatio(0.3)
                    .containmentRatio(0.3)
                    .seed(42L) // Fixed seed for reproducibility
                    .build();
        }
    }

    private final Config config;
    private final Random random;
    private final ResourceSet resourceSet;
    private final List<EPackage> packages = new ArrayList<>();
    private final List<EClass> allClasses = new ArrayList<>();

    // Statistics
    @Getter
    private int totalClasses;
    @Getter
    private int totalAttributes;
    @Getter
    private int totalReferences;
    @Getter
    private int totalOperations;
    @Getter
    private int abstractClassCount;

    /**
     * Creates a generator with the given configuration.
     */
    public RealisticEcoreModelGenerator(Config config) {
        this.config = config;
        this.random = new Random(config.getSeed());
        this.resourceSet = new ResourceSetImpl();

        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap()
                .put("*", new XMIResourceFactoryImpl());
    }

    /**
     * Creates a generator with default configuration.
     */
    public RealisticEcoreModelGenerator() {
        this(Config.defaults());
    }

    /**
     * Generates the model and returns the ResourceSet containing all generated elements.
     */
    public ResourceSet generate() {
        // Reset state
        packages.clear();
        allClasses.clear();
        totalClasses = 0;
        totalAttributes = 0;
        totalReferences = 0;
        totalOperations = 0;
        abstractClassCount = 0;

        // Phase 1: Generate packages
        for (int p = 0; p < config.getPackageCount(); p++) {
            EPackage pkg = createPackage(p);
            packages.add(pkg);
            resourceSet.getResources().add(createResource(pkg));
        }

        // Phase 2: Generate classes in each package
        for (EPackage pkg : packages) {
            for (int c = 0; c < config.getClassesPerPackage(); c++) {
                EClass eClass = createClass(pkg, c);
                pkg.getEClassifiers().add(eClass);
                allClasses.add(eClass);
                totalClasses++;

                if (eClass.isAbstract()) {
                    abstractClassCount++;
                }
            }
        }

        // Phase 3: Establish inheritance hierarchy
        establishInheritance();

        // Phase 4: Add attributes to classes
        for (EClass eClass : allClasses) {
            addAttributes(eClass);
        }

        // Phase 5: Add references between classes
        for (EClass eClass : allClasses) {
            addReferences(eClass);
        }

        // Phase 6: Add operations to classes
        for (EClass eClass : allClasses) {
            addOperations(eClass);
        }

        // Phase 7: Validate the model
        validateModel();

        return resourceSet;
    }

    /**
     * Returns statistics about the generated model.
     */
    public Map<String, Integer> getStatistics() {
        Map<String, Integer> stats = new LinkedHashMap<>();
        stats.put("packageCount", packages.size());
        stats.put("classCount", totalClasses);
        stats.put("attributeCount", totalAttributes);
        stats.put("referenceCount", totalReferences);
        stats.put("operationCount", totalOperations);
        stats.put("abstractClassCount", abstractClassCount);
        stats.put("totalElements",
                packages.size() + totalClasses + totalAttributes + totalReferences + totalOperations);
        return stats;
    }

    // ==================== Internal Generation Methods ====================

    private EPackage createPackage(int index) {
        EPackage pkg = EcoreFactory.eINSTANCE.createEPackage();
        pkg.setName("pkg" + index);
        pkg.setNsURI("http://test.example.com/pkg" + index);
        pkg.setNsPrefix("pkg" + index);
        return pkg;
    }

    private Resource createResource(EPackage pkg) {
        Resource resource = resourceSet.createResource(
                URI.createURI("test://generated/" + pkg.getName() + ".xmi"));
        resource.getContents().add(pkg);
        return resource;
    }

    private EClass createClass(EPackage pkg, int index) {
        EClass eClass = EcoreFactory.eINSTANCE.createEClass();
        eClass.setName(pkg.getName() + "_Class" + index);

        // Determine if abstract
        if (random.nextDouble() < config.getAbstractRatio()) {
            eClass.setAbstract(true);
        }

        return eClass;
    }

    private void establishInheritance() {
        if (config.getInheritanceDepth() <= 1) {
            return; // No inheritance
        }

        for (EClass eClass : allClasses) {
            // Skip if already has supertypes (unlikely at this stage)
            if (!eClass.getESuperTypes().isEmpty()) {
                continue;
            }

            // Determine inheritance depth for this class
            int depth = random.nextInt(config.getInheritanceDepth());

            if (depth > 0) {
                // Find potential supertypes from same package or earlier packages
                List<EClass> potentialSupertypes = new ArrayList<>();
                for (EClass other : allClasses) {
                    if (other != eClass && other.isAbstract()) {
                        potentialSupertypes.add(other);
                    }
                }

                // Add supertypes up to the determined depth
                int added = 0;
                Collections.shuffle(potentialSupertypes, random);
                for (EClass supertype : potentialSupertypes) {
                    if (added >= depth) break;
                    if (!wouldCreateCycle(eClass, supertype)) {
                        eClass.getESuperTypes().add(supertype);
                        added++;
                    }
                }
            }
        }
    }

    private boolean wouldCreateCycle(EClass child, EClass potentialParent) {
        // Check if potentialParent is already a descendant of child
        Set<EClass> visited = new HashSet<>();
        Queue<EClass> queue = new LinkedList<>();
        queue.add(potentialParent);

        while (!queue.isEmpty()) {
            EClass current = queue.poll();
            if (current == child) {
                return true;
            }
            if (visited.add(current)) {
                queue.addAll(current.getESuperTypes());
            }
        }
        return false;
    }

    private void addAttributes(EClass eClass) {
        for (int a = 0; a < config.getAttributesPerClass(); a++) {
            EAttribute attr = EcoreFactory.eINSTANCE.createEAttribute();
            attr.setName(eClass.getName() + "_attr" + a);
            attr.setEType(selectAttributeType());
            attr.setLowerBound(random.nextBoolean() ? 0 : 1);
            attr.setUpperBound(1);
            eClass.getEStructuralFeatures().add(attr);
            totalAttributes++;
        }
    }

    private EDataType selectAttributeType() {
        double roll = random.nextDouble();
        if (roll < 0.40) {
            return EcorePackage.eINSTANCE.getEString();      // 40%
        } else if (roll < 0.60) {
            return EcorePackage.eINSTANCE.getEInt();         // 20%
        } else if (roll < 0.75) {
            return EcorePackage.eINSTANCE.getEBoolean();     // 15%
        } else if (roll < 0.90) {
            return EcorePackage.eINSTANCE.getEDate();        // 15%
        } else {
            return EcorePackage.eINSTANCE.getEDouble();      // 10%
        }
    }

    private void addReferences(EClass eClass) {
        // Track created references for bidirectional pairing
        Map<String, EReference> createdRefs = new HashMap<>();

        for (int r = 0; r < config.getReferencesPerClass(); r++) {
            // Select a target class (can be same class for self-reference)
            EClass targetClass = selectTargetClass(eClass);

            EReference ref = EcoreFactory.eINSTANCE.createEReference();
            ref.setName(eClass.getName() + "_ref" + r);
            ref.setEType(targetClass);

            // Set cardinality
            setReferenceCardinality(ref);

            // Set containment
            ref.setContainment(random.nextDouble() < config.getContainmentRatio());

            eClass.getEStructuralFeatures().add(ref);
            totalReferences++;

            // Handle bidirectional references
            if (random.nextDouble() < config.getBidirectionalRatio()) {
                createOppositeReference(eClass, targetClass, ref);
            }
        }
    }

    private EClass selectTargetClass(EClass sourceClass) {
        // 20% chance of self-reference
        if (random.nextDouble() < 0.2) {
            return sourceClass;
        }

        // 40% chance of cross-package reference (if multiple packages)
        if (packages.size() > 1 && random.nextDouble() < 0.4) {
            EPackage otherPkg = packages.get(random.nextInt(packages.size()));
            if (!otherPkg.getEClassifiers().isEmpty()) {
                return (EClass) otherPkg.getEClassifiers()
                        .get(random.nextInt(otherPkg.getEClassifiers().size()));
            }
        }

        // Same package reference
        EPackage pkg = (EPackage) sourceClass.eContainer();
        if (pkg != null && !pkg.getEClassifiers().isEmpty()) {
            return (EClass) pkg.getEClassifiers()
                    .get(random.nextInt(pkg.getEClassifiers().size()));
        }

        return allClasses.get(random.nextInt(allClasses.size()));
    }

    private void setReferenceCardinality(EReference ref) {
        double roll = random.nextDouble();
        if (roll < 0.40) {
            // Single-valued (40%)
            ref.setLowerBound(random.nextBoolean() ? 0 : 1);
            ref.setUpperBound(1);
        } else if (roll < 0.70) {
            // Optional collection (30%)
            ref.setLowerBound(0);
            ref.setUpperBound(-1);
        } else {
            // Required collection (30%)
            ref.setLowerBound(random.nextInt(3) + 1);
            ref.setUpperBound(-1);
        }
    }

    private void createOppositeReference(EClass sourceClass, EClass targetClass, EReference sourceRef) {
        // Only create opposite if target is different (avoid self-referencing opposites)
        if (sourceClass == targetClass) {
            return;
        }

        EReference oppositeRef = EcoreFactory.eINSTANCE.createEReference();
        oppositeRef.setName(targetClass.getName() + "_oppositeOf_" + sourceRef.getName());
        oppositeRef.setEType(sourceClass);
        oppositeRef.setLowerBound(0);
        oppositeRef.setUpperBound(sourceRef.getUpperBound() == 1 ? 1 : -1);
        oppositeRef.setContainment(false); // Opposite of containment is non-containment

        // Link the references
        sourceRef.setEOpposite(oppositeRef);
        oppositeRef.setEOpposite(sourceRef);

        targetClass.getEStructuralFeatures().add(oppositeRef);
        totalReferences++;
    }

    private void addOperations(EClass eClass) {
        for (int o = 0; o < config.getOperationsPerClass(); o++) {
            EOperation op = EcoreFactory.eINSTANCE.createEOperation();
            op.setName(eClass.getName() + "_op" + o);
            op.setEType(selectReturnType());
            eClass.getEOperations().add(op);
            totalOperations++;
        }
    }

    private EClassifier selectReturnType() {
        double roll = random.nextDouble();
        if (roll < 0.30) {
            return EcorePackage.eINSTANCE.getEString();
        } else if (roll < 0.50) {
            return EcorePackage.eINSTANCE.getEInt();
        } else if (roll < 0.60) {
            return EcorePackage.eINSTANCE.getEBoolean();
        } else if (roll < 0.70) {
            return EcorePackage.eINSTANCE.getEDouble();
        } else if (!allClasses.isEmpty()) {
            // Return a generated class
            return allClasses.get(random.nextInt(allClasses.size()));
        } else {
            return EcorePackage.eINSTANCE.getEString();
        }
    }

    private void validateModel() {
        for (EPackage pkg : packages) {
            // Basic validation: ensure no null names
            assertNotNull(pkg.getName(), "Package name should not be null");

            for (EClassifier classifier : pkg.getEClassifiers()) {
                assertNotNull(classifier.getName(), "Classifier name should not be null");

                if (classifier instanceof EClass) {
                    EClass eClass = (EClass) classifier;
                    for (EStructuralFeature feature : eClass.getEStructuralFeatures()) {
                        assertNotNull(feature.getName(), "Feature name should not be null");
                        assertNotNull(feature.getEType(), "Feature type should not be null");
                    }
                }
            }
        }
    }

    private void assertNotNull(Object obj, String message) {
        if (obj == null) {
            throw new IllegalStateException(message);
        }
    }

    // ==================== Static Factory Methods ====================

    /**
     * Creates a generator with default configuration.
     */
    public static RealisticEcoreModelGenerator createDefault() {
        return new RealisticEcoreModelGenerator(Config.defaults());
    }

    /**
     * Creates a generator targeting approximately the given element count.
     *
     * @param targetElements approximate total elements to generate
     * @return generator configured for the target size
     */
    public static RealisticEcoreModelGenerator scaled(int targetElements) {
        return new RealisticEcoreModelGenerator(Config.scaled(targetElements));
    }

    /**
     * Builder pattern entry point.
     */
    public static Config.ConfigBuilder builder() {
        return Config.builder();
    }
}
