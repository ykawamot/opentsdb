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
package net.opentsdb.query.processor.topn;

import net.opentsdb.data.TimeSeries;
import net.opentsdb.data.TimeSeriesDataType;
import net.opentsdb.data.TimeSeriesValue;
import net.opentsdb.data.TypedTimeSeriesIterator;
import net.opentsdb.data.types.numeric.MutableNumericValue;
import net.opentsdb.data.types.numeric.NumericType;
import net.opentsdb.data.types.numeric.aggregators.NumericAggregator;
import net.opentsdb.data.types.numeric.aggregators.NumericAggregatorFactory;
import net.opentsdb.exceptions.QueryDownstreamException;
import net.opentsdb.query.QueryNode;
import net.opentsdb.query.QueryResult;

import java.io.IOException;
import java.util.Optional;

/**
 * Aggregates an entire numeric series into a single value.
 * 
 * @since 3.0
 */
public class TopNNumericAggregator {

  /** The aggregator. */
  private final NumericAggregator aggregator;
  
  /** The parent node. */
  protected final TopN node;
  
  /** The series we'll pull from. */
  protected final TimeSeries series;
  
  /**
   * Package private ctor.
   * @param node The non-null node.
   * @param result The non-null result.
   * @param source The non-null source.
   */
  TopNNumericAggregator(final QueryNode node, 
                        final QueryResult result,
                        final TimeSeries source) {
    this.node = (TopN) node;
    this.series = source;
    NumericAggregatorFactory agg_factory = node.pipelineContext().tsdb()
        .getRegistry().getPlugin(NumericAggregatorFactory.class, 
            ((TopNConfig) node.config()).getAggregator());
    if (agg_factory == null) {
      throw new IllegalArgumentException("No aggregator found for type: " 
          + ((TopNConfig) node.config()).getAggregator());
    }
    aggregator = agg_factory.newAggregator(
        ((TopNConfig) node.config()).getInfectiousNan());
  }
  
  /** @return Perform the aggregation. If no data is present, return null. */
  NumericType run() {
    final Optional<TypedTimeSeriesIterator<? extends TimeSeriesDataType>> optional =
        series.iterator(NumericType.TYPE);
    if (!optional.isPresent()) {
      return null;
    }
    try (final TypedTimeSeriesIterator<? extends TimeSeriesDataType> iterator = 
        optional.get()) {
      long[] long_values = new long[16];
      double[] double_values = null;
      int idx = 0;
      
      while(iterator.hasNext()) {
        @SuppressWarnings("unchecked")
        final TimeSeriesValue<NumericType> value = 
            (TimeSeriesValue<NumericType>) iterator.next();
        if (value.value() == null) {
          continue;
        }
        
        if (value.value().isInteger() && long_values != null) {
          if (idx >= long_values.length) {
            // grow
            long[] temp = new long[long_values.length + 
                                   (long_values.length >= 1024 ? 32 : long_values.length)];
            System.arraycopy(long_values, 0, temp, 0, long_values.length);
            long_values = temp;
          }
          long_values[idx++] = value.value().longValue();
        } else {
          if (double_values == null) {
            // shift
            double_values = new double[long_values.length];
            for (int i = 0; i < idx; i++) {
              double_values[i] = (double) long_values[i];
            }
            long_values = null;
          }
          
          if (idx >= double_values.length) {
            // grow
            double[] temp = new double[double_values.length + 
                                       (double_values.length >= 1024 ? 32 : double_values.length)];
            System.arraycopy(double_values, 0, temp, 0, double_values.length);
            double_values = temp;
          }
          double_values[idx++] = value.value().toDouble();
        }
      }
      
      if (idx <= 0) {
        return null;
      }
      
      final MutableNumericValue dp = new MutableNumericValue();
      if (long_values != null) {
        aggregator.run(long_values, 0, idx, dp);
      } else {
        aggregator.run(double_values, 0, idx, ((TopNConfig) node.config()).getInfectiousNan(), dp);
      }
      return dp.value();
    } catch (IOException e) {
      throw new QueryDownstreamException(e.getMessage(), e);
    }
  }
  
}
