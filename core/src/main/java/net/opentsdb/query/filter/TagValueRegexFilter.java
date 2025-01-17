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
package net.opentsdb.query.filter;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.stumbleupon.async.Deferred;

import net.opentsdb.core.Const;
import net.opentsdb.stats.Span;

/**
 * Regular expression filter for tag values given a literal tag key.
 * 
 * @since 3.0
 */
@JsonInclude(Include.NON_NULL)
@JsonDeserialize(builder = TagValueRegexFilter.Builder.class)
public class TagValueRegexFilter extends BaseTagValueFilter {

  /** The compiled pattern */
  final Pattern pattern;
  
  /** Whether or not the regex would match-all. */
  final boolean matches_all;
  
  /**
   * Protected ctor.
   * @param builder The non-null builder.
   */
  protected TagValueRegexFilter(final Builder builder) {
    super(builder.key, builder.filter);
    pattern = Pattern.compile(filter.trim());
    
    if (filter.equals(".*") || 
        filter.equals("^.*") || 
        filter.equals(".*$") || 
        filter.equals("^.*$")) {
      // yeah there are many more permutations but these are the most likely
      // to be encountered in the wild.
      matches_all = true;
    } else {
      matches_all = false;
    }
  }

  @Override
  public boolean matches(final Map<String, String> tags) {
    final String tagv = tags.get(tag_key);
    if (tagv == null) {
      return false;
    }
    if (matches_all) {
      return true;
    }
    return pattern.matcher(tagv).find();
  }

  @Override
  public boolean matches(final String tag_key, final String tag_value) {
    if (Strings.isNullOrEmpty(tag_key)) {
      return false;
    }
    if (!this.tag_key.equals(tag_key)) {
      return false;
    }
    if (Strings.isNullOrEmpty(tag_value)) {
      return false;
    }
    if (matches_all) {
      return true;
    }
    return pattern.matcher(tag_value).find();
  }
  
  @Override
  public boolean matches(final String tag_value) {
    return pattern.matcher(tag_value).find();
  }
  
  @Override
  public String getType() {
    return TagValueRegexFactory.TYPE;
  }
  
  /** Whether or not the regex would match all strings. */
  public boolean matchesAll() {
    return matches_all;
  }

  /** @return The compiled pattern. */
  public Pattern pattern() {
    return pattern;
  }
  
  @Override
  public String toString() {
    return new StringBuilder()
        .append("{type=")
        .append(getClass().getSimpleName())
        .append(", tagKey=")
        .append(tag_key)
        .append(", filter=")
        .append(filter)
        .append(", matchesAll=")
        .append(matches_all)
        .append("}")
        .toString();
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

    final TagValueRegexFilter otherTagFilter = (TagValueRegexFilter) o;

    return Objects.equal(matches_all, otherTagFilter.matchesAll())
        && Objects.equal(pattern.pattern(), otherTagFilter.pattern().pattern());

  }


  @Override
  public int hashCode() {
    return buildHashCode().asInt();
  }


  /** @return A HashCode object for deterministic, non-secure hashing */
  public HashCode buildHashCode() {
    final List<HashCode> hashes =
            Lists.newArrayListWithCapacity(2);

    hashes.add(super.buildHashCode());

    final HashCode hc = Const.HASH_FUNCTION().newHasher()
            .putBoolean(matches_all)
            .putString(Strings.nullToEmpty(getType()), Const.UTF8_CHARSET)
            .putString(Strings.nullToEmpty(pattern.pattern()), Const.UTF8_CHARSET)
            .hash();

    hashes.add(hc);

    return Hashing.combineOrdered(hashes);
  }

  
  @Override
  public Deferred<Void> initialize(final Span span) {
    return INITIALIZED;
  }
  
  public static Builder newBuilder() {
    return new Builder();
  }
  
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Builder {
    @JsonProperty
    private String key;
    @JsonProperty
    private String filter;
    
    public Builder setKey(final String tag_key) {
      this.key = tag_key;
      return this;
    }
    
    public Builder setFilter(final String filter) {
      this.filter = filter;
      return this;
    }
    
    public TagValueRegexFilter build() {
      return new TagValueRegexFilter(this);
    }
  }
  
}
