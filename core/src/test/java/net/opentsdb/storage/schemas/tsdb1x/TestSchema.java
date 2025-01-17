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
package net.opentsdb.storage.schemas.tsdb1x;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Maps;
import net.opentsdb.data.types.numeric.NumericSummaryType;
import net.opentsdb.data.types.numeric.NumericType;
import org.junit.Test;
import org.powermock.reflect.Whitebox;

import com.google.common.collect.Lists;
import com.stumbleupon.async.Deferred;

import net.opentsdb.auth.AuthState;
import net.opentsdb.common.Const;
import net.opentsdb.configuration.ConfigurationException;
import net.opentsdb.core.MockTSDB;
import net.opentsdb.core.TSDB;
import net.opentsdb.data.BaseTimeSeriesByteId;
import net.opentsdb.data.BaseTimeSeriesDatumStringId;
import net.opentsdb.data.MillisecondTimeStamp;
import net.opentsdb.data.PartialTimeSeriesSet;
import net.opentsdb.data.SecondTimeStamp;
import net.opentsdb.data.TimeSeriesByteId;
import net.opentsdb.data.TimeSeriesDatum;
import net.opentsdb.data.TimeSeriesDatumId;
import net.opentsdb.data.TimeSeriesSharedTagsAndTimeData;
import net.opentsdb.data.TimeSeriesDatumStringId;
import net.opentsdb.data.TimeSeriesStringId;
import net.opentsdb.data.TimeStamp;
import net.opentsdb.data.ZonedNanoTimeStamp;
import net.opentsdb.data.types.annotation.AnnotationType;
import net.opentsdb.data.types.numeric.MutableNumericValue;
import net.opentsdb.data.types.numeric.NumericByteArraySummaryType;
import net.opentsdb.data.types.numeric.NumericLongArrayType;
import net.opentsdb.pools.ByteArrayPool;
import net.opentsdb.pools.DefaultObjectPoolConfig;
import net.opentsdb.pools.DummyObjectPool;
import net.opentsdb.pools.LongArrayPool;
import net.opentsdb.pools.ObjectPool;
import net.opentsdb.rollup.DefaultRollupInterval;
import net.opentsdb.storage.StorageException;
import net.opentsdb.storage.WriteStatus;
import net.opentsdb.storage.WriteStatus.WriteState;
import net.opentsdb.uid.IdOrError;
import net.opentsdb.uid.UniqueId;
import net.opentsdb.uid.UniqueIdFactory;
import net.opentsdb.uid.UniqueIdStore;
import net.opentsdb.uid.UniqueIdType;
import net.opentsdb.utils.Bytes;
import net.opentsdb.utils.UnitTestException;

public class TestSchema extends SchemaBase {
  
  public static final String TESTID = "UT";
  
  private SchemaFactory factory = mock(SchemaFactory.class);
  
  @Test
  public void ctorDefault() throws Exception {
    Schema schema = schema();
    assertEquals(3, schema.metricWidth());
    assertEquals(3, schema.tagkWidth());
    assertEquals(3, schema.tagvWidth());
    assertEquals(20, schema.saltBuckets());
    assertEquals(0, schema.saltWidth());
    assertSame(store, schema.dataStore());
    assertSame(uid_store, schema.uidStore());
    assertSame(metrics, schema.metrics());
    assertSame(tag_names, schema.tagNames());
    assertSame(tag_values, schema.tagValues());
  }
  
  @Test
  public void ctorOverrides() throws Exception {
    MockTSDB tsdb = new MockTSDB();
    when(tsdb.registry.getPlugin(eq(Tsdb1xDataStoreFactory.class), anyString()))
      .thenReturn(store_factory);
    when(store_factory.newInstance(any(TSDB.class), anyString(), any(Schema.class)))
      .thenReturn(store);    
    when(tsdb.registry.getSharedObject("default_uidstore"))
      .thenReturn(uid_store);
    when(tsdb.registry.getPlugin(UniqueIdFactory.class, "LRU"))
      .thenReturn(uid_factory);
    tsdb.config.register("tsd.storage.uid.width.metric", 4, false, "UT");
    tsdb.config.register("tsd.storage.uid.width.tagk", 5, false, "UT");
    tsdb.config.register("tsd.storage.uid.width.tagv", 6, false, "UT");
    tsdb.config.register("tsd.storage.salt.buckets", 16, false, "UT");
    tsdb.config.register("tsd.storage.salt.width", 1, false, "UT");
    
    Schema schema = new Schema(factory, tsdb, null);
    assertEquals(4, schema.metricWidth());
    assertEquals(5, schema.tagkWidth());
    assertEquals(6, schema.tagvWidth());
    assertEquals(16, schema.saltBuckets());
    assertEquals(1, schema.saltWidth());
    assertSame(store, schema.dataStore());
    assertSame(uid_store, schema.uidStore());
  }
  
  @Test
  public void ctorID() throws Exception {
    MockTSDB tsdb = new MockTSDB();
    UniqueIdStore us = mock(UniqueIdStore.class);
    UniqueIdFactory uf = mock(UniqueIdFactory.class);
    UniqueId uc = mock(UniqueId.class);
    Tsdb1xDataStoreFactory sf = mock(Tsdb1xDataStoreFactory.class);
    Tsdb1xDataStore s = mock(Tsdb1xDataStore.class);
    when(tsdb.registry.getPlugin(eq(Tsdb1xDataStoreFactory.class), anyString()))
      .thenReturn(sf);
    when(sf.newInstance(eq(tsdb), eq(TESTID), any(Schema.class))).thenReturn(s);
    when(tsdb.registry.getSharedObject(TESTID + "_uidstore"))
      .thenReturn(us);
    when(tsdb.registry.getPlugin(UniqueIdFactory.class, "LRU"))
      .thenReturn(uf);
    when(uf.newInstance(eq(tsdb), anyString(), 
        any(UniqueIdType.class), eq(us))).thenReturn(uc);
    
    tsdb.config.register("tsd.storage." + TESTID + ".uid.width.metric", 4, false, "UT");
    tsdb.config.register("tsd.storage." + TESTID + ".uid.width.tagk", 5, false, "UT");
    tsdb.config.register("tsd.storage." + TESTID + ".uid.width.tagv", 6, false, "UT");
    tsdb.config.register("tsd.storage." + TESTID + ".salt.buckets", 16, false, "UT");
    tsdb.config.register("tsd.storage." + TESTID + ".salt.width", 1, false, "UT");
    tsdb.config.register("tsd.storage." + TESTID + ".data.store", TESTID, false, "UT");
    
    Schema schema = new Schema(factory, tsdb, TESTID);
    assertEquals(4, schema.metricWidth());
    assertEquals(5, schema.tagkWidth());
    assertEquals(6, schema.tagvWidth());
    assertEquals(16, schema.saltBuckets());
    assertEquals(1, schema.saltWidth());
    assertSame(s, schema.dataStore());
    assertSame(us, schema.uidStore());
    assertSame(uc, schema.metrics());
    assertSame(uc, schema.tagNames());
    assertSame(uc, schema.tagValues());
  }
  
  @Test
  public void ctorNoStoreFactory() throws Exception {
    MockTSDB tsdb = new MockTSDB();
    tsdb.config.register("tsd.storage.data.store", "NOTTHERE", false, "UT");
    try {
      new Schema(factory, tsdb, null);
      fail("Expected ConfigurationException");
    } catch (ConfigurationException e) { }
  }
  
  @Test
  public void ctorNullStoreFromFactory() throws Exception {
    MockTSDB tsdb = new MockTSDB();
    Tsdb1xDataStoreFactory store_factory = mock(Tsdb1xDataStoreFactory.class);
    when(tsdb.registry.getPlugin(eq(Tsdb1xDataStoreFactory.class), anyString()))
      .thenReturn(store_factory);
    when(store_factory.newInstance(eq(tsdb), eq(null), any(Schema.class)))
      .thenReturn(null);
    try {
      new Schema(factory, tsdb, null);
      fail("Expected IllegalStateException");
    } catch (IllegalStateException e) { }
  }
  
  @Test
  public void ctorStoreInstantiationFailure() throws Exception {
    MockTSDB tsdb = new MockTSDB();
    Tsdb1xDataStoreFactory store_factory = mock(Tsdb1xDataStoreFactory.class);
    when(tsdb.registry.getPlugin(eq(Tsdb1xDataStoreFactory.class), anyString()))
      .thenReturn(store_factory);
    when(store_factory.newInstance(eq(tsdb), eq(null), any(Schema.class)))
      .thenThrow(new UnitTestException());
    try {
      new Schema(factory, tsdb, null);
      fail("Expected UnitTestException");
    } catch (UnitTestException e) { }
  }

  @Test
  public void getTSUID() throws Exception {
    Schema schema = schema();
    try {
      schema.getTSUID(null);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    try {
      schema.getTSUID(new byte[0]);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    try {
      schema.getTSUID(new byte[4]);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    byte[] row = new byte[] { 0, 0, 1, 1, 2, 3, 4, 0, 0, 1, 0, 0, 1 };
    byte[] expected = new byte[] { 0, 0, 1, 0, 0, 1, 0, 0, 1 };
    assertArrayEquals(expected, schema.getTSUID(row));
    
    schema.salt_width = 1;
    row = new byte[] { 42, 0, 0, 1, 1, 2, 3, 4, 0, 0, 1, 0, 0, 1 };
    assertArrayEquals(expected, schema.getTSUID(row));
    
    schema.tagv_width = 5;
    row = new byte[] { 42, 0, 0, 1, 1, 2, 3, 4, 0, 0, 1, 0, 0, 0, 0, 1 };
    expected = new byte[] { 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 1 };
    assertArrayEquals(expected, schema.getTSUID(row));
  }
  
  @Test
  public void baseTimestamp() throws Exception {
    Schema schema = schema();
    TimeStamp ts = new MillisecondTimeStamp(0L);
    try {
      schema.baseTimestamp(null, ts);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    try {
      schema.baseTimestamp(new byte[0], ts);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    try {
      schema.baseTimestamp(new byte[4], ts);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    try {
      schema.baseTimestamp(new byte[16], null);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    byte[] row = new byte[] { 0, 0, 1, 1, 2, 3, 4, 0, 0, 1, 0, 0, 1 };
    schema.baseTimestamp(row, ts);
    assertEquals(16909060000L, ts.msEpoch());
    
    row = new byte[] { 0, 0, 1, 0x5A, 0x49, 0x7A, 0, 0, 0, 1, 0, 0, 1 };
    schema.baseTimestamp(row, ts);
    assertEquals(1514764800000L, ts.msEpoch());
  }
  
  @Test
  public void uidWidth() throws Exception {
    Schema schema = schema();
    schema.metric_width = 1;
    schema.tagk_width = 2;
    schema.tagv_width = 5;
    try {
      schema.uidWidth(null);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    assertEquals(1, schema.uidWidth(UniqueIdType.METRIC));
    assertEquals(2, schema.uidWidth(UniqueIdType.TAGK));
    assertEquals(5, schema.uidWidth(UniqueIdType.TAGV));
    try {
      schema.uidWidth(UniqueIdType.NAMESPACE);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
  }
  
  @Test
  public void getId() throws Exception {
    Schema schema = schema();
    
    try {
      schema.getId(null, METRIC_STRING, null);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    try {
      schema.getId(UniqueIdType.METRIC, null, null);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    try {
      schema.getId(UniqueIdType.METRIC, "", null);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    assertArrayEquals(METRIC_BYTES, schema.getId(
        UniqueIdType.METRIC, METRIC_STRING, null).join());
    assertArrayEquals(TAGK_BYTES, schema.getId(
        UniqueIdType.TAGK, TAGK_STRING, null).join());
    assertArrayEquals(TAGV_BYTES, schema.getId(
        UniqueIdType.TAGV, TAGV_STRING, null).join());
    
    assertNull(schema.getId(UniqueIdType.METRIC, NSUN_METRIC, null).join());
    assertNull(schema.getId(UniqueIdType.TAGK, NSUN_TAGK, null).join());
    assertNull(schema.getId(UniqueIdType.TAGV, NSUN_TAGV, null).join());
    
    Deferred<byte[]> deferred = schema.getId(
        UniqueIdType.METRIC, METRIC_STRING_EX, null);
    try {
      deferred.join();
      fail("Expected StorageException");
    } catch (StorageException e) { }
    
    deferred = schema.getId(UniqueIdType.TAGK, TAGK_STRING_EX, null);
    try {
      deferred.join();
      fail("Expected StorageException");
    } catch (StorageException e) { }
    
    deferred = schema.getId(UniqueIdType.TAGV, TAGV_STRING_EX, null);
    try {
      deferred.join();
      fail("Expected StorageException");
    } catch (StorageException e) { }
  }
  
  @Test
  public void getIds() throws Exception {
    Schema schema = schema();
    
    try {
      schema.getIds(null, Lists.newArrayList(METRIC_STRING), null);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    try {
      schema.getIds(UniqueIdType.METRIC, null, null);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    try {
      schema.getIds(UniqueIdType.METRIC, Lists.newArrayList(), null);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    assertArrayEquals(METRIC_BYTES, schema.getIds(UniqueIdType.METRIC, 
        Lists.newArrayList(METRIC_STRING), null).join().get(0));
    assertArrayEquals(TAGK_BYTES, schema.getIds(UniqueIdType.TAGK, 
        Lists.newArrayList(TAGK_STRING), null).join().get(0));
    assertArrayEquals(TAGV_BYTES, schema.getIds(UniqueIdType.TAGV, 
        Lists.newArrayList(TAGV_STRING), null).join().get(0));
    
    assertNull(schema.getIds(UniqueIdType.METRIC, 
        Lists.newArrayList(NSUN_METRIC), null).join().get(0));
    assertNull(schema.getIds(UniqueIdType.TAGK, 
        Lists.newArrayList(NSUN_TAGK), null).join().get(0));
    assertNull(schema.getIds(UniqueIdType.TAGV, 
        Lists.newArrayList(NSUN_TAGV), null).join().get(0));
    
    Deferred<List<byte[]>> deferred = schema.getIds(UniqueIdType.METRIC, 
        Lists.newArrayList(METRIC_STRING, METRIC_STRING_EX), null);
    try {
      deferred.join();
      fail("Expected StorageException");
    } catch (StorageException e) { }
    
    deferred = schema.getIds(UniqueIdType.TAGK, 
        Lists.newArrayList(TAGK_STRING, TAGK_STRING_EX), null);
    try {
      deferred.join();
      fail("Expected StorageException");
    } catch (StorageException e) { }
    
    deferred = schema.getIds(UniqueIdType.TAGV, 
        Lists.newArrayList(TAGV_STRING, TAGV_STRING_EX), null);
    try {
      deferred.join();
      fail("Expected StorageException");
    } catch (StorageException e) { }
  }
  
  @Test
  public void getName() throws Exception {
    Schema schema = schema();
    
    try {
      schema.getName(null, METRIC_BYTES, null);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    try {
      schema.getName(UniqueIdType.METRIC, null, null);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    try {
      schema.getName(UniqueIdType.METRIC, new byte[0], null);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    assertEquals(METRIC_STRING, schema.getName(
        UniqueIdType.METRIC, METRIC_BYTES, null).join());
    assertEquals(TAGK_STRING, schema.getName(
        UniqueIdType.TAGK, TAGK_BYTES, null).join());
    assertEquals(TAGV_STRING, schema.getName(
        UniqueIdType.TAGV, TAGV_BYTES, null).join());
    
    assertNull(schema.getName(UniqueIdType.METRIC, NSUI_METRIC, null).join());
    assertNull(schema.getName(UniqueIdType.TAGK, NSUI_TAGK, null).join());
    assertNull(schema.getName(UniqueIdType.TAGV, NSUI_TAGV, null).join());
    
    Deferred<String> deferred = schema.getName(
        UniqueIdType.METRIC, METRIC_BYTES_EX, null);
    try {
      deferred.join();
      fail("Expected StorageException");
    } catch (StorageException e) { }
    
    deferred = schema.getName(UniqueIdType.TAGK, TAGK_BYTES_EX, null);
    try {
      deferred.join();
      fail("Expected StorageException");
    } catch (StorageException e) { }
    
    deferred = schema.getName(UniqueIdType.TAGV, TAGV_BYTES_EX, null);
    try {
      deferred.join();
      fail("Expected StorageException");
    } catch (StorageException e) { }
  }
  
  @Test
  public void getNames() throws Exception {
    Schema schema = schema();
    
    try {
      schema.getNames(null, Lists.newArrayList(METRIC_BYTES), null);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    try {
      schema.getNames(UniqueIdType.METRIC, null, null);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    try {
      schema.getNames(UniqueIdType.METRIC, Lists.newArrayList(), null);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    assertEquals(METRIC_STRING, schema.getNames(UniqueIdType.METRIC, 
        Lists.newArrayList(METRIC_BYTES), null).join().get(0));
    assertEquals(TAGK_STRING, schema.getNames(UniqueIdType.TAGK, 
        Lists.newArrayList(TAGK_BYTES), null).join().get(0));
    assertEquals(TAGV_STRING, schema.getNames(UniqueIdType.TAGV, 
        Lists.newArrayList(TAGV_BYTES), null).join().get(0));
    
    assertNull(schema.getNames(UniqueIdType.METRIC, 
        Lists.newArrayList(NSUI_METRIC), null).join().get(0));
    assertNull(schema.getNames(UniqueIdType.TAGK, 
        Lists.newArrayList(NSUI_TAGK), null).join().get(0));
    assertNull(schema.getNames(UniqueIdType.TAGV, 
        Lists.newArrayList(NSUI_TAGV), null).join().get(0));
    
    Deferred<List<String>> deferred = schema.getNames(UniqueIdType.METRIC, 
        Lists.newArrayList(METRIC_BYTES, METRIC_BYTES_EX), null);
    try {
      deferred.join();
      fail("Expected StorageException");
    } catch (StorageException e) { }
    
    deferred = schema.getNames(UniqueIdType.TAGK, 
        Lists.newArrayList(TAGK_BYTES, TAGK_BYTES_EX), null);
    try {
      deferred.join();
      fail("Expected StorageException");
    } catch (StorageException e) { }
    
    deferred = schema.getNames(UniqueIdType.TAGV, 
        Lists.newArrayList(TAGV_BYTES, TAGV_BYTES_EX), null);
    try {
      deferred.join();
      fail("Expected StorageException");
    } catch (StorageException e) { }
  }
  
  @Test
  public void setBaseTime() throws Exception {
    // defaults
    Schema schema = schema();
    
    try {
      schema.setBaseTime(null, 42);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    try {
      schema.setBaseTime(new byte[0], 42);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    try {
      schema.setBaseTime(new byte[schema.metricWidth()], 42);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    byte[] row = new byte[schema.metricWidth() + Schema.TIMESTAMP_BYTES];
    schema.setBaseTime(row, 42);
    assertEquals(42, row[row.length - 1]);
    
    schema.setBaseTime(row, 1527811200);
    assertArrayEquals(Bytes.fromInt(1527811200), 
        Arrays.copyOfRange(row, schema.metricWidth(), 
            schema.metricWidth() + Schema.TIMESTAMP_BYTES));
    
    // with tags
    row = new byte[schema.metricWidth() + Schema.TIMESTAMP_BYTES
                   + schema.tagkWidth() + schema.tagvWidth()];
    
    schema.setBaseTime(row, 42);
    assertArrayEquals(Bytes.fromInt(42), 
        Arrays.copyOfRange(row, schema.metricWidth(), 
            schema.metricWidth() + Schema.TIMESTAMP_BYTES));
    
    schema.setBaseTime(row, 1527811200);
    assertArrayEquals(Bytes.fromInt(1527811200), 
        Arrays.copyOfRange(row, schema.metricWidth(), 
            schema.metricWidth() + Schema.TIMESTAMP_BYTES));
    
    // salt and diff metric width
    MockTSDB tsdb = new MockTSDB();
    when(tsdb.registry.getPlugin(eq(Tsdb1xDataStoreFactory.class), anyString()))
      .thenReturn(store_factory);
    when(store_factory.newInstance(any(TSDB.class), anyString(), any(Schema.class)))
      .thenReturn(store);    
    when(tsdb.registry.getSharedObject("default_uidstore"))
      .thenReturn(uid_store);
    when(tsdb.registry.getPlugin(UniqueIdFactory.class, "LRU"))
      .thenReturn(uid_factory);
    tsdb.config.register("tsd.storage.uid.width.metric", 4, false, "UT");
    tsdb.config.register("tsd.storage.salt.buckets", 16, false, "UT");
    tsdb.config.register("tsd.storage.salt.width", 1, false, "UT");
    
    schema = new Schema(factory, tsdb, null);
    
    row = new byte[schema.saltWidth() + schema.metricWidth() 
                   + Schema.TIMESTAMP_BYTES + schema.tagkWidth() 
                   + schema.tagvWidth()];
    
    schema.setBaseTime(row, 42);
    assertArrayEquals(Bytes.fromInt(42), 
        Arrays.copyOfRange(row, schema.saltWidth() + schema.metricWidth(), 
            schema.saltWidth() + schema.metricWidth() + Schema.TIMESTAMP_BYTES));
    
    schema.setBaseTime(row, 1527811200);
    assertArrayEquals(Bytes.fromInt(1527811200), 
        Arrays.copyOfRange(row, schema.saltWidth() + schema.metricWidth(), 
            schema.saltWidth() + schema.metricWidth() + Schema.TIMESTAMP_BYTES));
  }
  
  @Test
  public void prefixKeyWithSalt() throws Exception {
    MockTSDB tsdb = new MockTSDB();
    when(tsdb.registry.getPlugin(eq(Tsdb1xDataStoreFactory.class), anyString()))
      .thenReturn(store_factory);
    when(store_factory.newInstance(any(TSDB.class), anyString(), any(Schema.class)))
      .thenReturn(store);    
    when(tsdb.registry.getSharedObject("default_uidstore"))
      .thenReturn(uid_store);
    when(tsdb.registry.getPlugin(UniqueIdFactory.class, "LRU"))
      .thenReturn(uid_factory);
    tsdb.config.register("tsd.storage.uid.width.metric", 4, false, "UT");
    tsdb.config.register("tsd.storage.uid.width.tagk", 4, false, "UT");
    tsdb.config.register("tsd.storage.uid.width.tagv", 4, false, "UT");
    tsdb.config.register("tsd.storage.salt.buckets", 20, false, "UT");
    tsdb.config.register("tsd.storage.salt.width", 1, false, "UT");
    
    Schema schema = new Schema(factory, tsdb, null);
    
    byte[] key = new byte[] { 0, 0, -41, -87, -12, 91, 8, 107, 64, 0, 0, 
        0, 1, 0, 0, 0, 1, 0, 0, 0, 2, 69, 124, 52, -106, 0, 0, 0, 22, 
        34, -87, 25, 92, 0, 0, 0, 80, 6, 67, 94, -84, 0, 0, 0, 89, 0, 
        0, -17, -1, 0, 0, 16, 116, 72, 112, 67, 25 };
    schema.prefixKeyWithSalt(key);
    assertEquals(6, key[0]);
    
    key = new byte[] { 0, -27, 89, 20, -100, 91, 8, 65, 16, 0, 0, 0, 1, 
        0, 0, 0, 1, 0, 0, 0, 2, 16, -1, 42, -124, 0, 0, 0, 56, 0, 5, 
        79, -91 };
    schema.prefixKeyWithSalt(key);
    assertEquals(7, key[0]);
    
    key = new byte[] { 12, -27, 89, 20, -100, 91, 8, 51, 0, 0, 0, 0, 1, 
        0, 0, 0, 1, 0, 0, 0, 2, 16, -1, 44, 81, 0, 0, 0, 56, 0, 5, 79, 
        -91 };
    schema.prefixKeyWithSalt(key);
    assertEquals(0, key[0]);
    
    // Switch every hour/interval
    tsdb.config.override(Schema.TIMELESS_SALTING_KEY, false);
    tsdb.config.override("tsd.storage.uid.width.metric", 4);
    tsdb.config.override("tsd.storage.uid.width.tagk", 4);
    tsdb.config.override("tsd.storage.uid.width.tagv", 4);
    tsdb.config.override("tsd.storage.salt.buckets", 20);
    tsdb.config.override("tsd.storage.salt.width", 1);
    
    schema = new Schema(factory, tsdb, null);
    
    key = new byte[] { 0, 0, -41, -87, -12, 91, 8, 107, 64, 0, 0, 
        0, 1, 0, 0, 0, 1, 0, 0, 0, 2, 69, 124, 52, -106, 0, 0, 0, 22, 
        34, -87, 25, 92, 0, 0, 0, 80, 6, 67, 94, -84, 0, 0, 0, 89, 0, 
        0, -17, -1, 0, 0, 16, 116, 72, 112, 67, 25 };
    schema.prefixKeyWithSalt(key);
    assertEquals(4, key[0]);
    
    key = new byte[] { 0, -27, 89, 20, -100, 91, 8, 65, 16, 0, 0, 0, 1, 
        0, 0, 0, 1, 0, 0, 0, 2, 16, -1, 42, -124, 0, 0, 0, 56, 0, 5, 
        79, -91 };
    schema.prefixKeyWithSalt(key);
    assertEquals(3, key[0]);
    
    key = new byte[] { 12, -27, 89, 20, -100, 91, 8, 51, 0, 0, 0, 0, 1, 
        0, 0, 0, 1, 0, 0, 0, 2, 16, -1, 44, 81, 0, 0, 0, 56, 0, 5, 79, 
        -91 };
    schema.prefixKeyWithSalt(key);
    assertEquals(6, key[0]);
    
    // OLD school. Don't do it!!
    tsdb.config.override(Schema.OLD_SALTING_KEY, true);
    tsdb.config.override(Schema.TIMELESS_SALTING_KEY, false);
    tsdb.config.override("tsd.storage.uid.width.metric", 4);
    tsdb.config.override("tsd.storage.uid.width.tagk", 4);
    tsdb.config.override("tsd.storage.uid.width.tagv", 4);
    tsdb.config.override("tsd.storage.salt.buckets", 20);
    tsdb.config.override("tsd.storage.salt.width", 1);
    
    schema = new Schema(factory, tsdb, null);
    
    key = new byte[] { 0, 0, -41, -87, -12, 91, 8, 107, 64, 0, 0, 
        0, 1, 0, 0, 0, 1, 0, 0, 0, 2, 69, 124, 52, -106, 0, 0, 0, 22, 
        34, -87, 25, 92, 0, 0, 0, 80, 6, 67, 94, -84, 0, 0, 0, 89, 0, 
        0, -17, -1, 0, 0, 16, 116, 72, 112, 67, 25 };
    schema.prefixKeyWithSalt(key);
    assertEquals(10, key[0]);
    
    key = new byte[] { 0, -27, 89, 20, -100, 91, 8, 65, 16, 0, 0, 0, 1, 
        0, 0, 0, 1, 0, 0, 0, 2, 16, -1, 42, -124, 0, 0, 0, 56, 0, 5, 
        79, -91 };
    schema.prefixKeyWithSalt(key);
    assertEquals(8, key[0]);
    
    key = new byte[] { 12, -27, 89, 20, -100, 91, 8, 51, 0, 0, 0, 0, 1, 
        0, 0, 0, 1, 0, 0, 0, 2, 16, -1, 44, 81, 0, 0, 0, 56, 0, 5, 79, 
        -91 };
    schema.prefixKeyWithSalt(key);
    assertEquals(4, key[0]);
  }
  
  @Test
  public void prefixKeyWithSaltMultiByte() throws Exception {
    MockTSDB tsdb = new MockTSDB();
    when(tsdb.registry.getPlugin(eq(Tsdb1xDataStoreFactory.class), anyString()))
      .thenReturn(store_factory);
    when(store_factory.newInstance(any(TSDB.class), anyString(), any(Schema.class)))
      .thenReturn(store);    
    when(tsdb.registry.getSharedObject("default_uidstore"))
      .thenReturn(uid_store);
    when(tsdb.registry.getPlugin(UniqueIdFactory.class, "LRU"))
      .thenReturn(uid_factory);
    tsdb.config.register("tsd.storage.uid.width.metric", 4, false, "UT");
    tsdb.config.register("tsd.storage.uid.width.tagk", 4, false, "UT");
    tsdb.config.register("tsd.storage.uid.width.tagv", 4, false, "UT");
    tsdb.config.register("tsd.storage.salt.buckets", 20, false, "UT");
    tsdb.config.register("tsd.storage.salt.width", 3, false, "UT");
    
    Schema schema = new Schema(factory, tsdb, null);
    
    byte[] key = new byte[] { 0, 0, 0, 0, -41, -87, -12, 91, 8, 107, 64, 0, 0, 
        0, 1, 0, 0, 0, 1, 0, 0, 0, 2, 69, 124, 52, -106, 0, 0, 0, 22, 
        34, -87, 25, 92, 0, 0, 0, 80, 6, 67, 94, -84, 0, 0, 0, 89, 0, 
        0, -17, -1, 0, 0, 16, 116, 72, 112, 67, 25 };
    schema.prefixKeyWithSalt(key);
    assertEquals(6, key[2]);
    assertEquals(0, key[1]);
    assertEquals(0, key[0]);
    
    key = new byte[] { 0, 0, 0, -27, 89, 20, -100, 91, 8, 65, 16, 0, 0, 0, 1, 
        0, 0, 0, 1, 0, 0, 0, 2, 16, -1, 42, -124, 0, 0, 0, 56, 0, 5, 
        79, -91 };
    schema.prefixKeyWithSalt(key);
    assertEquals(7, key[2]);
    assertEquals(0, key[1]);
    assertEquals(0, key[0]);
    
    key = new byte[] { 0, 0, 12, -27, 89, 20, -100, 91, 8, 51, 0, 0, 0, 0, 1, 
        0, 0, 0, 1, 0, 0, 0, 2, 16, -1, 44, 81, 0, 0, 0, 56, 0, 5, 79, 
        -91 };
    schema.prefixKeyWithSalt(key);
    assertEquals(0, key[2]);
    assertEquals(0, key[1]);
    assertEquals(0, key[0]);
    
    // Switch every hour/interval
    tsdb.config.override(Schema.TIMELESS_SALTING_KEY, false);
    tsdb.config.override("tsd.storage.uid.width.metric", 4);
    tsdb.config.override("tsd.storage.uid.width.tagk", 4);
    tsdb.config.override("tsd.storage.uid.width.tagv", 4);
    tsdb.config.override("tsd.storage.salt.buckets", 20);
    tsdb.config.override("tsd.storage.salt.width", 3);
    
    schema = new Schema(factory, tsdb, null);
    
    key = new byte[] { 0, 0, 0, 0, -41, -87, -12, 91, 8, 107, 64, 0, 0, 
        0, 1, 0, 0, 0, 1, 0, 0, 0, 2, 69, 124, 52, -106, 0, 0, 0, 22, 
        34, -87, 25, 92, 0, 0, 0, 80, 6, 67, 94, -84, 0, 0, 0, 89, 0, 
        0, -17, -1, 0, 0, 16, 116, 72, 112, 67, 25 };
    schema.prefixKeyWithSalt(key);
    assertEquals(4, key[2]);
    assertEquals(0, key[1]);
    assertEquals(0, key[0]);
    
    key = new byte[] { 0, 0, 0, -27, 89, 20, -100, 91, 8, 65, 16, 0, 0, 0, 1, 
        0, 0, 0, 1, 0, 0, 0, 2, 16, -1, 42, -124, 0, 0, 0, 56, 0, 5, 
        79, -91 };
    schema.prefixKeyWithSalt(key);
    assertEquals(3, key[2]);
    assertEquals(0, key[1]);
    assertEquals(0, key[0]);
    
    key = new byte[] { 0, 0, 12, -27, 89, 20, -100, 91, 8, 51, 0, 0, 0, 0, 1, 
        0, 0, 0, 1, 0, 0, 0, 2, 16, -1, 44, 81, 0, 0, 0, 56, 0, 5, 79, 
        -91 };
    schema.prefixKeyWithSalt(key);
    assertEquals(6, key[2]);
    assertEquals(0, key[1]);
    assertEquals(0, key[0]);
    
    // OLD school. Don't do it!!
    tsdb.config.override(Schema.OLD_SALTING_KEY, true);
    tsdb.config.override(Schema.TIMELESS_SALTING_KEY, false);
    tsdb.config.override("tsd.storage.uid.width.metric", 4);
    tsdb.config.override("tsd.storage.uid.width.tagk", 4);
    tsdb.config.override("tsd.storage.uid.width.tagv", 4);
    tsdb.config.override("tsd.storage.salt.buckets", 20);
    tsdb.config.override("tsd.storage.salt.width", 3);
    
    schema = new Schema(factory, tsdb, null);
    
    key = new byte[] { 0, 0, 0, 0, -41, -87, -12, 91, 8, 107, 64, 0, 0, 
        0, 1, 0, 0, 0, 1, 0, 0, 0, 2, 69, 124, 52, -106, 0, 0, 0, 22, 
        34, -87, 25, 92, 0, 0, 0, 80, 6, 67, 94, -84, 0, 0, 0, 89, 0, 
        0, -17, -1, 0, 0, 16, 116, 72, 112, 67, 25 };
    schema.prefixKeyWithSalt(key);
    assertEquals(10, key[2]);
    assertEquals(0, key[1]);
    assertEquals(0, key[0]);
    
    key = new byte[] { 0, 0, 0, -27, 89, 20, -100, 91, 8, 65, 16, 0, 0, 0, 1, 
        0, 0, 0, 1, 0, 0, 0, 2, 16, -1, 42, -124, 0, 0, 0, 56, 0, 5, 
        79, -91 };
    schema.prefixKeyWithSalt(key);
    assertEquals(8, key[2]);
    assertEquals(0, key[1]);
    assertEquals(0, key[0]);
    
    key = new byte[] { 0, 0, 12, -27, 89, 20, -100, 91, 8, 51, 0, 0, 0, 0, 1, 
        0, 0, 0, 1, 0, 0, 0, 2, 16, -1, 44, 81, 0, 0, 0, 56, 0, 5, 79, 
        -91 };
    schema.prefixKeyWithSalt(key);
    assertEquals(4, key[2]);
    assertEquals(0, key[1]);
    assertEquals(0, key[0]);
  }

  @Test
  public void resolveByteId() throws Exception {
    Schema schema = schema();
    Whitebox.setInternalState(factory, "schema", schema);
    TimeSeriesByteId id = BaseTimeSeriesByteId.newBuilder(factory)
        .setNamespace("Ns".getBytes(Const.UTF8_CHARSET))
        .setMetric(METRIC_BYTES)
        .addTags(TAGK_BYTES, TAGV_BYTES)
        .addAggregatedTag(TAGK_B_BYTES)
        .setAlias("alias".getBytes(Const.UTF8_CHARSET))
        .addDisjointTag(UIDS.get("B"))
        .build();
    
    TimeSeriesStringId newid = schema.resolveByteId(id, null).join();
    assertEquals("alias", newid.alias());
    assertEquals("Ns", newid.namespace());
    assertEquals(METRIC_STRING, newid.metric());
    assertEquals(TAGV_STRING, newid.tags().get(TAGK_STRING));
    assertEquals(TAGK_B_STRING, newid.aggregatedTags().get(0));
    assertEquals("B", newid.disjointTags().get(0));
    
    // skip metric
    id = BaseTimeSeriesByteId.newBuilder(factory)
        .setNamespace("Ns".getBytes(Const.UTF8_CHARSET))
        .setMetric("MyMetric".getBytes(Const.UTF8_CHARSET))
        .addTags(TAGK_BYTES, TAGV_BYTES)
        .addAggregatedTag(TAGK_B_BYTES)
        .setAlias("alias".getBytes(Const.UTF8_CHARSET))
        .addDisjointTag(UIDS.get("B"))
        .setSkipMetric(true)
        .build();
    
    newid = schema.resolveByteId(id, null).join();
    assertEquals("alias", newid.alias());
    assertEquals("Ns", newid.namespace());
    assertEquals("MyMetric", newid.metric());
    assertEquals(TAGV_STRING, newid.tags().get(TAGK_STRING));
    assertEquals(TAGK_B_STRING, newid.aggregatedTags().get(0));
    assertEquals("B", newid.disjointTags().get(0));
    
    // exception
    id = BaseTimeSeriesByteId.newBuilder(factory)
        .setNamespace("Ns".getBytes(Const.UTF8_CHARSET))
        .setMetric(METRIC_BYTES_EX)
        .addTags(TAGK_BYTES, TAGV_BYTES)
        .addAggregatedTag(TAGK_B_BYTES)
        .setAlias("alias".getBytes(Const.UTF8_CHARSET))
        .addDisjointTag(UIDS.get("B"))
        .build();
    
    try {
      schema.resolveByteId(id, null).join();
      fail("Expected StorageException");
    } catch (StorageException e) { }
  }

//  @Test
//  public void writeDatum() throws Exception {
//    Schema schema = schema();
//    when(id_validator.validate(any(TimeSeriesDatumId.class)))
//      .thenReturn(null);
//    when(store.write(any(AuthState.class), any(TimeSeriesDatum.class), 
//        any(net.opentsdb.stats.Span.class)))
//      .thenReturn(Deferred.fromResult(WriteStatus.OK));
//    assertEquals(WriteStatus.OK, schema.write(null, 
//        mock(TimeSeriesDatum.class), null).join());
//    verify(store, times(1)).write(any(AuthState.class), any(TimeSeriesDatum.class), 
//        any(net.opentsdb.stats.Span.class));
//    
//    when(id_validator.validate(any(TimeSeriesDatumId.class)))
//      .thenReturn("Ooops");
//    
//    WriteStatus status = schema.write(null, 
//        mock(TimeSeriesDatum.class), null).join();
//    assertEquals(WriteState.REJECTED, status.state());
//    assertEquals("Ooops", status.message());
//    verify(store, times(1)).write(any(AuthState.class), any(TimeSeriesDatum.class), 
//        any(net.opentsdb.stats.Span.class));
//  }
  
//  @Test
//  public void writeData() throws Exception {
//    Schema schema = schema();
//    when(id_validator.validate(any(TimeSeriesDatumId.class)))
//      .thenReturn(null);
//    when(store.write(any(AuthState.class), any(TimeSeriesSharedTagsAndTimeData.class), 
//        any(net.opentsdb.stats.Span.class)))
//      .thenReturn(Deferred.fromResult(Lists.newArrayList(
//          WriteStatus.OK, 
//          WriteStatus.OK)));
//    
//    TimeSeriesDatumStringId id_a = BaseTimeSeriesDatumStringId.newBuilder()
//        .setMetric(METRIC_STRING)
//        .addTags(TAGK_STRING, TAGV_STRING)
//        .build();
//    TimeSeriesDatumStringId id_b = BaseTimeSeriesDatumStringId.newBuilder()
//        .setMetric(METRIC_B_STRING)
//        .addTags(TAGK_STRING, TAGV_STRING)
//        .build();
//    TimeStamp ts = new SecondTimeStamp(1262304000);
//    MutableNumericValue value_a = new MutableNumericValue(ts, 42);
//    MutableNumericValue value_b = new MutableNumericValue(ts, 24);
//    
//    List<TimeSeriesDatum> data = Lists.newArrayList(
//        TimeSeriesDatum.wrap(id_a, value_a),
//        TimeSeriesDatum.wrap(id_b, value_b));
//    
//    List<WriteStatus> status = schema.write(null, 
//        TimeSeriesSharedTagsAndTimeData.fromCollection(data), null).join(); 
//    assertEquals(2, status.size());
//    assertEquals(WriteStatus.OK, status.get(0));
//    assertEquals(WriteStatus.OK, status.get(1));
//    verify(store, times(1)).write(any(AuthState.class), 
//        any(TimeSeriesSharedTagsAndTimeData.class), 
//        any(net.opentsdb.stats.Span.class));
//    
//    // fail the first
//    when(id_validator.validate(any(TimeSeriesDatumId.class)))
//      .thenReturn("Ooops!")
//      .thenReturn(null);
//    when(store.write(any(AuthState.class), any(TimeSeriesSharedTagsAndTimeData.class), 
//        any(net.opentsdb.stats.Span.class)))
//      .thenReturn(Deferred.fromResult(Lists.newArrayList(
//          WriteStatus.OK)));
//    
//    status = schema.write(null, 
//        TimeSeriesSharedTagsAndTimeData.fromCollection(data), null).join(); 
//    assertEquals(2, status.size());
//    assertEquals(WriteState.REJECTED, status.get(0).state());
//    assertEquals(WriteStatus.OK, status.get(1));
//    verify(store, times(2)).write(any(AuthState.class), 
//        any(TimeSeriesSharedTagsAndTimeData.class), 
//        any(net.opentsdb.stats.Span.class));
//    
//    // fail the second
//    when(id_validator.validate(any(TimeSeriesDatumId.class)))
//      .thenReturn(null)
//      .thenReturn("Ooops!");
//    when(store.write(any(AuthState.class), any(TimeSeriesSharedTagsAndTimeData.class), 
//        any(net.opentsdb.stats.Span.class)))
//      .thenReturn(Deferred.fromResult(Lists.newArrayList(
//          WriteStatus.OK)));
//    
//    status = schema.write(null, 
//        TimeSeriesSharedTagsAndTimeData.fromCollection(data), null).join(); 
//    assertEquals(2, status.size());    
//    assertEquals(WriteStatus.OK, status.get(0));
//    assertEquals(WriteState.REJECTED, status.get(1).state());
//    verify(store, times(3)).write(any(AuthState.class), 
//        any(TimeSeriesSharedTagsAndTimeData.class), 
//        any(net.opentsdb.stats.Span.class));
//    
//    // fail all
//    when(id_validator.validate(any(TimeSeriesDatumId.class)))
//      .thenReturn("Ooops!")
//      .thenReturn("Ooops!");
//    when(store.write(any(AuthState.class), any(TimeSeriesSharedTagsAndTimeData.class), 
//        any(net.opentsdb.stats.Span.class)))
//      .thenReturn(Deferred.fromResult(Lists.newArrayList(
//          WriteStatus.OK)));
//    
//    status = schema.write(null, 
//        TimeSeriesSharedTagsAndTimeData.fromCollection(data), null).join(); 
//    assertEquals(2, status.size());    
//    assertEquals(WriteState.REJECTED, status.get(0).state());
//    assertEquals(WriteState.REJECTED, status.get(1).state());
//    
//    // not called here
//    verify(store, times(3)).write(any(AuthState.class), 
//        any(TimeSeriesSharedTagsAndTimeData.class), 
//        any(net.opentsdb.stats.Span.class));
//  }

  @Test
  public void createRowKeySuccess() throws Exception {
    resetConfig();
    Schema schema = schema();
    
    MutableNumericValue value = 
        new MutableNumericValue(new MillisecondTimeStamp(1262305800000L), 42);
    TimeSeriesDatumStringId id = BaseTimeSeriesDatumStringId.newBuilder()
        .setMetric(METRIC_STRING)
        .addTags(TAGK_STRING, TAGV_STRING)
        .build();
    
    IdOrError ioe = schema.createRowKey(null, 
        TimeSeriesDatum.wrap(id, value), null, null).join();
    assertNull(ioe.error());
    byte[] expected = getRowKey(schema, METRIC_STRING, 1262304000, 
        TAGK_STRING, TAGV_STRING);
    assertArrayEquals(expected, ioe.id());
    
    // two tags assigned
    id = BaseTimeSeriesDatumStringId.newBuilder()
        .setMetric(METRIC_STRING)
        .addTags(TAGK_STRING, TAGV_STRING)
        .addTags(TAGK_B_STRING, TAGV_B_STRING)
        .build();
    
    ioe = schema.createRowKey(null, 
        TimeSeriesDatum.wrap(id, value), null, null).join();
    assertNull(ioe.error());
    expected = getRowKey(schema, METRIC_STRING, 1262304000, 
        TAGK_STRING, TAGV_STRING, TAGK_B_STRING, TAGV_B_STRING);
    assertArrayEquals(expected, ioe.id());
    
    // ms resolution
    value = 
        new MutableNumericValue(new MillisecondTimeStamp(1262305800250L), 42);
    ioe = schema.createRowKey(null, 
        TimeSeriesDatum.wrap(id, value), null, null).join();
    assertNull(ioe.error());
    assertArrayEquals(expected, ioe.id());
    
    // nanosecond resolution
    value = new MutableNumericValue(new ZonedNanoTimeStamp(1262305800, 
        250000000, ZoneId.of("UTC")), 42);
    ioe = schema.createRowKey(null, 
        TimeSeriesDatum.wrap(id, value), null, null).join();
    assertNull(ioe.error());
    assertArrayEquals(expected, ioe.id());
  }

  @Test
  public void createRowKeySuccessSalted() throws Exception {
    resetConfig();
    MockTSDB tsdb = new MockTSDB();
    when(tsdb.registry.getPlugin(eq(Tsdb1xDataStoreFactory.class), anyString()))
      .thenReturn(store_factory);
    when(store_factory.newInstance(any(TSDB.class), anyString(), any(Schema.class)))
      .thenReturn(store);    
    when(tsdb.registry.getSharedObject("default_uidstore"))
      .thenReturn(uid_store);
    when(tsdb.registry.getPlugin(UniqueIdFactory.class, "LRU"))
      .thenReturn(uid_factory);
    tsdb.config.register("tsd.storage.salt.buckets", 250, false, "UT"); // high to ensure salting
    tsdb.config.register("tsd.storage.salt.width", 1, false, "UT");
    
    Schema schema = new Schema(factory, tsdb, null);
    
    MutableNumericValue value = 
        new MutableNumericValue(new MillisecondTimeStamp(1262305800000L), 42);
    TimeSeriesDatumStringId id = BaseTimeSeriesDatumStringId.newBuilder()
        .setMetric(METRIC_STRING)
        .addTags(TAGK_STRING, TAGV_STRING)
        .build();
    
    IdOrError ioe = schema.createRowKey(null, 
        TimeSeriesDatum.wrap(id, value), null, null).join();
    assertNull(ioe.error());
    byte[] expected = getRowKey(schema, METRIC_STRING, 1262304000, 
        TAGK_STRING, TAGV_STRING);
    assertArrayEquals(expected, ioe.id());
    assertTrue(ioe.id()[0] != 0);
    assertEquals(14, ioe.id().length);
    
    // two tags assigned
    id = BaseTimeSeriesDatumStringId.newBuilder()
        .setMetric(METRIC_STRING)
        .addTags(TAGK_STRING, TAGV_STRING)
        .addTags(TAGK_B_STRING, TAGV_B_STRING)
        .build();
    
    ioe = schema.createRowKey(null, 
        TimeSeriesDatum.wrap(id, value), null, null).join();
    assertNull(ioe.error());
    expected = getRowKey(schema, METRIC_STRING, 1262304000, 
        TAGK_STRING, TAGV_STRING, TAGK_B_STRING, TAGV_B_STRING);
    assertArrayEquals(expected, ioe.id());
    assertTrue(ioe.id()[0] != 0);
    assertEquals(20, ioe.id().length);
  }
  
  @Test
  public void createRowKeyRejected() throws Exception {
    resetConfig();
    Schema schema = schema();
    
    // negative timestamps are not allowed
    MutableNumericValue value = 
        new MutableNumericValue(new MillisecondTimeStamp(-1262305800000L), 42);
    TimeSeriesDatumStringId id = BaseTimeSeriesDatumStringId.newBuilder()
        .setMetric(METRIC_STRING)
        .addTags(TAGK_STRING, TAGV_STRING)
        .build();
    
    IdOrError ioe = schema.createRowKey(null, 
        TimeSeriesDatum.wrap(id, value), null, null).join();
    assertNull(ioe.id());
    assertEquals(WriteState.REJECTED, ioe.state());
    
    // unassigned strings
    id = BaseTimeSeriesDatumStringId.newBuilder()
        .setMetric("unassigned")
        .addTags(TAGK_STRING, TAGV_STRING)
        .build();
    
    ioe = schema.createRowKey(null, 
        TimeSeriesDatum.wrap(id, value), null, null).join();
    assertNull(ioe.id());
    assertEquals(WriteState.REJECTED, ioe.state());
    
    id = BaseTimeSeriesDatumStringId.newBuilder()
        .setMetric(METRIC_STRING)
        .addTags("unassigned", TAGV_STRING)
        .build();
    
    ioe = schema.createRowKey(null, 
        TimeSeriesDatum.wrap(id, value), null, null).join();
    assertNull(ioe.id());
    assertEquals(WriteState.REJECTED, ioe.state());
    
    id = BaseTimeSeriesDatumStringId.newBuilder()
        .setMetric(METRIC_STRING)
        .addTags(TAGK_STRING, "unassigned")
        .build();
    
    ioe = schema.createRowKey(null, 
        TimeSeriesDatum.wrap(id, value), null, null).join();
    assertNull(ioe.id());
    assertEquals(WriteState.REJECTED, ioe.state());
  }

  @Test
  public void createRowTagsSuccess() throws Exception {
    resetConfig();
    Schema schema = schema();

    Map<String, String> tags = Maps.newHashMap();
    tags.put(TAGK_STRING, TAGV_STRING);
    tags.put(TAGK_B_STRING, TAGV_B_STRING);
    IdOrError ioe = schema.createRowTags(null,
            METRIC_STRING, tags,null).join();
    byte[] expected = com.google.common.primitives.Bytes.concat(
            TAGK_BYTES, TAGV_BYTES, TAGK_B_BYTES, TAGV_B_BYTES);
    assertEquals(WriteState.OK, ioe.state());
    assertArrayEquals(expected, ioe.id());
  }

  @Test
  public void createRowTagsRejected() throws Exception {
    resetConfig();
    Schema schema = schema();

    Map<String, String> tags = Maps.newHashMap();
    tags.put("unassigned", TAGV_STRING);
    tags.put(TAGK_B_STRING, TAGV_B_STRING);
    IdOrError ioe = schema.createRowTags(null,
            METRIC_STRING, tags,null).join();
    assertNull(ioe.id());
    assertEquals(WriteState.REJECTED, ioe.state());

    tags = Maps.newHashMap();
    tags.put(TAGK_STRING, TAGV_STRING);
    tags.put(TAGK_B_STRING, "unassigned");
    ioe = schema.createRowTags(null,
            METRIC_STRING, tags,null).join();
    assertNull(ioe.id());
    assertEquals(WriteState.REJECTED, ioe.state());
  }

  @Test
  public void createRowMetricIdSuccess() throws Exception {
    resetConfig();
    Schema schema = schema();

    TimeSeriesDatumStringId id = BaseTimeSeriesDatumStringId.newBuilder()
            .setMetric(METRIC_STRING)
            .addTags(TAGK_STRING, TAGV_STRING)
            .build();
    IdOrError ioe = schema.createRowMetric(null, id, null).join();
    assertEquals(WriteState.OK, ioe.state());
    assertArrayEquals(METRIC_BYTES, ioe.id());
  }

  @Test
  public void createRowMetricIdRejected() throws Exception {
    resetConfig();
    Schema schema = schema();

    TimeSeriesDatumStringId id = BaseTimeSeriesDatumStringId.newBuilder()
            .setMetric("unassigned")
            .addTags(TAGK_STRING, TAGV_STRING)
            .build();
    IdOrError ioe = schema.createRowMetric(null, id, null).join();
    assertEquals(WriteState.REJECTED, ioe.state());
  }

  @Test
  public void createRowMetricStringsSuccess() throws Exception {
    resetConfig();
    Schema schema = schema();

    Map<String, String> tags = Maps.newHashMap();
    tags.put(TAGK_STRING, TAGV_STRING);
    tags.put(TAGK_B_STRING, TAGV_B_STRING);
    IdOrError ioe = schema.createRowMetric(null, METRIC_STRING, tags,null).join();
    assertEquals(WriteState.OK, ioe.state());
    assertArrayEquals(METRIC_BYTES, ioe.id());
  }

  @Test
  public void createRowMetricStringsRejected() throws Exception {
    resetConfig();
    Schema schema = schema();

    Map<String, String> tags = Maps.newHashMap();
    tags.put(TAGK_STRING, TAGV_STRING);
    tags.put(TAGK_B_STRING, TAGV_B_STRING);
    IdOrError ioe = schema.createRowMetric(null, "unassigned", tags,null).join();
    assertEquals(WriteState.REJECTED, ioe.state());
  }

  @Test
  public void getEncoder() throws Exception {
    resetConfig();
    Schema schema = schema();
    assertTrue(schema.getEncoder(NumericType.TYPE) instanceof NumericCodec);
    assertTrue(schema.getEncoder(NumericSummaryType.TYPE) instanceof NumericSummaryCodec);
    assertNull(schema.getEncoder(AnnotationType.TYPE));
  }

//  @Test
//  public void encode() throws Exception {
//    resetConfig();
//    Schema schema = schema();
//
//    MutableNumericValue value =
//        new MutableNumericValue(new SecondTimeStamp(1262304000), 42);
//    Pair<byte[], byte[]> qv = schema.encode(value, false, 1262304000, null);
//    assertArrayEquals(new byte[] { 0, 0 }, qv.getKey());
//    assertArrayEquals(new byte[] { 42 }, qv.getValue());
//
//    qv = schema.encode(value, true, 1262304000, null);
//    assertArrayEquals(NumericCodec.APPEND_QUALIFIER, qv.getKey());
//    assertArrayEquals(new byte[] { 0, 0, 42 }, qv.getValue());
//
//    TimeSeriesValue no_codec = mock(TimeSeriesValue.class);
//    when(no_codec.type()).thenReturn(TypeToken.of(String.class));
//    assertNull(schema.encode(no_codec, true, 1262304000, null));
//
//    try {
//      schema.encode(null, false, 1262304000, null);
//      fail("Expected IllegalArgumentException");
//    } catch (IllegalArgumentException e) { }
//  }

  @Test
  public void newSeries() throws Exception {
    ObjectPool numeric_pool = new DummyObjectPool(tsdb, 
        DefaultObjectPoolConfig.newBuilder()
          .setAllocator(new Tsdb1xNumericPartialTimeSeriesPool())
          .setId(Tsdb1xNumericPartialTimeSeriesPool.TYPE)
          .build());
    ObjectPool summary_pool = new DummyObjectPool(tsdb, 
        DefaultObjectPoolConfig.newBuilder()
          .setAllocator(new Tsdb1xNumericSummaryPartialTimeSeriesPool())
          .setId(Tsdb1xNumericSummaryPartialTimeSeriesPool.TYPE)
          .build());
    ObjectPool long_array_pool = new DummyObjectPool(tsdb, 
        DefaultObjectPoolConfig.newBuilder()
          .setAllocator(new LongArrayPool())
          .setId(LongArrayPool.TYPE)
          .build());
    ObjectPool byte_array_pool = new DummyObjectPool(tsdb, 
        DefaultObjectPoolConfig.newBuilder()
          .setAllocator(new ByteArrayPool())
          .setId(ByteArrayPool.TYPE)
          .build());
    when(tsdb.registry.getObjectPool(Tsdb1xNumericPartialTimeSeriesPool.TYPE))
      .thenReturn(numeric_pool);
    when(tsdb.registry.getObjectPool(Tsdb1xNumericSummaryPartialTimeSeriesPool.TYPE))
      .thenReturn(summary_pool);
    when(tsdb.registry.getObjectPool(LongArrayPool.TYPE))
      .thenReturn(long_array_pool);
    when(tsdb.registry.getObjectPool(ByteArrayPool.TYPE))
      .thenReturn(byte_array_pool);
    Schema schema = schema();
    PartialTimeSeriesSet set = mock(PartialTimeSeriesSet.class);
    TimeStamp timestamp = new SecondTimeStamp(1546300800);
    assertTrue(schema.pool_cache.isEmpty());
    
    Tsdb1xPartialTimeSeries pts = schema.newSeries(NumericLongArrayType.TYPE, 
        timestamp, 42, set, null);
    assertTrue(pts instanceof Tsdb1xNumericPartialTimeSeries);
    assertEquals(2, schema.pool_cache.size());
    assertSame(numeric_pool, schema.pool_cache.get(NumericLongArrayType.TYPE));
    assertSame(long_array_pool, schema.pool_cache.get(LongArrayPool.TYPE_TOKEN));
    
    // cache hit
    pts = schema.newSeries(NumericLongArrayType.TYPE, 
        timestamp, 42, set, null);
    assertTrue(pts instanceof Tsdb1xNumericPartialTimeSeries);
    assertEquals(2, schema.pool_cache.size());
    
    // summary
    pts = schema.newSeries(NumericByteArraySummaryType.TYPE, 
        timestamp, 42, set, mock(DefaultRollupInterval.class));
    assertTrue(pts instanceof Tsdb1xNumericSummaryPartialTimeSeries);
    assertEquals(4, schema.pool_cache.size());
    assertSame(summary_pool, schema.pool_cache.get(NumericByteArraySummaryType.TYPE));
    assertSame(byte_array_pool, schema.pool_cache.get(ByteArrayPool.TYPE_TOKEN));
    
    try {
      schema.newSeries(AnnotationType.TYPE, 
          timestamp, 42, set, mock(DefaultRollupInterval.class));
      fail("Expected IllegalStateException");
    } catch (IllegalStateException e) { }
    
    try {
      schema.newSeries(null, timestamp, 42, set, null);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    try {
      schema.newSeries(NumericLongArrayType.TYPE, null, 42, set, null);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    try {
      schema.newSeries(NumericLongArrayType.TYPE, timestamp, 42, null, null);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
  }
}
