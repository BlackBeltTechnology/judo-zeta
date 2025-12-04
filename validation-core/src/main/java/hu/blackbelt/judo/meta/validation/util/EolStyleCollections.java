package hu.blackbelt.judo.zeta.validation.util;

/*-
 * #%L
 * Judo :: Zeta :: Validation Core
 * %%
 * Copyright (C) 2018 - 2022 BlackBelt Technology
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

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Utility methods for EOL-style collection operations using Java Stream API.
 *
 * <p>Provides EOL-named methods that map to Java Stream operations for developers
 * familiar with Epsilon Object Language.</p>
 *
 * <p>Example mapping:</p>
 * <pre>
 * EOL: collection.select(c | condition)
 * Java: select(collection, c -> condition)
 * Stream: collection.stream().filter(c -> condition).collect(toList())
 * </pre>
 */
public class EolStyleCollections {

    /**
     * EOL-style select: filter elements matching predicate.
     * Equivalent to {@code collection.stream().filter(predicate).collect(toList())}
     *
     * @param collection the input collection
     * @param predicate the filter predicate
     * @return filtered list
     */
    public static <T> List<T> select(
        Collection<T> collection,
        Predicate<T> predicate
    ) {
        return collection
            .stream()
            .filter(predicate)
            .collect(Collectors.toList());
    }

    /**
     * EOL-style reject: filter elements NOT matching predicate.
     * Equivalent to {@code collection.stream().filter(predicate.negate()).collect(toList())}
     *
     * @param collection the input collection
     * @param predicate the filter predicate
     * @return filtered list
     */
    public static <T> List<T> reject(
        Collection<T> collection,
        Predicate<T> predicate
    ) {
        return collection
            .stream()
            .filter(predicate.negate())
            .collect(Collectors.toList());
    }

    /**
     * EOL-style collect: transform each element.
     * Equivalent to {@code collection.stream().map(mapper).collect(toList())}
     *
     * @param collection the input collection
     * @param mapper the transformation function
     * @return transformed list
     */
    public static <T, R> List<R> collect(
        Collection<T> collection,
        Function<T, R> mapper
    ) {
        return collection.stream().map(mapper).collect(Collectors.toList());
    }

    /**
     * EOL-style forAll: check if all elements match.
     * Equivalent to {@code collection.stream().allMatch(predicate)}
     *
     * @param collection the input collection
     * @param predicate the predicate to check
     * @return true if all elements match
     */
    public static <T> boolean forAll(
        Collection<T> collection,
        Predicate<T> predicate
    ) {
        return collection.stream().allMatch(predicate);
    }

    /**
     * EOL-style exists: check if any element matches.
     * Equivalent to {@code collection.stream().anyMatch(predicate)}
     *
     * @param collection the input collection
     * @param predicate the predicate to check
     * @return true if any element matches
     */
    public static <T> boolean exists(
        Collection<T> collection,
        Predicate<T> predicate
    ) {
        return collection.stream().anyMatch(predicate);
    }

    /**
     * EOL-style one: check if exactly one element matches.
     *
     * @param collection the input collection
     * @param predicate the predicate to check
     * @return true if exactly one element matches
     */
    public static <T> boolean one(
        Collection<T> collection,
        Predicate<T> predicate
    ) {
        return collection.stream().filter(predicate).count() == 1;
    }

    /**
     * EOL-style flatten: flatten nested collections.
     * Equivalent to {@code collections.stream().flatMap(Collection::stream).collect(toList())}
     *
     * @param collections the nested collections
     * @return flattened list
     */
    public static <T> List<T> flatten(
        Collection<? extends Collection<T>> collections
    ) {
        return collections
            .stream()
            .flatMap(Collection::stream)
            .collect(Collectors.toList());
    }

    /**
     * EOL-style including: add single element to collection.
     * Equivalent to {@code Stream.concat(collection.stream(), Stream.of(element)).collect(toList())}
     *
     * @param collection the input collection
     * @param element the element to add
     * @return new list with element included
     */
    public static <T> List<T> including(Collection<T> collection, T element) {
        return Stream.concat(collection.stream(), Stream.of(element)).collect(
            Collectors.toList()
        );
    }

    /**
     * EOL-style excluding: remove single element from collection.
     *
     * @param collection the input collection
     * @param element the element to exclude
     * @return new list without element
     */
    public static <T> List<T> excluding(Collection<T> collection, T element) {
        return collection
            .stream()
            .filter(e -> !Objects.equals(e, element))
            .collect(Collectors.toList());
    }

    /**
     * EOL-style includingAll: merge two collections.
     * Equivalent to {@code Stream.concat(collection.stream(), other.stream()).collect(toList())}
     *
     * @param collection the first collection
     * @param other the second collection
     * @return merged list
     */
    public static <T> List<T> includingAll(
        Collection<T> collection,
        Collection<T> other
    ) {
        return Stream.concat(collection.stream(), other.stream()).collect(
            Collectors.toList()
        );
    }

    /**
     * EOL-style excludingAll: remove all elements from other collection.
     *
     * @param collection the input collection
     * @param other the elements to exclude
     * @return new list without elements from other
     */
    public static <T> List<T> excludingAll(
        Collection<T> collection,
        Collection<T> other
    ) {
        Set<T> toExclude = new HashSet<>(other);
        return collection
            .stream()
            .filter(e -> !toExclude.contains(e))
            .collect(Collectors.toList());
    }

    /**
     * EOL-style first: get first element.
     * Equivalent to {@code collection.stream().findFirst().orElse(null)}
     *
     * @param collection the input collection
     * @return first element or null if empty
     */
    public static <T> T first(Collection<T> collection) {
        return collection.stream().findFirst().orElse(null);
    }

    /**
     * EOL-style asSet: convert to Set.
     * Equivalent to {@code new HashSet<>(collection)} or {@code collection.stream().collect(toSet())}
     *
     * @param collection the input collection
     * @return set containing unique elements
     */
    public static <T> Set<T> asSet(Collection<T> collection) {
        return new HashSet<>(collection);
    }

    /**
     * Check if collection is empty.
     *
     * @param collection the input collection
     * @return true if empty
     */
    public static <T> boolean isEmpty(Collection<T> collection) {
        return collection.isEmpty();
    }

    /**
     * Check if collection is not empty.
     *
     * @param collection the input collection
     * @return true if not empty
     */
    public static <T> boolean notEmpty(Collection<T> collection) {
        return !collection.isEmpty();
    }
}
