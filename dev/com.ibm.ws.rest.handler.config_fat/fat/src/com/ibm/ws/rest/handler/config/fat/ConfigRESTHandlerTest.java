/*******************************************************************************
 * Copyright (c) 2017,2019 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.rest.handler.config.fat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import componenttest.annotation.Server;
import componenttest.custom.junit.runner.FATRunner;
import componenttest.topology.impl.LibertyServer;
import componenttest.topology.utils.FATServletClient;
import componenttest.topology.utils.HttpsRequest;

@RunWith(FATRunner.class)
public class ConfigRESTHandlerTest extends FATServletClient {
    @Server("com.ibm.ws.rest.handler.config.fat")
    public static LibertyServer server;

    @BeforeClass
    public static void setUp() throws Exception {
        // Install user features
        server.copyFileToLibertyInstallRoot("usr/extension/lib/features/", "features/nestedFlat-1.0.mf");

        // Install bundles for user features
        server.copyFileToLibertyInstallRoot("usr/extension/lib/", "bundles/test.config.nested.flat.jar");

        server.startServer();

        // Wait for the API to become available
        assertNotNull(server.waitForStringInLog("CWWKS0008I")); // CWWKS0008I: The security service is ready.
        assertNotNull(server.waitForStringInLog("CWWKS4105I")); // CWWKS4105I: LTPA configuration is ready after # seconds.
        assertNotNull(server.waitForStringInLog("CWPKI0803A")); // CWPKI0803A: SSL certificate created in # seconds. SSL key file: ...
        assertNotNull(server.waitForStringInLog("CWWKO0219I")); // CWWKO0219I: TCP Channel defaultHttpEndpoint-ssl has been started and is now listening for requests on host *  (IPv6) port 8020.
        assertNotNull(server.waitForStringInLog("CWWKT0016I")); // CWWKT0016I: Web application available (default_host): http://9.10.111.222:8010/ibm/api/

        // TODO remove once transactions code is fixed to use container auth for the recovery log dataSource
        // Lacking this fix, transaction manager will experience an auth failure and log FFDC for it.
        // The following line causes an XA-capable data source to be used for the first time outside of a test method execution,
        // so that the FFDC is not considered a test failure.
        new HttpsRequest(server, "/ibm/api/validator/dataSource/DefaultDataSource").run(JsonObject.class);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        try {
            server.stopServer("CWWKE0701E", // TODO remove once transaction manager fixes its circular reference bug
                              "CWWKS1300E", // auth alias doesn't exist
                              "WTRN0112E" // TODO remove once transactions code is fixed to use container auth for the recovery log dataSource
            );
        } finally {
            // Remove the user extension added during setup
            server.deleteDirectoryFromLibertyInstallRoot("usr/extension/");
        }
    }

    // Invoke /ibm/api/config REST API to display information for all configured instances.
    @Test
    public void testConfig() throws Exception {
        JsonArray json = new HttpsRequest(server, "/ibm/api/config").run(JsonArray.class);
        String err = "unexpected response: " + json;
        int count = json.size();
        assertTrue(err, count > 10);

        JsonArray json2 = new HttpsRequest(server, "/ibm/api/config/").run(JsonArray.class);
        int count2 = json2.size();
        assertEquals(count, count2);

        Map<String, JsonObject> allConfig = new HashMap<String, JsonObject>();
        for (JsonValue jv : json) {
            JsonObject jo = (JsonObject) jv;
            String key = jo.getString(jo.containsKey("uid") ? "uid" : "configElementName");
            allConfig.put(key, jo);
        }

        JsonObject j;

        assertNotNull(err, j = allConfig.get("auth1"));
        assertEquals(err, "authData", j.getString("configElementName"));
        assertEquals(err, "auth1", j.getString("id"));
        assertEquals(err, "dbuser", j.getString("user"));
        assertEquals(err, "***", j.getString("password"));

        assertNotNull(err, j = allConfig.get("auth2"));
        assertEquals(err, "authData", j.getString("configElementName"));
        assertEquals(err, "auth2", j.getString("id"));
        assertEquals(err, "dbuser", j.getString("user"));
        assertEquals(err, "***", j.getString("password"));

        assertNotNull(err, j = allConfig.get("pool1"));
        assertEquals(err, "connectionManager", j.getString("configElementName"));
        assertEquals(err, "pool1", j.getString("id"));
        assertEquals(err, 10, j.getInt("maxPoolSize"));
        assertEquals(err, "ValidateAllConnections", j.getString("purgePolicy"));

        assertNotNull(err, j = allConfig.get("executor"));
        assertEquals(err, "executor", j.getString("configElementName"));
        assertEquals(err, -1, j.getInt("coreThreads"));
        assertEquals(err, -1, j.getInt("maxThreads"));
    }

    // Test configuration that is child-first
    @Test
    public void testConfigChildFirst() throws Exception {
        JsonObject app = new HttpsRequest(server, "/ibm/api/config/application/bogus").run(JsonObject.class);
        String err = "unexpected response: " + app;
        assertEquals(err, "bogus", app.getString("uid"));
        assertEquals(err, "bogus", app.getString("id"));

        JsonArray wsArray = app.getJsonArray("webservices-bnd");
        assertEquals(err, 1, wsArray.size());
        JsonObject wsBnd = wsArray.getJsonObject(0);
        assertEquals(err, "webservices-bnd", wsBnd.getString("configElementName"));
        assertEquals(err, "application[bogus]/webservices-bnd[default-0]", wsBnd.getString("uid"));
        JsonArray wsEndpointPropsArray = wsBnd.getJsonArray("webservice-endpoint-properties");
        assertEquals(err, 1, wsArray.size());
        JsonObject wsEndpointProps = wsEndpointPropsArray.getJsonObject(0);
        assertEquals(err, "test", wsEndpointProps.getString("someAttribute"));
        assertEquals(err, "application[bogus]/webservices-bnd[default-0]/webservice-endpoint-properties[default-0]",
                     wsEndpointProps.getString("uid"));
    }

    //Test the config api when a configuration element's feature is not enabled
    @Test
    public void testConfigCloudantNotEnabled() throws Exception {
        JsonArray json = new HttpsRequest(server, "/ibm/api/config/cloudant").run(JsonArray.class);
        JsonArray json2 = new HttpsRequest(server, "/ibm/api/config/cloudantDatabase").run(JsonArray.class);
        String err = "unexpected response: " + json;
        assertEquals(err, 1, json.size());
        assertEquals(err, 1, json2.size());

        JsonObject j = json.getJsonObject(0);
        assertEquals(err, "cloudant", j.getString("configElementName"));
        assertEquals(err, "builder", j.getString("uid"));
        assertEquals(err, "builder", j.getString("id"));
        assertNull(err, j.get("jndiName"));
        assertEquals(err, "Check that the spelling is correct and that the right features are enabled for this configuration.", j.getString("error"));
        assertEquals(err, "DerbyLib", j.getString("libraryRef"));
        assertEquals(err, "pwd1", j.getString("password")); //TODO Don't reveal password here
        assertEquals(err, "http://douc.hursley.ibm.com:5984", j.getString("url"));
        assertEquals(err, "u1", j.getString("username"));

        j = json2.getJsonObject(0);
        assertEquals(err, "cloudantDatabase", j.getString("configElementName"));
        assertEquals(err, "CloudantDBNotEnabled", j.getString("uid"));
        assertEquals(err, "CloudantDBNotEnabled", j.getString("id"));
        assertEquals(err, "cloudant/db", j.getString("jndiName"));
        assertEquals(err, "Check that the spelling is correct and that the right features are enabled for this configuration.", j.getString("error"));
        assertEquals(err, "builder", j.getString("cloudantRef"));
        assertEquals(err, "testdb", j.getString("databaseName"));
    }

    // Invoke /ibm/api/config/dataSource REST API to display information for all configured data sources.
    // Verify the correct number of data sources are included in the result.
    // Verify the names and specified values of properties that are configured on the data source.
    // Verify the contents of flattened configuration for vendor properties.
    // Verify that nested configuration elements are included.
    // Verify that directly referenced configurations and indirectly referenced configurations are included.
    @Test
    public void testConfigDataSource() throws Exception {
        JsonArray json = new HttpsRequest(server, "/ibm/api/config/dataSource").run(JsonArray.class);
        String err = "unexpected response: " + json;
        assertEquals(err, 6, json.size());

        JsonArray json2 = new HttpsRequest(server, "/ibm/api/config/dataSource/").run(JsonArray.class);
        assertEquals(json, json2);

        JsonObject j, jj;
        JsonArray ja;

        j = json.getJsonObject(0);
        assertEquals(err, "dataSource", j.getString("configElementName"));
        assertEquals(err, "DataSourceWithoutJDBCDriver", j.getString("uid"));
        assertEquals(err, "DataSourceWithoutJDBCDriver", j.getString("id"));
        assertEquals(err, "jdbc/withoutJDBCDriver", j.getString("jndiName"));
        assertEquals(err, true, j.getBoolean("beginTranForResultSetScrollingAPIs"));
        assertEquals(err, true, j.getBoolean("beginTranForVendorAPIs"));
        assertNull(err, j.get("connectionManagerRef"));
        assertEquals(err, "MatchCurrentState", j.getString("connectionSharing"));
        assertNotNull(err, ja = j.getJsonArray("containerAuthDataRef"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, jj = ja.getJsonObject(0));
        assertEquals(err, "containerAuthData", jj.getString("configElementName"));
        assertEquals(err, "dataSource[DataSourceWithoutJDBCDriver]/containerAuthData[dbuser-auth]", jj.getString("uid"));
        assertEquals(err, "dbuser-auth", jj.getString("id"));
        assertEquals(err, "***", jj.getString("password"));
        assertEquals(err, "dbuser", jj.getString("user"));
        assertEquals(err, false, j.getBoolean("enableConnectionCasting"));
        assertNull(err, j.get("jdbcDriverRef"));
        assertEquals(err, 10, j.getInt("statementCacheSize"));
        assertEquals(err, false, j.getBoolean("syncQueryTimeoutWithTransactionTimeout"));
        assertEquals(err, false, j.getBoolean("transactional"));
        assertNotNull(err, ja = j.getJsonArray("api"));
        boolean found = false;
        for (JsonValue jv : ja)
            if ("/ibm/api/validator/dataSource/DataSourceWithoutJDBCDriver".equals(((JsonString) jv).getString()))
                if (found)
                    fail("Duplicate value in api list");
                else
                    found = true;
        assertTrue(err, found);
        assertNotNull(err, ja = j.getJsonArray("properties.derby.embedded"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, j = ja.getJsonObject(0));
        assertEquals(err, "memory:withoutJDBCDriver", j.getString("databaseName"));

        j = json.getJsonObject(1);
        assertEquals(err, "dataSource", j.getString("configElementName"));
        assertEquals(err, "DefaultDataSource", j.getString("uid"));
        assertEquals(err, "DefaultDataSource", j.getString("id"));
        assertNull(err, j.get("jndiName"));
        assertEquals(err, true, j.getBoolean("beginTranForResultSetScrollingAPIs"));
        assertEquals(err, true, j.getBoolean("beginTranForVendorAPIs"));
        assertNull(err, j.get("connectionManagerRef"));
        assertEquals(err, "MatchOriginalRequest", j.getString("connectionSharing"));
        assertNull(err, j.get("containerAuthDataRef"));
        assertEquals(err, false, j.getBoolean("enableConnectionCasting"));
        assertEquals(err, "TRANSACTION_READ_COMMITTED", j.getString("isolationLevel"));
        assertNotNull(err, ja = j.getJsonArray("jdbcDriverRef"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, jj = ja.getJsonObject(0));
        assertEquals(err, "jdbcDriver", jj.getString("configElementName"));
        assertEquals(err, "dataSource[DefaultDataSource]/jdbcDriver[default-0]", jj.getString("uid"));
        assertNull(err, jj.get("id"));
        assertNotNull(err, ja = jj.getJsonArray("libraryRef"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, jj = ja.getJsonObject(0));
        assertEquals(err, "library", jj.getString("configElementName"));
        assertEquals(err, "Derby", jj.getString("uid"));
        assertEquals(err, "Derby", jj.getString("id"));
        assertEquals(err, "spec,ibm-api,api,stable", jj.getString("apiTypeVisibility"));
        assertNotNull(err, ja = jj.getJsonArray("fileRef"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, jj = ja.getJsonObject(0));
        assertEquals(err, "file", jj.getString("configElementName"));
        assertEquals(err, "library[Derby]/file[default-0]", jj.getString("uid"));
        assertTrue(err, jj.getString("name").endsWith("derby.jar"));
        assertEquals(err, 10, j.getInt("statementCacheSize"));
        assertEquals(err, false, j.getBoolean("syncQueryTimeoutWithTransactionTimeout"));
        assertEquals(err, true, j.getBoolean("transactional"));
        // TODO assertEquals(err, "javax.sql.XADataSource", j.getString("type"));
        assertNotNull(err, ja = j.getJsonArray("api"));
        found = false;
        for (JsonValue jv : ja)
            if ("/ibm/api/validator/dataSource/DefaultDataSource".equals(((JsonString) jv).getString()))
                if (found)
                    fail("Duplicate value in api list");
                else
                    found = true;
        assertTrue(err, found);
        assertNotNull(err, ja = j.getJsonArray("properties.derby.embedded"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, j = ja.getJsonObject(0));
        assertEquals(err, "create", j.getString("createDatabase"));
        assertEquals(err, "memory:defaultdb", j.getString("databaseName"));
        assertEquals(err, "dbuser", j.getString("user"));
        assertEquals(err, "***", j.getString("password"));

        j = json.getJsonObject(2);
        assertEquals(err, "dataSource", j.getString("configElementName"));
        assertEquals(err, "WrongDefaultAuth", j.getString("uid"));
        assertEquals(err, "WrongDefaultAuth", j.getString("id"));
        assertEquals(err, "jdbc/wrongdefaultauth", j.getString("jndiName"));
        assertEquals(err, true, j.getBoolean("beginTranForResultSetScrollingAPIs"));
        assertEquals(err, true, j.getBoolean("beginTranForVendorAPIs"));
        assertEquals(err, "rollback", j.getString("commitOrRollbackOnCleanup"));
        assertNotNull(err, ja = j.getJsonArray("connectionManagerRef"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, jj = ja.getJsonObject(0));
        assertEquals(err, "connectionManager", jj.getString("configElementName"));
        assertEquals(err, "pool1", jj.getString("uid"));
        assertEquals(err, "pool1", jj.getString("id"));
        assertEquals(err, -1, jj.getJsonNumber("agedTimeout").longValue());
        assertEquals(err, 30, jj.getJsonNumber("connectionTimeout").longValue());
        assertTrue(err, jj.getBoolean("enableSharingForDirectLookups"));
        assertEquals(err, 1800, jj.getJsonNumber("maxIdleTime").longValue());
        assertEquals(err, 10, jj.getJsonNumber("maxPoolSize").longValue());
        assertEquals(err, "ValidateAllConnections", jj.getString("purgePolicy"));
        assertEquals(err, 180, jj.getJsonNumber("reapTime").longValue());
        assertEquals(err, "MatchOriginalRequest", j.getString("connectionSharing"));
        assertNotNull(err, ja = j.getJsonArray("containerAuthDataRef"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, jj = ja.getJsonObject(0));
        assertEquals(err, "authData", jj.getString("configElementName"));
        assertEquals(err, "auth2", jj.getString("uid"));
        assertEquals(err, "auth2", jj.getString("id"));
        assertEquals(err, "***", jj.getString("password"));
        assertEquals(err, "dbuser", jj.getString("user"));
        assertEquals(err, false, j.getBoolean("enableConnectionCasting"));
        assertEquals(err, "The property's value.", j.getString("invalidProperty"));
        assertNotNull(err, ja = j.getJsonArray("jdbcDriverRef"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, jj = ja.getJsonObject(0));
        assertEquals(err, "jdbcDriver", jj.getString("configElementName"));
        assertEquals(err, "DerbyDriver", jj.getString("uid"));
        assertEquals(err, "DerbyDriver", jj.getString("id"));
        assertNotNull(err, ja = jj.getJsonArray("libraryRef"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, jj = ja.getJsonObject(0));
        assertEquals(err, "library", jj.getString("configElementName"));
        assertEquals(err, "Derby", jj.getString("uid"));
        assertEquals(err, "Derby", jj.getString("id"));
        assertEquals(err, "spec,ibm-api,api,stable", jj.getString("apiTypeVisibility"));
        assertNotNull(err, ja = jj.getJsonArray("fileRef"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, jj = ja.getJsonObject(0));
        assertEquals(err, "file", jj.getString("configElementName"));
        assertEquals(err, "library[Derby]/file[default-0]", jj.getString("uid"));
        assertTrue(err, jj.getString("name").endsWith("derby.jar"));
        assertEquals(err, 130, j.getInt("queryTimeout"));
        assertNotNull(err, ja = j.getJsonArray("recoveryAuthDataRef"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, jj = ja.getJsonObject(0));
        assertEquals(err, "authData", jj.getString("configElementName"));
        assertEquals(err, "auth2", jj.getString("uid"));
        assertEquals(err, "auth2", jj.getString("id"));
        assertEquals(err, "***", jj.getString("password"));
        assertEquals(err, "dbuser", jj.getString("user"));
        assertEquals(err, 15, j.getInt("statementCacheSize"));
        assertEquals(err, false, j.getBoolean("syncQueryTimeoutWithTransactionTimeout"));
        assertEquals(err, true, j.getBoolean("transactional"));
        assertEquals(err, 20, j.getInt("validationTimeout"));
        assertNotNull(ja = j.getJsonArray("api"));
        found = false;
        for (JsonValue jv : ja)
            if ("/ibm/api/validator/dataSource/WrongDefaultAuth".equals(((JsonString) jv).getString()))
                if (found)
                    fail("Duplicate value in api list");
                else
                    found = true;
        assertTrue(err, found);
        assertNotNull(err, ja = j.getJsonArray("properties"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, j = ja.getJsonObject(0));
        assertEquals(err, "create", j.getString("createDatabase"));
        assertEquals(err, "memory:defaultdb", j.getString("databaseName"));

        j = json.getJsonObject(3);
        assertEquals(err, "dataSource", j.getString("configElementName"));
        assertEquals(err, "dataSource[default-0]", j.getString("uid"));
        assertNull(err, j.get("id"));
        assertEquals(err, "jdbc/defaultauth", j.getString("jndiName"));
        assertEquals(err, true, j.getBoolean("beginTranForResultSetScrollingAPIs"));
        assertEquals(err, true, j.getBoolean("beginTranForVendorAPIs"));
        assertNotNull(err, ja = j.getJsonArray("connectionManagerRef"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, jj = ja.getJsonObject(0));
        assertEquals(err, "connectionManager", jj.getString("configElementName"));
        assertEquals(err, "dataSource[default-0]/connectionManager[default-0]", jj.getString("uid"));
        assertNull(err, jj.get("id"));
        assertEquals(err, -1, jj.getJsonNumber("agedTimeout").longValue());
        assertEquals(err, 30, jj.getJsonNumber("connectionTimeout").longValue());
        assertFalse(err, jj.getBoolean("enableSharingForDirectLookups"));
        assertEquals(err, 1800, jj.getJsonNumber("maxIdleTime").longValue());
        assertEquals(err, 50, jj.getJsonNumber("maxPoolSize").longValue());
        assertEquals(err, "EntirePool", jj.getString("purgePolicy"));
        assertEquals(err, 180, jj.getJsonNumber("reapTime").longValue());
        assertEquals(err, "MatchOriginalRequest", j.getString("connectionSharing"));
        assertNotNull(err, ja = j.getJsonArray("containerAuthDataRef"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, jj = ja.getJsonObject(0));
        assertEquals(err, "authData", jj.getString("configElementName"));
        assertEquals(err, "auth1", jj.getString("uid"));
        assertEquals(err, "auth1", jj.getString("id"));
        assertEquals(err, "***", jj.getString("password"));
        assertEquals(err, "dbuser", jj.getString("user"));
        assertEquals(err, false, j.getBoolean("enableConnectionCasting"));
        assertNotNull(ja = j.getJsonArray("api"));
        found = false;
        for (JsonValue jv : ja)
            if ("/ibm/api/validator/dataSource/dataSource%5Bdefault-0%5D".equals(((JsonString) jv).getString()))
                if (found)
                    fail("Duplicate value in api list");
                else
                    found = true;
        assertTrue(err, found);
        assertNotNull(err, ja = j.getJsonArray("jdbcDriverRef"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, jj = ja.getJsonObject(0));
        assertEquals(err, "jdbcDriver", jj.getString("configElementName"));
        assertEquals(err, "dataSource[default-0]/jdbcDriver[NestedDerbyDriver]", jj.getString("uid"));
        assertEquals(err, "NestedDerbyDriver", jj.getString("id"));
        assertEquals(err, "org.apache.derby.jdbc.EmbeddedDataSource", jj.getString("javax.sql.DataSource"));
        assertEquals(err, "org.apache.derby.jdbc.EmbeddedConnectionPoolDataSource", jj.getString("javax.sql.ConnectionPoolDataSource"));
        assertEquals(err, "org.apache.derby.jdbc.EmbeddedXADataSource", jj.getString("javax.sql.XADataSource"));
        assertNotNull(err, ja = jj.getJsonArray("libraryRef"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, jj = ja.getJsonObject(0));
        assertEquals(err, "library", jj.getString("configElementName"));
        assertEquals(err, "Derby", jj.getString("uid"));
        assertEquals(err, "Derby", jj.getString("id"));
        assertEquals(err, "spec,ibm-api,api,stable", jj.getString("apiTypeVisibility"));
        assertNotNull(err, ja = jj.getJsonArray("fileRef"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, jj = ja.getJsonObject(0));
        assertEquals(err, "file", jj.getString("configElementName"));
        assertEquals(err, "library[Derby]/file[default-0]", jj.getString("uid"));
        assertTrue(err, jj.getString("name").endsWith("derby.jar"));
        assertNotNull(err, ja = j.getJsonArray("onConnect"));
        assertEquals(err, 2, ja.size());
        assertEquals(err, "SET CURRENT SCHEMA = APP", ja.getString(0));
        assertEquals(err, "SET CURRENT SQLID = APP", ja.getString(1));
        assertEquals(err, 10, j.getInt("statementCacheSize"));
        assertEquals(err, false, j.getBoolean("syncQueryTimeoutWithTransactionTimeout"));
        assertEquals(err, true, j.getBoolean("transactional"));
        assertNotNull(err, ja = j.getJsonArray("properties.derby.embedded"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, j = ja.getJsonObject(0));
        assertEquals(err, "create", j.getString("createDatabase"));
        assertEquals(err, "memory:defaultdb", j.getString("databaseName"));

        j = json.getJsonObject(4);
        assertEquals(err, "dataSource", j.getString("configElementName"));
        assertEquals(err, "jdbc/nonexistentdb", j.getString("uid"));
        assertEquals(err, "jdbc/nonexistentdb", j.getString("id"));
        assertEquals(err, "jdbc/nonexistentdb", j.getString("jndiName"));
        assertEquals(err, true, j.getBoolean("beginTranForResultSetScrollingAPIs"));
        assertEquals(err, true, j.getBoolean("beginTranForVendorAPIs"));
        assertNotNull(err, ja = j.getJsonArray("connectionManagerRef"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, jj = ja.getJsonObject(0));
        assertEquals(err, "connectionManager", jj.getString("configElementName"));
        assertEquals(err, "dataSource[jdbc/nonexistentdb]/connectionManager[NestedConPool]", jj.getString("uid"));
        assertEquals(err, "NestedConPool", jj.getString("id"));
        assertEquals(err, 3723, jj.getJsonNumber("agedTimeout").longValue());
        assertEquals(err, 0, jj.getJsonNumber("connectionTimeout").longValue());
        assertTrue(err, jj.getBoolean("enableSharingForDirectLookups"));
        assertEquals(err, 2400, jj.getJsonNumber("maxIdleTime").longValue());
        assertEquals(err, 50, jj.getJsonNumber("maxPoolSize").longValue());
        assertEquals(err, "EntirePool", jj.getString("purgePolicy"));
        assertEquals(err, 150, jj.getJsonNumber("reapTime").longValue());
        assertEquals(err, "MatchOriginalRequest", j.getString("connectionSharing"));
        assertNull(err, j.get("containerAuthDataRef"));
        assertEquals(err, false, j.getBoolean("enableConnectionCasting"));
        assertNotNull(ja = j.getJsonArray("api"));
        found = false;
        for (JsonValue jv : ja)
            if ("/ibm/api/validator/dataSource/jdbc%2Fnonexistentdb".equals(((JsonString) jv).getString()))
                if (found)
                    fail("Duplicate value in api list");
                else
                    found = true;
        assertTrue(err, found);
        assertNotNull(err, ja = j.getJsonArray("jdbcDriverRef"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, jj = ja.getJsonObject(0));
        assertEquals(err, "jdbcDriver", jj.getString("configElementName"));
        assertEquals(err, "dataSource[jdbc/nonexistentdb]/jdbcDriver[default-0]", jj.getString("uid"));
        assertNull(err, jj.get("id"));
        assertNotNull(err, ja = jj.getJsonArray("libraryRef"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, jj = ja.getJsonObject(0));
        assertEquals(err, "library", jj.getString("configElementName"));
        assertEquals(err, "Derby", jj.getString("uid"));
        assertEquals(err, "Derby", jj.getString("id"));
        assertEquals(err, "spec,ibm-api,api,stable", jj.getString("apiTypeVisibility"));
        assertNotNull(err, ja = jj.getJsonArray("fileRef"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, jj = ja.getJsonObject(0));
        assertEquals(err, "file", jj.getString("configElementName"));
        assertEquals(err, "library[Derby]/file[default-0]", jj.getString("uid"));
        assertTrue(err, jj.getString("name").endsWith("derby.jar"));
        assertEquals(err, 10, j.getInt("statementCacheSize"));
        assertEquals(err, false, j.getBoolean("syncQueryTimeoutWithTransactionTimeout"));
        assertEquals(err, true, j.getBoolean("transactional"));
        assertNotNull(err, ja = j.getJsonArray("properties.derby.embedded"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, j = ja.getJsonObject(0));
        assertEquals(err, "memory:doesNotExist", j.getString("databaseName"));

        j = json.getJsonObject(5);
        assertEquals(err, "dataSource", j.getString("configElementName"));
        assertEquals(err, "transaction/dataSource[default-0]", j.getString("uid"));
        assertNull(err, j.get("id"));
        assertNull(err, j.get("jndiName"));
        assertEquals(err, true, j.getBoolean("beginTranForResultSetScrollingAPIs"));
        assertEquals(err, true, j.getBoolean("beginTranForVendorAPIs"));
        assertNotNull(err, ja = j.getJsonArray("connectionManagerRef"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, jj = ja.getJsonObject(0));
        assertEquals(err, "connectionManager", jj.getString("configElementName"));
        assertEquals(err, "transaction/dataSource[default-0]/connectionManager[default-0]", jj.getString("uid"));
        assertNull(err, jj.get("id"));
        assertEquals(err, -1, jj.getJsonNumber("agedTimeout").longValue());
        assertEquals(err, 0, jj.getJsonNumber("connectionTimeout").longValue());
        assertTrue(err, jj.getBoolean("enableSharingForDirectLookups"));
        assertEquals(err, 1800, jj.getJsonNumber("maxIdleTime").longValue());
        assertEquals(err, 5, jj.getJsonNumber("maxPoolSize").longValue());
        assertEquals(err, "EntirePool", jj.getString("purgePolicy"));
        assertEquals(err, 180, jj.getJsonNumber("reapTime").longValue());
        assertEquals(err, "MatchOriginalRequest", j.getString("connectionSharing"));
        assertNotNull(err, ja = j.getJsonArray("containerAuthDataRef"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, jj = ja.getJsonObject(0));
        assertEquals(err, "authData", jj.getString("configElementName"));
        assertEquals(err, "auth1", jj.getString("uid"));
        assertEquals(err, "auth1", jj.getString("id"));
        assertEquals(err, "***", jj.getString("password"));
        assertEquals(err, "dbuser", jj.getString("user"));
        assertEquals(err, false, j.getBoolean("enableConnectionCasting"));
        assertNotNull(ja = j.getJsonArray("api"));
        found = false;
        for (JsonValue jv : ja)
            if ("/ibm/api/validator/dataSource/transaction%2FdataSource%5Bdefault-0%5D".equals(((JsonString) jv).getString()))
                if (found)
                    fail("Duplicate value in api list");
                else
                    found = true;
        assertTrue(err, found);
        assertNotNull(err, ja = j.getJsonArray("jdbcDriverRef"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, jj = ja.getJsonObject(0));
        assertEquals(err, "jdbcDriver", jj.getString("configElementName"));
        assertEquals(err, "transaction/dataSource[default-0]/jdbcDriver[default-0]", jj.getString("uid"));
        assertNull(err, jj.get("id"));
        assertNotNull(err, ja = jj.getJsonArray("libraryRef"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, jj = ja.getJsonObject(0));
        assertEquals(err, "library", jj.getString("configElementName"));
        assertEquals(err, "Derby", jj.getString("uid"));
        assertEquals(err, "Derby", jj.getString("id"));
        assertEquals(err, "spec,ibm-api,api,stable", jj.getString("apiTypeVisibility"));
        assertNotNull(err, ja = jj.getJsonArray("fileRef"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, jj = ja.getJsonObject(0));
        assertEquals(err, "file", jj.getString("configElementName"));
        assertEquals(err, "library[Derby]/file[default-0]", jj.getString("uid"));
        assertTrue(err, jj.getString("name").endsWith("derby.jar"));
        assertEquals(err, 10, j.getInt("statementCacheSize"));
        assertEquals(err, false, j.getBoolean("syncQueryTimeoutWithTransactionTimeout"));
        assertEquals(err, false, j.getBoolean("transactional"));
        assertNotNull(err, ja = j.getJsonArray("properties.derby.embedded"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, j = ja.getJsonObject(0));
        assertEquals(err, "memory:recoverydb", j.getString("databaseName"));
    }

    // Invoke /ibm/api/config/dataSource with jndiName to filter for a specific dataSource instance.
    @Test
    public void testConfigDataSourceByJNDIName() throws Exception {
        JsonArray json = new HttpsRequest(server, "/ibm/api/config/dataSource?jndiName=jdbc%2FwithoutJDBCDriver").run(JsonArray.class);
        String err = "unexpected response: " + json;
        assertEquals(err, 1, json.size());

        JsonObject j = json.getJsonObject(0);
        assertEquals(err, "dataSource", j.getString("configElementName"));
        assertEquals(err, "DataSourceWithoutJDBCDriver", j.getString("uid"));
        assertEquals(err, "DataSourceWithoutJDBCDriver", j.getString("id"));
        assertEquals(err, "jdbc/withoutJDBCDriver", j.getString("jndiName"));
        // other attributes tested by testConfigDataSource, only including a few here
        JsonArray ja;
        assertNotNull(ja = j.getJsonArray("api"));
        boolean found = false;
        for (JsonValue jv : ja)
            if ("/ibm/api/validator/dataSource/DataSourceWithoutJDBCDriver".equals(((JsonString) jv).getString()))
                if (found)
                    fail("Duplicate value in api list");
                else
                    found = true;
        assertTrue(err, found);
        assertNotNull(err, ja = j.getJsonArray("properties.derby.embedded"));
        assertEquals(err, 1, ja.size());
        assertNotNull(err, j = ja.getJsonObject(0));
        assertEquals(err, "memory:withoutJDBCDriver", j.getString("databaseName"));
    }

    // Invoke /ibm/api/config/dataSource with jndiName query parameter specified with 2 different values on same request
    // and verify information for both dataSource instances (and no others) is returned.
    @Test
    public void testConfigDataSourceByJNDINames() throws Exception {
        JsonArray json = new HttpsRequest(server, "/ibm/api/config/dataSource?jndiName=jdbc/defaultauth&jndiName=jdbc/wrongdefaultauth").run(JsonArray.class);
        String err = "unexpected response: " + json;
        assertEquals(err, 2, json.size());

        JsonObject j = json.getJsonObject(0);
        assertEquals(err, "dataSource", j.getString("configElementName"));
        assertEquals(err, "WrongDefaultAuth", j.getString("uid"));
        assertEquals(err, "WrongDefaultAuth", j.getString("id"));
        assertEquals(err, "jdbc/wrongdefaultauth", j.getString("jndiName"));
        // other attributes are tested by testConfigDataSource, only including a few here
        assertEquals(err, "MatchOriginalRequest", j.getString("connectionSharing"));
        assertEquals(err, 130, j.getInt("queryTimeout"));

        j = json.getJsonObject(1);
        assertEquals(err, "dataSource", j.getString("configElementName"));
        assertEquals(err, "dataSource[default-0]", j.getString("uid"));
        assertNull(err, j.get("id"));
        assertEquals(err, "jdbc/defaultauth", j.getString("jndiName"));
        // other attributes are tested by testConfigDataSource, only including a few here
        JsonArray ja;
        assertNotNull(err, ja = j.getJsonArray("onConnect"));
        assertEquals(err, 2, ja.size());
        assertEquals(err, "SET CURRENT SCHEMA = APP", ja.getString(0));
        assertEquals(err, "SET CURRENT SQLID = APP", ja.getString(1));
        assertEquals(err, false, j.getBoolean("syncQueryTimeoutWithTransactionTimeout"));
    }

    // Invoke /ibm/api/config/dataSource/{uid} REST API and filter on attributes. This is redundant, but should still work.
    @Test
    public void testConfigDataSourceFilterSingleInstance() throws Exception {
        JsonObject j = new HttpsRequest(server, "/ibm/api/config/dataSource/WrongDefaultAuth?" +
                                                "id=WrongDefaultAuth&jndiName=jdbc/wrongdefaultauth&beginTranForVendorAPIs=true&" +
                                                "commitOrRollbackOnCleanup=rollback&invalidProperty=The+property's+value.&" +
                                                "queryTimeout=130&statementCacheSize=15&validationTimeout=20").run(JsonObject.class);
        String err = "unexpected response: " + j;
        assertEquals(err, "dataSource", j.getString("configElementName"));
        assertEquals(err, "WrongDefaultAuth", j.getString("uid"));
        assertEquals(err, "WrongDefaultAuth", j.getString("id"));
        assertEquals(err, "jdbc/wrongdefaultauth", j.getString("jndiName"));
        // other attributes are tested by testConfigDataSource, only including a few here
        assertEquals(err, 15, j.getInt("statementCacheSize"));
        assertTrue(err, j.getBoolean("transactional"));
    }

    // Invoke /ibm/api/config/dataSource/{uid} REST API to display information for a single data source.
    @Test
    public void testConfigDefaultDataSource() throws Exception {
        JsonObject json = new HttpsRequest(server, "/ibm/api/config/dataSource/DefaultDataSource").run(JsonObject.class);
        String err = "unexpected response: " + json;

        assertEquals(err, "DefaultDataSource", json.getString("uid"));
        assertEquals(err, "DefaultDataSource", json.getString("id"));
        assertNull(err, json.get("jndiName"));
        // other attributes are covered by testConfigDataSources
    }

    // Ensure that the requested element type matches the returned config.
    // For example, querying for 'application' element type should only return config of type 'application',
    // not 'applicationManager' or 'enterpriseApplication' because they contain the substring 'application'
    @Test
    public void testConfigElementSubStrings() throws Exception {
        JsonArray json = new HttpsRequest(server, "/ibm/api/config/application").run(JsonArray.class);
        String err = "unexpected response: " + json;
        assertEquals(err, 1, json.size());

        JsonObject app = json.getJsonObject(0);
        assertEquals(err, "bogus", app.getString("uid"));
        assertEquals(err, "bogus", app.getString("id"));
        assertEquals(err, false, app.getBoolean("autoStart"));
        assertEquals(err, "bogus.war", app.getString("location"));
        assertNotNull(err, app.getJsonArray("webservices-bnd"));
    }

    // Verify that a multiple cardinality attribute is displayed properly in the /ibm/api/config JSON response.
    @Test
    public void testConfigFeatureManager() throws Exception {
        JsonArray json = new HttpsRequest(server, "/ibm/api/config/featureManager").run(JsonArray.class);
        String err = "unexpected response: " + json;
        assertEquals(err, 1, json.size());

        JsonObject j = json.getJsonObject(0);
        assertEquals(err, "featureManager", j.getString("configElementName"));
        assertNull(err, j.get("uid"));
        assertNull(err, j.get("id"));
        assertNull(err, j.get("jndiName"));
        JsonArray ja;
        assertNotNull(err, ja = j.getJsonArray("feature"));

        int length = ja.size();
        assertEquals(err, 5, length);
        List<String> features = new ArrayList<String>();
        for (int i = 0; i < length; i++)
            features.add(ja.getString(i));
        assertTrue(err, features.contains("componenttest-1.0"));
        assertTrue(err, features.contains("configValidator-1.0"));
        assertTrue(err, features.contains("jdbc-4.2"));
        assertTrue(err, features.contains("timedexit-1.0"));
        assertTrue(err, features.contains("usr:nestedFlat-1.0"));
        assertEquals(err, "FAIL", j.getString("onError"));
    }

    // Verify that grandfathered attribute names containing '.' are included in the /ibm/api/config JSON response.
    @Test
    public void testConfigHttpEncoding() throws Exception {
        JsonArray json = new HttpsRequest(server, "/ibm/api/config/httpEncoding").run(JsonArray.class);
        String err = "unexpected response: " + json;

        JsonObject j = json.getJsonObject(0);
        assertEquals(err, "httpEncoding", j.getString("configElementName"));
        assertNull(err, j.get("uid"));
        assertNull(err, j.get("id"));
        assertEquals(err, "Cp943C", j.getString("converter.Shift_JIS"));
        assertEquals(err, "ISO-8859-6", j.getString("encoding.ar"));
    }

    //Test that internal config attributes are not returned
    @Test
    public void testConfigInternalAttributes() throws Exception {
        JsonObject json = new HttpsRequest(server, "/ibm/api/config/dataSource/DefaultDataSource").run(JsonObject.class);
        String err = "unexpected response: " + json;

        assertEquals(err, "DefaultDataSource", json.getString("id"));
        assertNull(json.get("connectionManager.target"));
        assertNull(json.get("config.id"));
        assertNull(json.get("javaCompDefaultName"));
    }

    //Test that a config element with name=internal is not returned
    @Test
    public void testConfigInternalElement() throws Exception {
        JsonArray json = new HttpsRequest(server, "/ibm/api/config/udpOptions").run(JsonArray.class);

        String err = "unexpected response: " + json;
        assertEquals(err, 0, json.size());
    }

    // Test a user-defined configuration that has nested flat config elements, for example:
    // <usr_parent id="a" name="one"> <child value="two"> <grandchild value="three"/> </child> </usr_parent>
    @Test
    public void testNestedFlat() throws Exception {
        JsonArray resp = new HttpsRequest(server, "/ibm/api/config/usr_parent").run(JsonArray.class);
        String err = "unexpected response: " + resp;
        assertEquals(err, 1, resp.size());

        JsonObject parent = resp.getJsonObject(0);
        assertEquals(err, "a", parent.getString("uid"));
        assertEquals(err, "a", parent.getString("id"));
        assertEquals(err, "usr_parent", parent.getString("configElementName"));
        assertEquals(err, "one", parent.getString("name"));

        JsonArray childArr = parent.getJsonArray("usr_child");
        assertEquals(err, 1, childArr.size());
        JsonObject child = childArr.getJsonObject(0);
        assertEquals(err, "two", child.getString("value"));

        JsonArray grandchildArr = parent.getJsonArray("usr_grandchild");
        assertEquals(err, 1, grandchildArr.size());
        JsonObject grandchild = grandchildArr.getJsonObject(0);
        assertEquals(err, "three", grandchild.getString("value"));
    }

    // Invoke /ibm/api/config/ REST API with query parameters to filter in a way that does not match any configuration. Ensure an empty response.
    @Test
    public void testConfigNoMatch() throws Exception {
        // Server configuration has a dataSource with jndiName jdbc/defaultauth and another with connectionSharing of MatchCurrentState,
        // but no single dataSource has both.
        JsonArray json = new HttpsRequest(server, "/ibm/api/config/dataSource?jndiName=jdbc/defaultauth&connectionSharing=MatchCurrentState").run(JsonArray.class);
        String err = "unexpected response: " + json;
        assertEquals(err, 0, json.size());

        // Attribute does not exist on any dataSource instance
        json = new HttpsRequest(server, "/ibm/api/config/dataSource?cancellationTimeout=60").run(JsonArray.class);
        err = "unexpected response: " + json;
        assertEquals(err, 0, json.size());

        // Attribute does not match on specific dataSource instance
        JsonObject j = new HttpsRequest(server, "/ibm/api/config/dataSource/DefaultDataSource?queryTimeout=130").run(JsonObject.class);
        err = "unexpected response: " + j;
        assertEquals(err, "DefaultDataSource", j.getString("uid"));
        assertNotNull(err, j.getString("error"));
    }

    // Invoke /ibm/api/config/ REST API for configuration element that is not present in the configuration. Ensure an empty response.
    @Test
    public void testConfigNotPresent() throws Exception {
        JsonArray json = new HttpsRequest(server, "/ibm/api/config/connectionFactory").run(JsonArray.class);
        String err = "unexpected response: " + json;
        assertEquals(err, 0, json.size());

        JsonArray json2 = new HttpsRequest(server, "/ibm/api/config/connectionFactory/").run(JsonArray.class);
        assertEquals(json, json2);
    }

    // Invoke /ibm/api/config/ REST API with query parameter to filter across multiple configuration element types.
    @Test
    public void testConfigOnError() throws Exception {
        JsonArray json = new HttpsRequest(server, "/ibm/api/config?onError=FAIL").run(JsonArray.class);
        String err = "unexpected response: " + json;
        assertEquals(err, 3, json.size()); // Increase the expected value if new configuration is added which has the onError attribute

        Map<String, JsonObject> configWithOnError = new HashMap<String, JsonObject>();
        for (JsonValue jv : json) {
            JsonObject jo = (JsonObject) jv;
            configWithOnError.put(jo.getString("configElementName"), jo);
        }

        JsonObject j;

        assertNotNull(err, j = configWithOnError.get("config"));
        assertNull(err, j.get("uid"));
        assertNull(err, j.get("id"));
        assertEquals(err, 500, j.getInt("monitorInterval"));
        assertEquals(err, "FAIL", j.getString("onError"));
        assertEquals(err, "polled", j.getString("updateTrigger"));

        assertNotNull(err, j = configWithOnError.get("featureManager"));
        assertNull(err, j.get("uid"));
        assertNull(err, j.get("id"));
        assertNotNull(err, j.get("feature")); // value is otherwise already tested by testConfigFeatureManager
        assertEquals(err, "FAIL", j.getString("onError"));

        assertNotNull(err, j = configWithOnError.get("httpEndpoint"));
        assertEquals(err, "defaultHttpEndpoint", j.getString("uid"));
        assertEquals(err, "defaultHttpEndpoint", j.getString("id"));
        assertNull(err, j.get("_defaultHostName"));
        assertTrue(err, j.getBoolean("enabled"));
        assertNotNull(err, j.getString("host"));
        assertTrue(err, j.getInt("httpPort") > 0);
        assertTrue(err, j.getInt("httpsPort") > 0);
        assertEquals(err, "FAIL", j.getString("onError"));
        JsonObject httpOptionsRef = j.getJsonObject("httpOptionsRef");
        assertNotNull(err, httpOptionsRef);
        assertEquals(err, "httpOptions", httpOptionsRef.getString("configElementName"));
        assertEquals(err, "defaultHttpOptions", httpOptionsRef.getString("uid"));
        assertEquals(err, "defaultHttpOptions", httpOptionsRef.getString("id"));
        assertTrue(err, httpOptionsRef.getBoolean("keepAliveEnabled"));
        assertEquals(err, 100, httpOptionsRef.getInt("maxKeepAliveRequests"));
        assertEquals(err, 30, httpOptionsRef.getInt("persistTimeout"));
        assertEquals(err, 60, httpOptionsRef.getInt("readTimeout"));
        assertFalse(err, httpOptionsRef.getBoolean("removeServerHeader"));
        assertNull(err, httpOptionsRef.get("v0CookieDateRFC1123compat"));
        assertEquals(err, 60, httpOptionsRef.getInt("writeTimeout"));
        JsonObject tcpOptionsRef = j.getJsonObject("tcpOptionsRef");
        assertNotNull(err, tcpOptionsRef);
        assertEquals(err, "tcpOptions", tcpOptionsRef.getString("configElementName"));
        assertEquals(err, "defaultTCPOptions", tcpOptionsRef.getString("uid"));
        assertEquals(err, "defaultTCPOptions", tcpOptionsRef.getString("id"));
        assertEquals(err, 60000, tcpOptionsRef.getInt("inactivityTimeout"));
        assertTrue(err, tcpOptionsRef.getBoolean("soReuseAddr"));
    }
}