/*
 *
 *  * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License").
 *  * You may not use this file except in compliance with the License.
 *  * A copy of the License is located at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * or in the "license" file accompanying this file. This file is distributed
 *  * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  * express or implied. See the License for the specific language governing
 *  * permissions and limitations under the License.
 *
 */

package com.amazon.opendistroforelasticsearch.indexmanagement.rollup

import com.amazon.opendistroforelasticsearch.indexmanagement.rollup.model.dimension.DateHistogram
import com.amazon.opendistroforelasticsearch.indexmanagement.rollup.model.dimension.Dimension
import com.amazon.opendistroforelasticsearch.indexmanagement.rollup.model.dimension.Histogram
import com.amazon.opendistroforelasticsearch.indexmanagement.rollup.model.dimension.Terms
import com.amazon.opendistroforelasticsearch.indexmanagement.rollup.settings.RollupSettings
import com.amazon.opendistroforelasticsearch.indexmanagement.rollup.util.getRollupJobs
import com.amazon.opendistroforelasticsearch.indexmanagement.rollup.util.rewriteSearchSourceBuilder
import org.apache.logging.log4j.LogManager
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.index.query.BoolQueryBuilder
import org.elasticsearch.index.query.BoostingQueryBuilder
import org.elasticsearch.index.query.ConstantScoreQueryBuilder
import org.elasticsearch.index.query.DisMaxQueryBuilder
import org.elasticsearch.index.query.MatchAllQueryBuilder
import org.elasticsearch.index.query.QueryBuilder
import org.elasticsearch.index.query.RangeQueryBuilder
import org.elasticsearch.index.query.TermQueryBuilder
import org.elasticsearch.index.query.TermsQueryBuilder
import org.elasticsearch.index.query.functionscore.FunctionScoreQueryBuilder
import org.elasticsearch.search.aggregations.AggregationBuilder
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramAggregationBuilder
import org.elasticsearch.search.aggregations.bucket.histogram.HistogramAggregationBuilder
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregationBuilder
import org.elasticsearch.search.aggregations.metrics.AvgAggregationBuilder
import org.elasticsearch.search.aggregations.metrics.MaxAggregationBuilder
import org.elasticsearch.search.aggregations.metrics.MinAggregationBuilder
import org.elasticsearch.search.aggregations.metrics.SumAggregationBuilder
import org.elasticsearch.search.aggregations.metrics.ValueCountAggregationBuilder
import org.elasticsearch.search.internal.ShardSearchRequest
import org.elasticsearch.tasks.Task
import org.elasticsearch.transport.TransportChannel
import org.elasticsearch.transport.TransportInterceptor
import org.elasticsearch.transport.TransportRequest
import org.elasticsearch.transport.TransportRequestHandler

class RollupInterceptor(
    val clusterService: ClusterService,
    val indexNameExpressionResolver: IndexNameExpressionResolver
) : TransportInterceptor {

    private val logger = LogManager.getLogger(javaClass)

    @Suppress("ComplexMethod", "SpreadOperator", "NestedBlockDepth", "LongMethod")
    override fun <T : TransportRequest> interceptHandler(
        action: String,
        executor: String,
        forceExecution: Boolean,
        actualHandler: TransportRequestHandler<T>
    ): TransportRequestHandler<T> {
        return object : TransportRequestHandler<T> {
            override fun messageReceived(request: T, channel: TransportChannel, task: Task) {
                if (request is ShardSearchRequest) {
                    val index = request.shardId().indexName
                    val isRollupIndex = RollupSettings.ROLLUP_INDEX.get(clusterService.state().metadata.index(index).settings)
                    if (isRollupIndex) {
                        val indices = request.indices().map { it.toString() }.toTypedArray()
                        val concreteIndices = indexNameExpressionResolver
                                .concreteIndexNames(clusterService.state(), request.indicesOptions(), *indices)

                        val hasNonRollupIndex = concreteIndices.any {
                            val isNonRollupIndex = !RollupSettings.ROLLUP_INDEX.get(clusterService.state().metadata.index(it).settings)
                            if (isNonRollupIndex) {
                                logger.warn("A non-rollup index cannot be searched with a rollup index [non-rollup-index=$it] [rollup-index=$index]")
                            }
                            isNonRollupIndex
                        }

                        if (hasNonRollupIndex) {
                            throw IllegalArgumentException("Cannot query rollup and normal indices in the same request")
                        }

                        val rollupJobs = clusterService.state().metadata.index(index).getRollupJobs()
                                ?: throw IllegalArgumentException("Could not find the mapping source for the index")

                        val queryDimensionTypesToFields = getQueryMetadata(request.source().query())
                        val (aggregateDimensionTypesToFields, aggregateMetricFieldsToTypes) = getAggregationMetadata(
                                request.source().aggregations()?.aggregatorFactories)
                        val dimensionTypesToFields: Map<String, Set<String>> =
                                (queryDimensionTypesToFields.keys + aggregateDimensionTypesToFields.keys)
                                        .associateWith { mergeSets(queryDimensionTypesToFields[it], aggregateDimensionTypesToFields[it]) }
                        val metricFieldsToTypes = mutableMapOf<String, Set<String>>()
                        metricFieldsToTypes.putAll(aggregateMetricFieldsToTypes)

                        // TODO: How does this job matching work with roles/security?
                        // TODO: Move to helper class or ext fn - this is veeeeery inefficient, but it works for development/testing
                        // The goal here is to find all the matching rollup jobs from the ones that exist on this rollup index
                        // A matching rollup job is one that has all the fields used in the aggregations in its own dimensions/metrics
                        val matchingRollupJobs = rollupJobs.filter { job ->
                            // We take the provided dimensionsTypesToFields and confirm for each entry that
                            // the given type (dimension type) and set (source fields) exists in the rollup job itself
                            // to verify that the query can be answered with the data from this rollup job
                            val hasAllDimensions = dimensionTypesToFields.entries.all { (type, set) ->
                                // The filteredDimensionsFields are all the source fields of a specific dimension type on this job
                                val filteredDimensionsFields = job.dimensions.filter { it.type.type == type }.map {
                                    when (it) {
                                        is DateHistogram -> it.sourceField
                                        is Histogram -> it.sourceField
                                        is Terms -> it.sourceField
                                        // TODO: can't throw - have to return early after overwriting parsedquery
                                        else -> throw IllegalArgumentException(
                                                "Found unsupported Dimension during search transformation [${it.type.type}]")
                                    }
                                }
                                // Confirm that the list of source fields on the rollup job's dimensions contains all of the fields in the user's
                                // aggregation
                                filteredDimensionsFields.containsAll(set)
                            }
                            // We take the provided metricFieldsToTypes and confirm for each entry that the given field (source field) and set
                            // (metric types) exists in the rollup job itself to verify that the query can be answered with the data from this
                            // rollup job
                            val hasAllMetrics = metricFieldsToTypes.entries.all { (field, set) ->
                                // The filteredMetrics are all the metric types that were computed for the given source field on this job
                                val filteredMetrics = job.metrics.find { it.sourceField == field }?.metrics?.map { it.type.type } ?: emptyList()
                                // Confirm that the list of metric aggregation types in the rollup job's metrics contains all of the metric types
                                // in the user's aggregation for this given source field
                                filteredMetrics.containsAll(set)
                            }

                            hasAllDimensions && hasAllMetrics
                        }

                        if (matchingRollupJobs.isEmpty()) {
                            // TODO: This needs to be more helpful and say what fields were missing
                            throw IllegalArgumentException("Could not find a rollup job that can answer this query")
                        }

                        // Very simple resolution to start: just take all the matching jobs that can answer the query and use the newest one
                        val matchedRollup = matchingRollupJobs.reduce { matched, new ->
                            if (matched.lastUpdateTime.isAfter(new.lastUpdateTime)) matched
                            else new
                        }

                        // only rebuild if there is necessity to rebuild
                        if (!(dimensionTypesToFields.isEmpty() && metricFieldsToTypes.isEmpty())) {
                            request.source(request.source().rewriteSearchSourceBuilder(matchedRollup))
                        }
                    }
                }
                actualHandler.messageReceived(request, channel, task)
            }
        }
    }

    @Suppress("ComplexMethod")
    private fun getAggregationMetadata(
        aggregationBuilders: Collection<AggregationBuilder>?,
        dimensionTypesToFields: MutableMap<String, MutableSet<String>> = mutableMapOf(),
        metricFieldsToTypes: MutableMap<String, MutableSet<String>> = mutableMapOf()
    ): Pair<Map<String, Set<String>>, Map<String, Set<String>>> {
        aggregationBuilders?.forEach {
            when (it) {
                is TermsAggregationBuilder -> {
                    dimensionTypesToFields.computeIfAbsent(it.type) { mutableSetOf() }.add(it.field())
                }
                is DateHistogramAggregationBuilder -> {
                    dimensionTypesToFields.computeIfAbsent(it.type) { mutableSetOf() }.add(it.field())
                }
                is HistogramAggregationBuilder -> {
                    dimensionTypesToFields.computeIfAbsent(it.type) { mutableSetOf() }.add(it.field())
                }
                is SumAggregationBuilder -> {
                    metricFieldsToTypes.computeIfAbsent(it.field()) { mutableSetOf() }.add(it.type)
                }
                is AvgAggregationBuilder -> {
                    metricFieldsToTypes.computeIfAbsent(it.field()) { mutableSetOf() }.add(it.type)
                }
                is MaxAggregationBuilder -> {
                    metricFieldsToTypes.computeIfAbsent(it.field()) { mutableSetOf() }.add(it.type)
                }
                is MinAggregationBuilder -> {
                    metricFieldsToTypes.computeIfAbsent(it.field()) { mutableSetOf() }.add(it.type)
                }
                is ValueCountAggregationBuilder -> {
                    metricFieldsToTypes.computeIfAbsent(it.field()) { mutableSetOf() }.add(it.type)
                }
            }
            if (it.subAggregations?.isNotEmpty() == true) {
                getAggregationMetadata(it.subAggregations, dimensionTypesToFields, metricFieldsToTypes)
            }
        }
        return dimensionTypesToFields to metricFieldsToTypes
    }

    @Suppress("ComplexMethod")
    private fun getQueryMetadata(
        query: QueryBuilder?,
        dimensionTypesToFields: MutableMap<String, MutableSet<String>> = mutableMapOf()
    ): Map<String, Set<String>> {
        if (query == null) {
            return dimensionTypesToFields
        }

        when (query) {
            is TermQueryBuilder -> {
                dimensionTypesToFields.computeIfAbsent(Dimension.Type.TERMS.type) { mutableSetOf() }.add(query.fieldName())
            }
            is TermsQueryBuilder -> {
                dimensionTypesToFields.computeIfAbsent(Dimension.Type.TERMS.type) { mutableSetOf() }.add(query.fieldName())
            }
            is RangeQueryBuilder -> {
                // TODO: looks like this can be applied on histograms as well, need additional logic
                dimensionTypesToFields.computeIfAbsent(Dimension.Type.DATE_HISTOGRAM.type) { mutableSetOf() }.add(query.fieldName())
            }
            is MatchAllQueryBuilder -> {
                // do nothing
            }
            is BoolQueryBuilder -> {
                query.must()?.forEach { this.getQueryMetadata(it, dimensionTypesToFields) }
                query.mustNot()?.forEach { this.getQueryMetadata(it, dimensionTypesToFields) }
                query.should()?.forEach { this.getQueryMetadata(it, dimensionTypesToFields) }
                query.filter()?.forEach { this.getQueryMetadata(it, dimensionTypesToFields) }
            }
            is BoostingQueryBuilder -> {
                query.positiveQuery()?.also { this.getQueryMetadata(it, dimensionTypesToFields) }
                query.negativeQuery()?.also { this.getQueryMetadata(it, dimensionTypesToFields) }
            }
            is ConstantScoreQueryBuilder -> {
                query.innerQuery()?.also { this.getQueryMetadata(it, dimensionTypesToFields) }
            }
            is DisMaxQueryBuilder -> {
                query.innerQueries().forEach { this.getQueryMetadata(it, dimensionTypesToFields) }
            }
            is FunctionScoreQueryBuilder -> {
                query.query().also { this.getQueryMetadata(it, dimensionTypesToFields) }
                query.filterFunctionBuilders().forEach { this.getQueryMetadata(it.filter, dimensionTypesToFields) }
            }
        }

        return dimensionTypesToFields
    }

    private fun mergeSets(first: Set<String>?, second: Set<String>?): Set<String> {
        val result: MutableSet<String> = HashSet()
        if (first != null) {
            result.addAll(first)
        }
        if (second != null) {
            result.addAll(second)
        }
        return result
    }
}
