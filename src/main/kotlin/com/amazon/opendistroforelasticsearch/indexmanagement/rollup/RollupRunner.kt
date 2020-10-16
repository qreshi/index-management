/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amazon.opendistroforelasticsearch.indexmanagement.rollup

import com.amazon.opendistroforelasticsearch.indexmanagement.elasticapi.retry
import com.amazon.opendistroforelasticsearch.indexmanagement.elasticapi.suspendUntil
import com.amazon.opendistroforelasticsearch.indexmanagement.rollup.action.index.IndexRollupAction
import com.amazon.opendistroforelasticsearch.indexmanagement.rollup.action.index.IndexRollupRequest
import com.amazon.opendistroforelasticsearch.indexmanagement.rollup.action.index.IndexRollupResponse
import com.amazon.opendistroforelasticsearch.indexmanagement.rollup.model.Rollup
import com.amazon.opendistroforelasticsearch.indexmanagement.rollup.model.RollupMetadata
import com.amazon.opendistroforelasticsearch.indexmanagement.rollup.model.incrementStats
import com.amazon.opendistroforelasticsearch.indexmanagement.rollup.model.mergeStats
import com.amazon.opendistroforelasticsearch.jobscheduler.spi.JobExecutionContext
import com.amazon.opendistroforelasticsearch.jobscheduler.spi.LockModel
import com.amazon.opendistroforelasticsearch.jobscheduler.spi.ScheduledJobParameter
import com.amazon.opendistroforelasticsearch.jobscheduler.spi.ScheduledJobRunner
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.apache.logging.log4j.LogManager
import org.elasticsearch.action.bulk.BackoffPolicy
import org.elasticsearch.action.support.WriteRequest
import org.elasticsearch.client.Client
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.unit.TimeValue
import org.elasticsearch.common.xcontent.NamedXContentRegistry
import org.elasticsearch.script.ScriptService
import org.elasticsearch.search.aggregations.bucket.composite.InternalComposite

@Suppress("TooManyFunctions")
object RollupRunner : ScheduledJobRunner,
    CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("RollupRunner")) {

    private val logger = LogManager.getLogger(javaClass)

    private lateinit var clusterService: ClusterService
    private lateinit var client: Client
    private lateinit var xContentRegistry: NamedXContentRegistry
    private lateinit var scriptService: ScriptService
    private lateinit var settings: Settings
    private lateinit var rollupMapperService: RollupMapperService
    private lateinit var rollupIndexer: RollupIndexer
    private lateinit var rollupSearchService: RollupSearchService
    private lateinit var rollupMetadataService: RollupMetadataService

    fun registerClusterService(clusterService: ClusterService): RollupRunner {
        this.clusterService = clusterService
        return this
    }

    fun registerClient(client: Client): RollupRunner {
        this.client = client
        return this
    }

    fun registerNamedXContentRegistry(xContentRegistry: NamedXContentRegistry): RollupRunner {
        this.xContentRegistry = xContentRegistry
        return this
    }

    fun registerScriptService(scriptService: ScriptService): RollupRunner {
        this.scriptService = scriptService
        return this
    }

    fun registerSettings(settings: Settings): RollupRunner {
        this.settings = settings
        return this
    }

    fun registerMapperService(rollupMapperService: RollupMapperService): RollupRunner {
        this.rollupMapperService = rollupMapperService
        return this
    }

    fun registerIndexer(rollupIndexer: RollupIndexer): RollupRunner {
        this.rollupIndexer = rollupIndexer
        return this
    }

    fun registerSearcher(rollupSearchService: RollupSearchService): RollupRunner {
        this.rollupSearchService = rollupSearchService
        return this
    }

    fun registerMetadataServices(
        rollupMetadataService: RollupMetadataService
    ): RollupRunner {
        this.rollupMetadataService = rollupMetadataService
        return this
    }

    fun registerConsumers(): RollupRunner {
        return this
    }

    @Suppress("ComplexMethod")
    override fun runJob(job: ScheduledJobParameter, context: JobExecutionContext) {
        if (job !is Rollup) {
            throw IllegalArgumentException("Invalid job type, found ${job.javaClass.simpleName} with id: ${context.jobId}")
        }

        launch {
            var metadata: RollupMetadata? = null
            try {
                if (job.metadataID != null) {
                    metadata = when (val getMetadataResult = rollupMetadataService.getExistingMetadata(job.metadataID)) {
                        is RollupMetadataService.MetadataResult.Success -> getMetadataResult.metadata
                        is RollupMetadataService.MetadataResult.NoMetadata -> null
                        is RollupMetadataService.MetadataResult.Failure ->
                            throw RollupMetadataException("Failed to get existing rollup metadata [${job.metadataID}]", getMetadataResult.cause)
                    }
                }
            } catch (e: RollupMetadataException) {
                // If the metadata was not able to be retrieved, the exception will be logged and the job run will be a no-op
                logger.error(e.message, e.cause)
                return@launch
            }

            // TODO: Move isJobValid() logic to some transport action and call here instead of inside runRollupJob()
            //   otherwise situations where source/target index are deleted are not caught until shouldProcessRollup is true

            // Check if rollup should be processed before acquiring the lock
            // If metadata does not exist, it will either be initialized for the first time or it will be recreated to communicate the failed state
            if (rollupSearchService.shouldProcessRollup(job, metadata)) {
                // Attempt to acquire lock
                var lock: LockModel? = null
                try {
                    BackoffPolicy.exponentialBackoff(TimeValue.timeValueMillis(1000), 3).retry(logger) {
                        lock = context.lockService.suspendUntil { acquireLock(job, context, it) }
                    }
                } catch (e: Exception) {
                    logger.error("Error trying to acquireLock", e)
                }
                if (lock == null) {
                    logger.debug("Could not acquire lock for ${job.id}")
                } else {
                    runRollupJob(job, context)
                    // Release lock
                    val released: Boolean = context.lockService.suspendUntil { release(lock, it) }
                    if (!released) {
                        logger.debug("Could not release lock for ${job.id}")
                    }
                }
            }
        }
    }

    // TODO: Clean up runner
    // TODO: Scenario: The rollup job is finished, but I (the user) want to redo it all again
    // TODO: need to get local version of job to see if page size has been changed
    /*
    * TODO situations:
    *  There is a rollup.metadataID and no metadata doc -> create new metadata doc with FAILED status?
    *  There is a rollup.metadataID and doc but theres no target index?
    *        -> index was deleted -> just recreate (but we would have to start over)? Or move to FAILED?
    *  There is a rollup.metadataID and doc but target index is not rollup index?
    *        -> index was deleted and recreated as non rollup -> move to FAILED
    *  There is a rollup.metadataID and doc but theres no job in target index?
    *        -> index was deleted and recreated as rollup -> just recreate (but we would have to start over)? Or move to FAILED?
    * */
    @Suppress("ReturnCount", "NestedBlockDepth", "ComplexMethod", "LongMethod")
    private suspend fun runRollupJob(job: Rollup, context: JobExecutionContext) {
        try {
            if (!isJobValid(job)) return

            var metadata = when (val initMetadataResult = rollupMetadataService.init(job)) {
                is RollupMetadataService.MetadataResult.Success -> initMetadataResult.metadata
                is RollupMetadataService.MetadataResult.NoMetadata -> return // No-op this execution
                is RollupMetadataService.MetadataResult.Failure ->
                    throw RollupMetadataException("Failed to initialize rollup metadata", initMetadataResult.cause)
            }

            if (metadata.status == RollupMetadata.Status.FAILED) {
                disableJob(job, metadata)
                return
            }

            // If metadata was created for the first time, update job with the id
            var updatableJob = job
            if (updatableJob.metadataID == null && metadata.status == RollupMetadata.Status.INIT) {
                when (val updateRollupJobResult = updateRollupJob(updatableJob.copy(metadataID = metadata.id), metadata)) {
                    is RollupResult.Success -> updatableJob = updateRollupJobResult.rollup
                    is RollupResult.Failure -> {
                        logger.error("Updating the rollup job [${updatableJob.id}] with metadata id [${metadata.id}]")
                        return // Exit runner early
                    }
                }
            }

            // TODO: before creating target index we also validate the source index exists?
            //  and anything else that would cause this to get delayed right away like invalid fields
            val successful = rollupMapperService.init(updatableJob)
            if (!successful) {
                // TODO: More helpful error messaging
                setFailedMetadataAndDisableJob(updatableJob, "Failed to initialize the target index", metadata)
                return
            }

            while (rollupSearchService.shouldProcessRollup(updatableJob, metadata)) {
                do {
                    try {
                        val compositeResponse = rollupSearchService.executeCompositeSearch(updatableJob, metadata)
                        val internalComposite: InternalComposite = compositeResponse.aggregations.get(updatableJob.id)
                        metadata = metadata.incrementStats(compositeResponse, internalComposite)
                        val indexStats = rollupIndexer.indexRollups(updatableJob, internalComposite)
                        metadata = rollupMetadataService.updateMetadata(updatableJob, metadata.mergeStats(indexStats), internalComposite)
                    } catch (e: RollupMetadataException) {
                        // Rethrow this exception so it doesn't get consumed here
                        throw e
                    } catch (e: Exception) {
                        // TODO: Remove this general exception catch since it will allow the rollup to keep going
                        logger.error("Failed to rollup ", e)
                    }
                } while (metadata.afterKey != null)
            }

            if (!updatableJob.continuous) {
                if (listOf(RollupMetadata.Status.STOPPED, RollupMetadata.Status.FINISHED, RollupMetadata.Status.FAILED).contains(metadata.status)) {
                    disableJob(updatableJob, metadata)
                }
            }
        } catch (e: RollupMetadataException) {
            // In most scenarios in the runner, the metadata will be used to communicate the result to the user
            // If change to the metadata itself fails, there is nothing else to relay state change
            // In these cases, the cause of the metadata operation will be logged here and the runner execution will exit
            logger.error(e.message, e.cause)
        }
    }

    /**
     * Updates a rollup job.
     *
     * Takes in the metadata for the rollup job as well. This metadata should be create/updated
     * before passing it to this method as it will not create/update the metadata during normal conditions.
     *
     * However, in the case that the update to the rollup job fails, the provided metadata will be set to FAILED
     * status and updated to reflect the change.
     */
    private suspend fun updateRollupJob(job: Rollup, metadata: RollupMetadata): RollupResult {
        try {
            val req = IndexRollupRequest(rollup = job, refreshPolicy = WriteRequest.RefreshPolicy.IMMEDIATE)
            val res: IndexRollupResponse = client.suspendUntil { execute(IndexRollupAction.INSTANCE, req, it) }
            // TODO: Verify the seqNo/primterm got updated
            return RollupResult.Success(res.rollup)
        } catch (e: Exception) {
            // TODO: Catching general exceptions for now, can make more granular
            // Set metadata to failed since update to rollup job failed
            val errorMessage = "An error occurred when updating rollup job [${job.id}]"
            return when (val setFailedMetadataResult = rollupMetadataService.setFailedMetadata(job, errorMessage, metadata)) {
                is RollupMetadataService.MetadataResult.Success -> RollupResult.Failure(errorMessage, e)
                // If the metadata update failed as well, throw an exception to end the runner execution
                is RollupMetadataService.MetadataResult.Failure ->
                    throw RollupMetadataException(setFailedMetadataResult.message, setFailedMetadataResult.cause)
                // Should not get NoMetadata here
                is RollupMetadataService.MetadataResult.NoMetadata ->
                    throw RollupMetadataException("Unexpected state when updating metadata", null)
            }
        }
    }

    // TODO: Source index could be a pattern but it's used at runtime so it could match new indices which weren't matched before
    //  which means we always need to validate the source index on every execution?
    // This fn: Should we even proceed to execute this job?
    // Validations could be broken down into:
    // Is this job even allowed to run? i.e. it was in a FAILED state and someone just switched enabled to true, do we actually retry it?
    // Do the job mappings make sense, is it possible to execute this job
    // etc. etc. etc.
    @Suppress("ReturnCount")
    private suspend fun isJobValid(job: Rollup): Boolean {
        // TODO: Handle exceptions
        val metadata = if (job.metadataID != null) {
            rollupMetadataService.getExistingMetadata(job.metadataID)
        } else null

        if (!rollupMapperService.isSourceIndexValid(job.sourceIndex)) {
            setFailedMetadataAndDisableJob(job, "Invalid source index")
            return false
        }

        // rollupMetadataService.init() will handle the cases where metadata is null
        if (metadata != null) {
            if (!rollupMapperService.indexExists(job.targetIndex)) {
                // TODO: Handle createRollupTargetIndex fails
                // TODO: Move error reason messages to RollupMetadataService as static strings (or functions for patterns), easier for testing
                rollupMapperService.createRollupTargetIndex(job)
                setFailedMetadataAndDisableJob(job, "The target index [${job.targetIndex}] was deleted. Index has been recreated, restart job.")
                return false
            }
            if (!rollupMapperService.isRollupIndex(job.targetIndex)) {
                setFailedMetadataAndDisableJob(job, "The target index [${job.targetIndex}] is not a rollup index")
                return false
            }
            if (!rollupMapperService.jobExistsInRollupIndex(job)) {
                setFailedMetadataAndDisableJob(job, "The target index [${job.targetIndex}] does not have rollup job information in mappings")
                return false
            }
        }

        return true
    }

    /** Sets a failed metadata (updating an existing metadata if provided, otherwise creating a new one) and disables the job */
    private suspend fun setFailedMetadataAndDisableJob(job: Rollup, reason: String, existingMetadata: RollupMetadata? = null) {
        val updatedMetadata = when (val setFailedMetadataResult = rollupMetadataService.setFailedMetadata(job, reason, existingMetadata)) {
            is RollupMetadataService.MetadataResult.Success -> setFailedMetadataResult.metadata
            is RollupMetadataService.MetadataResult.Failure ->
                throw RollupMetadataException(setFailedMetadataResult.message, setFailedMetadataResult.cause)
            // Should not get NoMetadata here
            is RollupMetadataService.MetadataResult.NoMetadata ->
                throw RollupMetadataException("Unexpected state when setting failed metadata", null)
        }

        disableJob(job, updatedMetadata)
    }

    /**
     * Disables a given job. Also takes in an existing metadata to replace the metadataID of the job
     * if this was a newly created metadata.
     *
     * Note: Set metadata to failed and ensure it's updated before passing it to this method because it
     * will not update the metadata (unless updateRollupJob job fails).
     */
    private suspend fun disableJob(job: Rollup, metadata: RollupMetadata) {
        val updatedRollupJob = if (metadata.id != job.metadataID) {
            job.copy(metadataID = metadata.id, enabled = false, jobEnabledTime = null)
        } else {
            job.copy(enabled = false, jobEnabledTime = null)
        }
        // TODO: Handle exceptions
        updateRollupJob(updatedRollupJob, metadata)
    }

    sealed class RollupResult {
        data class Success(val rollup: Rollup) : RollupResult()
        data class Failure(val message: String = "An error occurred for rollup job", val cause: Exception) : RollupResult()
    }
}
