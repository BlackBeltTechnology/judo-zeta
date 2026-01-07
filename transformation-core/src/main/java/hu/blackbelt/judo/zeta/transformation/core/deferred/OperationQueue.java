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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe queue for deferred EMF operations.
 *
 * <p>During parallel transformation, operations are added to this queue
 * from multiple threads. The queue assigns monotonically increasing
 * sequence numbers for deterministic ordering during replay.</p>
 *
 * <p>During commit phase, operations are sorted by sequence and applied
 * in order to the EMF model.</p>
 *
 * <h3>Usage Pattern:</h3>
 * <pre>{@code
 * OperationQueue queue = new OperationQueue();
 *
 * // Parallel phase: multiple threads add operations
 * queue.addSetAttribute(target, feature, value);
 * queue.addToList(container, listFeature, element, -1);
 *
 * // Commit phase: single thread replays
 * queue.commit();
 * }</pre>
 *
 * @see EMFOperation
 * @see DeferredEObject
 */
public class OperationQueue {

    private static final Logger log = LoggerFactory.getLogger(OperationQueue.class);

    /**
     * Thread-safe queue for collecting operations during parallel phase.
     */
    private final ConcurrentLinkedQueue<EMFOperation> operations = new ConcurrentLinkedQueue<>();

    /**
     * Monotonically increasing sequence counter for operation ordering.
     */
    private final AtomicLong sequenceCounter = new AtomicLong(0);

    /**
     * Flag indicating whether deferred mode is enabled.
     * When false, operations are applied immediately.
     */
    private volatile boolean deferredMode = false;

    /**
     * Enable deferred mode where operations are queued instead of applied.
     */
    public void enableDeferredMode() {
        this.deferredMode = true;
    }

    /**
     * Disable deferred mode.
     */
    public void disableDeferredMode() {
        this.deferredMode = false;
    }

    /**
     * Check if deferred mode is enabled.
     *
     * @return true if operations are being deferred
     */
    public boolean isDeferredMode() {
        return deferredMode;
    }

    /**
     * Get the next sequence number.
     *
     * @return the next sequence number
     */
    public long nextSequence() {
        return sequenceCounter.getAndIncrement();
    }

    /**
     * Add an operation to the queue.
     *
     * @param operation the operation to add
     */
    public void add(EMFOperation operation) {
        if (deferredMode) {
            operations.offer(operation);
        } else {
            // Apply immediately if not in deferred mode
            operation.apply();
        }
    }

    /**
     * Get the number of queued operations.
     *
     * @return the operation count
     */
    public int size() {
        return operations.size();
    }

    /**
     * Check if the queue is empty.
     *
     * @return true if no operations are queued
     */
    public boolean isEmpty() {
        return operations.isEmpty();
    }

    /**
     * Commit all queued operations to the EMF model.
     *
     * <p>Operations are sorted by sequence number and applied in order.
     * This method should be called from a single thread after the parallel
     * transformation phase completes.</p>
     *
     * @return the number of operations applied
     */
    public int commit() {
        if (operations.isEmpty()) {
            return 0;
        }

        // Collect all operations
        List<EMFOperation> toApply = new ArrayList<>();
        EMFOperation op;
        while ((op = operations.poll()) != null) {
            toApply.add(op);
        }

        // Sort by sequence for deterministic ordering
        toApply.sort(Comparator.comparingLong(EMFOperation::sequence));

        if (log.isDebugEnabled()) {
            log.debug("Committing {} deferred EMF operations", toApply.size());
        }

        // Apply in order
        int applied = 0;
        for (EMFOperation operation : toApply) {
            try {
                operation.apply();
                applied++;
            } catch (Exception e) {
                log.error("Failed to apply operation: {} (seq={})",
                        operation.getClass().getSimpleName(), operation.sequence(), e);
                throw new RuntimeException("Failed to apply deferred EMF operation", e);
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("Successfully applied {} deferred EMF operations", applied);
        }

        return applied;
    }

    /**
     * Clear all queued operations without applying them.
     *
     * <p>Use this to discard operations on error or rollback.</p>
     */
    public void clear() {
        operations.clear();
    }

    /**
     * Reset the queue and sequence counter.
     */
    public void reset() {
        operations.clear();
        sequenceCounter.set(0);
        deferredMode = false;
    }

    /**
     * Get all queued operations for debugging/testing.
     *
     * @return list of queued operations (not in order)
     */
    public List<EMFOperation> getOperations() {
        return new ArrayList<>(operations);
    }
}
