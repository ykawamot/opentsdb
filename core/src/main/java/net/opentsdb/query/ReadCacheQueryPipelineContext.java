// This file is part of OpenTSDB.
// Copyright (C) 2017-2020  The OpenTSDB Authors.
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
package net.opentsdb.query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.stumbleupon.async.Callback;
import com.stumbleupon.async.Deferred;

import net.opentsdb.common.Const;
import net.opentsdb.core.TSDB;
import net.opentsdb.data.PartialTimeSeries;
import net.opentsdb.data.TimeSeriesDataSource;
import net.opentsdb.data.TimeStamp;
import net.opentsdb.query.TimeSeriesQuery.CacheMode;
import net.opentsdb.query.execution.serdes.JsonV2QuerySerdesOptions;
import net.opentsdb.query.plan.DefaultQueryPlanner;
import net.opentsdb.query.processor.downsample.DownsampleConfig;
import net.opentsdb.query.processor.downsample.DownsampleFactory;
import net.opentsdb.query.processor.summarizer.Summarizer;
import net.opentsdb.query.processor.summarizer.SummarizerConfig;
import net.opentsdb.query.processor.summarizer.SummarizerFactory;
import net.opentsdb.query.processor.topn.TopNConfig;
import net.opentsdb.query.readcache.CombinedCachedResult;
import net.opentsdb.query.readcache.QueryReadCache;
import net.opentsdb.query.readcache.ReadCacheCallback;
import net.opentsdb.query.readcache.ReadCacheKeyGenerator;
import net.opentsdb.query.readcache.ReadCacheQueryResult;
import net.opentsdb.query.readcache.ReadCacheQueryResultSet;
import net.opentsdb.query.serdes.SerdesOptions;
import net.opentsdb.stats.Span;
import net.opentsdb.stats.StatsCollector;
import net.opentsdb.utils.Bytes;
import net.opentsdb.utils.DateTime;
import net.opentsdb.utils.JSON;

public class ReadCacheQueryPipelineContext extends AbstractQueryPipelineContext 
    implements ReadCacheCallback {
  static final Logger LOG = LoggerFactory.getLogger(
      ReadCacheQueryPipelineContext.class);
  
  public static final String SEGMENTS_HIT = "query.cache.segments.hit";
  public static final String SEGMENTS_MISS = "query.cache.segments.miss";
  public static final String SEGMENTS_CLEAR = "query.cache.segments.delete";
  public static final String SEGMENTS_UNCACHEABLE = "query.cache.segments.uncacheable";
  public static final String SEGMENTS_CACHED = "query.cache.segments.cached";
  public static final String SKIPS = "query.cache.skip";
  public static final String FULL_QUERY = "query.cache.fullQuery";
  public static final String[] NULL_TAGS = (String[]) null;
  
  public static final String CACHE_PLUGIN_KEY = "tsd.query.cache.plugin_id";
  public static final String KEYGEN_PLUGIN_KEY = "tsd.query.cache.keygen.plugin_id";
  
  protected final StatsCollector stats;
  protected final long current_time;
  protected final long original_query_hash;
  protected int[] slices;
  protected int interval_in_seconds;
  protected String string_interval;
  protected int min_interval;
  protected boolean skip_cache;
  protected QueryReadCache cache;
  protected ReadCacheKeyGenerator key_gen;
  protected boolean tip_query;
  protected byte[][] keys;
  protected long[] expirations;
  protected ResultOrSubQuery[] results;
  protected AtomicInteger cache_latch;
  protected AtomicInteger hits;
  protected AtomicBoolean failed;
  protected QueryContext full_query_context;
  protected List<QueryResult> sub_results;
  protected Map<String, QueryNode> summarizer_node_map;
  
  ReadCacheQueryPipelineContext(final QueryContext context, 
                                final List<QuerySink> direct_sinks) {
    super(context);
    stats = context.tsdb().getStatsCollector();
    if (direct_sinks != null && !direct_sinks.isEmpty()) {
      sinks.addAll(direct_sinks);
    }
    failed = new AtomicBoolean();
    current_time = DateTime.currentTimeMillis();
    original_query_hash = context.query().buildHashCode().asLong();
  }
  
  @Override
  public Deferred<Void> initialize(final Span span) {
    registerConfigs(context.tsdb());
    final TimeSeriesQuery original_query = context.query();
    
    cache = context.tsdb().getRegistry().getPlugin(QueryReadCache.class, 
        context.tsdb().getConfig().getString(CACHE_PLUGIN_KEY));
    if (cache == null) {
      throw new IllegalArgumentException("No cache plugin found for: " +
          (Strings.isNullOrEmpty(
              context.tsdb().getConfig().getString(CACHE_PLUGIN_KEY)) ? 
                  "Default" : context.tsdb().getConfig().getString(CACHE_PLUGIN_KEY)));
    }
    key_gen = context.tsdb().getRegistry()
        .getPlugin(ReadCacheKeyGenerator.class, 
            context.tsdb().getConfig().getString(KEYGEN_PLUGIN_KEY));
    if (key_gen == null) {
      throw new IllegalArgumentException("No key gen plugin found for: " + 
          (Strings.isNullOrEmpty(
              context.tsdb().getConfig().getString(KEYGEN_PLUGIN_KEY)) ? 
                  "Default" : context.tsdb().getConfig().getString(KEYGEN_PLUGIN_KEY)));
    }
    
    // TODO - pull this out into another shared function.
    // For now we find the highest common denominator for intervals.
    
    // TODO - issue: If we have a downsample of 1w, we can't query on 1 day segments
    // so we either cache the whole shebang or we bypass the cache.
    interval_in_seconds = 0;
    int ds_interval = Integer.MAX_VALUE;
    final QueryNodeFactory ds_factory = context.tsdb().getRegistry()
        .getQueryNodeFactory(DownsampleFactory.TYPE);
    if (ds_factory == null) {
      LOG.error("Unable to find a factory for the downsampler.");
    }
    if (((DownsampleFactory) ds_factory).intervals() == null) {
      LOG.error("No auto intervals for the downsampler.");
    }
    
    // This will hold our mutated downsamplers. After we look at all of them we
    // need to settle on a common resolution for proper caching since we cache
    // everything right now, not on a per time series basis.
    List<QueryNodeConfig> downsamplers = null;
    final List<QueryNodeConfig> execution_graph = 
        Lists.newArrayList(context.query().getExecutionGraph());
    Iterator<QueryNodeConfig> iterator = execution_graph.iterator();
    while (iterator.hasNext()) {
      QueryNodeConfig config = iterator.next();
      if (config instanceof TopNConfig) {
        skip_cache = true;
        LOG.warn("Skipping cache as we had a TOPN query.");
        break;
      }
      
      if (config instanceof TimeSeriesDataSourceConfig) {
        final List<String> types = ((TimeSeriesDataSourceConfig) config).getTypes();
        if (types != null && !types.isEmpty()) {
          for (final String type : types) {
            if (!type.equalsIgnoreCase("METRIC")) {
              skip_cache = true;
              LOG.warn("Skipping cache as we had a query for type: " + type);
            }
          }
        }
        
        if (skip_cache) {
          break;
        }
      }
      
      if (config instanceof DownsampleConfig) {
        String interval;
        if (((DownsampleConfig) config).getRunAll()) {
          skip_cache = true;
          break;
        } else if (((DownsampleConfig) config).getOriginalInterval()
            .toLowerCase().equals("auto")) {
          final long delta = context.query().endTime().msEpoch() - 
              context.query().startTime().msEpoch();
          interval = DownsampleFactory.getAutoInterval(delta, 
              ((DownsampleFactory) ds_factory).intervals(), null);
          
          iterator.remove();
          if (downsamplers == null) {
            downsamplers = Lists.newArrayList();
          }
          downsamplers.add(config);
        } else {
          // normal interval
          interval = ((DownsampleConfig) config).getInterval();
        }
        
        int parsed = (int) DateTime.parseDuration(interval) / 1000;
        if (parsed < ds_interval) {
          ds_interval = parsed;
        }
        
        if (parsed > interval_in_seconds) {
          interval_in_seconds = parsed;
          string_interval = interval;
        }
      }
    }
    
    if (interval_in_seconds >= 86400) {
      skip_cache = true;
      LOG.warn("Skipping cache for now as we have a rollup query.");
    }
    
    if (skip_cache) {
      // don't bother doing anything else.
      stats.incrementCounter(SKIPS, NULL_TAGS);
      return Deferred.fromResult(null);
    }

    if (downsamplers != null) {
      for (int i = 0; i < downsamplers.size(); i++) {
        DownsampleConfig config = ((DownsampleConfig.Builder) downsamplers.get(i).toBuilder())
            .setInterval(string_interval)
            .setSources(downsamplers.get(i).getSources())
            .setId(downsamplers.get(i).getId())
            .build();
        execution_graph.add(config);
      }
    }
    
    class CB implements Callback<Void, ArrayList<Void>> {
      final int ds_interval;
      final Set<String> serdes_filter;
      
      CB(final int ds_interval, final Set<String> serdes_filter) {
        this.ds_interval = ds_interval;
        this.serdes_filter = serdes_filter;
      }
      
      @Override
      public Void call(ArrayList<Void> arg) throws Exception {
        // TODO - in the future use rollup config. For now snap to one day.
        // AND 
        if (interval_in_seconds >= 3600) {
          interval_in_seconds = 86400;
          string_interval = "1d";
        } else {
          interval_in_seconds = 3600;
          string_interval = "1h";
        }
        
        if (ds_interval == Integer.MAX_VALUE) {
          min_interval = 0;
        } else {
          min_interval = ds_interval;
        }
        
        // TODO - validate calendaring. May need to snap differently based on timezone.
        long start = context.query().startTime().epoch();
        start = start - (start % interval_in_seconds);
        
        long end = context.query().endTime().epoch();
        end = end - (end % interval_in_seconds);
        if (end != context.query().endTime().epoch()) {
          end += interval_in_seconds;
        }
        
        slices = new int[(int) ((end - start) / interval_in_seconds)];
        int ts = (int) start;
        for (int i = 0; i < slices.length; i++) {
          slices[i] = ts;
          ts += interval_in_seconds;
        }
        
        expirations = new long[slices.length];
        expirations[0] = min_interval * 1000; // needs to be in millis
        
        // TODO - blah. This will let us zoom in or change the query time range
        // properly but it's ugly. E.g. if we start with a daily view, we may
        // auto downsample to 5 minutes. But if the user then zooms into a couple
        // hour view, we want 1 minute data. Tried changing the query earlier
        // but that breaks a few things.
        long hash = hash(ds_interval); 
        keys = key_gen.generate(hash, 
            string_interval, slices, expirations);
        if (tip_query) {
          keys = Arrays.copyOf(keys, keys.length - 1);
        }
        results = new ResultOrSubQuery[slices.length];
        
        if (context.query().getCacheMode() == CacheMode.CLEAR) {
          LOG.info("Clearing cache for query " 
              + hash
              + " at timestamps " + Arrays.toString(slices));
          context.logInfo("Clearing cache for query " 
              + hash
              + " at timestamps " + Arrays.toString(slices));
          cache.delete(slices, keys);
          stats.incrementCounter(SEGMENTS_CLEAR, keys.length, NULL_TAGS);
          skip_cache = true;
          // restore the original query so we don't have to worry about putting
          // summaries back, etc.
          ((BaseQueryContext) context).resetQuery((SemanticQuery) original_query);
          return null;
        }
        
        if (context.sinkConfigs() != null) {
          for (final QuerySinkConfig config : context.sinkConfigs()) {
            final QuerySinkFactory factory = context.tsdb().getRegistry()
                .getPlugin(QuerySinkFactory.class, config.getId());
            if (factory == null) {
              throw new IllegalArgumentException("No sink factory found for: " 
                  + config.getId());
            }
            
            final QuerySink sink = factory.newSink(context, config);
            if (sink == null) {
              throw new IllegalArgumentException("Factory returned a null sink for: " 
                  + config.getId());
            }
            sinks.add(sink);
            if (sinks.size() > 1) {
              throw new UnsupportedOperationException("Only one sink allowed for now, sorry!");
            }
          }
        }
        
        CacheQueryPlanner planner = new CacheQueryPlanner(
            ReadCacheQueryPipelineContext.this, 
            (QueryNode) ReadCacheQueryPipelineContext.this);
        
        planner.plan(null);
        Map<String, List<QueryResultId>> result_ids = Maps.newHashMap(); 
        for (QueryResultId id : planner.serializationSources()) {
          countdowns.put(id, new AtomicInteger(sinks.size()));
          List<QueryResultId> ids = result_ids.get(id.nodeID());
          if (ids == null) {
            ids = Lists.newArrayList();
            result_ids.put(id.nodeID(), ids);
          }
          ids.add(id);
        }
        
        // Now we need to re-link the summarizer to the context node.
        if (summarizer_node_map != null) {
          final Set<QueryNode> summarizers = Sets.newHashSet();
          for (final Entry<String, QueryNode> entry : summarizer_node_map.entrySet()) {
            summarizers.add(entry.getValue());
          }
          
          for (final QueryNode node : summarizers) {
            for (final String src: (List<String>) node.config().getSources()) {
              final List<QueryResultId> ids = result_ids.get(src);
              for (final QueryResultId id : ids) {
                countdowns.put(
                    new DefaultQueryResultId(node.config().getId(),  id.dataSource()), 
                      new AtomicInteger(sinks.size()));
              }
            }
          }
        }
        
        if (context.query().isTraceEnabled()) {
          context.logTrace("Cache timestamps=" + Arrays.toString(slices));
          StringBuilder buf = new StringBuilder().append("Cache keys=[");
          for (int i = 0; i < keys.length; i++) {
            if (i > 0) {
              buf.append(",");
            }
            buf.append(Arrays.toString(keys[i]));
          }
          buf.append("]");
          context.logTrace(buf.toString());
        }
        return null;
      }
      
    }
    
    // strip summarizers as we need to join the underlying results first and then
    // sum over all of them. If we run the full query, we need to tweak the query
    // to get the data feeding into the summarizer and cache *that*, not the
    // summary.
    
    // TODO - handle the case wherein a summary is in the middle of a DAG. That
    // could happen. For now we assume it's always at the end.
    final Map<String, QueryNodeConfig> summarizers = Maps.newHashMap();
    iterator = execution_graph.iterator();
    while (iterator.hasNext()) {
      QueryNodeConfig config = iterator.next();
      if (config instanceof SummarizerConfig) {
        summarizers.put(config.getId(), config);
        iterator.remove();
      }
    }
    
    Set<String> old_serdes_filters = Sets.newHashSet();
    if (!summarizers.isEmpty()) {
      SemanticQuery.Builder builder = ((SemanticQuery) context.query()).toBuilder();
      Set<String> new_serdes_filter = Sets.newHashSet();
      if (context.query().getSerdesConfigs() != null && 
          !context.query().getSerdesConfigs().isEmpty()) {
        // TODO - genercize for all types of filters
        // TODO - handle multiple filters
        // add summarizer sources
        for (final QueryNodeConfig config : summarizers.values()) {
          // ******* WTF? figure this out
          for (final Object source : config.getSources()) {
            new_serdes_filter.add((String) source);
          }
        }
        
        for (final SerdesOptions config : context.query().getSerdesConfigs()) {
          for (final String id : config.getFilter()) {
            old_serdes_filters.add(id);
            if (!summarizers.containsKey(id)) {
              new_serdes_filter.add(id);
            }
          }
        }
        
        builder.setExecutionGraph(execution_graph);
        builder.setSerdesConfigs(Lists.newArrayList(
            JsonV2QuerySerdesOptions.newBuilder()
                .setFilter(Lists.newArrayList(new_serdes_filter))
                .setId("serdes")
                .build()
            ));
        ((BaseQueryContext) context).resetQuery(builder.build());
      }
      
      // now compute the DAG
      summarizer_node_map = Maps.newHashMap();
      QueryNodeFactory factory = context.tsdb().getRegistry().getQueryNodeFactory(
          SummarizerFactory.TYPE);
      if (factory == null) {
        throw new IllegalStateException("No factory for summary??");
      }
      
      ArrayList<Deferred<Void>> deferreds = Lists.newArrayList();
      for (QueryNodeConfig config : summarizers.values()) {
        // pass through or not?
        boolean pass_through = false;
        for (final Object source : config.getSources()) {
          if (new_serdes_filter.contains((String) source) && 
              old_serdes_filters.contains((String) source)) {
            pass_through = true;
            break;
          }
        }
        
        if (pass_through) {
          config = ((SummarizerConfig) config).toBuilder()
              .setPassThrough(true)
              .setSources(config.getSources())
              .build();
        }
        
        final QueryNode summarizer = factory.newNode(this, config);
        for (final Object source : config.getSources()) {
          summarizer_node_map.put((String) source, summarizer);
        }
        deferreds.add(summarizer.initialize(span));
      }
      return Deferred.group(deferreds).addCallback(new CB(ds_interval, new_serdes_filter));
    } else if (downsamplers != null) {
      // make sure to filter out stuff we don't need.
      if (context.query().getSerdesConfigs() != null && 
          !context.query().getSerdesConfigs().isEmpty()) {
        for (final SerdesOptions config : context.query().getSerdesConfigs()) {
          for (final String id : config.getFilter()) {
            old_serdes_filters.add(id);
          }
        }
      }
      SemanticQuery.Builder builder = ((SemanticQuery) context.query()).toBuilder()
          .setExecutionGraph(execution_graph);
      ((BaseQueryContext) context).resetQuery(builder.build());
    } else {
      // make sure to filter out stuff we don't need.
      if (context.query().getSerdesConfigs() != null && 
          !context.query().getSerdesConfigs().isEmpty()) {
        for (final SerdesOptions config : context.query().getSerdesConfigs()) {
          for (final String id : config.getFilter()) {
            old_serdes_filters.add(id);
          }
        }
      }
    }
    
    try {
      return Deferred.fromResult(new CB(ds_interval, old_serdes_filters).call(null));
    } catch (Exception e) {
      return Deferred.fromError(e);
    }
  }

  @Override
  public void fetchNext(final Span span) {
    if (skip_cache) {
      throw new RuntimeException("We shouldn't be here as we're skipping the cache!!");
    }
    hits = new AtomicInteger();
    cache_latch = new AtomicInteger(slices.length);
    try {
      cache.fetch(this, keys, this, null);
    } catch(Throwable t) {
      onCacheError(-1, t);
    }
  }
  
  @Override
  public void onCacheResult(final ReadCacheQueryResultSet result) {
    if (failed.get()) {
      return;
    }
    
    try {
      ResultOrSubQuery ros = null;
      int idx = 0;
      for (int i = 0; i < keys.length; i++) {
        if (Bytes.memcmp(keys[i], result.key()) == 0) {
          synchronized (results) {
            results[i] = new ResultOrSubQuery(i);
            results[i].key = result.key();
            if (result.results() != null && !result.results().isEmpty()) {
              results[i].map = Maps.newHashMapWithExpectedSize(
                  result.results().size());
              for (final Entry<QueryResultId, ReadCacheQueryResult> entry : 
                  result.results().entrySet()) {
                results[i].map.put(entry.getKey(), entry.getValue());
              }
            }
          }
          ros = results[i];
          idx = i;
          break;
        }
      }
      
      if (ros == null) {
        onCacheError(-1, new RuntimeException("Whoops, got a result that wasn't in "
            + "our keys? " + Arrays.toString(result.key())));
        return;
      }
      
      if (ros.map == null || ros.map.isEmpty()) {
        // TODO - configure the threshold
        if (okToRunMisses(hits.get())) {
          if (LOG.isTraceEnabled()) {
            LOG.trace("Running sub query for interval at: " + slices[idx]);
          }
          if (query().isTraceEnabled()) {
            context.logTrace("Running sub query for interval at: " + slices[idx]);
          }
          //latch.incrementAndGet();
          ros.sub_context = ros.sub_context = buildQuery(slices[idx], 
              slices[idx] + interval_in_seconds, context, ros);
          ros.sub_context.initialize(null)
            .addCallback(new SubQueryCB(ros.sub_context))
            .addErrback(new ErrorCB());
        }
      } else {
        if (context.query().isTraceEnabled()) {
          context.logTrace("Cache hit for timestamp: " + slices[idx]);
        }
        if (okToRunMisses(hits.incrementAndGet())) {
          runCacheMissesAfterSatisfyingPercent();
        }
        if (requestTip(idx, result.lastValueTimestamp())) {
          ros.map = null;
          if (okToRunMisses(hits.get())) {
            if (LOG.isTraceEnabled()) {
              LOG.trace("Running sub query for interval at: " + slices[idx]);
            }
            if (query().isTraceEnabled()) {
              context.logTrace("Running sub query for interval at: " + slices[idx]);
            }
            if (context.query().isTraceEnabled()) {
              context.logTrace("Querying for more recent data at timestamp: " 
            + slices[idx]);
            }
            ros.sub_context = ros.sub_context = buildQuery(slices[idx], 
                slices[idx] + interval_in_seconds, context, ros);
            ros.sub_context.initialize(null)
              .addCallback(new SubQueryCB(ros.sub_context))
              .addErrback(new ErrorCB());
          }
        } else {
          ros.complete.set(true);
        }
      }
      
      if (cache_latch.decrementAndGet() == 0) {
        // all cache are in, see if we should send up or if we need to fire
        // sub queries.
        processResults();
      }
    } catch (Throwable t) {
      onCacheError(-1, t);
    }
  }

  @Override
  public void onCacheError(final int index, final Throwable t) {
    if (failed.compareAndSet(false, true)) {
      LOG.warn("Failure from cache", t);
      //onError(t);
      context.logError("Cache exception, running full query: " + t.getMessage());
      if (full_query_context != null) {
        throw new IllegalStateException("Unexpected exception: "
            + "SUB CONTEXT != null?");
      }
      full_query_context = buildQuery(
          slices[0], 
          slices[slices.length - 1] + interval_in_seconds, 
          context, 
          new FullQuerySink());
      if (query().isTraceEnabled()) {
        context.logTrace("Full query: " + JSON.serializeToString(
            full_query_context.query()));
      }
      full_query_context.initialize(null)
          .addCallback(new SubQueryCB(full_query_context))
          .addErrback(new ErrorCB());
      stats.incrementCounter(FULL_QUERY, NULL_TAGS);
      // while that's running, release the old resources
      cleanup();
    } else if (LOG.isDebugEnabled()) {
      LOG.debug("Failure from cache after initial failure", t);
    }
  }
  
  @Override
  public void close() {
    cleanup();
    try {
      super.close();
    } catch (Throwable t) {
      LOG.warn("failed to close super", t);
    }
  }
  
  void processResults() {
    final int local_hits = hits.get();
    if (local_hits < keys.length) {
      for (int i = 0; i < results.length; i++) {
        if (!results[i].complete.get()) {
          if (okToRunMisses(local_hits)) {
            runCacheMissesAfterSatisfyingPercent();
          } else {
            // We failed the cache threshold so we run a FULL query.
            if (LOG.isTraceEnabled()) {
              LOG.trace("Too many cache misses: " + (slices.length - local_hits) 
                  + " out of " + slices.length + "; running the full query.");
            }
            if (query().isTraceEnabled()) {
              context.logTrace("Too many cache misses: " + 
                  (slices.length - local_hits) + " out of " + slices.length 
                  + "; running the full query.");
            }
            if (full_query_context != null) {
              throw new IllegalStateException("Unexpected exception: "
                  + "SUB CONTEXT != null?");
            }
            full_query_context = buildQuery(
                slices[0], 
                slices[slices.length - 1] + interval_in_seconds, 
                context, 
                new FullQuerySink());
            if (query().isTraceEnabled()) {
              context.logTrace("Full query: " + JSON.serializeToString(
                  full_query_context.query()));
            }
            
            full_query_context.initialize(null)
                .addCallback(new SubQueryCB(full_query_context))
                .addErrback(new ErrorCB());
            stats.incrementCounter(FULL_QUERY, NULL_TAGS);
            // while that's running, release the old resources
            cleanup();
          }
          return;
        }
      }
    }
    
    if (!rosqComplete()) {
      return;
    }
    
    stats.incrementCounter(SEGMENTS_HIT, local_hits, NULL_TAGS);
    stats.incrementCounter(SEGMENTS_MISS, slices.length - local_hits, NULL_TAGS);
    // all sub queries in, ready to go.
    try {
      // sort and merge
      Map<QueryResultId, QueryResult[]> sorted = Maps.newHashMap();
      for (int i = 0; i < results.length; i++) {
        if (results[i] == null || results[i].map == null) {
          continue;
        }
        
        for (final Entry<QueryResultId, QueryResult> entry : results[i].map.entrySet()) {
          QueryResult[] qrs = sorted.get(entry.getKey());
          if (qrs == null) {
            qrs = new QueryResult[results.length + (tip_query ? 1 : 0)];
            sorted.put(entry.getKey(), qrs);
          }
          qrs[i] = entry.getValue();
        }
      }
      
      for (final Entry<QueryResultId, QueryResult[]> results : sorted.entrySet()) {
        if (results.getValue() == null) {
          continue;
        }
        // TODO - implement
        // TODO - send in thread pool
        QueryNode first_node = null;
        QueryResultId data_source = null;
        for (int i = 0; i < results.getValue().length; i++) {
          if (results.getValue()[i] == null) {
            continue;
          }
          
          if (results.getValue()[i].source() != null) {
            first_node = results.getValue()[i].source();
            data_source = results.getValue()[i].dataSource();
            // TODO ^^^^^^^^^^^^^
            break;
          }
        }
        
        if (first_node == null) {
          throw new IllegalStateException("Where's my node??");
        }
        final QueryResult result = new CombinedCachedResult(
            this, 
            results.getValue(), 
            first_node, 
            data_source, 
            sinks, 
            string_interval);
        final QueryNode summarizer = summarizer_node_map != null ? 
            summarizer_node_map.get(first_node.config().getId()) : null; 
        if (summarizer != null) {
          summarizer.onNext(result);
        } else {
          onNext(result);
        }
      }
      
      for (int i = 0; i < results.length; i++) {
        final int x = i;
        if (results[i].sub_context == null) {
          continue;
        }
        
        // write to the cache
        if (context.query().getCacheMode() == CacheMode.NORMAL ||
            context.query().getCacheMode() == CacheMode.WRITEONLY) {
          if (results[i].sub_context.cacheable()) {
            if (context.query().isTraceEnabled()) {
              context.logTrace("Caching sub segment at time: " + slices[i]);
            }
            context.tsdb().getQueryThreadPool().submit(new Runnable() {
              @Override
              public void run() {
                cache.cache(slices[x], keys[x], expirations[x], 
                    results[x].map.values(), null)
                .addBoth(new Callback<Void, Void>() {
                  @Override
                  public Void call(Void arg) throws Exception {
                    stats.incrementCounter(SEGMENTS_CACHED, keys.length, NULL_TAGS);
                    if (results[x].map != null) {
                      for (final QueryResult result : results[x].map.values()) {
                        if (result != null) {
                          try {
                            result.close();
                          } catch (Exception e) {
                            LOG.warn("Failed to close result: " + result, e);
                          }
                        }
                      }
                    }
                    try {
                      results[x].sub_context.close();
                    } catch (Exception e) {
                      LOG.warn("Failed to close sub context: " + results[x].sub_context, e);
                    }
                    return null;
                  }
                });
              }
            }, context);
          } else {
            stats.incrementCounter(SEGMENTS_UNCACHEABLE, keys.length, NULL_TAGS);
            if (context.query().isTraceEnabled()) {
              context.logTrace("Skipping cache at timestamp " + slices[i] 
                  + " as it was marked uncacheable.");
            }
            if (results[x].map != null) {
              for (final QueryResult result : results[x].map.values()) {
                if (result != null) {
                  try {
                    result.close();
                  } catch (Exception e) {
                    LOG.warn("Failed to close result: " + result, e);
                  }
                }
              }
            }
            try {
              results[x].sub_context.close();
            } catch (Exception e) {
              LOG.warn("Failed to close sub context: " + results[x].sub_context, e);
            }
            if (LOG.isDebugEnabled()) {
              LOG.debug("Skipping caching of sub query as it wasn't cacheable.");
            }
          }
        }
      }
    
    } catch (Throwable t) {
      LOG.error("Failed to process results", t);
      onError(t);
    }
  }
  
  @Override
  public Collection<QueryNode> upstream(final QueryNode node) {
    return Lists.newArrayList(this);
  }
  
  @Override
  public Collection<QueryNode> downstream(final QueryNode node) {
    return Collections.emptyList();
  }
  
  @Override
  public Collection<TimeSeriesDataSource> downstreamSources(final QueryNode node) {
    return Collections.emptyList();
  }
  
  /** @return if we found a query we couldn't cache. */
  public boolean skipCache() {
    return skip_cache;
  }
  
  class ResultOrSubQuery implements QuerySink {
    final int idx;
    byte[] key;
    QueryContext sub_context;
    volatile Map<QueryResultId, QueryResult> map = Maps.newConcurrentMap();
    AtomicBoolean complete = new AtomicBoolean();
    
    ResultOrSubQuery(final int idx) {
      this.idx = idx;
    }
    
    @Override
    public void onComplete() {
      if (failed.get()) {
        return;
      }
      
      if (sub_context != null && sub_context.logs() != null) {
        ((BaseQueryContext) context).appendLogs(sub_context.logs());
      }
      
      complete.compareAndSet(false, true);  
      if (rosqComplete()) {
        processResults();
      }
    }
    
    @Override
    public void onNext(final QueryResult next) {
      if (failed.get()) {
        return;
      }
      
      // don't cache summaries so we avoid reading them out.
      final QueryResult result = 
          new CachePipelineResultWrapper(next, sub_context.cacheable());
      if (!(result.source() instanceof Summarizer)) {
        final QueryResultId id = result.dataSource();
        if (map == null) {
          synchronized (this) {
            if (map == null) {
              map = Maps.newConcurrentMap();
            }
          }
        }
        map.put(id, result);
      }
      
      if (next instanceof ResultWrapper) {
        ((ResultWrapper) next).closeWrapperOnly();
      }
    }
    
    @Override
    public void onNext(final PartialTimeSeries next, 
                       final QuerySinkCallback callback) {
      // TODO Auto-generated method stub
    }
    
    @Override
    public void onError(final Throwable t) {
      if (failed.compareAndSet(false, true)) {
        ReadCacheQueryPipelineContext.this.onError(t);
      } else {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Failure in sub query after initial failure", t);
        }
      }
    }
    
  }
  
  static QueryContext buildQuery(final int start, 
                                 final int end, 
                                 final QueryContext context, 
                                 final QuerySink sink) {
    final SemanticQuery.Builder builder = ((SemanticQuery) context.query())
        .toBuilder()
        .setCacheMode(CacheMode.BYPASS)
        // TODO - PADDING compute the padding
        .setStart(Integer.toString(start - 300))
        .setEnd(Integer.toString(end));
    
    return SemanticQueryContext.newBuilder()
        .setTSDB(context.tsdb())
        .setLocalSinks((List<QuerySink>) Lists.newArrayList(sink))
        .setQuery(builder.build())
        .setStats(context.stats())
        .setAuthState(context.authState())
        .setHeaders(context.headers())
        .build();
  }

  void registerConfigs(final TSDB tsdb) {
    // TODO - factory
    synchronized (tsdb.getConfig()) {
      if (!tsdb.getConfig().hasProperty(CACHE_PLUGIN_KEY)) {
        tsdb.getConfig().register(CACHE_PLUGIN_KEY, null, true, 
            "The ID of a cache plugin to use.");
      }
      if (!tsdb.getConfig().hasProperty(KEYGEN_PLUGIN_KEY)) {
        tsdb.getConfig().register(KEYGEN_PLUGIN_KEY, null, true,
            "The ID of a key generator plugin to use.");
      }
    }
  }
  
  void runCacheMissesAfterSatisfyingPercent() {
    synchronized (results) {
      for (int i = 0; i < results.length; i++) {
        final ResultOrSubQuery ros = results[i];
        if (ros == null) {
          continue;
        }
        
        if (ros.sub_context == null && 
            (ros.map == null || ros.map.isEmpty())) {
          ros.sub_context = buildQuery(slices[i], slices[i] + 
              interval_in_seconds, context, ros);
          ros.sub_context.initialize(null)
            .addCallback(new SubQueryCB(ros.sub_context))
            .addErrback(new ErrorCB());
        }
      }
    }
  }
  
  boolean okToRunMisses(final int hits) {
    // TODO - configure the 60 pct
    return hits > 0 && ((double) hits / (double) keys.length) > .60;
  }
  
  boolean requestTip(final int index, final TimeStamp ts) {
    // if the index is earlier than the final two buckets then we know we
    // don't need to request any data as it's old enough.
    if (index < results.length - 3 || ts == null) {
      return false;
    }
    
    if (current_time - ts.msEpoch() > (min_interval * 1000)) {
      return false;
    }
    return true;
  }
  
  void cleanup() {
    if (results == null) {
      return;
    }
    
    for (int i = 0; i < results.length; i++) {
      if (results[i] == null) {
        continue;
      }
      
      if (results[i].map != null) {
        for (final QueryResult result : results[i].map.values()) {
          try {
            result.close();
          } catch (Throwable t) {
            LOG.warn("Failed to close result", t);
          }
        }
      }
      
      results[i].map = null;
      if (results[i].sub_context != null) {
        try {
          results[i].sub_context.close();
        } catch (Throwable t) {
          LOG.warn("Failed to close sub context", t);
        }
      }
    }
  }
  
  class SubQueryCB implements Callback<Void, Void> {
    final QueryContext context;
    
    SubQueryCB(final QueryContext context) {
      this.context = context;
    }
    
    @Override
    public Void call(final Void arg) throws Exception {
      context.fetchNext(null);
      return null;
    }
    
  }
  
  class ErrorCB implements Callback<Void, Exception> {

    @Override
    public Void call(final Exception e) throws Exception {
      if (failed.compareAndSet(false, true)) {
        onError(e);
      } else {
        LOG.warn("Failure in sub query after initial failure", e);
      }
      return null;
    }
    
  }

  class FullQuerySink implements QuerySink {

    @Override
    public void onComplete() {
      // no-op
      if (full_query_context != null && full_query_context.logs() != null) {
        ((BaseQueryContext) context).appendLogs(full_query_context.logs());
      }
      
    }
    
    @Override
    public void onNext(final QueryResult next) {
      final QueryResult result = new CachePipelineResultWrapper(next, 
          full_query_context.cacheable());
      if (result.source() instanceof Summarizer) {
        ReadCacheQueryPipelineContext.this.onNext(result);
        return;
      }
      
      synchronized (ReadCacheQueryPipelineContext.this) {
        if (sub_results == null) {
          sub_results = Lists.newArrayList();
        }
        sub_results.add(result);
      }
      
      final QueryNode summarizer = summarizer_node_map != null ? 
          summarizer_node_map.get(result.source().config().getId()) : null;
      if (summarizer != null) {
        summarizer.onNext(result);
      } else {
        ReadCacheQueryPipelineContext.this.onNext(result);
      }
    }
    
    @Override
    public void onNext(final PartialTimeSeries next, 
                       final QuerySinkCallback callback) {
      // TODO Auto-generated method stub
    }
    
    @Override
    public void onError(final Throwable t) {
      if (failed.compareAndSet(false, true)) {
        ReadCacheQueryPipelineContext.this.onError(t);
      } else {
        LOG.warn("Failure in main query after initial failure", t);
      }
    }
    
  }
  
  boolean rosqComplete() {
    if (cache_latch.get() != 0) {
      return false;
    }
    
    int non_null = 0;
    int complete = 0;
    for (int i = 0; i < results.length; i++) {
      if (results[i] == null) {
        continue;
      }
      
      non_null++;
      if (results[i].complete.get()) {
        complete++;
      }
    }
    
    return complete == non_null;
  }
  
  @Override
  protected boolean checkComplete() {
    if (super.checkComplete()) {
      if (full_query_context != null && 
          (context.query().getCacheMode() == CacheMode.NORMAL ||
           context.query().getCacheMode() == CacheMode.WRITEONLY)) {
        if (full_query_context.cacheable()) {
          if (context.query().isTraceEnabled()) {
            context.logTrace("Caching full query");
          }
          context.tsdb().getQueryThreadPool().submit(new Runnable() {
            @Override
            public void run() {
              try {
                cache.cache(slices, keys, expirations, sub_results, null)
                  .addBoth(new Callback<Void, Void>() {
                    @Override
                    public Void call(Void arg) throws Exception {
                      stats.incrementCounter(SEGMENTS_CACHED, NULL_TAGS);
                      for (final QueryResult result : sub_results) {
                        if (result != null) {
                          try {
                            result.close();
                          } catch (Exception e) {
                            LOG.warn("Failed to close result: " + result, e);
                          }
                        }
                      }
                      try {
                        full_query_context.close();
                      } catch (Exception e) {
                        LOG.warn("Failed to close full query context", e);
                      }
                      return null;
                    }
                  });
              } catch (Throwable t) {
                LOG.error("Failed to cache the data", t);
              }
            }
          }, context);
        } else {
          stats.incrementCounter(SEGMENTS_UNCACHEABLE, NULL_TAGS);
          if (context.query().isTraceEnabled()) {
            context.logTrace("Not caching full query as it was marked uncacheable.");
          }
          if (LOG.isDebugEnabled()) {
            LOG.debug("Will not cache full query as it's marked as not cacheable.");
          }
          for (final QueryResult result : sub_results) {
            if (result != null) {
              try {
                result.close();
              } catch (Exception e) {
              }
            }
          }
        }
      }
      return true;
    }
    return false;
  }
  
  protected long hash(final int ds_interval) {
    return Const.HASH_FUNCTION().newHasher()
        .putLong(original_query_hash)
        .putInt(ds_interval)
        .hash()
        .asLong();
  }

  class CacheQueryPlanner extends DefaultQueryPlanner {

    public CacheQueryPlanner(QueryPipelineContext context, QueryNode context_sink) {
      super(context, context_sink);
      // TODO Auto-generated constructor stub
    }
    
    @Override
    public Deferred<Void> plan(final Span span) {
      buildInitialConfigGraph();
      setupConfigGraph();
      verifySinkFilters();
      computeSerializationSources();
      return Deferred.fromResult(null);
    }
  }

  class CachePipelineResultWrapper extends BaseWrappedQueryResult {

    private final AtomicInteger references;
    public CachePipelineResultWrapper(final QueryResult result,
                                      final boolean will_be_cached) {
      super(result.source(), result);
      references = new AtomicInteger(will_be_cached ? 2 : 1);
    }
    
    @Override
    public void close() {
      if (references.decrementAndGet() <= 0) {
        result.close();
      }
    }
    
    @Override
    public QueryResultId dataSource() {
      return result.dataSource();
    }
  }
}