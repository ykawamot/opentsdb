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
package net.opentsdb.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.time.temporal.ChronoUnit;

import org.junit.Test;

import net.opentsdb.common.Const;
import net.opentsdb.core.TSDB;
import net.opentsdb.data.pbuf.TimeSeriesPB;
import net.opentsdb.data.pbuf.TimeSpecificationPB;
import net.opentsdb.data.pbuf.TimeStampPB;
import net.opentsdb.data.pbuf.QueryResultPB.QueryResult;
import net.opentsdb.exceptions.SerdesException;
import net.opentsdb.query.QueryNode;
import net.opentsdb.query.QueryPipelineContext;
import net.opentsdb.query.serdes.PBufSerdesFactory;

public class TestPBufQueryResult {

  @Test
  public void ctorStream() throws Exception {
    TSDB tsdb = mock(TSDB.class);
    QueryPipelineContext context = mock(QueryPipelineContext.class);
    when(context.tsdb()).thenReturn(tsdb);
    
    QueryResult pbuf = QueryResult.newBuilder()
        .setResolution(2)
        .setSequenceId(42)
        .setNodeId("ds")
        .addTimeseries(TimeSeriesPB.TimeSeries.newBuilder())
        .addTimeseries(TimeSeriesPB.TimeSeries.newBuilder())
        .setTimeSpecification(TimeSpecificationPB.TimeSpecification.newBuilder()
        .setStart(TimeStampPB.TimeStamp.newBuilder()
            .setEpoch(1514764800)
            .setNanos(500)
            .setZoneId("UTC")
            .build())
        .setEnd(TimeStampPB.TimeStamp.newBuilder()
            .setEpoch(1514768400)
            .setNanos(250)
            .setZoneId("UTC")
            .build())
        .setTimeZone("America/Denver")
        .setInterval("1h"))
        .build();
    PBufSerdesFactory factory = new PBufSerdesFactory();
    QueryNode node = mock(QueryNode.class);
    when(node.pipelineContext()).thenReturn(context);
    ByteArrayInputStream bais = new ByteArrayInputStream(pbuf.toByteArray());
    
    PBufQueryResult result = new PBufQueryResult(factory, node, null, bais);
    assertEquals("1h", result.timeSpecification().stringInterval());
    assertEquals(2, result.timeSeries().size());
    assertEquals(2, result.timeSeries().size());
    assertEquals(42, result.sequenceId());
    assertEquals("ds", result.source().config().getId());
    assertEquals(Const.TS_STRING_ID, result.idType());
    assertEquals(ChronoUnit.MILLIS, result.resolution());
    verify(context, times(2)).tsdb();
  }
  
  @Test
  public void ctorObj() throws Exception {
    TSDB tsdb = mock(TSDB.class);
    QueryPipelineContext context = mock(QueryPipelineContext.class);
    when(context.tsdb()).thenReturn(tsdb);
    
    QueryResult pbuf = QueryResult.newBuilder()
        .setResolution(2)
        .setSequenceId(42)
        .addTimeseries(TimeSeriesPB.TimeSeries.newBuilder())
        .addTimeseries(TimeSeriesPB.TimeSeries.newBuilder())
        .setTimeSpecification(TimeSpecificationPB.TimeSpecification.newBuilder()
        .setStart(TimeStampPB.TimeStamp.newBuilder()
            .setEpoch(1514764800)
            .setNanos(500)
            .setZoneId("UTC")
            .build())
        .setEnd(TimeStampPB.TimeStamp.newBuilder()
            .setEpoch(1514768400)
            .setNanos(250)
            .setZoneId("UTC")
            .build())
        .setTimeZone("America/Denver")
        .setInterval("1h"))
        .build();
    PBufSerdesFactory factory = new PBufSerdesFactory();
    QueryNode node = mock(QueryNode.class);
    when(node.pipelineContext()).thenReturn(context);
    
    PBufQueryResult result = new PBufQueryResult(factory, node, null, pbuf);
    assertEquals("1h", result.timeSpecification().stringInterval());
    assertEquals(2, result.timeSeries().size());
    assertEquals(2, result.timeSeries().size());
    assertEquals(42, result.sequenceId());
    assertSame(node, result.source());
    assertEquals(Const.TS_STRING_ID, result.idType());
    assertEquals(ChronoUnit.MILLIS, result.resolution());
    verify(context, times(2)).tsdb();
  }
  
  @Test
  public void ctorEmpty() throws Exception {
    QueryResult pbuf = QueryResult.newBuilder()
        .setResolution(2)
        .setSequenceId(42)
        .setNodeId("ds")
        .build();
    PBufSerdesFactory factory = new PBufSerdesFactory();
    QueryNode node = mock(QueryNode.class);
    ByteArrayInputStream bais = new ByteArrayInputStream(pbuf.toByteArray());
    
    PBufQueryResult result = new PBufQueryResult(factory, node, null, bais);
    assertNull(result.timeSpecification());
    assertTrue(result.timeSeries().isEmpty());
    assertEquals(42, result.sequenceId());
    assertEquals("ds", result.source().config().getId());
    assertEquals(Const.TS_STRING_ID, result.idType());
    assertEquals(ChronoUnit.MILLIS, result.resolution());
  }
  
  @Test
  public void ctorBadData() throws Exception {
    PBufSerdesFactory factory = new PBufSerdesFactory();
    QueryNode node = mock(QueryNode.class);
    ByteArrayInputStream bais = new ByteArrayInputStream(
        new byte[] { 42, 3, 0 });
    
    try {
      new PBufQueryResult(factory, node, null, bais);
      fail("Expected SerdesException");
    } catch (SerdesException e) { }
  }
}
