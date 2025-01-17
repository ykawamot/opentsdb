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

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;

import net.opentsdb.core.MockTSDB;
import net.opentsdb.utils.JSON;

import static org.junit.Assert.*;

public class TestTagValueWildcardFilterAndFactory {
  private static final String TAGK = "host";
  
  @Test
  public void parse() throws Exception {
    MockTSDB tsdb = new MockTSDB();
    TagValueWildcardFactory factory = new TagValueWildcardFactory();
    String json = "{\"tagKey\":\"host\",\"filter\":\"web*\"}";
    
    JsonNode node = JSON.getMapper().readTree(json);
    TagValueWildcardFilter filter = (TagValueWildcardFilter) 
        factory.parse(tsdb, JSON.getMapper(), node);
    assertEquals("host", filter.getTagKey());
    assertEquals("web*", filter.getFilter());
    assertFalse(filter.matchesAll());
    
    json = "{\"key\":\"host\",\"filter\":\"web*\"}";
    
    node = JSON.getMapper().readTree(json);
    filter = (TagValueWildcardFilter) 
        factory.parse(tsdb, JSON.getMapper(), node);
    assertEquals("host", filter.getTagKey());
    assertEquals("web*", filter.getFilter());
    assertFalse(filter.matchesAll());
    
    try {
      factory.parse(tsdb, JSON.getMapper(), null);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    // no filter
    json = "{\"tagKey\":\"host\"}";
    node = JSON.getMapper().readTree(json);
    try {
      factory.parse(tsdb, JSON.getMapper(), node);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    // no tag key
    json = "{\"filter\":\"web01|web02\"}";
    node = JSON.getMapper().readTree(json);
    try {
      factory.parse(tsdb, JSON.getMapper(), node);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
  }
  
  @Test
  public void builderAndMatches() throws Exception {
    Map<String, String> tags = new HashMap<String, String>(1);
    tags.put(TAGK, "ogg-01.ops.ankh.morpork.com");
    
    TagValueWildcardFilter filter = TagValueWildcardFilter.newBuilder()
        .setKey("host")
        .setFilter("*.morpork.com")
        .build();
    assertEquals("host", filter.getTagKey());
    assertEquals("*.morpork.com", filter.getFilter());
    assertEquals(1, filter.components().length);
    assertFalse(filter.matchesAll());
    assertTrue(filter.matches(tags));
    
    filter = TagValueWildcardFilter.newBuilder()
        .setKey("host")
        .setFilter("ogg*")
        .build();
    assertEquals("host", filter.getTagKey());
    assertEquals("ogg*", filter.getFilter());
    assertEquals(1, filter.components().length);
    assertFalse(filter.matchesAll());
    assertTrue(filter.matches(tags));
    
    filter = TagValueWildcardFilter.newBuilder()
        .setKey("host")
        .setFilter("*")
        .build();
    assertEquals("host", filter.getTagKey());
    assertEquals("*", filter.getFilter());
    assertEquals(1, filter.components().length);
    assertTrue(filter.matchesAll());
    assertTrue(filter.matches(tags));
    
    filter = TagValueWildcardFilter.newBuilder()
        .setKey("host")
        .setFilter("ogg*com")
        .build();
    assertEquals("host", filter.getTagKey());
    assertEquals("ogg*com", filter.getFilter());
    assertFalse(filter.matchesAll());
    assertTrue(filter.matches(tags));
    
    filter = TagValueWildcardFilter.newBuilder()
        .setKey("host")
        .setFilter("ogg*ops*ank*com")
        .build();
    assertEquals("host", filter.getTagKey());
    assertEquals("ogg*ops*ank*com", filter.getFilter());
    assertFalse(filter.matchesAll());
    assertTrue(filter.matches(tags));
    
    filter = TagValueWildcardFilter.newBuilder()
        .setKey("host")
        .setFilter("ogg*ops*com")
        .build();
    assertEquals("host", filter.getTagKey());
    assertEquals("ogg*ops*com", filter.getFilter());
    assertFalse(filter.matchesAll());
    assertTrue(filter.matches(tags));
    
    filter = TagValueWildcardFilter.newBuilder()
        .setKey("host")
        .setFilter("*morpork*")
        .build();
    assertEquals("host", filter.getTagKey());
    assertEquals("*morpork*", filter.getFilter());
    assertFalse(filter.matchesAll());
    assertTrue(filter.matches(tags));
    
    filter = TagValueWildcardFilter.newBuilder()
        .setKey("host")
        .setFilter("*ops*com")
        .build();
    assertEquals("host", filter.getTagKey());
    assertEquals("*ops*com", filter.getFilter());
    assertFalse(filter.matchesAll());
    assertTrue(filter.matches(tags));
    
    filter = TagValueWildcardFilter.newBuilder()
        .setKey("host")
        .setFilter("ogg***com")
        .build();
    assertEquals("host", filter.getTagKey());
    assertEquals("ogg***com", filter.getFilter());
    assertFalse(filter.matchesAll());
    assertTrue(filter.matches(tags));
    
    // trim
    filter = TagValueWildcardFilter.newBuilder()
        .setKey("host")
        .setFilter(" ogg*ops*com ")
        .build();
    assertEquals("host", filter.getTagKey());
    assertEquals(" ogg*ops*com ", filter.getFilter());
    assertFalse(filter.matchesAll());
    assertTrue(filter.matches(tags));
        
    try {
      TagValueWildcardFilter.newBuilder()
        //.setKey("host")
        .setFilter("ogg*ops*com")
        .build();
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    try {
      TagValueWildcardFilter.newBuilder()
        .setKey("")
        .setFilter("ogg*ops*com")
        .build();
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    try {
      TagValueWildcardFilter.newBuilder()
        .setKey("host")
        //.setFilter("web01")
        .build();
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    try {
      TagValueWildcardFilter.newBuilder()
        .setKey("host")
        .setFilter("")
        .build();
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    
    // no wildcard
    try {
      TagValueWildcardFilter.newBuilder()
        .setKey("host")
        .setFilter("web01")
        .build();
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
  }

  @Test
  public void serialize() throws Exception {
    TagValueWildcardFilter filter = TagValueWildcardFilter.newBuilder()
        .setKey("host")
        .setFilter("*.morpork.com")
        .build();
    
    final String json = JSON.serializeToString(filter);
    assertTrue(json.contains("\"filter\":\"*.morpork.com\""));
    assertTrue(json.contains("\"tagKey\":\"host"));
    assertTrue(json.contains("\"type\":\"TagValueWildcard"));
  }
  
  @Test
  public void initialize() throws Exception {
    TagValueWildcardFilter filter = TagValueWildcardFilter.newBuilder()
        .setKey("host")
        .setFilter("*.morpork.com")
        .build();
    assertNull(filter.initialize(null).join());
  }

  @Test
  public void equality() throws Exception {
    TagValueWildcardFilter filter = TagValueWildcardFilter.newBuilder()
            .setKey("host")
            .setFilter("*.morpork.com")
            .build();

    TagValueWildcardFilter filter2 = TagValueWildcardFilter.newBuilder()
            .setKey("host")
            .setFilter("*.morpork.com")
            .build();

    TagValueWildcardFilter filter3 = TagValueWildcardFilter.newBuilder()
            .setKey("host2")
            .setFilter("*.morpork.com")
            .build();

    assertTrue(filter.equals(filter2));
    assertTrue(!filter.equals(filter3));
    assertEquals(filter.hashCode(), filter2.hashCode());
    assertNotEquals(filter.hashCode(), filter3.hashCode());

    filter = TagValueWildcardFilter.newBuilder()
            .setKey("host")
            .setFilter("ogg*ops*ank*com")
            .build();

    filter2 = TagValueWildcardFilter.newBuilder()
            .setKey("host")
            .setFilter("ogg*ops*ank*com")
            .build();

    assertTrue(filter.equals(filter2));
    assertEquals(filter.hashCode(), filter2.hashCode());

  }
  
}
