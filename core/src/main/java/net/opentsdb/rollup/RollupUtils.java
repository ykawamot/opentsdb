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
package net.opentsdb.rollup;

import java.util.Calendar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.opentsdb.core.Const;
import net.opentsdb.storage.schemas.tsdb1x.NumericCodec;
import net.opentsdb.storage.schemas.tsdb1x.Schema;
import net.opentsdb.utils.Bytes;

/**
 * Static util class for dealing with parsing and storing rolled up data points
 * @since 2.4
 */
public final class RollupUtils {
  private static final Logger LOG = LoggerFactory.getLogger(RollupUtils.class);
  
  /**
   * Enum for rollup fallback control.
   * @since 2.4
   */
  public static enum RollupUsage {
    ROLLUP_RAW, //Don't use rollup data, instead use raw data
    ROLLUP_NOFALLBACK, //Use rollup data, and don't fallback on no data
    ROLLUP_FALLBACK, //Use rollup data and fallback to next best match on data
    ROLLUP_FALLBACK_RAW; //Use rollup data and fallback to raw on no data
    
    /**
     * Parse and transform a string to ROLLUP_USAGE object
     * @param str String to be parsed
     * @return enum param tells how to use the rollup data
     */
    public static RollupUsage parse(String str) {
      RollupUsage def = ROLLUP_NOFALLBACK;
      
      if (str != null) {
        try {
          def = RollupUsage.valueOf(str.toUpperCase());
        }
        catch(IllegalArgumentException ex) {
          LOG.warn("Unknown rollup usage, " + str + ", use default usage - which"
                  + "uses raw data but don't fallback on no data");
        }
      }
      
      return def;
    }
    
    /**
     * Whether to fallback to next best match or raw
     * @return true means fall back else false
     */
    public boolean fallback() {
      return this == ROLLUP_FALLBACK || this == ROLLUP_FALLBACK_RAW;
    }
  }
  
  public static final byte AGGREGATOR_MASK = (byte) 0x7F;
  
  public static final byte COMPACTED_MASK = (byte) 0x80;
  
  /** The rollup qualifier delimiter character */
  public static final String ROLLUP_QUAL_DELIM = ":";
  
  private RollupUtils() {
    // Do not instantiate me brah!
  }
  
  /**
   * Calculates the base time for a rollup interval, the time that can be
   * stored in the row key.
   * @param timestamp The data point timestamp to calculate from in seconds
   * or milliseconds
   * @param interval The configured interval object to use for calcaulting
   * the base time with a valid span of 'h', 'd', 'm' or 'y'
   * @return A base time as a unix epoch timestamp in seconds
   * @throws IllegalArgumentException if the timestamp is negative or the interval
   * has an unsupported span
   */
  public static int getRollupBasetime(final long timestamp, 
                                      final RollupInterval interval) {
    if (timestamp < 0) {
      throw new IllegalArgumentException("Not supporting negative "
          + "timestamps at this time: " + timestamp);
    }
    
    // avoid instantiating a calendar at all costs! If we are based on an hourly
    // span then use the old method of snapping to the hour
    if (interval.getUnits() == 'h') {
      int modulo = Schema.MAX_RAW_TIMESPAN;
      if (interval.getUnitMultiplier() > 1) {
        modulo = interval.getUnitMultiplier() * 60 * 60;
      }
      if ((timestamp & Const.SECOND_MASK) != 0) {
        // drop the ms timestamp to seconds to calculate the base timestamp
        return (int) ((timestamp / 1000) - 
            ((timestamp / 1000) % modulo));
      } else {
        return (int) (timestamp - (timestamp % modulo));
      }
    } else {
      final long time_milliseconds = (timestamp & Const.SECOND_MASK) != 0 ?
          timestamp : timestamp * 1000;
      
      // gotta go the long way with the calendar to snap to an appropriate
      // daily, monthly or weekly boundary
      final Calendar calendar = Calendar.getInstance(Const.UTC_TZ);
      calendar.setTimeInMillis(time_milliseconds);
      
      // zero out the hour, minutes, seconds
      calendar.set(Calendar.HOUR_OF_DAY, 0);
      calendar.set(Calendar.MINUTE, 0);
      calendar.set(Calendar.SECOND, 0);
      
      switch (interval.getUnits()) {
      case 'd':
        // all set via the zeros above
        break;
      case 'n':
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        break;
      case 'y':
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        calendar.set(Calendar.MONTH, 0); // 0 for January
        break;
      default:
        throw new IllegalArgumentException("Unrecogznied span: " + interval);
      }
      
      return (int) (calendar.getTimeInMillis() / 1000);
    }
  }

  /**
   * Builds a rollup column qualifier, prepending the append function as a single
   * byte id based on the rollup config. The remaining two bytes encode the
   * offset within the given interval while the last four bits are reserved for
   * the length and type flags similar to a raw value. I.e.
   * {@code <agg><offset(flag)>
   *   n  2 bytes }
   * @param timestamp The data point timestamp in unix epoch seconds.
   * @param flags The length and type (float || int) flags for the value
   * @param aggregator_id The numeric ID of the aggregator the value maps to.
   * @param interval The RollupInterval object with data about the interval
   * @return An n byte array to use as the qualifier
   * @throws IllegalArgumentException if the aggregator is null or empty or the
   * timestamp is too far from the base time to fit within the interval.
   */
  public static byte[] buildRollupQualifier(final long timestamp,
                                            final short flags,
                                            final int aggregator_id,
                                            final RollupInterval interval) {
    return buildRollupQualifier(timestamp, 
        getRollupBasetime(timestamp, interval), flags, aggregator_id, interval);
  }
  
  /**
   * Builds a rollup column qualifier, prepending the append function as a single
   * byte id based on the rollup config. The remaining two bytes encode the
   * offset within the given interval while the last four bits are reserved for
   * the length and type flags similar to a raw value. I.e.
   * {@code <agg><offset(flag)>
   *   n  2 bytes }
   * @param timestamp The data point timestamp in unix epoch seconds.
   * @param basetime The base timestamp to calculate the offset from 
   * @param flags The length and type (float || int) flags for the value
   * @param aggregator_id The numeric ID of the aggregator the value maps to.
   * @param interval The RollupInterval object with data about the interval
   * @return An n byte array to use as the qualifier
   * @throws IllegalArgumentException if the aggregator is null or empty or the
   * timestamp is too far from the base time to fit within the interval.
   */
  public static byte[] buildRollupQualifier(final long timestamp,
                                            final int basetime,
                                            final short flags,
                                            final int aggregator_id,
                                            final RollupInterval interval) {
    final byte[] qualifier = new byte[3];
    final int time_seconds = (int) ((timestamp & Const.SECOND_MASK) != 0 ?
            timestamp / 1000 : timestamp);

    // we shouldn't have a divide by 0 here as the rollup config validator makes
    // sure the interval is positive
    int offset = (time_seconds - basetime) / interval.getIntervalSeconds();
    if (offset >= interval.getIntervals()) {
      throw new IllegalArgumentException("Offset of " + offset + " was greater "
          + "than the configured intervals " + interval.getIntervals());
    }
   
    // shift the offset over 4 bits then apply the flag
    offset = offset << NumericCodec.FLAG_BITS;
    offset = offset | flags;
    qualifier[0] = (byte) aggregator_id;
    System.arraycopy(Bytes.fromShort((short) offset), 0, qualifier, 1, 2);

    return qualifier;
  }
  
  /**
   * Builds a rollup column qualifier, prepending the appender as a string
   * along with a colon as delimiter
   * @param aggregator The aggregator used to generate the data
   * @param timestamp The raw timestamp we'll snap to an interval
   * @param flags The length and type (float || int) flags for the value
   * @param interval The RollupInterval object with data about the interval
   * @return An n byte array with a string prefix.
   */
  public static byte[] buildStringRollupQualifier(final String aggregator,
                                                  final long timestamp,
                                                  final short flags,
                                                  final DefaultRollupInterval interval) {
    return buildStringRollupQualifier(aggregator, timestamp, 
        getRollupBasetime(timestamp, interval), flags, interval);
  }
  
  /**
   * Builds a rollup column qualifier, prepending the appender as a string
   * along with a colon as delimiter
   * @param aggregator The aggregator used to generate the data
   * @param timestamp The raw timestamp we'll snap to an interval
   * @param basetime The base timestamp to calculate the offset from 
   * @param flags The length and type (float || int) flags for the value
   * @param interval The RollupInterval object with data about the interval
   * @return An n byte array with a string prefix.
   */
  public static byte[] buildStringRollupQualifier(final String aggregator,
                                                  final long timestamp,
                                                  final int basetime,
                                                  final short flags,
                                                  final DefaultRollupInterval interval) {
    final String prefix = aggregator.toLowerCase() + ROLLUP_QUAL_DELIM;
    final byte[] qualifier = new byte[prefix.length() + 2];
    System.arraycopy(prefix.getBytes(Const.ASCII_CHARSET), 0, qualifier, 
        0, prefix.length());
    final int time_seconds = (int) ((timestamp & Const.SECOND_MASK) != 0 ?
        timestamp / 1000 : timestamp);
    // we shouldn't have a divide by 0 here as the rollup config validator makes
    // sure the interval is positive
    int offset = (time_seconds - basetime) / interval.getIntervalSeconds();
    if (offset >= interval.getIntervals()) {
      throw new IllegalArgumentException("Offset of " + offset + " was greater "
          + "than the configured intervals " + interval.getIntervals());
    }
    
    // shift the offset over 4 bits then apply the flag
    offset = offset << NumericCodec.FLAG_BITS;
    offset = offset | flags;
    System.arraycopy(Bytes.fromShort((short) offset), 0, qualifier, prefix.length(), 2);
    
    return qualifier;
  }
  
  /**
   * Builds an append value with the offset then value in one byte array that 
   * should be sent to storage as an "append" value.
   * @param timestamp The raw timestamp that will be snapped to an interval.
   * @param flags The flags showing how the value is encoded.
   * @param interval The interval.
   * @param value The value to write.
   * @return A concatenated byte array.
   */
  public static byte[] buildAppendRollupValue(final long timestamp,
                                              final short flags,
                                              final DefaultRollupInterval interval,
                                              final byte[] value) {
    return buildAppendRollupValue(timestamp, 
        getRollupBasetime(timestamp, interval), flags, interval, value);
  }
  
  /**
   * Builds an append value with the offset then value in one byte array that 
   * should be sent to storage as an "append" value.
   * @param timestamp The raw timestamp that will be snapped to an interval.
   * @param basetime The base time snapped to an interval.
   * @param flags The flags showing how the value is encoded.
   * @param interval The interval.
   * @param value The value to write.
   * @return A concatenated byte array.
   */
  public static byte[] buildAppendRollupValue(final long timestamp,
                                              final int basetime,
                                              final short flags,
                                              final RollupInterval interval,
                                              final byte[] value) {
    final int time_seconds = (int) ((timestamp & Const.SECOND_MASK) != 0 ?
    timestamp / 1000 : timestamp);
    
    // we shouldn't have a divide by 0 here as the rollup config validator makes
    // sure the interval is positive
    int offset = (time_seconds - basetime) / interval.getIntervalSeconds();
    if (offset >= interval.getIntervals()) {
      throw new IllegalArgumentException("Offset of " + offset + " was greater "
          + "than the configured intervals " + interval.getIntervals());
    }
    
    // shift the offset over 4 bits then apply the flag
    offset = offset << NumericCodec.FLAG_BITS;
    offset = offset | flags;
    byte[] result = new byte[2 + value.length];
    Bytes.setShort(result, (short) offset);
    System.arraycopy(value, 0, result, 2, value.length);
    return result;
  }
  
  /**
   * Returns the absolute timestamp of a data point qualifier in milliseconds
   * @param qualifier The qualifier to parse
   * @param base_time The base time, in seconds, from the row key
   * @param interval The RollupInterval object with data about the interval
   * @param offset An offset within the byte array
   * @return The absolute timestamp in milliseconds
   */
  public static long getTimestampFromRollupQualifier(final byte[] qualifier, 
                                                     final long base_time,
                                                     final RollupInterval interval,
                                                     final int offset) {
    return (base_time * 1000) + 
            getOffsetFromRollupQualifier(qualifier, offset, interval);
  }
  
  /**
   * Returns the absolute timestamp of a data point qualifier in milliseconds
   * @param qualifier The qualifier to parse
   * @param base_time The base time, in seconds, from the row key
   * @param interval  The RollupInterval object with data about the interval
   * @return The absolute timestamp in milliseconds
   */
  public static long getTimestampFromRollupQualifier(final int qualifier,
                                                     final long base_time,
                                                     final RollupInterval interval) {
    return (base_time * 1000) + getOffsetFromRollupQualifier(qualifier, interval);
  }

  /**
   * Returns the offset in milliseconds from the row base timestamp from a data
   * point qualifier at the given offset (for compacted columns)
   * @param qualifier The qualifier to parse
   * @param byte_offset An offset within the byte array
   * @param interval The RollupInterval object with data about the interval
   * @return The offset in milliseconds from the base time
   */
  public static long getOffsetFromRollupQualifier(final byte[] qualifier, 
                                                  final int byte_offset,
                                                  final RollupInterval interval) {
    
    long offset = 0;
    
    if ((qualifier[byte_offset] & Const.MS_BYTE_FLAG) == Const.MS_BYTE_FLAG) {
      offset = ((Bytes.getUnsignedInt(qualifier, byte_offset) & 0x0FFFFFC0) 
        >>> Const.MS_FLAG_BITS)/1000;
    } else {
      offset = (Bytes.getUnsignedShort(qualifier, byte_offset) & 0xFFFF) 
        >>> NumericCodec.FLAG_BITS;
    }
    
    return offset * interval.getIntervalSeconds() * 1000;
  }
  
  /**
   * Returns the offset in milliseconds from the row base timestamp from a data
   * point qualifier at the given offset (for compacted columns)
   * @param qualifier The qualifier to parse
   * @param interval The RollupInterval object with data about the interval
   * @return The offset in milliseconds from the base time
   */
  public static long getOffsetFromRollupQualifier(final int qualifier, 
                                                  final RollupInterval interval) {

    long offset = 0;
    if ((qualifier & Const.MS_FLAG) == Const.MS_FLAG) {
      LOG.warn("Unexpected rollup qualifier in milliseconds: " + qualifier 
          + " for interval " + interval);
      offset = (qualifier & 0x0FFFFFC0) >>> (Const.MS_FLAG_BITS) / 1000;
    } else {
      offset = (qualifier & 0xFFFF) >>> NumericCodec.FLAG_BITS;
    }
    return offset * interval.getIntervalSeconds() * 1000;
  }

  /**
   * Determines whether or not the column has been compacted or appended.
   * @param qualifier A non-null and non-empty byte array.
   * @return True if the column was compacted, false if not.
   */
  public static boolean isCompacted(final byte[] qualifier) {
    return (qualifier[0] & COMPACTED_MASK) != 0;
  }
}
