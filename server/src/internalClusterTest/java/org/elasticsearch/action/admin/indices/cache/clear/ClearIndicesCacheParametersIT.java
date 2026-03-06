/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.action.admin.indices.cache.clear;

import org.elasticsearch.action.admin.indices.stats.IndicesStatsResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexModule;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.indices.IndicesQueryCache;
import org.elasticsearch.indices.IndicesRequestCache;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.InternalSettingsPlugin;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

/**
 * Tests the clear cache API with various query parameter combinations to verify
 * which caches are actually cleared. This exercises the full REST flow: query
 * parameter parsing through {@code RestClearIndicesCacheAction}, transport
 * action dispatch, and the cache clearing logic in {@code IndicesService} and
 * {@code IndexService}.
 *
 * The three caches tested are:
 * <ul>
 *   <li><b>query</b> – the node-level query/filter cache</li>
 *   <li><b>request</b> – the shard-level request cache</li>
 *   <li><b>fielddata</b> – the fielddata cache (for text fields with fielddata enabled)</li>
 * </ul>
 */
@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST, numDataNodes = 1, numClientNodes = 0)
public class ClearIndicesCacheParametersIT extends ESIntegTestCase {

    private static final String INDEX = "test_cache";

    @Override
    protected boolean addMockHttpTransport() {
        return false;
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return List.of(InternalSettingsPlugin.class, getTestTransportPlugin());
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal, Settings otherSettings) {
        return Settings.builder()
            .put(super.nodeSettings(nodeOrdinal, otherSettings))
            .put(IndicesService.INDICES_CACHE_CLEAN_INTERVAL_SETTING.getKey(), "1ms")
            .put(IndicesQueryCache.INDICES_QUERIES_CACHE_ALL_SEGMENTS_SETTING.getKey(), true)
            .build();
    }

    private Settings.Builder indexSettingsBuilder() {
        return Settings.builder()
            .put(IndexModule.INDEX_QUERY_CACHE_EVERYTHING_SETTING.getKey(), true)
            .put(IndexModule.INDEX_QUERY_CACHE_ENABLED_SETTING.getKey(), true)
            .put(IndicesRequestCache.INDEX_CACHE_REQUEST_ENABLED_SETTING.getKey(), true)
            .put(IndexSettings.INDEX_SOFT_DELETES_RETENTION_OPERATIONS_SETTING.getKey(), 0)
            .put("index.number_of_shards", 1)
            .put("index.number_of_replicas", 0);
    }

    private void createTestIndex() {
        assertAcked(
            indicesAdmin().prepareCreate(INDEX).setSettings(indexSettingsBuilder()).setMapping("field", "type=text,fielddata=true")
        );
        ensureGreen(INDEX);

        prepareIndex(INDEX).setId("1").setSource("field", "value1").get();
        prepareIndex(INDEX).setId("2").setSource("field", "value2").get();
        indicesAdmin().prepareRefresh(INDEX).get();
    }

    /**
     * Populate all three caches (query, request, fielddata) so that subsequent
     * clear-cache calls can be verified via stats.
     */
    private void populateAllCaches() throws Exception {
        assertBusy(() -> {
            prepareSearch(INDEX).setPostFilter(QueryBuilders.termQuery("field", "value1")).addSort("field", SortOrder.ASC).get().decRef();

            assertThat(getQueryCacheMemory(), greaterThan(0L));
        });

        assertBusy(() -> {
            prepareSearch(INDEX).setSearchType(SearchType.QUERY_THEN_FETCH).setSize(0).get().decRef();

            assertThat(getRequestCacheMemory(), greaterThan(0L));
        });

        assertThat(getFieldDataMemory(), greaterThan(0L));
    }

    private long getQueryCacheMemory() {
        IndicesStatsResponse stats = indicesAdmin().prepareStats(INDEX).setQueryCache(true).get();
        return stats.getTotal().getQueryCache().getMemorySizeInBytes();
    }

    private long getRequestCacheMemory() {
        IndicesStatsResponse stats = indicesAdmin().prepareStats(INDEX).setRequestCache(true).get();
        return stats.getTotal().getRequestCache().getMemorySizeInBytes();
    }

    private long getFieldDataMemory() {
        IndicesStatsResponse stats = indicesAdmin().prepareStats(INDEX).setFieldData(true).get();
        return stats.getTotal().getFieldData().getMemorySizeInBytes();
    }

    /**
     * Issue a POST to /{index}/_cache/clear with the given query parameters
     * via the real HTTP endpoint, exercising the full REST parsing flow.
     */
    private void clearCacheViaRest(String... params) throws IOException {
        assert params.length % 2 == 0 : "params must be key-value pairs";
        Request request = new Request("POST", "/" + INDEX + "/_cache/clear");
        for (int i = 0; i < params.length; i += 2) {
            request.addParameter(params[i], params[i + 1]);
        }
        Response response = getRestClient().performRequest(request);
        assertThat(response.getStatusLine().getStatusCode(), equalTo(200));
    }

    private record CacheState(long queryCacheMemory, long requestCacheMemory, long fieldDataMemory) {

        boolean queryCachePopulated() {
            return queryCacheMemory > 0;
        }

        boolean requestCachePopulated() {
            return requestCacheMemory > 0;
        }

        boolean fieldDataPopulated() {
            return fieldDataMemory > 0;
        }
    }

    private CacheState getCacheState() {
        return new CacheState(getQueryCacheMemory(), getRequestCacheMemory(), getFieldDataMemory());
    }

    // --- Test cases covering all parameter combinations ---

    /**
     * No parameters: should clear all caches (query, request, and fielddata).
     */
    public void testClearAllCaches_noParams() throws Exception {
        createTestIndex();
        populateAllCaches();

        clearCacheViaRest();
        Thread.sleep(100);

        CacheState state = getCacheState();
        assertThat("query cache should be cleared", state.queryCacheMemory(), equalTo(0L));
        assertThat("request cache should be cleared", state.requestCacheMemory(), equalTo(0L));
        assertThat("fielddata should be cleared", state.fieldDataMemory(), equalTo(0L));
    }

    /**
     * Only request=true: should clear ONLY the request cache.
     * <p>
     * This test documents the bug described in
     * <a href="https://github.com/elastic/elasticsearch/pull/94512">#94512</a>:
     * specifying only {@code request=true} actually clears ALL caches because
     * {@code IndexService.clearCaches(false, false)} interprets both booleans
     * being {@code false} as "nothing specified, clear everything."
     */
    public void testClearOnlyRequestCache() throws Exception {
        createTestIndex();
        populateAllCaches();

        clearCacheViaRest("request", "true");
        Thread.sleep(100);

        CacheState state = getCacheState();
        assertThat("request cache should be cleared", state.requestCacheMemory(), equalTo(0L));
        // These assertions document the CURRENT (buggy) behavior:
        // query cache and fielddata are also cleared even though only request=true was specified.
        // When the bug is fixed, these should be changed to greaterThan(0L).
        assertThat("[BUG] query cache is cleared even though only request=true was specified", state.queryCacheMemory(), equalTo(0L));
        assertThat("[BUG] fielddata is cleared even though only request=true was specified", state.fieldDataMemory(), equalTo(0L));
    }

    /**
     * Only query=true: should clear ONLY the query cache.
     */
    public void testClearOnlyQueryCache() throws Exception {
        createTestIndex();
        populateAllCaches();

        clearCacheViaRest("query", "true");
        Thread.sleep(100);

        CacheState state = getCacheState();
        assertThat("query cache should be cleared", state.queryCacheMemory(), equalTo(0L));
        assertThat("request cache should NOT be cleared", state.requestCachePopulated(), equalTo(true));
        assertThat("fielddata should NOT be cleared", state.fieldDataPopulated(), equalTo(true));
    }

    /**
     * Only fielddata=true: should clear ONLY fielddata.
     */
    public void testClearOnlyFieldData() throws Exception {
        createTestIndex();
        populateAllCaches();

        clearCacheViaRest("fielddata", "true");
        Thread.sleep(100);

        CacheState state = getCacheState();
        assertThat("fielddata should be cleared", state.fieldDataMemory(), equalTo(0L));
        assertThat("query cache should NOT be cleared", state.queryCachePopulated(), equalTo(true));
        assertThat("request cache should NOT be cleared", state.requestCachePopulated(), equalTo(true));
    }

    /**
     * request=true and query=true: should clear request and query caches, but not fielddata.
     */
    public void testClearRequestAndQueryCache() throws Exception {
        createTestIndex();
        populateAllCaches();

        clearCacheViaRest("request", "true", "query", "true");
        Thread.sleep(100);

        CacheState state = getCacheState();
        assertThat("query cache should be cleared", state.queryCacheMemory(), equalTo(0L));
        assertThat("request cache should be cleared", state.requestCacheMemory(), equalTo(0L));
        assertThat("fielddata should NOT be cleared", state.fieldDataPopulated(), equalTo(true));
    }

    /**
     * request=true and fielddata=true: should clear request cache and fielddata, but not query cache.
     */
    public void testClearRequestAndFieldData() throws Exception {
        createTestIndex();
        populateAllCaches();

        clearCacheViaRest("request", "true", "fielddata", "true");
        Thread.sleep(100);

        CacheState state = getCacheState();
        assertThat("request cache should be cleared", state.requestCacheMemory(), equalTo(0L));
        assertThat("fielddata should be cleared", state.fieldDataMemory(), equalTo(0L));
        assertThat("query cache should NOT be cleared", state.queryCachePopulated(), equalTo(true));
    }

    /**
     * query=true and fielddata=true: should clear query cache and fielddata, but not request cache.
     */
    public void testClearQueryAndFieldData() throws Exception {
        createTestIndex();
        populateAllCaches();

        clearCacheViaRest("query", "true", "fielddata", "true");
        Thread.sleep(100);

        CacheState state = getCacheState();
        assertThat("query cache should be cleared", state.queryCacheMemory(), equalTo(0L));
        assertThat("fielddata should be cleared", state.fieldDataMemory(), equalTo(0L));
        assertThat("request cache should NOT be cleared", state.requestCachePopulated(), equalTo(true));
    }

    /**
     * All three set to true: should clear all caches.
     */
    public void testClearAllCaches_allTrue() throws Exception {
        createTestIndex();
        populateAllCaches();

        clearCacheViaRest("request", "true", "query", "true", "fielddata", "true");
        Thread.sleep(100);

        CacheState state = getCacheState();
        assertThat("query cache should be cleared", state.queryCacheMemory(), equalTo(0L));
        assertThat("request cache should be cleared", state.requestCacheMemory(), equalTo(0L));
        assertThat("fielddata should be cleared", state.fieldDataMemory(), equalTo(0L));
    }

    /**
     * request=false (explicitly): since the default is also false, this behaves
     * identically to no parameters — all caches are cleared.
     */
    public void testRequestExplicitlyFalse() throws Exception {
        createTestIndex();
        populateAllCaches();

        clearCacheViaRest("request", "false");
        Thread.sleep(100);

        CacheState state = getCacheState();
        assertThat("query cache should be cleared (same as no params)", state.queryCacheMemory(), equalTo(0L));
        assertThat("fielddata should be cleared (same as no params)", state.fieldDataMemory(), equalTo(0L));
        assertThat("request cache should be cleared (same as no params)", state.requestCacheMemory(), equalTo(0L));
    }

    /**
     * query=false (explicitly): since the default is also false, this behaves
     * identically to no parameters — all caches are cleared.
     */
    public void testQueryExplicitlyFalse() throws Exception {
        createTestIndex();
        populateAllCaches();

        clearCacheViaRest("query", "false");
        Thread.sleep(100);

        CacheState state = getCacheState();
        assertThat("query cache should be cleared (same as no params)", state.queryCacheMemory(), equalTo(0L));
        assertThat("fielddata should be cleared (same as no params)", state.fieldDataMemory(), equalTo(0L));
        assertThat("request cache should be cleared (same as no params)", state.requestCacheMemory(), equalTo(0L));
    }

    /**
     * fielddata=false (explicitly): since the default is also false, this behaves
     * identically to no parameters — all caches are cleared.
     */
    public void testFieldDataExplicitlyFalse() throws Exception {
        createTestIndex();
        populateAllCaches();

        clearCacheViaRest("fielddata", "false");
        Thread.sleep(100);

        CacheState state = getCacheState();
        assertThat("query cache should be cleared (same as no params)", state.queryCacheMemory(), equalTo(0L));
        assertThat("fielddata should be cleared (same as no params)", state.fieldDataMemory(), equalTo(0L));
        assertThat("request cache should be cleared (same as no params)", state.requestCacheMemory(), equalTo(0L));
    }

    /**
     * request=true with query=false and fielddata=false: this should clear only
     * the request cache, but due to the bug, all caches are cleared because the
     * explicit false values are indistinguishable from unset defaults.
     */
    public void testRequestTrueOthersFalse() throws Exception {
        createTestIndex();
        populateAllCaches();

        clearCacheViaRest("request", "true", "query", "false", "fielddata", "false");
        Thread.sleep(100);

        CacheState state = getCacheState();
        assertThat("request cache should be cleared", state.requestCacheMemory(), equalTo(0L));
        // These assertions document the CURRENT (buggy) behavior:
        assertThat("[BUG] query cache is cleared even though query=false was explicitly set", state.queryCacheMemory(), equalTo(0L));
        assertThat("[BUG] fielddata is cleared even though fielddata=false was explicitly set", state.fieldDataMemory(), equalTo(0L));
    }

    /**
     * query=true with request=false: should clear only the query cache.
     * Request cache should not be cleared since request=false.
     */
    public void testQueryTrueRequestFalse() throws Exception {
        createTestIndex();
        populateAllCaches();

        clearCacheViaRest("query", "true", "request", "false");
        Thread.sleep(100);

        CacheState state = getCacheState();
        assertThat("query cache should be cleared", state.queryCacheMemory(), equalTo(0L));
        assertThat("request cache should NOT be cleared", state.requestCachePopulated(), equalTo(true));
        assertThat("fielddata should NOT be cleared", state.fieldDataPopulated(), equalTo(true));
    }

    /**
     * fielddata=true with request=false: should clear only fielddata.
     * Request cache should not be cleared since request=false.
     */
    public void testFieldDataTrueRequestFalse() throws Exception {
        createTestIndex();
        populateAllCaches();

        clearCacheViaRest("fielddata", "true", "request", "false");
        Thread.sleep(100);

        CacheState state = getCacheState();
        assertThat("fielddata should be cleared", state.fieldDataMemory(), equalTo(0L));
        assertThat("query cache should NOT be cleared", state.queryCachePopulated(), equalTo(true));
        assertThat("request cache should NOT be cleared", state.requestCachePopulated(), equalTo(true));
    }

    /**
     * query=true with fielddata=false: should clear only the query cache.
     */
    public void testQueryTrueFieldDataFalse() throws Exception {
        createTestIndex();
        populateAllCaches();

        clearCacheViaRest("query", "true", "fielddata", "false");
        Thread.sleep(100);

        CacheState state = getCacheState();
        assertThat("query cache should be cleared", state.queryCacheMemory(), equalTo(0L));
        assertThat("fielddata should NOT be cleared", state.fieldDataPopulated(), equalTo(true));
        assertThat("request cache should NOT be cleared", state.requestCachePopulated(), equalTo(true));
    }

    /**
     * fielddata=true with query=false: should clear only fielddata.
     */
    public void testFieldDataTrueQueryFalse() throws Exception {
        createTestIndex();
        populateAllCaches();

        clearCacheViaRest("fielddata", "true", "query", "false");
        Thread.sleep(100);

        CacheState state = getCacheState();
        assertThat("fielddata should be cleared", state.fieldDataMemory(), equalTo(0L));
        assertThat("query cache should NOT be cleared", state.queryCachePopulated(), equalTo(true));
        assertThat("request cache should NOT be cleared", state.requestCachePopulated(), equalTo(true));
    }

    /**
     * All three explicitly false: since all default to false this is the same as
     * no parameters, so all caches are cleared. This is arguably surprising
     * behavior — explicitly saying "don't clear any of these" still clears
     * everything.
     */
    public void testAllExplicitlyFalse() throws Exception {
        createTestIndex();
        populateAllCaches();

        clearCacheViaRest("request", "false", "query", "false", "fielddata", "false");
        Thread.sleep(100);

        CacheState state = getCacheState();
        assertThat("query cache is cleared (same as no params)", state.queryCacheMemory(), equalTo(0L));
        assertThat("fielddata is cleared (same as no params)", state.fieldDataMemory(), equalTo(0L));
        assertThat("request cache is cleared (same as no params)", state.requestCacheMemory(), equalTo(0L));
    }

    /**
     * query=true and fielddata=true with request=false: should clear query cache
     * and fielddata but not request cache.
     */
    public void testQueryAndFieldDataTrueRequestFalse() throws Exception {
        createTestIndex();
        populateAllCaches();

        clearCacheViaRest("query", "true", "fielddata", "true", "request", "false");
        Thread.sleep(100);

        CacheState state = getCacheState();
        assertThat("query cache should be cleared", state.queryCacheMemory(), equalTo(0L));
        assertThat("fielddata should be cleared", state.fieldDataMemory(), equalTo(0L));
        assertThat("request cache should NOT be cleared", state.requestCachePopulated(), equalTo(true));
    }

    /**
     * request=true and query=true with fielddata=false: should clear request and
     * query caches but not fielddata.
     */
    public void testRequestAndQueryTrueFieldDataFalse() throws Exception {
        createTestIndex();
        populateAllCaches();

        clearCacheViaRest("request", "true", "query", "true", "fielddata", "false");
        Thread.sleep(100);

        CacheState state = getCacheState();
        assertThat("query cache should be cleared", state.queryCacheMemory(), equalTo(0L));
        assertThat("request cache should be cleared", state.requestCacheMemory(), equalTo(0L));
        assertThat("fielddata should NOT be cleared", state.fieldDataPopulated(), equalTo(true));
    }

    /**
     * request=true and fielddata=true with query=false: should clear request
     * cache and fielddata but not query cache.
     */
    public void testRequestAndFieldDataTrueQueryFalse() throws Exception {
        createTestIndex();
        populateAllCaches();

        clearCacheViaRest("request", "true", "fielddata", "true", "query", "false");
        Thread.sleep(100);

        CacheState state = getCacheState();
        assertThat("request cache should be cleared", state.requestCacheMemory(), equalTo(0L));
        assertThat("fielddata should be cleared", state.fieldDataMemory(), equalTo(0L));
        assertThat("query cache should NOT be cleared", state.queryCachePopulated(), equalTo(true));
    }
}
