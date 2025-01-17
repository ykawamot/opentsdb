// This file is part of OpenTSDB.
// Copyright (C) 2018-2020  The OpenTSDB Authors.
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
package net.opentsdb.query.joins;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;

import org.junit.BeforeClass;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.google.common.collect.Lists;
import com.google.common.reflect.TypeToken;

import gnu.trove.map.hash.TLongObjectHashMap;
import net.opentsdb.common.Const;
import net.opentsdb.data.BaseTimeSeriesByteId;
import net.opentsdb.data.BaseTimeSeriesStringId;
import net.opentsdb.data.TimeSeries;
import net.opentsdb.data.TimeSeriesByteId;
import net.opentsdb.data.TimeSeriesDataSourceFactory;
import net.opentsdb.data.TimeSeriesId;
import net.opentsdb.query.DefaultQueryResultId;
import net.opentsdb.query.QueryResult;
import net.opentsdb.query.joins.JoinConfig.JoinType;

public class BaseJoinTest {
  protected static final String ID = "UT";
  
  protected static final String NAMESPACE = "tsdb";
  protected static final byte[] NAMESPACE_BYTES = new byte[] { 0, 0, 1 };
  
  protected static final String METRIC_L = "sys.cpu.user";
  protected static final String METRIC_R = "sys.cpu.sys";
  protected static final byte[] METRIC_L_BYTES = new byte[] { 0, 0, 1 };
  protected static final byte[] METRIC_R_BYTES = new byte[] { 0, 0, 2 };
  
  protected static final String ALIAS_L = "downstream";
  protected static final byte[] ALIAS_L_BYTES = "downstream".getBytes(Const.UTF8_CHARSET);
  protected static final String ALIAS_R = "raw";
  protected static final byte[] ALIAS_R_BYTES = "raw".getBytes(Const.UTF8_CHARSET);
  protected static final String ALIAS2 = "UT";
  protected static final byte[] ALIAS2_BYTES = "UT".getBytes(Const.UTF8_CHARSET);
  
  protected static final String TERNARY = "subexp#0";
  protected static final byte[] TERNARY_BYTES = "subexp#0".getBytes(Const.UTF8_CHARSET);
  
  // tag key and value byte arrays.
  protected final static byte[] HOST = new byte[] { 0, 0, 1 };
  protected final static byte[] OWNER = new byte[] { 0, 0, 2 };
  protected final static byte[] DC = new byte[] { 0, 0, 3 };
  protected final static byte[] ROLE = new byte[] { 0, 0, 4 };
  protected final static byte[] UNIT = new byte[] { 0, 0, 5 };
  
  protected final static byte[] WEB01 = new byte[] { 0, 0, 1 };
  protected final static byte[] WEB02 = new byte[] { 0, 0, 2 };
  protected final static byte[] WEB03 = new byte[] { 0, 0, 3 };
  protected final static byte[] WEB04 = new byte[] { 0, 0, 4 };
  protected final static byte[] WEB05 = new byte[] { 0, 0, 5 };
  protected final static byte[] WEB06 = new byte[] { 0, 0, 6 };
  
  protected final static byte[] TYRION = new byte[] { 0, 1, 1 };
  protected final static byte[] CERSEI = new byte[] { 0, 1, 2 };
  
  // one to one match
  protected final static TimeSeries L_1 = mock(TimeSeries.class);
  protected final static TimeSeries R_1 = mock(TimeSeries.class);
  
  // only left side
  protected final static TimeSeries L_2 = mock(TimeSeries.class);
  
  // only right side
  protected final static TimeSeries R_3 = mock(TimeSeries.class);
  
  // one left, 2 right
  protected final static TimeSeries L_4 = mock(TimeSeries.class);
  protected final static TimeSeries R_4A = mock(TimeSeries.class);
  protected final static TimeSeries R_4B = mock(TimeSeries.class);
  
  // 2 left, one right
  protected final static TimeSeries L_5A = mock(TimeSeries.class);
  protected final static TimeSeries L_5B = mock(TimeSeries.class);
  protected final static TimeSeries R_5 = mock(TimeSeries.class);
  
  // 2 left, 2 right
  protected final static TimeSeries L_6A = mock(TimeSeries.class);
  protected final static TimeSeries L_6B = mock(TimeSeries.class);
  protected final static TimeSeries R_6A = mock(TimeSeries.class);
  protected final static TimeSeries R_6B = mock(TimeSeries.class);
  
  // ternary
  protected final static TimeSeries T_1 = mock(TimeSeries.class);
  protected final static TimeSeries T_2 = mock(TimeSeries.class);
  protected final static TimeSeries T_4A = mock(TimeSeries.class);
  protected final static TimeSeries T_4B = mock(TimeSeries.class);
  protected final static TimeSeries T_5A = mock(TimeSeries.class);
  protected final static TimeSeries T_5B = mock(TimeSeries.class);
  protected final static TimeSeries T_6A = mock(TimeSeries.class);
  protected final static TimeSeries T_6B = mock(TimeSeries.class);
  
  protected static TimeSeriesId TAGLESS_STRING;
  protected static TimeSeriesId TAGLESS_BYTE;
  
  protected static TimeSeriesId MANY_TAGS_STRING;
  protected static TimeSeriesId MANY_TAGS_BYTE;
  
  protected static TimeSeriesId TAG_PROMOTION_L_STRING;
  protected static TimeSeriesId TAG_PROMOTION_L_BYTE;
  protected static TimeSeriesId TAG_PROMOTION_R_STRING;
  protected static TimeSeriesId TAG_PROMOTION_R_BYTE;
  
  protected TimeSeriesId l1_id;
  protected TimeSeriesId r1_id;
  
  protected TimeSeriesId l2_id;
  
  protected TimeSeriesId r3_id;
  
  protected TimeSeriesId l4_id;
  protected TimeSeriesId r4a_id;
  protected TimeSeriesId r4b_id;
  
  protected TimeSeriesId l5a_id;
  protected TimeSeriesId l5b_id;
  protected TimeSeriesId r5_id;
  
  protected TimeSeriesId l6a_id;
  protected TimeSeriesId l6b_id;
  protected TimeSeriesId r6a_id;
  protected TimeSeriesId r6b_id;
  
  protected TimeSeriesId t_1_id;
  protected TimeSeriesId t_2_id;
  protected TimeSeriesId t_4a_id;
  protected TimeSeriesId t_4b_id;
  protected TimeSeriesId t_5a_id;
  protected TimeSeriesId t_5b_id;
  protected TimeSeriesId t_6a_id;
  protected TimeSeriesId t_6b_id;
  
  @BeforeClass
  public static void beforeClass() throws Exception {
    when(L_1.toString()).thenReturn("L_1");
    when(R_1.toString()).thenReturn("R_1");
    
    when(L_2.toString()).thenReturn("L_2");
    
    when(R_3.toString()).thenReturn("R_3");
    
    when(L_4.toString()).thenReturn("L_4");
    when(R_4A.toString()).thenReturn("R_4A");
    when(R_4B.toString()).thenReturn("R_4B");
    
    when(L_5A.toString()).thenReturn("L_5A");
    when(L_5B.toString()).thenReturn("L_5B");
    when(R_5.toString()).thenReturn("R_5");
    
    when(L_6A.toString()).thenReturn("L_6A");
    when(L_6B.toString()).thenReturn("L_6B");
    when(R_6A.toString()).thenReturn("R_6A");
    when(R_6B.toString()).thenReturn("R_6B");
    
    when(T_1.toString()).thenReturn("T_1");
    when(T_2.toString()).thenReturn("T_2");
    when(T_4A.toString()).thenReturn("T_4A");
    when(T_4B.toString()).thenReturn("T_4B");
    when(T_5A.toString()).thenReturn("T_5A");
    when(T_5B.toString()).thenReturn("T_5B");
    when(T_6A.toString()).thenReturn("T_6A");
    when(T_6B.toString()).thenReturn("T_6B");
    
    TAGLESS_STRING = BaseTimeSeriesStringId.newBuilder()
        .setAlias(ALIAS_L)
        .setNamespace(NAMESPACE)
        .setMetric(METRIC_L)
        .addAggregatedTag("host")
        .build();
    
    TAGLESS_BYTE = BaseTimeSeriesByteId.newBuilder(mock(TimeSeriesDataSourceFactory.class))
        .setAlias(ALIAS_L_BYTES)
        .setNamespace(NAMESPACE_BYTES)
        .setMetric(METRIC_L_BYTES)
        .addAggregatedTag(HOST)
        .build();
    
    MANY_TAGS_STRING = BaseTimeSeriesStringId.newBuilder()
        .setAlias(ALIAS_L)
        .setNamespace(NAMESPACE)
        .setMetric(METRIC_L)
        .addTags("host", "db01")
        .addTags("owner", "sam")
        .addTags("dc", "phx")
        .addTags("role", "db")
        .build();
    
    MANY_TAGS_BYTE = BaseTimeSeriesByteId.newBuilder(mock(TimeSeriesDataSourceFactory.class))
        .setAlias(ALIAS_L_BYTES)
        .setNamespace(NAMESPACE_BYTES)
        .setMetric(METRIC_L_BYTES)
        .addTags(HOST, WEB03)
        .addTags(OWNER, new byte[] { 0, 2, 16 })
        .addTags(DC, new byte[] { 0, 2, 4 })
        .addTags(ROLE, new byte[] { 0, 2, 9 })
        .build();
    
    TAG_PROMOTION_L_STRING = BaseTimeSeriesStringId.newBuilder()
        .setAlias(ALIAS_L)
        .setNamespace(NAMESPACE)
        .setMetric(METRIC_L)
        .addTags("host", "db01")
        .addAggregatedTag("owner")
        .addDisjointTag("dc")
        .build();
    
    TAG_PROMOTION_R_STRING = BaseTimeSeriesStringId.newBuilder()
        .setAlias(ALIAS_L)
        .setNamespace(NAMESPACE)
        .setMetric(METRIC_R)
        .addTags("host", "db02")
        .addTags("unit", "devops")
        .addAggregatedTag("dc")
        .addDisjointTag("role")
        .build();
    
    TAG_PROMOTION_L_BYTE = BaseTimeSeriesByteId.newBuilder(mock(TimeSeriesDataSourceFactory.class))
        .setAlias(ALIAS_L_BYTES)
        .setNamespace(NAMESPACE_BYTES)
        .setMetric(METRIC_L_BYTES)
        .addTags(HOST, WEB03)
        .addAggregatedTag(OWNER)
        .addDisjointTag(DC)
        .build();
    
    TAG_PROMOTION_R_BYTE = BaseTimeSeriesByteId.newBuilder(mock(TimeSeriesDataSourceFactory.class))
        .setAlias(ALIAS_L_BYTES)
        .setNamespace(NAMESPACE_BYTES)
        .setMetric(METRIC_R_BYTES)
        .addTags(HOST, WEB04)
        .addTags(UNIT, new byte[] { 1, 1, 1 })
        .addAggregatedTag(DC)
        .addAggregatedTag(ROLE)
        .build();
  }
  
  protected static BaseHashedJoinSet leftAndRightSet(final JoinType type) {
    final UTBaseHashedJoinSet set = new UTBaseHashedJoinSet(type);
    
    set.left_map = new TLongObjectHashMap<List<TimeSeries>>();
    set.right_map = new TLongObjectHashMap<List<TimeSeries>>();
    
    // one to one
    set.left_map.put(1, Lists.newArrayList(L_1));
    set.right_map.put(1, Lists.newArrayList(R_1));
    
    // left only
    set.left_map.put(2, Lists.newArrayList(L_2));
    
    // right only
    set.right_map.put(3, Lists.newArrayList(R_3));
    
    // one left, 2 right
    set.left_map.put(4, Lists.newArrayList(L_4));
    set.right_map.put(4, Lists.newArrayList(R_4A, R_4B));
    
    // 2 left, one right
    set.left_map.put(5, Lists.newArrayList(L_5A, L_5B));
    set.right_map.put(5, Lists.newArrayList(R_5));
    
    // 2 left, 2 right
    set.left_map.put(6, Lists.newArrayList(L_6A, L_6B));
    set.right_map.put(6, Lists.newArrayList(R_6A, R_6B));
    
    return set;
  }
  
  protected static BaseHashedJoinSet leftOnlySet(final JoinType type) {
    final BaseHashedJoinSet set = leftAndRightSet(type);
    set.right_map = null;
    return set;
  }
  
  protected static BaseHashedJoinSet rightOnlySet(final JoinType type) {
    final BaseHashedJoinSet set = leftAndRightSet(type);
    set.left_map = null;
    return set;
  }
  
  protected static BaseHashedJoinSet emptyMaps(final JoinType type) {
    final UTBaseHashedJoinSet set = new UTBaseHashedJoinSet(type);
    
    set.left_map = new TLongObjectHashMap<List<TimeSeries>>();
    set.right_map = new TLongObjectHashMap<List<TimeSeries>>();
    
    return set;
  }
  
  protected static BaseHashedJoinSet leftAndRightNullLists(final JoinType type) {
    final UTBaseHashedJoinSet set = new UTBaseHashedJoinSet(type);
    
    set.left_map = new TLongObjectHashMap<List<TimeSeries>>();
    set.right_map = new TLongObjectHashMap<List<TimeSeries>>();
    
    // one to one
    set.left_map.put(1, Lists.newArrayList(L_1));
    set.right_map.put(1, Lists.newArrayList(R_1));
    
    // left only
    set.left_map.put(2, Lists.newArrayList(L_2));
    
    // right only
    set.right_map.put(3, Lists.newArrayList(R_3));
    
    // one left, 2 right
    set.left_map.put(4, null);
    set.right_map.put(4, Lists.newArrayList(R_4A, R_4B));
    
    // 2 left, one right
    set.left_map.put(5, null);
    set.right_map.put(5, Lists.newArrayList(R_5));
    
    // 2 left, 2 right
    set.left_map.put(6, Lists.newArrayList(L_6A, L_6B));
    set.right_map.put(6, Lists.newArrayList(R_6A, R_6B));
    
    return set;
  }
  
  protected static BaseHashedJoinSet leftAndRightEmptyLists(final JoinType type) {
    final UTBaseHashedJoinSet set = new UTBaseHashedJoinSet(type);
    
    set.left_map = new TLongObjectHashMap<List<TimeSeries>>();
    set.right_map = new TLongObjectHashMap<List<TimeSeries>>();
    
    // one to one
    set.left_map.put(1, Lists.newArrayList(L_1));
    set.right_map.put(1, Lists.newArrayList(R_1));
    
    // left only
    set.left_map.put(2, Lists.newArrayList(L_2));
    
    // right only
    set.right_map.put(3, Lists.newArrayList(R_3));
    
    // one left, 2 right
    set.left_map.put(4, Lists.newArrayList());
    set.right_map.put(4, Lists.newArrayList(R_4A, R_4B));
    
    // 2 left, one right
    set.left_map.put(5, Lists.newArrayList());
    set.right_map.put(5, Lists.newArrayList(R_5));
    
    // 2 left, 2 right
    set.left_map.put(6, Lists.newArrayList(L_6A, L_6B));
    set.right_map.put(6, Lists.newArrayList(R_6A, R_6B));
    
    return set;
  }
  
  protected static BaseHashedJoinSet ternaryOnlySet(final JoinType type) {
    final TernaryKeyedHashedJoinSet set = new TernaryKeyedHashedJoinSet(type, 3);
    
    set.condition_map = new TLongObjectHashMap<List<TimeSeries>>();
    
    // Ternaries!
    set.condition_map.put(1, Lists.newArrayList(T_1));
    set.condition_map.put(2, Lists.newArrayList(T_2));
    set.condition_map.put(4, Lists.newArrayList(T_4A, T_4B));
    set.condition_map.put(5, Lists.newArrayList(T_5A, T_5B));
    set.condition_map.put(6, Lists.newArrayList(T_6A, T_6B));
    
    return set;
  }
  
  protected static BaseHashedJoinSet ternaryLeftOnlySet(final JoinType type) {
    final TernaryKeyedHashedJoinSet set = new TernaryKeyedHashedJoinSet(type, 3);
    
    set.left_map = new TLongObjectHashMap<List<TimeSeries>>();
    set.left_map.put(1, Lists.newArrayList(L_1));
    set.left_map.put(2, Lists.newArrayList(L_2));
    set.left_map.put(4, Lists.newArrayList(L_4));
    set.left_map.put(5, Lists.newArrayList(L_5A, L_5B));
    set.left_map.put(6, Lists.newArrayList(L_6A, L_6B));
    
    return set;
  }
  
  protected static BaseHashedJoinSet ternaryRightOnlySet(final JoinType type) {
    final TernaryKeyedHashedJoinSet set = new TernaryKeyedHashedJoinSet(type, 3);
    
    set.right_map = new TLongObjectHashMap<List<TimeSeries>>();
    set.right_map.put(1, Lists.newArrayList(R_1));
    set.right_map.put(3, Lists.newArrayList(R_3));
    set.right_map.put(4, Lists.newArrayList(R_4A, R_4B));
    set.right_map.put(5, Lists.newArrayList(R_5));
    set.right_map.put(6, Lists.newArrayList(R_6A, R_6B));
    return set;
  }
  
  protected static BaseHashedJoinSet ternaryAndLeftOnlySet(final JoinType type) {
    final TernaryKeyedHashedJoinSet set = new TernaryKeyedHashedJoinSet(type, 3);
    
    set.left_map = new TLongObjectHashMap<List<TimeSeries>>();
    set.condition_map = new TLongObjectHashMap<List<TimeSeries>>();
    set.left_map.put(1, Lists.newArrayList(L_1));
    set.left_map.put(2, Lists.newArrayList(L_2));
    set.left_map.put(4, Lists.newArrayList(L_4));
    set.left_map.put(5, Lists.newArrayList(L_5A, L_5B));
    set.left_map.put(6, Lists.newArrayList(L_6A, L_6B));
    
    // Ternaries!
    set.condition_map.put(1, Lists.newArrayList(T_1));
    set.condition_map.put(2, Lists.newArrayList(T_2));
    set.condition_map.put(4, Lists.newArrayList(T_4A, T_4B));
    set.condition_map.put(5, Lists.newArrayList(T_5A, T_5B));
    set.condition_map.put(6, Lists.newArrayList(T_6A, T_6B));
    
    return set;
  }
  
  protected static BaseHashedJoinSet ternaryAndRightOnlySet(final JoinType type) {
    final TernaryKeyedHashedJoinSet set = new TernaryKeyedHashedJoinSet(type, 3);
    
    set.right_map = new TLongObjectHashMap<List<TimeSeries>>();
    set.condition_map = new TLongObjectHashMap<List<TimeSeries>>();
    set.right_map.put(1, Lists.newArrayList(R_1));
    set.right_map.put(3, Lists.newArrayList(R_3));
    set.right_map.put(4, Lists.newArrayList(R_4A, R_4B));
    set.right_map.put(5, Lists.newArrayList(R_5));
    set.right_map.put(6, Lists.newArrayList(R_6A, R_6B));
    
    // Ternaries!
    set.condition_map.put(1, Lists.newArrayList(T_1));
    set.condition_map.put(2, Lists.newArrayList(T_2));
    set.condition_map.put(4, Lists.newArrayList(T_4A, T_4B));
    set.condition_map.put(5, Lists.newArrayList(T_5A, T_5B));
    set.condition_map.put(6, Lists.newArrayList(T_6A, T_6B));
    
    return set;
  }
  
  protected static BaseHashedJoinSet ternaryNoTernarySet(final JoinType type) {
    final TernaryKeyedHashedJoinSet set = new TernaryKeyedHashedJoinSet(type, 3);
    
    set.left_map = new TLongObjectHashMap<List<TimeSeries>>();
    set.right_map = new TLongObjectHashMap<List<TimeSeries>>();
    set.condition_map = new TLongObjectHashMap<List<TimeSeries>>();
    
    // one to one
    set.left_map.put(1, Lists.newArrayList(L_1));
    set.right_map.put(1, Lists.newArrayList(R_1));
    
    // left only
    set.left_map.put(2, Lists.newArrayList(L_2));
    
    // right only
    set.right_map.put(3, Lists.newArrayList(R_3));
    
    // one left, 2 right
    set.left_map.put(4, Lists.newArrayList(L_4));
    set.right_map.put(4, Lists.newArrayList(R_4A, R_4B));
    
    // 2 left, one right
    set.left_map.put(5, Lists.newArrayList(L_5A, L_5B));
    set.right_map.put(5, Lists.newArrayList(R_5));
    
    // 2 left, 2 right
    set.left_map.put(6, Lists.newArrayList(L_6A, L_6B));
    set.right_map.put(6, Lists.newArrayList(R_6A, R_6B));
    
    return set;
  }
  
  protected static BaseHashedJoinSet ternaryNullListsSet(final JoinType type) {
    final TernaryKeyedHashedJoinSet set = new TernaryKeyedHashedJoinSet(type, 3);
    
    set.left_map = new TLongObjectHashMap<List<TimeSeries>>();
    set.right_map = new TLongObjectHashMap<List<TimeSeries>>();
    set.condition_map = new TLongObjectHashMap<List<TimeSeries>>();
    
    // one to one
    set.left_map.put(1, Lists.newArrayList(L_1));
    set.right_map.put(1, Lists.newArrayList(R_1));
    
    // left only
    set.left_map.put(2, null);
    
    // right only
    set.right_map.put(3, Lists.newArrayList(R_3));
    
    // one left, 2 right
    set.left_map.put(4, null);
    set.right_map.put(4, Lists.newArrayList(R_4A, R_4B));
    
    // 2 left, one right
    set.left_map.put(5, Lists.newArrayList(L_5A, L_5B));
    set.right_map.put(5, Lists.newArrayList(R_5));
    
    // 2 left, 2 right
    set.left_map.put(6, null);
    set.right_map.put(6, Lists.newArrayList(R_6A, R_6B));
    
    // Ternaries!
    set.condition_map.put(1, Lists.newArrayList(T_1));
    set.condition_map.put(2, Lists.newArrayList(T_2));
    set.condition_map.put(4, Lists.newArrayList(T_4A, T_4B));
    set.condition_map.put(5, null);
    set.condition_map.put(6, Lists.newArrayList(T_6A, T_6B));
    
    return set;
  }
  
  protected static BaseHashedJoinSet ternaryEmptyListsSet(final JoinType type) {
    final TernaryKeyedHashedJoinSet set = new TernaryKeyedHashedJoinSet(type, 3);
    
    set.left_map = new TLongObjectHashMap<List<TimeSeries>>();
    set.right_map = new TLongObjectHashMap<List<TimeSeries>>();
    set.condition_map = new TLongObjectHashMap<List<TimeSeries>>();
    
    // one to one
    set.left_map.put(1, Lists.newArrayList(L_1));
    set.right_map.put(1, Lists.newArrayList(R_1));
    
    // left only
    set.left_map.put(2, Lists.newArrayList());
    
    // right only
    set.right_map.put(3, Lists.newArrayList(R_3));
    
    // one left, 2 right
    set.left_map.put(4, Lists.newArrayList());
    set.right_map.put(4, Lists.newArrayList(R_4A, R_4B));
    
    // 2 left, one right
    set.left_map.put(5, Lists.newArrayList(L_5A, L_5B));
    set.right_map.put(5, Lists.newArrayList(R_5));
    
    // 2 left, 2 right
    set.left_map.put(6, Lists.newArrayList());
    set.right_map.put(6, Lists.newArrayList(R_6A, R_6B));
    
    // Ternaries!
    set.condition_map.put(1, Lists.newArrayList(T_1));
    set.condition_map.put(2, Lists.newArrayList(T_2));
    set.condition_map.put(4, Lists.newArrayList(T_4A, T_4B));
    set.condition_map.put(5, Lists.newArrayList());
    set.condition_map.put(6, Lists.newArrayList(T_6A, T_6B));
    
    return set;
  }
  
  protected static BaseHashedJoinSet ternarySet(final JoinType type) {
    final TernaryKeyedHashedJoinSet set = new TernaryKeyedHashedJoinSet(type, 3);
    
    set.left_map = new TLongObjectHashMap<List<TimeSeries>>();
    set.right_map = new TLongObjectHashMap<List<TimeSeries>>();
    set.condition_map = new TLongObjectHashMap<List<TimeSeries>>();
    
    // one to one
    set.left_map.put(1, Lists.newArrayList(L_1));
    set.right_map.put(1, Lists.newArrayList(R_1));
    
    // left only
    set.left_map.put(2, Lists.newArrayList(L_2));
    
    // right only
    set.right_map.put(3, Lists.newArrayList(R_3));
    
    // one left, 2 right
    set.left_map.put(4, Lists.newArrayList(L_4));
    set.right_map.put(4, Lists.newArrayList(R_4A, R_4B));
    
    // 2 left, one right
    set.left_map.put(5, Lists.newArrayList(L_5A, L_5B));
    set.right_map.put(5, Lists.newArrayList(R_5));
    
    // 2 left, 2 right
    set.left_map.put(6, Lists.newArrayList(L_6A, L_6B));
    set.right_map.put(6, Lists.newArrayList(R_6A, R_6B));
    
    // Ternaries!
    set.condition_map.put(1, Lists.newArrayList(T_1));
    set.condition_map.put(2, Lists.newArrayList(T_2));
    set.condition_map.put(4, Lists.newArrayList(T_4A, T_4B));
    set.condition_map.put(5, Lists.newArrayList(T_5A, T_5B));
    set.condition_map.put(6, Lists.newArrayList(T_6A, T_6B));
    
    return set;
  }
  
  protected static List<QueryResult> singleResult(final TypeToken<?> ts_type,
                                                  final String ds) {
    final QueryResult result = mock(QueryResult.class);
    final List<TimeSeries> ts = Lists.newArrayList(
        L_1, R_1,
        L_2,
        R_3,
        L_4, R_4A, R_4B,
        L_5A, L_5B, R_5,
        L_6A, L_6B, R_6A, R_6B);
    when(result.timeSeries()).thenReturn(ts);
    when(result.idType()).thenAnswer(new Answer<TypeToken<?>>() {
      @Override
      public TypeToken<?> answer(InvocationOnMock invocation) throws Throwable {
        return ts_type;
      }
    });
    when(result.dataSource()).thenReturn(new DefaultQueryResultId(ds, ds));
    return Lists.newArrayList(result);
  }
  
  protected static List<QueryResult> multiResults(final TypeToken<?> ts_type,
                                                  final String left,
                                                  final String right) {
    final List<QueryResult> results = Lists.newArrayList();
    QueryResult result = mock(QueryResult.class);
    List<TimeSeries> ts = Lists.newArrayList(
        L_1,
        L_2,
        L_4,
        L_5A, L_5B,
        L_6A, L_6B);
    when(result.timeSeries()).thenReturn(ts);
    when(result.idType()).thenAnswer(new Answer<TypeToken<?>>() {
      @Override
      public TypeToken<?> answer(InvocationOnMock invocation) throws Throwable {
        return ts_type;
      }
    });
    when(result.dataSource()).thenReturn(new DefaultQueryResultId(left, left));
    results.add(result);
    
    // right
    result = mock(QueryResult.class);
    ts = Lists.newArrayList(
        R_1,
        R_3,
        R_4A, R_4B,
        R_5,
        R_6A, R_6B);
    when(result.timeSeries()).thenReturn(ts);
    when(result.idType()).thenAnswer(new Answer<TypeToken<?>>() {
      @Override
      public TypeToken<?> answer(InvocationOnMock invocation) throws Throwable {
        return ts_type;
      }
    });
    when(result.dataSource()).thenReturn(new DefaultQueryResultId(right, right));
    results.add(result);
    return results;
  }
  
  protected static List<QueryResult> ternaryResults(final TypeToken<?> ts_type,
                                                    final String left,
                                                    final String right,
                                                    final String ternary) {
    final List<QueryResult> results = Lists.newArrayList();
    QueryResult result = mock(QueryResult.class);
    when(result.dataSource()).thenReturn(new DefaultQueryResultId(left, left));
    List<TimeSeries> ts = Lists.newArrayList(
        L_1,
        L_2,
        L_4,
        L_5A, L_5B,
        L_6A, L_6B);
    when(result.timeSeries()).thenReturn(ts);
    when(result.idType()).thenAnswer(new Answer<TypeToken<?>>() {
      @Override
      public TypeToken<?> answer(InvocationOnMock invocation) throws Throwable {
        return ts_type;
      }
    });
    results.add(result);
    
    // right
    result = mock(QueryResult.class);
    when(result.dataSource()).thenReturn(new DefaultQueryResultId(right, right));
    ts = Lists.newArrayList(
        R_1,
        R_3,
        R_4A, R_4B,
        R_5,
        R_6A, R_6B);
    when(result.timeSeries()).thenReturn(ts);
    when(result.idType()).thenAnswer(new Answer<TypeToken<?>>() {
      @Override
      public TypeToken<?> answer(InvocationOnMock invocation) throws Throwable {
        return ts_type;
      }
    });
    results.add(result);
    
    // ternary
    result = mock(QueryResult.class);
    when(result.dataSource()).thenReturn(new DefaultQueryResultId(ternary, ternary));
    ts = Lists.newArrayList(
        T_1,
        T_2,
        T_4A, T_4B,
        T_5A, T_5B,
        T_6A, T_6B);
    when(result.timeSeries()).thenReturn(ts);
    when(result.idType()).thenAnswer(new Answer<TypeToken<?>>() {
      @Override
      public TypeToken<?> answer(InvocationOnMock invocation) throws Throwable {
        return ts_type;
      }
    });
    results.add(result);
    return results;
  }
  
  protected void setStringIds() throws Exception {
    l1_id = BaseTimeSeriesStringId.newBuilder()
        .setAlias(ALIAS_L)
        .setNamespace(NAMESPACE)
        .setMetric(METRIC_L)
        .addTags("host", "web01")
        .build();
    when(L_1.id()).thenReturn(l1_id);
    r1_id = BaseTimeSeriesStringId.newBuilder()
        .setAlias(ALIAS_R)
        .setNamespace(NAMESPACE)
        .setMetric(METRIC_R)
        .addTags("host", "web01")
        .build();
    when(R_1.id()).thenReturn(r1_id);
    
    l2_id = BaseTimeSeriesStringId.newBuilder()
        .setAlias(ALIAS_L)
        .setNamespace(NAMESPACE)
        .setMetric(METRIC_L)
        .addTags("host", "web02")
        .build();
    when(L_2.id()).thenReturn(l2_id);
    
    r3_id = BaseTimeSeriesStringId.newBuilder()
        .setAlias(ALIAS_R)
        .setNamespace(NAMESPACE)
        .setMetric(METRIC_R)
        .addTags("host", "web03")
        .build();
    when(R_3.id()).thenReturn(r3_id);
    
    l4_id = BaseTimeSeriesStringId.newBuilder()
        .setAlias(ALIAS_L)
        .setNamespace(NAMESPACE)
        .setMetric(METRIC_L)
        .addTags("host", "web04")
        .build();
    when(L_4.id()).thenReturn(l4_id);
    r4a_id = BaseTimeSeriesStringId.newBuilder()
        .setAlias(ALIAS_R)
        .setNamespace(NAMESPACE)
        .setMetric(METRIC_R)
        .addTags("host", "web04")
        .addTags("owner", "tyrion")
        .build();
    when(R_4A.id()).thenReturn(r4a_id);
    r4b_id = BaseTimeSeriesStringId.newBuilder()
        .setAlias(ALIAS_R)
        .setNamespace(NAMESPACE)
        .setMetric(METRIC_R)
        .addTags("host", "web04")
        .addTags("owner", "cersei")
        .build();
    when(R_4B.id()).thenReturn(r4b_id);
    
    l5a_id = BaseTimeSeriesStringId.newBuilder()
        .setAlias(ALIAS_L)
        .setNamespace(NAMESPACE)
        .setMetric(METRIC_L)
        .addTags("host", "web05")
        .addTags("owner", "tyrion")
        .build();
    when(L_5A.id()).thenReturn(l5a_id);
    l5b_id = BaseTimeSeriesStringId.newBuilder()
        .setAlias(ALIAS_L)
        .setNamespace(NAMESPACE)
        .setMetric(METRIC_L)
        .addTags("host", "web05")
        .addTags("owner", "cersei")
        .build();
    when(L_5B.id()).thenReturn(l5b_id);
    r5_id = BaseTimeSeriesStringId.newBuilder()
        .setAlias(ALIAS_R)
        .setNamespace(NAMESPACE)
        .setMetric(METRIC_R)
        .addTags("host", "web05")
        .build();
    when(R_5.id()).thenReturn(r5_id);
    
    l6a_id = BaseTimeSeriesStringId.newBuilder()
        .setAlias(ALIAS_L)
        .setNamespace(NAMESPACE)
        .setMetric(METRIC_L)
        .addTags("host", "web06")
        .addTags("owner", "tyrion")
        .build();
    when(L_6A.id()).thenReturn(l6a_id);
    l6b_id = BaseTimeSeriesStringId.newBuilder()
        .setAlias(ALIAS_L)
        .setNamespace(NAMESPACE)
        .setMetric(METRIC_L)
        .addTags("host", "web06")
        .addTags("owner", "cersei")
        .build();
    when(L_6B.id()).thenReturn(l6b_id);
    r6a_id = BaseTimeSeriesStringId.newBuilder()
        .setAlias(ALIAS_R)
        .setNamespace(NAMESPACE)
        .setMetric(METRIC_R)
        .addTags("host", "web06")
        .addTags("owner", "tyrion")
        .build();
    when(R_6A.id()).thenReturn(r6a_id);
    r6b_id = BaseTimeSeriesStringId.newBuilder()
        .setAlias(ALIAS_R)
        .setNamespace(NAMESPACE)
        .setMetric(METRIC_R)
        .addTags("host", "web06")
        .addTags("owner", "cersei")
        .build();
    when(R_6B.id()).thenReturn(r6b_id);
    
    t_1_id = BaseTimeSeriesStringId.newBuilder()
        .setAlias(TERNARY)
        .setNamespace(NAMESPACE)
        .setMetric(TERNARY)
        .addTags("host", "web01")
        .build();
    when(T_1.id()).thenReturn(t_1_id);
    t_2_id = BaseTimeSeriesStringId.newBuilder()
        .setAlias(TERNARY)
        .setNamespace(NAMESPACE)
        .setMetric(TERNARY)
        .addTags("host", "web02")
        .build();
    when(T_2.id()).thenReturn(t_2_id);
    t_4a_id = BaseTimeSeriesStringId.newBuilder()
        .setAlias(TERNARY)
        .setNamespace(NAMESPACE)
        .setMetric(TERNARY)
        .addTags("host", "web04")
        .addTags("owner", "tyrion")
        .build();
    when(T_4A.id()).thenReturn(t_4a_id);
    t_4b_id = BaseTimeSeriesStringId.newBuilder()
        .setAlias(TERNARY)
        .setNamespace(NAMESPACE)
        .setMetric(TERNARY)
        .addTags("host", "web04")
        .addTags("owner", "cersei")
        .build();
    when(T_4B.id()).thenReturn(t_4b_id);
    t_5a_id = BaseTimeSeriesStringId.newBuilder()
        .setAlias(TERNARY)
        .setNamespace(NAMESPACE)
        .setMetric(TERNARY)
        .addTags("host", "web05")
        .addTags("owner", "tyrion")
        .build();
    when(T_5A.id()).thenReturn(t_5a_id);
    t_5b_id = BaseTimeSeriesStringId.newBuilder()
        .setAlias(TERNARY)
        .setNamespace(NAMESPACE)
        .setMetric(TERNARY)
        .addTags("host", "web05")
        .addTags("owner", "cersei")
        .build();
    when(T_5B.id()).thenReturn(t_5b_id);
    t_6a_id = BaseTimeSeriesStringId.newBuilder()
        .setAlias(TERNARY)
        .setNamespace(NAMESPACE)
        .setMetric(TERNARY)
        .addTags("host", "web06")
        .addTags("owner", "tyrion")
        .build();
    when(T_6A.id()).thenReturn(t_6a_id);
    t_6b_id = BaseTimeSeriesStringId.newBuilder()
        .setAlias(TERNARY)
        .setNamespace(NAMESPACE)
        .setMetric(TERNARY)
        .addTags("host", "web06")
        .addTags("owner", "cersei")
        .build();
    when(T_6B.id()).thenReturn(t_6b_id);
  }
  
  protected void setByteIds() throws Exception {
    l1_id = BaseTimeSeriesByteId.newBuilder(mock(TimeSeriesDataSourceFactory.class))
        .setAlias(ALIAS_L_BYTES)
        .setNamespace(NAMESPACE_BYTES)
        .setMetric(METRIC_L_BYTES)
        .addTags(HOST, WEB01)
        .build();
    when(L_1.id()).thenReturn(l1_id);
    r1_id = BaseTimeSeriesByteId.newBuilder(mock(TimeSeriesDataSourceFactory.class))
        .setAlias(ALIAS_R_BYTES)
        .setNamespace(NAMESPACE_BYTES)
        .setMetric(METRIC_R_BYTES)
        .addTags(HOST, WEB01)
        .build();
    when(R_1.id()).thenReturn(clone(r1_id));
    
    l2_id = BaseTimeSeriesByteId.newBuilder(mock(TimeSeriesDataSourceFactory.class))
        .setAlias(ALIAS_L_BYTES)
        .setNamespace(NAMESPACE_BYTES)
        .setMetric(METRIC_L_BYTES)
        .addTags(HOST, WEB02)
        .build();
    when(L_2.id()).thenReturn(l2_id);
    
    r3_id = BaseTimeSeriesByteId.newBuilder(mock(TimeSeriesDataSourceFactory.class))
        .setAlias(ALIAS_R_BYTES)
        .setNamespace(NAMESPACE_BYTES)
        .setMetric(METRIC_R_BYTES)
        .addTags(HOST, WEB03)
        .build();
    when(R_3.id()).thenReturn(clone(r3_id));
    
    l4_id = BaseTimeSeriesByteId.newBuilder(mock(TimeSeriesDataSourceFactory.class))
        .setAlias(ALIAS_L_BYTES)
        .setNamespace(NAMESPACE_BYTES)
        .setMetric(METRIC_L_BYTES)
        .addTags(HOST, WEB04)
        .build();
    when(L_4.id()).thenReturn(l4_id);
    r4a_id = BaseTimeSeriesByteId.newBuilder(mock(TimeSeriesDataSourceFactory.class))
        .setAlias(ALIAS_R_BYTES)
        .setNamespace(NAMESPACE_BYTES)
        .setMetric(METRIC_R_BYTES)
        .addTags(HOST, WEB04)
        .addTags(OWNER, TYRION)
        .build();
    when(R_4A.id()).thenReturn(clone(r4a_id));
    r4b_id = BaseTimeSeriesByteId.newBuilder(mock(TimeSeriesDataSourceFactory.class))
        .setAlias(ALIAS_R_BYTES)
        .setNamespace(NAMESPACE_BYTES)
        .setMetric(METRIC_R_BYTES)
        .addTags(HOST, WEB04)
        .addTags(OWNER, CERSEI)
        .build();
    when(R_4B.id()).thenReturn(clone(r4b_id));
    
    l5a_id = BaseTimeSeriesByteId.newBuilder(mock(TimeSeriesDataSourceFactory.class))
        .setAlias(ALIAS_L_BYTES)
        .setNamespace(NAMESPACE_BYTES)
        .setMetric(METRIC_L_BYTES)
        .addTags(HOST, WEB05)
        .addTags(OWNER, TYRION)
        .build();
    when(L_5A.id()).thenReturn(l5a_id);
    l5b_id = BaseTimeSeriesByteId.newBuilder(mock(TimeSeriesDataSourceFactory.class))
        .setAlias(ALIAS_L_BYTES)
        .setNamespace(NAMESPACE_BYTES)
        .setMetric(METRIC_L_BYTES)
        .addTags(HOST, WEB05)
        .addTags(OWNER, CERSEI)
        .build();
    when(L_5B.id()).thenReturn(l5b_id);
    r5_id = BaseTimeSeriesByteId.newBuilder(mock(TimeSeriesDataSourceFactory.class))
        .setAlias(ALIAS_R_BYTES)
        .setNamespace(NAMESPACE_BYTES)
        .setMetric(METRIC_R_BYTES)
        .addTags(HOST, WEB05)
        .build();
    when(R_5.id()).thenReturn(clone(r5_id));
    
    l6a_id = BaseTimeSeriesByteId.newBuilder(mock(TimeSeriesDataSourceFactory.class))
        .setAlias(ALIAS_L_BYTES)
        .setNamespace(NAMESPACE_BYTES)
        .setMetric(METRIC_L_BYTES)
        .addTags(HOST, WEB06)
        .addTags(OWNER, TYRION)
        .build();
    when(L_6A.id()).thenReturn(l6a_id);
    l6b_id = BaseTimeSeriesByteId.newBuilder(mock(TimeSeriesDataSourceFactory.class))
        .setAlias(ALIAS_L_BYTES)
        .setNamespace(NAMESPACE_BYTES)
        .setMetric(METRIC_L_BYTES)
        .addTags(HOST, WEB06)
        .addTags(OWNER, CERSEI)
        .build();
    when(L_6B.id()).thenReturn(l6b_id);
    r6a_id = BaseTimeSeriesByteId.newBuilder(mock(TimeSeriesDataSourceFactory.class))
        .setAlias(ALIAS_R_BYTES)
        .setNamespace(NAMESPACE_BYTES)
        .setMetric(METRIC_R_BYTES)
        .addTags(HOST, WEB06)
        .addTags(OWNER, TYRION)
        .build();
    when(R_6A.id()).thenReturn(clone(r6a_id));
    r6b_id = BaseTimeSeriesByteId.newBuilder(mock(TimeSeriesDataSourceFactory.class))
        .setAlias(ALIAS_R_BYTES)
        .setNamespace(NAMESPACE_BYTES)
        .setMetric(METRIC_R_BYTES)
        .addTags(HOST, WEB06)
        .addTags(OWNER, CERSEI)
        .build();
    when(R_6B.id()).thenReturn(clone(r6b_id));
    
    t_1_id = BaseTimeSeriesByteId.newBuilder(mock(TimeSeriesDataSourceFactory.class))
        .setAlias(TERNARY_BYTES)
        .setNamespace(NAMESPACE_BYTES)
        .setMetric(TERNARY_BYTES)
        .addTags(HOST, WEB01)
        .build();
    when(T_1.id()).thenReturn(t_1_id);
    t_2_id = BaseTimeSeriesByteId.newBuilder(mock(TimeSeriesDataSourceFactory.class))
        .setAlias(TERNARY_BYTES)
        .setNamespace(NAMESPACE_BYTES)
        .setMetric(TERNARY_BYTES)
        .addTags(HOST, WEB02)
        .build();
    when(T_2.id()).thenReturn(t_2_id);
    t_4a_id = BaseTimeSeriesByteId.newBuilder(mock(TimeSeriesDataSourceFactory.class))
        .setAlias(TERNARY_BYTES)
        .setNamespace(NAMESPACE_BYTES)
        .setMetric(TERNARY_BYTES)
        .addTags(HOST, WEB04)
        .addTags(OWNER, TYRION)
        .build();
    when(T_4A.id()).thenReturn(t_4a_id);
    t_4b_id = BaseTimeSeriesByteId.newBuilder(mock(TimeSeriesDataSourceFactory.class))
        .setAlias(TERNARY_BYTES)
        .setNamespace(NAMESPACE_BYTES)
        .setMetric(TERNARY_BYTES)
        .addTags(HOST, WEB04)
        .addTags(OWNER, CERSEI)
        .build();
    when(T_4B.id()).thenReturn(t_4b_id);
    t_5a_id = BaseTimeSeriesByteId.newBuilder(mock(TimeSeriesDataSourceFactory.class))
        .setAlias(TERNARY_BYTES)
        .setNamespace(NAMESPACE_BYTES)
        .setMetric(TERNARY_BYTES)
        .addTags(HOST, WEB05)
        .addTags(OWNER, TYRION)
        .build();
    when(T_5A.id()).thenReturn(t_5a_id);
    t_5b_id = BaseTimeSeriesByteId.newBuilder(mock(TimeSeriesDataSourceFactory.class))
        .setAlias(TERNARY_BYTES)
        .setNamespace(NAMESPACE_BYTES)
        .setMetric(TERNARY_BYTES)
        .addTags(HOST, WEB05)
        .addTags(OWNER, CERSEI)
        .build();
    when(T_5B.id()).thenReturn(t_5b_id);
    t_6a_id = BaseTimeSeriesByteId.newBuilder(mock(TimeSeriesDataSourceFactory.class))
        .setAlias(TERNARY_BYTES)
        .setNamespace(NAMESPACE_BYTES)
        .setMetric(TERNARY_BYTES)
        .addTags(HOST, WEB06)
        .addTags(OWNER, TYRION)
        .build();
    when(T_6A.id()).thenReturn(t_6a_id);
    t_6b_id = BaseTimeSeriesByteId.newBuilder(mock(TimeSeriesDataSourceFactory.class))
        .setAlias(TERNARY_BYTES)
        .setNamespace(NAMESPACE_BYTES)
        .setMetric(TERNARY_BYTES)
        .addTags(HOST, WEB06)
        .addTags(OWNER, CERSEI)
        .build();
    when(T_6B.id()).thenReturn(t_6b_id);
  }
  
  protected static TimeSeriesId clone(final TimeSeriesId id) {
    if (id instanceof TimeSeriesByteId) {
      BaseTimeSeriesByteId.Builder builder = 
          BaseTimeSeriesByteId.newBuilder(mock(TimeSeriesDataSourceFactory.class));
      final TimeSeriesByteId byte_id = (TimeSeriesByteId) id;
      if (byte_id.alias() != null) {
        builder.setAlias(Arrays.copyOf(byte_id.alias(), byte_id.alias().length));
      }
      
      if (byte_id.namespace() != null) {
        builder.setNamespace(Arrays.copyOf(byte_id.namespace(), byte_id.namespace().length));
      }
      
      if (byte_id.metric() != null) {
        builder.setMetric(Arrays.copyOf(byte_id.metric(), byte_id.metric().length));
      }
      
      for (final Entry<byte[], byte[]> entry : byte_id.tags().entrySet()) {
        builder.addTags(Arrays.copyOf(entry.getKey(), entry.getKey().length), 
            Arrays.copyOf(entry.getValue(), entry.getValue().length));
      }
      
      for (final byte[] tag : byte_id.aggregatedTags()) {
        builder.addAggregatedTag(Arrays.copyOf(tag, tag.length));
      }
      
      for (final byte[] tag : byte_id.disjointTags()) {
        builder.addDisjointTag(Arrays.copyOf(tag, tag.length));
      }
      
      for (final byte[] tsuid : byte_id.uniqueIds()) {
        builder.addUniqueId(Arrays.copyOf(tsuid, tsuid.length));
      }
      
      return builder.build();
    }
    
    // TODO strings
    throw new UnsupportedOperationException();
  }
  
  static class UTBaseHashedJoinSet extends BaseHashedJoinSet {

    public UTBaseHashedJoinSet(final JoinType type) {
      super(type, 1, false);
    }
    
  }
   
}