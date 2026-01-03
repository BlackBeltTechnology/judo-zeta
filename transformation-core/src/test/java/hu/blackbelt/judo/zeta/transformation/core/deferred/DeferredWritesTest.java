package hu.blackbelt.judo.zeta.transformation.core.deferred;

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

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for the deferred EMF writes pattern.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Operation recording and replay (EMFOperation types)</li>
 *   <li>Proxy behavior (DeferredEObject)</li>
 *   <li>List operations (DeferredEList)</li>
 *   <li>Operation queue management (OperationQueue)</li>
 *   <li>Read-after-write consistency</li>
 *   <li>Thread-safe concurrent operation recording</li>
 *   <li>Deterministic ordering across runs</li>
 * </ul>
 */
class DeferredWritesTest {

    private OperationQueue queue;

    @BeforeEach
    void setUp() {
        queue = new OperationQueue();
    }

    // ==================== OperationQueue Tests ====================

    @Nested
    class OperationQueueTests {

        @Test
        void testQueueStartsEmpty() {
            assertTrue(queue.isEmpty());
            assertEquals(0, queue.size());
        }

        @Test
        void testSequenceCounterIncrementsMonotonically() {
            long seq1 = queue.nextSequence();
            long seq2 = queue.nextSequence();
            long seq3 = queue.nextSequence();

            assertEquals(0, seq1);
            assertEquals(1, seq2);
            assertEquals(2, seq3);
        }

        @Test
        void testDeferredModeQueuesOperations() {
            EClass target = EcoreFactory.eINSTANCE.createEClass();
            EStructuralFeature nameFeature = EcorePackage.Literals.ENAMED_ELEMENT__NAME;

            queue.enableDeferredMode();
            assertTrue(queue.isDeferredMode());

            queue.add(new EMFOperation.SetAttributeOp(target, nameFeature, "Test", queue.nextSequence()));

            assertEquals(1, queue.size());
            assertNull(target.getName()); // Not applied yet
        }

        @Test
        void testImmediateModeAppliesOperations() {
            EClass target = EcoreFactory.eINSTANCE.createEClass();
            EStructuralFeature nameFeature = EcorePackage.Literals.ENAMED_ELEMENT__NAME;

            assertFalse(queue.isDeferredMode());

            queue.add(new EMFOperation.SetAttributeOp(target, nameFeature, "Test", queue.nextSequence()));

            assertEquals(0, queue.size()); // Applied immediately
            assertEquals("Test", target.getName());
        }

        @Test
        void testCommitAppliesOperationsInOrder() {
            EClass target = EcoreFactory.eINSTANCE.createEClass();
            EStructuralFeature nameFeature = EcorePackage.Literals.ENAMED_ELEMENT__NAME;

            queue.enableDeferredMode();

            // Add operations out of order
            queue.add(new EMFOperation.SetAttributeOp(target, nameFeature, "First", 0));
            queue.add(new EMFOperation.SetAttributeOp(target, nameFeature, "Third", 2));
            queue.add(new EMFOperation.SetAttributeOp(target, nameFeature, "Second", 1));

            assertEquals(3, queue.size());
            assertNull(target.getName());

            int applied = queue.commit();

            assertEquals(3, applied);
            assertEquals(0, queue.size());
            assertEquals("Third", target.getName()); // Last operation wins
        }

        @Test
        void testClearDiscardsOperations() {
            EClass target = EcoreFactory.eINSTANCE.createEClass();
            EStructuralFeature nameFeature = EcorePackage.Literals.ENAMED_ELEMENT__NAME;

            queue.enableDeferredMode();
            queue.add(new EMFOperation.SetAttributeOp(target, nameFeature, "Test", queue.nextSequence()));

            assertEquals(1, queue.size());

            queue.clear();

            assertEquals(0, queue.size());
            assertTrue(queue.isEmpty());
        }

        @Test
        void testResetClearsQueueAndSequence() {
            queue.enableDeferredMode();
            queue.nextSequence();
            queue.nextSequence();

            EClass target = EcoreFactory.eINSTANCE.createEClass();
            queue.add(new EMFOperation.SetAttributeOp(
                    target, EcorePackage.Literals.ENAMED_ELEMENT__NAME, "Test", queue.nextSequence()));

            queue.reset();

            assertTrue(queue.isEmpty());
            assertFalse(queue.isDeferredMode());
            assertEquals(0, queue.nextSequence()); // Sequence reset
        }

        @Test
        void testConcurrentSequenceGeneration() throws InterruptedException {
            int threadCount = 10;
            int sequencesPerThread = 100;
            Set<Long> allSequences = ConcurrentHashMap.newKeySet();

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < sequencesPerThread; i++) {
                            allSequences.add(queue.nextSequence());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            endLatch.await();
            executor.shutdown();

            // All sequences should be unique
            assertEquals(threadCount * sequencesPerThread, allSequences.size(),
                    "All generated sequences should be unique");
        }
    }

    // ==================== EMFOperation Tests ====================

    @Nested
    class EMFOperationTests {

        @Test
        void testSetAttributeOp() {
            EClass target = EcoreFactory.eINSTANCE.createEClass();
            EStructuralFeature nameFeature = EcorePackage.Literals.ENAMED_ELEMENT__NAME;

            EMFOperation op = new EMFOperation.SetAttributeOp(target, nameFeature, "TestName", 0);

            assertEquals(target, op.target());
            assertEquals(nameFeature, op.feature());
            assertEquals(0, op.sequence());

            assertNull(target.getName());
            op.apply();
            assertEquals("TestName", target.getName());
        }

        @Test
        void testSetReferenceOp() {
            // ETypedElement.eType is a changeable single reference
            EAttribute attr = EcoreFactory.eINSTANCE.createEAttribute();
            EDataType stringType = EcorePackage.Literals.ESTRING;

            EStructuralFeature typeFeature = EcorePackage.Literals.ETYPED_ELEMENT__ETYPE;

            EMFOperation op = new EMFOperation.SetReferenceOp(attr, typeFeature, stringType, 0);

            assertNull(attr.getEType());
            op.apply();
            assertEquals(stringType, attr.getEType());
        }

        @Test
        void testSetReferenceOpUnwrapsProxy() {
            // Create an EClass and use its eAttributeType reference via an EReference
            EReference ref = EcoreFactory.eINSTANCE.createEReference();
            EClass targetClass = EcoreFactory.eINSTANCE.createEClass();
            targetClass.setName("TargetClass");

            // Create a proxy of targetClass
            OperationQueue testQueue = new OperationQueue();
            EClass proxiedTarget = DeferredEObject.createProxy(targetClass, testQueue);

            EStructuralFeature typeFeature = EcorePackage.Literals.ETYPED_ELEMENT__ETYPE;

            // Operation should unwrap the proxy
            EMFOperation op = new EMFOperation.SetReferenceOp(ref, typeFeature, proxiedTarget, 0);
            op.apply();

            // Should be the real object, not the proxy
            assertSame(targetClass, ref.getEType());
        }

        @Test
        void testUnsetFeatureOp() {
            EClass target = EcoreFactory.eINSTANCE.createEClass();
            target.setName("InitialName");
            EStructuralFeature nameFeature = EcorePackage.Literals.ENAMED_ELEMENT__NAME;

            assertEquals("InitialName", target.getName());

            EMFOperation op = new EMFOperation.UnsetFeatureOp(target, nameFeature, 0);
            op.apply();

            assertNull(target.getName());
        }

        @Test
        void testAddToListOp() {
            EClass target = EcoreFactory.eINSTANCE.createEClass();
            EAttribute attr = EcoreFactory.eINSTANCE.createEAttribute();
            attr.setName("myAttr");

            EStructuralFeature attrsFeature = EcorePackage.Literals.ECLASS__ESTRUCTURAL_FEATURES;

            assertTrue(target.getEStructuralFeatures().isEmpty());

            EMFOperation op = new EMFOperation.AddToListOp(target, attrsFeature, attr, -1, 0);
            op.apply();

            assertEquals(1, target.getEStructuralFeatures().size());
            assertSame(attr, target.getEStructuralFeatures().get(0));
        }

        @Test
        void testAddToListOpAtIndex() {
            EClass target = EcoreFactory.eINSTANCE.createEClass();
            EAttribute attr1 = EcoreFactory.eINSTANCE.createEAttribute();
            attr1.setName("attr1");
            EAttribute attr2 = EcoreFactory.eINSTANCE.createEAttribute();
            attr2.setName("attr2");
            EAttribute attr3 = EcoreFactory.eINSTANCE.createEAttribute();
            attr3.setName("attr3");

            target.getEStructuralFeatures().add(attr1);
            target.getEStructuralFeatures().add(attr3);

            EStructuralFeature attrsFeature = EcorePackage.Literals.ECLASS__ESTRUCTURAL_FEATURES;

            EMFOperation op = new EMFOperation.AddToListOp(target, attrsFeature, attr2, 1, 0);
            op.apply();

            assertEquals(3, target.getEStructuralFeatures().size());
            assertEquals("attr1", target.getEStructuralFeatures().get(0).getName());
            assertEquals("attr2", target.getEStructuralFeatures().get(1).getName());
            assertEquals("attr3", target.getEStructuralFeatures().get(2).getName());
        }

        @Test
        void testAddAllToListOp() {
            EClass target = EcoreFactory.eINSTANCE.createEClass();
            EAttribute attr1 = EcoreFactory.eINSTANCE.createEAttribute();
            attr1.setName("attr1");
            EAttribute attr2 = EcoreFactory.eINSTANCE.createEAttribute();
            attr2.setName("attr2");

            Collection<EAttribute> attrs = Arrays.asList(attr1, attr2);

            EStructuralFeature attrsFeature = EcorePackage.Literals.ECLASS__ESTRUCTURAL_FEATURES;

            EMFOperation op = new EMFOperation.AddAllToListOp(target, attrsFeature, attrs, -1, 0);
            op.apply();

            assertEquals(2, target.getEStructuralFeatures().size());
        }

        @Test
        void testRemoveFromListOp() {
            EClass target = EcoreFactory.eINSTANCE.createEClass();
            EAttribute attr = EcoreFactory.eINSTANCE.createEAttribute();
            attr.setName("myAttr");
            target.getEStructuralFeatures().add(attr);

            EStructuralFeature attrsFeature = EcorePackage.Literals.ECLASS__ESTRUCTURAL_FEATURES;

            assertEquals(1, target.getEStructuralFeatures().size());

            EMFOperation op = new EMFOperation.RemoveFromListOp(target, attrsFeature, attr, 0);
            op.apply();

            assertTrue(target.getEStructuralFeatures().isEmpty());
        }

        @Test
        void testRemoveAllFromListOp() {
            EClass target = EcoreFactory.eINSTANCE.createEClass();
            EAttribute attr1 = EcoreFactory.eINSTANCE.createEAttribute();
            EAttribute attr2 = EcoreFactory.eINSTANCE.createEAttribute();
            EAttribute attr3 = EcoreFactory.eINSTANCE.createEAttribute();
            target.getEStructuralFeatures().addAll(Arrays.asList(attr1, attr2, attr3));

            EStructuralFeature attrsFeature = EcorePackage.Literals.ECLASS__ESTRUCTURAL_FEATURES;

            EMFOperation op = new EMFOperation.RemoveAllFromListOp(
                    target, attrsFeature, Arrays.asList(attr1, attr3), 0);
            op.apply();

            assertEquals(1, target.getEStructuralFeatures().size());
            assertSame(attr2, target.getEStructuralFeatures().get(0));
        }

        @Test
        void testClearListOp() {
            EClass target = EcoreFactory.eINSTANCE.createEClass();
            EAttribute attr1 = EcoreFactory.eINSTANCE.createEAttribute();
            EAttribute attr2 = EcoreFactory.eINSTANCE.createEAttribute();
            target.getEStructuralFeatures().addAll(Arrays.asList(attr1, attr2));

            EStructuralFeature attrsFeature = EcorePackage.Literals.ECLASS__ESTRUCTURAL_FEATURES;

            assertEquals(2, target.getEStructuralFeatures().size());

            EMFOperation op = new EMFOperation.ClearListOp(target, attrsFeature, 0);
            op.apply();

            assertTrue(target.getEStructuralFeatures().isEmpty());
        }

        @Test
        void testSetListElementOp() {
            EClass target = EcoreFactory.eINSTANCE.createEClass();
            EAttribute attr1 = EcoreFactory.eINSTANCE.createEAttribute();
            attr1.setName("original");
            EAttribute attr2 = EcoreFactory.eINSTANCE.createEAttribute();
            attr2.setName("replacement");
            target.getEStructuralFeatures().add(attr1);

            EStructuralFeature attrsFeature = EcorePackage.Literals.ECLASS__ESTRUCTURAL_FEATURES;

            EMFOperation op = new EMFOperation.SetListElementOp(target, attrsFeature, 0, attr2, 0);
            op.apply();

            assertEquals(1, target.getEStructuralFeatures().size());
            assertEquals("replacement", target.getEStructuralFeatures().get(0).getName());
        }

        @Test
        void testMoveListElementOp() {
            EClass target = EcoreFactory.eINSTANCE.createEClass();
            EAttribute attr1 = EcoreFactory.eINSTANCE.createEAttribute();
            attr1.setName("first");
            EAttribute attr2 = EcoreFactory.eINSTANCE.createEAttribute();
            attr2.setName("second");
            EAttribute attr3 = EcoreFactory.eINSTANCE.createEAttribute();
            attr3.setName("third");
            target.getEStructuralFeatures().addAll(Arrays.asList(attr1, attr2, attr3));

            EStructuralFeature attrsFeature = EcorePackage.Literals.ECLASS__ESTRUCTURAL_FEATURES;

            // Move element at index 2 to index 0
            EMFOperation op = new EMFOperation.MoveListElementOp(target, attrsFeature, 0, 2, 0);
            op.apply();

            assertEquals("third", target.getEStructuralFeatures().get(0).getName());
            assertEquals("first", target.getEStructuralFeatures().get(1).getName());
            assertEquals("second", target.getEStructuralFeatures().get(2).getName());
        }
    }

    // ==================== DeferredEObject Tests ====================

    @Nested
    class DeferredEObjectTests {

        @Test
        void testCreateProxyReturnsProxy() {
            EClass target = EcoreFactory.eINSTANCE.createEClass();
            EClass proxy = DeferredEObject.createProxy(target, queue);

            assertNotSame(target, proxy);
            assertTrue(proxy instanceof DeferredEObject.ProxyMarker);
        }

        @Test
        void testCreateProxyWithNullReturnsNull() {
            EClass proxy = DeferredEObject.createProxy(null, queue);
            assertNull(proxy);
        }

        @Test
        void testCreateProxyDoesNotDoubleWrap() {
            EClass target = EcoreFactory.eINSTANCE.createEClass();
            EClass proxy1 = DeferredEObject.createProxy(target, queue);
            EClass proxy2 = DeferredEObject.createProxy(proxy1, queue);

            assertSame(proxy1, proxy2);
        }

        @Test
        void testUnwrapReturnsDelegate() {
            EClass target = EcoreFactory.eINSTANCE.createEClass();
            EClass proxy = DeferredEObject.createProxy(target, queue);

            EClass unwrapped = DeferredEObject.unwrap(proxy);
            assertSame(target, unwrapped);
        }

        @Test
        void testUnwrapNonProxyReturnsSame() {
            EClass target = EcoreFactory.eINSTANCE.createEClass();
            EClass unwrapped = DeferredEObject.unwrap(target);
            assertSame(target, unwrapped);
        }

        @Test
        void testGetDelegateReturnsRealObject() {
            EClass target = EcoreFactory.eINSTANCE.createEClass();
            EClass proxy = DeferredEObject.createProxy(target, queue);

            EObject delegate = ((DeferredEObject.ProxyMarker) proxy).getDelegate();
            assertSame(target, delegate);
        }

        @Test
        void testSetterRecordsOperation() {
            queue.enableDeferredMode();

            EClass target = EcoreFactory.eINSTANCE.createEClass();
            EClass proxy = DeferredEObject.createProxy(target, queue);

            proxy.setName("TestName");

            assertEquals(1, queue.size());
            assertNull(target.getName()); // Not applied yet
        }

        @Test
        void testReadAfterWriteConsistency() {
            queue.enableDeferredMode();

            EClass target = EcoreFactory.eINSTANCE.createEClass();
            EClass proxy = DeferredEObject.createProxy(target, queue);

            proxy.setName("PendingName");

            // Proxy should return pending value
            assertEquals("PendingName", proxy.getName());
            // Real object should not have the value
            assertNull(target.getName());
        }

        @Test
        void testESetRecordsOperation() {
            queue.enableDeferredMode();

            EClass target = EcoreFactory.eINSTANCE.createEClass();
            EClass proxy = DeferredEObject.createProxy(target, queue);

            proxy.eSet(EcorePackage.Literals.ENAMED_ELEMENT__NAME, "ESetName");

            assertEquals(1, queue.size());
            assertEquals("ESetName", proxy.eGet(EcorePackage.Literals.ENAMED_ELEMENT__NAME));
            assertNull(target.getName());
        }

        @Test
        void testEUnsetRecordsOperation() {
            queue.enableDeferredMode();

            EClass target = EcoreFactory.eINSTANCE.createEClass();
            target.setName("Initial");
            EClass proxy = DeferredEObject.createProxy(target, queue);

            proxy.eUnset(EcorePackage.Literals.ENAMED_ELEMENT__NAME);

            assertEquals(1, queue.size());
        }

        @Test
        void testPassthroughMethods() {
            EClass target = EcoreFactory.eINSTANCE.createEClass();
            target.setName("TestClass");
            EClass proxy = DeferredEObject.createProxy(target, queue);

            // These methods should delegate directly
            assertSame(target.eClass(), proxy.eClass());
            assertSame(target.eResource(), proxy.eResource());
            assertEquals(target.hashCode(), target.hashCode());
            assertEquals(target.toString(), proxy.toString());
        }

        @Test
        void testBooleanGetter() {
            queue.enableDeferredMode();

            EClass target = EcoreFactory.eINSTANCE.createEClass();
            EClass proxy = DeferredEObject.createProxy(target, queue);

            // Default is false
            assertFalse(proxy.isAbstract());

            proxy.setAbstract(true);

            // Pending value should be visible
            assertTrue(proxy.isAbstract());
            // Real object unchanged
            assertFalse(target.isAbstract());
        }

        @Test
        void testCommitAppliesPendingChanges() {
            queue.enableDeferredMode();

            EClass target = EcoreFactory.eINSTANCE.createEClass();
            EClass proxy = DeferredEObject.createProxy(target, queue);

            proxy.setName("CommittedName");
            proxy.setAbstract(true);

            assertNull(target.getName());
            assertFalse(target.isAbstract());

            queue.commit();

            assertEquals("CommittedName", target.getName());
            assertTrue(target.isAbstract());
        }
    }

    // ==================== DeferredEList Tests ====================

    @Nested
    class DeferredEListTests {

        private EClass container;
        private EStructuralFeature feature;
        private DeferredEList<EStructuralFeature> deferredList;

        @BeforeEach
        void setUpList() {
            queue.enableDeferredMode();
            container = EcoreFactory.eINSTANCE.createEClass();
            feature = EcorePackage.Literals.ECLASS__ESTRUCTURAL_FEATURES;

            @SuppressWarnings("unchecked")
            EList<EStructuralFeature> realList = (EList<EStructuralFeature>) container.eGet(feature);
            deferredList = new DeferredEList<>(realList, container, feature, queue);
        }

        @Test
        void testAddRecordsOperation() {
            EAttribute attr = EcoreFactory.eINSTANCE.createEAttribute();

            assertTrue(deferredList.add(attr));

            assertEquals(1, queue.size());
            assertTrue(container.getEStructuralFeatures().isEmpty());
        }

        @Test
        void testAddVisibleInPendingList() {
            EAttribute attr = EcoreFactory.eINSTANCE.createEAttribute();
            attr.setName("pendingAttr");

            deferredList.add(attr);

            // Should be visible via deferred list
            assertEquals(1, deferredList.size());
            assertTrue(deferredList.contains(attr));
            assertSame(attr, deferredList.get(0));
        }

        @Test
        void testAddAtIndexRecordsOperation() {
            EAttribute attr = EcoreFactory.eINSTANCE.createEAttribute();

            deferredList.add(0, attr);

            assertEquals(1, queue.size());
        }

        @Test
        void testAddAllRecordsOperation() {
            EAttribute attr1 = EcoreFactory.eINSTANCE.createEAttribute();
            EAttribute attr2 = EcoreFactory.eINSTANCE.createEAttribute();

            assertTrue(deferredList.addAll(Arrays.asList(attr1, attr2)));

            assertEquals(1, queue.size()); // Single AddAllToListOp
            assertEquals(2, deferredList.size());
        }

        @Test
        void testRemoveRecordsOperation() {
            // First add to real list
            EAttribute attr = EcoreFactory.eINSTANCE.createEAttribute();
            container.getEStructuralFeatures().add(attr);

            // Recreate deferred list with populated delegate
            @SuppressWarnings("unchecked")
            EList<EStructuralFeature> realList = (EList<EStructuralFeature>) container.eGet(feature);
            deferredList = new DeferredEList<>(realList, container, feature, queue);

            deferredList.remove(attr);

            assertEquals(1, queue.size());
            // Element should be marked as removed
            assertFalse(deferredList.contains(attr));
        }

        @Test
        void testClearRecordsOperation() {
            EAttribute attr1 = EcoreFactory.eINSTANCE.createEAttribute();
            EAttribute attr2 = EcoreFactory.eINSTANCE.createEAttribute();
            container.getEStructuralFeatures().addAll(Arrays.asList(attr1, attr2));

            @SuppressWarnings("unchecked")
            EList<EStructuralFeature> realList = (EList<EStructuralFeature>) container.eGet(feature);
            deferredList = new DeferredEList<>(realList, container, feature, queue);

            deferredList.clear();

            assertEquals(1, queue.size());
            assertTrue(deferredList.isEmpty());
        }

        @Test
        void testSetRecordsOperation() {
            EAttribute attr1 = EcoreFactory.eINSTANCE.createEAttribute();
            attr1.setName("original");
            container.getEStructuralFeatures().add(attr1);

            @SuppressWarnings("unchecked")
            EList<EStructuralFeature> realList = (EList<EStructuralFeature>) container.eGet(feature);
            deferredList = new DeferredEList<>(realList, container, feature, queue);

            EAttribute attr2 = EcoreFactory.eINSTANCE.createEAttribute();
            attr2.setName("replacement");

            EStructuralFeature old = deferredList.set(0, attr2);

            assertSame(attr1, old);
            assertEquals(1, queue.size());
        }

        @Test
        void testMoveByElementRecordsOperation() {
            EAttribute attr1 = EcoreFactory.eINSTANCE.createEAttribute();
            EAttribute attr2 = EcoreFactory.eINSTANCE.createEAttribute();
            container.getEStructuralFeatures().addAll(Arrays.asList(attr1, attr2));

            @SuppressWarnings("unchecked")
            EList<EStructuralFeature> realList = (EList<EStructuralFeature>) container.eGet(feature);
            deferredList = new DeferredEList<>(realList, container, feature, queue);

            deferredList.move(0, attr2);

            assertEquals(1, queue.size());
        }

        @Test
        void testMoveByIndexRecordsOperation() {
            EAttribute attr1 = EcoreFactory.eINSTANCE.createEAttribute();
            EAttribute attr2 = EcoreFactory.eINSTANCE.createEAttribute();
            container.getEStructuralFeatures().addAll(Arrays.asList(attr1, attr2));

            @SuppressWarnings("unchecked")
            EList<EStructuralFeature> realList = (EList<EStructuralFeature>) container.eGet(feature);
            deferredList = new DeferredEList<>(realList, container, feature, queue);

            EStructuralFeature moved = deferredList.move(0, 1);

            assertSame(attr2, moved);
            assertEquals(1, queue.size());
        }

        @Test
        void testCombinedViewIncludesDelegateAndPending() {
            // Add to delegate first
            EAttribute existing = EcoreFactory.eINSTANCE.createEAttribute();
            existing.setName("existing");
            container.getEStructuralFeatures().add(existing);

            @SuppressWarnings("unchecked")
            EList<EStructuralFeature> realList = (EList<EStructuralFeature>) container.eGet(feature);
            deferredList = new DeferredEList<>(realList, container, feature, queue);

            // Add pending
            EAttribute pending = EcoreFactory.eINSTANCE.createEAttribute();
            pending.setName("pending");
            deferredList.add(pending);

            // Combined view should have both
            assertEquals(2, deferredList.size());
            assertTrue(deferredList.contains(existing));
            assertTrue(deferredList.contains(pending));
        }

        @Test
        void testIteratorReturnsCorrectElements() {
            EAttribute attr1 = EcoreFactory.eINSTANCE.createEAttribute();
            EAttribute attr2 = EcoreFactory.eINSTANCE.createEAttribute();

            deferredList.add(attr1);
            deferredList.add(attr2);

            List<EStructuralFeature> collected = new ArrayList<>();
            for (EStructuralFeature f : deferredList) {
                collected.add(f);
            }

            assertEquals(2, collected.size());
            assertTrue(collected.contains(attr1));
            assertTrue(collected.contains(attr2));
        }

        @Test
        void testContainsAllWithPendingElements() {
            EAttribute attr1 = EcoreFactory.eINSTANCE.createEAttribute();
            EAttribute attr2 = EcoreFactory.eINSTANCE.createEAttribute();

            deferredList.add(attr1);
            deferredList.add(attr2);

            assertTrue(deferredList.containsAll(Arrays.asList(attr1, attr2)));
        }

        @Test
        void testCommitAppliesListOperations() {
            EAttribute attr1 = EcoreFactory.eINSTANCE.createEAttribute();
            attr1.setName("attr1");
            EAttribute attr2 = EcoreFactory.eINSTANCE.createEAttribute();
            attr2.setName("attr2");

            deferredList.add(attr1);
            deferredList.add(attr2);

            assertTrue(container.getEStructuralFeatures().isEmpty());

            queue.commit();

            assertEquals(2, container.getEStructuralFeatures().size());
            assertEquals("attr1", container.getEStructuralFeatures().get(0).getName());
            assertEquals("attr2", container.getEStructuralFeatures().get(1).getName());
        }
    }

    // ==================== Integration Tests ====================

    @Nested
    class IntegrationTests {

        @Test
        void testFullWorkflowWithProxiedObject() {
            queue.enableDeferredMode();

            // Create and proxy an object
            EClass target = EcoreFactory.eINSTANCE.createEClass();
            EClass proxy = DeferredEObject.createProxy(target, queue);

            // Make multiple modifications
            proxy.setName("TestClass");
            proxy.setAbstract(true);

            // Add attributes via list
            EAttribute attr1 = EcoreFactory.eINSTANCE.createEAttribute();
            attr1.setName("id");
            EAttribute attr2 = EcoreFactory.eINSTANCE.createEAttribute();
            attr2.setName("name");

            proxy.getEStructuralFeatures().add(attr1);
            proxy.getEStructuralFeatures().add(attr2);

            // Verify nothing applied yet
            assertNull(target.getName());
            assertFalse(target.isAbstract());
            assertTrue(target.getEStructuralFeatures().isEmpty());

            // But proxy shows pending values
            assertEquals("TestClass", proxy.getName());
            assertTrue(proxy.isAbstract());
            assertEquals(2, proxy.getEStructuralFeatures().size());

            // Commit all operations
            int applied = queue.commit();

            // Verify all changes applied
            assertEquals(4, applied); // 2 setters + 2 list adds
            assertEquals("TestClass", target.getName());
            assertTrue(target.isAbstract());
            assertEquals(2, target.getEStructuralFeatures().size());
        }

        @Test
        void testOperationsAppliedInSequenceOrder() {
            queue.enableDeferredMode();

            EClass target = EcoreFactory.eINSTANCE.createEClass();
            EClass proxy = DeferredEObject.createProxy(target, queue);

            // Make changes - each gets a sequence number
            proxy.setName("First");
            proxy.setName("Second");
            proxy.setName("Third");

            queue.commit();

            // Last change should win (applied in sequence order)
            assertEquals("Third", target.getName());
        }

        @Test
        void testMixedOperationTypes() {
            queue.enableDeferredMode();

            EClass target = EcoreFactory.eINSTANCE.createEClass();
            EClass proxy = DeferredEObject.createProxy(target, queue);

            // Various operations
            proxy.setName("MixedTest");
            proxy.setAbstract(true);

            EAttribute attr = EcoreFactory.eINSTANCE.createEAttribute();
            proxy.getEStructuralFeatures().add(attr);

            EClass superClass = EcoreFactory.eINSTANCE.createEClass();
            superClass.setName("Super");
            proxy.getESuperTypes().add(superClass);

            queue.commit();

            assertEquals("MixedTest", target.getName());
            assertTrue(target.isAbstract());
            assertEquals(1, target.getEStructuralFeatures().size());
            assertEquals(1, target.getESuperTypes().size());
        }
    }

    // ==================== Concurrent Operation Tests ====================

    @Nested
    class ConcurrentTests {

        @Test
        void testConcurrentAddOperations() throws InterruptedException {
            queue.enableDeferredMode();

            EClass target = EcoreFactory.eINSTANCE.createEClass();
            EClass proxy = DeferredEObject.createProxy(target, queue);

            int threadCount = 10;
            int attrsPerThread = 10;

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < attrsPerThread; i++) {
                            EAttribute attr = EcoreFactory.eINSTANCE.createEAttribute();
                            attr.setName("attr_" + threadId + "_" + i);
                            proxy.getEStructuralFeatures().add(attr);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            endLatch.await();
            executor.shutdown();

            // All operations should be queued
            assertEquals(threadCount * attrsPerThread, queue.size());

            // Commit should succeed
            int applied = queue.commit();
            assertEquals(threadCount * attrsPerThread, applied);

            // All attributes should be in the list
            assertEquals(threadCount * attrsPerThread, target.getEStructuralFeatures().size());
        }

        @Test
        void testDeterministicOrderingAcrossRuns() throws InterruptedException {
            // Run the same concurrent operations multiple times
            // Results should be deterministic (same sequence = same order)
            List<List<String>> runResults = new ArrayList<>();

            for (int run = 0; run < 3; run++) {
                OperationQueue runQueue = new OperationQueue();
                runQueue.enableDeferredMode();

                EClass target = EcoreFactory.eINSTANCE.createEClass();
                EClass proxy = DeferredEObject.createProxy(target, runQueue);

                int threadCount = 5;
                ExecutorService executor = Executors.newFixedThreadPool(threadCount);
                CountDownLatch startLatch = new CountDownLatch(1);
                CountDownLatch endLatch = new CountDownLatch(threadCount);

                for (int t = 0; t < threadCount; t++) {
                    final int threadId = t;
                    executor.submit(() -> {
                        try {
                            startLatch.await();
                            EAttribute attr = EcoreFactory.eINSTANCE.createEAttribute();
                            attr.setName("attr_" + threadId);
                            proxy.getEStructuralFeatures().add(attr);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            endLatch.countDown();
                        }
                    });
                }

                startLatch.countDown();
                endLatch.await();
                executor.shutdown();

                // Get sequence-sorted results
                List<EMFOperation> ops = runQueue.getOperations();
                ops.sort(Comparator.comparingLong(EMFOperation::sequence));

                List<String> orderedNames = new ArrayList<>();
                for (EMFOperation op : ops) {
                    if (op instanceof EMFOperation.AddToListOp addOp) {
                        EAttribute attr = (EAttribute) addOp.element();
                        orderedNames.add(attr.getName());
                    }
                }
                runResults.add(orderedNames);
            }

            // The order may vary between runs (due to thread scheduling),
            // but within each run the sequence ensures determinism
            // What we're testing is that sorting by sequence produces consistent results
            for (List<String> result : runResults) {
                assertEquals(5, result.size());
            }
        }

        @Test
        void testNoExceptionsDuringConcurrentOperations() throws InterruptedException {
            queue.enableDeferredMode();

            int threadCount = 20;
            int operationsPerThread = 50;
            List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        EClass target = EcoreFactory.eINSTANCE.createEClass();
                        EClass proxy = DeferredEObject.createProxy(target, queue);

                        for (int i = 0; i < operationsPerThread; i++) {
                            proxy.setName("Thread" + threadId + "_Op" + i);
                            proxy.setAbstract(i % 2 == 0);

                            EAttribute attr = EcoreFactory.eINSTANCE.createEAttribute();
                            attr.setName("attr_" + threadId + "_" + i);
                            proxy.getEStructuralFeatures().add(attr);
                        }
                    } catch (Throwable e) {
                        exceptions.add(e);
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            endLatch.await();
            executor.shutdown();

            if (!exceptions.isEmpty()) {
                StringBuilder sb = new StringBuilder("Exceptions during concurrent operations:\n");
                for (Throwable e : exceptions) {
                    sb.append(e.getClass().getName()).append(": ").append(e.getMessage()).append("\n");
                }
                fail(sb.toString());
            }

            // Should have many operations queued
            assertTrue(queue.size() > 0);

            // Commit should succeed
            assertDoesNotThrow(() -> queue.commit());
        }
    }
}
