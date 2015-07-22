/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.audit.index;

import com.google.common.base.Predicate;
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.action.admin.cluster.node.info.NodesInfoResponse;
import org.elasticsearch.action.admin.indices.settings.get.GetSettingsResponse;
import org.elasticsearch.action.exists.ExistsResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.inject.util.Providers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.transport.LocalTransportAddress;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.shield.ShieldPlugin;
import org.elasticsearch.shield.User;
import org.elasticsearch.shield.authc.AuthenticationService;
import org.elasticsearch.shield.authc.AuthenticationToken;
import org.elasticsearch.shield.transport.filter.IPFilter;
import org.elasticsearch.shield.transport.filter.ShieldIpFilterRule;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.elasticsearch.test.InternalTestCluster;
import org.elasticsearch.test.ShieldIntegrationTest;
import org.elasticsearch.test.ShieldSettingsSource;
import org.elasticsearch.transport.TransportInfo;
import org.elasticsearch.transport.TransportMessage;
import org.elasticsearch.transport.TransportRequest;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.ISODateTimeFormat;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.elasticsearch.shield.audit.index.IndexNameResolver.Rollover.*;
import static org.elasticsearch.test.ElasticsearchIntegrationTest.Scope.SUITE;
import static org.elasticsearch.test.InternalTestCluster.clusterName;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.*;

/**
 *
 */
@ElasticsearchIntegrationTest.ClusterScope(scope = SUITE, numDataNodes = 1)
public class IndexAuditTrailTests extends ShieldIntegrationTest {

    public static final String SECOND_CLUSTER_NODE_PREFIX = "remote_" + SUITE_CLUSTER_NODE_PREFIX;

    private static final IndexAuditUserHolder user = new IndexAuditUserHolder(IndexAuditTrail.INDEX_NAME_PREFIX);

    private IndexNameResolver.Rollover rollover;
    private IndexAuditTrail auditor;
    private boolean remoteIndexing = false;
    private InternalTestCluster cluster2;
    private Client remoteClient;
    private int numShards;
    private int numReplicas;

    private Settings commonSettings(IndexNameResolver.Rollover rollover) {
        return Settings.builder()
                .put("shield.audit.enabled", true)
                .put("shield.audit.outputs", "index, logfile")
                .put("shield.audit.index.bulk_size", 1)
                .put("shield.audit.index.flush_interval", "1ms")
                .put("shield.audit.index.rollover", rollover.name().toLowerCase(Locale.ENGLISH))
                .put("shield.audit.index.settings.index.number_of_shards", numShards)
                .put("shield.audit.index.settings.index.number_of_replicas", numReplicas)
                .build();
    }

    private Settings remoteSettings(String address, int port, String clusterName) {
        return Settings.builder()
                .put("shield.audit.index.client.hosts", address + ":" + port)
                .put("shield.audit.index.client.cluster.name", clusterName)
                .build();
    }

    private Settings levelSettings(String[] includes, String[] excludes) {
        Settings.Builder builder = Settings.builder();
        if (includes != null) {
            builder.putArray("shield.audit.index.events.include", includes);
        }
        if (excludes != null) {
            builder.putArray("shield.audit.index.events.exclude", excludes);
        }
        return builder.build();
    }

    private Settings settings(IndexNameResolver.Rollover rollover, String[] includes, String[] excludes) {
        Settings.Builder builder = Settings.builder();
        builder.put(levelSettings(includes, excludes));
        builder.put(commonSettings(rollover));
        return builder.build();
    }

    private Client getClient() {
        return remoteIndexing ? remoteClient : client();
    }

    private void initialize(String... excludes) throws IOException {
        initialize(null, excludes);
    }

    private void initialize(String[] includes, String[] excludes) throws IOException {
        rollover = randomFrom(HOURLY, DAILY, WEEKLY, MONTHLY);
        numReplicas = numberOfReplicas();
        numShards = numberOfShards();
        Settings settings = settings(rollover, includes, excludes);
        AuthenticationService authService = mock(AuthenticationService.class);
        remoteIndexing = randomBoolean();

        if (remoteIndexing) {
            // create another cluster
            String cluster2Name = clusterName(Scope.SUITE.name(), randomLong());

            // Setup a second test cluster with randomization for number of nodes, shield enabled, and SSL
            final int numNodes = randomIntBetween(1, 2);
            final boolean useShield = randomBoolean();
            final boolean useSSL = useShield && randomBoolean();
            logger.info("--> remote indexing enabled. shield enabled: [{}], SSL enabled: [{}]", useShield, useSSL);
            ShieldSettingsSource cluster2SettingsSource = new ShieldSettingsSource(numNodes, useSSL, systemKey(), createTempDir(), Scope.SUITE) {
                    @Override
                    public Settings node(int nodeOrdinal) {
                        return Settings.builder()
                                .put(super.node(nodeOrdinal))
                                .put(ShieldPlugin.ENABLED_SETTING_NAME, useShield)
                                .build();
                    }
            };
            cluster2 = new InternalTestCluster(randomLong(), createTempDir(), numNodes, numNodes, cluster2Name, cluster2SettingsSource, 0, false, SECOND_CLUSTER_NODE_PREFIX);
            cluster2.beforeTest(getRandom(), 0.5);
            remoteClient = cluster2.client();

            NodesInfoResponse response = remoteClient.admin().cluster().prepareNodesInfo().execute().actionGet();
            TransportInfo info = response.getNodes()[0].getTransport();
            InetSocketTransportAddress inet = (InetSocketTransportAddress) info.address().publishAddress();

            Settings.Builder builder = Settings.builder()
                    .put(settings)
                    .put(ShieldPlugin.ENABLED_SETTING_NAME, useShield)
                    .put(remoteSettings(inet.address().getAddress().getHostAddress(), inet.address().getPort(), cluster2Name))
                    .put("shield.audit.index.client.shield.user", ShieldSettingsSource.DEFAULT_USER_NAME + ":" + ShieldSettingsSource.DEFAULT_PASSWORD);

            if (useSSL) {
                for (Map.Entry<String, String> entry : cluster2SettingsSource.getClientSSLSettings().getAsMap().entrySet()) {
                    builder.put("shield.audit.index.client." + entry.getKey(), entry.getValue());
                }
            }
            settings = builder.build();

            doThrow(new IllegalStateException("indexing user should not be attached when sending remotely")).when(authService).attachUserHeaderIfMissing(any(TransportMessage.class), eq(user.user()));
        }

        settings = Settings.builder().put(settings).put("path.home", createTempDir()).build();
        logger.info("--> settings: [{}]", settings.getAsMap().toString());
        when(authService.authenticate(mock(RestRequest.class))).thenThrow(new UnsupportedOperationException(""));
        when(authService.authenticate("_action", new LocalHostMockMessage(), user.user())).thenThrow(new UnsupportedOperationException(""));

        Environment env = new Environment(settings);
        auditor = new IndexAuditTrail(settings, user, env, authService, Providers.of(client()));
        auditor.start(true);
    }

    @After
    public void afterTest() {
        if (auditor != null) {
            auditor.close();
        }

        cluster().wipe();
        if (remoteIndexing && cluster2 != null) {
            cluster2.wipe();
            remoteClient.close();
            cluster2.close();
        }
    }

    @Test
    public void testAnonymousAccessDenied_Transport() throws Exception {

        initialize();
        TransportMessage message = randomFrom(new RemoteHostMockMessage(), new LocalHostMockMessage(), new MockIndicesTransportMessage());
        auditor.anonymousAccessDenied("_action", message);
        awaitIndexCreation(resolveIndexName());

        SearchHit hit = getIndexedAuditMessage();
        assertAuditMessage(hit, "transport", "anonymous_access_denied");

        if (message instanceof RemoteHostMockMessage) {
            assertEquals(remoteHostAddress(), hit.field("origin_address").getValue());
        } else {
            assertEquals("local[local_host]", hit.field("origin_address").getValue());
        }

        assertEquals("_action", hit.field("action").getValue());
        assertEquals("transport", hit.field("origin_type").getValue());
        if (message instanceof IndicesRequest) {
            List<Object> indices = hit.field("indices").getValues();
            assertThat(indices, contains((Object[]) ((IndicesRequest)message).indices()));
        }
    }

    @Test(expected = IndexNotFoundException.class)
    public void testAnonymousAccessDenied_Transport_Muted() throws Exception {
        initialize("anonymous_access_denied");
        TransportMessage message = randomFrom(new RemoteHostMockMessage(), new LocalHostMockMessage(), new MockIndicesTransportMessage());
        auditor.anonymousAccessDenied("_action", message);
        getClient().prepareExists(resolveIndexName()).execute().actionGet();
    }

    @Test
    public void testAnonymousAccessDenied_Rest() throws Exception {

        initialize();
        RestRequest request = mockRestRequest();
        auditor.anonymousAccessDenied(request);
        awaitIndexCreation(resolveIndexName());

        SearchHit hit = getIndexedAuditMessage();

        assertAuditMessage(hit, "rest", "anonymous_access_denied");
        assertThat("127.0.0.1", equalTo(hit.field("origin_address").getValue()));
        assertThat("_uri", equalTo(hit.field("uri").getValue()));
        assertThat((String) hit.field("origin_type").getValue(), is("rest"));
        assertThat(hit.field("request_body").getValue(), notNullValue());
    }

    @Test(expected = IndexNotFoundException.class)
    public void testAnonymousAccessDenied_Rest_Muted() throws Exception {
        initialize("anonymous_access_denied");
        RestRequest request = mockRestRequest();
        auditor.anonymousAccessDenied(request);
        getClient().prepareExists(resolveIndexName()).execute().actionGet();
    }

    @Test
    public void testAuthenticationFailed_Transport() throws Exception {

        initialize();
        TransportMessage message = randomBoolean() ? new RemoteHostMockMessage() : new LocalHostMockMessage();
        auditor.authenticationFailed(new MockToken(), "_action", message);
        awaitIndexCreation(resolveIndexName());

        SearchHit hit = getIndexedAuditMessage();

        assertAuditMessage(hit, "transport", "authentication_failed");

        if (message instanceof RemoteHostMockMessage) {
            assertEquals(remoteHostAddress(), hit.field("origin_address").getValue());
        } else {
            assertEquals("local[local_host]", hit.field("origin_address").getValue());
        }

        assertEquals("_principal", hit.field("principal").getValue());
        assertEquals("_action", hit.field("action").getValue());
        assertEquals("transport", hit.field("origin_type").getValue());
    }

    @Test
    public void testAuthenticationFailed_Transport_NoToken() throws Exception {
        initialize();
        TransportMessage message = randomFrom(new RemoteHostMockMessage(), new LocalHostMockMessage(), new MockIndicesTransportMessage());
        auditor.authenticationFailed("_action", message);
        awaitIndexCreation(resolveIndexName());

        SearchHit hit = getIndexedAuditMessage();

        assertAuditMessage(hit, "transport", "authentication_failed");

        if (message instanceof RemoteHostMockMessage) {
            assertEquals(remoteHostAddress(), hit.field("origin_address").getValue());
        } else {
            assertEquals("local[local_host]", hit.field("origin_address").getValue());
        }

        assertThat(hit.field("principal"), nullValue());
        assertEquals("_action", hit.field("action").getValue());
        assertEquals("transport", hit.field("origin_type").getValue());
        if (message instanceof IndicesRequest) {
            List<Object> indices = hit.field("indices").getValues();
            assertThat(indices, contains((Object[]) ((IndicesRequest)message).indices()));
        }
    }

    @Test(expected = IndexNotFoundException.class)
    public void testAuthenticationFailed_Transport_Muted() throws Exception {
        initialize("authentication_failed");
        TransportMessage message = randomFrom(new RemoteHostMockMessage(), new LocalHostMockMessage(), new MockIndicesTransportMessage());
        auditor.authenticationFailed(new MockToken(), "_action", message);
        getClient().prepareExists(resolveIndexName()).execute().actionGet();
    }

    @Test(expected = IndexNotFoundException.class)
    public void testAuthenticationFailed_Transport_NoToken_Muted() throws Exception {
        initialize("authentication_failed");
        TransportMessage message = randomFrom(new RemoteHostMockMessage(), new LocalHostMockMessage(), new MockIndicesTransportMessage());
        auditor.authenticationFailed("_action", message);
        getClient().prepareExists(resolveIndexName()).execute().actionGet();
    }

    @Test
    public void testAuthenticationFailed_Rest() throws Exception {

        initialize();
        RestRequest request = mockRestRequest();
        auditor.authenticationFailed(new MockToken(), request);
        awaitIndexCreation(resolveIndexName());

        SearchHit hit = getIndexedAuditMessage();

        assertAuditMessage(hit, "rest", "authentication_failed");
        assertThat(hit.field("principal").getValue(), is((Object) "_principal"));
        assertThat("127.0.0.1", equalTo(hit.field("origin_address").getValue()));
        assertThat("_uri", equalTo(hit.field("uri").getValue()));
        assertThat((String) hit.field("origin_type").getValue(), is("rest"));
        assertThat(hit.field("request_body").getValue(), notNullValue());
    }

    @Test
    public void testAuthenticationFailed_Rest_NoToken() throws Exception {
        initialize();
        RestRequest request = mockRestRequest();
        auditor.authenticationFailed(request);
        awaitIndexCreation(resolveIndexName());

        SearchHit hit = getIndexedAuditMessage();

        assertAuditMessage(hit, "rest", "authentication_failed");
        assertThat(hit.field("principal"), nullValue());
        assertThat("127.0.0.1", equalTo(hit.field("origin_address").getValue()));
        assertThat("_uri", equalTo(hit.field("uri").getValue()));
        assertThat((String) hit.field("origin_type").getValue(), is("rest"));
        assertThat(hit.field("request_body").getValue(), notNullValue());
    }

    @Test(expected = IndexNotFoundException.class)
    public void testAuthenticationFailed_Rest_Muted() throws Exception {
        initialize("authentication_failed");
        RestRequest request = mockRestRequest();
        auditor.authenticationFailed(new MockToken(), request);
        getClient().prepareExists(resolveIndexName()).execute().actionGet();
    }

    @Test(expected = IndexNotFoundException.class)
    public void testAuthenticationFailed_Rest_NoToken_Muted() throws Exception {
        initialize("authentication_failed");
        RestRequest request = mockRestRequest();
        auditor.authenticationFailed(request);
        getClient().prepareExists(resolveIndexName()).execute().actionGet();
    }

    @Test
    public void testAuthenticationFailed_Transport_Realm() throws Exception {

        initialize();
        TransportMessage message = randomFrom(new RemoteHostMockMessage(), new LocalHostMockMessage(), new MockIndicesTransportMessage());
        auditor.authenticationFailed("_realm", new MockToken(), "_action", message);
        awaitIndexCreation(resolveIndexName());

        SearchHit hit = getIndexedAuditMessage();

        assertAuditMessage(hit, "transport", "authentication_failed");

        if (message instanceof RemoteHostMockMessage) {
            assertEquals(remoteHostAddress(), hit.field("origin_address").getValue());
        } else {
            assertEquals("local[local_host]", hit.field("origin_address").getValue());
        }

        assertEquals("transport", hit.field("origin_type").getValue());
        assertEquals("_principal", hit.field("principal").getValue());
        assertEquals("_action", hit.field("action").getValue());
        assertEquals("_realm", hit.field("realm").getValue());
        if (message instanceof IndicesRequest) {
            List<Object> indices = hit.field("indices").getValues();
            assertThat(indices, contains((Object[]) ((IndicesRequest)message).indices()));
        }
    }

    @Test(expected = IndexNotFoundException.class)
    public void testAuthenticationFailed_Transport_Realm_Muted() throws Exception {
        initialize("authentication_failed");
        TransportMessage message = randomFrom(new RemoteHostMockMessage(), new LocalHostMockMessage(), new MockIndicesTransportMessage());
        auditor.authenticationFailed("_realm", new MockToken(), "_action", message);
        getClient().prepareExists(resolveIndexName()).execute().actionGet();
    }

    @Test
    public void testAuthenticationFailed_Rest_Realm() throws Exception {

        initialize();
        RestRequest request = mockRestRequest();
        auditor.authenticationFailed("_realm", new MockToken(), request);
        awaitIndexCreation(resolveIndexName());

        SearchHit hit = getIndexedAuditMessage();

        assertAuditMessage(hit, "rest", "authentication_failed");
        assertThat("127.0.0.1", equalTo(hit.field("origin_address").getValue()));
        assertThat("_uri", equalTo(hit.field("uri").getValue()));
        assertEquals("_realm", hit.field("realm").getValue());
        assertThat((String) hit.field("origin_type").getValue(), is("rest"));
        assertThat(hit.field("request_body").getValue(), notNullValue());
    }

    @Test(expected = IndexNotFoundException.class)
    public void testAuthenticationFailed_Rest_Realm_Muted() throws Exception {
        initialize("authentication_failed");
        RestRequest request = mockRestRequest();
        auditor.authenticationFailed("_realm", new MockToken(), request);
        getClient().prepareExists(resolveIndexName()).execute().actionGet();
    }

    @Test
    public void testAccessGranted() throws Exception {

        initialize();
        TransportMessage message = randomFrom(new RemoteHostMockMessage(), new LocalHostMockMessage(), new MockIndicesTransportMessage());
        auditor.accessGranted(new User.Simple("_username", "r1"), "_action", message);
        awaitIndexCreation(resolveIndexName());

        SearchHit hit = getIndexedAuditMessage();
        assertAuditMessage(hit, "transport", "access_granted");
        assertEquals("transport", hit.field("origin_type").getValue());
        assertEquals("_username", hit.field("principal").getValue());
        assertEquals("_action", hit.field("action").getValue());
        if (message instanceof IndicesRequest) {
            List<Object> indices = hit.field("indices").getValues();
            assertThat(indices, contains((Object[]) ((IndicesRequest)message).indices()));
        }
    }

    @Test(expected = IndexNotFoundException.class)
    public void testAccessGranted_Muted() throws Exception {
        initialize("access_granted");
        TransportMessage message = randomFrom(new RemoteHostMockMessage(), new LocalHostMockMessage(), new MockIndicesTransportMessage());
        auditor.accessGranted(new User.Simple("_username", "r1"), "_action", message);
        getClient().prepareExists(resolveIndexName()).execute().actionGet();
    }

    @Test
    public void testSystemAccessGranted() throws Exception {
        initialize(new String[] { "system_access_granted" }, null);
        TransportMessage message = randomBoolean() ? new RemoteHostMockMessage() : new LocalHostMockMessage();
        auditor.accessGranted(User.SYSTEM, "internal:_action", message);
        awaitIndexCreation(resolveIndexName());

        SearchHit hit = getIndexedAuditMessage();
        assertAuditMessage(hit, "transport", "access_granted");
        assertEquals("transport", hit.field("origin_type").getValue());
        assertEquals(User.SYSTEM.principal(), hit.field("principal").getValue());
        assertEquals("internal:_action", hit.field("action").getValue());
    }

    @Test(expected = IndexNotFoundException.class)
    public void testSystemAccessGranted_Muted() throws Exception {
        initialize();
        TransportMessage message = randomBoolean() ? new RemoteHostMockMessage() : new LocalHostMockMessage();
        auditor.accessGranted(User.SYSTEM, "internal:_action", message);
        getClient().prepareExists(resolveIndexName()).execute().actionGet();
        awaitIndexCreation(resolveIndexName());
    }

    @Test
    public void testAccessDenied() throws Exception {

        initialize();
        TransportMessage message = randomFrom(new RemoteHostMockMessage(), new LocalHostMockMessage(), new MockIndicesTransportMessage());
        auditor.accessDenied(new User.Simple("_username", "r1"), "_action", message);
        awaitIndexCreation(resolveIndexName());

        SearchHit hit = getIndexedAuditMessage();
        assertAuditMessage(hit, "transport", "access_denied");
        assertEquals("transport", hit.field("origin_type").getValue());
        assertEquals("_username", hit.field("principal").getValue());
        assertEquals("_action", hit.field("action").getValue());
        if (message instanceof IndicesRequest) {
            List<Object> indices = hit.field("indices").getValues();
            assertThat(indices, contains((Object[]) ((IndicesRequest)message).indices()));
        }
    }

    @Test(expected = IndexNotFoundException.class)
    public void testAccessDenied_Muted() throws Exception {
        initialize("access_denied");
        TransportMessage message = randomFrom(new RemoteHostMockMessage(), new LocalHostMockMessage(), new MockIndicesTransportMessage());
        auditor.accessDenied(new User.Simple("_username", "r1"), "_action", message);
        getClient().prepareExists(resolveIndexName()).execute().actionGet();
    }

    @Test
    public void testTamperedRequest() throws Exception {

        initialize();
        TransportRequest message = new RemoteHostMockTransportRequest();
        auditor.tamperedRequest(new User.Simple("_username", "r1"), "_action", message);
        awaitIndexCreation(resolveIndexName());

        SearchHit hit = getIndexedAuditMessage();

        assertAuditMessage(hit, "transport", "tampered_request");
        assertEquals("transport", hit.field("origin_type").getValue());
        assertEquals("_username", hit.field("principal").getValue());
        assertEquals("_action", hit.field("action").getValue());
    }

    @Test(expected = IndexNotFoundException.class)
    public void testTamperedRequest_Muted() throws Exception {
        initialize("tampered_request");
        TransportRequest message = new RemoteHostMockTransportRequest();
        auditor.tamperedRequest(new User.Simple("_username", "r1"), "_action", message);
        getClient().prepareExists(resolveIndexName()).execute().actionGet();
    }

    @Test
    public void testConnectionGranted() throws Exception {

        initialize();
        InetAddress inetAddress = InetAddress.getLocalHost();
        ShieldIpFilterRule rule = IPFilter.DEFAULT_PROFILE_ACCEPT_ALL;
        auditor.connectionGranted(inetAddress, "default", rule);
        awaitIndexCreation(resolveIndexName());

        SearchHit hit = getIndexedAuditMessage();

        assertAuditMessage(hit, "ip_filter", "connection_granted");
        assertEquals("allow default:accept_all", hit.field("rule").getValue());
        assertEquals("default", hit.field("transport_profile").getValue());
    }

    @Test(expected = IndexNotFoundException.class)
    public void testConnectionGranted_Muted() throws Exception {
        initialize("connection_granted");
        InetAddress inetAddress = InetAddress.getLocalHost();
        ShieldIpFilterRule rule = IPFilter.DEFAULT_PROFILE_ACCEPT_ALL;
        auditor.connectionGranted(inetAddress, "default", rule);
        getClient().prepareExists(resolveIndexName()).execute().actionGet();
    }

    @Test
    public void testConnectionDenied() throws Exception {

        initialize();
        InetAddress inetAddress = InetAddress.getLocalHost();
        ShieldIpFilterRule rule = new ShieldIpFilterRule(false, "_all");
        auditor.connectionDenied(inetAddress, "default", rule);
        awaitIndexCreation(resolveIndexName());

        SearchHit hit = getIndexedAuditMessage();

        assertAuditMessage(hit, "ip_filter", "connection_denied");
        assertEquals("deny _all", hit.field("rule").getValue());
        assertEquals("default", hit.field("transport_profile").getValue());
    }

    @Test(expected = IndexNotFoundException.class)
    public void testConnectionDenied_Muted() throws Exception {
        initialize("connection_denied");
        InetAddress inetAddress = InetAddress.getLocalHost();
        ShieldIpFilterRule rule = new ShieldIpFilterRule(false, "_all");
        auditor.connectionDenied(inetAddress, "default", rule);
        getClient().prepareExists(resolveIndexName()).execute().actionGet();
    }

    private void assertAuditMessage(SearchHit hit, String layer, String type) {
        assertThat(hit.field("@timestamp").getValue(), notNullValue());
        DateTime dateTime = ISODateTimeFormat.dateTimeParser().withZoneUTC().parseDateTime((String) hit.field("@timestamp").getValue());
        assertThat(dateTime.isBefore(DateTime.now(DateTimeZone.UTC)), is(true));

        assertThat(clusterService().localNode().getHostName(), equalTo(hit.field("node_host_name").getValue()));
        assertThat(clusterService().localNode().getHostAddress(), equalTo(hit.field("node_host_address").getValue()));

        assertEquals(layer, hit.field("layer").getValue());
        assertEquals(type, hit.field("event_type").getValue());
    }

    private static class LocalHostMockMessage extends TransportMessage<LocalHostMockMessage> {
        LocalHostMockMessage() {
            remoteAddress(new LocalTransportAddress("local_host"));
        }
    }

    private static class RemoteHostMockMessage extends TransportMessage<RemoteHostMockMessage> {
        RemoteHostMockMessage() throws Exception {
            remoteAddress(new InetSocketTransportAddress(InetAddress.getLocalHost(), 1234));
        }
    }

    private static class RemoteHostMockTransportRequest extends TransportRequest {
        RemoteHostMockTransportRequest() throws Exception {
            remoteAddress(new InetSocketTransportAddress(InetAddress.getLocalHost(), 1234));
        }
    }

    private static class MockIndicesTransportMessage extends RemoteHostMockMessage implements IndicesRequest {
        MockIndicesTransportMessage() throws Exception {
            super();
        }

        @Override
        public String[] indices() {
            return new String[] { "foo", "bar", "baz" };
        }

        @Override
        public IndicesOptions indicesOptions() {
            return null;
        }
    }

    private static class MockToken implements AuthenticationToken {
        @Override
        public String principal() {
            return "_principal";
        }

        @Override
        public Object credentials() {
            fail("it's not allowed to print the credentials of the auth token");
            return null;
        }

        @Override
        public void clearCredentials() {
        }
    }

    private RestRequest mockRestRequest() {
        RestRequest request = mock(RestRequest.class);
        when(request.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 9200));
        when(request.uri()).thenReturn("_uri");
        return request;
    }

    private SearchHit getIndexedAuditMessage() {

        SearchResponse response = getClient().prepareSearch(resolveIndexName())
                .setTypes(IndexAuditTrail.DOC_TYPE)
                .addFields(fieldList())
                .execute().actionGet();

        assertEquals(1, response.getHits().getTotalHits());
        return response.getHits().getHits()[0];
    }

    private String[] fieldList() {
        return new String[] {
                "@timestamp",
                "node_name",
                "node_host_name",
                "node_host_address",
                "layer",
                "event_type",
                "origin_address",
                "origin_type",
                "principal",
                "action",
                "indices",
                "request",
                "request_body",
                "uri",
                "realm",
                "transport_profile",
                "rule"
        };
    }

    private void awaitIndexCreation(final String indexName) throws InterruptedException {
        boolean found = awaitBusy(new Predicate<Void>() {
            @Override
            public boolean apply(Void o) {
                try {
                    ExistsResponse response =
                            getClient().prepareExists(indexName).execute().actionGet();
                    return response.exists();
                } catch (Exception e) {
                    return false;
                }
            }
        });
        assertThat("[" + indexName + "] does not exist!", found, is(true));

        GetSettingsResponse response = getClient().admin().indices().prepareGetSettings(indexName).execute().actionGet();
        assertThat(response.getSetting(indexName, "index.number_of_shards"), is(Integer.toString(numShards)));
        assertThat(response.getSetting(indexName, "index.number_of_replicas"), is(Integer.toString(numReplicas)));
    }

    private String resolveIndexName() {
        return IndexNameResolver.resolve(IndexAuditTrail.INDEX_NAME_PREFIX, DateTime.now(DateTimeZone.UTC), rollover);
    }

    static String remoteHostAddress() throws Exception {
        return InetAddress.getLocalHost().getHostAddress();
    }
}

