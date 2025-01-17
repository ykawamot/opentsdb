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
package net.opentsdb.query.processor.expressions;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Sets;
import com.google.common.reflect.TypeToken;

import net.opentsdb.data.TimeSeries;
import net.opentsdb.data.TimeSeriesDataType;
import net.opentsdb.data.TimeSeriesValue;
import net.opentsdb.data.TimeStamp;
import net.opentsdb.data.TimeStamp.Op;
import net.opentsdb.data.types.numeric.MutableNumericSummaryValue;
import net.opentsdb.data.types.numeric.NumericSummaryType;
import net.opentsdb.data.types.numeric.NumericType;
import net.opentsdb.exceptions.QueryDownstreamException;
import net.opentsdb.query.QueryNode;
import net.opentsdb.query.QueryResult;
import net.opentsdb.query.interpolation.QueryInterpolator;
import net.opentsdb.query.interpolation.QueryInterpolatorConfig;
import net.opentsdb.query.interpolation.QueryInterpolatorFactory;
import net.opentsdb.query.interpolation.types.numeric.NumericInterpolatorConfig;
import net.opentsdb.query.interpolation.types.numeric.NumericSummaryInterpolatorConfig;
import net.opentsdb.query.processor.expressions.ExpressionParseNode.OperandType;

/**
 * An expression iterator over summary data.
 * 
 * <b>WARNING</b> Currently this implementation simply performs the same 
 * operation over <i>every</i> summary available in each value per
 * iteration. So if we have a SUM and a COUNT and we're dividing left by
 * right, you'll get a result where SUM => left SUM / right SUM and 
 * COUNT => left COUNT / right COUNT.
 * 
 * TODO - we can setup rules around handling different types of summaries.
 * 
 * @since 3.0
 */
public class ExpressionNumericSummaryIterator extends 
    BaseExpressionNumericIterator<NumericSummaryType> {

  /** Interpolators. May be null for literals.*/
  protected QueryInterpolator<NumericSummaryType> left_interpolator;
  protected QueryInterpolator<NumericSummaryType> right_interpolator;
  
  /** The data point set and returned by the iterator. */
  protected final MutableNumericSummaryValue dp;
  
  /** A set, cleared and populated on each iteration, of the union of 
   * summaries available at the time. */
  protected final Set<Integer> summaries_available;
  
  @SuppressWarnings("unchecked")
  ExpressionNumericSummaryIterator(final QueryNode node, 
                                   final QueryResult result,
                                   final Map<String, TimeSeries> sources) {
    super(node, result, sources);
    dp = new MutableNumericSummaryValue();
    
    if (sources.get(ExpressionTimeSeries.LEFT_KEY) == null) {
      left_interpolator = null;
      if (((BinaryExpressionNode) node).config().getLeftType() 
            == OperandType.LITERAL_BOOL ||
         ((BinaryExpressionNode) node).config().getLeftType() 
            == OperandType.LITERAL_NUMERIC) {
      }
    } else {
      QueryInterpolatorConfig interpolator_config = 
          ((BinaryExpressionNode) node).expressionConfig().interpolatorConfig(
              NumericSummaryType.TYPE, 
              (String) ((ExpressionParseNode) this.node.config()).getLeft());
      if (interpolator_config == null) {
        interpolator_config = 
            ((BinaryExpressionNode) node).expressionConfig()
              .interpolatorConfig(NumericSummaryType.TYPE);
      }
      // still null, then we fail over to NumericType
      if (interpolator_config == null) {
        interpolator_config = 
            ((BinaryExpressionNode) node).expressionConfig().interpolatorConfig(
                NumericType.TYPE, 
                  (String) ((ExpressionParseNode) this.node.config()).getLeft());
        if (interpolator_config == null) {
          interpolator_config = 
              ((BinaryExpressionNode) node).expressionConfig()
                .interpolatorConfig(NumericType.TYPE);
        }
        
        if (interpolator_config == null) {
          throw new IllegalArgumentException("No NumericSummaryType or "
              + "NumericType interpolator config found.");
        }
        
        NumericSummaryInterpolatorConfig.Builder nsic = 
            NumericSummaryInterpolatorConfig.newBuilder()
            .setDefaultFillPolicy(
                ((NumericInterpolatorConfig) interpolator_config).getFillPolicy())
            .setDefaultRealFillPolicy(
                ((NumericInterpolatorConfig) interpolator_config).getRealFillPolicy());
        // we need expected summaries. Without reading the data we don't know
        // what to expect. So grab em all
        for (final int summary : result.rollupConfig().getAggregationIds().values()) {
          nsic.addExpectedSummary(summary);
        }
        interpolator_config = nsic
            .setDataType(NumericSummaryType.TYPE.toString())
            .setType(null)
            .build();
      }
      
      final QueryInterpolatorFactory factory = 
          node.pipelineContext().tsdb().getRegistry().getPlugin(
              QueryInterpolatorFactory.class, 
              interpolator_config.getType());
      if (factory == null) {
        throw new IllegalArgumentException("No interpolator factory found for: " + 
            (interpolator_config.getType() == null ? "Default" : 
              interpolator_config.getType()));
      }
      
      left_interpolator = (QueryInterpolator<NumericSummaryType>) factory.newInterpolator(
          NumericSummaryType.TYPE, sources.get(ExpressionTimeSeries.LEFT_KEY), 
          interpolator_config);
      has_next = left_interpolator.hasNext();
      if (has_next) {
        next_ts.update(left_interpolator.nextReal());
      }
    }
    
    if (sources.get(ExpressionTimeSeries.RIGHT_KEY) == null) {
      right_interpolator = null;
      if (((BinaryExpressionNode) node).config().getRightType() 
            != OperandType.LITERAL_BOOL &&
          ((BinaryExpressionNode) node).config().getRightType() 
            != OperandType.LITERAL_NUMERIC) {
//        has_next = false;
//        summaries_available = null;
//        return;
      }
    } else {
      QueryInterpolatorConfig interpolator_config = 
          ((BinaryExpressionNode) node).expressionConfig().interpolatorConfig(
              NumericSummaryType.TYPE, 
              (String) ((ExpressionParseNode) this.node.config()).getRight());
      if (interpolator_config == null) {
        interpolator_config = 
            ((BinaryExpressionNode) node).expressionConfig()
              .interpolatorConfig(NumericSummaryType.TYPE);
      }
      // still null, then we fail over to NumericType
      if (interpolator_config == null) {
        interpolator_config = 
            ((BinaryExpressionNode) node).expressionConfig().interpolatorConfig(
                NumericType.TYPE, 
                  (String) ((ExpressionParseNode) this.node.config()).getRight());
        if (interpolator_config == null) {
          interpolator_config = 
              ((BinaryExpressionNode) node).expressionConfig()
                .interpolatorConfig(NumericType.TYPE);
        }
        
        if (interpolator_config == null) {
          throw new IllegalArgumentException("No NumericSummaryType or "
              + "NumericType interpolator config found.");
        }
        
        NumericSummaryInterpolatorConfig.Builder nsic = 
            NumericSummaryInterpolatorConfig.newBuilder()
            .setDefaultFillPolicy(
                ((NumericInterpolatorConfig) interpolator_config).getFillPolicy())
            .setDefaultRealFillPolicy(
                ((NumericInterpolatorConfig) interpolator_config).getRealFillPolicy());
        // we need expected summaries. Without reading the data we don't know
        // what to expect. So grab em all
        for (final int summary : result.rollupConfig().getAggregationIds().values()) {
          nsic.addExpectedSummary(summary);
        }
        interpolator_config = nsic
            .setDataType(NumericSummaryType.TYPE.toString())
            .setType(null)
            .build();
      }
      
      final QueryInterpolatorFactory factory = 
          node.pipelineContext().tsdb().getRegistry().getPlugin(
              QueryInterpolatorFactory.class, 
              interpolator_config.getType());
      if (factory == null) {
        throw new IllegalArgumentException("No interpolator factory found for: " + 
            interpolator_config.getType() == null ? "Default" : 
              interpolator_config.getType());
      }
      
      right_interpolator = (QueryInterpolator<NumericSummaryType>) factory.newInterpolator(
          NumericSummaryType.TYPE, sources.get(ExpressionTimeSeries.RIGHT_KEY), 
          interpolator_config);
      if (has_next) {
        has_next = right_interpolator.hasNext();
        if (right_interpolator.hasNext()) {
          next_ts.update(right_interpolator.nextReal());
        }
      } else {
        if (right_interpolator.hasNext() && 
            right_interpolator.nextReal().compare(Op.LT, next_ts)) {
          next_ts.update(right_interpolator.nextReal());
        }
      }
      has_next = right_interpolator.hasNext();
    }
    
    // final sanity check
    if (left_interpolator == null && right_interpolator == null) {
      throw new IllegalStateException("Must have at least one time "
          + "series in an expression.");
    }
    
    summaries_available = Sets.newHashSet();
  }

  @Override
  public TimeSeriesValue<? extends TimeSeriesDataType> next() {
    has_next = false;
    next_next_ts.setMax();
    summaries_available.clear();
    dp.clear();
    
    final NumericSummaryType left;
    final NumericSummaryType right;
    
    if (left_interpolator != null && right_interpolator != null) {
      left = left_interpolator.next(next_ts).value();
      right = right_interpolator.next(next_ts).value();
      
      if (left_interpolator.hasNext()) {
        has_next = true;
        next_next_ts.update(left_interpolator.nextReal());
      }
      if (right_interpolator.hasNext()) {
        has_next = true;
        if (right_interpolator.nextReal().compare(Op.LT, next_next_ts)) {
          next_next_ts.update(right_interpolator.nextReal());
        }
      }
    } else if (left_interpolator == null) {
      left = null;
      right = right_interpolator.next(next_ts).value();
      
      if (right_interpolator.hasNext()) {
        has_next = true;
        next_next_ts.update(right_interpolator.nextReal());
      }
    } else {
      left = left_interpolator.next(next_ts).value();
      ExpressionParseNode config = node.config();
      // check if left and right have same operand
      if(config.getLeftId() != null &&
         config.getRightId() != null &&
         config.getLeftId().equals(config.getRightId())) {
        right = left;
      } else {
        right = null;
      }
      
      if (left_interpolator.hasNext()) {
        has_next = true;
        next_next_ts.update(left_interpolator.nextReal());
      }
    }
    
    if (left != null) {
      summaries_available.addAll(left.summariesAvailable());
    }
    if (right != null) {
      summaries_available.addAll(right.summariesAvailable());
    }
    
    for (final int summary : summaries_available) {
      final NumericType left_value = left == null ? 
          left_literal : left.value(summary);
      final NumericType right_value = right == null ? 
          right_literal : right.value(summary);
      final NumericType result;
      switch (((ExpressionParseNode) node.config()).getOperator()) {
      // logical
      case OR:
      case AND:
        result = logical(left_value, right_value);
        break;
      // relational
      case EQ:
      case NE:
      case LE:
      case GE:
      case LT:
      case GT:
        result = relation(left_value, right_value);
        break;
      // arithmetic
      case ADD:
      case SUBTRACT:
        result = additive(left_value, right_value);
        break;
      case DIVIDE:
        result = divide(left_value, right_value);
        break;
      case MULTIPLY:
        result = multiply(left_value, right_value);
        break;
      case MOD:
        result = mod(left_value, right_value);
        break;
      default:
        throw new QueryDownstreamException("Expression iterator was "
            + "told to handle the unexpected operator: " 
            + ((ExpressionParseNode) node.config()).getOperator());
      }
      
      if (result != null) {
        dp.resetValue(summary, value);
      }
    }
    dp.resetTimestamp(next_ts);
    next_ts.update(next_next_ts);
    return dp;
  }

  @Override
  public TimeStamp timestamp() {
    return dp.timestamp();
  }

  @Override
  public NumericSummaryType value() {
    return dp;
  }

  @Override
  public TypeToken<NumericSummaryType> type() {
    return NumericSummaryType.TYPE;
  }

  @Override
  public TypeToken<? extends TimeSeriesDataType> getType() {
    return NumericSummaryType.TYPE;
  }

  @Override
  public void close() {
    if (left_interpolator != null) {
      try {
        left_interpolator.close();
      } catch (IOException e) {
        // don't bother logging.
        e.printStackTrace();
      }
      left_interpolator = null;
    }
    
    if (right_interpolator != null) {
      try {
        right_interpolator.close();
      } catch (IOException e) {
        // don't bother logging.
        e.printStackTrace();
      }
      right_interpolator = null;
    }
  }
  
}
