//This file is part of OpenTSDB.
//Copyright (C) 2018-2021  The OpenTSDB Authors.
//
//Licensed under the Apache License, Version 2.0 (the "License");
//you may not use this file except in compliance with the License.
//You may obtain a copy of the License at
//
//http://www.apache.org/licenses/LICENSE-2.0
//
//Unless required by applicable law or agreed to in writing, software
//distributed under the License is distributed on an "AS IS" BASIS,
//WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//See the License for the specific language governing permissions and
//limitations under the License.
package net.opentsdb.query.processor.expressions;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import net.opentsdb.query.DefaultQueryResultId;
import org.junit.Before;
import org.junit.Test;
import org.powermock.reflect.Whitebox;

import com.google.common.collect.ImmutableMap;

import net.opentsdb.data.SecondTimeStamp;
import net.opentsdb.data.TimeSeries;
import net.opentsdb.data.TimeSeriesValue;
import net.opentsdb.data.types.numeric.NumericArrayTimeSeries;
import net.opentsdb.data.types.numeric.NumericArrayType;
import net.opentsdb.query.processor.expressions.ExpressionParseNode.ExpressionOp;
import net.opentsdb.query.processor.expressions.ExpressionParseNode.OperandType;
import net.opentsdb.query.processor.expressions.ExpressionParser.NumericLiteral;

public class TestExpressionNumericArrayIteratorLogical extends BaseNumericTest {

  private TimeSeries left;
  private TimeSeries right;
  
  @Before
  public void beforeLocal() {
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight("b")
        .setRightType(OperandType.VARIABLE)
        .setExpressionOp(ExpressionOp.OR)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
  }
  
  @Test
  public void longLong() throws Exception {
    left = new NumericArrayTimeSeries(LEFT_ID, 
        new SecondTimeStamp(60));
    ((NumericArrayTimeSeries) left).add(1);
    ((NumericArrayTimeSeries) left).add(0);
    ((NumericArrayTimeSeries) left).add(2);
    
    right = new NumericArrayTimeSeries(RIGHT_ID, 
        new SecondTimeStamp(60));
    ((NumericArrayTimeSeries) right).add(4);
    ((NumericArrayTimeSeries) right).add(0);
    ((NumericArrayTimeSeries) right).add(-1);
    
    ExpressionNumericArrayIterator iterator = 
        new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericArrayType> value = 
        (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 1, 0, 1 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
    
    // AND
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight(null)
        .setRightType(OperandType.NULL)
        .setExpressionOp(ExpressionOp.AND)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    iterator = new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    value =  (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 1, 0, 0 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());

    // AND w/ same operand
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
            .setLeft("a")
            .setLeftType(OperandType.VARIABLE)
            .setLeftId(new DefaultQueryResultId("m1", "m1"))
            .setRight("a")
            .setRightType(OperandType.VARIABLE)
            .setRightId(new DefaultQueryResultId("m1", "m1"))
            .setExpressionOp(ExpressionOp.AND)
            .setExpressionConfig(config)
            .setId("expression")
            .build();
    when(node.config()).thenReturn(expression_config);

    iterator = new ExpressionNumericArrayIterator(node, RESULT,
            (Map) ImmutableMap.builder()
                    .put(ExpressionTimeSeries.LEFT_KEY, left)
                    .build());
    assertTrue(iterator.hasNext());
    value =  (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 1, 0, 1 },
            value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
  }

  @Test
  public void longLongWithRate() throws Exception {
    left = new NumericArrayTimeSeries(LEFT_ID,
        new SecondTimeStamp(60));
    ((NumericArrayTimeSeries) left).add(1);
    ((NumericArrayTimeSeries) left).add(0);
    ((NumericArrayTimeSeries) left).add(2);

    right = new NumericArrayTimeSeries(RIGHT_ID,
        new SecondTimeStamp(60));
    ((NumericArrayTimeSeries) right).add(4);
    ((NumericArrayTimeSeries) right).add(0);

    ExpressionNumericArrayIterator iterator =
        new ExpressionNumericArrayIterator(node, RESULT,
            (Map) ImmutableMap.builder()
                .put(ExpressionTimeSeries.LEFT_KEY, left)
                .put(ExpressionTimeSeries.RIGHT_KEY, right)
                .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericArrayType> value =
        (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 1, 1 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(2, value.value().end());
    assertFalse(iterator.hasNext());

    // AND
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight(null)
        .setRightType(OperandType.NULL)
        .setExpressionOp(ExpressionOp.AND)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);

    iterator = new ExpressionNumericArrayIterator(node, RESULT,
        (Map) ImmutableMap.builder()
            .put(ExpressionTimeSeries.LEFT_KEY, left)
            .put(ExpressionTimeSeries.RIGHT_KEY, right)
            .build());
    assertTrue(iterator.hasNext());
    value =  (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 0, 0 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(2, value.value().end());
    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void longLongNot() throws Exception {
    left = new NumericArrayTimeSeries(LEFT_ID, 
        new SecondTimeStamp(60));
    ((NumericArrayTimeSeries) left).add(1);
    ((NumericArrayTimeSeries) left).add(0);
    ((NumericArrayTimeSeries) left).add(2);
    
    right = new NumericArrayTimeSeries(RIGHT_ID, 
        new SecondTimeStamp(60));
    ((NumericArrayTimeSeries) right).add(4);
    ((NumericArrayTimeSeries) right).add(0);
    ((NumericArrayTimeSeries) right).add(-1);
    
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight("b")
        .setRightType(OperandType.VARIABLE)
        .setExpressionOp(ExpressionOp.OR)
        .setExpressionConfig(config)
        .setNot(true)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    ExpressionNumericArrayIterator iterator = 
        new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericArrayType> value = 
        (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 0, 1, 0 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
    
    // AND
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight(null)
        .setRightType(OperandType.NULL)
        .setExpressionOp(ExpressionOp.AND)
        .setExpressionConfig(config)
        .setNot(true)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    iterator = new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    value =  (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 0, 1, 1 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void longDouble() throws Exception {
    left = new NumericArrayTimeSeries(LEFT_ID, 
        new SecondTimeStamp(60));
    ((NumericArrayTimeSeries) left).add(1);
    ((NumericArrayTimeSeries) left).add(0);
    ((NumericArrayTimeSeries) left).add(2);
    
    right = new NumericArrayTimeSeries(RIGHT_ID, 
        new SecondTimeStamp(60));
    ((NumericArrayTimeSeries) right).add(4.5);
    ((NumericArrayTimeSeries) right).add(0);
    ((NumericArrayTimeSeries) right).add(-1.5);
    
    ExpressionNumericArrayIterator iterator = 
        new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericArrayType> value = 
        (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 1, 0, 1 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
    
    // AND
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight(null)
        .setRightType(OperandType.NULL)
        .setExpressionOp(ExpressionOp.AND)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    iterator = new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    value =  (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 1, 0, 0 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void longDoubleNot() throws Exception {
    left = new NumericArrayTimeSeries(LEFT_ID, 
        new SecondTimeStamp(60));
    ((NumericArrayTimeSeries) left).add(1);
    ((NumericArrayTimeSeries) left).add(0);
    ((NumericArrayTimeSeries) left).add(2);
    
    right = new NumericArrayTimeSeries(RIGHT_ID, 
        new SecondTimeStamp(60));
    ((NumericArrayTimeSeries) right).add(4.5);
    ((NumericArrayTimeSeries) right).add(0);
    ((NumericArrayTimeSeries) right).add(-1.5);
    
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight("b")
        .setRightType(OperandType.VARIABLE)
        .setExpressionOp(ExpressionOp.OR)
        .setExpressionConfig(config)
        .setNot(true)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    ExpressionNumericArrayIterator iterator = 
        new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericArrayType> value = 
        (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 0, 1, 0 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
    
    // AND
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight(null)
        .setRightType(OperandType.NULL)
        .setExpressionOp(ExpressionOp.AND)
        .setExpressionConfig(config)
        .setNot(true)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    iterator = new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    value =  (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 0, 1, 1 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void doubleDouble() throws Exception {
    left = new NumericArrayTimeSeries(LEFT_ID, 
        new SecondTimeStamp(60));
    ((NumericArrayTimeSeries) left).add(1.1);
    ((NumericArrayTimeSeries) left).add(0.0);
    ((NumericArrayTimeSeries) left).add(2.66);
    
    right = new NumericArrayTimeSeries(RIGHT_ID, 
        new SecondTimeStamp(60));
    ((NumericArrayTimeSeries) right).add(4.5);
    ((NumericArrayTimeSeries) right).add(0);
    ((NumericArrayTimeSeries) right).add(-1.5);
    
    ExpressionNumericArrayIterator iterator = 
        new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericArrayType> value = 
        (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 1, 0, 1 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
    
    // AND
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight(null)
        .setRightType(OperandType.NULL)
        .setExpressionOp(ExpressionOp.AND)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    iterator = new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    value =  (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 1, 0, 0 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void fillNaN() throws Exception {
    left = new NumericArrayTimeSeries(LEFT_ID, 
        new SecondTimeStamp(60));
    ((NumericArrayTimeSeries) left).add(1.1);
    ((NumericArrayTimeSeries) left).add(Double.NaN);
    ((NumericArrayTimeSeries) left).add(Double.NaN);
    
    right = new NumericArrayTimeSeries(RIGHT_ID, 
        new SecondTimeStamp(60));
    ((NumericArrayTimeSeries) right).add(4.5);
    ((NumericArrayTimeSeries) right).add(Double.NaN);
    ((NumericArrayTimeSeries) right).add(-1.5);
    
    ExpressionNumericArrayIterator iterator = 
        new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericArrayType> value = 
        (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 1, 0, 0 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
    
    // AND
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight(null)
        .setRightType(OperandType.NULL)
        .setExpressionOp(ExpressionOp.AND)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    iterator = new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    value =  (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 1, 0, 0 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void fillNaNNot() throws Exception {
    left = new NumericArrayTimeSeries(LEFT_ID, 
        new SecondTimeStamp(60));
    ((NumericArrayTimeSeries) left).add(1.1);
    ((NumericArrayTimeSeries) left).add(Double.NaN);
    ((NumericArrayTimeSeries) left).add(Double.NaN);
    
    right = new NumericArrayTimeSeries(RIGHT_ID, 
        new SecondTimeStamp(60));
    ((NumericArrayTimeSeries) right).add(4.5);
    ((NumericArrayTimeSeries) right).add(Double.NaN);
    ((NumericArrayTimeSeries) right).add(-1.5);
    
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight("b")
        .setRightType(OperandType.VARIABLE)
        .setExpressionOp(ExpressionOp.OR)
        .setExpressionConfig(config)
        .setNot(true)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    ExpressionNumericArrayIterator iterator = 
        new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericArrayType> value = 
        (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 0, 1, 1 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
    
    // AND
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight("b")
        .setRightType(OperandType.VARIABLE)
        .setExpressionOp(ExpressionOp.AND)
        .setNot(true)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    iterator = new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    value =  (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 0, 1, 1 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void fillNaNInfectious() throws Exception {
    left = new NumericArrayTimeSeries(LEFT_ID, 
        new SecondTimeStamp(60));
    ((NumericArrayTimeSeries) left).add(1.1);
    ((NumericArrayTimeSeries) left).add(Double.NaN);
    ((NumericArrayTimeSeries) left).add(Double.NaN);
    
    right = new NumericArrayTimeSeries(RIGHT_ID, 
        new SecondTimeStamp(60));
    ((NumericArrayTimeSeries) right).add(4.5);
    ((NumericArrayTimeSeries) right).add(Double.NaN);
    ((NumericArrayTimeSeries) right).add(-1.5);
    
    ExpressionNumericArrayIterator iterator = 
        new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    Whitebox.setInternalState(iterator, "infectious_nan", true);
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericArrayType> value = 
        (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new double[] { 1, Double.NaN, Double.NaN },
        value.value().doubleArray(), 0.001);
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
    
    // AND
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight(null)
        .setRightType(OperandType.NULL)
        .setExpressionOp(ExpressionOp.AND)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    iterator = new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    Whitebox.setInternalState(iterator, "infectious_nan", true);
    assertTrue(iterator.hasNext());
    value =  (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new double[] { 1, Double.NaN, Double.NaN },
        value.value().doubleArray(), 0.001);
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void fillNaNInfectiousNot() throws Exception {
    left = new NumericArrayTimeSeries(LEFT_ID, 
        new SecondTimeStamp(60));
    ((NumericArrayTimeSeries) left).add(1.1);
    ((NumericArrayTimeSeries) left).add(Double.NaN);
    ((NumericArrayTimeSeries) left).add(Double.NaN);
    
    right = new NumericArrayTimeSeries(RIGHT_ID, 
        new SecondTimeStamp(60));
    ((NumericArrayTimeSeries) right).add(4.5);
    ((NumericArrayTimeSeries) right).add(Double.NaN);
    ((NumericArrayTimeSeries) right).add(-1.5);
    
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight("b")
        .setRightType(OperandType.VARIABLE)
        .setExpressionOp(ExpressionOp.OR)
        .setExpressionConfig(config)
        .setNot(true)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    ExpressionNumericArrayIterator iterator = 
        new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    Whitebox.setInternalState(iterator, "infectious_nan", true);
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericArrayType> value = 
        (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new double[] { 0, Double.NaN, Double.NaN },
        value.value().doubleArray(), 0.001);
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
    
    // AND
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight("b")
        .setRightType(OperandType.VARIABLE)
        .setExpressionOp(ExpressionOp.AND)
        .setExpressionConfig(config)
        .setNot(true)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    iterator = new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    Whitebox.setInternalState(iterator, "infectious_nan", true);
    assertTrue(iterator.hasNext());
    value =  (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new double[] { 0, Double.NaN, Double.NaN },
        value.value().doubleArray(), 0.001);
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void numericLiteralLeft() throws Exception {
    NumericLiteral literal = mock(NumericLiteral.class);
    when(literal.isInteger()).thenReturn(true);
    when(literal.longValue()).thenReturn(42L);
    
    right = new NumericArrayTimeSeries(RIGHT_ID, 
        new SecondTimeStamp(60));
    ((NumericArrayTimeSeries) right).add(4);
    ((NumericArrayTimeSeries) right).add(0);
    ((NumericArrayTimeSeries) right).add(-1);
    
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft(literal)
        .setLeftType(OperandType.LITERAL_NUMERIC)
        .setRight("b")
        .setRightType(OperandType.VARIABLE)
        .setExpressionOp(ExpressionOp.OR)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    ExpressionNumericArrayIterator iterator = 
        new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericArrayType> value = 
        (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 1, 1, 1 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
    
    // AND
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft(literal)
        .setLeftType(OperandType.LITERAL_NUMERIC)
        .setRight("b")
        .setRightType(OperandType.VARIABLE)
        .setExpressionOp(ExpressionOp.AND)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    iterator = new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    value =  (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 1, 0, 0 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void numericLiteralRight() throws Exception {
    NumericLiteral literal = mock(NumericLiteral.class);
    when(literal.isInteger()).thenReturn(true);
    when(literal.longValue()).thenReturn(42L);
    
    left = new NumericArrayTimeSeries(LEFT_ID, 
        new SecondTimeStamp(60));
    ((NumericArrayTimeSeries) left).add(1);
    ((NumericArrayTimeSeries) left).add(0);
    ((NumericArrayTimeSeries) left).add(2);
    
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight(literal)
        .setRightType(OperandType.LITERAL_NUMERIC)
        .setExpressionOp(ExpressionOp.OR)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    ExpressionNumericArrayIterator iterator = 
        new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericArrayType> value = 
        (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 1, 1, 1 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
    
    // AND
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight(literal)
        .setRightType(OperandType.LITERAL_NUMERIC)
        .setExpressionOp(ExpressionOp.AND)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    iterator = new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .build());
    assertTrue(iterator.hasNext());
    value =  (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 1, 0, 1 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
  }

  @Test
  public void booleanLeft() throws Exception {
    right = new NumericArrayTimeSeries(RIGHT_ID, 
        new SecondTimeStamp(60));
    ((NumericArrayTimeSeries) right).add(4);
    ((NumericArrayTimeSeries) right).add(0);
    ((NumericArrayTimeSeries) right).add(-1);
    
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft(true)
        .setLeftType(OperandType.LITERAL_BOOL)
        .setRight("b")
        .setRightType(OperandType.VARIABLE)
        .setExpressionOp(ExpressionOp.OR)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    ExpressionNumericArrayIterator iterator = 
        new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericArrayType> value = 
        (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 1, 1, 1 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
    
    // AND
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft(true)
        .setLeftType(OperandType.LITERAL_BOOL)
        .setRight("b")
        .setRightType(OperandType.VARIABLE)
        .setExpressionOp(ExpressionOp.AND)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    iterator = new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    value =  (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 1, 0, 0 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
    
    // with false == 0
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft(false)
        .setLeftType(OperandType.LITERAL_BOOL)
        .setRight("b")
        .setRightType(OperandType.VARIABLE)
        .setExpressionOp(ExpressionOp.OR)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    iterator = new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    value = (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 1, 0, 0 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
    
    // AND
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft(false)
        .setLeftType(OperandType.LITERAL_BOOL)
        .setRight("b")
        .setRightType(OperandType.VARIABLE)
        .setExpressionOp(ExpressionOp.AND)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    iterator = new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    value =  (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 0, 0, 0 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void booleanRight() throws Exception {
    left = new NumericArrayTimeSeries(LEFT_ID, 
        new SecondTimeStamp(60));
    ((NumericArrayTimeSeries) left).add(1);
    ((NumericArrayTimeSeries) left).add(0);
    ((NumericArrayTimeSeries) left).add(2);
    
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight(true)
        .setRightType(OperandType.LITERAL_BOOL)
        .setExpressionOp(ExpressionOp.OR)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    ExpressionNumericArrayIterator iterator = 
        new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericArrayType> value = 
        (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 1, 1, 1 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
    
    // AND
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight(true)
        .setRightType(OperandType.LITERAL_BOOL)
        .setExpressionOp(ExpressionOp.AND)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    iterator = new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .build());
    assertTrue(iterator.hasNext());
    value =  (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 1, 0, 1 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
    
    // with false == 0
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight(false)
        .setRightType(OperandType.LITERAL_BOOL)
        .setExpressionOp(ExpressionOp.OR)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    iterator = new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .build());
    assertTrue(iterator.hasNext());
    value = (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 1, 0, 1 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
    
    // AND
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight(false)
        .setRightType(OperandType.LITERAL_BOOL)
        .setExpressionOp(ExpressionOp.AND)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    iterator = new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .build());
    assertTrue(iterator.hasNext());
    value =  (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 0, 0, 0 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void nullLeft() throws Exception {
    right = new NumericArrayTimeSeries(RIGHT_ID, 
        new SecondTimeStamp(60));
    ((NumericArrayTimeSeries) right).add(4);
    ((NumericArrayTimeSeries) right).add(0);
    ((NumericArrayTimeSeries) right).add(-1);
    
    ExpressionConfig cfg = ExpressionConfig.newBuilder()
        .setExpression("a || b")
        .setJoinConfig(JOIN_CONFIG)
        .addInterpolatorConfig(NUMERIC_CONFIG)
        .setId("e1")
        .build();
    
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft(null)
        .setLeftType(OperandType.NULL)
        .setRight("b")
        .setRightType(OperandType.VARIABLE)
        .setExpressionOp(ExpressionOp.OR)
        .setExpressionConfig(cfg)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    when(node.expressionConfig()).thenReturn(cfg);
    
    ExpressionNumericArrayIterator iterator = 
        new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericArrayType> value = 
        (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 1, 0, 0 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
    
    // AND
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft(null)
        .setLeftType(OperandType.NULL)
        .setRight("b")
        .setRightType(OperandType.VARIABLE)
        .setExpressionOp(ExpressionOp.AND)
        .setExpressionConfig(cfg)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    iterator = new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    value =  (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 0, 0, 0 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void nullLeftNot() throws Exception {
    right = new NumericArrayTimeSeries(RIGHT_ID, 
        new SecondTimeStamp(60));
    ((NumericArrayTimeSeries) right).add(4);
    ((NumericArrayTimeSeries) right).add(0);
    ((NumericArrayTimeSeries) right).add(-1);
    
    ExpressionConfig cfg = ExpressionConfig.newBuilder()
        .setExpression("a || b")
        .setJoinConfig(JOIN_CONFIG)
        .addInterpolatorConfig(NUMERIC_CONFIG)
        .setId("e1")
        .build();
    
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft(null)
        .setLeftType(OperandType.NULL)
        .setRight("b")
        .setRightType(OperandType.VARIABLE)
        .setExpressionOp(ExpressionOp.OR)
        .setExpressionConfig(cfg)
        .setNot(true)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    when(node.expressionConfig()).thenReturn(cfg);
    
    ExpressionNumericArrayIterator iterator = 
        new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericArrayType> value = 
        (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 0, 1, 1 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
    
    // AND
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft(null)
        .setLeftType(OperandType.NULL)
        .setRight("b")
        .setRightType(OperandType.VARIABLE)
        .setExpressionOp(ExpressionOp.AND)
        .setExpressionConfig(cfg)
        .setNot(true)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    iterator = new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertTrue(iterator.hasNext());
    value =  (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 1, 1, 1 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
  }

  @Test
  public void nullLeftInfectiousNan() throws Exception {
    ExpressionConfig e = ExpressionConfig.newBuilder()
        .setExpression("a or b")
        .setJoinConfig(JOIN_CONFIG)
        .setInfectiousNan(true)
        .addInterpolatorConfig(NUMERIC_CONFIG)
        .setId("e1")
        .build();
    when(node.expressionConfig()).thenReturn(e);
    
    right = new NumericArrayTimeSeries(RIGHT_ID, 
        new SecondTimeStamp(60));
    ((NumericArrayTimeSeries) right).add(4);
    ((NumericArrayTimeSeries) right).add(0);
    ((NumericArrayTimeSeries) right).add(-1);
    
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft(null)
        .setLeftType(OperandType.NULL)
        .setRight("b")
        .setRightType(OperandType.VARIABLE)
        .setExpressionOp(ExpressionOp.OR)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    ExpressionNumericArrayIterator iterator = 
        new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertFalse(iterator.hasNext());
    
    // AND
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft(null)
        .setLeftType(OperandType.NULL)
        .setRight("b")
        .setRightType(OperandType.VARIABLE)
        .setExpressionOp(ExpressionOp.AND)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    iterator = new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.RIGHT_KEY, right)
              .build());
    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void nullRight() throws Exception {
    left = new NumericArrayTimeSeries(LEFT_ID, 
        new SecondTimeStamp(60));
    ((NumericArrayTimeSeries) left).add(1);
    ((NumericArrayTimeSeries) left).add(0);
    ((NumericArrayTimeSeries) left).add(2);
    
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight(null)
        .setRightType(OperandType.NULL)
        .setExpressionOp(ExpressionOp.OR)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    ExpressionNumericArrayIterator iterator = 
        new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericArrayType> value = 
        (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 1, 0, 1 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
    
    // AND
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight(null)
        .setRightType(OperandType.NULL)
        .setExpressionOp(ExpressionOp.AND)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    iterator = new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .build());
    assertTrue(iterator.hasNext());
    value =  (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 0, 0, 0 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void nullRightNot() throws Exception {
    left = new NumericArrayTimeSeries(LEFT_ID, 
        new SecondTimeStamp(60));
    ((NumericArrayTimeSeries) left).add(1);
    ((NumericArrayTimeSeries) left).add(0);
    ((NumericArrayTimeSeries) left).add(2);
    
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight(null)
        .setRightType(OperandType.NULL)
        .setExpressionOp(ExpressionOp.OR)
        .setExpressionConfig(config)
        .setNot(true)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    ExpressionNumericArrayIterator iterator = 
        new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .build());
    assertTrue(iterator.hasNext());
    TimeSeriesValue<NumericArrayType> value = 
        (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 0, 1, 0 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
    
    // AND
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight(null)
        .setRightType(OperandType.NULL)
        .setExpressionOp(ExpressionOp.AND)
        .setExpressionConfig(config)
        .setNot(true)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    iterator = new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .build());
    assertTrue(iterator.hasNext());
    value =  (TimeSeriesValue<NumericArrayType>) iterator.next();
    assertArrayEquals(new long[] { 1, 1, 1 },
        value.value().longArray());
    assertEquals(60, value.timestamp().epoch());
    assertEquals(0, value.value().offset());
    assertEquals(3, value.value().end());
    assertFalse(iterator.hasNext());
  }
  
  @Test
  public void nullRightInfectious() throws Exception {
    left = new NumericArrayTimeSeries(LEFT_ID, 
        new SecondTimeStamp(60));
    ((NumericArrayTimeSeries) left).add(1);
    ((NumericArrayTimeSeries) left).add(0);
    ((NumericArrayTimeSeries) left).add(2);
    
    ExpressionConfig cfg = ExpressionConfig.newBuilder()
        .setExpression("a || b")
        .setJoinConfig(JOIN_CONFIG)
        .addInterpolatorConfig(NUMERIC_CONFIG)
        .setInfectiousNan(true)
        .setId("e1")
        .build();
    
    when(node.expressionConfig()).thenReturn(cfg);
    
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight(null)
        .setRightType(OperandType.NULL)
        .setExpressionOp(ExpressionOp.OR)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    ExpressionNumericArrayIterator iterator = 
        new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .build());
    assertFalse(iterator.hasNext());
    
    // AND
    expression_config = (ExpressionParseNode) ExpressionParseNode.newBuilder()
        .setLeft("a")
        .setLeftType(OperandType.VARIABLE)
        .setRight(null)
        .setRightType(OperandType.NULL)
        .setExpressionOp(ExpressionOp.AND)
        .setExpressionConfig(config)
        .setId("expression")
        .build();
    when(node.config()).thenReturn(expression_config);
    
    iterator = new ExpressionNumericArrayIterator(node, RESULT, 
            (Map) ImmutableMap.builder()
              .put(ExpressionTimeSeries.LEFT_KEY, left)
              .build());
    assertFalse(iterator.hasNext());
  }
  
  
}
