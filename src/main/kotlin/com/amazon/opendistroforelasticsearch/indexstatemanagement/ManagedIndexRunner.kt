/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package com.amazon.opendistroforelasticsearch.indexstatemanagement

import com.amazon.opendistroforelasticsearch.indexstatemanagement.IndexStateManagementPlugin.Companion.INDEX_STATE_MANAGEMENT_INDEX
import com.amazon.opendistroforelasticsearch.indexstatemanagement.action.Action
import com.amazon.opendistroforelasticsearch.indexstatemanagement.transport.action.updateindexmetadata.UpdateManagedIndexMetaDataAction
import com.amazon.opendistroforelasticsearch.indexstatemanagement.transport.action.updateindexmetadata.UpdateManagedIndexMetaDataRequest
import com.amazon.opendistroforelasticsearch.indexstatemanagement.elasticapi.getManagedIndexMetaData
import com.amazon.opendistroforelasticsearch.indexstatemanagement.elasticapi.getPolicyID
import com.amazon.opendistroforelasticsearch.indexstatemanagement.elasticapi.retry
import com.amazon.opendistroforelasticsearch.indexstatemanagement.elasticapi.suspendUntil
import com.amazon.opendistroforelasticsearch.indexstatemanagement.model.ManagedIndexConfig
import com.amazon.opendistroforelasticsearch.indexstatemanagement.model.ManagedIndexMetaData
import com.amazon.opendistroforelasticsearch.indexstatemanagement.model.Policy
import com.amazon.opendistroforelasticsearch.indexstatemanagement.model.managedindexmetadata.PolicyRetryInfoMetaData
import com.amazon.opendistroforelasticsearch.indexstatemanagement.model.managedindexmetadata.StateMetaData
import com.amazon.opendistroforelasticsearch.indexstatemanagement.settings.ManagedIndexSettings
import com.amazon.opendistroforelasticsearch.indexstatemanagement.step.Step
import com.amazon.opendistroforelasticsearch.indexstatemanagement.util.createManagedIndexRequest
import com.amazon.opendistroforelasticsearch.indexstatemanagement.util.getActionToExecute
import com.amazon.opendistroforelasticsearch.indexstatemanagement.util.getStartingManagedIndexMetaData
import com.amazon.opendistroforelasticsearch.indexstatemanagement.util.getStateToExecute
import com.amazon.opendistroforelasticsearch.indexstatemanagement.util.getCompletedManagedIndexMetaData
import com.amazon.opendistroforelasticsearch.indexstatemanagement.util.isFailed
import com.amazon.opendistroforelasticsearch.indexstatemanagement.util.isSuccessfulDelete
import com.amazon.opendistroforelasticsearch.indexstatemanagement.util.shouldBackoff
import com.amazon.opendistroforelasticsearch.indexstatemanagement.util.shouldChangePolicy
import com.amazon.opendistroforelasticsearch.indexstatemanagement.util.updateDisableManagedIndexRequest
import com.amazon.opendistroforelasticsearch.jobscheduler.spi.JobExecutionContext
import com.amazon.opendistroforelasticsearch.jobscheduler.spi.LockModel
import com.amazon.opendistroforelasticsearch.jobscheduler.spi.ScheduledJobParameter
import com.amazon.opendistroforelasticsearch.jobscheduler.spi.ScheduledJobRunner
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.logging.log4j.LogManager
import org.elasticsearch.action.admin.indices.settings.put.UpdateSettingsRequest
import org.elasticsearch.action.bulk.BackoffPolicy
import org.elasticsearch.action.get.GetRequest
import org.elasticsearch.action.get.GetResponse
import org.elasticsearch.action.index.IndexResponse
import org.elasticsearch.action.support.master.AcknowledgedResponse
import org.elasticsearch.action.update.UpdateResponse
import org.elasticsearch.client.Client
import org.elasticsearch.cluster.block.ClusterBlockException
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.unit.TimeValue
import org.elasticsearch.common.xcontent.LoggingDeprecationHandler
import org.elasticsearch.common.xcontent.NamedXContentRegistry
import org.elasticsearch.common.xcontent.XContentHelper
import org.elasticsearch.common.xcontent.XContentType
import org.elasticsearch.index.Index
import org.elasticsearch.rest.RestStatus
import java.time.Instant

@Suppress("TooManyFunctions")
object ManagedIndexRunner : ScheduledJobRunner,
        CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("ManagedIndexRunner")) {

    private val logger = LogManager.getLogger(javaClass)

    private lateinit var clusterService: ClusterService
    private lateinit var client: Client
    private lateinit var xContentRegistry: NamedXContentRegistry
    @Suppress("MagicNumber")
    private val savePolicyRetryPolicy = BackoffPolicy.exponentialBackoff(TimeValue.timeValueMillis(250), 3)
    @Suppress("MagicNumber")
    private val updateMetaDataRetryPolicy = BackoffPolicy.exponentialBackoff(TimeValue.timeValueMillis(250), 3)

    fun registerClusterService(clusterService: ClusterService): ManagedIndexRunner {
        this.clusterService = clusterService
        return this
    }

    fun registerClient(client: Client): ManagedIndexRunner {
        this.client = client
        return this
    }

    fun registerNamedXContentRegistry(xContentRegistry: NamedXContentRegistry): ManagedIndexRunner {
        this.xContentRegistry = xContentRegistry
        return this
    }

    override fun runJob(job: ScheduledJobParameter, context: JobExecutionContext) {
        if (job !is ManagedIndexConfig) {
            throw IllegalArgumentException("Invalid job type, found ${job.javaClass.simpleName} with id: ${context.jobId}")
        }

        launch {
            // Attempt to acquire lock
            val lock: LockModel? = withContext(Dispatchers.IO) { context.lockService.acquireLock(job, context) }
            if (lock == null) {
                logger.debug("Could not acquire lock for ${job.index}")
            } else {
                runManagedIndexConfig(job)
                // Release lock
                val released = withContext(Dispatchers.IO) { context.lockService.release(lock) }
                if (!released) {
                    logger.debug("Could not release lock for ${job.index}")
                }
            }
        }
    }

    // TODO: Implement logic for when ISM is moved to STOPPING/STOPPED state when we add those APIs
    @Suppress("ReturnCount")
    private suspend fun runManagedIndexConfig(managedIndexConfig: ManagedIndexConfig) {
        // Get current IndexMetaData and ManagedIndexMetaData
        val indexMetaData = clusterService.state().metaData().index(managedIndexConfig.index)
        if (indexMetaData == null) {
            logger.error("Could not find IndexMetaData in cluster state for ${managedIndexConfig.index}")
            return
        }
        val managedIndexMetaData = indexMetaData.getManagedIndexMetaData()

        // If policy or managedIndexMetaData is null then initialize
        val policy = managedIndexConfig.policy
        if (policy == null || managedIndexMetaData == null) {
            initManagedIndex(managedIndexConfig, managedIndexMetaData)
            return
        }

        // If there is a mismatch between _settings policy_id and job policy_id it means the user
        // tried to change it using the _settings API or we failed to update during ChangePolicy
        // so we will attempt to self heal and disallow the use of _settings API to modify policy_id
        if (indexMetaData.getPolicyID() != managedIndexConfig.policyID) {
            updateIndexPolicyIDSetting(managedIndexConfig.index, managedIndexConfig.policyID)
        }

        if (managedIndexConfig.shouldChangePolicy(managedIndexMetaData)) {
            initChangePolicy(managedIndexConfig, managedIndexMetaData)
            return
        }

        // If the policy was completed or failed then return early and disable job so it stops scheduling work
        if (managedIndexMetaData.policyCompleted == true || managedIndexMetaData.isFailed) {
            disableManagedIndexConfig(managedIndexConfig)
            return
        }

        // TODO: Compare policy version of ManagedIndexMetaData with policy version of job
        // If mismatch, update ManagedIndexMetaData with Version Conflict error

        val state = policy.getStateToExecute(managedIndexMetaData)
        val action: Action? = state?.getActionToExecute(clusterService, client, managedIndexMetaData)
        val step: Step? = action?.getStepToExecute()

        val shouldBackOff = action?.shouldBackoff(managedIndexMetaData.actionMetaData, action.config.configRetry)
        if (shouldBackOff?.first == true) {
            // If we should back off then exit early.
            logger.info("Backoff for retrying. Remaining time ${shouldBackOff.second}")
            return
        }

        // If Step status is still in Start it means we have failed to update the IndexMetaData.
        // TODO: In case the step is Idempotent we can retry. ie. open, close, read_only, read_write, etc...
        if (managedIndexMetaData.stepMetaData?.stepStatus == Step.StepStatus.STARTING) {
            val info = mapOf("message" to "Previous action was not able to update IndexMetaData.")
            updateManagedIndexMetaData(managedIndexMetaData.copy(policyRetryInfo = PolicyRetryInfoMetaData(true, 0), info = info))
            return
        }

        // If any of State, Action, Step components come back as null then we are moving to error in ManagedIndexMetaData
        val startingManagedIndexMetaData = managedIndexMetaData.getStartingManagedIndexMetaData(state, action, step)
        val updateResult = updateManagedIndexMetaData(startingManagedIndexMetaData)

        val actionMetaData = startingManagedIndexMetaData.actionMetaData

        if (updateResult && state != null && action != null && step != null && actionMetaData != null) {
            // Step null check is done in getStartingManagedIndexMetaData
            step.execute()
            val executedManagedIndexMetaData = startingManagedIndexMetaData.getCompletedManagedIndexMetaData(state, action, step, actionMetaData)

            // TODO: Check if we can move this into the TransportUpdateManagedIndexMetaDataAction to cover all cases where
            //  IndexMetaData does not exist anymore since if this current execution was a delete step and it was
            //  successful then the IndexMetaData will be wiped and will throw a NPE if we attempt to update it
            if (executedManagedIndexMetaData.isSuccessfulDelete) {
                return
            }

            if (!updateManagedIndexMetaData(executedManagedIndexMetaData)) {
                logger.error("failed to update ManagedIndexMetaData after executing the Step : ${step.name}")
            }
        }
    }

    private suspend fun initManagedIndex(managedIndexConfig: ManagedIndexConfig, managedIndexMetaData: ManagedIndexMetaData?) {
        var policy: Policy? = managedIndexConfig.policy
        // If policy does not currently exist, we need to save the policy on the ManagedIndexConfig for the first time
        // or if a change policy exists then we will also execute the change as we are still in initialization phase
        if (policy == null || managedIndexConfig.changePolicy != null) {
            // Get the policy by the name unless a ChangePolicy exists then allow the change to happen during initialization
            policy = getPolicy(managedIndexConfig.changePolicy?.policyID ?: managedIndexConfig.policyID)
            // Attempt to save the policy
            if (policy != null) {
                val saved = savePolicyToManagedIndexConfig(managedIndexConfig, policy)
                // If we failed to save the policy, don't initialize ManagedIndexMetaData
                if (!saved) return
            }
            // If we failed to get the policy then we will update the ManagedIndexMetaData with error info
        }

        // Initializing ManagedIndexMetaData for the first time
        initializeManagedIndexMetaData(managedIndexMetaData, managedIndexConfig, policy)
    }

    private suspend fun getPolicy(policyID: String): Policy? {
        try {
            val getRequest = GetRequest(INDEX_STATE_MANAGEMENT_INDEX, policyID)
            val getResponse: GetResponse = client.suspendUntil { get(getRequest, it) }
            if (!getResponse.isExists || getResponse.isSourceEmpty) {
                return null
            }

            val policySource = getResponse.sourceAsBytesRef
            // Intellij complains about createParser/parseWithType blocking because it sees they throw IOExceptions
            return withContext(Dispatchers.IO) {
                val xcp = XContentHelper.createParser(xContentRegistry, LoggingDeprecationHandler.INSTANCE,
                        policySource, XContentType.JSON)
                Policy.parseWithType(xcp, getResponse.id, getResponse.seqNo, getResponse.primaryTerm)
            }
        } catch (e: Exception) {
            logger.error("Failed to get policy: $policyID", e)
            return null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun disableManagedIndexConfig(managedIndexConfig: ManagedIndexConfig) {
        val updatedManagedIndexConfig = managedIndexConfig.copy(enabled = false, jobEnabledTime = null)
        val indexRequest = updateDisableManagedIndexRequest(updatedManagedIndexConfig.indexUuid)
        try {
            val indexResponse: UpdateResponse = client.suspendUntil { update(indexRequest, it) }
            if (indexResponse.status() != RestStatus.OK) {
                logger.error("Failed to disable ManagedIndexConfig(${managedIndexConfig.index}) Error : indexResponse.status()")
            }
        } catch (e: Exception) {
            logger.error("Failed to disable ManagedIndexConfig(${managedIndexConfig.index})", e)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun savePolicyToManagedIndexConfig(managedIndexConfig: ManagedIndexConfig, policy: Policy): Boolean {
        val updatedManagedIndexConfig = managedIndexConfig.copy(policyID = policy.id, policy = policy,
                policySeqNo = policy.seqNo, policyPrimaryTerm = policy.primaryTerm, changePolicy = null)
        val indexRequest = createManagedIndexRequest(updatedManagedIndexConfig)
        var savedPolicy = false
        try {
            savePolicyRetryPolicy.retry(logger) {
                val indexResponse: IndexResponse = client.suspendUntil { index(indexRequest, it) }
                savedPolicy = indexResponse.status() == RestStatus.OK
            }
        } catch (e: Exception) {
            logger.error("Failed to save policy(${policy.id}) to ManagedIndexConfig(${managedIndexConfig.index})", e)
        }
        return savedPolicy
    }

    private suspend fun initializeManagedIndexMetaData(
        managedIndexMetaData: ManagedIndexMetaData?,
        managedIndexConfig: ManagedIndexConfig,
        policy: Policy?
    ) {
        val state = managedIndexConfig.changePolicy?.state ?: policy?.defaultState
        val stateMetaData = if (state != null) {
            StateMetaData(state, Instant.now().toEpochMilli())
        } else {
            null
        }

        val updatedManagedIndexMetaData = when {
            managedIndexMetaData == null -> ManagedIndexMetaData(
                index = managedIndexConfig.index,
                indexUuid = managedIndexConfig.indexUuid,
                policyID = policy?.id ?: managedIndexConfig.policyID,
                policySeqNo = policy?.seqNo,
                policyPrimaryTerm = policy?.primaryTerm,
                policyCompleted = false,
                rolledOver = false,
                wasReadOnly = null,
                transitionTo = null,
                stateMetaData = stateMetaData,
                actionMetaData = null,
                stepMetaData = null,
                policyRetryInfo = PolicyRetryInfoMetaData(failed = policy == null, consumedRetries = 0),
                info = mapOf(
                    "message" to "${if (policy == null) "Fail to load" else "Successfully initialized"} policy: ${managedIndexConfig.policyID}"
                )
            )
            policy == null ->
                // Don't reset existing Policy data to keep the record in case they were populated.
                managedIndexMetaData.copy(
                    policyRetryInfo = PolicyRetryInfoMetaData(failed = true, consumedRetries = 0),
                    info = mapOf("message" to "Fail to load policy: ${managedIndexConfig.policyID}")
                )
            managedIndexMetaData.policySeqNo == null || managedIndexMetaData.policyPrimaryTerm == null ->
                // If there is seqNo and PrimaryTerm it is first time populating Policy.
                managedIndexMetaData.copy(
                    policySeqNo = policy.seqNo,
                    policyPrimaryTerm = policy.primaryTerm,
                    stateMetaData = stateMetaData,
                    policyRetryInfo = PolicyRetryInfoMetaData(failed = false, consumedRetries = 0),
                    info = mapOf("message" to "Successfully initialized policy: ${managedIndexConfig.policyID}")
                )
            managedIndexMetaData.policySeqNo == policy.seqNo && managedIndexMetaData.policyPrimaryTerm == policy.primaryTerm ->
                // If existing PolicySeqNo and PolicyPrimaryTerm is equal to cached Policy then no issue.
                managedIndexMetaData.copy(
                    policyRetryInfo = PolicyRetryInfoMetaData(failed = false, consumedRetries = 0),
                    info = mapOf("message" to "Successfully initialized policy: ${managedIndexConfig.policyID}")
                )
            else ->
                // If existing IndexMetaData PolicySeqNo and PolicyPrimaryTerm is different, we need to fail the Policy.
                managedIndexMetaData.copy(
                    policyRetryInfo = PolicyRetryInfoMetaData(failed = true, consumedRetries = 0),
                    info = mapOf("message" to "Fail to load policy: ${managedIndexConfig.policyID} with " +
                        "seqNo ${policy.seqNo} and primaryTerm ${policy.primaryTerm}")
                )
        }

        updateManagedIndexMetaData(updatedManagedIndexMetaData)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun updateManagedIndexMetaData(managedIndexMetaData: ManagedIndexMetaData): Boolean {
        var result = false
        try {
            val request = UpdateManagedIndexMetaDataRequest(
                    listOf(Pair(Index(managedIndexMetaData.index, managedIndexMetaData.indexUuid), managedIndexMetaData))
            )
            updateMetaDataRetryPolicy.retry(logger) {
                val response: AcknowledgedResponse = client.suspendUntil { execute(UpdateManagedIndexMetaDataAction, request, it) }
                if (response.isAcknowledged) {
                    result = true
                } else {
                    logger.error("Failed to save ManagedIndexMetaData")
                }
            }
        } catch (e: ClusterBlockException) {
            logger.error("There was ClusterBlockException trying to update the metadata for ${managedIndexMetaData.index}. Message: ${e.message}", e)
        } catch (e: Exception) {
            logger.error("Failed to save ManagedIndexMetaData", e)
        }
        return result
    }

    /**
     * Initializes the change policy process where we will get the policy using the change policy's policyID, update the [ManagedIndexMetaData]
     * to reflect the new policy, and save the new policy to the [ManagedIndexConfig] while resetting the change policy to null
     */
    private suspend fun initChangePolicy(managedIndexConfig: ManagedIndexConfig, managedIndexMetaData: ManagedIndexMetaData) {

        // should never happen since we only call this if there is a changePolicy, but we'll do it to make changePolicy non null
        val changePolicy = managedIndexConfig.changePolicy
        if (changePolicy == null) {
            logger.debug("initChangePolicy was called with a null ChangePolicy, ManagedIndexConfig: $managedIndexConfig")
            return
        }

        // get the policy we'll attempt to change to
        val policy = getPolicy(changePolicy.policyID)

        // update the ManagedIndexMetaData with new information
        val updatedManagedIndexMetaData = if (policy == null) {
            managedIndexMetaData.copy(
                info = mapOf("message" to "Failed to load change policy: ${changePolicy.policyID}"),
                policyRetryInfo = PolicyRetryInfoMetaData(failed = true, consumedRetries = 0)
            )
        } else {
            managedIndexMetaData.copy(
                info = mapOf("message" to "Attempting to change policy to ${policy.id}"),
                transitionTo = changePolicy.state,
                stepMetaData = null,
                policyCompleted = false,
                policySeqNo = policy.seqNo,
                policyPrimaryTerm = policy.primaryTerm,
                policyID = policy.id
            )
        }

        /*
        * Try to update the ManagedIndexMetaData in cluster state, we need to do this first before updating the
        * ManagedIndexConfig because if this fails we can fail early and still retry this whole process on the next
        * execution whereas if we do the update to ManagedIndexConfig first we lose the ChangePolicy on the job and
        * could fail to update the ManagedIndexMetaData which would put us in a bad state
        * */
        val updated = updateManagedIndexMetaData(updatedManagedIndexMetaData)

        if (!updated || policy == null) return

        // this will save the new policy on the job and reset the change policy back to null
        val saved = savePolicyToManagedIndexConfig(managedIndexConfig, policy)

        if (saved) {
            /*
            * If we successfully saved the the new policy then the last thing we need to do is update the
            * opendistro.indexstatemanagement.policy_id setting to the new policy id we don't care that much if this fails, because we'll
            * have a check in the beginning of the runner to read in the setting and compare it with the policy_id on the job and update
            * the setting if they ever differ, as we do not allow someone to change an existing policy using _settings API
            * */
            updateIndexPolicyIDSetting(managedIndexConfig.index, changePolicy.policyID)
        }
    }

    /**
     * Once we successfully swap over a ChangePolicy then we need to update the [ManagedIndexSettings.POLICY_ID] setting.
     *
     * We will constantly check the [ManagedIndexSettings.POLICY_ID] against the [ManagedIndexConfig] policyID and if
     * there is ever a mismatch we will overwrite the [ManagedIndexSettings.POLICY_ID] with the [ManagedIndexConfig] policyID.
     *
     * We do this because if this fails we want to ensure we try again on the next execution of the job. At the same time, this
     * will disallow the user from directly using the _settings API to change the policy_id. We do not want to allow this,
     * they must use the ChangePolicy API as the [ManagedIndexSettings.POLICY_ID] is referring to the currently running policy.
     */
    private suspend fun updateIndexPolicyIDSetting(index: String, policyID: String) {
        try {
            val settings = Settings.builder().put(ManagedIndexSettings.POLICY_ID.key, policyID).build()
            val updateSettingsRequest = UpdateSettingsRequest(index).settings(settings)
            val response: AcknowledgedResponse = client.admin().indices().suspendUntil { updateSettings(updateSettingsRequest) }
            if (!response.isAcknowledged) {
                logger.warn("Updating policy_id ($policyID) for $index was not acknowledged")
            }
        } catch (e: Exception) {
            logger.error("There was an error while trying to update the policy_id ($policyID) setting for $index", e)
        }
    }
}
