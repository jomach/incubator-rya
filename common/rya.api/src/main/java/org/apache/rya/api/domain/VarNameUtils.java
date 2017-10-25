/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.rya.api.domain;

import org.apache.commons.lang.StringUtils;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.algebra.helpers.TupleExprs;

/**
 * Utility methods and constants for RDF {@code Var} names.
 */
public final class VarNameUtils {
    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    /**
     * Prepended to the start of constant var names.
     */
    public static final String CONSTANT_PREFIX = "_const_";
    private static final String LEGACY_CONSTANT_PREFIX = "-const-";

    /**
     * Prepended to the start of anonymous var names.
     */
    public static final String ANONYMOUS_PREFIX = "_anon_";
    private static final String LEGACY_ANONYMOUS_PREFIX = "-anon-";

    /**
     * Private constructor to prevent instantiation.
     */
    private VarNameUtils() {
    }

    /**
     * Prepends the constant prefix to the specified value.
     * @param value the value to add the constant prefix to.
     * @return the value with the constant prefix attached before it.
     */
    public static String prependConstant(final String value) {
        if (value != null) {
            return CONSTANT_PREFIX + value;
        }
        return null;
    }

    /**
     * Checks if the var name has the constant prefix.
     * @param name the var name to check.
     * @return {@code true} if the name begins with the constant prefix.
     * {@code false} otherwise.
     */
    public static boolean isConstant(final String name) {
        if (name != null) {
            return name.startsWith(CONSTANT_PREFIX) || name.startsWith(LEGACY_CONSTANT_PREFIX);
        }
        return false;
    }

    /**
     * Removes the constant prefix from a string if it exists.
     * @param name the name string to strip the constant prefix from.
     * @return the string with the constant prefix removed. Otherwise returns
     * the original string.
     */
    public static String removeConstant(final String name) {
        if (isConstant(name)) {
            String removed = StringUtils.removeStart(name, CONSTANT_PREFIX);
            if (name.equals(removed)) {
                removed = StringUtils.removeStart(name, LEGACY_CONSTANT_PREFIX);
            }
            return removed;
        }
        return name;
    }

    /**
     * Prepends the anonymous prefix to the specified value.
     * @param value the value to add the anonymous prefix to.
     * @return the value with the anonymous prefix attached before it.
     */
    public static String prependAnonymous(final String value) {
        if (value != null) {
            return ANONYMOUS_PREFIX + value;
        }
        return null;
    }

    /**
     * Checks if the var name has the anonymous prefix.
     * @param name the var name to check.
     * @return {@code true} if the name begins with the anonymous prefix.
     * {@code false} otherwise.
     */
    public static boolean isAnonymous(final String name) {
        if (name != null) {
            return name.startsWith(ANONYMOUS_PREFIX) || name.startsWith(LEGACY_ANONYMOUS_PREFIX);
        }
        return false;
    }

    /**
     * Removes the anonymous prefix from a string if it exists.
     * @param name the name string to strip the anonymous prefix from.
     * @return the string with the anonymous prefix removed. Otherwise returns
     * the original string.
     */
    public static String removeAnonymous(final String name) {
        if (isAnonymous(name)) {
            String removed = StringUtils.removeStart(name, ANONYMOUS_PREFIX);
            if (name.equals(removed)) {
                removed = StringUtils.removeStart(name, LEGACY_ANONYMOUS_PREFIX);
            }
        }
        return name;
    }

    /**
     * Creates a unique constant name for the {@code Var} with the supplied
     * {@link Value}.
     * @param value the {@link  Value}.
     * @return the unique constant name for the {@code Var}.
     */
    public static String createUniqueConstVarName(final Value value) {
        return TupleExprs.createConstVar(value).getName();
    }

    /**
     * Creates a unique constant name for the {@code Var} with the supplied label.
     * @param label the label for the {@code Literal}.
     * @return the unique constant name for the {@code Var}.
     */
    public static String createUniqueConstVarName(final String label) {
        return createUniqueConstVarName(VF.createLiteral(label));
    }
}