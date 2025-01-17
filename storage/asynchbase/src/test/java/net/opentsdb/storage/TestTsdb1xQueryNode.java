// This file is part of OpenTSDB.
// Copyright (C) 2018-2021  The OpenTSDB Authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package net.opentsdb.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import net.opentsdb.data.SecondTimeStamp;
import net.opentsdb.query.DefaultTimeSeriesDataSourceConfig;
import org.hbase.async.HBaseClient;
import org.hbase.async.Scanner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import com.google.common.collect.Lists;
import com.google.common.primitives.Bytes;
import com.google.common.reflect.TypeToken;
import com.stumbleupon.async.Deferred;

import net.opentsdb.common.Const;
import net.opentsdb.data.BaseTimeSeriesByteId;
import net.opentsdb.data.BaseTimeSeriesStringId;
import net.opentsdb.data.PartialTimeSeries;
import net.opentsdb.data.TimeSeriesId;
import net.opentsdb.exceptions.IllegalDataException;
import net.opentsdb.exceptions.QueryUpstreamException;
import net.opentsdb.meta.MetaDataStorageResult;
import net.opentsdb.meta.MetaDataStorageSchema;
import net.opentsdb.meta.MetaDataStorageResult.MetaResult;
import net.opentsdb.pools.ObjectPool;
import net.opentsdb.query.QueryContext;
import net.opentsdb.query.QueryMode;
import net.opentsdb.query.QueryNode;
import net.opentsdb.query.QueryPipelineContext;
import net.opentsdb.query.QueryResult;
import net.opentsdb.query.TimeSeriesDataSourceConfig;
import net.opentsdb.query.SemanticQuery;
import net.opentsdb.query.filter.MetricLiteralFilter;
import net.opentsdb.rollup.DefaultRollupConfig;
import net.opentsdb.rollup.DefaultRollupInterval;
import net.opentsdb.rollup.RollupUtils.RollupUsage;
import net.opentsdb.stats.MockTrace;
import net.opentsdb.stats.Span;
import net.opentsdb.storage.HBaseExecutor.State;
import net.opentsdb.storage.schemas.tsdb1x.Schema;
import net.opentsdb.uid.NoSuchUniqueName;
import net.opentsdb.utils.UnitTestException;

@RunWith(PowerMockRunner.class)
@PrepareForTest({ HBaseClient.class, Scanner.class, 
  Tsdb1xHBaseQueryNode.class })
public class TestTsdb1xQueryNode extends UTBase {
  
  private QueryPipelineContext context;
  private TimeSeriesDataSourceConfig source_config;
  private DefaultRollupConfig rollup_config;
  private Tsdb1xQueryResult result;
  private Tsdb1xScanners scanners;
  private MetaDataStorageSchema meta_schema;
  private Deferred<MetaDataStorageResult> meta_deferred;
  private QueryNode upstream_a;
  private QueryNode upstream_b;
  private SemanticQuery query;
  
  @Before
  public void before() throws Exception {
    context = mock(QueryPipelineContext.class);
    QueryContext query_context = mock(QueryContext.class);
    when(context.queryContext()).thenReturn(query_context);
    
    rollup_config = mock(DefaultRollupConfig.class);
    result = mock(Tsdb1xQueryResult.class);
    scanners = mock(Tsdb1xScanners.class);
    meta_schema = mock(MetaDataStorageSchema.class);
    meta_deferred = new Deferred<MetaDataStorageResult>();
    upstream_a = mock(QueryNode.class);
    upstream_b = mock(QueryNode.class);
    
    ObjectPool scanners_pool = mock(ObjectPool.class);
    when(scanners_pool.claim()).thenReturn(scanners);
    when(scanners.object()).thenReturn(scanners);
    when(tsdb.getRegistry().getObjectPool(Tsdb1xScannersPool.TYPE))
      .thenReturn(scanners_pool);
    
    query = SemanticQuery.newBuilder()
        .setMode(QueryMode.SINGLE)
        .setStart(Integer.toString(START_TS))
        .setEnd(Integer.toString(END_TS))
        .setExecutionGraph(Collections.emptyList())
        .build();
    when(context.query()).thenReturn(query);
    
    source_config = (TimeSeriesDataSourceConfig) baseConfig()
        .build();
    
    when(meta_schema.runQuery(any(QueryPipelineContext.class), 
        any(TimeSeriesDataSourceConfig.class), any(Span.class)))
      .thenReturn(meta_deferred);
    
    PowerMockito.whenNew(Tsdb1xQueryResult.class).withAnyArguments()
      .thenReturn(result);
    PowerMockito.whenNew(Tsdb1xScanners.class).withAnyArguments()
      .thenReturn(scanners);
    
    when(context.upstream(any(QueryNode.class)))
      .thenReturn(Lists.newArrayList(upstream_a, upstream_b));
    when(context.tsdb()).thenReturn(tsdb);
    
    when(data_store.dynamicInt(Tsdb1xHBaseDataStore.MULTI_GET_CONCURRENT_KEY))
      .thenReturn(2);
    when(data_store.dynamicInt(Tsdb1xHBaseDataStore.MULTI_GET_BATCH_KEY))
      .thenReturn(4);
    tsdb.runnables.clear();
  }
  
  @Test
  public void ctorDefault() throws Exception {
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
        data_store, context, source_config);
    assertSame(source_config, node.config);
    assertEquals(0, node.sequence_id.get());
    assertFalse(node.initialized.get());
    assertFalse(node.initializing.get());
    assertNull(node.executor);
    assertFalse(node.skip_nsun_tagks);
    assertFalse(node.skip_nsun_tagvs);
    assertFalse(node.skip_nsui);
    assertFalse(node.delete);
    assertNull(node.rollup_intervals);
    assertEquals(RollupUsage.ROLLUP_NOFALLBACK, node.rollup_usage);
    
    // default methods
    assertSame(source_config, node.config());
    assertSame(schema, node.schema());
    assertNull(node.sequenceEnd());
    assertFalse(node.skipNSUI());
    assertTrue(node.fetchDataType((byte) 0));
    assertFalse(node.deleteData());
    assertNull(node.rollupIntervals());
    assertEquals(RollupUsage.ROLLUP_NOFALLBACK, node.rollupUsage());
  }
  
  @Test
  public void ctorQueryOverrides() throws Exception {
    query = SemanticQuery.newBuilder()
        .setMode(QueryMode.SINGLE)
        .setStart(Integer.toString(START_TS))
        .setEnd(Integer.toString(END_TS))
        .setExecutionGraph(Collections.emptyList())
        .build();
    when(context.query()).thenReturn(query);
    
    source_config = (TimeSeriesDataSourceConfig) DefaultTimeSeriesDataSourceConfig.newBuilder()
        .setMetric(MetricLiteralFilter.newBuilder()
            .setMetric(METRIC_STRING)
            .build())
        .addOverride(Tsdb1xHBaseDataStore.SKIP_NSUN_TAGK_KEY, "true")
        .addOverride(Tsdb1xHBaseDataStore.SKIP_NSUN_TAGV_KEY, "true")
        .addOverride(Tsdb1xHBaseDataStore.SKIP_NSUI_KEY, "true")
        .addOverride(Tsdb1xHBaseDataStore.DELETE_KEY, "true")
        .addOverride(Tsdb1xHBaseDataStore.ROLLUP_USAGE_KEY, "ROLLUP_RAW")
        .setId("m1")
        .build();
    
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
        data_store, context, source_config);
    assertSame(source_config, node.config);
    assertEquals(0, node.sequence_id.get());
    assertFalse(node.initialized.get());
    assertFalse(node.initializing.get());
    assertNull(node.executor);
    assertTrue(node.skip_nsun_tagks);
    assertTrue(node.skip_nsun_tagvs);
    assertTrue(node.skip_nsui);
    assertTrue(node.delete);
    assertNull(node.rollup_intervals);
    assertEquals(RollupUsage.ROLLUP_RAW, node.rollup_usage);
  }
  
  @Test
  public void ctorRollups() throws Exception {
    Tsdb1xHBaseDataStore data_store = mock(Tsdb1xHBaseDataStore.class);
    Schema schema = mock(Schema.class);
    when(data_store.schema()).thenReturn(schema);
    
    when(rollup_config.getRollupInterval("1h")).thenReturn(
        DefaultRollupInterval.builder()
          .setInterval("1h")
          .setTable("tsdb-1h")
          .setPreAggregationTable("tsdb-agg-1h")
          .setRowSpan("1d")
          .build());
    when(rollup_config.getRollupInterval("30m")).thenReturn(
        DefaultRollupInterval.builder()
          .setInterval("30m")
          .setTable("tsdb-30m")
          .setPreAggregationTable("tsdb-agg-30m")
          .setRowSpan("1d")
          .build());
    
    when(schema.rollupConfig()).thenReturn(rollup_config);
    
    source_config = (TimeSeriesDataSourceConfig) 
        DefaultTimeSeriesDataSourceConfig.newBuilder()
        .setMetric(MetricLiteralFilter.newBuilder()
            .setMetric(METRIC_STRING)
            .build())
        .addSummaryAggregation("sum")
        .addSummaryAggregation("count")
        .addRollupInterval("1h")
        .addRollupInterval("30m")
//        .setPrePadding("1h")
//        .setPostPadding("1h")
        .setId("m1")
        .build();
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
        data_store, context, source_config);
    assertSame(source_config, node.config);
    assertEquals(0, node.sequence_id.get());
    assertFalse(node.initialized.get());
    assertFalse(node.initializing.get());
    assertNull(node.executor);
    assertFalse(node.skip_nsun_tagks);
    assertFalse(node.skip_nsun_tagvs);
    assertFalse(node.skip_nsui);
    assertFalse(node.delete);
    assertNull(node.rollup_intervals);
    assertEquals(RollupUsage.ROLLUP_NOFALLBACK, node.rollup_usage);
    
    // init sets the rollups
    node.initialize(null);
    assertEquals(2, node.rollup_intervals.size());
    assertArrayEquals("tsdb-1h".getBytes(), 
        node.rollup_intervals.get(0).getTemporalTable());
    assertArrayEquals("tsdb-30m".getBytes(), 
        node.rollup_intervals.get(1).getTemporalTable());
  }

  @Test
  public void ctorExceptions() throws Exception {
    try {
      new Tsdb1xHBaseQueryNode(null, context, source_config);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    try {
      new Tsdb1xHBaseQueryNode(data_store, null, source_config);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    try {
      new Tsdb1xHBaseQueryNode(data_store, context, null);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
  }

  @Test
  public void fetchNextScanner() throws Exception {
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
        data_store, context, source_config);
    node.fetchNext(null);
    
    assertSame(scanners, node.executor);
    verify(scanners, times(1)).fetchNext(any(Tsdb1xQueryResult.class), 
        any(Span.class));
    assertEquals(1, node.sequence_id.get());
    assertTrue(node.initialized.get());
    assertTrue(node.initializing.get());
    PowerMockito.verifyNew(Tsdb1xQueryResult.class, times(1))
      .withArguments(anyLong(), any(Tsdb1xHBaseQueryNode.class), any(Schema.class));
    
    // next call
    node.fetchNext(null);
    
    assertSame(scanners, node.executor);
    verify(scanners, times(2)).fetchNext(any(Tsdb1xQueryResult.class), 
        any(Span.class));
    assertEquals(2, node.sequence_id.get());
    assertTrue(node.initialized.get());
    assertTrue(node.initializing.get());
    PowerMockito.verifyNew(Tsdb1xQueryResult.class, times(2))
      .withArguments(anyLong(), any(Tsdb1xHBaseQueryNode.class), any(Schema.class));
    
    // next call
    node.fetchNext(null);
    
    assertSame(scanners, node.executor);
    verify(scanners, times(3)).fetchNext(any(Tsdb1xQueryResult.class), 
        any(Span.class));
    assertEquals(3, node.sequence_id.get());
    assertTrue(node.initialized.get());
    assertTrue(node.initializing.get());
    PowerMockito.verifyNew(Tsdb1xQueryResult.class, times(3))
      .withArguments(anyLong(), any(Tsdb1xHBaseQueryNode.class), any(Schema.class));
  }
  
  @Test
  public void fetchNextMeta() throws Exception {
    Tsdb1xHBaseDataStore data_store = mock(Tsdb1xHBaseDataStore.class);
    Schema schema = mock(Schema.class);
    when(data_store.schema()).thenReturn(schema);
    when(schema.metaSchema()).thenReturn(meta_schema);
    
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
        data_store, context, source_config);
    node.fetchNext(null);
    
    assertNull(node.executor);
    verify(scanners, never()).fetchNext(any(Tsdb1xQueryResult.class), 
        any(Span.class));
    assertEquals(0, node.sequence_id.get());
    assertFalse(node.initialized.get());
    assertTrue(node.initializing.get());
    PowerMockito.verifyNew(Tsdb1xQueryResult.class, never())
      .withArguments(anyLong(), any(Tsdb1xHBaseQueryNode.class), any(Schema.class));
    verify(meta_schema, times(1)).runQuery(any(QueryPipelineContext.class), 
        any(TimeSeriesDataSourceConfig.class), any(Span.class));
    
    try {
      node.fetchNext(null);
      fail("Expected IllegalStateException");
    } catch (IllegalStateException e) { }
  }
  
  @Test
  public void setIntervals() throws Exception {
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
        data_store, context, source_config);
    node.initialize(null).join();
    String[] intervals = node.setIntervals();
    
    assertEquals(1, intervals.length);
    assertEquals("1h", intervals[0]);
  }
  
  @Test
  public void setIntervalsRollups() throws Exception {
    Tsdb1xHBaseDataStore data_store = mock(Tsdb1xHBaseDataStore.class);
    Schema schema = mock(Schema.class);
    when(data_store.schema()).thenReturn(schema);
    
    when(rollup_config.getRollupInterval("1h")).thenReturn(
        DefaultRollupInterval.builder()
          .setInterval("1h")
          .setTable("tsdb-1h")
          .setPreAggregationTable("tsdb-agg-1h")
          .setRowSpan("1d")
          .build());
    when(rollup_config.getRollupInterval("30m")).thenReturn(
        DefaultRollupInterval.builder()
          .setInterval("30m")
          .setTable("tsdb-30m")
          .setPreAggregationTable("tsdb-agg-30m")
          .setRowSpan("1d")
          .build());
    
    when(schema.rollupConfig()).thenReturn(rollup_config);
    
    source_config = (TimeSeriesDataSourceConfig) 
        DefaultTimeSeriesDataSourceConfig.newBuilder()
        .setMetric(MetricLiteralFilter.newBuilder()
            .setMetric(METRIC_STRING)
            .build())
        .addSummaryAggregation("sum")
        .addSummaryAggregation("count")
        .addRollupInterval("1h")
        .addRollupInterval("30m")
//        .setPrePadding("1h")
//        .setPostPadding("1h")
        .setId("m1")
        .build();
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
        data_store, context, source_config);
    node.initialize(null).join();
    String[] intervals = node.setIntervals();
    
    assertEquals(3, intervals.length);
    assertEquals("1d", intervals[0]);
    assertEquals("1d", intervals[1]);
    assertEquals("1h", intervals[2]);
  }
  
  @Test
  public void setupScanner() throws Exception {
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
        data_store, context, source_config);
    node.setup(null);
    
    assertSame(scanners, node.executor);
    verify(scanners, times(1)).fetchNext(any(Tsdb1xQueryResult.class), 
        any(Span.class));
    assertEquals(1, node.sequence_id.get());
    assertTrue(node.initialized.get());
    PowerMockito.verifyNew(Tsdb1xQueryResult.class, times(1))
      .withArguments(anyLong(), any(Tsdb1xHBaseQueryNode.class), any(Schema.class));
  }
  
  @Test
  public void setupMeta() throws Exception {
    Tsdb1xHBaseDataStore data_store = mock(Tsdb1xHBaseDataStore.class);
    Schema schema = mock(Schema.class);
    when(data_store.schema()).thenReturn(schema);
    when(schema.metaSchema()).thenReturn(meta_schema);
    
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
        data_store, context, source_config);
    node.setup(null);
    
    assertNull(node.executor);
    verify(scanners, never()).fetchNext(any(Tsdb1xQueryResult.class), 
        any(Span.class));
    assertEquals(0, node.sequence_id.get());
    assertFalse(node.initialized.get());
    PowerMockito.verifyNew(Tsdb1xQueryResult.class, never())
      .withArguments(anyLong(), any(Tsdb1xHBaseQueryNode.class), any(Schema.class));
    verify(meta_schema, times(1)).runQuery(any(QueryPipelineContext.class), 
        any(TimeSeriesDataSourceConfig.class), any(Span.class));
  }

  @Test
  public void onComplete() throws Exception {
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
        data_store, context, source_config);
    node.initialize(null);
    
    node.onComplete(node, 1, 1);
    
    assertEquals(1, tsdb.runnables.size());
    tsdb.runnables.get(0).run();
    tsdb.runnables.clear();
    
    verify(upstream_a, times(1)).onComplete(node, 1, 1);
    verify(upstream_b, times(1)).onComplete(node, 1, 1);
  }
  
  @Test
  public void onNextContinue() throws Exception {
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
        data_store, context, source_config);
    node.initialize(null);
    node.executor = mock(HBaseExecutor.class);
    when(node.executor.state()).thenReturn(State.CONTINUE);
    
    node.onNext(result);
    
    assertEquals(1, tsdb.runnables.size());
    tsdb.runnables.get(0).run();
    tsdb.runnables.clear();
    
    verify(upstream_a, times(1)).onNext(result);
    verify(upstream_b, times(1)).onNext(result);
    verify(upstream_a, never()).onComplete(any(QueryNode.class), 
        anyLong(), anyLong());
    verify(upstream_b, never()).onComplete(any(QueryNode.class), 
        anyLong(), anyLong());
  }
  
  @Test
  public void onNextComplete() throws Exception {
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
        data_store, context, source_config);
    node.initialize(null);
    node.executor = mock(HBaseExecutor.class);
    when(node.executor.state()).thenReturn(State.COMPLETE);
    
    node.onNext(result);
    
    assertEquals(1, tsdb.runnables.size());
    tsdb.runnables.get(0).run();
    tsdb.runnables.clear();
    
    verify(upstream_a, times(1)).onNext(result);
    verify(upstream_b, times(1)).onNext(result);
    verify(upstream_a, times(1)).onComplete(any(QueryNode.class), 
        anyLong(), anyLong());
    verify(upstream_b, times(1)).onComplete(any(QueryNode.class), 
        anyLong(), anyLong());
  }
  
  @Test
  public void onNextPTS() throws Exception {
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
        data_store, context, source_config);
    node.initialize(null);
    
    PartialTimeSeries pts = mock(PartialTimeSeries.class);
    node.onNext(pts);
    
    verify(upstream_a, times(1)).onNext(pts);
    verify(upstream_b, times(1)).onNext(pts);
    verify(upstream_a, never()).onComplete(any(QueryNode.class), 
        anyLong(), anyLong());
    verify(upstream_b, never()).onComplete(any(QueryNode.class), 
        anyLong(), anyLong());
    
    try {
      node.onNext((PartialTimeSeries) null);
      fail("Expected QueryUpstreamException");
    } catch (QueryUpstreamException e) { }
  }
  
  @Test
  public void onError() throws Exception {
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
        data_store, context, source_config);
    node.initialize(null);
    UnitTestException ex = new UnitTestException();
    
    node.onError(ex);

    assertEquals(1, tsdb.runnables.size());
    tsdb.runnables.get(0).run();
    tsdb.runnables.clear();
    
    verify(upstream_a, times(1)).onError(ex);
    verify(upstream_b, times(1)).onError(ex);
  }
  
  @Test
  public void metaErrorCB() throws Exception {
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
        data_store, context, source_config);
    net.opentsdb.storage.Tsdb1xHBaseQueryNode.MetaErrorCB cb = 
        node.new MetaErrorCB(null);
    node.initialize(null);
    UnitTestException ex = new UnitTestException();
    
    cb.call(ex);
    
    verify(upstream_a, times(1)).onError(ex);
    verify(upstream_b, times(1)).onError(ex);
    
    trace = new MockTrace(true);
    
    cb = node.new MetaErrorCB(trace.newSpan("UT").start());
    cb.call(ex);
    
    verify(upstream_a, times(2)).onError(ex);
    verify(upstream_b, times(2)).onError(ex);
   
    verifySpan("UT", UnitTestException.class);
  }

  @Test
  public void metaCBWithData() throws Exception {
    setupMultigetPool();
    List<TimeSeriesId> ids = Lists.newArrayList();
    ids.add(BaseTimeSeriesStringId.newBuilder()
        .setMetric(METRIC_STRING)
        .addTags(TAGK_STRING, TAGV_STRING)
        .build());
    ids.add(BaseTimeSeriesStringId.newBuilder()
        .setMetric(METRIC_STRING)
        .addTags(TAGK_STRING, TAGV_B_STRING)
        .build());
    
    MetaDataStorageResult meta_result = mock(MetaDataStorageResult.class);
    when(meta_result.timeSeries()).thenReturn(ids);
    when(meta_result.result()).thenReturn(MetaResult.DATA);
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
        data_store, context, source_config);
    node.initialize(null);
    
    node.new MetaCB(null).call(meta_result);
    
    assertEquals(1, tsdb.runnables.size());
    tsdb.runnables.get(0).run();
    tsdb.runnables.clear();
    
    Tsdb1xMultiGet gets = (Tsdb1xMultiGet) node.executor;
    assertEquals(2, gets.tsuids.size());
    assertArrayEquals(Bytes.concat(METRIC_BYTES, TAGK_BYTES, TAGV_BYTES), 
        gets.tsuids.get(0));
    assertArrayEquals(Bytes.concat(METRIC_BYTES, TAGK_BYTES, TAGV_B_BYTES), 
        gets.tsuids.get(1));
    assertTrue(node.initialized.get());
    verify(upstream_a, times(1)).onNext(any(QueryResult.class));
    verify(upstream_b, times(1)).onNext(any(QueryResult.class));
  }
  
  @Test
  public void metaCBNoData() throws Exception {
    MetaDataStorageResult meta_result = mock(MetaDataStorageResult.class);
    when(meta_result.result()).thenReturn(MetaResult.NO_DATA);
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
        data_store, context, source_config);
    node.initialize(null);
    
    node.new MetaCB(null).call(meta_result);
    
    assertNull(node.executor);
    assertTrue(node.initialized.get());
    verify(upstream_a, times(1)).onNext(any(QueryResult.class));
    verify(upstream_b, times(1)).onNext(any(QueryResult.class));
    verify(upstream_a, times(1)).onComplete(node, 0, 0);
    verify(upstream_b, times(1)).onComplete(node, 0, 0);
  }
  
  @Test
  public void metaCBException() throws Exception {
    MetaDataStorageResult meta_result = mock(MetaDataStorageResult.class);
    when(meta_result.result()).thenReturn(MetaResult.EXCEPTION);
    when(meta_result.exception()).thenReturn(new UnitTestException());
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
        data_store, context, source_config);
    node.initialize(null);
    
    node.new MetaCB(null).call(meta_result);
    
    assertNull(node.executor);
    assertTrue(node.initialized.get());
    verify(upstream_a, times(1)).onError(any(UnitTestException.class));
    verify(upstream_b, times(1)).onError(any(UnitTestException.class));
  }
  
  @Test
  public void metaCBNoDataFallback() throws Exception {
    MetaDataStorageResult meta_result = mock(MetaDataStorageResult.class);
    when(meta_result.result()).thenReturn(MetaResult.NO_DATA_FALLBACK);
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
        data_store, context, source_config);
    
    node.new MetaCB(null).call(meta_result);
    
    assertSame(scanners, node.executor);
    verify(scanners, times(1)).fetchNext(any(Tsdb1xQueryResult.class), 
        any(Span.class));
    assertEquals(1, node.sequence_id.get());
    assertTrue(node.initialized.get());
    PowerMockito.verifyNew(Tsdb1xQueryResult.class, times(1))
      .withArguments(anyLong(), any(Tsdb1xHBaseQueryNode.class), any(Schema.class));
  }
  
  @Test
  public void metaCBExceptionFallback() throws Exception {
    MetaDataStorageResult meta_result = mock(MetaDataStorageResult.class);
    when(meta_result.result()).thenReturn(MetaResult.EXCEPTION_FALLBACK);
    when(meta_result.exception()).thenReturn(new UnitTestException());
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
        data_store, context, source_config);
    
    node.new MetaCB(null).call(meta_result);
    
    assertSame(scanners, node.executor);
    verify(scanners, times(1)).fetchNext(any(Tsdb1xQueryResult.class), 
        any(Span.class));
    assertEquals(1, node.sequence_id.get());
    assertTrue(node.initialized.get());
    PowerMockito.verifyNew(Tsdb1xQueryResult.class, times(1))
      .withArguments(anyLong(), any(Tsdb1xHBaseQueryNode.class), any(Schema.class));
    verify(upstream_a, never()).onError(any(UnitTestException.class));
    verify(upstream_b, never()).onError(any(UnitTestException.class));
  }
  
  @Test
  public void resolveMetaStringOk() throws Exception {
    // Seems the PowerMockito won't mock down to the nested classes
    // so this will actually execute the query via multi-get.
    List<TimeSeriesId> ids = Lists.newArrayList();
    ids.add(BaseTimeSeriesStringId.newBuilder()
        .setMetric(METRIC_STRING)
        .addTags(TAGK_STRING, TAGV_STRING)
        .build());
    ids.add(BaseTimeSeriesStringId.newBuilder()
        .setMetric(METRIC_STRING)
        .addTags(TAGK_STRING, TAGV_B_STRING)
        .build());
    
    setupMultigetPool();
    MetaDataStorageResult meta_result = mock(MetaDataStorageResult.class);
    when(meta_result.timeSeries()).thenReturn(ids);
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
        data_store, context, source_config);
    node.initialize(null);
    
    node.resolveMeta(meta_result, null);
    
    assertEquals(1, tsdb.runnables.size());
    tsdb.runnables.get(0).run();
    tsdb.runnables.clear();
    
    Tsdb1xMultiGet gets = (Tsdb1xMultiGet) node.executor;
    assertEquals(2, gets.tsuids.size());
    assertArrayEquals(Bytes.concat(METRIC_BYTES, TAGK_BYTES, TAGV_BYTES), 
        gets.tsuids.get(0));
    assertArrayEquals(Bytes.concat(METRIC_BYTES, TAGK_BYTES, TAGV_B_BYTES), 
        gets.tsuids.get(1));
    assertTrue(node.initialized.get());
    verify(upstream_a, times(1)).onNext(any(QueryResult.class));
    verify(upstream_b, times(1)).onNext(any(QueryResult.class));
  }
  
  @Test
  public void resolveMetaStringMetricNSUN() throws Exception {
    // Seems the PowerMockito won't mock down to the nested classes
    // so this will actually execute the query via multi-get.
    List<TimeSeriesId> ids = Lists.newArrayList();
    ids.add(BaseTimeSeriesStringId.newBuilder()
        .setMetric(NSUN_METRIC)
        .addTags(TAGK_STRING, TAGV_STRING)
        .build());
    ids.add(BaseTimeSeriesStringId.newBuilder()
        .setMetric(NSUN_METRIC)
        .addTags(TAGK_STRING, TAGV_B_STRING)
        .build());
    
    MetaDataStorageResult meta_result = mock(MetaDataStorageResult.class);
    when(meta_result.timeSeries()).thenReturn(ids);
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
        data_store, context, source_config);
    node.initialize(null);
    
    node.resolveMeta(meta_result, null);
    
    assertNull(node.executor);
    assertFalse(node.initialized.get());
    verify(upstream_a, times(1)).onError(any(NoSuchUniqueName.class));
    verify(upstream_b, times(1)).onError(any(NoSuchUniqueName.class));
  }

  @Test
  public void resolveMetaStringMetricNSUNAllowed() throws Exception {
    // Seems the PowerMockito won't mock down to the nested classes
    // so this will actually execute the query via multi-get.
    query = SemanticQuery.newBuilder()
            .setMode(QueryMode.SINGLE)
            .setStart(Integer.toString(START_TS))
            .setEnd(Integer.toString(END_TS))
            .setExecutionGraph(Collections.emptyList())
            .build();
    when(context.query()).thenReturn(query);
    source_config = (TimeSeriesDataSourceConfig) DefaultTimeSeriesDataSourceConfig.newBuilder()
            .setMetric(MetricLiteralFilter.newBuilder()
                    .setMetric(METRIC_STRING)
                    .build())
            .addOverride(Tsdb1xHBaseDataStore.SKIP_NSUN_METRIC_KEY, "true")
            .setId("m1")
            .build();

    List<TimeSeriesId> ids = Lists.newArrayList();
    ids.add(BaseTimeSeriesStringId.newBuilder()
            .setMetric(NSUN_METRIC)
            .addTags(TAGK_STRING, TAGV_STRING)
            .build());
    ids.add(BaseTimeSeriesStringId.newBuilder()
            .setMetric(NSUN_METRIC)
            .addTags(TAGK_STRING, TAGV_B_STRING)
            .build());

    MetaDataStorageResult meta_result = mock(MetaDataStorageResult.class);
    when(meta_result.timeSeries()).thenReturn(ids);
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
            data_store, context, source_config);
    node.initialize(null);

    node.resolveMeta(meta_result, null);

    assertNull(node.executor);
    assertTrue(node.initialized.get());
    verify(upstream_a, never()).onError(any(NoSuchUniqueName.class));
    verify(upstream_b, never()).onError(any(NoSuchUniqueName.class));
    verify(upstream_a, times(1)).onNext(any(QueryResult.class));
  }
  
  @Test
  public void resolveMetaStringTagkNSUN() throws Exception {
    // Seems the PowerMockito won't mock down to the nested classes
    // so this will actually execute the query via multi-get.
    List<TimeSeriesId> ids = Lists.newArrayList();
    ids.add(BaseTimeSeriesStringId.newBuilder()
        .setMetric(METRIC_STRING)
        .addTags(NSUN_TAGK, TAGV_STRING)
        .build());
    ids.add(BaseTimeSeriesStringId.newBuilder()
        .setMetric(METRIC_STRING)
        .addTags(TAGK_STRING, TAGV_B_STRING)
        .build());
    
    MetaDataStorageResult meta_result = mock(MetaDataStorageResult.class);
    when(meta_result.timeSeries()).thenReturn(ids);
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
        data_store, context, source_config);
    node.initialize(null);
    
    node.resolveMeta(meta_result, null);
    
    assertNull(node.executor);
    assertFalse(node.initialized.get());
    verify(upstream_a, times(1)).onError(any(NoSuchUniqueName.class));
    verify(upstream_b, times(1)).onError(any(NoSuchUniqueName.class));
  }
  
  @Test
  public void resolveMetaStringTagkNSUNAllowed() throws Exception {
    // Seems the PowerMockito won't mock down to the nested classes
    // so this will actually execute the query via multi-get.
    query = SemanticQuery.newBuilder()
        .setMode(QueryMode.SINGLE)
        .setStart(Integer.toString(START_TS))
        .setEnd(Integer.toString(END_TS))
        .setExecutionGraph(Collections.emptyList())
        .build();
    when(context.query()).thenReturn(query);
    source_config = (TimeSeriesDataSourceConfig) baseConfig()
        .addOverride(Tsdb1xHBaseDataStore.SKIP_NSUN_TAGK_KEY, "true")
        .setId("m1")
        .build();
    
    List<TimeSeriesId> ids = Lists.newArrayList();
    ids.add(BaseTimeSeriesStringId.newBuilder()
        .setMetric(METRIC_STRING)
        .addTags(NSUN_TAGK, TAGV_STRING)
        .build());
    ids.add(BaseTimeSeriesStringId.newBuilder()
        .setMetric(METRIC_STRING)
        .addTags(TAGK_STRING, TAGV_B_STRING)
        .build());
    
    setupMultigetPool();
    MetaDataStorageResult meta_result = mock(MetaDataStorageResult.class);
    when(meta_result.timeSeries()).thenReturn(ids);
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
        data_store, context, source_config);
    node.initialize(null);
    
    node.resolveMeta(meta_result, null);
    
    assertEquals(1, tsdb.runnables.size());
    tsdb.runnables.get(0).run();
    tsdb.runnables.clear();
    
    Tsdb1xMultiGet gets = (Tsdb1xMultiGet) node.executor;
    assertEquals(1, gets.tsuids.size());
    assertArrayEquals(Bytes.concat(METRIC_BYTES, TAGK_BYTES, TAGV_B_BYTES), 
        gets.tsuids.get(0));
    assertTrue(node.initialized.get());
    verify(upstream_a, times(1)).onNext(any(QueryResult.class));
    verify(upstream_b, times(1)).onNext(any(QueryResult.class));
  }

  @Test
  public void resolveMetaStringTagvNSUN() throws Exception {
    // Seems the PowerMockito won't mock down to the nested classes
    // so this will actually execute the query via multi-get.
    List<TimeSeriesId> ids = Lists.newArrayList();
    ids.add(BaseTimeSeriesStringId.newBuilder()
        .setMetric(METRIC_STRING)
        .addTags(TAGK_STRING, TAGV_STRING)
        .build());
    ids.add(BaseTimeSeriesStringId.newBuilder()
        .setMetric(METRIC_STRING)
        .addTags(TAGK_STRING, NSUN_TAGV)
        .build());
    
    MetaDataStorageResult meta_result = mock(MetaDataStorageResult.class);
    when(meta_result.timeSeries()).thenReturn(ids);
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
        data_store, context, source_config);
    node.initialize(null);
    
    node.resolveMeta(meta_result, null);
    
    assertNull(node.executor);
    assertFalse(node.initialized.get());
    verify(upstream_a, times(1)).onError(any(NoSuchUniqueName.class));
    verify(upstream_b, times(1)).onError(any(NoSuchUniqueName.class));
  }
  
  @Test
  public void resolveMetaStringTagvNSUNAllowed() throws Exception {
    // Seems the PowerMockito won't mock down to the nested classes
    // so this will actually execute the query via multi-get.
    query = SemanticQuery.newBuilder()
        .setMode(QueryMode.SINGLE)
        .setStart(Integer.toString(START_TS))
        .setEnd(Integer.toString(END_TS))
        .setExecutionGraph(Collections.emptyList())
        .build();
    when(context.query()).thenReturn(query);
    source_config = (TimeSeriesDataSourceConfig) baseConfig()
        .addOverride(Tsdb1xHBaseDataStore.SKIP_NSUN_TAGV_KEY, "true")
        .setId("m1")
        .build();
    
    List<TimeSeriesId> ids = Lists.newArrayList();
    ids.add(BaseTimeSeriesStringId.newBuilder()
        .setMetric(METRIC_STRING)
        .addTags(TAGK_STRING, TAGV_STRING)
        .build());
    ids.add(BaseTimeSeriesStringId.newBuilder()
        .setMetric(METRIC_STRING)
        .addTags(TAGK_STRING, NSUN_TAGV)
        .build());
    
    setupMultigetPool();
    MetaDataStorageResult meta_result = mock(MetaDataStorageResult.class);
    when(meta_result.timeSeries()).thenReturn(ids);
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
        data_store, context, source_config);
    node.initialize(null);
    
    node.resolveMeta(meta_result, null);
    
    assertEquals(1, tsdb.runnables.size());
    tsdb.runnables.get(0).run();
    tsdb.runnables.clear();
    
    Tsdb1xMultiGet gets = (Tsdb1xMultiGet) node.executor;
    assertEquals(1, gets.tsuids.size());
    assertArrayEquals(Bytes.concat(METRIC_BYTES, TAGK_BYTES, TAGV_BYTES), 
        gets.tsuids.get(0));
    assertTrue(node.initialized.get());
    verify(upstream_a, times(1)).onNext(any(QueryResult.class));
    verify(upstream_b, times(1)).onNext(any(QueryResult.class));
  }

  @Test
  public void resolveMetaStringTwoMetrics() throws Exception {
    // Seems the PowerMockito won't mock down to the nested classes
    // so this will actually execute the query via multi-get.
    List<TimeSeriesId> ids = Lists.newArrayList();
    ids.add(BaseTimeSeriesStringId.newBuilder()
        .setMetric(METRIC_STRING)
        .addTags(TAGK_STRING, TAGV_STRING)
        .build());
    ids.add(BaseTimeSeriesStringId.newBuilder()
        .setMetric(METRIC_B_STRING)
        .addTags(TAGK_STRING, TAGV_B_STRING)
        .build());
    
    MetaDataStorageResult meta_result = mock(MetaDataStorageResult.class);
    when(meta_result.timeSeries()).thenReturn(ids);
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
        data_store, context, source_config);
    node.initialize(null);
    
    try {
      node.resolveMeta(meta_result, null);
      fail("Expected IllegalDataException");
    } catch (IllegalDataException e) { }
    
    assertNull(node.executor);
    assertFalse(node.initialized.get());
    verify(upstream_a, never()).onError(any());
    verify(upstream_b, never()).onError(any());
  }

  @Test
  public void resolveMetaBytesOK() throws Exception {
    // Seems the PowerMockito won't mock down to the nested classes
    // so this will actually execute the query via multi-get.
    List<TimeSeriesId> ids = Lists.newArrayList();
    ids.add(BaseTimeSeriesByteId.newBuilder(schema_factory)
        .setMetric(METRIC_BYTES)
        .addTags(TAGK_BYTES, TAGV_BYTES)
        .build());
    ids.add(BaseTimeSeriesByteId.newBuilder(schema_factory)
        .setMetric(METRIC_BYTES)
        .addTags(TAGK_BYTES, TAGV_B_BYTES)
        .build());
    
    setupMultigetPool();
    MetaDataStorageResult meta_result = mock(MetaDataStorageResult.class);
    when(meta_result.timeSeries()).thenReturn(ids);
    when(meta_result.idType()).thenAnswer(new Answer<TypeToken<?>>() {
      @Override
      public TypeToken<?> answer(InvocationOnMock invocation) throws Throwable {
        return Const.TS_BYTE_ID;
      }
    });
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
        data_store, context, source_config);
    node.initialize(null);
    
    node.resolveMeta(meta_result, null);
    
    assertEquals(1, tsdb.runnables.size());
    tsdb.runnables.get(0).run();
    tsdb.runnables.clear();
    
    Tsdb1xMultiGet gets = (Tsdb1xMultiGet) node.executor;
    assertEquals(2, gets.tsuids.size());
    assertArrayEquals(Bytes.concat(METRIC_BYTES, TAGK_BYTES, TAGV_BYTES), 
        gets.tsuids.get(0));
    assertArrayEquals(Bytes.concat(METRIC_BYTES, TAGK_BYTES, TAGV_B_BYTES), 
        gets.tsuids.get(1));
    assertTrue(node.initialized.get());
    verify(tsdb.query_pool, times(1)).submit(node);
  }
  
  @Test
  public void resolveMetaBytesTwoMetrics() throws Exception {
    // Seems the PowerMockito won't mock down to the nested classes
    // so this will actually execute the query via multi-get.
    List<TimeSeriesId> ids = Lists.newArrayList();
    ids.add(BaseTimeSeriesByteId.newBuilder(schema_factory)
        .setMetric(METRIC_BYTES)
        .addTags(TAGK_BYTES, TAGV_BYTES)
        .build());
    ids.add(BaseTimeSeriesByteId.newBuilder(schema_factory)
        .setMetric(METRIC_B_BYTES)
        .addTags(TAGK_BYTES, TAGV_B_BYTES)
        .build());
    
    MetaDataStorageResult meta_result = mock(MetaDataStorageResult.class);
    when(meta_result.timeSeries()).thenReturn(ids);
    when(meta_result.idType()).thenAnswer(new Answer<TypeToken<?>>() {
      @Override
      public TypeToken<?> answer(InvocationOnMock invocation) throws Throwable {
        return Const.TS_BYTE_ID;
      }
    });
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
        data_store, context, source_config);
    node.initialize(null);
    
    try {
      node.resolveMeta(meta_result, null);
      fail("Expected IllegalDataException");
    } catch (IllegalDataException e) { }
    
    assertNull(node.executor);
    assertFalse(node.initialized.get());
    verify(upstream_a, never()).onError(any());
    verify(upstream_b, never()).onError(any());
  }

  @Test
  public void sentData() {
    Tsdb1xHBaseQueryNode node = new Tsdb1xHBaseQueryNode(
        data_store, context, source_config);
    node.initialize(null);
    
    assertFalse(node.sentData());
    
    node.setSentData();
    assertTrue(node.sentData());
  }
  
  void setupMultigetPool() {
    ObjectPool mget_pool = mock(ObjectPool.class);
    Tsdb1xMultiGet executor = new Tsdb1xMultiGet();
    when(mget_pool.claim()).thenReturn(executor);
    when(tsdb.getRegistry().getObjectPool(Tsdb1xMultiGetPool.TYPE))
      .thenReturn(mget_pool);
  }

  TimeSeriesDataSourceConfig.Builder baseConfig() {
    return baseConfig(START_TS, END_TS);
  }

  TimeSeriesDataSourceConfig.Builder baseConfig(int start, int end) {
    return DefaultTimeSeriesDataSourceConfig.newBuilder()
            .setMetric(MetricLiteralFilter.newBuilder()
                    .setMetric(METRIC_STRING)
                    .build())
            .setStartTimeStamp(new SecondTimeStamp(start))
            .setEndTimeStamp(new SecondTimeStamp(end))
            .setId("m1");
  }
}
