/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sentry.tests.e2e.solr;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.commons.io.FileUtils;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.impl.CloudSolrServer;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.cloud.AbstractFullDistribZkTestBase;
import org.apache.solr.cloud.ZkController;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.servlet.SolrDispatchFilter;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractSolrSentryTestBase extends AbstractFullDistribZkTestBase {
  private static final Logger LOG = LoggerFactory.getLogger(AbstractSolrSentryTestBase.class);
  protected static final String SENTRY_ERROR_MSG = "401, message:Unauthorized";
  private static MiniDFSCluster dfsCluster;
  private static SortedMap<Class, String> extraRequestFilters;
  protected static final String ADMIN_USER = "admin";
  protected static final Random RANDOM = new Random();

  private static void addPropertyToSentry(StringBuilder builder, String name, String value) {
    builder.append("<property>\n");
    builder.append("<name>").append(name).append("</name>\n");
    builder.append("<value>").append(value).append("</value>\n");
    builder.append("</property>\n");
  }

  public static File setupSentry() throws Exception {
    File sentrySite = File.createTempFile("sentry-site", "xml");
    sentrySite.deleteOnExit();
    File authProviderDir = new File(SolrTestCaseJ4.TEST_HOME(), "sentry");
    String authProviderName = "test-authz-provider.ini";
    FileSystem clusterFs = dfsCluster.getFileSystem();
    clusterFs.copyFromLocalFile(false,
      new Path(authProviderDir.toString(), authProviderName),
      new Path(authProviderName));

    // need to write sentry-site at execution time because we don't know
    // the location of sentry.solr.provider.resource beforehand
    StringBuilder sentrySiteData = new StringBuilder();
    sentrySiteData.append("<configuration>\n");
    addPropertyToSentry(sentrySiteData, "sentry.provider",
      "org.apache.sentry.provider.file.LocalGroupResourceAuthorizationProvider");
    addPropertyToSentry(sentrySiteData, "sentry.solr.provider.resource",
       clusterFs.getWorkingDirectory() + File.separator + authProviderName);
    sentrySiteData.append("</configuration>\n");
    FileUtils.writeStringToFile(sentrySite,sentrySiteData.toString());
    return sentrySite;
  }

  @BeforeClass
  public static void beforeTestSimpleSolrEndToEnd() throws Exception {
    dfsCluster = HdfsTestUtil.setupClass(new File(TEMP_DIR,
      AbstractSolrSentryTestBase.class.getName() + "_"
        + System.currentTimeMillis()).getAbsolutePath());
    File sentrySite = setupSentry();
    System.setProperty("solr.authorization.sentry.site", sentrySite.toURI().toURL().toString().substring("file:".length()));
    System.setProperty("solr.hdfs.home", dfsCluster.getURI().toString() + "/solr");
    extraRequestFilters = new TreeMap<Class, String>(new Comparator<Class>() {
      // There's only one class, make this as simple as possible
      public int compare(Class o1, Class o2) {
        return 0;
      }

      public boolean equals(Object obj) {
        return true;
      }
    });
    extraRequestFilters.put(ModifiableUserAuthenticationFilter.class, "*");
  }

  @AfterClass
  public static void teardownClass() throws Exception {
    HdfsTestUtil.teardownClass(dfsCluster);
    System.clearProperty("solr.hdfs.home");
    System.clearProperty("solr.authorization.sentry.site");
    dfsCluster = null;
    extraRequestFilters = null;
  }

  @Before
  public void setupBeforeTest() throws Exception {
    System.setProperty("numShards", Integer.toString(sliceCount));
    System.setProperty("solr.xml.persist", "true");
    super.setUp();
  }

  @After
  public void tearDown() throws Exception {
    super.tearDown();
    System.clearProperty("numShards");
    System.clearProperty("solr.xml.persist");
  }

  @Override
  protected String getDataDir(String dataDir) throws IOException {
    return HdfsTestUtil.getDataDir(dfsCluster, dataDir);
  }

  @Override
  protected String getSolrXml() {
    return "solr-no-core.xml";
  }

  @Override
  protected String getCloudSolrConfig() {
    return "solrconfig.xml";
  }

  @Override
  public SortedMap<Class,String> getExtraRequestFilters() {
    return extraRequestFilters;
  }

  /**
   * Set the proper user in the Solr authentication filter
   * @param solrUser
   */
  protected void setAuthenticationUser(String solrUser) throws Exception {
    ModifiableUserAuthenticationFilter.setUser(solrUser);
  }

  /**
   * Function to return the user name based on the permissions provided.
   * @param collectionName - Name of the solr collection.
   * @param isQuery - Boolean that specifies query permission.
   * @param isUpdate - Boolean that specifies update permission.
   * @param isAll - Boolean that specifies all permission.
   * @return - String which represents the Solr username.
   */
  protected String getUsernameForPermissions(String collectionName,
                                             boolean isQuery,
                                             boolean isUpdate,
                                             boolean isAll) {
    StringBuilder finalStr = new StringBuilder();
    finalStr.append(collectionName);
    finalStr.append("_");
    StringBuilder permissions = new StringBuilder();
    if (isQuery) {
      permissions.append("q");
    }

    if (isUpdate) {
      permissions.append("u");
    }

    if (isAll) {
      permissions.append("a");
    }

    finalStr.append(permissions.toString());
    return finalStr.toString();
  }

  /**
   * Method to validate Solr update passes
   * @param solrUserName - User authenticated into Solr
   * @param collectionName - Name of the collection to which the data has to be updated
   * @param solrInputDoc - Instance of SolrInputDocument
   * @throws Exception
   */
  protected void verifyUpdatePass(String solrUserName,
                                   String collectionName,
                                   SolrInputDocument solrInputDoc)
                                   throws Exception {
    int originalSolrDocCount = getSolrDocs(collectionName).size();
    setAuthenticationUser(solrUserName);
    CloudSolrServer cloudSolrServer = getCloudSolrServer(collectionName);
    try {
      cloudSolrServer.add(solrInputDoc);
      cloudSolrServer.commit();
    } finally {
      cloudSolrServer.shutdown();
    }

    // Validate Solr content to check whether the update command went through.
    // Authenticate as user "admin"
    validateSolrDocCountAndContent(collectionName, originalSolrDocCount+1, solrInputDoc);
  }

  /**
   * Method to validate Solr update fails
   * @param solrUserName - User authenticated into Solr
   * @param collectionName - Name of the collection to which the data has to be updated
   * @param solrInputDoc - Instance of SolrInputDocument
   * @throws Exception
   */
  protected void verifyUpdateFail(String solrUserName,
                                   String collectionName,
                                   SolrInputDocument solrInputDoc)
                                   throws Exception {
    int originalSolrDocCount = getSolrDocs(collectionName).size();
    setAuthenticationUser(solrUserName);
    CloudSolrServer cloudSolrServer = getCloudSolrServer(collectionName);
    try {
      cloudSolrServer.add(solrInputDoc);
      cloudSolrServer.commit();
      fail("The specified user: " + solrUserName + " shouldn't get update access!");
    } catch (Exception exception) {
      assertTrue("Expected " + SENTRY_ERROR_MSG + " in " + exception.toString(),
          exception.toString().contains(SENTRY_ERROR_MSG));
    } finally {
      cloudSolrServer.shutdown();
    }

    // Validate Solr content to check whether the update command didn't go through.
    // Authenticate as user "admin"
    validateSolrDocCountAndContent(collectionName, originalSolrDocCount, null);
  }

  /**
   * Method to validate Solr deletedocs passes
   * (This function doesn't check if there is at least one Solr document present in Solr)
   * @param solrUserName - User authenticated into Solr
   * @param collectionName - Name of the collection to which the data has to be updated
   * @param allowZeroDocs - Boolean for running this method only if there is atleast one Solr doc present.
   * @throws MalformedURLException, SolrServerException, IOException
   */
  protected void verifyDeletedocsPass(String solrUserName,
                                   String collectionName, boolean allowZeroDocs)
                                   throws Exception {
    int originalSolrDocCount = getSolrDocs(collectionName).size();
    if (allowZeroDocs == false) {
      assertTrue("Solr should contain atleast one solr doc to run this test.", originalSolrDocCount > 0);
    }

    setAuthenticationUser(solrUserName);
    CloudSolrServer cloudSolrServer = getCloudSolrServer(collectionName);
    try {
      cloudSolrServer.deleteByQuery("*:*");
      cloudSolrServer.commit();
    } finally {
      cloudSolrServer.shutdown();
    }

    // Validate Solr content to check whether the update command didn't go through.
    // Authenticate as user "admin"
    validateSolrDocCountAndContent(collectionName, 0, null);
  }

  /**
   * Method to validate Solr deletedocs fails
   * (This function doesn't check if there is at least one Solr document present in Solr)
   * @param solrUserName - User authenticated into Solr
   * @param collectionName - Name of the collection to which the data has to be updated
   * @param allowZeroDocs - Boolean for running this method only if there is atleast one Solr doc present.
   * @throws Exception
   */
  protected void verifyDeletedocsFail(String solrUserName,
                                   String collectionName, boolean allowZeroDocs)
                                   throws Exception {
    int originalSolrDocCount = getSolrDocs(collectionName).size();
    if (allowZeroDocs == false) {
      assertTrue("Solr should contain atleast one solr doc to run this test.", originalSolrDocCount > 0);
    }

    setAuthenticationUser(solrUserName);
    CloudSolrServer cloudSolrServer = getCloudSolrServer(collectionName);
    try {
      cloudSolrServer.deleteByQuery("*:*");
      cloudSolrServer.commit();
      fail("The specified user: " + solrUserName + " shouldn't get deletedocs access!");
    } catch (Exception exception) {
      assertTrue("Expected " + SENTRY_ERROR_MSG + " in " + exception.toString(),
          exception.toString().contains(SENTRY_ERROR_MSG));
    } finally {
      cloudSolrServer.shutdown();
    }

    // Validate Solr content to check whether the deletedocs command didn't go through.
    // Authenticate as user "admin"
    validateSolrDocCountAndContent(collectionName, originalSolrDocCount, null);
  }

  /**
   * Function to verify whether Solr doc count matches the expected number and
   * also to verify if the Input document is present in present in the response.
   * @param collectionName - Name of the Solr collection
   * @param expectedDocCount - Count of expected Solr docs
   * @param solrInputDoc - Solr doc inserted into Solr
   * @throws Exception
   */
  public void validateSolrDocCountAndContent(String collectionName, int expectedDocCount, SolrInputDocument solrInputDoc)
                                   throws Exception {
    // Authenticate as user "admin"
    setAuthenticationUser(ADMIN_USER);
    SolrDocumentList solrRespDocs = getSolrDocs(collectionName);
    assertEquals("Expected: " + expectedDocCount + " Solr docs; But, found "
        + solrRespDocs.size() + " Solr docs.", solrRespDocs.size(), expectedDocCount);
      if (solrInputDoc != null) {
        validateSolrDocContent(solrInputDoc, solrRespDocs);
      }
  }

  /**
   * Function to query the collection and fetch the Solr docs
   * @param collectionName -  Name of the collection
   * @return -  Instance of SolrDocumentList
   * @throws Exception
   */
  protected SolrDocumentList getSolrDocs(String collectionName) throws Exception {
    // Authenticate as user "admin"
    setAuthenticationUser(ADMIN_USER);
    CloudSolrServer cloudSolrServer = getCloudSolrServer(collectionName);
    SolrDocumentList solrDocs = null;
    try {
      SolrQuery query = new SolrQuery("*:*");
      QueryResponse response = cloudSolrServer.query(query);
      solrDocs = response.getResults();
    } finally {
      cloudSolrServer.shutdown();
    }

    return solrDocs;
  }

  /**
   * Function to validate the content of Solr response with that of input document.
   * @param solrInputDoc - Solr doc inserted into Solr
   * @param solrRespDocs - List of Solr doc obtained as response
   * (NOTE: This function ignores "_version_" field in validating Solr doc content)
   */
  public void validateSolrDocContent(SolrInputDocument solrInputDoc, SolrDocumentList solrRespDocs) {
    solrInputDoc.removeField("_version_");
    for (SolrDocument solrRespDoc : solrRespDocs) {
      solrRespDoc.removeFields("_version_");
      String expFieldValue = (String) solrInputDoc.getFieldValue("id");
      String resFieldValue = (String) solrRespDoc.getFieldValue("id");
      if (expFieldValue.equals(resFieldValue)) {
        assertEquals("Expected " + solrInputDoc.size() + " fields. But, found "
            + solrRespDoc.size() + " fields", solrInputDoc.size() , solrRespDoc.size());
        for (String field : solrInputDoc.getFieldNames()) {
          expFieldValue = (String) solrInputDoc.getFieldValue(field);
          resFieldValue = (String) solrRespDoc.getFieldValue(field);
          assertEquals("Expected value for field: " + field + " is " + expFieldValue
              + "; But, found " + resFieldValue, expFieldValue, resFieldValue);
        }

        return;
      }
    }

    fail("Solr doc not found in Solr collection");
  }

  /**
   * Function to return the instance of CloudSolrServer for the collectionName specified
   * @param collectionName - Name of the collection
   * @return instance of CloudSolrServer
   * @throws MalformedURLException
   */
  protected CloudSolrServer getCloudSolrServer(String collectionName) throws MalformedURLException {
    CloudSolrServer cloudSolrServer = new CloudSolrServer(zkServer.getZkAddress(),
        random().nextBoolean());
    cloudSolrServer.setDefaultCollection(collectionName);
    cloudSolrServer.connect();
    return cloudSolrServer;
  }

  /**
   * Function to create a solr collection with the name passed as parameter
   * (Runs commands as ADMIN user)
   * @param collectionName - Name of the collection
   * @throws Exception
   */
  protected void setupCollection(String collectionName) throws Exception {
    // Authenticate as user "admin"
    setAuthenticationUser(ADMIN_USER);
    uploadConfigDirToZk(getSolrHome() + File.separator + DEFAULT_COLLECTION
      + File.separator + "conf");
    createCollection(collectionName, 1, 1, 1);
    waitForRecoveriesToFinish(collectionName, false);
  }

  /**
   * Function to clean Solr collections
   * @param collectionName - Name of the collection
   * @throws Exception
   */
  protected void cleanSolrCollection(String collectionName)
                                     throws Exception {
    verifyDeletedocsPass(ADMIN_USER, collectionName, true);
  }

  /**
   * Function to create a test Solrdoc with a random number as the ID
   * @throws Exception
   */
  protected SolrInputDocument createSolrTestDoc() throws Exception {
    SolrInputDocument solrInputDoc = new SolrInputDocument();
    String solrDocId = String.valueOf(RANDOM.nextInt());
    solrInputDoc.addField("id", solrDocId);
    solrInputDoc.addField("name", "testdoc" + solrDocId);
    return solrInputDoc;
  }

  /**
   * Load Solr collection with the SolrDocument passed.
   * @param collectionName - Name of the Solr collection
   * @param solrInputDoc - Solr document to be uploaded
   * (If solrInputDoc is null, then a test Solr doc will be uploaded)
   * @throws Exception
   */
  protected void uploadSolrDoc(String collectionName,
                                       SolrInputDocument solrInputDoc) throws Exception {
    if (solrInputDoc == null) {
      solrInputDoc = createSolrTestDoc();
    }

    verifyUpdatePass(ADMIN_USER, collectionName, solrInputDoc);
  }

  /**
   * Subclasses can override this to change a test's solr home
   * (default is in test-files)
   */
  public String getSolrHome() {
    return SolrTestCaseJ4.TEST_HOME();
  }

  protected void uploadConfigDirToZk(String collectionConfigDir) throws Exception {
    SolrDispatchFilter dispatchFilter =
      (SolrDispatchFilter) jettys.get(0).getDispatchFilter().getFilter();
    ZkController zkController = dispatchFilter.getCores().getZkController();
    // conf1 is the config used by AbstractFullDistribZkTestBase
    zkController.uploadConfigDir(new File(collectionConfigDir), "conf1");
  }
}
