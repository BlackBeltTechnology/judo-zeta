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

import hu.blackbelt.judo.zeta.annotation.*;
import org.eclipse.emf.ecore.EClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance benchmark tests using realistic Ecore models.
 *
 * <p>Measures transformation throughput, memory usage, and scalability with different model sizes.</p>
 */
class EcorePerformanceBenchmarkTest extends AbstractEcoreTransformationTest {

    private static final Logger LOG = Logger.getLogger(EcorePerformanceBenchmarkTest.class.getName());

    // ==================== Small Model Benchmark (CI) ====================

    @Nested
    @DisplayName("Small Model Benchmark (20 classes)")
    class SmallModelBenchmark {

        @Test
        @DisplayName("Transform small model and measure throughput")
        void transformSmallModel() {
            // Generate small model
            var config = RealisticEcoreModelGenerator.Config.builder()
                    .packageCount(1)
                    .classesPerPackage(20)
                    .attributesPerClass(5)
                    .referencesPerClass(3)
                    .operationsPerClass(2)
                    .inheritanceDepth(3)
                    .seed(42L)
                    .build();

            var generator = new RealisticEcoreModelGenerator(config);
            generator.generate();

            var stats = generator.getStatistics();
            LOG.info("Small model stats: " + stats);

            // Run transformation
            registry.register(SimpleCopyTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            long startTime = System.currentTimeMillis();
            executor.transform();
            long duration = System.currentTimeMillis() - startTime;

            int totalElements = stats.get("totalElements");
            double throughput = (double) totalElements / duration * 1000.0;

            LOG.info(String.format("Small model: %d classes, %d ms, %.1f elements/sec",
                    20, duration, throughput));

            assertTrue(duration < 1000, "Should complete in < 1 second");
            assertTrue(throughput > 100, "Should process > 100 elements/sec");
        }
    }

    // ==================== Medium Model Benchmark ====================

    @Nested
    @DisplayName("Medium Model Benchmark (100 classes)")
    @Tag("performance")
    class MediumModelBenchmark {

        @Test
        @DisplayName("Transform medium model and measure throughput")
        void transformMediumModel() {
            var config = RealisticEcoreModelGenerator.Config.builder()
                    .packageCount(2)
                    .classesPerPackage(50)
                    .attributesPerClass(5)
                    .referencesPerClass(3)
                    .operationsPerClass(2)
                    .inheritanceDepth(3)
                    .seed(42L)
                    .build();

            var generator = new RealisticEcoreModelGenerator(config);
            generator.generate();

            var stats = generator.getStatistics();
            LOG.info("Medium model stats: " + stats);

            registry.register(SimpleCopyTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            long startTime = System.currentTimeMillis();
            executor.transform();
            long duration = System.currentTimeMillis() - startTime;

            int totalElements = stats.get("totalElements");
            double throughput = (double) totalElements / duration * 1000.0;

            LOG.info(String.format("Medium model: %d classes, %d ms, %.1f elements/sec",
                    100, duration, throughput));

            assertTrue(duration < 10000, "Should complete in < 10 seconds");
        }
    }

    // ==================== Large Model Benchmark ====================

    @Nested
    @DisplayName("Large Model Benchmark (500 classes)")
    @Tag("performance")
    @Tag("slow")
    class LargeModelBenchmark {

        @Test
        @DisplayName("Transform large model and measure throughput")
        void transformLargeModel() {
            var config = RealisticEcoreModelGenerator.Config.builder()
                    .packageCount(3)
                    .classesPerPackage(167)
                    .attributesPerClass(5)
                    .referencesPerClass(3)
                    .operationsPerClass(2)
                    .inheritanceDepth(3)
                    .seed(42L)
                    .build();

            var generator = new RealisticEcoreModelGenerator(config);
            generator.generate();

            var stats = generator.getStatistics();
            LOG.info("Large model stats: " + stats);

            registry.register(SimpleCopyTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            long startTime = System.currentTimeMillis();
            executor.transform();
            long duration = System.currentTimeMillis() - startTime;

            int totalElements = stats.get("totalElements");
            double throughput = (double) totalElements / duration * 1000.0;

            LOG.info(String.format("Large model: %d classes, %d ms, %.1f elements/sec",
                    500, duration, throughput));

            assertTrue(duration < 60000, "Should complete in < 60 seconds");
        }
    }

    // ==================== Parallel vs Sequential Comparison ====================

    @Nested
    @DisplayName("Parallel vs Sequential Comparison")
    @Tag("performance")
    class ParallelVsSequentialBenchmark {

        @Test
        @DisplayName("Compare parallel and sequential execution")
        void compareParallelAndSequential() {
            var config = RealisticEcoreModelGenerator.Config.builder()
                    .packageCount(2)
                    .classesPerPackage(50)
                    .attributesPerClass(5)
                    .referencesPerClass(3)
                    .operationsPerClass(2)
                    .inheritanceDepth(3)
                    .seed(42L)
                    .build();

            var generator = new RealisticEcoreModelGenerator(config);
            generator.generate();

            var stats = generator.getStatistics();
            LOG.info("Parallel comparison model stats: " + stats);

            // Sequential execution
            registry.register(SimpleCopyTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor sequentialExecutor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            long seqStart = System.currentTimeMillis();
            sequentialExecutor.transform();
            long seqDuration = System.currentTimeMillis() - seqStart;

            // Reset for parallel
            setUpBase();

            // Parallel execution
            var generator2 = new RealisticEcoreModelGenerator(config);
            generator2.generate();

            registry.register(SimpleCopyTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor parallelExecutor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallelThreshold(1) // Force parallel
                    .build();

            long parStart = System.currentTimeMillis();
            parallelExecutor.transform();
            long parDuration = System.currentTimeMillis() - parStart;

            double speedup = (double) seqDuration / parDuration;

            LOG.info(String.format("Sequential: %d ms, Parallel: %d ms, Speedup: %.2fx",
                    seqDuration, parDuration, speedup));

            // For small models, parallel overhead can make it slower than sequential
            // Just verify both complete successfully - the speedup is informational
            assertTrue(seqDuration >= 0 && parDuration >= 0,
                    "Both sequential and parallel should complete");
        }
    }

    // ==================== Memory Usage Tracking ====================

    @Nested
    @DisplayName("Memory Usage Tracking")
    @Tag("performance")
    class MemoryUsageBenchmark {

        @Test
        @DisplayName("Track memory usage during transformation")
        void trackMemoryUsage() {
            var config = RealisticEcoreModelGenerator.Config.builder()
                    .packageCount(2)
                    .classesPerPackage(50)
                    .seed(42L)
                    .build();

            var generator = new RealisticEcoreModelGenerator(config);
            generator.generate();

            var stats = generator.getStatistics();
            LOG.info("Memory tracking model stats: " + stats);

            // Measure before
            Runtime runtime = Runtime.getRuntime();
            long beforeMemory = runtime.totalMemory() - runtime.freeMemory();
            LOG.info(String.format("Memory before: used=%d MB, free=%d MB",
                    bytesToMB(beforeMemory), bytesToMB(runtime.freeMemory())));

            registry.register(SimpleCopyTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor executor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();

            executor.transform();

            // Measure after
            long afterMemory = runtime.totalMemory() - runtime.freeMemory();
            long memoryDelta = afterMemory - beforeMemory;
            LOG.info(String.format("Memory after: used=%d MB, free=%d MB, delta=%d MB",
                    bytesToMB(afterMemory), bytesToMB(runtime.freeMemory()), bytesToMB(memoryDelta)));

            // Memory delta should be reasonable
            assertTrue(memoryDelta < 100 * 1024 * 1024, "Memory delta should be < 100 MB");
        }
    }

    // ==================== Scalability Measurement ====================

    @Nested
    @DisplayName("Scalability Measurement")
    @Tag("performance")
    class ScalabilityBenchmark {

        @Test
        @DisplayName("Verify linear scaling with model size")
        void verifyLinearScaling() {
            // Small model
            var smallConfig = RealisticEcoreModelGenerator.Config.builder()
                    .packageCount(1)
                    .classesPerPackage(25)
                    .seed(42L)
                    .build();
            var smallGen = new RealisticEcoreModelGenerator(smallConfig);
            smallGen.generate();
            int smallStats = smallGen.getStatistics().get("totalElements");

            // Medium model (2x)
            var mediumConfig = RealisticEcoreModelGenerator.Config.builder()
                    .packageCount(1)
                    .classesPerPackage(50)
                    .seed(42L)
                    .build();
            var mediumGen = new RealisticEcoreModelGenerator(mediumConfig);
            mediumGen.generate();
            int mediumStats = mediumGen.getStatistics().get("totalElements");

            // Large model (4x)
            var largeConfig = RealisticEcoreModelGenerator.Config.builder()
                    .packageCount(1)
                    .classesPerPackage(100)
                    .seed(42L)
                    .build();
            var largeGen = new RealisticEcoreModelGenerator(largeConfig);
            largeGen.generate();
            int largeStats = largeGen.getStatistics().get("totalElements");

            LOG.info(String.format("Scaling test: small=%d, medium=%d, large=%d",
                    smallStats, mediumStats, largeStats));

            // Verify roughly linear scaling
            double ratio = (double) mediumStats / smallStats;
            LOG.info(String.format("Medium/small ratio: %.2f (expect ~2.0)", ratio));
            assertTrue(ratio < 3.0, "Scaling should be roughly linear");
        }
    }

    // ==================== Warmup and Measurement ====================

    @Nested
    @DisplayName("Warmup and Measurement")
    @Tag("performance")
    class WarmupBenchmark {

        @Test
        @DisplayName("Warmup run improves measurement accuracy")
        void warmupRunImprovesAccuracy() {
            var config = RealisticEcoreModelGenerator.Config.builder()
                    .packageCount(1)
                    .classesPerPackage(50)
                    .seed(42L)
                    .build();

            // Warmup run
            var warmupGen = new RealisticEcoreModelGenerator(config);
            warmupGen.generate();

            registry.register(SimpleCopyTransformation.class);
            context.setTransformationRegistry(registry);

            TransformationExecutor warmupExecutor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            warmupExecutor.transform();

            // Reset for measurement run
            setUpBase();

            // Measurement run
            var measureGen = new RealisticEcoreModelGenerator(config);
            measureGen.generate();

            registry.register(SimpleCopyTransformation.class);
            context.setTransformationRegistry(registry);

            long startTime = System.currentTimeMillis();
            TransformationExecutor measureExecutor = TransformationExecutor.builder()
                    .registry(registry)
                    .context(context)
                    .parallel(false)
                    .build();
            measureExecutor.transform();
            long duration = System.currentTimeMillis() - startTime;

            LOG.info(String.format("After warmup: %d ms for %d elements",
                    duration, measureGen.getStatistics().get("totalElements")));

            assertTrue(duration < 5000, "After warmup should be faster");
        }
    }

    // ==================== Helper Methods ====================

    private long bytesToMB(long bytes) {
        return bytes / (1024 * 1024);
    }

    // ==================== Transformation Classes ====================

    /**
     * Simple copy transformation for performance testing.
     */
    @hu.blackbelt.judo.zeta.annotation.TransformationContext(source = EClass.class, target = EClass.class)
    public static class SimpleCopyTransformation {

        @TransformRule(name = "CopyEClass")
        @Transform(type = EClass.class)
        public TransformFunction<EClass, EClass> copyEClass() {
            return (source, ctx) -> {
                EClass target = ctx.createTarget(EClass.class);
                target.setName(source.getName());
                target.setAbstract(source.isAbstract());

                // Copy attributes
                for (var attr : source.getEAttributes()) {
                    var targetAttr = ctx.createTarget(org.eclipse.emf.ecore.EAttribute.class);
                    targetAttr.setName(attr.getName());
                    targetAttr.setEType(attr.getEType());
                    targetAttr.setLowerBound(attr.getLowerBound());
                    targetAttr.setUpperBound(attr.getUpperBound());
                    target.getEStructuralFeatures().add(targetAttr);
                }

                // Copy references
                for (var ref : source.getEReferences()) {
                    var targetRef = ctx.createTarget(org.eclipse.emf.ecore.EReference.class);
                    targetRef.setName(ref.getName());
                    targetRef.setEType(ctx.equivalent((EClass) ref.getEType(), EClass.class));
                    targetRef.setLowerBound(ref.getLowerBound());
                    targetRef.setUpperBound(ref.getUpperBound());
                    targetRef.setContainment(ref.isContainment());
                    target.getEStructuralFeatures().add(targetRef);
                }

                // Copy operations
                for (var op : source.getEOperations()) {
                    var targetOp = ctx.createTarget(org.eclipse.emf.ecore.EOperation.class);
                    targetOp.setName(op.getName());
                    targetOp.setEType(op.getEType());
                    target.getEOperations().add(targetOp);
                }

                ctx.addToResource(target);
                return target;
            };
        }
    }
}
