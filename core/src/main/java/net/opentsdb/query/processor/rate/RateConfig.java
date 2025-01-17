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
package net.opentsdb.query.processor.rate;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.Lists;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;

import com.google.common.hash.Hashing;
import net.opentsdb.core.Const;
import net.opentsdb.core.TSDB;
import net.opentsdb.data.TimeStamp;
import net.opentsdb.query.BaseQueryNodeConfig;
import net.opentsdb.query.QueryNodeConfigOptions;
import net.opentsdb.utils.DateTime;

/**
 * Provides additional options that will be used when calculating rates. These
 * options are useful when working with metrics that are raw counter values, 
 * where a counter is defined by a value that always increases until it hits
 * a maximum value and then it "rolls over" to start back at 0.
 * <p>
 * These options will only be utilized if the query is for a rate calculation
 * and if the "counter" options is set to true.
 * @since 2.0
 */
@JsonInclude(Include.NON_DEFAULT)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonDeserialize(builder = RateConfig.Builder.class)
public class RateConfig extends BaseQueryNodeConfig<RateConfig.Builder, RateConfig> {
  public static final long DEFAULT_RESET_VALUE = 0;
  public static final String DEFAULT_INTERVAL = "1s";
  public static final long DEFAULT_COUNTER_MAX = Long.MAX_VALUE;
  public static final Integer PREVIOUS_INTERVALS = 1;
  
  /**
   * If true, then when calculating a rate of change assume that the metric
   * values are counters and thus non-zero, always increasing and wrap around at
   * some maximum. */
  private boolean counter;
  
  /** Whether or not to simply drop rolled-over or reset data points */
  private boolean drop_resets;

  /**
   * If calculating a rate of change over a metric that is a counter, then this
   * value specifies the maximum value the counter will obtain before it rolls
   * over. This value will default to Long.MAX_VALUE.
   */
  private long counter_max = DEFAULT_COUNTER_MAX;

  /**
   * Specifies the the rate change value which, if exceeded, will be considered
   * a data anomaly, such as a system reset of the counter, and the rate will be
   * returned as a zero value for a given data point.
   */
  private long reset_value;
  
  /** The rate interval in duration format. Default is 1 seconds as per TSDB 1/2 */
  private String interval = DEFAULT_INTERVAL;
  
  /** Whether or not we just want a delta. */
  private boolean delta_only;
  
  /** Whether we want the rate to count. */
  private boolean rate_to_count;
  
  /** For the rate to count, a user given data interval we can use to handle
   * missing values. */
  private String data_interval;
  private long data_interval_ms;

  /** Parsed values. */
  private Duration duration;
  private ChronoUnit units;
    
  /**
   * Ctor
   */
  protected RateConfig(final Builder builder) {
    super(builder);
    counter = builder.counter;
    drop_resets = builder.dropResets;
    counter_max = builder.counterMax;
    reset_value = builder.resetValue;
    interval = builder.interval;
    data_interval = builder.dataInterval;
    delta_only = builder.deltaOnly;
    rate_to_count = builder.rateToCount;
    
    if (interval.toLowerCase().equals("auto")) {
      if (builder.start_time != null && builder.end_time != null) {
        if (builder.factory.intervals() == null) {
          throw new IllegalArgumentException("Auto downsampling is not "
              + "configured or enabled.");
        }
        // TODO - handle smaller scales
        final long delta = builder.end_time.msEpoch() - 
            builder.start_time.msEpoch();
        interval = builder.factory.getAutoInterval(delta);
        final long interval_part = DateTime.getDurationInterval(interval);
        units = DateTime.unitsToChronoUnit(DateTime.getDurationUnits(interval));
        duration = Duration.of(interval_part, units);
      } else {
        // we've just be parsed, not setup, so set back to auto.
        interval = "auto";
      }
    } else {
      final long interval_part = DateTime.getDurationInterval(interval);
      units = DateTime.unitsToChronoUnit(DateTime.getDurationUnits(interval));
      duration = Duration.of(interval_part, units);
    }
    
    if (!Strings.isNullOrEmpty(data_interval)) {
      data_interval_ms = DateTime.parseDuration(data_interval);
    }
  }
  
  /** @return Whether or not the counter flag is set */
  public boolean isCounter() {
    return counter;
  }

  /** @return The counter max value */
  public long getCounterMax() {
    return counter_max;
  }

  /** @return The optional reset value for anomaly suppression */
  public long getResetValue() {
    return reset_value;
  }

  /** @return Whether or not to drop rolled-over or reset counters */
  public boolean getDropResets() {
    return drop_resets;
  }
  
  /** @return The rate interval in duration format. Default is 1 seconds as 
   * per TSDB 1/2. */
  public String getInterval() {
    return interval;
  }
  
  /** @return The optional data interval. */
  public String getDataInterval() {
    return data_interval;
  }
  
  /** @return Whether or not to return the delta only, not rate. */
  public boolean getDeltaOnly() {
    return delta_only;
  }

  /** @return Whether or not we want to convert a rate to a count. */
  public boolean getRateToCount() {
    return rate_to_count;
  }
  
  /** @return The duration of the rate to convert to. E.g. per second or per
   * 8 seconds, etc. */
  public Duration duration() {
    return duration;
  }
  
  /** @return The parsed units of the interval. */
  public ChronoUnit units() {
    return units;
  }

  /** @return The optional data interval in milliseconds. 0 if not set. */
  public long dataIntervalMs() {
    return data_interval_ms;
  }
  
  @Override
  public Builder toBuilder() {
    RateConfig.Builder builder = new Builder()
        .setInterval(interval)
        .setDropResets(drop_resets)
        .setCounter(counter)
        .setCounterMax(counter_max)
        .setDeltaOnly(delta_only)
        .setResetValue(reset_value)
        .setRateToCount(rate_to_count)
        .setDataInterval(data_interval);
    super.toBuilder(builder);
    return builder;
  }
  
  @Override
  public boolean pushDown() {
    return true;
  }
  
  @Override
  public boolean joins() {
    return false;
  }

  @Override
  public <T> T nodeOption(QueryNodeConfigOptions option) {
    if (option == QueryNodeConfigOptions.PREVIOUS_INTERVALS) {
      return (T) PREVIOUS_INTERVALS;
    }
    return null;
  }

  /**
   * Generates a String version of the rate option instance in a format that 
   * can be utilized in a query.
   * @return string version of the rate option instance.
   */
  public String toString() {
    StringBuilder buf = new StringBuilder();
    buf.append('{');
    buf.append(counter);
    buf.append(',').append(counter_max);
    buf.append(',').append(reset_value);
    buf.append('}');
    return buf.toString();
  }

  /** Validates the config
   * @throws IllegalArgumentException if one or more parameters were invalid
   */
  public void validate(final TSDB tsdb) {
    if (Strings.isNullOrEmpty(interval)) {
      throw new IllegalArgumentException("Interval cannot be null or empty.");
    }
    DateTime.parseDuration2(interval);
  }
  
  @Override
  public boolean equals(final Object o) {
    if (this == o)
      return true;
    if (o == null || getClass() != o.getClass())
      return false;
   final RateConfig options = (RateConfig) o;
   return Objects.equal(counter, options.counter)
       && Objects.equal(drop_resets, options.drop_resets)
       && Objects.equal(counter_max, options.counter_max)
       && Objects.equal(reset_value, options.reset_value)
       && Objects.equal(interval, options.interval)
       && Objects.equal(data_interval, options.data_interval)
       && Objects.equal(delta_only, options.delta_only)
       && Objects.equal(id, options.id)
       && Objects.equal(rate_to_count, options.getRateToCount());

  }
  
  @Override
  public int hashCode() {
    return buildHashCode().asInt();
  }

  /** @return A HashCode object for deterministic, non-secure hashing */
  public HashCode buildHashCode() {
    if (cached_hash != null) {
      return cached_hash;
    }
    
    final List<HashCode> hashes =
            Lists.newArrayListWithCapacity(3);

    hashes.add(super.buildHashCode());

    Hasher hasher = Const.HASH_FUNCTION().newHasher();
    hasher.putBoolean(counter)
    .putBoolean(drop_resets)
    .putBoolean(delta_only)
    .putLong(counter_max)
    .putLong(reset_value)
    .putString(interval, Const.UTF8_CHARSET)
    .putString(data_interval == null ? "" : data_interval, Const.UTF8_CHARSET)
    .putBoolean(rate_to_count);

    if (id !=null) {
      hasher.putString(id, Const.UTF8_CHARSET);
    }

    hashes.add(hasher.hash());

    cached_hash = Hashing.combineOrdered(hashes);
    return cached_hash;
  }
  
  @Override
  public int compareTo(final RateConfig other) {
    return ComparisonChain.start()
        .compareTrueFirst(counter, other.counter)
        .compareTrueFirst(drop_resets, other.drop_resets)
        .compare(counter_max, other.counter_max)
        .compare(reset_value, other.reset_value)
        .compare(interval, other.interval)
        .compare(data_interval == null ? "" : data_interval, 
            other.data_interval == null ? "" : other.data_interval)
        .result();
  }
  
  /** @return A new builder to construct a RateOptions from. */
  public static Builder newBuilder() {
    return new Builder();
  }
  
  /**
   * Clones an options into a new builder.
   * @param options A non-null options to pull values from
   * @return A new builder populated with values from the given options.
   * @throws IllegalArgumentException if the options was null.
   * @since 3.0
   */
  public static Builder newBuilder(final RateConfig options) {
    if (options == null) {
      throw new IllegalArgumentException("RateOptions cannot be null.");
    }
    return new Builder()
        .setCounter(options.counter)
        .setCounterMax(options.counter_max)
        .setResetValue(options.reset_value)
        .setDropResets(options.drop_resets)
        .setInterval(options.interval)
        .setDataInterval(options.data_interval)
        .setRateToCount(options.rate_to_count);
  }
  
  /**
   * A builder for the rate options config for a query.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Builder extends BaseQueryNodeConfig.Builder<Builder, RateConfig> {
    @JsonProperty
    private boolean counter;
    @JsonProperty
    private boolean dropResets;
    @JsonProperty
    private long counterMax = DEFAULT_COUNTER_MAX;
    @JsonProperty
    private long resetValue = DEFAULT_RESET_VALUE;
    @JsonProperty
    private String interval = DEFAULT_INTERVAL;
    @JsonProperty
    private String dataInterval;
    @JsonProperty
    private boolean deltaOnly;
    @JsonProperty
    private boolean rateToCount;
    private TimeStamp start_time;
    private TimeStamp end_time;
    private RateFactory factory;
    
    Builder() {
      setType(RateFactory.TYPE);
    }
    
    public Builder setCounter(final boolean counter) {
      this.counter = counter;
      return this;
    }
    
    public Builder setDropResets(final boolean drop_resets) {
      this.dropResets = drop_resets;
      return this;
    }
    
    public Builder setCounterMax(final long counter_max) {
      this.counterMax = counter_max;
      return this;
    }
    
    public Builder setResetValue(final long counter_reset) {
      this.resetValue = counter_reset;
      return this;
    }
    
    public Builder setInterval(final String interval) {
      this.interval = interval;
      return this;
    }

    public Builder setDataInterval(final String data_interval) {
      dataInterval = data_interval;
      return this;
    }
    
    public Builder setDeltaOnly(final boolean delta_only) {
      this.deltaOnly = delta_only;
      return this;
    }
    
    public Builder setRateToCount(final boolean rate_to_count) {
      this.rateToCount = rate_to_count;
      return this;
    }
    
    public Builder setStartTime(final TimeStamp start_time) {
      this.start_time = start_time;
      return this;
    }
    
    public Builder setEndTime(final TimeStamp end_time) {
      this.end_time = end_time;
      return this;
    }
    
    public Builder setFactory(final RateFactory factory) {
      this.factory = factory;
      return this;
    }
    
    public RateConfig build() {
      return new RateConfig(this);
    }

    @Override
    public Builder self() {
      return this;
    }
  }
  
}
