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

import com.google.common.collect.Sets;

import java.util.Map;
import java.util.Set;

/**
 * Utilities for working with filters.
 * 
 * @since 3.0
 */
public class FilterUtils {

  /**
   * Private ctor, statics only.
   */
  private FilterUtils() { }

  /**
   * Determines if the filter(s) are satisfied with the tag sets. This
   * utility will handle chained filters based on the AND or OR operand.
   * For non-tag handling filters, this method will return true.
   * @param filter The filter to evaluate.
   * @param tags The non-null (possibly empty) set of tags to evaluate.
   * @param matched A set of tag keys used to determine if we've satisfied
   * an explicit tags filter.
   * @return True if the filter(s) matched, false if not or true if the
   * filter(s) were not tag filters.
   */
  public static boolean matchesTags(final QueryFilter filter,
                                    final Map<String, String> tags,
                                    Set<String> matched) {
    if (filter == null) {
      throw new IllegalArgumentException("Filter cannot be null.");
    }
    if (tags == null) {
      throw new IllegalArgumentException("Tags cannot be null.");
    }

    if (filter instanceof ExplicitTagsFilter) {
      matched = Sets.newHashSetWithExpectedSize(tags.size());
      final boolean satisfied = matchesTags(
          ((ExplicitTagsFilter) filter).getFilter(), tags, matched);

      if (!satisfied) {
        return false;
      }
      if (matched.size() != tags.size()) {
        return false;
      }
      return true;
    }

    if (filter instanceof TagValueFilter) {
      if (((TagValueFilter) filter).matches(tags)) {
        if (matched != null) {
          matched.add(((TagValueFilter) filter).getTagKey());
        }
        return true;
      }
      return false;
    }
    if (filter instanceof TagKeyFilter) {
      if (((TagValueFilter) filter).matches(tags)) {
        if (matched != null) {
          matched.add(((TagKeyFilter) filter).filter());
        }
        return true;
      }
      return false;
    }
    if (filter instanceof ChainFilter) {
      final ChainFilter chain = (ChainFilter) filter;
      switch (chain.getOp()) {
        case AND:
          for (final QueryFilter child : chain.getFilters()) {
            if (!matchesTags(child, tags, matched)) {
              return false;
            }
          }
          return true;
        case OR:
          for (final QueryFilter child : chain.getFilters()) {
            if (matchesTags(child, tags, matched)) {
              return true;
            }
          }
          return false;
        default:
          throw new IllegalStateException("Unsupported chain operator: "
              + chain.getOp());
      }
    }
    if (filter instanceof NotFilter) {
      return !matchesTags(((NotFilter) filter).getFilter(), tags, matched);
    }

    // it's a different type of filter so return true.
    return true;
  }

  /**
   * Walks the filter recursively to figure out which tag keys would be of
   * use in the query, particularly for determining pre-aggregates. If a key
   * is not'ted then we'll omit it.
   * @param filter A non-null filter to start with.
   * @return Null if the filter did not have any useful tag key filters, a 
   * non-empty set if one or more tag keys were found.
   */
  public static Set<String> desiredTagKeys(final QueryFilter filter) {
    return desiredTagKeys(filter, false);
  }
  
  private static Set<String> desiredTagKeys(final QueryFilter filter, 
                                            final boolean not) {
    if (filter instanceof NestedQueryFilter) {
      if (filter instanceof NotFilter) {
        return desiredTagKeys(((NestedQueryFilter) filter).getFilter(), true);
      } else {
        return desiredTagKeys(((NestedQueryFilter) filter).getFilter(), not);
      }
    } else if (filter instanceof ChainFilter) {
      Set<String> tags = null;
      for (final QueryFilter sub_filter : ((ChainFilter) filter).getFilters()) {
        final Set<String> sub_tags = desiredTagKeys(sub_filter, not);
        if (sub_tags != null) {
          if (tags == null) {
            tags = Sets.newHashSet();
          }
          tags.addAll(sub_tags);
        }
      }
      return tags;
    } else if (filter instanceof TagKeyLiteralOrFilter && !not) {
      return Sets.newHashSet(((TagKeyLiteralOrFilter) filter).literals());
    } else if (filter instanceof TagValueRegexFilter) {
      if (not && ((TagValueRegexFilter) filter).matchesAll()) {
        // same as if we had a NOT(TagKeyFilter).
        return null;
      }
      return Sets.newHashSet(((TagValueFilter) filter).getTagKey());
    } else if (filter instanceof TagValueWildcardFilter) {
      if (not && ((TagValueWildcardFilter) filter).matchesAll()) {
        // same as if we had a NOT(TagKeyFilter).
        return null;
      }
      return Sets.newHashSet(((TagValueFilter) filter).getTagKey());
    } else if (filter instanceof TagValueFilter) {
      // we always need to put these in as we don't know if the user specified
      // all tags to be "not"ted.
      return Sets.newHashSet(((TagValueFilter) filter).getTagKey());
    } 
    return null;
  }
}