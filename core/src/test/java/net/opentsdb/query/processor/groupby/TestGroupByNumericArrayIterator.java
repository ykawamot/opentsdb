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
package net.opentsdb.query.processor.groupby;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import net.opentsdb.common.Const;
import net.opentsdb.core.MockTSDB;
import net.opentsdb.core.MockTSDBDefault;
import net.opentsdb.data.TimeSeriesId;
import net.opentsdb.data.TypedTimeSeriesIterator;
import net.opentsdb.data.types.numeric.NumericMillisecondShard;
import net.opentsdb.data.types.numeric.NumericType;
import net.opentsdb.data.types.numeric.aggregators.NumericAggregatorFactory;
import net.opentsdb.data.types.numeric.aggregators.NumericArrayAggregatorFactory;
import net.opentsdb.data.types.numeric.aggregators.SumFactory;
import net.opentsdb.pools.MockObjectPool;
import net.opentsdb.query.DefaultTimeSeriesDataSourceConfig;
import net.opentsdb.query.QueryMode;
import net.opentsdb.query.QueryNode;
import net.opentsdb.query.QueryResultId;
import net.opentsdb.query.SemanticQuery;
import net.opentsdb.query.TimeSeriesDataSourceConfig;
import net.opentsdb.query.processor.downsample.Downsample;
import net.opentsdb.query.processor.downsample.DownsampleConfig;
import net.opentsdb.query.processor.downsample.DownsampleFactory;
import net.opentsdb.query.processor.groupby.GroupByFactory.GroupByJob;
import net.opentsdb.rollup.RollupConfig;
import net.opentsdb.stats.StatsCollector;
import net.opentsdb.utils.MockBigSmallLinkedBlockingQueue;

import net.opentsdb.utils.UnitTestException;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.reflect.TypeToken;

import net.opentsdb.core.Registry;
import net.opentsdb.core.TSDB;
import net.opentsdb.data.BaseTimeSeriesStringId;
import net.opentsdb.data.MillisecondTimeStamp;
import net.opentsdb.data.SecondTimeStamp;
import net.opentsdb.data.TimeSeries;
import net.opentsdb.data.TimeSeriesDataType;
import net.opentsdb.data.TimeSeriesStringId;
import net.opentsdb.data.TimeSeriesValue;
import net.opentsdb.data.TimeSpecification;
import net.opentsdb.data.TimeStamp;
import net.opentsdb.data.types.numeric.NumericArrayTimeSeries;
import net.opentsdb.data.types.numeric.NumericArrayType;
import net.opentsdb.data.types.numeric.aggregators.ArraySumFactory;
import net.opentsdb.query.QueryPipelineContext;
import net.opentsdb.query.QueryResult;
import net.opentsdb.query.TimeSeriesQuery;
import net.opentsdb.query.DefaultQueryResultId;
import net.opentsdb.query.QueryContext;
import net.opentsdb.query.QueryFillPolicy.FillWithRealPolicy;
import net.opentsdb.query.interpolation.types.numeric.NumericInterpolatorConfig;
import net.opentsdb.query.pojo.FillPolicy;

public class TestGroupByNumericArrayIterator {
  private static final long BASE_TIME = 1514764800000L;
  
  public static MockTSDB TSDB;
  public static NumericInterpolatorConfig NUMERIC_CONFIG;
  private static GroupByFactory FACTORY;

  private Registry registry;
  private NumericInterpolatorConfig numeric_config;
  private TimeSpecification time_spec;
  private GroupByConfig config;
  private GroupBy node;
  private TimeSeries ts1;
  private TimeSeries ts2;
  private TimeSeries ts3;
  private Map<String, TimeSeries> source_map;
  private GroupByResult result;
  private QueryResult source_result;
  private StatsCollector statsCollector;
  private QueryPipelineContext context;
  private int queueThreshold = 1000;
  private int threadCount = 8;
  private int timeSeriesPerJob = 512;

  @BeforeClass
  public static void beforeClass() throws Exception {
    TSDB = MockTSDBDefault.getMockTSDB();
    NUMERIC_CONFIG =
        (NumericInterpolatorConfig) NumericInterpolatorConfig.newBuilder()
            .setFillPolicy(FillPolicy.NOT_A_NUMBER)
            .setRealFillPolicy(FillWithRealPolicy.PREFER_NEXT)
            .setDataType(NumericType.TYPE.toString())
            .build();
    FACTORY = (GroupByFactory) TSDB.getRegistry().getQueryNodeFactory(GroupByFactory.TYPE);
  }
  
  @Before
  public void before() throws Exception {
    result = mock(GroupByResult.class);
    source_result = mock(QueryResult.class);
    time_spec = mock(TimeSpecification.class);
    statsCollector = mock(StatsCollector.class);
    
    numeric_config = (NumericInterpolatorConfig) 
          NumericInterpolatorConfig.newBuilder()
        .setFillPolicy(FillPolicy.NONE)
        .setRealFillPolicy(FillWithRealPolicy.NONE)
        .setDataType(NumericArrayType.TYPE.toString())
        .build();
    
    config = GroupByConfig.newBuilder()
        .setAggregator("sum")
        .addTagKey("dc")
        .addInterpolatorConfig(numeric_config)
        .setId("Testing")
        .build();
    
    node = mock(GroupBy.class);
    when(node.config()).thenReturn(config);
    context = mock(QueryPipelineContext.class);
    when(node.pipelineContext()).thenReturn(context);
    when(node.factory()).thenReturn(FACTORY);
    final TSDB tsdb = mock(TSDB.class);
    when(context.tsdb()).thenReturn(tsdb);
    final TimeSeriesQuery q = mock(TimeSeriesQuery.class);
    when(context.query()).thenReturn(q);
    TimeStamp st = new SecondTimeStamp(System.currentTimeMillis()/1000l);
    when(q.startTime()).thenReturn(st);
    when(q.endTime()).thenReturn(st);  
    registry = mock(Registry.class);
    when(tsdb.getRegistry()).thenReturn(registry);
    when(registry.getPlugin(eq(NumericArrayAggregatorFactory.class), anyString()))
        .thenReturn(new ArraySumFactory());
    when(registry.getPlugin(eq(NumericAggregatorFactory.class), anyString()))
        .thenReturn(new SumFactory());
    when(result.downstreamResult()).thenReturn(source_result);
    when(result.source()).thenReturn(node);
    TimeSeriesDataSourceConfig dataSourceConfig = mock(TimeSeriesDataSourceConfig.class);
    when(dataSourceConfig.startTimestamp()).thenReturn(new SecondTimeStamp(1));
    when(dataSourceConfig.endTimestamp()).thenReturn(new SecondTimeStamp(5));
    when(result.dataSourceConfig()).thenReturn(dataSourceConfig);
    when(source_result.timeSpecification()).thenReturn(time_spec);
    when(result.isSourceProcessInParallel()).thenReturn(true);
    when(time_spec.start()).thenReturn(new MillisecondTimeStamp(1000));
    when(time_spec.interval()).thenReturn(Duration.ofSeconds(60));
    when(tsdb.getStatsCollector()).thenReturn(statsCollector);
    QueryContext ctx = mock(QueryContext.class);
    when(context.queryContext()).thenReturn(ctx);

    ts1 = new NumericArrayTimeSeries(
        BaseTimeSeriesStringId.newBuilder()
        .setMetric("a")
        .build(), new MillisecondTimeStamp(1000));
    ((NumericArrayTimeSeries) ts1).add(1);
    ((NumericArrayTimeSeries) ts1).add(5);
    ((NumericArrayTimeSeries) ts1).add(2);
    ((NumericArrayTimeSeries) ts1).add(1);
    
    ts2 = new NumericArrayTimeSeries(
        BaseTimeSeriesStringId.newBuilder()
        .setMetric("a")
        .build(), new MillisecondTimeStamp(1000));
    ((NumericArrayTimeSeries) ts2).add(4);
    ((NumericArrayTimeSeries) ts2).add(10);
    ((NumericArrayTimeSeries) ts2).add(8);
    ((NumericArrayTimeSeries) ts2).add(6);
    
    ts3 = new NumericArrayTimeSeries(
        BaseTimeSeriesStringId.newBuilder()
        .setMetric("a")
        .build(), new MillisecondTimeStamp(1000));
    ((NumericArrayTimeSeries) ts3).add(0);
    ((NumericArrayTimeSeries) ts3).add(7);
    ((NumericArrayTimeSeries) ts3).add(3);
    ((NumericArrayTimeSeries) ts3).add(7);
    
    source_map = Maps.newHashMapWithExpectedSize(3);
    source_map.put("a", ts1);
    source_map.put("b", ts2);
    source_map.put("c", ts3);
  }
  
  @Test
  public void ctor() throws Exception {
    GroupByNumericArrayIterator iterator = 
        new GroupByNumericArrayIterator(node, result, source_map, queueThreshold, timeSeriesPerJob, threadCount);
    assertTrue(iterator.hasNext());
    
    iterator = new GroupByNumericArrayIterator(node, result, source_map.values(), queueThreshold, timeSeriesPerJob, threadCount);
    assertTrue(iterator.hasNext());

    // invalid agg
    config = (GroupByConfig) GroupByConfig.newBuilder()
        .setAggregator("nosuchagg")
        .addTagKey("dc")
        .addInterpolatorConfig(numeric_config)
        .setId("Testing")
        .build();
    when(node.config()).thenReturn(config);
    when(registry.getPlugin(any(Class.class), anyString()))
      .thenReturn(null);
    new GroupByNumericArrayIterator(node, result, source_map.values(), queueThreshold, timeSeriesPerJob, threadCount);
    verify(node, times(1)).onError(any(IllegalArgumentException.class));
  }

  @Test
  public void iterateLongsAlligned() {
    GroupByNumericArrayIterator iterator = new GroupByNumericArrayIterator(node, result, source_map, queueThreshold, timeSeriesPerJob, threadCount);
    assertTrue(iterator.hasNext());
    
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericArrayType> v = (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertEquals(1000, v.timestamp().msEpoch());
    assertTrue(v.value().isInteger());
    assertEquals(4, v.value().longArray().length);
    assertEquals(5, v.value().longArray()[0]);
    assertEquals(22, v.value().longArray()[1]);
    assertEquals(13, v.value().longArray()[2]);
    assertEquals(14, v.value().longArray()[3]);
    
    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void iterateLongsEmptySeries() {
    ts2 = new NumericArrayTimeSeries(
        BaseTimeSeriesStringId.newBuilder()
        .setMetric("a")
        .build(), new MillisecondTimeStamp(1000));
    
    source_map = Maps.newHashMapWithExpectedSize(3);
    source_map.put("a", ts1);
    source_map.put("b", ts2);
    source_map.put("c", ts3);
    
    GroupByNumericArrayIterator iterator = new GroupByNumericArrayIterator(node, result, source_map, queueThreshold, timeSeriesPerJob, threadCount);
    assertTrue(iterator.hasNext());
    
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericArrayType> v = (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertEquals(1000, v.timestamp().msEpoch());
    assertTrue(v.value().isInteger());
    assertEquals(1, v.value().longArray()[0]);
    assertEquals(12, v.value().longArray()[1]);
    assertEquals(5, v.value().longArray()[2]);
    assertEquals(8, v.value().longArray()[3]);
    
    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void iterateLongsAndDoubles() {
    ts2 = new NumericArrayTimeSeries(
        BaseTimeSeriesStringId.newBuilder()
        .setMetric("a")
        .build(), new MillisecondTimeStamp(1000));
    ((NumericArrayTimeSeries) ts2).add(4.0);
    ((NumericArrayTimeSeries) ts2).add(10.0);
    ((NumericArrayTimeSeries) ts2).add(8.89);
    ((NumericArrayTimeSeries) ts2).add(6.01);
    
    source_map = Maps.newHashMapWithExpectedSize(3);
    source_map.put("a", ts1);
    source_map.put("b", ts2);
    source_map.put("c", ts3);
    
    GroupByNumericArrayIterator iterator = new GroupByNumericArrayIterator(node, result, source_map, queueThreshold, timeSeriesPerJob, threadCount);
    assertTrue(iterator.hasNext());
    
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericArrayType> v = (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertEquals(1000, v.timestamp().msEpoch());
    assertFalse(v.value().isInteger());
    assertEquals(5.0, v.value().doubleArray()[0], 0.001);
    assertEquals(22.0, v.value().doubleArray()[1], 0.001);
    assertEquals(13.89, v.value().doubleArray()[2], 0.001);
    assertEquals(14.01, v.value().doubleArray()[3], 0.001);
    
    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void iterateDoubles() {
    ts1 = new NumericArrayTimeSeries(
        BaseTimeSeriesStringId.newBuilder()
        .setMetric("a")
        .build(), new MillisecondTimeStamp(1000));
    ((NumericArrayTimeSeries) ts1).add(1.5);
    ((NumericArrayTimeSeries) ts1).add(5.75);
    ((NumericArrayTimeSeries) ts1).add(2.3);
    ((NumericArrayTimeSeries) ts1).add(1.8);
    
    ts2 = new NumericArrayTimeSeries(
        BaseTimeSeriesStringId.newBuilder()
        .setMetric("a")
        .build(), new MillisecondTimeStamp(1000));
    ((NumericArrayTimeSeries) ts2).add(4.1);
    ((NumericArrayTimeSeries) ts2).add(10.25);
    ((NumericArrayTimeSeries) ts2).add(8.89);
    ((NumericArrayTimeSeries) ts2).add(6.01);
    
    ts3 = new NumericArrayTimeSeries(
        BaseTimeSeriesStringId.newBuilder()
        .setMetric("a")
        .build(), new MillisecondTimeStamp(1000));
    ((NumericArrayTimeSeries) ts3).add(0.4);
    ((NumericArrayTimeSeries) ts3).add(7.89);
    ((NumericArrayTimeSeries) ts3).add(3.51);
    ((NumericArrayTimeSeries) ts3).add(7.4);
    
    source_map = Maps.newHashMapWithExpectedSize(3);
    source_map.put("a", ts1);
    source_map.put("b", ts2);
    source_map.put("c", ts3);
    
    GroupByNumericArrayIterator iterator = new GroupByNumericArrayIterator(node, result, source_map, queueThreshold, timeSeriesPerJob, threadCount);
    assertTrue(iterator.hasNext());
    
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericArrayType> v = (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertEquals(1000, v.timestamp().msEpoch());
    assertFalse(v.value().isInteger());
    assertEquals(6.0, v.value().doubleArray()[0], 0.001);
    assertEquals(23.89, v.value().doubleArray()[1], 0.001);
    assertEquals(14.7, v.value().doubleArray()[2], 0.001);
    assertEquals(15.21, v.value().doubleArray()[3], 0.001);
    
    assertFalse(iterator.hasNext());
  }

  @Test
  public void iterateDoublesSerial() {
    when(result.isSourceProcessInParallel()).thenReturn(false);
    ts1 = new NumericArrayTimeSeries(
            BaseTimeSeriesStringId.newBuilder()
                    .setMetric("a")
                    .build(), new MillisecondTimeStamp(1000));
    ((NumericArrayTimeSeries) ts1).add(1.5);
    ((NumericArrayTimeSeries) ts1).add(5.75);
    ((NumericArrayTimeSeries) ts1).add(2.3);
    ((NumericArrayTimeSeries) ts1).add(1.8);

    ts2 = new NumericArrayTimeSeries(
            BaseTimeSeriesStringId.newBuilder()
                    .setMetric("a")
                    .build(), new MillisecondTimeStamp(1000));
    ((NumericArrayTimeSeries) ts2).add(4.1);
    ((NumericArrayTimeSeries) ts2).add(10.25);
    ((NumericArrayTimeSeries) ts2).add(8.89);
    ((NumericArrayTimeSeries) ts2).add(6.01);

    ts3 = new NumericArrayTimeSeries(
            BaseTimeSeriesStringId.newBuilder()
                    .setMetric("a")
                    .build(), new MillisecondTimeStamp(1000));
    ((NumericArrayTimeSeries) ts3).add(0.4);
    ((NumericArrayTimeSeries) ts3).add(7.89);
    ((NumericArrayTimeSeries) ts3).add(3.51);
    ((NumericArrayTimeSeries) ts3).add(7.4);

    source_map = Maps.newHashMapWithExpectedSize(3);
    source_map.put("a", ts1);
    source_map.put("b", ts2);
    source_map.put("c", ts3);

    GroupByNumericArrayIterator iterator = new GroupByNumericArrayIterator(node, result, source_map, queueThreshold, timeSeriesPerJob, threadCount);
    assertTrue(iterator.hasNext());

    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericArrayType> v = (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertEquals(1000, v.timestamp().msEpoch());
    assertFalse(v.value().isInteger());
    assertEquals(6.0, v.value().doubleArray()[0], 0.001);
    assertEquals(23.89, v.value().doubleArray()[1], 0.001);
    assertEquals(14.7, v.value().doubleArray()[2], 0.001);
    assertEquals(15.21, v.value().doubleArray()[3], 0.001);

    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void iterateOneSeriesWithoutNumerics() throws Exception {
    source_map = Maps.newHashMapWithExpectedSize(3);
    source_map.put("a", ts1);
    source_map.put("b", new MockSeries());
    source_map.put("c", ts3);
    
    GroupByNumericArrayIterator iterator = new GroupByNumericArrayIterator(node, result, source_map, queueThreshold, timeSeriesPerJob, threadCount);
    assertTrue(iterator.hasNext());
    
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericArrayType> v = (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertEquals(1000, v.timestamp().msEpoch());
    assertTrue(v.value().isInteger());
    assertEquals(1, v.value().longArray()[0]);
    assertEquals(12, v.value().longArray()[1]);
    assertEquals(5, v.value().longArray()[2]);
    assertEquals(8, v.value().longArray()[3]);
    
    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void iterateNoNumerics() throws Exception {
    source_map = Maps.newHashMapWithExpectedSize(3);
    source_map.put("a", new MockSeries());
    source_map.put("b", new MockSeries());
    source_map.put("c", new MockSeries());
    
    GroupByNumericArrayIterator iterator = new GroupByNumericArrayIterator(node, result, source_map, queueThreshold, timeSeriesPerJob, threadCount);
    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void iterateFillNonInfectiousNans() throws Exception {
    numeric_config = (NumericInterpolatorConfig) NumericInterpolatorConfig.newBuilder()
        .setFillPolicy(FillPolicy.NOT_A_NUMBER)
        .setRealFillPolicy(FillWithRealPolicy.NONE)
        .setDataType(NumericArrayType.TYPE.toString())
        .build();
    
    config = GroupByConfig.newBuilder()
        .setAggregator("sum")
        .addTagKey("dc")
        .addInterpolatorConfig(numeric_config)
        .setId("Testing")
        .build();
    when(node.config()).thenReturn(config);
    
    ts2 = new NumericArrayTimeSeries(
        BaseTimeSeriesStringId.newBuilder()
        .setMetric("a")
        .build(), new MillisecondTimeStamp(1000));
    ((NumericArrayTimeSeries) ts2).add(4);
    ((NumericArrayTimeSeries) ts2).add(Double.NaN);
    ((NumericArrayTimeSeries) ts2).add(8);
    ((NumericArrayTimeSeries) ts2).add(Double.NaN);
    
    source_map = Maps.newHashMapWithExpectedSize(3);
    source_map.put("a", ts1);
    source_map.put("b", ts2);
    source_map.put("c", ts3);
    
    GroupByNumericArrayIterator iterator = new GroupByNumericArrayIterator(node, result, source_map, queueThreshold, timeSeriesPerJob, threadCount);
    assertTrue(iterator.hasNext());
    
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericArrayType> v = (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertEquals(1000, v.timestamp().msEpoch());
    assertFalse(v.value().isInteger());
    assertEquals(5, v.value().doubleArray()[0], 0.001);
    assertEquals(12, v.value().doubleArray()[1], 0.001);
    assertEquals(13, v.value().doubleArray()[2], 0.001);
    assertEquals(8, v.value().doubleArray()[3], 0.001);
    
    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void iterateFillInfectiousNan() throws Exception {
    numeric_config = (NumericInterpolatorConfig) NumericInterpolatorConfig.newBuilder()
        .setFillPolicy(FillPolicy.NOT_A_NUMBER)
        .setRealFillPolicy(FillWithRealPolicy.NONE)
        .setDataType(NumericArrayType.TYPE.toString())
        .build();
    
    config = GroupByConfig.newBuilder()
        .setAggregator("sum")
        .addTagKey("dc")
        .setInfectiousNan(true)
        .addInterpolatorConfig(numeric_config)
        .setId("Testing")
        .build();
    when(node.config()).thenReturn(config);
    
    ts2 = new NumericArrayTimeSeries(
        BaseTimeSeriesStringId.newBuilder()
        .setMetric("a")
        .build(), new MillisecondTimeStamp(1000));
    ((NumericArrayTimeSeries) ts2).add(4);
    ((NumericArrayTimeSeries) ts2).add(Double.NaN);
    ((NumericArrayTimeSeries) ts2).add(8);
    ((NumericArrayTimeSeries) ts2).add(Double.NaN);
    
    source_map = Maps.newHashMapWithExpectedSize(3);
    source_map.put("a", ts1);
    source_map.put("b", ts2);
    source_map.put("c", ts3);
    
    GroupByNumericArrayIterator iterator = new GroupByNumericArrayIterator(node, result, source_map, queueThreshold, timeSeriesPerJob, threadCount);
    assertTrue(iterator.hasNext());
    
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericArrayType> v = (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertEquals(1000, v.timestamp().msEpoch());
    assertFalse(v.value().isInteger());
    System.out.println(Arrays.toString(v.value().doubleArray()));
    assertEquals(5, v.value().doubleArray()[0], 0.001);
    assertTrue(Double.isNaN(v.value().doubleArray()[1]));
    assertEquals(13, v.value().doubleArray()[2], 0.001);
    assertTrue(Double.isNaN(v.value().doubleArray()[3]));
    
    assertFalse(iterator.hasNext());
  }

  @Test
  public void testAccumulationInParallel() {
    DownsampleConfig dsConfig =
        DownsampleConfig.newBuilder()
            .setAggregator("sum")
            .setId("foo")
            .setInterval("1m")
            .setRunAll(true)
            .setStart("1514764800")
            .setEnd("1514765040")
            .setFill(true)
            .setProcessAsArrays(true)
            .addInterpolatorConfig(numeric_config)
            .setRunAll(false)
            .build();

    SemanticQuery q =
        SemanticQuery.newBuilder()
            .setMode(QueryMode.SINGLE)
            .setStart("1514764800")
            .setEnd("1514765040")
            .setExecutionGraph(Collections.emptyList())
            .build();

    when(context.query()).thenReturn(q);
    when(context.queryContext()).thenReturn(mock(QueryContext.class));
    DownsampleFactory factory = new DownsampleFactory();
    factory.initialize(TSDB, null);
    Downsample ds = new Downsample(factory, context, dsConfig);
    ds.initialize(null);
    QueryResult r = mock(QueryResult.class);
    when(r.dataSource()).thenReturn(new DefaultQueryResultId("m1", "m1"));
    Downsample.DownsampleResult dsResult = ds.new DownsampleResult(r);

    ts1 =
        new NumericMillisecondShard(
            BaseTimeSeriesStringId.newBuilder().setMetric("a").build(),
            new MillisecondTimeStamp(BASE_TIME + 1000),
            new MillisecondTimeStamp(BASE_TIME + 7000));
    ((NumericMillisecondShard) ts1).add(BASE_TIME + 1000, 1);

    ts2 =
        new NumericMillisecondShard(
            BaseTimeSeriesStringId.newBuilder().setMetric("a").build(),
            new MillisecondTimeStamp(BASE_TIME + 1000),
            new MillisecondTimeStamp(BASE_TIME + 7000));
    ((NumericMillisecondShard) ts2).add(BASE_TIME + 1000, 4);

    ts3 =
        new NumericMillisecondShard(
            BaseTimeSeriesStringId.newBuilder().setMetric("a").build(),
            new MillisecondTimeStamp(BASE_TIME + 1000),
            new MillisecondTimeStamp(BASE_TIME + 7000));
    ((NumericMillisecondShard) ts3).add(BASE_TIME + 1000, 0);

    source_map = Maps.newHashMapWithExpectedSize(3);
    source_map.put("a", dsResult.new DownsampleTimeSeries(ts1));
    source_map.put("b", dsResult.new DownsampleTimeSeries(ts2));
    source_map.put("c", dsResult.new DownsampleTimeSeries(ts3));

    when(this.result.isSourceProcessInParallel()).thenReturn(true);
    when(node.getDownsampleConfig()).thenReturn(dsConfig);

    GroupByNumericArrayIterator iterator =
        new GroupByNumericArrayIterator(node, this.result, source_map, queueThreshold, timeSeriesPerJob, threadCount);

    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericArrayType> v = (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertEquals(1000, v.timestamp().msEpoch());
    assertFalse(v.value().isInteger());
    double[] doubles = v.value().doubleArray();
    assertEquals(4, doubles.length);
    assertEquals(5.0, doubles[0], 0.0);
    assertTrue(Double.isNaN(doubles[1]));
    assertTrue(Double.isNaN(doubles[2]));
    assertTrue(Double.isNaN(doubles[3]));
    assertFalse(iterator.hasNext());

    TimeSeries ts4 =
        new NumericMillisecondShard(
            BaseTimeSeriesStringId.newBuilder().setMetric("a").build(),
            new MillisecondTimeStamp(BASE_TIME + 1000),
            new MillisecondTimeStamp(BASE_TIME + 7000));
    ((NumericMillisecondShard) ts4).add(BASE_TIME + 1000, 10);
    TimeSeries ts5 =
        new NumericMillisecondShard(
            BaseTimeSeriesStringId.newBuilder().setMetric("a").build(),
            new MillisecondTimeStamp(BASE_TIME + 1000),
            new MillisecondTimeStamp(BASE_TIME + 7000));
    ((NumericMillisecondShard) ts5).add(BASE_TIME + 1000, 10);
    TimeSeries ts6 =
        new NumericMillisecondShard(
            BaseTimeSeriesStringId.newBuilder().setMetric("a").build(),
            new MillisecondTimeStamp(BASE_TIME + 1000),
            new MillisecondTimeStamp(BASE_TIME + 7000));
    ((NumericMillisecondShard) ts6).add(BASE_TIME + 1000, 5);
    TimeSeries ts7 =
        new NumericMillisecondShard(
            BaseTimeSeriesStringId.newBuilder().setMetric("a").build(),
            new MillisecondTimeStamp(BASE_TIME + 1000),
            new MillisecondTimeStamp(BASE_TIME + 7000));
    ((NumericMillisecondShard) ts7).add(BASE_TIME + 1000, 20);
    TimeSeries ts8 =
        new NumericMillisecondShard(
            BaseTimeSeriesStringId.newBuilder().setMetric("a").build(),
            new MillisecondTimeStamp(BASE_TIME + 1000),
            new MillisecondTimeStamp(BASE_TIME + 7000));
    ((NumericMillisecondShard) ts8).add(BASE_TIME + 1000, 10);
    TimeSeries ts9 =
        new NumericMillisecondShard(
            BaseTimeSeriesStringId.newBuilder().setMetric("a").build(),
            new MillisecondTimeStamp(BASE_TIME + 1000),
            new MillisecondTimeStamp(BASE_TIME + 7000));
    ((NumericMillisecondShard) ts9).add(BASE_TIME + 1000, 10);

    source_map.put("d", dsResult.new DownsampleTimeSeries(ts4));
    source_map.put("e", dsResult.new DownsampleTimeSeries(ts5));
    source_map.put("f", dsResult.new DownsampleTimeSeries(ts6));
    source_map.put("g", dsResult.new DownsampleTimeSeries(ts7));
    source_map.put("h", dsResult.new DownsampleTimeSeries(ts8));
    source_map.put("i", dsResult.new DownsampleTimeSeries(ts9));

    iterator =
        new GroupByNumericArrayIterator(node, this.result, source_map, queueThreshold, timeSeriesPerJob, threadCount);
    assertTrue(iterator.hasNext());
    v = (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertEquals(1000, v.timestamp().msEpoch());
    assertFalse(v.value().isInteger());
    doubles = v.value().doubleArray();
    assertEquals(4, doubles.length);
    assertEquals(70.0, doubles[0], 0.0);
    assertTrue(Double.isNaN(doubles[1]));
    assertTrue(Double.isNaN(doubles[2]));
    assertTrue(Double.isNaN(doubles[3]));
    assertFalse(iterator.hasNext());
  }

  @Test
  public void testAccumulationInParallelManyJobs() {
    DownsampleConfig dsConfig =
        DownsampleConfig.newBuilder()
            .setAggregator("sum")
            .setId("foo")
            .setInterval("1s")
            .setRunAll(true)
            .setStart(Long.toString(BASE_TIME / 1000))
            .setEnd(Long.toString((BASE_TIME / 1000) + 10))
            .addInterpolatorConfig(numeric_config)
            .setRunAll(false)
            .build();

    SemanticQuery q =
        SemanticQuery.newBuilder()
            .setMode(QueryMode.SINGLE)
            .setStart(Long.toString(BASE_TIME / 1000))
            .setEnd(Long.toString((BASE_TIME / 1000) + 10))
            .setExecutionGraph(Collections.emptyList())
            .build();

    when(context.query()).thenReturn(q);
    DownsampleFactory factory = new DownsampleFactory();
    factory.initialize(TSDB, null);
    Downsample ds = new Downsample(factory, context, dsConfig);
    ds.initialize(null);
    when(context.queryContext()).thenReturn(mock(QueryContext.class));
    QueryResult ds_of_ds_result = mock(QueryResult.class);
    when(ds_of_ds_result.dataSource()).thenReturn(new DefaultQueryResultId("m1", "m1"));
    when(ds_of_ds_result.timeSpecification()).thenReturn(time_spec);
    Downsample.DownsampleResult dsResult = ds.new DownsampleResult(ds_of_ds_result);
    when(result.downstreamResult()).thenReturn(dsResult);
    when(dsResult.timeSpecification()).thenReturn(time_spec);
    when(time_spec.start()).thenReturn(new MillisecondTimeStamp(BASE_TIME));
    when(time_spec.interval()).thenReturn(Duration.ofSeconds(1));
    
    List<TimeSeries> series = Lists.newArrayList();
    for (int i = 0; i < 1024; i++) {
      TimeSeries ts = new NumericArrayTimeSeries(
              BaseTimeSeriesStringId.newBuilder().setMetric("a").build(),
              new MillisecondTimeStamp(BASE_TIME));
      
      for (int x = 0; x < 10; x++) {
        ((NumericArrayTimeSeries) ts).add(1);
      }
      series.add(dsResult.new DownsampleTimeSeries(ts));
    }
    
    when(this.result.isSourceProcessInParallel()).thenReturn(true);
    when(node.getDownsampleConfig()).thenReturn(dsConfig);
    
    GroupByNumericArrayIterator iterator = new GroupByNumericArrayIterator(
        node, this.result, series, queueThreshold, 16, threadCount);
    assertTrue(iterator.hasNext());
    
    TimeSeriesValue<NumericArrayType> v = (TimeSeriesValue<NumericArrayType>) iterator.next();
    
    assertEquals(BASE_TIME, v.timestamp().msEpoch());
    assertTrue(v.value().isInteger());
    assertEquals(10, v.value().end());
    long[] expected = new long[10];
    Arrays.fill(expected, 1024);
    assertArrayEquals(expected, v.value().longArray());
    assertFalse(iterator.hasNext());
  }

  @Test
  public void testAccumulationInParallelManyJobsOneFailed() {
    DownsampleConfig dsConfig =
            DownsampleConfig.newBuilder()
                    .setAggregator("sum")
                    .setId("foo")
                    .setInterval("1s")
                    .setRunAll(true)
                    .setStart(Long.toString(BASE_TIME / 1000))
                    .setEnd(Long.toString((BASE_TIME / 1000) + 10))
                    .addInterpolatorConfig(numeric_config)
                    .setRunAll(false)
                    .build();

    SemanticQuery q =
            SemanticQuery.newBuilder()
                    .setMode(QueryMode.SINGLE)
                    .setStart(Long.toString(BASE_TIME / 1000))
                    .setEnd(Long.toString((BASE_TIME / 1000) + 10))
                    .setExecutionGraph(Collections.emptyList())
                    .build();

    when(context.query()).thenReturn(q);
    DownsampleFactory factory = new DownsampleFactory();
    factory.initialize(TSDB, null);
    Downsample ds = new Downsample(factory, context, dsConfig);
    ds.initialize(null);
    when(context.queryContext()).thenReturn(mock(QueryContext.class));
    QueryResult ds_of_ds_result = mock(QueryResult.class);
    when(ds_of_ds_result.dataSource()).thenReturn(new DefaultQueryResultId("m1", "m1"));
    when(ds_of_ds_result.timeSpecification()).thenReturn(time_spec);
    Downsample.DownsampleResult dsResult = ds.new DownsampleResult(ds_of_ds_result);
    when(result.downstreamResult()).thenReturn(dsResult);
    when(dsResult.timeSpecification()).thenReturn(time_spec);
    when(time_spec.start()).thenReturn(new MillisecondTimeStamp(BASE_TIME));
    when(time_spec.interval()).thenReturn(Duration.ofSeconds(1));

    List<TimeSeries> series = Lists.newArrayList();
    for (int i = 0; i < 1024; i++) {
      TimeSeries ts = new NumericArrayTimeSeries(
              BaseTimeSeriesStringId.newBuilder().setMetric("a").build(),
              new MillisecondTimeStamp(BASE_TIME));

      for (int x = 0; x < 10; x++) {
        ((NumericArrayTimeSeries) ts).add(1);
      }

      if (i == 128) {
       TimeSeries mockTs = mock(Downsample.DownsampleResult.DownsampleTimeSeries.class);
       when(mockTs.iterator(NumericArrayType.TYPE)).thenThrow(new UnitTestException());
       series.add(mockTs);
      } else {
        series.add(dsResult.new DownsampleTimeSeries(ts));
      }
    }

    when(this.result.isSourceProcessInParallel()).thenReturn(true);
    when(node.getDownsampleConfig()).thenReturn(dsConfig);

    GroupByNumericArrayIterator iterator = new GroupByNumericArrayIterator(
            node, this.result, series, queueThreshold, 16, threadCount);
    assertTrue(iterator.hasNext());
    // there is data but it's wrong.
    verify(node, times(1)).onError(any(UnitTestException.class));
  }

  @Test
  public void testAccumulationInParallelManyJobsOneNull() {
    DownsampleConfig dsConfig =
            DownsampleConfig.newBuilder()
                    .setAggregator("sum")
                    .setId("foo")
                    .setInterval("1s")
                    .setRunAll(true)
                    .setStart(Long.toString(BASE_TIME / 1000))
                    .setEnd(Long.toString((BASE_TIME / 1000) + 10))
                    .addInterpolatorConfig(numeric_config)
                    .setRunAll(false)
                    .build();

    SemanticQuery q =
            SemanticQuery.newBuilder()
                    .setMode(QueryMode.SINGLE)
                    .setStart(Long.toString(BASE_TIME / 1000))
                    .setEnd(Long.toString((BASE_TIME / 1000) + 10))
                    .setExecutionGraph(Collections.emptyList())
                    .build();

    when(context.query()).thenReturn(q);
    DownsampleFactory factory = new DownsampleFactory();
    factory.initialize(TSDB, null);
    Downsample ds = new Downsample(factory, context, dsConfig);
    ds.initialize(null);
    when(context.queryContext()).thenReturn(mock(QueryContext.class));
    QueryResult ds_of_ds_result = mock(QueryResult.class);
    when(ds_of_ds_result.dataSource()).thenReturn(new DefaultQueryResultId("m1", "m1"));
    when(ds_of_ds_result.timeSpecification()).thenReturn(time_spec);
    Downsample.DownsampleResult dsResult = ds.new DownsampleResult(ds_of_ds_result);
    when(result.downstreamResult()).thenReturn(dsResult);
    when(dsResult.timeSpecification()).thenReturn(time_spec);
    when(time_spec.start()).thenReturn(new MillisecondTimeStamp(BASE_TIME));
    when(time_spec.interval()).thenReturn(Duration.ofSeconds(1));

    List<TimeSeries> series = Lists.newArrayList();
    for (int i = 0; i < 1024; i++) {
      TimeSeries ts = new NumericArrayTimeSeries(
              BaseTimeSeriesStringId.newBuilder().setMetric("a").build(),
              new MillisecondTimeStamp(BASE_TIME));

      for (int x = 0; x < 10; x++) {
        ((NumericArrayTimeSeries) ts).add(1);
      }

      if (i == 128) {
        series.add(null);
      } else {
        series.add(dsResult.new DownsampleTimeSeries(ts));
      }
    }

    when(this.result.isSourceProcessInParallel()).thenReturn(true);
    when(node.getDownsampleConfig()).thenReturn(dsConfig);

    GroupByNumericArrayIterator iterator = new GroupByNumericArrayIterator(
            node, this.result, series, queueThreshold, 16, threadCount);
    assertFalse(iterator.hasNext());
    // there is data but it's wrong.
    verify(node, times(1)).onError(any(UnitTestException.class));
  }

  @Test
  public void testAccumulationInParallelManyJobsQueryClosed() {
    DownsampleConfig dsConfig =
            DownsampleConfig.newBuilder()
                    .setAggregator("sum")
                    .setId("foo")
                    .setInterval("1s")
                    .setRunAll(true)
                    .setStart(Long.toString(BASE_TIME / 1000))
                    .setEnd(Long.toString((BASE_TIME / 1000) + 10))
                    .addInterpolatorConfig(numeric_config)
                    .setRunAll(false)
                    .build();

    SemanticQuery q =
            SemanticQuery.newBuilder()
                    .setMode(QueryMode.SINGLE)
                    .setStart(Long.toString(BASE_TIME / 1000))
                    .setEnd(Long.toString((BASE_TIME / 1000) + 10))
                    .setExecutionGraph(Collections.emptyList())
                    .build();

    when(context.query()).thenReturn(q);
    DownsampleFactory factory = new DownsampleFactory();
    factory.initialize(TSDB, null);
    Downsample ds = new Downsample(factory, context, dsConfig);
    ds.initialize(null);
    QueryContext ctx = mock(QueryContext.class);
    when(context.queryContext()).thenReturn(ctx);
    QueryResult ds_of_ds_result = mock(QueryResult.class);
    when(ds_of_ds_result.dataSource()).thenReturn(new DefaultQueryResultId("m1", "m1"));
    when(ds_of_ds_result.timeSpecification()).thenReturn(time_spec);
    Downsample.DownsampleResult dsResult = ds.new DownsampleResult(ds_of_ds_result);
    when(result.downstreamResult()).thenReturn(dsResult);
    when(dsResult.timeSpecification()).thenReturn(time_spec);
    when(time_spec.start()).thenReturn(new MillisecondTimeStamp(BASE_TIME));
    when(time_spec.interval()).thenReturn(Duration.ofSeconds(1));

    when(ctx.isClosed()).thenReturn(false)
                        .thenReturn(false)
                        .thenReturn(false)
                        .thenReturn(true);
    List<TimeSeries> series = Lists.newArrayList();
    for (int i = 0; i < 1024; i++) {
      TimeSeries ts = new NumericArrayTimeSeries(
              BaseTimeSeriesStringId.newBuilder().setMetric("a").build(),
              new MillisecondTimeStamp(BASE_TIME));

      for (int x = 0; x < 10; x++) {
        ((NumericArrayTimeSeries) ts).add(1);
      }
      series.add(dsResult.new DownsampleTimeSeries(ts));
    }

    when(this.result.isSourceProcessInParallel()).thenReturn(true);
    when(node.getDownsampleConfig()).thenReturn(dsConfig);

    GroupByNumericArrayIterator iterator = new GroupByNumericArrayIterator(
            node, this.result, series, queueThreshold, 16, threadCount);
    assertTrue(iterator.hasNext());
    // there is data but it's wrong.
  }

  @Test
  public void singleSeriesShortcut() throws Exception {
    source_map = Maps.newHashMapWithExpectedSize(1);
    source_map.put("a", ts1);
    
    GroupByNumericArrayIterator iterator = new GroupByNumericArrayIterator(node, result, source_map, queueThreshold, timeSeriesPerJob, threadCount);
    assertTrue(iterator.hasNext());
    
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericArrayType> v = (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertEquals(1000, v.timestamp().msEpoch());
    assertTrue(v.value().isInteger());
    assertEquals(1, v.value().longArray()[0]);
    assertEquals(5, v.value().longArray()[1]);
    assertEquals(2, v.value().longArray()[2]);
    assertEquals(1, v.value().longArray()[3]);
    
    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void singleSeriesRefShortcut() throws Exception {
    List<TimeSeries> sources = Lists.newArrayList(ts1);
    when(source_result.timeSeries()).thenReturn(sources);
    when(result.downstreamResult()).thenReturn(source_result);
    
    GroupByNumericArrayIterator iterator =
            new GroupByNumericArrayIterator(node, result, new int[] { 0 }, 1, queueThreshold, timeSeriesPerJob, threadCount);
    assertTrue(iterator.hasNext());
    
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericArrayType> v = (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertEquals(1000, v.timestamp().msEpoch());
    assertTrue(v.value().isInteger());
    assertEquals(1, v.value().longArray()[0]);
    assertEquals(5, v.value().longArray()[1]);
    assertEquals(2, v.value().longArray()[2]);
    assertEquals(1, v.value().longArray()[3]);
    
    assertFalse(iterator.hasNext());
  }

  @Test
  public void testAccumulationInParallelRefs() throws Exception {
    source_result = new MockRefResult(16);
    when(result.downstreamResult()).thenReturn(source_result);
    int[] refs = new int[] { 3, 6, 8, 9 };

    GroupByNumericArrayIterator iterator = new GroupByNumericArrayIterator(
            node, this.result, refs, refs.length, queueThreshold, timeSeriesPerJob, threadCount);
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericArrayType> v = (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertEquals(1000, v.timestamp().msEpoch());
    assertTrue(v.value().isInteger());
    System.out.println(Arrays.toString(v.value().longArray()));
    assertArrayEquals(new long[] { 30, 30, 30, 30, 30 }, v.value().longArray());
  }

  class MockSeries implements TimeSeries {

    @Override
    public TimeSeriesStringId id() {
      return BaseTimeSeriesStringId.newBuilder()
          .setMetric("a")
          .build();
    }

    @Override
    public Optional<TypedTimeSeriesIterator<? extends TimeSeriesDataType>> iterator(
        TypeToken<? extends TimeSeriesDataType> type) {
      return Optional.empty();
    }

    @Override
    public Collection<TypedTimeSeriesIterator<? extends TimeSeriesDataType>> iterators() {
      return Collections.emptyList();
    }

    @Override
    public Collection<TypeToken<? extends TimeSeriesDataType>> types() {
      return Lists.newArrayList();
    }

    @Override
    public void close() { }
    
  }

  class MockRefResult implements QueryResult {
    List<TimeSeries> ts;
    MockRefResult(int count) {
      ts = Lists.newArrayList();
      for (int i = 0; i < count; i++) {
        NumericArrayTimeSeries nats = new NumericArrayTimeSeries(
                BaseTimeSeriesStringId.newBuilder()
                        .setMetric("a")
                        .build(), new MillisecondTimeStamp(1000));
        for (int x = 0; x < 5; x++) {
          nats.add(i + 1);
        }
        ts.add(nats);
      }
    }

    @Override
    public TimeSpecification timeSpecification() {
      return time_spec;
    }

    @Override
    public List<TimeSeries> timeSeries() {
      return ts;
    }

    @Override
    public String error() {
      return null;
    }

    @Override
    public Throwable exception() {
      return null;
    }

    @Override
    public long sequenceId() {
      return 0;
    }

    @Override
    public QueryNode source() {
      return node;
    }

    @Override
    public QueryResultId dataSource() {
      return null;
    }

    @Override
    public TypeToken<? extends TimeSeriesId> idType() {
      return Const.TS_STRING_ID;
    }

    @Override
    public ChronoUnit resolution() {
      return null;
    }

    @Override
    public RollupConfig rollupConfig() {
      return null;
    }

    @Override
    public void close() {

    }

    @Override
    public boolean processInParallel() {
      return true;
    }
  }
}