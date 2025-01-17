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
package net.opentsdb.data.types.numeric.aggregators;

import net.opentsdb.core.TSDBPlugin;
import net.opentsdb.data.AggregatorFactory;

/**
 * A factory for generating aggregators.
 * 
 * @since 3.0
 */
public interface NumericAggregatorFactory extends TSDBPlugin, AggregatorFactory {
  
  /**
   * Instantiates a new aggregator.
   * @param infectious_nan Whether or not NaNs are infectious.
   * @return A new instance of the aggregator.
   */
  public NumericAggregator newAggregator(final boolean infectious_nan);
  
}
