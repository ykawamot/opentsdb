// This file is part of OpenTSDB.
// Copyright (C) 2017  The OpenTSDB Authors.
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
package net.opentsdb.query.processor;

import com.google.common.collect.Maps;
import com.google.common.reflect.TypeToken;
import com.stumbleupon.async.Deferred;
import net.opentsdb.core.BaseTSDBPlugin;
import net.opentsdb.core.TSDB;
import net.opentsdb.data.TimeSeries;
import net.opentsdb.data.TimeSeriesDataType;
import net.opentsdb.data.TypedTimeSeriesIterator;
import net.opentsdb.query.AbstractQueryNode;
import net.opentsdb.query.BaseQueryNodeConfig;
import net.opentsdb.query.QueryIteratorFactory;
import net.opentsdb.query.QueryNodeConfig;
import net.opentsdb.query.QueryNodeFactory;
import net.opentsdb.query.QueryPipelineContext;
import net.opentsdb.query.QueryResult;
import net.opentsdb.query.plan.QueryPlanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;

/**
 * A simple base class for implementing {@link QueryNodeFactory}s. It maintains
 * a map of types to factories that can be registered by accessing the factory
 * from the registry.
 * 
 * @since 3.0
 */
public abstract class BaseQueryNodeFactory<C extends QueryNodeConfig, N extends AbstractQueryNode>
    extends BaseTSDBPlugin implements ProcessorFactory<C, N> {
  private final Logger LOG = LoggerFactory.getLogger(getClass());
  
  /** The map of iterator factories keyed on type. */
  protected final Map<TypeToken<? extends TimeSeriesDataType>, QueryIteratorFactory> iterator_factories;
  
  /**
   * Default ctor.
   */
  public BaseQueryNodeFactory() {
    iterator_factories = Maps.newHashMapWithExpectedSize(3);
  }
  
  @Override
  public Collection<TypeToken<? extends TimeSeriesDataType>> types() {
    return iterator_factories.keySet();
  }

  @Override
  public <T extends TimeSeriesDataType> void registerIteratorFactory(
      final TypeToken<? extends TimeSeriesDataType> type,
      final QueryIteratorFactory<N, T> factory) {
    if (type == null) {
      throw new IllegalArgumentException("Type cannot be null.");
    }
    if (factory == null) {
      throw new IllegalArgumentException("Factory cannot be null.");
    }
    if (iterator_factories.containsKey(type)) {
      LOG.warn("Replacing existing iterator factory: " + 
          iterator_factories.get(type) + " with factory: " + factory);
    }
    iterator_factories.put(type, factory);
    if (LOG.isDebugEnabled()) {
      LOG.debug("Registering iteratator factory: " + factory 
          + " with type: " + type);
    }
  }

  @Override
  public <T extends TimeSeriesDataType>  TypedTimeSeriesIterator newTypedIterator(
      final TypeToken<T> type,
      final N node,
      final QueryResult result,
      final Collection<TimeSeries> sources) {
    if (type == null) {
      throw new IllegalArgumentException("Type cannot be null.");
    }
    if (node == null) {
      throw new IllegalArgumentException("Node cannot be null.");
    }
    if (sources == null || sources.isEmpty()) {
      throw new IllegalArgumentException("Sources cannot be null or empty.");
    }
    
    final QueryIteratorFactory factory = iterator_factories.get(type);
    if (factory == null) {
      return null;
    }
    return factory.newIterator(node, result, sources, type);
  }

  @Override
  public <T extends TimeSeriesDataType> TypedTimeSeriesIterator newTypedIterator(
      final TypeToken<T> type,
      final N node,
      final QueryResult result,
      final Map<String, TimeSeries> sources) {
    if (type == null) {
      throw new IllegalArgumentException("Type cannot be null.");
    }
    if (node == null) {
      throw new IllegalArgumentException("Node cannot be null.");
    }
    if (sources == null || sources.isEmpty()) {
      throw new IllegalArgumentException("Sources cannot be null or empty.");
    }
    
    final QueryIteratorFactory factory = iterator_factories.get(type);
    if (factory == null) {
      return null;
    }
    return factory.newIterator(node, result, sources, type);
  }

  // Force implementation.
  @Override
  public abstract Deferred<Object> initialize(final TSDB tsdb, final String id);

  @Override
  public N newNode(QueryPipelineContext context) {
    throw new UnsupportedOperationException();
  }

  @Override
  public N newNode(QueryPipelineContext context, C config) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setupGraph(QueryPipelineContext context, C config, QueryPlanner planner) {
  }

}
