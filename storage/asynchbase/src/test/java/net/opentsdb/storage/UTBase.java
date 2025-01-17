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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.hbase.async.Bytes;
import org.hbase.async.HBaseClient;
import org.junit.BeforeClass;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.google.common.collect.Lists;

import net.opentsdb.common.Const;
import net.opentsdb.core.MockTSDB;
import net.opentsdb.core.TSDB;
import net.opentsdb.rollup.DefaultRollupConfig;
import net.opentsdb.rollup.DefaultRollupInterval;
import net.opentsdb.rollup.RollupUtils;
import net.opentsdb.stats.MockTrace;
import net.opentsdb.storage.schemas.tsdb1x.NumericCodec;
import net.opentsdb.storage.schemas.tsdb1x.Schema;
import net.opentsdb.storage.schemas.tsdb1x.SchemaBase;
import net.opentsdb.storage.schemas.tsdb1x.SchemaFactory;
import net.opentsdb.storage.schemas.tsdb1x.Tsdb1xDataStoreFactory;
import net.opentsdb.storage.schemas.tsdb1x.NumericCodec.OffsetResolution;
import net.opentsdb.uid.LRUUniqueId;
import net.opentsdb.uid.UniqueId;
import net.opentsdb.uid.UniqueIdFactory;
import net.opentsdb.uid.UniqueIdStore;
import net.opentsdb.uid.UniqueIdType;
import net.opentsdb.utils.UnitTestException;

/**
 * Base class that mocks out the various components and populates the
 * MockBase with some data.
 */
public class UTBase {
  public static final String METRIC_STRING = "sys.cpu.user";
  public static final byte[] METRIC_BYTES = new byte[] { 0, 0, 1 };
  public static final String METRIC_B_STRING = "sys.cpu.system";
  public static final byte[] METRIC_B_BYTES = new byte[] { 0, 0, 2 };
  public static final String NSUN_METRIC = "sys.cpu.nice";
  public static final byte[] NSUI_METRIC = new byte[] { 0, 0, 3 };
  public static final String METRIC_STRING_EX = "sys.cpu.idle";
  public static final byte[] METRIC_BYTES_EX = new byte[] { 0, 0, 7 };
  
  public static final String TAGK_STRING = "host";
  public static final byte[] TAGK_BYTES = new byte[] { 0, 0, 1 };
  public static final String TAGK_B_STRING = "owner";
  public static final byte[] TAGK_B_BYTES = new byte[] { 0, 0, 3 };
  public static final String NSUN_TAGK = "dc";
  public static final byte[] NSUI_TAGK = new byte[] { 0, 0, 4 };
  public static final String TAGK_STRING_EX = "colo";
  public static final byte[] TAGK_BYTES_EX = new byte[] { 0, 0, 8 };
  
  public static final String TAGV_STRING = "web01";
  public static final byte[] TAGV_BYTES = new byte[] { 0, 0, 1 };
  public static final String TAGV_B_STRING = "web02";
  public static final byte[] TAGV_B_BYTES = new byte[] { 0, 0, 2 };
  public static final String NSUN_TAGV = "web03";
  public static final byte[] NSUI_TAGV = new byte[] { 0, 0, 3 };
  public static final String TAGV_STRING_EX = "web04";
  public static final byte[] TAGV_BYTES_EX = new byte[] { 0, 0, 9 };
  
  public static final int TS_SINGLE_SERIES = 1517443200;
  public static final int TS_SINGLE_SERIES_COUNT = 16;
  public static final int TS_SINGLE_SERIES_INTERVAL = 3600;
  
  public static final int TS_SINGLE_SERIES_GAP = 1530403200;
  public static final int TS_SINGLE_SERIES_GAP_COUNT = 16;
  public static final int TS_SINGLE_SERIES_GAP_SIZE = 5;
  public static final int TS_SINGLE_SERIES_GAP_INTERVAL = 3600;
  
  public static final int TS_DOUBLE_SERIES = 1522540800;
  public static final int TS_DOUBLE_SERIES_COUNT = 16;
  public static final int TS_DOUBLE_SERIES_INTERVAL = 3600;
  
  public static final int TS_MULTI_SERIES_EX = 1525132800;
  public static final int TS_MULTI_SERIES_EX_COUNT = 16;
  public static final int TS_MULTI_SERIES_EX_INDEX = 7;
  public static final int TS_MULTI_SERIES_INTERVAL = 3600;
  
  public static final int TS_MULTI_COLUMN_SERIES = 1530662400;
  public static final int TS_MULTI_COLUMN_SERIES_COUNT = 16;
  public static final int TS_MULTI_COLUMN_SERIES_INTERVAL = 3600;
  
  public static final int TS_NSUI_SERIES = 1527811200;
  public static final int TS_NSUI_SERIES_COUNT = 16;
  public static final int TS_NSUI_SERIES_INTERVAL = 3600;
  
  public static final int TS_APPEND_SERIES = 1531008000;
  public static final int TS_APPEND_SERIES_COUNT = 16;
  public static final int TS_APPEND_SERIES_INTERVAL = 3600;
  
  public static final int TS_ROLLUP_SERIES = 1533081600;
  public static final int TS_ROLLUP_SERIES_COUNT = 4;
  public static final int TS_ROLLUP_SERIES_INTERVAL = 86400;
  
  public static final int TS_ROLLUP_APPEND_SERIES = 1535760000;
  public static final int TS_ROLLUP_APPEND_SERIES_COUNT = 4;
  public static final int TS_ROLLUP_APPEND_SERIES_INTERVAL = 86400;
  
  public static final byte[] DATA_TABLE = "tsdb".getBytes(Const.ISO_8859_CHARSET);
  public static final byte[] UID_TABLE = "tsdb-uid".getBytes(Const.ISO_8859_CHARSET);
  public static final byte[] ROLLUP_TABLE = "tsdb-rollup-1h".getBytes(Const.ISO_8859_CHARSET);
  
  // GMT: Monday, January 1, 2018 12:15:00 AM
  public static final int START_TS = 1514765700;
  
  // GMT: Monday, January 1, 2018 1:15:00 AM
  public static final int END_TS = 1514769300;
  
  /** The types of series to use as a helper. */
  public static enum Series {
    /** Two metrics but one series each. */
    SINGLE_SERIES,
    
    /** Two metrics but one series each with a time gap in the middle. */
    SINGLE_SERIES_GAP,
    
    /** Two metrics and two series each. */
    DOUBLE_SERIES,
    
    /** Two metrics, two series, and an exception is returned at a point. */
    MULTI_SERIES_EX,
    
    /** Two metrics, four series with one of each metric incorporating a 
     * non-assigned tag value ID. */
    NSUI_SERIES,

    /** Two metrics with multiple separate columns each. */
    MULTI_COLUMN_SERIES,
    
    /** One metric with Append columns. */
    APPEND_SERIES,
    
    /** Two metric with rollup data, 4 values per day, both sum and count. */
    ROLLUP_SERIES,
    
    /** Two metric with rollup data, 4 values per day, both sum and count. */
    ROLLUP_APPEND_SERIES,
  }
  
  protected static MockTSDB tsdb;
  protected static Tsdb1xDataStoreFactory store_factory;
  protected static HBaseClient client;
  protected static MockBase storage;
  protected static Tsdb1xHBaseDataStore data_store;
  protected static UniqueIdFactory uid_factory;
  protected static UniqueIdStore uid_store;
  protected static DefaultRollupInterval HOURLY_INTERVAL;
  
  protected static Schema schema;
  protected static SchemaFactory schema_factory;
  
  protected static MockTrace trace;
  
  @BeforeClass
  public static void beforeClass() throws Exception {
    tsdb = new MockTSDB();
    store_factory = mock(Tsdb1xDataStoreFactory.class);
    client = mock(HBaseClient.class);
    uid_factory = mock(UniqueIdFactory.class);
    data_store = mock(Tsdb1xHBaseDataStore.class);
    
    when(tsdb.registry.getPlugin(eq(Tsdb1xDataStoreFactory.class), anyString()))
      .thenReturn(store_factory);
    when(store_factory.newInstance(any(TSDB.class), any(), any(Schema.class)))
      .thenReturn(data_store);    
    when(data_store.tsdb()).thenReturn(tsdb);
    when(data_store.getConfigKey(anyString()))
      .thenAnswer(new Answer<String>() {
      @Override
      public String answer(InvocationOnMock invocation) throws Throwable {
        return "tsd.mock." + (String) invocation.getArguments()[0];
      }
    });
    when(data_store.dataTable()).thenReturn("tsdb".getBytes(Const.ISO_8859_CHARSET));
    when(data_store.uidTable()).thenReturn(UID_TABLE);
    when(data_store.client()).thenReturn(client);
    when(tsdb.registry.getSharedObject(any())).thenReturn(data_store);
   
    when(tsdb.registry.getPlugin(UniqueIdFactory.class, "LRU"))
      .thenReturn(uid_factory);
    uid_store = new Tsdb1xUniqueIdStore(data_store, null);
    when(tsdb.registry.getSharedObject("default_uidstore"))
      .thenReturn(uid_store);
    when(uid_factory.newInstance(eq(tsdb), anyString(), 
        any(UniqueIdType.class), eq(uid_store))).thenAnswer(new Answer<UniqueId>() {
          @Override
          public UniqueId answer(InvocationOnMock invocation)
              throws Throwable {
            // TODO Auto-generated method stub
            return new LRUUniqueId(tsdb, null, (UniqueIdType) invocation.getArguments()[2], uid_store);
          }
        });
    
    schema_factory = mock(SchemaFactory.class);
    schema = spy(new Schema(schema_factory, tsdb, null));
    when(data_store.schema()).thenReturn(schema);
    
    HOURLY_INTERVAL = DefaultRollupInterval.builder()
        .setInterval("1h")
        .setRowSpan("1d")
        .setTable(new String(ROLLUP_TABLE))
        .setPreAggregationTable(new String(ROLLUP_TABLE))
        .build();
    HOURLY_INTERVAL.setRollupConfig(DefaultRollupConfig.newBuilder()
        .addAggregationId("sum", 0)
        .addAggregationId("count", 1)
        .addInterval(HOURLY_INTERVAL)
            .addInterval(DefaultRollupInterval.builder()
                    .setInterval("1m")
                    .setRowSpan("1h")
                    .setTable(new String(DATA_TABLE))
                    .setPreAggregationTable(new String(DATA_TABLE))
                    .setDefaultInterval(true)
                    .build())
        .build());
    
    storage = new MockBase(client, true, true, true, true);
    loadUIDTable();
    loadRawData();
    loadRollupData();
  }
  
  /**
   * Populates the UID table with the mappings and some exceptions.
   */
  public static void loadUIDTable() {
    bothUIDs(UniqueIdType.METRIC, METRIC_STRING, METRIC_BYTES);
    bothUIDs(UniqueIdType.METRIC, METRIC_B_STRING, METRIC_B_BYTES);
    storage.throwException(METRIC_STRING_EX.getBytes(Const.ISO_8859_CHARSET), 
        new UnitTestException(), true);
    storage.throwException(METRIC_BYTES_EX, new UnitTestException(), true);
    
    bothUIDs(UniqueIdType.TAGK, TAGK_STRING, TAGK_BYTES);
    bothUIDs(UniqueIdType.TAGK, TAGK_B_STRING, TAGK_B_BYTES);
    storage.throwException(TAGK_STRING_EX.getBytes(Const.ISO_8859_CHARSET), 
        new UnitTestException(), true);
    storage.throwException(TAGK_BYTES_EX, new UnitTestException(), true);
    
    bothUIDs(UniqueIdType.TAGV, TAGV_STRING, TAGV_BYTES);
    bothUIDs(UniqueIdType.TAGV, TAGV_B_STRING, TAGV_B_BYTES);
    storage.throwException(TAGV_STRING_EX.getBytes(Const.ISO_8859_CHARSET), 
        new UnitTestException(), true);
    storage.throwException(TAGV_BYTES_EX, new UnitTestException(), true);
    
    for (final Map.Entry<String, byte[]> uid : SchemaBase.UIDS.entrySet()) {
      bothUIDs(UniqueIdType.METRIC, uid.getKey(), uid.getValue());
      bothUIDs(UniqueIdType.TAGK, uid.getKey(), uid.getValue());
      bothUIDs(UniqueIdType.TAGV, uid.getKey(), uid.getValue());
    }
  }
  
  /**
   * Mocks out both UIDs, writing them to storage.
   * @param type The type.
   * @param name The name.
   * @param id The id.
   */
  static void bothUIDs(final UniqueIdType type, 
                       final String name, 
                       final byte[] id) {
    byte[] qualifier = null;
    switch (type) {
    case METRIC:
      qualifier = Tsdb1xUniqueIdStore.METRICS_QUAL;
      break;
    case TAGK:
      qualifier = Tsdb1xUniqueIdStore.TAG_NAME_QUAL;
      break;
    case TAGV:
      qualifier = Tsdb1xUniqueIdStore.TAG_VALUE_QUAL;
      break;
    default:
      throw new IllegalArgumentException("Hmm, " + type 
          + " isn't supported here.");
    }
    storage.addColumn(UID_TABLE, 
        name.getBytes(Const.ISO_8859_CHARSET), 
        Tsdb1xUniqueIdStore.ID_FAMILY,
        qualifier, 
        id);
    storage.addColumn(UID_TABLE, 
        id, 
        Tsdb1xUniqueIdStore.NAME_FAMILY,
        qualifier, 
        name.getBytes(Const.ISO_8859_CHARSET));
  }
  
  /**
   * Utility to generate a row key for scanners or storage.
   * @param metric A non-null metric UID.
   * @param timestamp A timestamp.
   * @param tags An optional list of key/value pairs.
   * @return The row key.
   */
  public static byte[] makeRowKey(byte[] metric, int timestamp, byte[]... tags) {
    int size = metric.length + 4;
    if (tags != null) {
      for (byte[] tag : tags) {
        size += tag.length;
      }
    }
    byte[] key = new byte[size];
    System.arraycopy(metric, 0, key, 0, metric.length);
    System.arraycopy(Bytes.fromInt(timestamp), 0, key, metric.length, 4);
    
    int offset = metric.length + 4;
    if (tags != null) {
      for (byte[] tag : tags) {
        System.arraycopy(tag, 0, key, offset, tag.length);
        offset += tag.length;
      }
    }
    return key;
  }
  
  /**
   * Populates MockBase with some data.
   * @throws Exception
   */
  public static void loadRawData() throws Exception {
    final byte[] table = "tsdb".getBytes(Const.ISO_8859_CHARSET);
    for (int i = 0; i < TS_SINGLE_SERIES_COUNT; i++) {
      storage.addColumn(table, makeRowKey(
          METRIC_BYTES, 
          TS_SINGLE_SERIES + (i * TS_SINGLE_SERIES_INTERVAL), 
          TAGK_BYTES,
          TAGV_BYTES), 
        Tsdb1xHBaseDataStore.DATA_FAMILY, 
        new byte[2], 
        new byte[] { 1 });
      
      storage.addColumn(table, makeRowKey(
          METRIC_B_BYTES, 
          TS_SINGLE_SERIES + (i * TS_SINGLE_SERIES_INTERVAL), 
          TAGK_BYTES,
          TAGV_BYTES), 
        Tsdb1xHBaseDataStore.DATA_FAMILY, 
        new byte[2], 
        new byte[] { 1 });
    }
    
    for (int i = 0; i < TS_SINGLE_SERIES_GAP_COUNT; i++) {
      storage.addColumn(table, makeRowKey(
          METRIC_BYTES, 
          TS_SINGLE_SERIES_GAP + (i * TS_SINGLE_SERIES_GAP_INTERVAL), 
          TAGK_BYTES,
          TAGV_BYTES), 
        Tsdb1xHBaseDataStore.DATA_FAMILY, 
        new byte[2], 
        new byte[] { 1 });
      
      if (i == TS_SINGLE_SERIES_GAP_SIZE) {
        i += TS_SINGLE_SERIES_GAP_SIZE;
      }
      
      storage.addColumn(table, makeRowKey(
          METRIC_B_BYTES, 
          TS_SINGLE_SERIES_GAP + (i * TS_SINGLE_SERIES_GAP_INTERVAL), 
          TAGK_BYTES,
          TAGV_BYTES), 
        Tsdb1xHBaseDataStore.DATA_FAMILY, 
        new byte[2], 
        new byte[] { 1 });
    }
    
    for (int i = 0; i < TS_DOUBLE_SERIES_COUNT; i++) {
      storage.addColumn(table, makeRowKey(
          METRIC_BYTES, 
          TS_DOUBLE_SERIES + (i * TS_DOUBLE_SERIES_INTERVAL), 
          TAGK_BYTES,
          TAGV_BYTES), 
        Tsdb1xHBaseDataStore.DATA_FAMILY, 
        new byte[2], 
        new byte[] { 1 });
      
      storage.addColumn(table, makeRowKey(
          METRIC_BYTES, 
          TS_DOUBLE_SERIES + (i * TS_DOUBLE_SERIES_INTERVAL), 
          TAGK_BYTES,
          TAGV_B_BYTES), 
        Tsdb1xHBaseDataStore.DATA_FAMILY, 
        new byte[2], 
        new byte[] { 1 });
      
      storage.addColumn(table, makeRowKey(
          METRIC_B_BYTES, 
          TS_DOUBLE_SERIES + (i * TS_DOUBLE_SERIES_INTERVAL), 
          TAGK_BYTES,
          TAGV_BYTES), 
        Tsdb1xHBaseDataStore.DATA_FAMILY, 
        new byte[2], 
        new byte[] { 1 });
      
      storage.addColumn(table, makeRowKey(
          METRIC_B_BYTES, 
          TS_DOUBLE_SERIES + (i * TS_DOUBLE_SERIES_INTERVAL), 
          TAGK_BYTES,
          TAGV_B_BYTES), 
        Tsdb1xHBaseDataStore.DATA_FAMILY, 
        new byte[2], 
        new byte[] { 1 });
    }
    
    for (int i = 0; i < TS_MULTI_SERIES_EX_COUNT; i++) {
      storage.addColumn(table, makeRowKey(
          METRIC_BYTES, 
          TS_MULTI_SERIES_EX + (i * TS_MULTI_SERIES_INTERVAL), 
          TAGK_BYTES,
          TAGV_BYTES), 
        Tsdb1xHBaseDataStore.DATA_FAMILY, 
        new byte[2], 
        new byte[] { 1 });
      
      storage.addColumn(table, makeRowKey(
          METRIC_BYTES, 
          TS_MULTI_SERIES_EX + (i * TS_MULTI_SERIES_INTERVAL), 
          TAGK_BYTES,
          TAGV_B_BYTES), 
        Tsdb1xHBaseDataStore.DATA_FAMILY, 
        new byte[2], 
        new byte[] { 1 });
      
      storage.addColumn(table, makeRowKey(
          METRIC_B_BYTES, 
          TS_MULTI_SERIES_EX + (i * TS_MULTI_SERIES_INTERVAL), 
          TAGK_BYTES,
          TAGV_BYTES), 
        Tsdb1xHBaseDataStore.DATA_FAMILY, 
        new byte[2], 
        new byte[] { 1 });
      
      storage.addColumn(table, makeRowKey(
          METRIC_B_BYTES, 
          TS_MULTI_SERIES_EX + (i * TS_MULTI_SERIES_INTERVAL), 
          TAGK_BYTES,
          TAGV_B_BYTES), 
        Tsdb1xHBaseDataStore.DATA_FAMILY, 
        new byte[2], 
        new byte[] { 1 });
      
      if (i == TS_MULTI_SERIES_EX_INDEX) {
        storage.throwException(makeRowKey(
            METRIC_BYTES, 
            TS_MULTI_SERIES_EX + (i * TS_MULTI_SERIES_INTERVAL), 
            TAGK_BYTES,
            TAGV_BYTES),
            new UnitTestException(), true);
        
        storage.throwException(makeRowKey(
            METRIC_B_BYTES, 
            TS_MULTI_SERIES_EX + (i * TS_MULTI_SERIES_INTERVAL), 
            TAGK_BYTES,
            TAGV_BYTES),
            new UnitTestException(), true);
      }
    }
    
    for (int i = 0; i < TS_MULTI_COLUMN_SERIES_COUNT; i++) {
      for (int x = 0; x < 5; x++) {
        short offset = (short) (x * 60);
        offset <<= NumericCodec.FLAG_BITS;
        storage.addColumn(table, makeRowKey(
            METRIC_BYTES, 
            TS_MULTI_COLUMN_SERIES + (i * TS_MULTI_COLUMN_SERIES_INTERVAL), 
            TAGK_BYTES,
            TAGV_BYTES), 
          Tsdb1xHBaseDataStore.DATA_FAMILY, 
          Bytes.fromShort(offset),
          new byte[] { (byte) x });
        
        storage.addColumn(table, makeRowKey(
            METRIC_B_BYTES, 
            TS_MULTI_COLUMN_SERIES + (i * TS_MULTI_COLUMN_SERIES_INTERVAL), 
            TAGK_BYTES,
            TAGV_BYTES), 
          Tsdb1xHBaseDataStore.DATA_FAMILY, 
          Bytes.fromShort(offset),
          new byte[] { (byte) x });
      }
    }
    
    for (int i = 0; i < TS_NSUI_SERIES_COUNT; i++) {
      storage.addColumn(table, makeRowKey(
          METRIC_BYTES, 
          TS_NSUI_SERIES + (i * TS_NSUI_SERIES_INTERVAL), 
          TAGK_BYTES,
          TAGV_BYTES), 
        Tsdb1xHBaseDataStore.DATA_FAMILY, 
        new byte[2], 
        new byte[] { 1 });
      
      // offset a bit
      if (i > 0) {
        storage.addColumn(table, makeRowKey(
            METRIC_BYTES, 
            TS_NSUI_SERIES + (i * TS_NSUI_SERIES_INTERVAL), 
            TAGK_BYTES,
            NSUI_TAGV), 
          Tsdb1xHBaseDataStore.DATA_FAMILY, 
          new byte[2], 
          new byte[] { 1 });
      }
      
      storage.addColumn(table, makeRowKey(
          METRIC_B_BYTES, 
          TS_NSUI_SERIES + (i * TS_NSUI_SERIES_INTERVAL), 
          TAGK_BYTES,
          TAGV_BYTES), 
        Tsdb1xHBaseDataStore.DATA_FAMILY, 
        new byte[2], 
        new byte[] { 1 });
      
      if (i > 0) {
        storage.addColumn(table, makeRowKey(
            METRIC_B_BYTES, 
            TS_NSUI_SERIES + (i * TS_NSUI_SERIES_INTERVAL), 
            TAGK_BYTES,
            NSUI_TAGV), 
          Tsdb1xHBaseDataStore.DATA_FAMILY, 
          new byte[2], 
          new byte[] { 1 });
      }
    }
    
    for (int i = 0; i < TS_APPEND_SERIES_COUNT; i++) {
      storage.addColumn(table, makeRowKey(
          METRIC_BYTES, 
          TS_APPEND_SERIES + (i * TS_APPEND_SERIES_INTERVAL), 
          TAGK_BYTES,
          TAGV_BYTES), 
        Tsdb1xHBaseDataStore.DATA_FAMILY, 
        new byte[] { Schema.APPENDS_PREFIX, 0, 0 }, 
        NumericCodec.encodeAppendValue(OffsetResolution.SECONDS, 60, 42));
    }
  }
  
  public static void loadRollupData() throws Exception {
    storage.addTable(ROLLUP_TABLE, Lists.newArrayList(Tsdb1xHBaseDataStore.DATA_FAMILY));
    for (int i = 0; i < TS_ROLLUP_SERIES_COUNT; i++) {
      // every 6 hours
      for (int x = 0; x < 4; x++) {
        // sum and count
        int base_ts = TS_ROLLUP_SERIES + (i * TS_ROLLUP_SERIES_INTERVAL);
        storage.addColumn(ROLLUP_TABLE, makeRowKey(
            METRIC_BYTES, 
            base_ts, 
            TAGK_BYTES,
            TAGV_BYTES), 
          Tsdb1xHBaseDataStore.DATA_FAMILY, 
          RollupUtils.buildRollupQualifier((base_ts + (x * 21600)), (short) 0, 0, HOURLY_INTERVAL),
          new byte[] { (byte) x });
        
        storage.addColumn(ROLLUP_TABLE, makeRowKey(
            METRIC_BYTES, 
            base_ts, 
            TAGK_BYTES,
            TAGV_BYTES), 
          Tsdb1xHBaseDataStore.DATA_FAMILY, 
          RollupUtils.buildStringRollupQualifier("count", (base_ts + (x * 21600)), (short) 0, HOURLY_INTERVAL),
          new byte[] { 1 });
        
        storage.addColumn(ROLLUP_TABLE, makeRowKey(
            METRIC_B_BYTES, 
            base_ts, 
            TAGK_BYTES,
            TAGV_BYTES), 
          Tsdb1xHBaseDataStore.DATA_FAMILY, 
          RollupUtils.buildRollupQualifier((base_ts + (x * 21600)), (short) 0, 0, HOURLY_INTERVAL),
          new byte[] { (byte) x });
        
        storage.addColumn(ROLLUP_TABLE, makeRowKey(
            METRIC_B_BYTES, 
            base_ts, 
            TAGK_BYTES,
            TAGV_BYTES), 
          Tsdb1xHBaseDataStore.DATA_FAMILY, 
          RollupUtils.buildStringRollupQualifier("count", (base_ts + (x * 21600)), (short) 0, HOURLY_INTERVAL),
          new byte[] { 1 });
      }
    }
    
    for (int i = 0; i < TS_ROLLUP_APPEND_SERIES_COUNT; i++) {
      int base_ts = TS_ROLLUP_APPEND_SERIES + (i * TS_ROLLUP_APPEND_SERIES_INTERVAL);
      // every 6 hours
      byte[] sums = new byte[0];
      byte[] counts = new byte[0];
      
      for (int x = 0; x < 4; x++) {
        sums = com.google.common.primitives.Bytes.concat(
            sums, RollupUtils.buildAppendRollupValue((base_ts + (x * 21600)), (short) 0, HOURLY_INTERVAL, 
            new byte[] { (byte) x }));
        counts = com.google.common.primitives.Bytes.concat(
            counts, RollupUtils.buildAppendRollupValue((base_ts + (x * 21600)), (short) 0, HOURLY_INTERVAL, 
            new byte[] { 1 }));
      }
        // sum and count
      for (int agg = 0; agg < 2; agg++) {
        storage.addColumn(ROLLUP_TABLE, makeRowKey(
            METRIC_BYTES, 
            base_ts, 
            TAGK_BYTES,
            TAGV_BYTES), 
          Tsdb1xHBaseDataStore.DATA_FAMILY, 
          new byte[1],
          sums);
        
        storage.addColumn(ROLLUP_TABLE, makeRowKey(
            METRIC_BYTES, 
            base_ts, 
            TAGK_BYTES,
            TAGV_BYTES), 
          Tsdb1xHBaseDataStore.DATA_FAMILY, 
          new byte[] { 1 },
          counts);
        
        storage.addColumn(ROLLUP_TABLE, makeRowKey(
            METRIC_B_BYTES, 
            base_ts, 
            TAGK_BYTES,
            TAGV_BYTES), 
          Tsdb1xHBaseDataStore.DATA_FAMILY, 
          new byte[1],
          sums);
        
        storage.addColumn(ROLLUP_TABLE, makeRowKey(
            METRIC_B_BYTES, 
            base_ts, 
            TAGK_BYTES,
            TAGV_BYTES), 
          Tsdb1xHBaseDataStore.DATA_FAMILY, 
          new byte[] { 1 },
          counts);
      }
    }
  }
  
  static void verifySpan(final String name) {
    verifySpan(name, 1);
  }
  
  static void verifySpan(final String name, final int spans) {
    assertEquals(spans, trace.spans.size());
    assertEquals(name, trace.spans.get(spans - 1).id);
    assertEquals("OK", trace.spans.get(spans - 1).tags.get("status"));
  }
  
  static void verifySpan(final String name, final Class<?> ex) {
    verifySpan(name, ex, 1);
  }
  
  static void verifySpan(final String name, final Class<?> ex, final int size) {
    assertEquals(size, trace.spans.size());
    assertEquals(name, trace.spans.get(size - 1).id);
    assertEquals("Error", trace.spans.get(size - 1).tags.get("status"));
    assertTrue(ex.isInstance(trace.spans.get(size - 1).exceptions.get("Exception")));
  }

}
