/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.query.runtime.operator.window.range;

import java.util.ArrayList;
import java.util.List;
import org.apache.pinot.common.utils.DataSchema;
import org.apache.pinot.query.planner.logical.RexExpression;
import org.apache.pinot.query.runtime.operator.WindowAggregateOperator;


public class DenseRankWindowFunction extends RangeWindowFunction {

  public DenseRankWindowFunction(RexExpression.FunctionCall aggCall, String functionName, DataSchema inputSchema,
      WindowAggregateOperator.OrderSetInfo orderSetInfo) {
    super(aggCall, functionName, inputSchema, orderSetInfo);
  }

  @Override
  public List<Object> processRows(List<Object[]> rows) {
    List<Object> result = new ArrayList<>();
    int rank = 1;
    Object[] lastRow = null;
    for (Object[] row : rows) {
      if (lastRow == null) {
        result.add(rank);
      } else {
        if (compareRows(row, lastRow) != 0) {
          rank++;
        }
        result.add(rank);
      }
      lastRow = row;
    }
    return result;
  }
}
