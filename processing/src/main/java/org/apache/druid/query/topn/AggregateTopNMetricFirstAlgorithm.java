/*
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

package org.apache.druid.query.topn;

import org.apache.druid.collections.NonBlockingPool;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.query.ColumnSelectorPlus;
import org.apache.druid.query.CursorGranularizer;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.aggregation.AggregatorUtil;
import org.apache.druid.query.aggregation.PostAggregator;
import org.apache.druid.segment.Cursor;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * This {@link TopNAlgorithm} is tailored to processing aggregates on high cardility columns which are likely to have
 * larger result sets. Internally it uses a 2 phase approach to compute the top-n result using the
 * {@link PooledTopNAlgorithm} for each phase. The first phase is to process the segment with only the order-by
 * aggregator to compute which values constitute the top 'n' results. With this information, a actual result set
 * is computed by a second run of the {@link PooledTopNAlgorithm}, this time with all aggregators, but only considering
 * the values from the 'n' results to avoid performing any aggregations that would have been thrown away for results
 * that didn't make the top-n.
 */
public class AggregateTopNMetricFirstAlgorithm implements TopNAlgorithm<int[], TopNParams>
{
  private final TopNQuery query;
  private final TopNCursorInspector cursorInspector;
  private final NonBlockingPool<ByteBuffer> bufferPool;

  public AggregateTopNMetricFirstAlgorithm(
      TopNQuery query,
      TopNCursorInspector cursorInspector,
      NonBlockingPool<ByteBuffer> bufferPool
  )
  {
    this.query = query;
    this.cursorInspector = cursorInspector;
    this.bufferPool = bufferPool;
  }

  @Override
  public TopNParams makeInitParams(ColumnSelectorPlus selectorPlus, Cursor cursor, CursorGranularizer granularizer)
  {
    return new TopNParams(selectorPlus, cursor, granularizer, Integer.MAX_VALUE);
  }

  @Override
  public void run(
      TopNParams params,
      TopNResultBuilder resultBuilder,
      int[] ints,
      @Nullable TopNQueryMetrics queryMetrics
  )
  {
    final String metric = query.getTopNMetricSpec().getMetricName(query.getDimensionSpec());
    Pair<List<AggregatorFactory>, List<PostAggregator>> condensedAggPostAggPair =
        AggregatorUtil.condensedAggregators(query.getAggregatorSpecs(), query.getPostAggregatorSpecs(), metric);

    if (condensedAggPostAggPair.lhs.isEmpty() && condensedAggPostAggPair.rhs.isEmpty()) {
      throw new ISE("Can't find the topN metric");
    }
    // Run topN for only a single metric
    TopNQuery singleMetricQuery = new TopNQueryBuilder(query)
        .aggregators(condensedAggPostAggPair.lhs)
        .postAggregators(condensedAggPostAggPair.rhs)
        .build();
    final TopNResultBuilder singleMetricResultBuilder = BaseTopNAlgorithm.makeResultBuilder(params, singleMetricQuery);

    PooledTopNAlgorithm singleMetricAlgo = new PooledTopNAlgorithm(singleMetricQuery, cursorInspector, bufferPool);
    PooledTopNAlgorithm.PooledTopNParams singleMetricParam = null;
    int[] dimValSelector;
    try {
      singleMetricParam = singleMetricAlgo.makeInitParams(params.getSelectorPlus(), params.getCursor(), params.getGranularizer());
      singleMetricAlgo.run(
          singleMetricParam,
          singleMetricResultBuilder,
          null,
          null // Don't collect metrics during the preparation run.
      );

      // Get only the topN dimension values
      dimValSelector = getDimValSelectorForTopNMetric(singleMetricParam, singleMetricResultBuilder);
    }
    finally {
      singleMetricAlgo.cleanup(singleMetricParam);
    }

    PooledTopNAlgorithm allMetricAlgo = new PooledTopNAlgorithm(query, cursorInspector, bufferPool);
    PooledTopNAlgorithm.PooledTopNParams allMetricsParam = null;
    try {
      // reset cursor since we call run again
      params.getCursor().reset();
      params.getGranularizer().advanceToBucket(params.getGranularizer().getCurrentInterval());
      // Run topN for all metrics for top N dimension values
      allMetricsParam = allMetricAlgo.makeInitParams(params.getSelectorPlus(), params.getCursor(), params.getGranularizer());
      allMetricAlgo.run(
          allMetricsParam,
          resultBuilder,
          dimValSelector,
          queryMetrics
      );
    }
    finally {
      allMetricAlgo.cleanup(allMetricsParam);
    }
  }

  @Override
  public void cleanup(TopNParams params)
  {
  }

  private int[] getDimValSelectorForTopNMetric(TopNParams params, TopNResultBuilder resultBuilder)
  {
    if (params.getCardinality() < 0) {
      throw new UnsupportedOperationException("Cannot operate on a dimension with unknown cardinality");
    }

    int[] dimValSelector = new int[params.getCardinality()];
    Arrays.fill(dimValSelector, SKIP_POSITION_VALUE);

    Iterator<DimValHolder> dimValIter = resultBuilder.getTopNIterator();
    while (dimValIter.hasNext()) {
      int dimValIndex = (Integer) dimValIter.next().getDimValIndex();
      dimValSelector[dimValIndex] = INIT_POSITION_VALUE;
    }

    return dimValSelector;
  }
}
