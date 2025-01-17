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
package net.opentsdb.query.filter;

import static org.junit.Assert.*;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Maps;

import net.opentsdb.core.MockTSDB;
import net.opentsdb.query.filter.ChainFilter.FilterOp;
import net.opentsdb.query.filter.UTFilterFactory.UTQueryFilter;
import net.opentsdb.utils.JSON;

public class TestExplicitTagsFilterAndFactory {

  @Test
  public void parse() throws Exception {
    String json = "{\"type\":\"ExplicitTags\",\"filter\":"
        + "{\"type\":\"TagValueLiteralOr\",\"tagKey\":\"host\",\"filter\":\"web01|web02\"}}";
    
    MockTSDB tsdb = new MockTSDB();
    TagValueLiteralOrFactory tv_factory = new TagValueLiteralOrFactory();
    ExplicitTagsFilterFactory exp_factory = new ExplicitTagsFilterFactory();
    when(tsdb.registry.getPlugin(QueryFilterFactory.class, "TagValueLiteralOr"))
      .thenReturn(tv_factory);
    when(tsdb.registry.getPlugin(QueryFilterFactory.class, "ExplicitTags"))
      .thenReturn(exp_factory);
    
    JsonNode node = JSON.getMapper().readTree(json);
    ExplicitTagsFilter filter = (ExplicitTagsFilter) exp_factory.parse(tsdb, JSON.getMapper(),
            node);
    assertTrue(filter.getFilter() instanceof TagValueLiteralOrFilter);
    assertEquals("web01|web02", ((TagValueLiteralOrFilter) 
        filter.getFilter()).getFilter());
    
    // no type
    json = "{\"type\":\"ExplicitTags\",\"filter\":"
        + "{\"tagKey\":\"host\",\"filter\":\"web01|web02\"}}";
    node = JSON.getMapper().readTree(json);
    try {
      exp_factory.parse(tsdb, JSON.getMapper(), node);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    // no filter
    json = "{\"type\":\"ExplicitTags\",\"filter\":null}";
    node = JSON.getMapper().readTree(json);
    try {
      exp_factory.parse(tsdb, JSON.getMapper(), node);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
  }
  
  @Test
  public void build() throws Exception {
    ExplicitTagsFilter filter = ExplicitTagsFilter.newBuilder()
        .setFilter(TagValueLiteralOrFilter.newBuilder()
            .setFilter("web01")
            .setKey("host")
            .build())
        .build();
    assertEquals(1, filter.tagKeys().size());
    assertTrue(filter.tagKeys().contains("host"));
    
    try {
      ExplicitTagsFilter.newBuilder().build();
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
  }
  
  @Test
  public void matches() throws Exception {
    Map<String, String> tags = Maps.newHashMap();
    tags.put("host", "web01");
    
    ExplicitTagsFilter filter = ExplicitTagsFilter.newBuilder()
        .setFilter(TagValueLiteralOrFilter.newBuilder()
            .setFilter("web01")
            .setKey("host")
            .build())
        .build();
    assertTrue(filter.matches(tags));
    
    // doesn't care about the value.
    tags.put("host", "web02");
    assertTrue(filter.matches(tags));
    
    // now we fail.
    tags.put("owner", "tyrion");
    assertFalse(filter.matches(tags));
    
    // chain
    filter = ExplicitTagsFilter.newBuilder()
        .setFilter(ChainFilter.newBuilder()
            .setOp(FilterOp.AND)
            .addFilter(TagValueLiteralOrFilter.newBuilder()
              .setFilter("web01")
              .setKey("host")
              .build())
            .addFilter(TagValueLiteralOrFilter.newBuilder()
              .setFilter("tyrion")
              .setKey("owner")
              .build())
            .build())
        .build();
    assertTrue(filter.matches(tags));
    
    // missing one
    tags.remove("owner");
    assertFalse(filter.matches(tags));
    
    // nested nested
    tags.put("owner", "tyrion");
    tags.put("dc", "phx");
    filter = ExplicitTagsFilter.newBuilder()
        .setFilter(ChainFilter.newBuilder()
            .setOp(FilterOp.AND)
            .addFilter(TagValueLiteralOrFilter.newBuilder()
              .setFilter("web01")
              .setKey("host")
              .build())
            .addFilter(ChainFilter.newBuilder()
                .addFilter(TagValueLiteralOrFilter.newBuilder()
                  .setFilter("tyrion")
                  .setKey("owner")
                  .build())
                .addFilter(TagValueLiteralOrFilter.newBuilder()
                  .setFilter("lga")
                  .setKey("dc")
                  .build())
                .build())
            .build())
        .build();
    assertTrue(filter.matches(tags));
    
    // duplicates
    filter = ExplicitTagsFilter.newBuilder()
        .setFilter(ChainFilter.newBuilder()
            .setOp(FilterOp.AND)
            .addFilter(TagValueLiteralOrFilter.newBuilder()
              .setFilter("web01")
              .setKey("host")
              .build())
            .addFilter(ChainFilter.newBuilder()
                .addFilter(TagValueLiteralOrFilter.newBuilder()
                  .setFilter("tyrion")
                  .setKey("owner")
                  .build())
                .addFilter(TagValueLiteralOrFilter.newBuilder()
                  .setFilter("web02")
                  .setKey("host")
                  .build())
                .build())
            .build())
        .build();
    assertFalse(filter.matches(tags));
    
    // not filter is ignored
    filter = ExplicitTagsFilter.newBuilder()
        .setFilter(ChainFilter.newBuilder()
            .setOp(FilterOp.AND)
            .addFilter(TagValueLiteralOrFilter.newBuilder()
              .setFilter("web01")
              .setKey("host")
              .build())
            .addFilter((QueryFilter) NotFilter.newBuilder()
                .setFilter(ChainFilter.newBuilder()
                  .addFilter(TagValueLiteralOrFilter.newBuilder()
                    .setFilter("tyrion")
                    .setKey("owner")
                    .build())
                  .addFilter(TagValueLiteralOrFilter.newBuilder()
                    .setFilter("lga")
                    .setKey("dc")
                    .build())
                  .build())
                .build())
            .build())
        .build();
    assertFalse(filter.matches(tags));
    
    // no tags
    filter = ExplicitTagsFilter.newBuilder()
        .setFilter(MetricLiteralFilter.newBuilder()
            .setMetric("sys.cpu.user")
            .build())
        .build();
    assertFalse(filter.matches(tags));
    
    // ok now
    tags.clear();
    assertTrue(filter.matches(tags));
  }
  
  @Test
  public void serialize() throws Exception {
    ExplicitTagsFilter filter = ExplicitTagsFilter.newBuilder()
        .setFilter(TagValueLiteralOrFilter.newBuilder()
            .setFilter("web01")
            .setKey("host")
            .build())
        .build();
    
    final String json = JSON.serializeToString(filter);
    assertTrue(json.contains("\"filter\":\"web01\""));
    assertTrue(json.contains("\"tagKey\":\"host"));
    assertTrue(json.contains("\"type\":\"TagValueLiteralOr"));
    assertTrue(json.contains("\"type\":\"ExplicitTags"));
  }

  @Test
  public void initialize() throws Exception {
    UTQueryFilter filter_a = spy(new UTQueryFilter("host", "web01|web02"));
    
    ExplicitTagsFilter filter = ExplicitTagsFilter.newBuilder()
        .setFilter(filter_a)
        .build();
    
    assertNull(filter.initialize(null).join());
    verify(filter_a, times(1)).initialize(null);
  }


  @Test
  public void equality() throws Exception {
    ExplicitTagsFilter filter = ExplicitTagsFilter.newBuilder()
            .setFilter(ChainFilter.newBuilder()
                    .setOp(FilterOp.AND)
                    .addFilter(TagValueLiteralOrFilter.newBuilder()
                            .setFilter("web01")
                            .setKey("host")
                            .build())
                    .addFilter(TagValueLiteralOrFilter.newBuilder()
                            .setFilter("tyrion")
                            .setKey("owner")
                            .build())
                    .build())
            .build();

    ExplicitTagsFilter filter2 = ExplicitTagsFilter.newBuilder()
            .setFilter(ChainFilter.newBuilder()
                    .setOp(FilterOp.AND)
                    .addFilter(TagValueLiteralOrFilter.newBuilder()
                            .setFilter("web01")
                            .setKey("host")
                            .build())
                    .addFilter(TagValueLiteralOrFilter.newBuilder()
                            .setFilter("tyrion")
                            .setKey("owner")
                            .build())
                    .build())
            .build();

    ExplicitTagsFilter filter3 = ExplicitTagsFilter.newBuilder()
            .setFilter(ChainFilter.newBuilder()
                    .setOp(FilterOp.AND)
                    .addFilter(TagValueLiteralOrFilter.newBuilder()
                            .setFilter("web02")
                            .setKey("host")
                            .build())
                    .addFilter(TagValueLiteralOrFilter.newBuilder()
                            .setFilter("tyrion")
                            .setKey("owner")
                            .build())
                    .build())
            .build();

    Map<String, String> tags = Maps.newHashMap();
    tags.put("host", "web02");
    tags.put("owner", "val");
    assertTrue(filter.matches(tags));

    assertTrue(filter.equals(filter2));
    assertTrue(!filter.equals(filter3));
    assertEquals(filter.hashCode(), filter2.hashCode());
    assertNotEquals(filter.hashCode(), filter3.hashCode());


  }
  
}
