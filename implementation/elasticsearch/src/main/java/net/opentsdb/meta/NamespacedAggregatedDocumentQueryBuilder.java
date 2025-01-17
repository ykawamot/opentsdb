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
package net.opentsdb.meta;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.org.apache.xpath.internal.operations.Bool;
import net.opentsdb.exceptions.QueryExecutionException;
import net.opentsdb.meta.BatchMetaQuery.QueryType;
import net.opentsdb.query.filter.AnyFieldRegexFilter;
import net.opentsdb.query.filter.ChainFilter;
import net.opentsdb.query.filter.ChainFilter.FilterOp;
import net.opentsdb.query.filter.ExplicitTagsFilter;
import net.opentsdb.query.filter.MetricFilter;
import net.opentsdb.query.filter.MetricLiteralFilter;
import net.opentsdb.query.filter.MetricRegexFilter;
import net.opentsdb.query.filter.NotFilter;
import net.opentsdb.query.filter.QueryFilter;
import net.opentsdb.query.filter.TagKeyFilter;
import net.opentsdb.query.filter.TagKeyLiteralOrFilter;
import net.opentsdb.query.filter.TagKeyRegexFilter;
import net.opentsdb.query.filter.TagValueFilter;
import net.opentsdb.query.filter.TagValueLiteralOrFilter;
import net.opentsdb.query.filter.TagValueRegexFilter;
import net.opentsdb.query.filter.TagValueWildcardFilter;
import net.opentsdb.utils.DateTime;
import org.elasticsearch.index.query.BoolFilterBuilder;
import org.elasticsearch.index.query.FilterBuilder;
import org.elasticsearch.index.query.FilterBuilders;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.terms.Terms.Order;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the ElasticSearch query
 *
 * @since 3.0
 */
public class NamespacedAggregatedDocumentQueryBuilder {
  private static final Logger LOG = LoggerFactory.getLogger(
          NamespacedAggregatedDocumentQueryBuilder.class);
  public static final String QUERY_NAMESPACE_KEY = "namespace.lowercase";
  public static final String QUERY_TAG_KEY_KEY = "tags.key.lowercase";
  public static final String QUERY_TAG_VALUE_KEY = "tags.value";
  public static final String RESULT_TAG_KEY_KEY = "key.raw";
  public static final String RESULT_TAG_VALUE_KEY = "value.raw";
  public static final String METRIC_PATH = "AM_nested";
  public static final String TAG_PATH = "tags";
  public static final String QUERY_METRIC = "AM_nested.name.lowercase";
  public static final String RESULT_METRIC = "AM_nested.name.raw";
  public static final String RESULT_NAMESPACE = "namespace.raw";

  public static final String LAST_SEEN = "lastSeenTime";
  public static final String NAMESPACE_AGG = "ns_agg";
  public static final String METRIC_AGG = "metric_agg";
  public static final String METRIC_UNIQUE = "unique_metrics";
  public static final String TAG_KEY_AGG = "tagk_agg";
  public static final String TAG_KEY_UNIQUE = "unique_tagks";
  public static final String TAG_VALUE_AGG = "tagv_agg";
  public static final String TAG_VALUE_UNIQUE = "unique_tagvs";
  public static final String TAGS_AGG = "tags_agg";
  public static final String TAGS_UNIQUE = "unique_tags";
  public static final String TAGS_SUB_AGG = "tags_sub_agg";
  public static final String TAGS_SUB_UNIQUE = "unique_sub_tags";
  private static final String TAG_KEYS_INDEX_SUFFIX = "_tagkeys";

  public final Map<NamespacedKey, List<SearchSourceBuilder>> search_source_builders;

  private final BatchMetaQuery query;

  private boolean isMultiGet;


  private NamespacedAggregatedDocumentQueryBuilder(final BatchMetaQuery query) {
    this.search_source_builders = new LinkedHashMap<>();
    this.query = query;
  }

  FilterBuilder setFilter(final QueryFilter filter, final List<FilterBuilder> metric_filters
          , final boolean nested, final String exclude_key) {
    if (filter == null) {
      return null;
    }

    if (filter instanceof ExplicitTagsFilter) {
      return setFilter(((ExplicitTagsFilter) filter).getFilter(), metric_filters, nested, exclude_key);
    }


    if (filter instanceof TagKeyFilter) {
      return getTagKeyFilter((TagKeyFilter) filter, nested);
    }

    if (filter instanceof TagValueFilter) {
      if (! ((TagValueFilter) filter).getTagKey().equals(exclude_key)) {
        return getTagValueFilter((TagValueFilter) filter, nested);
      } else {
        return null;
      }
    }

    if (filter instanceof AnyFieldRegexFilter) {
      return getAnyFieldFilter((AnyFieldRegexFilter) filter, nested);
    }

    if (filter instanceof NotFilter) {
      return FilterBuilders.boolFilter().mustNot(
          setFilter(((NotFilter) filter).getFilter(), metric_filters, nested, exclude_key));
    }

    if (filter instanceof MetricFilter) {
      metric_filters.add(getMetricFilter((MetricFilter) filter, nested));
      return null;
    }

    if (filter instanceof ChainFilter) {
      BoolFilterBuilder builder = FilterBuilders.boolFilter();
      if (((ChainFilter) filter).getOp() == FilterOp.AND) {
        for (final QueryFilter sub_filter : ((ChainFilter) filter).getFilters()) {
          if (sub_filter instanceof MetricFilter) {
            setFilter(sub_filter, metric_filters, nested, exclude_key);
          } else {
            FilterBuilder filter_builder = setFilter(sub_filter, metric_filters, nested, exclude_key);
            if (filter_builder!= null) builder.must(filter_builder);
          }
        }
      } else {
        for (final QueryFilter sub_filter : ((ChainFilter) filter).getFilters()) {
          FilterBuilder filter_builder = setFilter(sub_filter, metric_filters, nested, exclude_key);
          if (filter_builder != null) builder.should(filter_builder);
        }
      }
      return builder;
    }

    throw new UnsupportedOperationException("Unsupported filter: "
        + filter.getClass().toString() + " and " + ((MetricFilter)filter).getMetric());
  }

  FilterBuilder setBasicFilterForAgg(final QueryFilter filter) {

    if (filter instanceof AnyFieldRegexFilter) {
      return getAnyFieldFilter((AnyFieldRegexFilter) filter, false);
    }

    if (filter instanceof ChainFilter) {
      BoolFilterBuilder builder = FilterBuilders.boolFilter();
      if (((ChainFilter) filter).getOp() == FilterOp.AND) {
        for (final QueryFilter sub_filter : ((ChainFilter) filter).getFilters()) {
          FilterBuilder b = setBasicFilterForAgg(sub_filter);
          if (b != null) builder.must(b);
        }
      } else {
        for (final QueryFilter sub_filter : ((ChainFilter) filter).getFilters()) {
          FilterBuilder b = setBasicFilterForAgg(sub_filter);
          if (b != null) builder.should(b);
        }
      }
      if (builder.hasClauses()) {
        return builder;
      } else return null;
    }
    return null;
  }

  FilterBuilder getMetricFilter(final MetricFilter filter, final boolean nested) {
    if (filter instanceof MetricLiteralFilter) {
      String metric = filter.getMetric().toLowerCase();
      String[] metric_literals = metric.split("\\|");
      FilterBuilder builder =  FilterBuilders.boolFilter().must(
              FilterBuilders.termsFilter(QUERY_METRIC,
                  metric_literals));
      if (nested) {
        return FilterBuilders.nestedFilter(METRIC_PATH, builder);
      }
      return builder;
    } else if (filter instanceof MetricRegexFilter) {
      FilterBuilder builder = FilterBuilders.boolFilter().must(
              FilterBuilders.regexpFilter(QUERY_METRIC,
                      convertToLuceneRegex(filter.getMetric())));
      if (nested) {
        return FilterBuilders.nestedFilter(METRIC_PATH, builder);
      }
      return builder;
    } else {
      throw new UnsupportedOperationException("Unsupported metric filter: "
              + filter.getClass().toString());
    }
  }

  FilterBuilder getTagValueFilter(final TagValueFilter filter, final boolean nested) {
    if (filter instanceof TagValueLiteralOrFilter) {
      // handles the range filter as well.
      try {
        filter.initialize(null).join();
      } catch (Exception e) {
        throw new QueryExecutionException("Unable to initialize plugin", 204);
      }
      final List<String> lower_case = Lists.newArrayListWithCapacity(
          ((TagValueLiteralOrFilter) filter).literals().size());
      for (final String tag : ((TagValueLiteralOrFilter) filter).literals()) {
        lower_case.add(tag.toLowerCase());
      }
      final BoolFilterBuilder builder = FilterBuilders.boolFilter();
          builder.must(FilterBuilders.termsFilter(QUERY_TAG_VALUE_KEY, lower_case));
      if (! filter.getTagKey().equalsIgnoreCase(".*")) {
        builder.must(FilterBuilders.termFilter(QUERY_TAG_KEY_KEY, filter.getTagKey
                ().toLowerCase()));
      }
      if (nested) {
        return FilterBuilders.nestedFilter(TAG_PATH, builder);
      }
      return builder;
    } else if (filter instanceof TagValueRegexFilter) {
      final String regexp = convertToLuceneRegex(
          ((TagValueRegexFilter) filter).getFilter());
      final BoolFilterBuilder builder = FilterBuilders.boolFilter();
          builder.must(FilterBuilders.regexpFilter(QUERY_TAG_VALUE_KEY, regexp));
      if (! filter.getTagKey().equalsIgnoreCase(".*")) {
        builder.must(FilterBuilders.termFilter(QUERY_TAG_KEY_KEY, filter.getTagKey
                ().toLowerCase()));
      }
      if (nested) {
        return FilterBuilders.nestedFilter(TAG_PATH, builder);
      }
      return builder;
    } else if (filter instanceof TagValueWildcardFilter) {
      final BoolFilterBuilder builder = FilterBuilders.boolFilter();
          builder.must(FilterBuilders.regexpFilter(QUERY_TAG_VALUE_KEY,
              ((TagValueWildcardFilter) filter).getFilter()
                .toLowerCase().replace("*", ".*")));
      if (! filter.getTagKey().equalsIgnoreCase(".*")) {
        builder.must(FilterBuilders.termFilter(QUERY_TAG_KEY_KEY, filter.getTagKey
                ().toLowerCase()));
      }
      if (nested) {
        return FilterBuilders.nestedFilter(TAG_PATH, builder);
      }
      return builder;
    } else {
      throw new UnsupportedOperationException("Unsupported tag value filter: "
          + filter.getClass().toString());
    }
  }

  FilterBuilder getTagKeyFilter(final TagKeyFilter filter, final boolean nested) {
    final FilterBuilder builder = FilterBuilders.boolFilter();
    if (filter instanceof TagKeyLiteralOrFilter) {
      String filter_str = filter.filter().toLowerCase();
      String[] filter_literals = filter_str.split("\\|");
      ((BoolFilterBuilder) builder).must(FilterBuilders.termsFilter(QUERY_TAG_KEY_KEY, filter_literals));

    } else if (filter instanceof TagKeyRegexFilter) {
      ((BoolFilterBuilder) builder).must(FilterBuilders.regexpFilter(QUERY_TAG_KEY_KEY, convertToLuceneRegex(filter.filter())));
    }
    if (nested) {
      return FilterBuilders.nestedFilter(TAG_PATH, builder);
    }

    return builder;
  }

  FilterBuilder getAnyFieldFilter(final AnyFieldRegexFilter filter, final boolean nested) {
    String filterStr = filter.pattern().toString();
    final String pattern = convertToLuceneRegex(filterStr);
    final BoolFilterBuilder builder = FilterBuilders.boolFilter();

    // metric
//    builder.should(FilterBuilders.nestedFilter(METRIC_PATH,
//          FilterBuilders.boolFilter()
//            .should(FilterBuilders.regexpFilter(RESULT_METRIC, pattern))
//            .should(FilterBuilders.regexpFilter(QUERY_METRIC, pattern))));

    // tags
    builder.should(
        FilterBuilders.boolFilter()
          .should(FilterBuilders.regexpFilter(QUERY_TAG_KEY_KEY, pattern))
          .should(FilterBuilders.regexpFilter(QUERY_TAG_VALUE_KEY, pattern)));

    if (filterStr.contains(":")) {
      int index = filterStr.indexOf(":");
      String key = filterStr.substring(0, index);
      String val = filterStr.substring(index+1);
      builder.should(FilterBuilders.boolFilter()
      .must(FilterBuilders.regexpFilter(QUERY_TAG_KEY_KEY, convertToLuceneRegex(key)))
    .must(FilterBuilders.regexpFilter(QUERY_TAG_VALUE_KEY, convertToLuceneRegex(val))));
    }

    if (nested) {
      return FilterBuilders.nestedFilter(TAG_PATH, builder);
    }
    // TODO - verify this
    return builder;
  }

  AggregationBuilder<?> metricAgg(final QueryFilter filter, final int size) {
    if (filter instanceof ExplicitTagsFilter) {
      return metricAgg(((ExplicitTagsFilter) filter).getFilter(), size);
    }
    ChainFilter.Builder metric_only_filter = ChainFilter.newBuilder();
    if (filter instanceof ChainFilter) {

      for (final QueryFilter sub_filter : ((ChainFilter) filter).getFilters()) {
        if (sub_filter instanceof NotFilter) {
          if (((NotFilter) sub_filter).getFilter() instanceof MetricFilter) {
            metric_only_filter.addFilter(sub_filter);
          }
        }
        if (sub_filter instanceof MetricFilter) {
          metric_only_filter.addFilter(sub_filter);
        }
      }
    } else if (filter instanceof MetricFilter) {
      metric_only_filter.addFilter(filter);
    }
    FilterBuilder pair_filter = getTagPairFilter(metric_only_filter.build(),
            false);
    return AggregationBuilders.nested(METRIC_AGG)
        .path(METRIC_PATH)
        .subAggregation(AggregationBuilders.filter(METRIC_AGG)
                .filter(pair_filter)
                .subAggregation(AggregationBuilders.terms(METRIC_UNIQUE)
            .field(RESULT_METRIC)
            .size(size)
            .order(query.getOrder() == BatchMetaQuery.Order.ASCENDING ?
                Order.term(true) : Order.term(false))));
  }

  AggregationBuilder<?> tagKeyAgg(final QueryFilter filter, final int size) {
    if (filter instanceof ExplicitTagsFilter) {
      return tagKeyAgg(((ExplicitTagsFilter) filter).getFilter(), size);
    }
    ChainFilter.Builder tags_filters = ChainFilter.newBuilder();
    if (filter instanceof ChainFilter) {
      for (final QueryFilter sub_filter : ((ChainFilter) filter).getFilters()) {
        if (sub_filter instanceof NotFilter) {
          if (((NotFilter) sub_filter).getFilter() instanceof TagKeyFilter) {
            tags_filters.addFilter(sub_filter);
          }
        }
        if (sub_filter instanceof TagKeyFilter) {
            tags_filters.addFilter(sub_filter);
        }
      }
    } else if (filter instanceof TagKeyFilter) {
      tags_filters.addFilter(filter);
    }

    FilterBuilder pair_filter = getTagPairFilter(tags_filters.build(), true);
    if (pair_filter == null) {
      return null;
    }

    return AggregationBuilders.nested(TAG_KEY_AGG)
        .path(TAG_PATH)
        .subAggregation(AggregationBuilders.filter(TAG_KEY_UNIQUE)
                .filter(pair_filter)
                .subAggregation(AggregationBuilders.terms(TAG_KEY_UNIQUE)
                        .field(RESULT_TAG_KEY_KEY)
                        .size(size)
                        .order(query.getOrder() == BatchMetaQuery.Order.ASCENDING ?
                                Order.term(true) : Order.term(false))));
  }

  AggregationBuilder<?> tagValueAgg(final QueryFilter filter, int size) {
    if (filter instanceof ExplicitTagsFilter) {
      return tagValueAgg(((ExplicitTagsFilter) filter).getFilter(), size);
    }
    return AggregationBuilders.nested(TAG_VALUE_AGG)
        .path(TAG_PATH)
        .subAggregation(AggregationBuilders.terms(TAG_VALUE_UNIQUE)
            .field(RESULT_TAG_VALUE_KEY)
            .size(size)
            .order(query.getOrder() == BatchMetaQuery.Order.ASCENDING ?
                Order.term(true) : Order.term(false)));
  }

  AggregationBuilder<?> tagKeyAndValueAgg(final QueryFilter filter, final String
          field, final int size) {
    if (filter instanceof ExplicitTagsFilter) {
      return tagKeyAndValueAgg(((ExplicitTagsFilter) filter).getFilter(),
              field, size);
    }
    ChainFilter.Builder tags_filters = ChainFilter.newBuilder();

    if (filter instanceof ChainFilter) {
      for (final QueryFilter sub_filter : ((ChainFilter) filter).getFilters()) {
        if (sub_filter instanceof NotFilter) {
          if (sub_filter instanceof TagKeyFilter) {
            if (Strings.isNullOrEmpty(field) || field.equalsIgnoreCase
                    (((TagKeyFilter) sub_filter).filter())) {
              tags_filters.addFilter(sub_filter);
            }
          }
          if (sub_filter instanceof TagValueFilter) {
            if (Strings.isNullOrEmpty(field) || field.equalsIgnoreCase
                    (((TagValueFilter) sub_filter).getTagKey())) {
              tags_filters.addFilter(sub_filter);
            }
          }
        }
        if (sub_filter instanceof TagValueFilter) {
          if (Strings.isNullOrEmpty(field) || field.equalsIgnoreCase
                  (((TagValueFilter) sub_filter).getTagKey())) {
            tags_filters.addFilter(sub_filter);
          }
        }
        if (sub_filter instanceof TagKeyFilter) {
          if (Strings.isNullOrEmpty(field) || field.equalsIgnoreCase
                  (((TagKeyFilter) sub_filter).filter())) {
            tags_filters.addFilter(sub_filter);
          }
        }
      }
    }

    if (tags_filters.filters() == null || tags_filters.filters().size() == 0) {
      tags_filters.addFilter(TagValueWildcardFilter.newBuilder().setKey
              (field).setFilter(".*").build());
    }
    // we have to recurse here and find tag key/tag value filters.

    FilterBuilder pair_filter = getTagPairFilter(tags_filters.build(), true);
    if (pair_filter == null) {
      return null;
    }

    return AggregationBuilders.nested(TAGS_AGG)
            .path(TAG_PATH)
            .subAggregation(AggregationBuilders.filter(TAGS_UNIQUE)
                    .filter(pair_filter)
                    .subAggregation(AggregationBuilders.terms(TAGS_UNIQUE)
                            .field(RESULT_TAG_KEY_KEY)
                            .size(0)
                            .order(query.getOrder() == BatchMetaQuery.Order.ASCENDING ?
                                    Order.term(true) : Order.term(false))
                            .subAggregation(AggregationBuilders.filter(TAGS_SUB_AGG)
                                    .filter(pair_filter)
                                    .subAggregation(AggregationBuilders.terms(TAGS_SUB_UNIQUE)
                                            .field(RESULT_TAG_VALUE_KEY)
                                            .size(size)
                                            .order(query.getOrder() == BatchMetaQuery.Order.ASCENDING ?
                                                    Order.term(true) : Order.term(false))))));
  }

  AggregationBuilder<?> basicAgg(final QueryFilter filter, final String
          field, final int size) {

    if (filter instanceof ExplicitTagsFilter) {
      return basicAgg(((ExplicitTagsFilter) filter).getFilter(),
              field, size);
    }

    FilterBuilder agg_filters;
    if (Strings.isNullOrEmpty(field)) {
       agg_filters = setBasicFilterForAgg(filter);
    } else {
      ChainFilter.Builder tags_filters = ChainFilter.newBuilder();
      tags_filters.addFilter(TagValueWildcardFilter.newBuilder().setKey
              (field).setFilter(".*").build());
      agg_filters = getTagPairFilter(tags_filters.build(), true);
      ((BoolFilterBuilder) agg_filters).must(setBasicFilterForAgg(filter));
    }

    return AggregationBuilders.nested(TAGS_AGG)
            .path(TAG_PATH)
            .subAggregation(AggregationBuilders.filter(TAGS_UNIQUE)
                    .filter(FilterBuilders.boolFilter().must(agg_filters))
                    .subAggregation(AggregationBuilders.terms(TAGS_UNIQUE)
                            .field(RESULT_TAG_KEY_KEY)
                            .size(0)
                            .order(query.getOrder() == BatchMetaQuery.Order.ASCENDING ?
                                    Order.term(true) : Order.term(false))
                            .subAggregation(AggregationBuilders.filter(TAGS_SUB_AGG)
                                    .filter(FilterBuilders.boolFilter().must(agg_filters))
                                    .subAggregation(AggregationBuilders.terms(TAGS_SUB_UNIQUE)
                                            .field(RESULT_TAG_VALUE_KEY)
                                            .size(size)
                                            .order(query.getOrder() == BatchMetaQuery.Order.ASCENDING ?
                                                    Order.term(true) : Order.term(false))))));
  }


  FilterBuilder getTagPairFilter(final QueryFilter filter, final boolean
          use_must) {
    if (filter == null) {
      return null;
    }

    if (filter instanceof MetricFilter) {
      return getMetricFilter((MetricFilter) filter, false);
    }

    if (filter instanceof TagValueFilter) {
      return getTagValueFilter((TagValueFilter) filter, false);
    }

    if (filter instanceof TagKeyFilter) {
      return getTagKeyFilter((TagKeyFilter) filter, false);
    }

    if (filter instanceof AnyFieldRegexFilter) {
      return FilterBuilders.boolFilter()
            .must(FilterBuilders.regexpFilter(QUERY_TAG_VALUE_KEY, ".*"))
            .must(FilterBuilders.regexpFilter(QUERY_TAG_KEY_KEY,
                ((AnyFieldRegexFilter) filter).pattern().toString()));
    }

    if (filter instanceof NotFilter) {
      return FilterBuilders.boolFilter().mustNot(
          getTagPairFilter(((NotFilter) filter).getFilter(), use_must));
    }

    if (filter instanceof ChainFilter) {
      BoolFilterBuilder builder = FilterBuilders.boolFilter();
      // Metrics are should, tags_key_and_value is a must filter
      for (final QueryFilter sub_filter : ((ChainFilter) filter).getFilters()) {
        final FilterBuilder sub_builder = getTagPairFilter(sub_filter, use_must);
        if (sub_builder != null) {
          if (use_must) {
            builder.must(sub_builder);
          } else {
            builder.should(sub_builder);
          }
        }
      }
      return builder;
    }

    return null;
  }


  public static NamespacedAggregatedDocumentQueryBuilder newBuilder(
      final BatchMetaQuery query) {
    return new NamespacedAggregatedDocumentQueryBuilder(query);
  }

  public NamespacedAggregatedDocumentQueryBuilder setIsMultiGet() {
    this.isMultiGet = true;
    return this;
  }

  public Map<NamespacedKey, List<SearchSourceBuilder>> build() {
    for (final MetaQuery meta_query : query.getQueries()) {
      if (query.getType() == QueryType.NAMESPACES) {
        SearchSourceBuilder search_source_builder = new SearchSourceBuilder();
        search_source_builder.query(FilterBuilders.boolFilter()
                .must(FilterBuilders.regexpFilter(QUERY_NAMESPACE_KEY,
                        convertToLuceneRegex(meta_query.getNamespace())))
                .buildAsBytes());
        search_source_builder.aggregation(AggregationBuilders.terms(NAMESPACE_AGG)

                .field(RESULT_NAMESPACE)
                .size(0)
                .order(query.getOrder() == BatchMetaQuery.Order.ASCENDING ?
                        Order.term(true) : Order.term(false)));
        search_source_builder.size(0);
        search_source_builders.computeIfAbsent(new NamespacedKey("all_namespace", "-1"),
                k -> new ArrayList<>()).add(search_source_builder);
      } else {
        List<SearchSourceBuilder> search_source_builders_list = null;
      if (meta_query.getFilter() != null || query.getStart() != null || query.getEnd() !=
              null) {
       search_source_builders_list = translate(meta_query, null);


        if (query.getType() == QueryType.BASIC) {
          List<String> keys = new ArrayList<>();
          getTagValuesQuery(meta_query.getFilter(), keys);
          for (String key : keys) {
            List<SearchSourceBuilder> tmp_search_source_builders = translate(meta_query, key);
            for (SearchSourceBuilder search_source_builder: tmp_search_source_builders) {
              search_source_builder.aggregation(basicAgg(meta_query.getFilter(), key, query.getAggregationSize()));
              search_source_builder.size(0);
             // search_source_builders_list.add(search_source_builder);
            }
          }
        }
      }

      if (search_source_builders_list == null) {
        throw new IllegalArgumentException("Error building Elastic Search query");
      }

      for (SearchSourceBuilder search_source_builder : search_source_builders_list) {
        switch (query.getType()) {
          case METRICS:
            if (search_source_builders_list.size() > 1) {
              throw new IllegalArgumentException("Query not supported with AND operation. Don't use multiple MetricLiterals");
            }
            search_source_builder
                    .aggregation(metricAgg(meta_query.getFilter(), query.getAggregationSize()));
            search_source_builder.size(0);
            break;
          case TAG_KEYS:
            search_source_builder
                    .aggregation(tagKeyAgg(meta_query.getFilter(), query.getAggregationSize()));
            search_source_builder.size(0);
            break;
          case TAG_VALUES:
            if (search_source_builders_list.size() > 1) {
              throw new IllegalArgumentException("Query not supported with AND operation. Don't use multiple MetricLiterals");
            }
            search_source_builder
                    .aggregation(tagValueAgg(meta_query.getFilter(), query.getAggregationSize()));
            search_source_builder.size(0);
            break;
          case TAG_KEYS_AND_VALUES:
            if (search_source_builders_list.size() > 1) {
              throw new IllegalArgumentException("Query not supported with AND operation. Don't use multiple MetricLiterals");
            }
            search_source_builder.aggregation(tagKeyAndValueAgg(meta_query.getFilter(),
                    query.getAggregationField() == null ? null : query.getAggregationField().toLowerCase(), query.getAggregationSize()));
            search_source_builder.size(0);
            break;
          case BASIC:
            search_source_builder.aggregation(basicAgg(meta_query.getFilter(), null, query.getAggregationSize()));
            search_source_builder.size(0);
            break;
          case TIMESERIES:
            if (search_source_builders_list.size() > 1) {
              throw new IllegalArgumentException("Query not supported with AND operation. Don't use multiple MetricLiterals");
            }
            search_source_builder.from(query.getFrom());
            search_source_builder.size(query.getTo() - query.getFrom());
            break;
          default:
            throw new UnsupportedOperationException(query.getType() + " not implemented yet.");
        }
      }
    }
  }
    LOG.info("Meta queries == " + search_source_builders);
    return search_source_builders;
  }

  private List<SearchSourceBuilder> translate(MetaQuery meta_query, String exclude) {
    List<SearchSourceBuilder> search_source_builders_list = new ArrayList<>();
    List<FilterBuilder> metric_filters = new ArrayList<>();
    if (query.getStart() != null || query.getEnd() != null) {
      FilterBuilder time_filter;
      if (query.getStart() != null && query.getEnd() != null) {
        time_filter = FilterBuilders.rangeFilter(LAST_SEEN)
                .from(query.getStart().msEpoch())
                .to(query.getEnd().msEpoch());
      } else if (query.getStart() != null) {
        time_filter = FilterBuilders.rangeFilter(LAST_SEEN)
                .from(query.getStart().msEpoch())
                .to(DateTime.currentTimeMillis());
      } else {
        time_filter = FilterBuilders.rangeFilter(LAST_SEEN)
                .from(0)
                .to(query.getEnd().epoch());
      }

      FilterBuilder tag_filters = setFilter(meta_query.getFilter(), metric_filters, true, exclude);
      List<FilterBuilder> all_filters = new ArrayList<>();
      all_filters.add(tag_filters);

      all_filters.add(time_filter);

      buildESQuery(all_filters, metric_filters, search_source_builders_list);


    } else if (meta_query.getFilter() instanceof ExplicitTagsFilter) {
      FilterBuilder tag_filters = setFilter(meta_query.getFilter(), metric_filters, true, exclude);
      FilterBuilder explicit_filter = FilterBuilders.termFilter
              ("tags_value", NamespacedAggregatedDocumentSchema.countTagValueFilters(meta_query.getFilter(), 0));

      List<FilterBuilder> all_filters = new ArrayList<>();
      all_filters.add(tag_filters);
      all_filters.add(explicit_filter);

      buildESQuery(all_filters, metric_filters, search_source_builders_list);
    } else {
      FilterBuilder tag_filters = setFilter(meta_query.getFilter(), metric_filters, true, exclude);
      List<FilterBuilder> all_filters = new ArrayList<>();
      all_filters.add(tag_filters);

      buildESQuery(all_filters, metric_filters, search_source_builders_list);
    }

    int count = 0;
    String index =
            (NamespacedAggregatedDocumentSchema.countTagValueFilters(meta_query.getFilter(), count) > 0
                    || isMultiGet)
                    ? meta_query.getNamespace().toLowerCase() :
                    (meta_query.getNamespace() + TAG_KEYS_INDEX_SUFFIX).toLowerCase();
    String id = meta_query.getId();
    if (! Strings.isNullOrEmpty(exclude)) {
      id = id + "-" + exclude;
    }
    search_source_builders
            .put(new NamespacedKey(index, id),
                    search_source_builders_list);
    return search_source_builders_list;
  }

  private void getTagValuesQuery(QueryFilter query_filter, List<String> keys) {

    if (query_filter instanceof TagValueFilter) {
     keys.add(((TagValueFilter) query_filter).getTagKey());
    }

    if (query_filter instanceof ChainFilter) {
      for (QueryFilter filter : ((ChainFilter) query_filter).getFilters()) {
        getTagValuesQuery(filter, keys);
      }
    }
  }

  private void buildESQuery(List<FilterBuilder> filters, List<FilterBuilder> metric_filters,
                                           List<SearchSourceBuilder> search_source_builders) {
    if (filters.isEmpty()) {
      throw new IllegalArgumentException("Filters cannot be empty");
    }

    if (metric_filters.isEmpty()) {
      BoolFilterBuilder bool_filter_builder = FilterBuilders.boolFilter();
      search_source_builders.add(addTagFiltersToESQuery(filters, bool_filter_builder));
      return;
    }

    for (FilterBuilder metric_filter: metric_filters) {
      BoolFilterBuilder bool_filter_builder = FilterBuilders.boolFilter();
      bool_filter_builder.must(metric_filter);

      SearchSourceBuilder search_source_builder = addTagFiltersToESQuery(filters, bool_filter_builder);
      search_source_builders.add(search_source_builder);
    }

  }

  public SearchSourceBuilder addTagFiltersToESQuery(List<FilterBuilder> filters, BoolFilterBuilder bool_filter_builder) {
    if (!filters.isEmpty()) {
      for (FilterBuilder each_filter : filters) {
        if (each_filter != null) {
          bool_filter_builder.must(each_filter);
        }
      }
    }
    SearchSourceBuilder search_source_builder = new SearchSourceBuilder();
    search_source_builder.query(bool_filter_builder.buildAsBytes());
    return search_source_builder;
  }

  static String convertToLuceneRegex(final String value_str) throws
          RuntimeException {
    if (value_str == null || value_str.isEmpty()) {
      throw new IllegalArgumentException("Please provide a valid regex");
    }

    String result = value_str.toLowerCase().trim().replaceAll("\\|", ".*|.*");
    int length = result.length();
    if (result.charAt(0) == '(') {
      result = result.substring(0);
    }
    if (result.charAt(length - 1) == '(') {
      result = result.substring(0, length - 1);
    }

    if (result.startsWith("^")) {
      result = result.substring(1, length);
    } else if (!result.startsWith("~") &&
               !result.startsWith(".*")) {
      result = ".*" + result;
    }
    length = result.length();
    if (result.endsWith("$")) {
      result = result.substring(0, length - 1);
    } else if (!result.startsWith("~") &&
               !result.endsWith(".*")) {
      result = result + ".*";
    }

    return result;
  }

}