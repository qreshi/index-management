package com.amazon.opendistroforelasticsearch.indexstatemanagement.model.action

import com.amazon.opendistroforelasticsearch.indexstatemanagement.action.Action
import com.amazon.opendistroforelasticsearch.indexstatemanagement.action.OpenAction
import com.amazon.opendistroforelasticsearch.indexstatemanagement.model.ManagedIndexMetaData
import org.elasticsearch.client.Client
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.common.xcontent.ToXContent
import org.elasticsearch.common.xcontent.ToXContentObject
import org.elasticsearch.common.xcontent.XContentBuilder
import org.elasticsearch.common.xcontent.XContentParser
import org.elasticsearch.common.xcontent.XContentParser.Token
import org.elasticsearch.common.xcontent.XContentParserUtils.ensureExpectedToken
import org.elasticsearch.script.ScriptService
import java.io.IOException

data class OpenActionConfig(
    val index: Int
) : ToXContentObject, ActionConfig(ActionType.OPEN, index) {

    override fun toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder {
        builder.startObject()
        super.toXContent(builder, params)
            .startObject(ActionType.OPEN.type)
        return builder.endObject().endObject()
    }

    override fun isFragment(): Boolean = super<ToXContentObject>.isFragment()

    override fun toAction(
        clusterService: ClusterService,
        scriptService: ScriptService,
        client: Client,
        managedIndexMetaData: ManagedIndexMetaData
    ): Action = OpenAction(clusterService, client, managedIndexMetaData, this)

    companion object {
        @JvmStatic
        @Throws(IOException::class)
        fun parse(xcp: XContentParser, index: Int): OpenActionConfig {
            ensureExpectedToken(Token.START_OBJECT, xcp.currentToken(), xcp::getTokenLocation)
            ensureExpectedToken(Token.END_OBJECT, xcp.nextToken(), xcp::getTokenLocation)

            return OpenActionConfig(index)
        }
    }
}
