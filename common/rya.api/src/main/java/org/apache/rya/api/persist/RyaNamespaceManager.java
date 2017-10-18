package org.apache.rya.api.persist;

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

import org.apache.rya.api.RdfCloudTripleStoreConfiguration;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.Namespace;

/**
 * Date: 7/17/12
 * Time: 8:23 AM
 */
public interface RyaNamespaceManager<C extends RdfCloudTripleStoreConfiguration> extends RyaConfigured<C> {

    void addNamespace(String pfx, String namespace) throws RyaDAOException;

    String getNamespace(String pfx) throws RyaDAOException;

    void removeNamespace(String pfx) throws RyaDAOException;

    CloseableIteration<? extends Namespace, RyaDAOException> iterateNamespace() throws RyaDAOException;
}
