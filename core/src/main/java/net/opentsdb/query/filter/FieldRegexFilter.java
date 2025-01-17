// This file is part of OpenTSDB.
// Copyright (C) 2015-2018  The OpenTSDB Authors.
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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.hash.HashCode;
import com.stumbleupon.async.Deferred;
import net.opentsdb.core.Const;
import net.opentsdb.stats.Span;

import java.util.regex.Pattern;

/**
 * Filters on a set of one or more case sensitive tag value strings.
 *
 * @since 3.0
 */
@JsonInclude(Include.NON_NULL)
@JsonDeserialize(builder = FieldRegexFilter.Builder.class)
public class FieldRegexFilter extends BaseFieldFilter {

  /** The compiled pattern */
  final Pattern pattern;

  /** Whether or not the regex would match-all. */
  final boolean matches_all;

  /**
   * Protected ctor.
   *
   * @param builder The non-null builder.
   */
  protected FieldRegexFilter(final FieldRegexFilter.Builder builder) {
    super(builder.key, builder.filter);
    pattern = Pattern.compile(filter.trim());

    if (filter.equals(".*")
        || filter.equals("^.*")
        || filter.equals(".*$")
        || filter.equals("^.*$")) {
      // yeah there are many more permutations but these are the most likely
      // to be encountered in the wild.
      matches_all = true;
    } else {
      matches_all = false;
    }
  }

  @Override
  public String getType() {
    return FieldRegexFactory.TYPE;
  }

  /** Whether or not the regex would match all strings. */
  public boolean matchesAll() {
    return matches_all;
  }

  @Override
  public String toString() {
    return new StringBuilder()
        .append("{type=")
        .append(getClass().getSimpleName())
        .append(", key=")
        .append(key)
        .append(", filter=")
        .append(filter)
        .append(", matchesAll=")
        .append(matches_all)
        .append("}")
        .toString();
  }

  @Override
  public Deferred<Void> initialize(final Span span) {
    return INITIALIZED;
  }

  public static FieldRegexFilter.Builder newBuilder() {
    return new FieldRegexFilter.Builder();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Builder {
    @JsonProperty private String key;
    @JsonProperty private String filter;

    public Builder setKey(final String key) {
      this.key = key;
      return this;
    }

    public Builder setFilter(final String filter) {
      this.filter = filter;
      return this;
    }

    public FieldRegexFilter build() {
      return new FieldRegexFilter(this);
    }
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o)
      return true;
    if (o == null || getClass() != o.getClass())
      return false;

    if (!super.equals(o)) {
      return false;
    }

    final FieldRegexFilter otherFilter = (FieldRegexFilter) o;

    return Objects.equal(matches_all, otherFilter.matchesAll());
  }

  @Override
  public int hashCode() {
    return buildHashCode().asInt();
  }


  /** @return A HashCode object for deterministic, non-secure hashing */
  public HashCode buildHashCode() {
    final HashCode hc = Const.HASH_FUNCTION().newHasher()
            .putString(Strings.nullToEmpty(key), Const.UTF8_CHARSET)
            .putString(Strings.nullToEmpty(filter), Const.UTF8_CHARSET)
            .putString(Strings.nullToEmpty(getType()), Const.UTF8_CHARSET)
            .putBoolean(matches_all)
            .hash();

    return hc;
  }
}
