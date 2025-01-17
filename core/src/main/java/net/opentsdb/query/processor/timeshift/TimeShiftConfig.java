// This file is part of OpenTSDB.
// Copyright (C) 2019  The OpenTSDB Authors.
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
package net.opentsdb.query.processor.timeshift;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.hash.HashCode;

import java.time.temporal.TemporalAmount;
import java.util.List;

import com.google.common.hash.Hashing;
import net.opentsdb.core.Const;
import net.opentsdb.query.BaseQueryNodeConfig;
import net.opentsdb.utils.DateTime;
import net.opentsdb.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@JsonInclude(Include.NON_NULL)
@JsonDeserialize(builder = TimeShiftConfig.Builder.class)
public class TimeShiftConfig extends BaseQueryNodeConfig<TimeShiftConfig.Builder, TimeShiftConfig> {
  private static final Logger LOG = LoggerFactory.getLogger(TimeShiftConfig.class);

  private String timeShiftInterval;
  private Pair<Boolean, TemporalAmount> amounts;

  protected TimeShiftConfig(final Builder builder) {
    super(builder);
    timeShiftInterval = builder.interval;
    if (!Strings.isNullOrEmpty(builder.interval)) {
      DateTime.parseDuration(builder.interval);
      timeShiftInterval = builder.interval;

      // TODO - must be easier/cleaner ways.
      // TODO - handle calendaring
      final int count = DateTime.getDurationInterval(timeShiftInterval);
      final String units = DateTime.getDurationUnits(timeShiftInterval);
      final TemporalAmount amount = DateTime.parseDuration2(
              Integer.toString(count) + units);
      amounts = new Pair<Boolean, TemporalAmount>(true, amount);

    }
  }

  public Pair<Boolean, TemporalAmount> amounts() {
    return amounts;
  }

  public String getTimeShiftInterval() {
    return timeShiftInterval;
  }

  @Override
  public Builder toBuilder() {
    return new Builder().setTimeshiftInterval(timeShiftInterval).setId(id).setSources(sources);
  }

  @Override
  public int compareTo(final TimeShiftConfig o) {
    // TODO Auto-generated method stub
    return 0;
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

    final TimeShiftConfig tsconfig = (TimeShiftConfig) o;

    return Objects.equal(timeShiftInterval, tsconfig.getTimeShiftInterval());
  }

  @Override
  public int hashCode() {
    return buildHashCode().asInt();
  }

  @Override
  public HashCode buildHashCode() {
    if (cached_hash != null) {
      return cached_hash;
    }
    
    final HashCode hc = net.opentsdb.core.Const.HASH_FUNCTION().newHasher()
            .putString(Strings.nullToEmpty(timeShiftInterval), Const.UTF8_CHARSET)
            .hash();
    final List<HashCode> hashes =
            Lists.newArrayListWithCapacity(2);

    hashes.add(super.buildHashCode());

    hashes.add(hc);

    cached_hash = Hashing.combineOrdered(hashes);
    return cached_hash;
  }

  @Override
  public boolean pushDown() {
    return true;
  }

  @Override
  public boolean joins() {
    return false;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Builder extends BaseQueryNodeConfig.Builder<Builder, TimeShiftConfig> {
    protected String interval;

    Builder() {
      setType(TimeShiftFactory.TYPE);
    }

    public Builder setTimeshiftInterval(final String interval) {
      this.interval = interval;
      return this;
    }

    @Override
    public TimeShiftConfig build() {
      return new TimeShiftConfig(this);
    }

    @Override
    public Builder self() {
      return this;
    }
  }

}