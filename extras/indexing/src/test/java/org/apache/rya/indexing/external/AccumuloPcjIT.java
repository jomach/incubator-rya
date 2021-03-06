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
package org.apache.rya.indexing.external;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.MutationsRejectedException;
import org.apache.accumulo.core.client.TableExistsException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.admin.TableOperations;
import org.apache.hadoop.conf.Configuration;
import org.apache.rya.accumulo.AccumuloRdfConfiguration;
import org.apache.rya.api.RdfCloudTripleStoreConfiguration;
import org.apache.rya.api.persist.RyaDAOException;
import org.apache.rya.indexing.IndexPlanValidator.IndexPlanValidator;
import org.apache.rya.indexing.accumulo.ConfigUtils;
import org.apache.rya.indexing.external.PrecomputedJoinIndexerConfig.PrecomputedJoinStorageType;
import org.apache.rya.indexing.external.tupleSet.AccumuloIndexSet;
import org.apache.rya.indexing.external.tupleSet.ExternalTupleSet;
import org.apache.rya.indexing.pcj.matching.PCJOptimizer;
import org.apache.rya.indexing.pcj.storage.PcjException;
import org.apache.rya.rdftriplestore.inference.InferenceEngineException;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.MalformedQueryException;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.QueryResultHandlerException;
import org.eclipse.rdf4j.query.TupleQueryResultHandler;
import org.eclipse.rdf4j.query.TupleQueryResultHandlerException;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.parser.ParsedQuery;
import org.eclipse.rdf4j.query.parser.sparql.SPARQLParser;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.repository.sail.SailRepositoryConnection;
import org.eclipse.rdf4j.sail.SailException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

public class AccumuloPcjIT {
    private static final ValueFactory VF = SimpleValueFactory.getInstance();

	private SailRepositoryConnection conn, pcjConn;
	private SailRepository repo, pcjRepo;
	private Connector accCon;
	private final Configuration conf = getConf();
	private final String prefix = "table_";
	private final String tablename = "table_INDEX_";
	private IRI obj, obj2, subclass, subclass2, talksTo;

	@Before
	public void init() throws RepositoryException,
			TupleQueryResultHandlerException, QueryEvaluationException,
			MalformedQueryException, AccumuloException,
			AccumuloSecurityException, TableExistsException, RyaDAOException,
			TableNotFoundException, InferenceEngineException,
			NumberFormatException, UnknownHostException, SailException {

		repo = PcjIntegrationTestingUtil.getNonPcjRepo(prefix, "instance");
		conn = repo.getConnection();

		pcjRepo = PcjIntegrationTestingUtil.getPcjRepo(prefix, "instance");
		pcjConn = pcjRepo.getConnection();

		final IRI sub = VF.createIRI("uri:entity");
		subclass = VF.createIRI("uri:class");
		obj = VF.createIRI("uri:obj");
		talksTo = VF.createIRI("uri:talksTo");

		conn.add(sub, RDF.TYPE, subclass);
		conn.add(sub, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(sub, talksTo, obj);

		final IRI sub2 = VF.createIRI("uri:entity2");
		subclass2 = VF.createIRI("uri:class2");
		obj2 = VF.createIRI("uri:obj2");

		conn.add(sub2, RDF.TYPE, subclass2);
		conn.add(sub2, RDFS.LABEL, VF.createLiteral("label2"));
		conn.add(sub2, talksTo, obj2);

		accCon = ConfigUtils.getConnector(conf);


	}

	@After
	public void close() throws RepositoryException, AccumuloException,
			AccumuloSecurityException, TableNotFoundException {

		PcjIntegrationTestingUtil.closeAndShutdown(conn, repo);
		PcjIntegrationTestingUtil.closeAndShutdown(pcjConn, pcjRepo);
		PcjIntegrationTestingUtil.deleteCoreRyaTables(accCon, prefix);
		PcjIntegrationTestingUtil.deleteIndexTables(accCon, 3, prefix);

	}

	@Test
	public void testEvaluateSingleIndex()
			throws TupleQueryResultHandlerException, QueryEvaluationException,
			MalformedQueryException, RepositoryException, PcjException,
			SailException, MutationsRejectedException, TableNotFoundException {

		final String indexSparqlString = ""//
				+ "SELECT ?e ?l ?c " //
				+ "{" //
				+ "  ?e a ?c . "//
				+ "  ?e <http://www.w3.org/2000/01/rdf-schema#label> ?l "//
				+ "}";//

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename + 1,
				indexSparqlString, new String[] { "e", "l", "c" },
				Optional.absent());

		final String queryString = ""//
				+ "SELECT ?e ?c ?l ?o " //
				+ "{" //
				+ "  ?e a ?c . "//
				+ "  ?e <http://www.w3.org/2000/01/rdf-schema#label> ?l . "//
				+ "  ?e <uri:talksTo> ?o . "//
				+ "}";//

		final CountingResultHandler crh1 = new CountingResultHandler();
		final CountingResultHandler crh2 = new CountingResultHandler();

		conn.prepareTupleQuery(QueryLanguage.SPARQL, queryString)
				.evaluate(crh1);

		pcjConn.prepareTupleQuery(QueryLanguage.SPARQL, queryString).evaluate(
				crh2);

		Assert.assertEquals(crh1.getCount(), crh2.getCount());

	}

	@Test
	public void testEvaluateTwoIndexTwoVarOrder1()
			throws TupleQueryResultHandlerException, QueryEvaluationException,
			MalformedQueryException, RepositoryException, AccumuloException,
			AccumuloSecurityException, TableExistsException, PcjException,
			SailException, TableNotFoundException {

		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));

		final String indexSparqlString = ""//
				+ "SELECT ?e ?l ?c " //
				+ "{" //
				+ "  ?e a ?c . "//
				+ "  ?e <http://www.w3.org/2000/01/rdf-schema#label> ?l "//
				+ "}";//

		final String indexSparqlString2 = ""//
				+ "SELECT ?e ?o ?l " //
				+ "{" //
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l "//
				+ "}";//

		final String queryString = ""//
				+ "SELECT ?e ?c ?l ?o " //
				+ "{" //
				+ "  ?e a ?c . "//
				+ "  ?e <http://www.w3.org/2000/01/rdf-schema#label> ?l . "//
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l "//
				+ "}";//

		final CountingResultHandler crh1 = new CountingResultHandler();
		final CountingResultHandler crh2 = new CountingResultHandler();

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename + 1,
				indexSparqlString, new String[] { "e", "l", "c" },
				Optional.absent());

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename + 2,
				indexSparqlString2, new String[] { "e", "o", "l" },
				Optional.absent());

		conn.prepareTupleQuery(QueryLanguage.SPARQL, queryString)
				.evaluate(crh1);
		PcjIntegrationTestingUtil.deleteCoreRyaTables(accCon, prefix);
		pcjConn.prepareTupleQuery(QueryLanguage.SPARQL, queryString).evaluate(
				crh2);

		Assert.assertEquals(2, crh1.getCount());
		Assert.assertEquals(2, crh2.getCount());

	}

	@Test
	public void testEvaluateTwoIndexTwoVarOrder2() throws PcjException,
			RepositoryException, TupleQueryResultHandlerException,
			QueryEvaluationException, MalformedQueryException,
			AccumuloException, AccumuloSecurityException, TableExistsException,
			SailException, TableNotFoundException {

		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));

		final String indexSparqlString = ""//
				+ "SELECT ?c ?e ?l  " //
				+ "{" //
				+ "  ?e a ?c . "//
				+ "  ?e <http://www.w3.org/2000/01/rdf-schema#label> ?l "//
				+ "}";//

		final String indexSparqlString2 = ""//
				+ "SELECT ?e ?o ?l " //
				+ "{" //
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l "//
				+ "}";//

		final String queryString = ""//
				+ "SELECT ?e ?c ?l ?o " //
				+ "{" //
				+ "  ?e a ?c . "//
				+ "  ?e <http://www.w3.org/2000/01/rdf-schema#label> ?l . "//
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l "//
				+ "}";//

		final CountingResultHandler crh1 = new CountingResultHandler();
		final CountingResultHandler crh2 = new CountingResultHandler();

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename + 1,
				indexSparqlString, new String[] { "c", "e", "l" },
				Optional.absent());

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename + 2,
				indexSparqlString2, new String[] { "e", "o", "l" },
				Optional.absent());

		conn.prepareTupleQuery(QueryLanguage.SPARQL, queryString)
				.evaluate(crh1);
		PcjIntegrationTestingUtil.deleteCoreRyaTables(accCon, prefix);
		pcjConn.prepareTupleQuery(QueryLanguage.SPARQL, queryString).evaluate(
				crh2);

		Assert.assertEquals(2, crh1.getCount());
		Assert.assertEquals(2, crh2.getCount());

	}

	@Test
	public void testEvaluateTwoIndexTwoVarInvalidOrder() throws PcjException,
			RepositoryException, AccumuloException, AccumuloSecurityException,
			TableExistsException, TupleQueryResultHandlerException,
			QueryEvaluationException, MalformedQueryException, SailException,
			TableNotFoundException {

		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));

		final String indexSparqlString = ""//
				+ "SELECT ?e ?c ?l  " //
				+ "{" //
				+ "  ?e a ?c . "//
				+ "  ?e <http://www.w3.org/2000/01/rdf-schema#label> ?l "//
				+ "}";//

		final String indexSparqlString2 = ""//
				+ "SELECT ?e ?o ?l " //
				+ "{" //
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l "//
				+ "}";//

		final String queryString = ""//
				+ "SELECT ?e ?c ?l ?o " //
				+ "{" //
				+ "  ?e a ?c . "//
				+ "  ?e <http://www.w3.org/2000/01/rdf-schema#label> ?l . "//
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l "//
				+ "}";//

		final CountingResultHandler crh1 = new CountingResultHandler();
		final CountingResultHandler crh2 = new CountingResultHandler();

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename + 1,
				indexSparqlString, new String[] { "e", "c", "l" },
				Optional.absent());

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename + 2,
				indexSparqlString2, new String[] { "e", "o", "l" },
				Optional.absent());

		conn.prepareTupleQuery(QueryLanguage.SPARQL, queryString)
				.evaluate(crh1);
		PcjIntegrationTestingUtil.deleteCoreRyaTables(accCon, prefix);
		pcjConn.prepareTupleQuery(QueryLanguage.SPARQL, queryString).evaluate(
				crh2);

		Assert.assertEquals(crh1.getCount(), crh2.getCount());

	}

	@Test
	public void testEvaluateTwoIndexThreeVarOrder1()
			throws TupleQueryResultHandlerException, QueryEvaluationException,
			MalformedQueryException, RepositoryException, AccumuloException,
			AccumuloSecurityException, TableExistsException,
			TableNotFoundException, PcjException, SailException {

		final TableOperations ops = accCon.tableOperations();
		final Set<String> tables = ops.tableIdMap().keySet();
		final Collection<String> vals = ops.tableIdMap().values();
		System.out.println("Tables: " + tables + "and values " + vals);

		final IRI superclass = VF.createIRI("uri:superclass");
		final IRI superclass2 = VF.createIRI("uri:superclass2");

		conn.add(subclass, RDF.TYPE, superclass);
		conn.add(subclass2, RDF.TYPE, superclass2);
		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));
		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));

		final String indexSparqlString = ""//
				+ "SELECT ?c ?e ?l  " //
				+ "{" //
				+ "  ?e a ?c . "//
				+ "  ?e <http://www.w3.org/2000/01/rdf-schema#label> ?l "//
				+ "}";//

		final String indexSparqlString2 = ""//
				+ "SELECT ?e ?c ?l ?f ?o" //
				+ "{" //
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?c a ?f . " //
				+ "}";//

		final String queryString = ""//
				+ "SELECT ?e ?c ?l ?f ?o " //
				+ "{" //
				+ "  ?e a ?c . "//
				+ "  ?e <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?c a ?f . " //
				+ "}";//

		final CountingResultHandler crh1 = new CountingResultHandler();
		final CountingResultHandler crh2 = new CountingResultHandler();

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename + 1,
				indexSparqlString, new String[] { "c", "e", "l" },
				Optional.absent());

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename + 2,
				indexSparqlString2, new String[] { "e", "c", "l", "f", "o" },
				Optional.absent());

		conn.prepareTupleQuery(QueryLanguage.SPARQL, queryString)
				.evaluate(crh1);
		PcjIntegrationTestingUtil.deleteCoreRyaTables(accCon, prefix);
		pcjConn.prepareTupleQuery(QueryLanguage.SPARQL, queryString)
				.evaluate(crh2);

		Assert.assertEquals(2, crh1.getCount());
		Assert.assertEquals(2, crh2.getCount());
	}

	 @Test
	public void testEvaluateTwoIndexThreeVarsDiffLabel() throws PcjException,
			RepositoryException, AccumuloException, AccumuloSecurityException,
			TableNotFoundException, TableExistsException,
			MalformedQueryException, SailException, QueryEvaluationException,
			TupleQueryResultHandlerException {

		final IRI superclass = VF.createIRI("uri:superclass");
		final IRI superclass2 = VF.createIRI("uri:superclass2");

		conn.add(subclass, RDF.TYPE, superclass);
		conn.add(subclass2, RDF.TYPE, superclass2);
		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));
		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));

		final String indexSparqlString = ""//
				+ "SELECT ?dog ?pig ?owl  " //
				+ "{" //
				+ "  ?pig a ?dog . "//
				+ "  ?pig <http://www.w3.org/2000/01/rdf-schema#label> ?owl "//
				+ "}";//

		final String indexSparqlString2 = ""//
				+ "SELECT ?e ?c ?l ?f ?o" //
				+ "{" //
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?c a ?f . " //
				+ "}";//

		final String queryString = ""//
				+ "SELECT ?e ?c ?l ?f ?o " //
				+ "{" //
				+ "  ?e a ?c . "//
				+ "  ?e <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?c a ?f . " //
				+ "}";//

		final CountingResultHandler crh2 = new CountingResultHandler();

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename + 1,
				indexSparqlString, new String[] { "dog", "pig", "owl" },
				Optional.absent());

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename + 2,
				indexSparqlString2, new String[] { "e", "c", "l", "f", "o" },
				Optional.absent());
		PcjIntegrationTestingUtil.deleteCoreRyaTables(accCon, prefix);
		pcjConn.prepareTupleQuery(QueryLanguage.SPARQL, queryString).evaluate(
				crh2);

		Assert.assertEquals(2, crh2.getCount());

	}

	@Test
	public void testEvaluateTwoIndexThreeVarOrder2() throws PcjException,
			RepositoryException, AccumuloException, AccumuloSecurityException,
			TableExistsException, TableNotFoundException,
			TupleQueryResultHandlerException, QueryEvaluationException,
			MalformedQueryException, SailException {

		final IRI superclass = VF.createIRI("uri:superclass");
		final IRI superclass2 = VF.createIRI("uri:superclass2");

		conn.add(subclass, RDF.TYPE, superclass);
		conn.add(subclass2, RDF.TYPE, superclass2);
		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));
		accCon.tableOperations().create("table2");
		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));

		final String indexSparqlString = ""//
				+ "SELECT ?c ?e ?l  " //
				+ "{" //
				+ "  ?e a ?c . "//
				+ "  ?e <http://www.w3.org/2000/01/rdf-schema#label> ?l "//
				+ "}";//

		final String indexSparqlString2 = ""//
				+ "SELECT ?o ?f ?e ?c ?l  " //
				+ "{" //
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?c a ?f . " //
				+ "}";//

		final String queryString = ""//
				+ "SELECT ?e ?c ?l ?f ?o " //
				+ "{" //
				+ "  ?e a ?c . "//
				+ "  ?e <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?c a ?f . " //
				+ "}";//

		final CountingResultHandler crh2 = new CountingResultHandler();

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename + 1,
				indexSparqlString, new String[] { "c", "e", "l" },
				Optional.absent());

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename + 2,
				indexSparqlString2, new String[] { "o", "f", "e", "c", "l" },
				Optional.absent());
		PcjIntegrationTestingUtil.deleteCoreRyaTables(accCon, prefix);
		pcjConn.prepareTupleQuery(QueryLanguage.SPARQL, queryString).evaluate(
				crh2);

		Assert.assertEquals(2, crh2.getCount());

	}

	@Test
	public void testEvaluateTwoIndexThreeVarOrder3ThreeBindingSet()
			throws TupleQueryResultHandlerException, QueryEvaluationException,
			MalformedQueryException, RepositoryException, AccumuloException,
			AccumuloSecurityException, TableNotFoundException,
			TableExistsException, PcjException, SailException {

		final IRI sub3 = VF.createIRI("uri:entity3");
		final IRI subclass3 = VF.createIRI("uri:class3");
		final IRI obj3 = VF.createIRI("uri:obj3");
		final IRI superclass = VF.createIRI("uri:superclass");
		final IRI superclass2 = VF.createIRI("uri:superclass2");
		final IRI superclass3 = VF.createIRI("uri:superclass3");

		conn.add(sub3, RDF.TYPE, subclass3);
		conn.add(sub3, RDFS.LABEL, VF.createLiteral("label3"));
		conn.add(sub3, talksTo, obj3);
		conn.add(subclass, RDF.TYPE, superclass);
		conn.add(subclass2, RDF.TYPE, superclass2);
		conn.add(subclass3, RDF.TYPE, superclass3);
		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));
		conn.add(obj3, RDFS.LABEL, VF.createLiteral("label3"));
		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));
		final String indexSparqlString = ""//
				+ "SELECT ?c ?e ?l  " //
				+ "{" //
				+ "  ?e a ?c . "//
				+ "  ?e <http://www.w3.org/2000/01/rdf-schema#label> ?l "//
				+ "}";//

		final String indexSparqlString2 = ""//
				+ "SELECT ?o ?f ?l ?e ?c  " //
				+ "{" //
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?c a ?f . " //
				+ "}";//

		final String queryString = ""//
				+ "SELECT ?e ?c ?l ?f ?o " //
				+ "{" //
				+ "  ?e a ?c . "//
				+ "  ?e <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?c a ?f . " //
				+ "}";//

		final CountingResultHandler crh1 = new CountingResultHandler();
		final CountingResultHandler crh2 = new CountingResultHandler();
		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename + 1,
				indexSparqlString, new String[] { "c", "e", "l" },
				Optional.absent());

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename + 2,
				indexSparqlString2, new String[] { "o", "f", "l", "e", "c" },
				Optional.absent());

		conn.prepareTupleQuery(QueryLanguage.SPARQL, queryString)
				.evaluate(crh1);
		PcjIntegrationTestingUtil.deleteCoreRyaTables(accCon, prefix);
		pcjConn.prepareTupleQuery(QueryLanguage.SPARQL, queryString).evaluate(
				crh2);
		Assert.assertEquals(3, crh1.getCount());
		Assert.assertEquals(3, crh2.getCount());

	}

	@Test
	public void testEvaluateTwoIndexThreeVarOrder5ThreeBindingSet()
			throws TupleQueryResultHandlerException, QueryEvaluationException,
			MalformedQueryException, RepositoryException, AccumuloException,
			AccumuloSecurityException, TableNotFoundException,
			TableExistsException, PcjException, SailException {

		final IRI sub3 = VF.createIRI("uri:entity3");
		final IRI subclass3 = VF.createIRI("uri:class3");
		final IRI obj3 = VF.createIRI("uri:obj3");

		final IRI superclass = VF.createIRI("uri:superclass");
		final IRI superclass2 = VF.createIRI("uri:superclass2");
		final IRI superclass3 = VF.createIRI("uri:superclass3");

		conn.add(sub3, RDF.TYPE, subclass3);
		conn.add(sub3, RDFS.LABEL, VF.createLiteral("label3"));
		conn.add(sub3, talksTo, obj3);
		conn.add(subclass, RDF.TYPE, superclass);
		conn.add(subclass2, RDF.TYPE, superclass2);
		conn.add(subclass3, RDF.TYPE, superclass3);
		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));
		conn.add(obj3, RDFS.LABEL, VF.createLiteral("label3"));
		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));
		final String indexSparqlString = ""//
				+ "SELECT ?c ?e ?l  " //
				+ "{" //
				+ "  ?e a ?c . "//
				+ "  ?e <http://www.w3.org/2000/01/rdf-schema#label> ?l "//
				+ "}";//

		final String indexSparqlString2 = ""//
				+ "SELECT ?o ?f ?e ?l ?c  " //
				+ "{" //
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?c a ?f . " //
				+ "}";//

		final String queryString = ""//
				+ "SELECT ?e ?c ?l ?f ?o " //
				+ "{" //
				+ "  ?e a ?c . "//
				+ "  ?e <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?c a ?f . " //
				+ "}";//

		final CountingResultHandler crh1 = new CountingResultHandler();
		final CountingResultHandler crh2 = new CountingResultHandler();

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename + 1,
				indexSparqlString, new String[] { "c", "e", "l" },
				Optional.absent());

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename + 2,
				indexSparqlString2, new String[] { "o", "f", "e", "l", "c" },
				Optional.absent());

		conn.prepareTupleQuery(QueryLanguage.SPARQL, queryString)
				.evaluate(crh1);
		PcjIntegrationTestingUtil.deleteCoreRyaTables(accCon, prefix);
		pcjConn.prepareTupleQuery(QueryLanguage.SPARQL, queryString).evaluate(
				crh2);

		Assert.assertEquals(3, crh1.getCount());
		Assert.assertEquals(3, crh2.getCount());

	}

	@Test
	public void testEvaluateTwoIndexThreeVarOrder4ThreeBindingSet()
			throws PcjException, RepositoryException, AccumuloException,
			AccumuloSecurityException, TableExistsException,
			TableNotFoundException, TupleQueryResultHandlerException,
			QueryEvaluationException, MalformedQueryException, SailException {

		final IRI sub3 = VF.createIRI("uri:entity3");
		final IRI subclass3 = VF.createIRI("uri:class3");
		final IRI obj3 = VF.createIRI("uri:obj3");

		final IRI superclass = VF.createIRI("uri:superclass");
		final IRI superclass2 = VF.createIRI("uri:superclass2");
		final IRI superclass3 = VF.createIRI("uri:superclass3");

		conn.add(sub3, RDF.TYPE, subclass3);
		conn.add(sub3, RDFS.LABEL, VF.createLiteral("label3"));
		conn.add(sub3, talksTo, obj3);
		conn.add(subclass, RDF.TYPE, superclass);
		conn.add(subclass2, RDF.TYPE, superclass2);
		conn.add(subclass3, RDF.TYPE, superclass3);
		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));
		conn.add(obj3, RDFS.LABEL, VF.createLiteral("label3"));
		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));

		final String indexSparqlString = ""//
				+ "SELECT ?c ?e ?l  " //
				+ "{" //
				+ "  ?e a ?c . "//
				+ "  ?e <http://www.w3.org/2000/01/rdf-schema#label> ?l "//
				+ "}";//

		final String indexSparqlString2 = ""//
				+ "SELECT ?o ?f ?c ?e ?l  " //
				+ "{" //
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?c a ?f . " //
				+ "}";//

		final String queryString = ""//
				+ "SELECT ?e ?c ?l ?f ?o " //
				+ "{" //
				+ "  ?e a ?c . "//
				+ "  ?e <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?c a ?f . " //
				+ "}";//

		final CountingResultHandler crh1 = new CountingResultHandler();
		final CountingResultHandler crh2 = new CountingResultHandler();

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename + 1,
				indexSparqlString, new String[] { "c", "e", "l" },
				Optional.absent());

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename + 2,
				indexSparqlString2, new String[] { "o", "f", "c", "e", "l" },
				Optional.absent());

		conn.prepareTupleQuery(QueryLanguage.SPARQL, queryString)
				.evaluate(crh1);
		PcjIntegrationTestingUtil.deleteCoreRyaTables(accCon, prefix);
		pcjConn.prepareTupleQuery(QueryLanguage.SPARQL, queryString).evaluate(
				crh2);

		Assert.assertEquals(3, crh1.getCount());
		Assert.assertEquals(3, crh2.getCount());

	}

	@Test
	public void testEvaluateTwoIndexThreeVarOrder6ThreeBindingSet()
			throws MalformedQueryException, RepositoryException,
			AccumuloException, AccumuloSecurityException, TableExistsException,
			TableNotFoundException, PcjException,
			TupleQueryResultHandlerException, QueryEvaluationException,
			SailException {

		final IRI sub3 = VF.createIRI("uri:entity3");
		final IRI subclass3 = VF.createIRI("uri:class3");
		final IRI obj3 = VF.createIRI("uri:obj3");

		final IRI superclass = VF.createIRI("uri:superclass");
		final IRI superclass2 = VF.createIRI("uri:superclass2");
		final IRI superclass3 = VF.createIRI("uri:superclass3");

		conn.add(sub3, RDF.TYPE, subclass3);
		conn.add(sub3, RDFS.LABEL, VF.createLiteral("label3"));
		conn.add(sub3, talksTo, obj3);
		conn.add(subclass, RDF.TYPE, superclass);
		conn.add(subclass2, RDF.TYPE, superclass2);
		conn.add(subclass3, RDF.TYPE, superclass3);
		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));
		conn.add(obj3, RDFS.LABEL, VF.createLiteral("label3"));
		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));
		final String indexSparqlString = ""//
				+ "SELECT ?c ?e ?l  " //
				+ "{" //
				+ "  ?e a ?c . "//
				+ "  ?e <http://www.w3.org/2000/01/rdf-schema#label> ?l "//
				+ "}";//

		final String indexSparqlString2 = ""//
				+ "SELECT ?c ?l ?e ?o ?f " //
				+ "{" //
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?c a ?f . " //
				+ "}";//

		final String queryString = ""//
				+ "SELECT ?e ?c ?l ?f ?o " //
				+ "{" //
				+ "  ?e a ?c . "//
				+ "  ?e <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?c a ?f . " //
				+ "}";//

		final CountingResultHandler crh1 = new CountingResultHandler();
		final CountingResultHandler crh2 = new CountingResultHandler();

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename + 1,
				indexSparqlString, new String[] { "c", "e", "l" },
				Optional.absent());

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename + 2,
				indexSparqlString2, new String[] { "c", "l", "e", "o", "f" },
				Optional.absent());

		conn.prepareTupleQuery(QueryLanguage.SPARQL, queryString)
				.evaluate(crh1);
		PcjIntegrationTestingUtil.deleteCoreRyaTables(accCon, prefix);
		pcjConn.prepareTupleQuery(QueryLanguage.SPARQL, queryString).evaluate(
				crh2);

		Assert.assertEquals(3, crh1.getCount());
		Assert.assertEquals(3, crh2.getCount());

	}

	@Test
	public void testEvaluateTwoIndexThreeVarOrder7ThreeBindingSet()
			throws PcjException, RepositoryException, AccumuloException,
			AccumuloSecurityException, TableExistsException,
			TableNotFoundException, TupleQueryResultHandlerException,
			QueryEvaluationException, MalformedQueryException, SailException {

		final IRI sub3 = VF.createIRI("uri:entity3");
		final IRI subclass3 = VF.createIRI("uri:class3");
		final IRI obj3 = VF.createIRI("uri:obj3");

		final IRI superclass = VF.createIRI("uri:superclass");
		final IRI superclass2 = VF.createIRI("uri:superclass2");
		final IRI superclass3 = VF.createIRI("uri:superclass3");

		conn.add(sub3, RDF.TYPE, subclass3);
		conn.add(sub3, RDFS.LABEL, VF.createLiteral("label3"));
		conn.add(sub3, talksTo, obj3);
		conn.add(subclass, RDF.TYPE, superclass);
		conn.add(subclass2, RDF.TYPE, superclass2);
		conn.add(subclass3, RDF.TYPE, superclass3);
		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));
		conn.add(obj3, RDFS.LABEL, VF.createLiteral("label3"));
		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));
		final String indexSparqlString = ""//
				+ "SELECT ?c ?e ?l  " //
				+ "{" //
				+ "  ?e a ?c . "//
				+ "  ?e <http://www.w3.org/2000/01/rdf-schema#label> ?l "//
				+ "}";//

		final String indexSparqlString2 = ""//
				+ "SELECT ?o ?l ?c ?e ?f " //
				+ "{" //
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?c a ?f . " //
				+ "}";//

		final String queryString = ""//
				+ "SELECT ?e ?c ?l ?f ?o " //
				+ "{" //
				+ "  ?e a ?c . "//
				+ "  ?e <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?c a ?f . " //
				+ "}";//

		final CountingResultHandler crh1 = new CountingResultHandler();
		final CountingResultHandler crh2 = new CountingResultHandler();

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename + 1,
				indexSparqlString, new String[] { "c", "e", "l" },
				Optional.absent());

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename + 2,
				indexSparqlString2, new String[] { "o", "l", "c", "e", "f" },
				Optional.absent());

		conn.prepareTupleQuery(QueryLanguage.SPARQL, queryString)
				.evaluate(crh1);
		PcjIntegrationTestingUtil.deleteCoreRyaTables(accCon, prefix);
		pcjConn.prepareTupleQuery(QueryLanguage.SPARQL, queryString).evaluate(
				crh2);
		Assert.assertEquals(3, crh1.getCount());
		Assert.assertEquals(3, crh2.getCount());

	}

	@Test
	public void testEvaluateTwoIndexThreeVarInvalidOrder1()
			throws TupleQueryResultHandlerException, QueryEvaluationException,
			MalformedQueryException, RepositoryException, AccumuloException,
			AccumuloSecurityException, TableExistsException,
			TableNotFoundException, PcjException, SailException {

		final IRI superclass = VF.createIRI("uri:superclass");
		final IRI superclass2 = VF.createIRI("uri:superclass2");

		conn.add(subclass, RDF.TYPE, superclass);
		conn.add(subclass2, RDF.TYPE, superclass2);
		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));
		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));

		final String indexSparqlString = ""//
				+ "SELECT ?c ?e ?l  " //
				+ "{" //
				+ "  ?e a ?c . "//
				+ "  ?e <http://www.w3.org/2000/01/rdf-schema#label> ?l "//
				+ "}";//

		final String indexSparqlString2 = ""//
				+ "SELECT ?e ?o ?f ?c ?l  " //
				+ "{" //
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?c a ?f . " //
				+ "}";//

		final String queryString = ""//
				+ "SELECT ?e ?c ?l ?f ?o " //
				+ "{" //
				+ "  ?e a ?c . "//
				+ "  ?e <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?c a ?f . " //
				+ "}";//

		final CountingResultHandler crh1 = new CountingResultHandler();
		final CountingResultHandler crh2 = new CountingResultHandler();

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename + 1,
				indexSparqlString, new String[] { "c", "e", "l" },
				Optional.absent());

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename + 2,
				indexSparqlString2, new String[] { "e", "o", "f", "c", "l" },
				Optional.absent());

		conn.prepareTupleQuery(QueryLanguage.SPARQL, queryString)
				.evaluate(crh1);
		PcjIntegrationTestingUtil.deleteCoreRyaTables(accCon, prefix);
		pcjConn.prepareTupleQuery(QueryLanguage.SPARQL, queryString).evaluate(
				crh2);
		Assert.assertEquals(crh1.getCount(), crh2.getCount());

	}


	@Test
	public void testEvaluateOneIndex() throws PcjException,
			RepositoryException, AccumuloException, AccumuloSecurityException,
			TableExistsException, TableNotFoundException,
			MalformedQueryException, SailException, QueryEvaluationException,
			TupleQueryResultHandlerException {

		final IRI superclass = VF.createIRI("uri:superclass");
		final IRI superclass2 = VF.createIRI("uri:superclass2");

		conn.add(subclass, RDF.TYPE, superclass);
		conn.add(subclass2, RDF.TYPE, superclass2);
		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));
		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));

		final String indexSparqlString = ""//
				+ "SELECT ?dog ?pig ?duck  " //
				+ "{" //
				+ "  ?pig a ?dog . "//
				+ "  ?pig <http://www.w3.org/2000/01/rdf-schema#label> ?duck "//
				+ "}";//

		final CountingResultHandler crh1 = new CountingResultHandler();
		final CountingResultHandler crh2 = new CountingResultHandler();

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename + 1,
				indexSparqlString, new String[] { "dog", "pig", "duck" },
				Optional.absent());

		conn.prepareTupleQuery(QueryLanguage.SPARQL, indexSparqlString)
				.evaluate(crh1);
		PcjIntegrationTestingUtil.deleteCoreRyaTables(accCon, prefix);
		pcjConn.prepareTupleQuery(QueryLanguage.SPARQL, indexSparqlString)
				.evaluate(crh2);

		Assert.assertEquals(crh1.count, crh2.count);

	}

	@Test
	public void testEvaluateTwoIndexThreeVarOrder3() throws PcjException,
			RepositoryException, AccumuloException, AccumuloSecurityException,
			TableNotFoundException, TableExistsException,
			TupleQueryResultHandlerException, QueryEvaluationException,
			MalformedQueryException, SailException {

		final IRI superclass = VF.createIRI("uri:superclass");
		final IRI superclass2 = VF.createIRI("uri:superclass2");

		conn.add(subclass, RDF.TYPE, superclass);
		conn.add(subclass2, RDF.TYPE, superclass2);
		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));
		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));

		final String indexSparqlString = ""//
				+ "SELECT ?dog ?pig ?duck  " //
				+ "{" //
				+ "  ?pig a ?dog . "//
				+ "  ?pig <http://www.w3.org/2000/01/rdf-schema#label> ?duck "//
				+ "}";//

		final String indexSparqlString2 = ""//
				+ "SELECT ?o ?f ?e ?c ?l  " //
				+ "{" //
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?c a ?f . " //
				+ "}";//

		final String queryString = ""//
				+ "SELECT ?e ?c ?l ?f ?o " //
				+ "{" //
				+ "  ?e a ?c . "//
				+ "  ?e <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?c a ?f . " //
				+ "}";//

		final CountingResultHandler crh1 = new CountingResultHandler();
		final CountingResultHandler crh2 = new CountingResultHandler();

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename + 1,
				indexSparqlString, new String[] { "dog", "pig", "duck" },
				Optional.absent());

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename + 2,
				indexSparqlString2, new String[] { "o", "f", "e", "c", "l" },
				Optional.absent());

		conn.prepareTupleQuery(QueryLanguage.SPARQL, queryString)
				.evaluate(crh1);
		PcjIntegrationTestingUtil.deleteCoreRyaTables(accCon, prefix);
		pcjConn.prepareTupleQuery(QueryLanguage.SPARQL, queryString).evaluate(
				crh2);
		Assert.assertEquals(crh1.getCount(), crh2.getCount());

	}

	@Test
	public void testSupportedVarOrders1() throws PcjException,
			RepositoryException, AccumuloException, AccumuloSecurityException,
			TableNotFoundException, TableExistsException,
			MalformedQueryException, SailException, QueryEvaluationException {

		final IRI superclass = VF.createIRI("uri:superclass");
		final IRI superclass2 = VF.createIRI("uri:superclass2");

		conn.add(subclass, RDF.TYPE, superclass);
		conn.add(subclass2, RDF.TYPE, superclass2);
		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));
		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));

		final String indexSparqlString = ""//
				+ "SELECT ?dog ?pig ?duck  " //
				+ "{" //
				+ "  ?pig a ?dog . "//
				+ "  ?pig <http://www.w3.org/2000/01/rdf-schema#label> ?duck "//
				+ "}";//

		final String indexSparqlString2 = ""//
				+ "SELECT ?o ?f ?e ?c ?l  " //
				+ "{" //
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?c a ?f . " //
				+ "}";//


		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename + 1,
				indexSparqlString, new String[] { "dog", "pig", "duck" },
				Optional.absent());

		final AccumuloIndexSet ais1 = new AccumuloIndexSet(conf,
				tablename + 1);

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename + 2,
				indexSparqlString2, new String[] { "o", "f", "e", "c", "l" },
				Optional.absent());

		final AccumuloIndexSet ais2 = new AccumuloIndexSet(conf,
				tablename + 2);

		final Set<String> ais1Set1 = Sets.newHashSet();
		ais1Set1.add("dog");

		Assert.assertTrue(ais1.supportsBindingSet(ais1Set1));
		ais1Set1.add("duck");

		Assert.assertTrue(ais1.supportsBindingSet(ais1Set1));

		ais1Set1.add("chicken");

		Assert.assertTrue(ais1.supportsBindingSet(ais1Set1));

		final Set<String> ais2Set1 = Sets.newHashSet();
		ais2Set1.add("f");

		Assert.assertTrue(ais2.supportsBindingSet(ais2Set1));
		ais2Set1.add("e");

		Assert.assertTrue(ais2.supportsBindingSet(ais2Set1));

		ais2Set1.add("o");

		Assert.assertTrue(ais2.supportsBindingSet(ais2Set1));

		ais2Set1.add("l");

		Assert.assertTrue(ais2.supportsBindingSet(ais2Set1));

		final Set<String> ais2Set2 = Sets.newHashSet();
		ais2Set2.add("f");

		Assert.assertTrue(ais2.supportsBindingSet(ais2Set2));

		ais2Set2.add("o");

		Assert.assertTrue(ais2.supportsBindingSet(ais2Set2));

		ais2Set2.add("c");

		Assert.assertTrue(!ais2.supportsBindingSet(ais2Set2));

		final Set<String> ais2Set3 = Sets.newHashSet();
		ais2Set3.add("c");

		Assert.assertTrue(ais2.supportsBindingSet(ais2Set3));

		ais2Set3.add("e");

		Assert.assertTrue(ais2.supportsBindingSet(ais2Set3));

		ais2Set3.add("l");

		Assert.assertTrue(ais2.supportsBindingSet(ais2Set3));

	}

	@Test
	public void testEvaluateTwoIndexThreeVarOrder() throws PcjException,
			RepositoryException, AccumuloException, AccumuloSecurityException,
			TableNotFoundException, TableExistsException,
			TupleQueryResultHandlerException, QueryEvaluationException,
			MalformedQueryException, SailException {

		final IRI superclass = VF.createIRI("uri:superclass");
		final IRI superclass2 = VF.createIRI("uri:superclass2");

		conn.add(subclass, RDF.TYPE, superclass);
		conn.add(subclass2, RDF.TYPE, superclass2);
		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));
		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));

		final String indexSparqlString = ""//
				+ "SELECT ?dog ?pig ?duck  " //
				+ "{" //
				+ "  ?pig a ?dog . "//
				+ "  ?pig <http://www.w3.org/2000/01/rdf-schema#label> ?duck "//
				+ "}";//

		final String indexSparqlString2 = ""//
				+ "SELECT ?o ?f ?e ?c ?l  " //
				+ "{" //
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?c a ?f . " //
				+ "}";//

		final String queryString = ""//
				+ "SELECT ?e ?c ?l ?f ?o " //
				+ "{" //
				+ "  ?e a ?c . "//
				+ "  ?e <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?c a ?f . " //
				+ "}";//

		final CountingResultHandler crh1 = new CountingResultHandler();
		final CountingResultHandler crh2 = new CountingResultHandler();

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename+1,
				indexSparqlString, new String[] { "dog", "pig", "duck" },
				Optional.absent());

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename+2,
				indexSparqlString2, new String[] { "o", "f", "e", "c", "l" },
				Optional.absent());

		conn.prepareTupleQuery(QueryLanguage.SPARQL, queryString)
				.evaluate(crh1);
		PcjIntegrationTestingUtil.deleteCoreRyaTables(accCon, prefix);
		pcjConn.prepareTupleQuery(QueryLanguage.SPARQL, queryString).evaluate(
				crh2);
		Assert.assertEquals(crh1.getCount(), crh2.getCount());

	}

	@Test
	public void testEvaluateTwoIndexValidate() throws Exception {

		final IRI superclass = VF.createIRI("uri:superclass");
		final IRI superclass2 = VF.createIRI("uri:superclass2");

		conn.add(subclass, RDF.TYPE, superclass);
		conn.add(subclass2, RDF.TYPE, superclass2);
		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));
		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));

		final String indexSparqlString = ""//
				+ "SELECT ?dog ?pig ?duck  " //
				+ "{" //
				+ "  ?pig a ?dog . "//
				+ "  ?pig <http://www.w3.org/2000/01/rdf-schema#label> ?duck "//
				+ "}";//

		final String indexSparqlString2 = ""//
				+ "SELECT ?o ?f ?e ?c ?l  " //
				+ "{" //
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?c a ?f . " //
				+ "}";//

		final String queryString = ""//
				+ "SELECT ?e ?c ?l ?f ?o " //
				+ "{" //
				+ "  ?e a ?c . "//
				+ "  ?e <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?c a ?f . " //
				+ "}";//

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename+1,
				indexSparqlString, new String[] { "dog", "pig", "duck" },
				Optional.absent());

		final AccumuloIndexSet ais1 = new AccumuloIndexSet(conf, tablename+1);

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename+2,
				indexSparqlString2, new String[] { "o", "f", "e", "c", "l" },
				Optional.absent());

		final AccumuloIndexSet ais2 = new AccumuloIndexSet(conf, tablename+2);

		final List<ExternalTupleSet> index = new ArrayList<>();
		index.add(ais1);
		index.add(ais2);

		ParsedQuery pq = null;
		final SPARQLParser sp = new SPARQLParser();
		pq = sp.parseQuery(queryString, null);
		final List<TupleExpr> teList = Lists.newArrayList();
		final TupleExpr te = pq.getTupleExpr();

		final PCJOptimizer pcj = new PCJOptimizer(index, false);
        pcj.optimize(te, null, null);
		teList.add(te);

		final IndexPlanValidator ipv = new IndexPlanValidator(false);

		Assert.assertTrue(ipv.isValid(te));

	}

	@Test
	public void testEvaluateThreeIndexValidate() throws Exception {

		final IRI superclass = VF.createIRI("uri:superclass");
		final IRI superclass2 = VF.createIRI("uri:superclass2");

		final IRI sub = VF.createIRI("uri:entity");
		subclass = VF.createIRI("uri:class");
		obj = VF.createIRI("uri:obj");
		talksTo = VF.createIRI("uri:talksTo");

		final IRI howlsAt = VF.createIRI("uri:howlsAt");
		final IRI subType = VF.createIRI("uri:subType");
		final IRI superSuperclass = VF.createIRI("uri:super_superclass");

		conn.add(subclass, RDF.TYPE, superclass);
		conn.add(subclass2, RDF.TYPE, superclass2);
		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));
		conn.add(sub, howlsAt, superclass);
		conn.add(superclass, subType, superSuperclass);
		conn.add(obj, RDFS.LABEL, VF.createLiteral("label"));
		conn.add(obj2, RDFS.LABEL, VF.createLiteral("label2"));

		final String indexSparqlString = ""//
				+ "SELECT ?dog ?pig ?duck  " //
				+ "{" //
				+ "  ?pig a ?dog . "//
				+ "  ?pig <http://www.w3.org/2000/01/rdf-schema#label> ?duck "//
				+ "}";//

		final String indexSparqlString2 = ""//
				+ "SELECT ?o ?f ?e ?c ?l  " //
				+ "{" //
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?c a ?f . " //
				+ "}";//

		final String indexSparqlString3 = ""//
				+ "SELECT ?wolf ?sheep ?chicken  " //
				+ "{" //
				+ "  ?wolf <uri:howlsAt> ?sheep . "//
				+ "  ?sheep <uri:subType> ?chicken. "//
				+ "}";//

		final String queryString = ""//
				+ "SELECT ?e ?c ?l ?f ?o " //
				+ "{" //
				+ "  ?e a ?c . "//
				+ "  ?e <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?e <uri:talksTo> ?o . "//
				+ "  ?o <http://www.w3.org/2000/01/rdf-schema#label> ?l. "//
				+ "  ?c a ?f . " //
				+ "  ?e <uri:howlsAt> ?f. "//
				+ "  ?f <uri:subType> ?o. "//
				+ "}";//

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename+1,
				indexSparqlString, new String[] { "dog", "pig", "duck" },
				Optional.absent());

		final AccumuloIndexSet ais1 = new AccumuloIndexSet(conf, tablename+1);

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename+2,
				indexSparqlString2, new String[] { "o", "f", "e", "c", "l" },
				Optional.absent());

		final AccumuloIndexSet ais2 = new AccumuloIndexSet(conf, tablename+2);

		PcjIntegrationTestingUtil.createAndPopulatePcj(conn, accCon, tablename+3,
				indexSparqlString3,
				new String[] { "wolf", "sheep", "chicken" },
				Optional.absent());

		final AccumuloIndexSet ais3 = new AccumuloIndexSet(conf, tablename+3);

		final List<ExternalTupleSet> index = new ArrayList<>();
		index.add(ais1);
		index.add(ais3);
		index.add(ais2);

		ParsedQuery pq = null;
		final SPARQLParser sp = new SPARQLParser();
		pq = sp.parseQuery(queryString, null);
		final List<TupleExpr> teList = Lists.newArrayList();
		final TupleExpr te = pq.getTupleExpr();

		final PCJOptimizer pcj = new PCJOptimizer(index, false);
        pcj.optimize(te, null, null);

		teList.add(te);

		final IndexPlanValidator ipv = new IndexPlanValidator(false);

		Assert.assertTrue(ipv.isValid(te));

	}

	public static class CountingResultHandler implements
			TupleQueryResultHandler {
		private int count = 0;

		public int getCount() {
			return count;
		}

		public void resetCount() {
			count = 0;
		}

		@Override
		public void startQueryResult(final List<String> arg0)
				throws TupleQueryResultHandlerException {
		}

		@Override
		public void handleSolution(final BindingSet arg0)
				throws TupleQueryResultHandlerException {
			count++;
			System.out.println(arg0);
		}

		@Override
		public void endQueryResult() throws TupleQueryResultHandlerException {
		}

		@Override
		public void handleBoolean(final boolean arg0)
				throws QueryResultHandlerException {

		}

		@Override
		public void handleLinks(final List<String> arg0)
				throws QueryResultHandlerException {

		}

	}

	private static Configuration getConf() {
		final AccumuloRdfConfiguration conf = new AccumuloRdfConfiguration();
		conf.setBoolean(ConfigUtils.USE_MOCK_INSTANCE, true);
		conf.set(RdfCloudTripleStoreConfiguration.CONF_TBL_PREFIX, "rya_");
		conf.set(ConfigUtils.CLOUDBASE_USER, "root");
		conf.set(ConfigUtils.CLOUDBASE_PASSWORD, "");
		conf.set(ConfigUtils.CLOUDBASE_INSTANCE, "instance");
		conf.set(ConfigUtils.CLOUDBASE_AUTHS, "");
		conf.set(PrecomputedJoinIndexerConfig.PCJ_STORAGE_TYPE,PrecomputedJoinStorageType.ACCUMULO.name());
		return conf;
	}

}
