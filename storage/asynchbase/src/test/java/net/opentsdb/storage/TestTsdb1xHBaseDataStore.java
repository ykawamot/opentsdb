// This file is part of OpenTSDB.
// Copyright (C) 2018  The OpenTSDB Authors.
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import net.opentsdb.data.MockLowLevelMetricData;
import net.opentsdb.data.MockLowLevelRollupMetricData;
import net.opentsdb.data.TimeSeriesDataType;
import net.opentsdb.data.TimeSeriesSharedTagsAndTimeData;
import net.opentsdb.data.TimeSeriesValue;
import net.opentsdb.data.TimeStamp;
import net.opentsdb.rollup.DefaultRollupConfig;
import net.opentsdb.rollup.MutableRollupDatum;
import net.opentsdb.rollup.RollupConfig;
import net.opentsdb.uid.UniqueId;
import net.opentsdb.utils.UnitTestException;
import org.hbase.async.HBaseClient;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.reflect.Whitebox;

import net.opentsdb.common.Const;
import net.opentsdb.configuration.Configuration;
import net.opentsdb.configuration.UnitTestConfiguration;
import net.opentsdb.core.DefaultRegistry;
import net.opentsdb.core.DefaultTSDB;
import net.opentsdb.data.BaseTimeSeriesDatumStringId;
import net.opentsdb.data.MillisecondTimeStamp;
import net.opentsdb.data.SecondTimeStamp;
import net.opentsdb.data.TimeSeriesDatum;
import net.opentsdb.data.TimeSeriesDatumStringId;
import net.opentsdb.data.types.numeric.MutableNumericValue;
import net.opentsdb.storage.WriteStatus.WriteState;
import net.opentsdb.storage.schemas.tsdb1x.NumericCodec;
import net.opentsdb.storage.schemas.tsdb1x.Schema;
import net.opentsdb.storage.schemas.tsdb1x.Tsdb1xDataStoreFactory;
import net.opentsdb.uid.UniqueIdStore;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RunWith(PowerMockRunner.class)
@PrepareForTest({ Tsdb1xHBaseDataStore.class, HBaseClient.class })
public class TestTsdb1xHBaseDataStore extends UTBase {

  private Tsdb1xHBaseFactory factory;
//  private DefaultTSDB tsdb;
//  private Configuration config;
//  private DefaultRegistry registry;
  
  @Before
  public void before() throws Exception {
    factory = mock(Tsdb1xHBaseFactory.class);
//    tsdb = mock(DefaultTSDB.class);
//    config = UnitTestConfiguration.getConfiguration();
//    registry = mock(DefaultRegistry.class);
//    when(tsdb.getConfig()).thenReturn(config);
//    when(tsdb.getRegistry()).thenReturn(registry);
    when(factory.tsdb()).thenReturn(tsdb);
    PowerMockito.whenNew(HBaseClient.class).withAnyArguments().thenReturn(client);
    storage.flushStorage("tsdb".getBytes(Const.ASCII_US_CHARSET));
    when(schema_factory.rollupConfig()).thenReturn(null);
  }
  
  @Test
  public void ctorDefault() throws Exception {
    final Tsdb1xHBaseDataStore store = 
        new Tsdb1xHBaseDataStore(factory, "UT", schema);
    assertArrayEquals("tsdb".getBytes(Const.ISO_8859_CHARSET), store.dataTable());
    assertArrayEquals("tsdb-uid".getBytes(Const.ISO_8859_CHARSET), store.uidTable());
    assertSame(tsdb, store.tsdb());
    assertNotNull(store.uidStore());
    verify(tsdb.registry, atLeastOnce()).registerSharedObject(eq("UT_uidstore"), 
        any(UniqueIdStore.class));
  }
  
  @Test
  public void writeDatum() throws Exception {
    MutableNumericValue value = 
        new MutableNumericValue(new SecondTimeStamp(1262304000), 42);
    TimeSeriesDatumStringId id = BaseTimeSeriesDatumStringId.newBuilder()
        .setMetric(METRIC_STRING)
        .addTags(TAGK_STRING, TAGV_STRING)
        .build();
    
    Tsdb1xHBaseDataStore store = 
        new Tsdb1xHBaseDataStore(factory, "UT", schema);
    Whitebox.setInternalState(store, "use_dp_timestamp", false);
    store.write(null, TimeSeriesDatum.wrap(id, value), null);

    byte[] row_key = new byte[] { 0, 0, 1, 75, 61, 59, 0, 0, 0, 1, 0, 0, 1 };
    assertArrayEquals(new byte[] { 42 }, storage.getColumn(
        store.dataTable(), row_key, Tsdb1xHBaseDataStore.DATA_FAMILY, 
        new byte[] { 0, 0 }));

    // appends
    Whitebox.setInternalState(store, "write_appends", true);
    store.write(null, TimeSeriesDatum.wrap(id, value), null);
    assertArrayEquals(new byte[] { 0, 0, 42 }, storage.getColumn(
        store.dataTable(), row_key, Tsdb1xHBaseDataStore.DATA_FAMILY, 
        NumericCodec.APPEND_QUALIFIER));
    
    Whitebox.setInternalState(store, "write_appends", false);
    Whitebox.setInternalState(store, "encode_as_appends", true);
    value.resetValue(1);
    store.write(null, TimeSeriesDatum.wrap(id, value), null);
    // overwrites
    assertArrayEquals(new byte[] { 0, 0, 1 }, storage.getColumn(
        store.dataTable(), row_key, Tsdb1xHBaseDataStore.DATA_FAMILY, 
        NumericCodec.APPEND_QUALIFIER));
    
    // bad metric
    id = BaseTimeSeriesDatumStringId.newBuilder()
        .setMetric(METRIC_STRING_EX)
        .addTags(TAGK_STRING, TAGV_STRING)
        .build();
    store.write(null, TimeSeriesDatum.wrap(id, value), null);
    // TODO - validate
  }

  @Test
  public void writeSharedData() throws Exception {
    TimeStamp ts = new SecondTimeStamp(1262304000);
    Map<String, String> tags = ImmutableMap.<String, String>builder()
            .put(TAGK_STRING, TAGV_STRING)
            .build();

    MutableNumericValue dp = new MutableNumericValue(ts, 42);
    TimeSeriesDatumStringId id = BaseTimeSeriesDatumStringId.newBuilder()
            .setMetric(METRIC_STRING)
            .addTags(TAGK_STRING, TAGV_STRING)
            .build();

    List<TimeSeriesDatum> data = Lists.newArrayList();
    data.add(TimeSeriesDatum.wrap(id, dp));

    dp = new MutableNumericValue(ts, 24);
    id = BaseTimeSeriesDatumStringId.newBuilder()
            .setMetric(METRIC_B_STRING)
            .addTags(TAGK_STRING, TAGV_STRING)
            .build();
    data.add(TimeSeriesDatum.wrap(id, dp));

    TimeSeriesSharedTagsAndTimeData shared =
            TimeSeriesSharedTagsAndTimeData.fromCollection(data);
    Tsdb1xHBaseDataStore store =
            new Tsdb1xHBaseDataStore(factory, "UT", schema);
    Whitebox.setInternalState(store, "use_dp_timestamp", false);
    store.write(null, shared, null);

    byte[] row_key = new byte[] { 0, 0, 1, 75, 61, 59, 0, 0, 0, 1, 0, 0, 1 };
    assertArrayEquals(new byte[] { 42 }, storage.getColumn(
            store.dataTable(), row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            new byte[] { 0, 0 }));

    row_key = new byte[] { 0, 0, 2, 75, 61, 59, 0, 0, 0, 1, 0, 0, 1 };
    assertArrayEquals(new byte[] { 24 }, storage.getColumn(
            store.dataTable(), row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            new byte[] { 0, 0 }));

    // appends
    Whitebox.setInternalState(store, "write_appends", true);
    store.write(null, shared, null);

    row_key = new byte[] { 0, 0, 1, 75, 61, 59, 0, 0, 0, 1, 0, 0, 1 };
    assertArrayEquals(new byte[] { 0, 0, 42 }, storage.getColumn(
            store.dataTable(), row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            NumericCodec.APPEND_QUALIFIER));

    row_key = new byte[] { 0, 0, 2, 75, 61, 59, 0, 0, 0, 1, 0, 0, 1 };
    assertArrayEquals(new byte[] { 0, 0, 24 }, storage.getColumn(
            store.dataTable(), row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            NumericCodec.APPEND_QUALIFIER));

    Whitebox.setInternalState(store, "write_appends", false);
    Whitebox.setInternalState(store, "encode_as_appends", true);
    ((MutableNumericValue) data.get(0).value()).resetValue(1);
    ((MutableNumericValue) data.get(1).value()).resetValue(2);

    store.write(null, shared, null);

    row_key = new byte[] { 0, 0, 1, 75, 61, 59, 0, 0, 0, 1, 0, 0, 1 };
    assertArrayEquals(new byte[] { 0, 0, 1 }, storage.getColumn(
            store.dataTable(), row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            NumericCodec.APPEND_QUALIFIER));

    row_key = new byte[] { 0, 0, 2, 75, 61, 59, 0, 0, 0, 1, 0, 0, 1 };
    assertArrayEquals(new byte[] { 0, 0, 2 }, storage.getColumn(
            store.dataTable(), row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            NumericCodec.APPEND_QUALIFIER));

    // one error
    id = BaseTimeSeriesDatumStringId.newBuilder()
            .setMetric(METRIC_STRING_EX)
            .addTags(TAGK_STRING, TAGV_STRING)
            .build();
    dp = new MutableNumericValue(ts, 8);
    data.set(0, TimeSeriesDatum.wrap(id, dp));
    shared =
            TimeSeriesSharedTagsAndTimeData.fromCollection(data);

    store.write(null, shared, null);
    // TODO - validate
  }

  @Test
  public void writeLowLevel() throws Exception {
    TimeStamp ts = new SecondTimeStamp(1262304000);
    Map<String, String> tags = ImmutableMap.<String, String>builder()
            .put(TAGK_STRING, TAGV_STRING)
            .build();

    MutableNumericValue dp = new MutableNumericValue(ts, 42);
    TimeSeriesDatumStringId id = BaseTimeSeriesDatumStringId.newBuilder()
            .setMetric(METRIC_STRING)
            .addTags(TAGK_STRING, TAGV_STRING)
            .build();
    TimeSeriesDatum datum_1 = TimeSeriesDatum.wrap(id, dp);

    dp = new MutableNumericValue(ts, 24);
    id = BaseTimeSeriesDatumStringId.newBuilder()
            .setMetric(METRIC_B_STRING)
            .addTags(TAGK_STRING, TAGV_STRING)
            .build();
    TimeSeriesDatum datum_2 = TimeSeriesDatum.wrap(id, dp);

    MockLowLevelMetricData data = lowLevel(datum_1, datum_2);
    Tsdb1xHBaseDataStore store =
            new Tsdb1xHBaseDataStore(factory, "UT", schema);
    Whitebox.setInternalState(store, "use_dp_timestamp", false);
    store.write(null, data, null);

    byte[] row_key = new byte[] { 0, 0, 1, 75, 61, 59, 0, 0, 0, 1, 0, 0, 1 };
    assertArrayEquals(new byte[] { 42 }, storage.getColumn(
            store.dataTable(), row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            new byte[] { 0, 0 }));

    row_key = new byte[] { 0, 0, 2, 75, 61, 59, 0, 0, 0, 1, 0, 0, 1 };
    assertArrayEquals(new byte[] { 24 }, storage.getColumn(
            store.dataTable(), row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            new byte[] { 0, 0 }));

    // appends
    data = lowLevel(datum_1, datum_2);
    Whitebox.setInternalState(store, "write_appends", true);
    store.write(null, data, null);

    row_key = new byte[] { 0, 0, 1, 75, 61, 59, 0, 0, 0, 1, 0, 0, 1 };
    assertArrayEquals(new byte[] { 0, 0, 42 }, storage.getColumn(
            store.dataTable(), row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            NumericCodec.APPEND_QUALIFIER));

    row_key = new byte[] { 0, 0, 2, 75, 61, 59, 0, 0, 0, 1, 0, 0, 1 };
    assertArrayEquals(new byte[] { 0, 0, 24 }, storage.getColumn(
            store.dataTable(), row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            NumericCodec.APPEND_QUALIFIER));

    data = lowLevel(datum_1, datum_2);
    Whitebox.setInternalState(store, "write_appends", false);
    Whitebox.setInternalState(store, "encode_as_appends", true);
    ((MutableNumericValue) datum_1.value()).resetValue(1);
    ((MutableNumericValue) datum_2.value()).resetValue(2);

    store.write(null, data, null);

    row_key = new byte[] { 0, 0, 1, 75, 61, 59, 0, 0, 0, 1, 0, 0, 1 };
    assertArrayEquals(new byte[] { 0, 0, 1 }, storage.getColumn(
            store.dataTable(), row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            NumericCodec.APPEND_QUALIFIER));

    row_key = new byte[] { 0, 0, 2, 75, 61, 59, 0, 0, 0, 1, 0, 0, 1 };
    assertArrayEquals(new byte[] { 0, 0, 2 }, storage.getColumn(
            store.dataTable(), row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            NumericCodec.APPEND_QUALIFIER));

    // one error
    id = BaseTimeSeriesDatumStringId.newBuilder()
            .setMetric(METRIC_STRING_EX)
            .addTags(TAGK_STRING, TAGV_STRING)
            .build();
    dp = new MutableNumericValue(ts, 8);
    datum_1 = TimeSeriesDatum.wrap(id, dp);
    data = lowLevel(datum_1, datum_2);

    store.write(null, data, null);
    // TODO - validate
  }

  @Test
  public void writeWithDPTimestamp() throws Exception {
    MutableNumericValue value =
            new MutableNumericValue(new SecondTimeStamp(1262304000), 42);
    TimeSeriesDatumStringId id = BaseTimeSeriesDatumStringId.newBuilder()
            .setMetric(METRIC_STRING)
            .addTags(TAGK_STRING, TAGV_STRING)
            .build();

    Tsdb1xHBaseDataStore store =
            new Tsdb1xHBaseDataStore(factory, "UT", schema);
    store.write(null, TimeSeriesDatum.wrap(id, value), null);
    byte[] row_key = new byte[] { 0, 0, 1, 75, 61, 59, 0, 0, 0, 1, 0, 0, 1 };
    assertArrayEquals(new byte[] { 42 }, storage.getColumn(
            store.dataTable(), row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            new byte[] { 0, 0 },
            1262304000_000L));

    // now without the timestamp
    value.resetValue(24);
    Whitebox.setInternalState(store, "use_dp_timestamp", false);
    store.write(null, TimeSeriesDatum.wrap(id, value), null);
    row_key = new byte[] { 0, 0, 1, 75, 61, 59, 0, 0, 0, 1, 0, 0, 1 };
    assertArrayEquals(new byte[] { 24 }, storage.getColumn(
            store.dataTable(), row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            new byte[] { 0, 0 },
            storage.getCurrentTimestamp() - 1));
  }

  @Test
  public void writeDatumRollup() throws Exception {
    storage.flushStorage("tsdb-rollup-1h".getBytes(StandardCharsets.UTF_8));
    when(schema_factory.rollupConfig()).thenReturn(HOURLY_INTERVAL.rollupConfig());

    MutableRollupDatum value = new MutableRollupDatum();
    value.setId(BaseTimeSeriesDatumStringId.newBuilder()
            .setMetric(METRIC_STRING)
            .addTags(TAGK_STRING, TAGV_STRING)
            .build());
    value.resetTimestamp(new SecondTimeStamp(1262304000));
    value.resetValue(0, 42);
    value.resetValue(1, 60);
    value.resetValue(2, 5);
    value.resetValue(3, 0);
    value.setInterval("1h");

    Tsdb1xHBaseDataStore store =
            new Tsdb1xHBaseDataStore(factory, "UT", schema);
    Whitebox.setInternalState(store, "use_dp_timestamp", false);
    store.write(null, value, null);

    byte[] row_key = new byte[] { 0, 0, 1, 75, 61, 59, 0, 0, 0, 1, 0, 0, 1 };
    assertArrayEquals(new byte[] { 42 }, storage.getColumn(
            ROLLUP_TABLE, row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            new byte[] { 0, 0, 0 }));
    assertArrayEquals(new byte[] { 60 }, storage.getColumn(
            ROLLUP_TABLE, row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            new byte[] { 1, 0, 0 }));
    assertArrayEquals(new byte[] { 5 }, storage.getColumn(
            ROLLUP_TABLE, row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            new byte[] { 2, 0, 0 }));
    assertArrayEquals(new byte[] { 0 }, storage.getColumn(
            ROLLUP_TABLE, row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            new byte[] { 3, 0, 0 }));

    // appends
    Whitebox.setInternalState(store, "write_appends", true);
    store.write(null, value, null);
    assertArrayEquals(new byte[] { 0, 0, 42 }, storage.getColumn(
            ROLLUP_TABLE, row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            new byte[] { 0 }));
    assertArrayEquals(new byte[] { 0, 0, 60 }, storage.getColumn(
            ROLLUP_TABLE, row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            new byte[] { 1 }));
    assertArrayEquals(new byte[] { 0, 0, 5 }, storage.getColumn(
            ROLLUP_TABLE, row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            new byte[] { 2 }));
    assertArrayEquals(new byte[] { 0, 0, 0 }, storage.getColumn(
            ROLLUP_TABLE, row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            new byte[] { 3 }));
  }

  @Test
  public void writeSharedDataRollup() throws Exception {
    storage.flushStorage("tsdb-rollup-1h".getBytes(StandardCharsets.UTF_8));
    when(schema_factory.rollupConfig()).thenReturn(HOURLY_INTERVAL.rollupConfig());

    TimeStamp ts = new SecondTimeStamp(1262304000);
    MutableRollupDatum value = new MutableRollupDatum();
    value.setId(BaseTimeSeriesDatumStringId.newBuilder()
            .setMetric(METRIC_STRING)
            .addTags(TAGK_STRING, TAGV_STRING)
            .build());
    value.resetTimestamp(ts);
    value.resetValue(0, 42);
    value.resetValue(1, 60);
    value.setInterval("1h");

    List<TimeSeriesDatum> data = Lists.newArrayList();
    data.add(value);

    value = new MutableRollupDatum();
    value.setId(BaseTimeSeriesDatumStringId.newBuilder()
            .setMetric(METRIC_B_STRING)
            .addTags(TAGK_STRING, TAGV_STRING)
            .build());
    value.resetTimestamp(ts);
    value.resetValue(0, 24);
    value.resetValue(1, 30);
    value.setInterval("1h");
    data.add(value);

    TimeSeriesSharedTagsAndTimeData shared =
            TimeSeriesSharedTagsAndTimeData.fromCollection(data);
    Tsdb1xHBaseDataStore store =
            new Tsdb1xHBaseDataStore(factory, "UT", schema);
    Whitebox.setInternalState(store, "use_dp_timestamp", false);
    store.write(null, shared, null);

    byte[] row_key = new byte[] { 0, 0, 1, 75, 61, 59, 0, 0, 0, 1, 0, 0, 1 };
    assertArrayEquals(new byte[] { 42 }, storage.getColumn(
            ROLLUP_TABLE, row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            new byte[] { 0, 0, 0 }));
    assertArrayEquals(new byte[] { 60 }, storage.getColumn(
            ROLLUP_TABLE, row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            new byte[] { 1, 0, 0 }));

    row_key = new byte[] { 0, 0, 2, 75, 61, 59, 0, 0, 0, 1, 0, 0, 1 };
    assertArrayEquals(new byte[] { 24 }, storage.getColumn(
            ROLLUP_TABLE, row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            new byte[] { 0, 0, 0 }));
    assertArrayEquals(new byte[] { 30 }, storage.getColumn(
            ROLLUP_TABLE, row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            new byte[] { 1, 0, 0 }));

    // appends
    Whitebox.setInternalState(store, "write_appends", true);
    store.write(null, shared, null);

    row_key = new byte[] { 0, 0, 1, 75, 61, 59, 0, 0, 0, 1, 0, 0, 1 };
    assertArrayEquals(new byte[] { 0, 0, 42 }, storage.getColumn(
            ROLLUP_TABLE, row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            new byte[] { 0 }));
    assertArrayEquals(new byte[] { 0, 0, 60 }, storage.getColumn(
            ROLLUP_TABLE, row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            new byte[] { 1 }));

    row_key = new byte[] { 0, 0, 2, 75, 61, 59, 0, 0, 0, 1, 0, 0, 1 };
    assertArrayEquals(new byte[] { 0, 0, 24 }, storage.getColumn(
            ROLLUP_TABLE, row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            new byte[] { 0 }));
    assertArrayEquals(new byte[] { 0, 0, 30 }, storage.getColumn(
            ROLLUP_TABLE, row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            new byte[] { 1 }));
  }

  @Test
  public void writeLowLevelRollup() throws Exception {
    storage.flushStorage("tsdb-rollup-1h".getBytes(StandardCharsets.UTF_8));
    when(schema_factory.rollupConfig()).thenReturn(HOURLY_INTERVAL.rollupConfig());

    TimeStamp ts = new SecondTimeStamp(1262304000);
    MutableRollupDatum datum_1 = new MutableRollupDatum();
    datum_1.setId(BaseTimeSeriesDatumStringId.newBuilder()
            .setMetric(METRIC_STRING)
            .addTags(TAGK_STRING, TAGV_STRING)
            .build());
    datum_1.resetTimestamp(ts);
    datum_1.resetValue(0, 42);
    datum_1.resetValue(1, 60);
    datum_1.setInterval("1h");

    MutableRollupDatum datum_2 = new MutableRollupDatum();
    datum_2.setId(BaseTimeSeriesDatumStringId.newBuilder()
            .setMetric(METRIC_B_STRING)
            .addTags(TAGK_STRING, TAGV_STRING)
            .build());
    datum_2.resetTimestamp(ts);
    datum_2.resetValue(0, 24);
    datum_2.resetValue(1, 30);
    datum_2.setInterval("1h");

    MockLowLevelRollupMetricData data = lowLevelRollup(datum_1, datum_2);
    Tsdb1xHBaseDataStore store =
            new Tsdb1xHBaseDataStore(factory, "UT", schema);
    Whitebox.setInternalState(store, "use_dp_timestamp", false);
    store.write(null, data, null);

    byte[] row_key = new byte[] { 0, 0, 1, 75, 61, 59, 0, 0, 0, 1, 0, 0, 1 };
    assertArrayEquals(new byte[] { 42 }, storage.getColumn(
            ROLLUP_TABLE, row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            new byte[] { 0, 0, 0 }));
    assertArrayEquals(new byte[] { 60 }, storage.getColumn(
            ROLLUP_TABLE, row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            new byte[] { 1, 0, 0 }));

    row_key = new byte[] { 0, 0, 2, 75, 61, 59, 0, 0, 0, 1, 0, 0, 1 };
    assertArrayEquals(new byte[] { 24 }, storage.getColumn(
            ROLLUP_TABLE, row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            new byte[] { 0, 0, 0 }));
    assertArrayEquals(new byte[] { 30 }, storage.getColumn(
            ROLLUP_TABLE, row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            new byte[] { 1, 0, 0 }));

    // appends
    data = lowLevelRollup(datum_1, datum_2);
    Whitebox.setInternalState(store, "write_appends", true);
    store.write(null, data, null);

    row_key = new byte[] { 0, 0, 1, 75, 61, 59, 0, 0, 0, 1, 0, 0, 1 };
    assertArrayEquals(new byte[] { 0, 0, 42 }, storage.getColumn(
            ROLLUP_TABLE, row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            new byte[] { 0 }));
    assertArrayEquals(new byte[] { 0, 0, 60 }, storage.getColumn(
            ROLLUP_TABLE, row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            new byte[] { 1 }));

    row_key = new byte[] { 0, 0, 2, 75, 61, 59, 0, 0, 0, 1, 0, 0, 1 };
    assertArrayEquals(new byte[] { 0, 0, 24 }, storage.getColumn(
            ROLLUP_TABLE, row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            new byte[] { 0 }));
    assertArrayEquals(new byte[] { 0, 0, 30 }, storage.getColumn(
            ROLLUP_TABLE, row_key, Tsdb1xHBaseDataStore.DATA_FAMILY,
            new byte[] { 1 }));
  }

  MockLowLevelMetricData lowLevel(TimeSeriesDatum... data) {
    MockLowLevelMetricData low_level = new MockLowLevelMetricData();
    for (int i = 0; i < data.length; i++) {
      low_level.add(data[i]);
    }
    return low_level;
  }

  MockLowLevelRollupMetricData lowLevelRollup(TimeSeriesDatum... data) {
    MockLowLevelRollupMetricData low_level = new MockLowLevelRollupMetricData();
    for (int i = 0; i < data.length; i++) {
      low_level.add(data[i]);
    }
    return low_level;
  }
}
