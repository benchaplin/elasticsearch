/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.action.search;

import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TotalHits;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.breaker.NoopCircuitBreaker;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.lucene.search.TopDocsAndMaxScore;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.common.util.concurrent.EsExecutors.TaskTrackingConfig;
import org.elasticsearch.common.util.concurrent.EsThreadPoolExecutor;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.search.DocValueFormat;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.SearchModule;
import org.elasticsearch.search.SearchPhaseResult;
import org.elasticsearch.search.SearchShardTarget;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.AggregationReduceContext;
import org.elasticsearch.search.aggregations.InternalAggregations;
import org.elasticsearch.search.aggregations.metrics.InternalTopHits;
import org.elasticsearch.search.aggregations.metrics.TopHitsAggregationBuilder;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregator;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.internal.ShardSearchContextId;
import org.elasticsearch.search.lookup.Source;
import org.elasticsearch.search.query.QuerySearchResult;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.TestEsExecutors;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xcontent.XContentType;
import org.junit.After;
import org.junit.Before;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Collections.emptyList;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.mock;

/**
 * Debuggable scenarios illustrating partial vs final reductions in {@link QueryPhaseResultConsumer}.
 *
 * <h2>How reductions are triggered</h2>
 * {@link QueryPhaseResultConsumer#consume} accumulates shard results in an in-memory buffer.
 * It checks {@code buffer.size()} <em>before</em> adding the current result, then:
 * <ul>
 *   <li>If {@code buffer.size() + (hasPartialReduce ? 1 : 0) >= batchReduceSize}: the accumulated
 *       buffer is moved to a {@code MergeTask} queued on the executor,
 *       a fresh buffer is started, and the current shard lands in the fresh buffer.</li>
 *   <li>Otherwise: the current shard is added to the existing buffer and its {@code next} callback
 *       is called immediately.</li>
 * </ul>
 * {@code batchReduceSize = Math.min(request.getBatchedReduceSize(), expectedResultSize)} (when
 * the query has top-docs or aggregations). For {@code N} shards and batch size {@code B}:
 * <ul>
 *   <li>The first {@code B} shard arrivals fill the buffer without triggering (buffer grows from
 *       0 to B-1 when they arrive).</li>
 *   <li>Shard {@code B} (0-indexed) sees a full buffer of size {@code B} and triggers partial#1;
 *       it then lands in a fresh buffer.</li>
 *   <li>For {@code B=2}: every subsequent shard triggers its own partial (hasPartialReduce=true
 *       makes the effective count hit the threshold immediately). Total = N − B.</li>
 *   <li>For {@code B≥3}: subsequent shards need to accumulate B−1 in the post-trigger buffer
 *       before the next trigger fires.</li>
 *   <li>For {@code N ≤ B}: the buffer never fills while shard results are arriving; all shards
 *       stay in the buffer for the final reduction (0 partial reductions).</li>
 * </ul>
 *
 * <h2>Partial reduction (QueryPhaseResultConsumer.partialReduce)</h2>
 * Runs asynchronously on the executor. Merges top-docs via {@code TopDocs.merge} (Lucene) and
 * aggregations via {@link InternalAggregations#topLevelReduce}. The {@link AggregationReduceContext}
 * has {@code isFinalReduce() == false}: pipeline aggregations are NOT applied and lossy operations
 * (e.g. terms {@code min_doc_count} filtering) are skipped. Produces a
 * {@link QueryPhaseResultConsumer.MergeResult} holding serialized or in-memory aggregations wrapped
 * in {@link org.elasticsearch.common.io.stream.DelayableWriteable}.
 *
 * <h2>Final reduction (QueryPhaseResultConsumer.reduce)</h2>
 * Called once after all shards respond. Folds the remaining buffer plus all accumulated
 * {@link QueryPhaseResultConsumer.MergeResult}s (local partial chain + any batched results from
 * data nodes) into one pass with a {@link AggregationReduceContext} where
 * {@code isFinalReduce() == true}. Only at this point does
 * {@link InternalAggregations#maybeExecuteFinalReduce} apply pipeline aggregators (per-agg
 * {@code reducePipelines} + sibling pipelines like {@code max_bucket}).
 *
 * <h2>Breakpoints for debugging</h2>
 * <ul>
 *   <li>{@code QueryPhaseResultConsumer.partialReduce} – inspect {@code toConsume},
 *       {@code lastMerge}, and the returned {@code MergeResult}</li>
 *   <li>{@code QueryPhaseResultConsumer.reduce} – inspect {@code buffer}, {@code mergeResult},
 *       and the produced {@code ReducedQueryPhase}</li>
 *   <li>{@code InternalAggregations.topLevelReduce} – observe {@code context.isFinalReduce()}</li>
 *   <li>{@code InternalAggregations.maybeExecuteFinalReduce} – see pipeline gating logic</li>
 *   <li>{@code SearchPhaseController.mergeTopDocs} – watch Lucene's {@code TopDocs.merge}</li>
 *   <li>{@code AggregationReduceContext.transferTopHitsForRelease} – track SearchHits lifecycle</li>
 * </ul>
 */
public class QueryPhaseReductionScenariosTests extends ESTestCase {

    private SearchPhaseController searchPhaseController;
    private ThreadPool threadPool;
    private EsThreadPoolExecutor executor;

    @Override
    protected NamedWriteableRegistry writableRegistry() {
        return new NamedWriteableRegistry(new ArrayList<>(new SearchModule(Settings.EMPTY, emptyList()).getNamedWriteables()));
    }

    @Before
    public void setup() {
        searchPhaseController = new SearchPhaseController((t, s) -> new AggregationReduceContext.Builder() {
            @Override
            public AggregationReduceContext forPartialReduction(@Nullable Collection<SearchHits> topHitsToRelease) {
                return new AggregationReduceContext.ForPartial(
                    BigArrays.NON_RECYCLING_INSTANCE,
                    null,
                    t,
                    mock(AggregationBuilder.class),
                    b -> {},
                    topHitsToRelease
                );
            }

            @Override
            public AggregationReduceContext forFinalReduction(@Nullable Collection<SearchHits> topHitsToRelease) {
                return new AggregationReduceContext.ForFinal(
                    BigArrays.NON_RECYCLING_INSTANCE,
                    null,
                    t,
                    mock(AggregationBuilder.class),
                    b -> {},
                    PipelineAggregator.PipelineTree.EMPTY,
                    topHitsToRelease
                );
            }
        });
        threadPool = new TestThreadPool(QueryPhaseReductionScenariosTests.class.getName());
        executor = EsExecutors.newFixed(
            "test",
            1,
            10,
            TestEsExecutors.testOnlyDaemonThreadFactory("test"),
            threadPool.getThreadContext(),
            TaskTrackingConfig.DEFAULT
        );
    }

    @After
    public void cleanup() {
        executor.shutdownNow();
        terminate(threadPool);
    }

    /**
     * Ten shards, each returning one scored hit (shard i has score i+1); top 3 requested.
     * {@code batchReduceSize=2}: shards 0+1 accumulate without triggering; shard 2 arrives
     * and sees buffer size 2 → partial#1 is queued with shards 0+1; shard 2 lands in the new
     * buffer. Every subsequent shard (3–9) arrives while a partial is in-flight, so
     * {@code hasPartialReduce=true} makes the effective size hit the threshold immediately,
     * and each triggers its own partial. Total = 10 − 2 = 8 partial reductions.
     *
     * <p>Each partial calls {@code SearchPhaseController.mergeTopDocs} which delegates to Lucene's
     * {@code TopDocs.merge(from=0, topN=3, ...)}. After every partial, only the 3 highest-scoring
     * docs survive in the {@code MergeResult.reducedTopDocs}. The final {@code reduce()} wraps the
     * last partial result (shard 9 in the buffer, shard 8's partial as {@code mergeResult}).</p>
     */
    public void testScoreBasedTopDocsMerging() throws Exception {
        int numShards = 10;
        int size = 3;

        SearchRequest request = new SearchRequest("index");
        request.source(new SearchSourceBuilder().size(size));
        request.setBatchedReduceSize(2);

        RecordingProgressListener listener = new RecordingProgressListener();
        CountDownLatch latch = new CountDownLatch(numShards);

        try (
            QueryPhaseResultConsumer consumer = new QueryPhaseResultConsumer(
                request,
                executor,
                new NoopCircuitBreaker(CircuitBreaker.REQUEST),
                searchPhaseController,
                () -> false,
                listener,
                numShards,
                e -> {}
            )
        ) {
            for (int i = 0; i < numShards; i++) {
                SearchShardTarget target = new SearchShardTarget("node", new ShardId("index", "uuid", i), null);
                QuerySearchResult result = new QuerySearchResult(new ShardSearchContextId("", i), target, null);
                try {
                    float score = i + 1.0f;
                    ScoreDoc[] scoreDocs = { new ScoreDoc(i * 100, score) };
                    TopDocs topDocs = new TopDocs(new TotalHits(1, TotalHits.Relation.EQUAL_TO), scoreDocs);
                    result.topDocs(new TopDocsAndMaxScore(topDocs, score), new DocValueFormat[0]);
                    result.setShardIndex(i);
                    result.size(size);
                    consumer.consumeResult(result, latch::countDown);
                } finally {
                    result.decRef();
                }
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS));

            SearchPhaseController.ReducedQueryPhase phase = consumer.reduce();

            // 10 shards × 1 hit = 10 total; only the top 3 (shards 9, 8, 7 with scores 10, 9, 8) survive
            assertThat(phase.totalHits().value(), equalTo(10L));
            assertThat(phase.sortedTopDocs().scoreDocs().length, equalTo(size));
            assertThat((double) phase.sortedTopDocs().scoreDocs()[0].score, closeTo(10.0, 0.001));
            assertThat((double) phase.sortedTopDocs().scoreDocs()[1].score, closeTo(9.0, 0.001));
            assertThat((double) phase.sortedTopDocs().scoreDocs()[2].score, closeTo(8.0, 0.001));

            // batchReduceSize=2 with 10 shards → 10−2=8 partial reductions; final is always exactly 1
            assertThat(listener.partialReduceCount.get(), equalTo(8));
            assertThat(listener.finalReduceCount.get(), equalTo(1));
        }
    }

    /**
     * Four shards with no explicit {@code batchedReduceSize} (defaults to 512).
     * {@code batchReduceSize = Math.min(512, 4) = 4}. Because the buffer is checked
     * <em>before</em> adding the arriving shard, a trigger fires when the buffer already
     * holds {@code batchReduceSize} elements. With 4 shards, the buffer never reaches 4
     * items while shards are arriving:
     * <ul>
     *   <li>Shard 0 arrives: buffer=[], size 0 &lt; 4 → no trigger → buffer=[0]</li>
     *   <li>Shard 3 arrives: buffer=[0,1,2], size 3 &lt; 4 → no trigger → buffer=[0,1,2,3]</li>
     * </ul>
     * All 4 results land in the buffer. When {@code reduce()} is called, {@code mergeResult==null}
     * and the buffer holds all 4. The final reduction processes them in one single pass — no
     * {@code partialReduce} call is ever made. Put a breakpoint in {@code reduce()} and
     * observe that {@code buffer.size()==4} and {@code mergeResult==null}.
     */
    public void testAllShardsInBufferNoPartialReductions() throws Exception {
        int numShards = 4;

        SearchRequest request = new SearchRequest("index");
        request.source(new SearchSourceBuilder().size(10));
        // Do not set batchedReduceSize; default 512 adapts to min(512, 4) = 4

        RecordingProgressListener listener = new RecordingProgressListener();
        CountDownLatch latch = new CountDownLatch(numShards);

        try (
            QueryPhaseResultConsumer consumer = new QueryPhaseResultConsumer(
                request,
                executor,
                new NoopCircuitBreaker(CircuitBreaker.REQUEST),
                searchPhaseController,
                () -> false,
                listener,
                numShards,
                e -> {}
            )
        ) {
            for (int i = 0; i < numShards; i++) {
                SearchShardTarget target = new SearchShardTarget("node", new ShardId("index", "uuid", i), null);
                QuerySearchResult result = new QuerySearchResult(new ShardSearchContextId("", i), target, null);
                try {
                    float score = i + 1.0f;
                    ScoreDoc[] scoreDocs = { new ScoreDoc(i, score) };
                    TopDocs topDocs = new TopDocs(new TotalHits(1, TotalHits.Relation.EQUAL_TO), scoreDocs);
                    result.topDocs(new TopDocsAndMaxScore(topDocs, score), new DocValueFormat[0]);
                    result.setShardIndex(i);
                    result.size(10);
                    consumer.consumeResult(result, latch::countDown);
                } finally {
                    result.decRef();
                }
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS));

            SearchPhaseController.ReducedQueryPhase phase = consumer.reduce();

            assertThat(phase.totalHits().value(), equalTo(4L));
            assertThat(phase.sortedTopDocs().scoreDocs().length, equalTo(numShards));

            // batchReduceSize=4 ≥ numShards=4: all shards stay in the buffer, no partial reductions
            assertThat(listener.partialReduceCount.get(), equalTo(0));
            assertThat(listener.finalReduceCount.get(), equalTo(1));
        }
    }

    /**
     * Single shard: the buffer is empty when shard 0 arrives (buffer=[], size=0), so the trigger
     * condition is never met regardless of {@code batchReduceSize}. The sole result waits in the
     * buffer until {@code reduce()} is called. Zero partial reductions; the final reduction handles
     * the single-shard result directly.
     *
     * <p>Also note the Lucene shortcut in {@code SearchPhaseController.mergeTopDocs}: when
     * {@code numShards==1 && from==0}, it returns the single-shard {@code TopDocs} unchanged
     * without calling {@code TopDocs.merge} at all.</p>
     */
    public void testSingleShardNoPartialReductions() throws Exception {
        SearchRequest request = new SearchRequest("index");
        request.source(new SearchSourceBuilder().size(10));
        // batchReduceSize = Math.min(512, 1) = 1, but buffer is empty when shard 0 arrives → no trigger

        RecordingProgressListener listener = new RecordingProgressListener();
        CountDownLatch latch = new CountDownLatch(1);

        try (
            QueryPhaseResultConsumer consumer = new QueryPhaseResultConsumer(
                request,
                executor,
                new NoopCircuitBreaker(CircuitBreaker.REQUEST),
                searchPhaseController,
                () -> false,
                listener,
                1,
                e -> {}
            )
        ) {
            SearchShardTarget target = new SearchShardTarget("node", new ShardId("index", "uuid", 0), null);
            QuerySearchResult result = new QuerySearchResult(new ShardSearchContextId("", 0), target, null);
            try {
                ScoreDoc[] scoreDocs = { new ScoreDoc(0, 42.0f) };
                TopDocs topDocs = new TopDocs(new TotalHits(1, TotalHits.Relation.EQUAL_TO), scoreDocs);
                result.topDocs(new TopDocsAndMaxScore(topDocs, 42.0f), new DocValueFormat[0]);
                result.setShardIndex(0);
                result.size(10);
                consumer.consumeResult(result, latch::countDown);
            } finally {
                result.decRef();
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS));

            SearchPhaseController.ReducedQueryPhase phase = consumer.reduce();

            assertThat(phase.totalHits().value(), equalTo(1L));
            assertThat(phase.sortedTopDocs().scoreDocs()[0].score, equalTo(42.0f));

            // Buffer was empty when shard 0 arrived; trigger never fires → 0 partials
            assertThat(listener.partialReduceCount.get(), equalTo(0));
            assertThat(listener.finalReduceCount.get(), equalTo(1));
        }
    }

    /**
     * Six shards with the minimum allowed {@code batchReduceSize=2}. This produces the densest
     * partial-reduce chain for an even-size batch. The first two shards fill the initial buffer
     * without triggering (buffer goes 0→1 on shard0, 1→2 on shard1 but check is pre-add so
     * still size=1&lt;2). Shard 2 arrives, sees buffer=[0,1] (size 2 ≥ 2), queues partial#1 with
     * [0,1], and lands in the fresh buffer. Shards 3–5 each arrive while {@code hasPartialReduce=true}
     * making the effective size 1+1=2 ≥ 2, so each triggers its own partial. Total = N−B = 6−2 = 4.
     *
     * <p>Each of partials 2–4 receives {@code toConsume} with exactly one element and
     * {@code lastMerge != null}. Put a breakpoint in {@code partialReduce} and step through
     * to observe how the rolling {@code MergeResult} accumulates each shard's contribution.</p>
     */
    public void testSmallBatchSizeMaximisesPartialChain() throws Exception {
        int numShards = 6;

        SearchRequest request = new SearchRequest("index");
        request.source(new SearchSourceBuilder().size(numShards));
        request.setBatchedReduceSize(2);

        RecordingProgressListener listener = new RecordingProgressListener();
        CountDownLatch latch = new CountDownLatch(numShards);

        try (
            QueryPhaseResultConsumer consumer = new QueryPhaseResultConsumer(
                request,
                executor,
                new NoopCircuitBreaker(CircuitBreaker.REQUEST),
                searchPhaseController,
                () -> false,
                listener,
                numShards,
                e -> {}
            )
        ) {
            for (int i = 0; i < numShards; i++) {
                SearchShardTarget target = new SearchShardTarget("node", new ShardId("index", "uuid", i), null);
                QuerySearchResult result = new QuerySearchResult(new ShardSearchContextId("", i), target, null);
                try {
                    float score = i + 1.0f;
                    ScoreDoc[] scoreDocs = { new ScoreDoc(i, score) };
                    TopDocs topDocs = new TopDocs(new TotalHits(1, TotalHits.Relation.EQUAL_TO), scoreDocs);
                    result.topDocs(new TopDocsAndMaxScore(topDocs, score), new DocValueFormat[0]);
                    result.setShardIndex(i);
                    result.size(numShards);
                    consumer.consumeResult(result, latch::countDown);
                } finally {
                    result.decRef();
                }
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS));

            SearchPhaseController.ReducedQueryPhase phase = consumer.reduce();

            assertThat(phase.totalHits().value(), equalTo((long) numShards));
            assertThat(phase.sortedTopDocs().scoreDocs().length, equalTo(numShards));

            // batchReduceSize=2: shards 0+1 fill the buffer; shards 2–5 each trigger their own partial.
            // Total = numShards − batchReduceSize = 6 − 2 = 4 partial reductions.
            assertThat(listener.partialReduceCount.get(), equalTo(4));
            assertThat(listener.finalReduceCount.get(), equalTo(1));
        }
    }

    /**
     * Six shards, each contributing an {@link InternalTopHits} aggregation (size=0 outer query,
     * so top-level top-docs merging is skipped). {@code batchReduceSize=2}: 4 partial reductions,
     * each merging one new shard result into the rolling {@code MergeResult}.
     *
     * <p>During each partial, {@code InternalTopHits.reduce} merges its own internal
     * {@code TopDocs} via Lucene to keep only the top-1 hit. Note that
     * {@code AggregationReduceContext.isFinalReduce()==false}, so
     * {@code maybeExecuteFinalReduce} returns the aggregations unchanged (no pipeline step).</p>
     *
     * <p>The final {@code reduce()} creates a {@code ForFinal} context
     * ({@code isFinalReduce()==true}); with {@code PipelineTree.EMPTY} used here, pipeline
     * execution is a no-op, but in production this is where e.g. {@code max_bucket} would fire.</p>
     *
     * <p>Breakpoint: {@code InternalAggregations.maybeExecuteFinalReduce} — observe
     * {@code context.isFinalReduce()} flipping from {@code false} (partials) to {@code true}
     * (final). Also watch {@code context.pipelineTreeRoot()} returning null for partial contexts.</p>
     */
    public void testTopHitsAggregationWithPartialReductions() throws Exception {
        final String aggName = "best_hit";
        int numShards = 6;

        SearchPhaseController productionController = new SearchPhaseController((t, agg) -> new AggregationReduceContext.Builder() {
            @Override
            public AggregationReduceContext forPartialReduction(@Nullable Collection<SearchHits> topHitsToRelease) {
                return new AggregationReduceContext.ForPartial(BigArrays.NON_RECYCLING_INSTANCE, null, t, agg, b -> {}, topHitsToRelease);
            }

            @Override
            public AggregationReduceContext forFinalReduction(@Nullable Collection<SearchHits> topHitsToRelease) {
                return new AggregationReduceContext.ForFinal(BigArrays.NON_RECYCLING_INSTANCE, null, t, agg, b -> {}, topHitsToRelease);
            }
        });

        SearchRequest request = new SearchRequest("index");
        request.source(new SearchSourceBuilder().size(0).aggregation(new TopHitsAggregationBuilder(aggName).size(1)));
        request.setBatchedReduceSize(2);

        RecordingProgressListener listener = new RecordingProgressListener();
        CountDownLatch latch = new CountDownLatch(numShards);

        try (
            SearchPhaseResults<SearchPhaseResult> consumer = productionController.newSearchPhaseResults(
                executor,
                new NoopCircuitBreaker(CircuitBreaker.REQUEST),
                () -> false,
                listener,
                request,
                numShards,
                e -> {}
            )
        ) {
            for (int i = 0; i < numShards; i++) {
                SearchShardTarget target = new SearchShardTarget("node", new ShardId("index", "uuid", i), null);
                QuerySearchResult result = new QuerySearchResult(new ShardSearchContextId("", i), target, null);
                try {
                    float score = i + 1.0f;

                    SearchHit hit = new SearchHit(0, "id-" + i);
                    hit.sourceRef(Source.fromMap(Map.of("shard", i), XContentType.JSON).internalSourceRef());
                    hit.score(score);
                    SearchHits searchHits = new SearchHits(new SearchHit[] { hit }, new TotalHits(1, TotalHits.Relation.EQUAL_TO), score);

                    TopDocsAndMaxScore aggTopDocs = new TopDocsAndMaxScore(
                        new TopDocs(new TotalHits(1, TotalHits.Relation.EQUAL_TO), new ScoreDoc[] { new ScoreDoc(0, score) }),
                        score
                    );
                    InternalTopHits internalTopHits = new InternalTopHits(aggName, 0, 1, aggTopDocs, searchHits, null);

                    // size=0 outer query: empty top-docs at the query level
                    result.topDocs(
                        new TopDocsAndMaxScore(new TopDocs(new TotalHits(0, TotalHits.Relation.EQUAL_TO), new ScoreDoc[0]), Float.NaN),
                        new DocValueFormat[0]
                    );
                    result.aggregations(InternalAggregations.from(Collections.singletonList(internalTopHits)));
                    result.setShardIndex(i);
                    result.size(0);
                    consumer.consumeResult(result, latch::countDown);
                } finally {
                    result.decRef();
                }
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS));

            SearchPhaseController.ReducedQueryPhase phase = consumer.reduce();

            assertNotNull(phase.aggregations());
            InternalTopHits reduced = phase.aggregations().get(aggName);
            assertNotNull(reduced);
            // Shard 5 has score 6 — the highest across all shards
            assertThat(reduced.getHits().getHits().length, equalTo(1));
            assertThat((double) reduced.getHits().getHits()[0].getScore(), closeTo(6.0, 0.001));

            // 6 shards, batchReduceSize=2 → 6−2=4 partial reductions
            assertThat(listener.partialReduceCount.get(), equalTo(4));
            assertThat(listener.finalReduceCount.get(), equalTo(1));

            // Release pooled SearchHits owned by the ReducedQueryPhase (done by SearchResponse.decRef in production)
            if (phase.topHitsToRelease() != null) {
                phase.topHitsToRelease().forEach(SearchHits::decRef);
            }
        }
    }

    /**
     * Mixed scenario: both outer top-docs (size=3) and an {@link InternalTopHits} aggregation.
     * Two independent TopDocs merges happen per reduction: one in
     * {@code SearchPhaseController.mergeTopDocs} for the outer hits, and one inside
     * {@code InternalTopHits.reduce} for the inner aggregation hits.
     *
     * <p>Put breakpoints in both {@code mergeTopDocs} and {@code InternalTopHits.reduce}
     * during the same partial to observe both code paths firing in sequence.</p>
     */
    public void testMixedTopDocsAndTopHitsAggregation() throws Exception {
        final String aggName = "inner";
        int numShards = 6;
        int outerSize = 3;

        SearchPhaseController productionController = new SearchPhaseController((t, agg) -> new AggregationReduceContext.Builder() {
            @Override
            public AggregationReduceContext forPartialReduction(@Nullable Collection<SearchHits> topHitsToRelease) {
                return new AggregationReduceContext.ForPartial(BigArrays.NON_RECYCLING_INSTANCE, null, t, agg, b -> {}, topHitsToRelease);
            }

            @Override
            public AggregationReduceContext forFinalReduction(@Nullable Collection<SearchHits> topHitsToRelease) {
                return new AggregationReduceContext.ForFinal(BigArrays.NON_RECYCLING_INSTANCE, null, t, agg, b -> {}, topHitsToRelease);
            }
        });

        SearchRequest request = new SearchRequest("index");
        request.source(new SearchSourceBuilder().size(outerSize).aggregation(new TopHitsAggregationBuilder(aggName).size(1)));
        request.setBatchedReduceSize(2);

        CountDownLatch latch = new CountDownLatch(numShards);

        try (
            SearchPhaseResults<SearchPhaseResult> consumer = productionController.newSearchPhaseResults(
                executor,
                new NoopCircuitBreaker(CircuitBreaker.REQUEST),
                () -> false,
                SearchProgressListener.NOOP,
                request,
                numShards,
                e -> {}
            )
        ) {
            for (int i = 0; i < numShards; i++) {
                SearchShardTarget target = new SearchShardTarget("node", new ShardId("index", "uuid", i), null);
                QuerySearchResult result = new QuerySearchResult(new ShardSearchContextId("", i), target, null);
                try {
                    float score = i + 1.0f;

                    // Outer top-docs: shard i returns one scored hit
                    ScoreDoc[] outerDocs = { new ScoreDoc(i * 100, score) };
                    TopDocs outerTopDocs = new TopDocs(new TotalHits(1, TotalHits.Relation.EQUAL_TO), outerDocs);

                    // InternalTopHits agg: same document, score, shard
                    SearchHit hit = new SearchHit(0, "doc-" + i);
                    hit.sourceRef(Source.fromMap(Map.of("shard", i), XContentType.JSON).internalSourceRef());
                    hit.score(score);
                    SearchHits aggHits = new SearchHits(new SearchHit[] { hit }, new TotalHits(1, TotalHits.Relation.EQUAL_TO), score);
                    TopDocsAndMaxScore aggTopDocs = new TopDocsAndMaxScore(
                        new TopDocs(new TotalHits(1, TotalHits.Relation.EQUAL_TO), new ScoreDoc[] { new ScoreDoc(0, score) }),
                        score
                    );
                    InternalTopHits internalTopHits = new InternalTopHits(aggName, 0, 1, aggTopDocs, aggHits, null);

                    result.topDocs(new TopDocsAndMaxScore(outerTopDocs, score), new DocValueFormat[0]);
                    result.aggregations(InternalAggregations.from(Collections.singletonList(internalTopHits)));
                    result.setShardIndex(i);
                    result.size(outerSize);
                    consumer.consumeResult(result, latch::countDown);
                } finally {
                    result.decRef();
                }
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS));

            SearchPhaseController.ReducedQueryPhase phase = consumer.reduce();

            // Outer: 6 total hits; top 3 are shards 5,4,3 with scores 6,5,4
            assertThat(phase.totalHits().value(), equalTo(6L));
            assertThat(phase.sortedTopDocs().scoreDocs().length, equalTo(outerSize));
            assertThat((double) phase.sortedTopDocs().scoreDocs()[0].score, closeTo(6.0, 0.001));
            assertThat((double) phase.sortedTopDocs().scoreDocs()[1].score, closeTo(5.0, 0.001));
            assertThat((double) phase.sortedTopDocs().scoreDocs()[2].score, closeTo(4.0, 0.001));

            // Inner agg: top hit is also from shard 5 (score 6)
            InternalTopHits reducedAgg = phase.aggregations().get(aggName);
            assertNotNull(reducedAgg);
            assertThat(reducedAgg.getHits().getHits().length, equalTo(1));
            assertThat((double) reducedAgg.getHits().getHits()[0].getScore(), closeTo(6.0, 0.001));

            // Release pooled SearchHits owned by the ReducedQueryPhase (done by SearchResponse.decRef in production)
            if (phase.topHitsToRelease() != null) {
                phase.topHitsToRelease().forEach(SearchHits::decRef);
            }
        }
    }

    /**
     * Verifies that pooled per-shard {@link SearchHits} held inside {@link InternalTopHits} are
     * released after the {@link SearchResponse} is dropped.
     *
     * <p>During partial reductions, the aggregation tree is merged shard by shard. Each call to
     * {@code AggregationReduceContext.transferTopHitsForRelease} registers the per-shard
     * {@code SearchHits} with the consumer's {@code topHitsToRelease} list. When the
     * {@link SearchResponse} is created, ownership of that list is transferred to it. On
     * {@code response.decRef()}, all entries in the list are released.</p>
     *
     * <p>Breakpoint: {@code AggregationReduceContext.transferTopHitsForRelease} — watch how
     * loser shard SearchHits enter the list during merging, and how the winner's SearchHits
     * are kept alive until the response is dropped.</p>
     */
    public void testTopHitsSearchHitsReleasedAfterResponseDecRef() throws Exception {
        final String aggName = "top";
        int numShards = 3;

        SearchPhaseController productionController = new SearchPhaseController((t, agg) -> new AggregationReduceContext.Builder() {
            @Override
            public AggregationReduceContext forPartialReduction(@Nullable Collection<SearchHits> topHitsToRelease) {
                return new AggregationReduceContext.ForPartial(BigArrays.NON_RECYCLING_INSTANCE, null, t, agg, b -> {}, topHitsToRelease);
            }

            @Override
            public AggregationReduceContext forFinalReduction(@Nullable Collection<SearchHits> topHitsToRelease) {
                return new AggregationReduceContext.ForFinal(BigArrays.NON_RECYCLING_INSTANCE, null, t, agg, b -> {}, topHitsToRelease);
            }
        });

        SearchRequest request = new SearchRequest("index");
        request.source(new SearchSourceBuilder().size(0).aggregation(new TopHitsAggregationBuilder(aggName).size(1)));
        request.setBatchedReduceSize(2);

        List<SearchHits> shardHits = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(numShards);

        try (
            SearchPhaseResults<SearchPhaseResult> consumer = productionController.newSearchPhaseResults(
                executor,
                new NoopCircuitBreaker(CircuitBreaker.REQUEST),
                () -> false,
                SearchProgressListener.NOOP,
                request,
                numShards,
                e -> {}
            )
        ) {
            for (int i = 0; i < numShards; i++) {
                SearchShardTarget target = new SearchShardTarget("node", new ShardId("index", "uuid", i), null);
                QuerySearchResult result = new QuerySearchResult(new ShardSearchContextId("", i), target, null);
                try {
                    float score = i + 1.0f;

                    SearchHit hit = new SearchHit(0, "id-" + i);
                    hit.sourceRef(Source.fromMap(Map.of("f", i), XContentType.JSON).internalSourceRef());
                    hit.score(score);
                    SearchHits hits = new SearchHits(new SearchHit[] { hit }, new TotalHits(1, TotalHits.Relation.EQUAL_TO), score);
                    assertTrue(hits.isPooled());
                    shardHits.add(hits);

                    TopDocsAndMaxScore aggTopDocs = new TopDocsAndMaxScore(
                        new TopDocs(new TotalHits(1, TotalHits.Relation.EQUAL_TO), new ScoreDoc[] { new ScoreDoc(0, score) }),
                        score
                    );
                    InternalTopHits internalTopHits = new InternalTopHits(aggName, 0, 1, aggTopDocs, hits, null);
                    result.topDocs(
                        new TopDocsAndMaxScore(new TopDocs(new TotalHits(0, TotalHits.Relation.EQUAL_TO), new ScoreDoc[0]), Float.NaN),
                        new DocValueFormat[0]
                    );
                    result.aggregations(InternalAggregations.from(Collections.singletonList(internalTopHits)));
                    result.setShardIndex(i);
                    result.size(0);
                    consumer.consumeResult(result, latch::countDown);
                } finally {
                    result.decRef();
                }
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS));

            SearchPhaseController.ReducedQueryPhase phase = consumer.reduce();

            SearchResponseSections sections = phase.buildResponse(SearchHits.EMPTY_WITH_TOTAL_HITS, Collections.emptyList(), null);
            SearchResponse response = new SearchResponse(
                sections,
                null,
                numShards,
                numShards,
                0,
                0,
                null,
                SearchResponse.Clusters.EMPTY,
                null,
                null,
                null
            );
            try {
                InternalTopHits reducedHits = response.getAggregations().get(aggName);
                assertThat(reducedHits.getHits().getHits().length, equalTo(1));
                // Shard 2 has score 3 — the highest
                assertThat((double) reducedHits.getHits().getHits()[0].getScore(), closeTo(3.0, 0.001));
            } finally {
                response.decRef();
            }
        }

        // After the response is dropped, all per-shard SearchHits (winners and losers) are released
        for (SearchHits hits : shardHits) {
            assertFalse(hits.hasReferences());
        }
    }

    /**
     * All shards return zero hits. The outer top-docs merge always produces empty results;
     * aggregations are null (no aggs in this query). Exercises the empty-result path in
     * {@code SearchPhaseController.reducedQueryPhase}.
     */
    public void testAllShardsReturnEmptyResults() throws Exception {
        int numShards = 5;

        SearchRequest request = new SearchRequest("index");
        request.source(new SearchSourceBuilder().size(10));
        request.setBatchedReduceSize(2);

        RecordingProgressListener listener = new RecordingProgressListener();
        CountDownLatch latch = new CountDownLatch(numShards);

        try (
            QueryPhaseResultConsumer consumer = new QueryPhaseResultConsumer(
                request,
                executor,
                new NoopCircuitBreaker(CircuitBreaker.REQUEST),
                searchPhaseController,
                () -> false,
                listener,
                numShards,
                e -> {}
            )
        ) {
            for (int i = 0; i < numShards; i++) {
                SearchShardTarget target = new SearchShardTarget("node", new ShardId("index", "uuid", i), null);
                QuerySearchResult result = new QuerySearchResult(new ShardSearchContextId("", i), target, null);
                try {
                    TopDocs emptyDocs = new TopDocs(new TotalHits(0, TotalHits.Relation.EQUAL_TO), new ScoreDoc[0]);
                    result.topDocs(new TopDocsAndMaxScore(emptyDocs, Float.NaN), new DocValueFormat[0]);
                    result.setShardIndex(i);
                    result.size(10);
                    consumer.consumeResult(result, latch::countDown);
                } finally {
                    result.decRef();
                }
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS));

            SearchPhaseController.ReducedQueryPhase phase = consumer.reduce();

            assertThat(phase.totalHits().value(), equalTo(0L));
            assertThat(phase.sortedTopDocs().scoreDocs().length, equalTo(0));
            // isEmptyResult is only true when queryResults itself is empty (no shards responded at all);
            // when shards respond with 0 hits, isEmptyResult remains false
            assertThat(phase.isEmptyResult(), equalTo(false));
            assertThat(listener.finalReduceCount.get(), equalTo(1));
        }
    }

    private static class RecordingProgressListener extends SearchProgressListener {
        final AtomicInteger partialReduceCount = new AtomicInteger();
        final AtomicInteger finalReduceCount = new AtomicInteger();

        @Override
        protected void onPartialReduce(List<SearchShard> shards, TotalHits totalHits, InternalAggregations aggs, int reducePhase) {
            partialReduceCount.incrementAndGet();
        }

        @Override
        protected void onFinalReduce(List<SearchShard> shards, TotalHits totalHits, InternalAggregations aggs, int reducePhase) {
            finalReduceCount.incrementAndGet();
        }
    }
}
