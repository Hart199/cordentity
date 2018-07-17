package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.service.IndyArtifactsRegistry
import com.luxoft.blockchainlab.hyperledger.indy.IndyUser
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.CordaX500Name


object CreateClaimDefFlow {

    @InitiatingFlow
    @StartableByRPC
    class Authority(private val schemaDetails: IndyUser.SchemaDetails,
                    private val artifactoryName: CordaX500Name) : FlowLogic<String>() {

        @Suspendable
        override fun call(): String {
            try {
                // get schema Id from Artifactory
                val schemaId = getSchemaId(schemaDetails, artifactoryName)

                val credDef = indyUser().createClaimDef(schemaId)

                // put definition on Artifactory
                val definitionReq = IndyArtifactsRegistry.PutRequest(
                        IndyArtifactsRegistry.ARTIFACT_TYPE.Definition, credDef.json)
                subFlow(ArtifactsRegistryFlow.ArtifactCreator(definitionReq, artifactoryName))

                return credDef.id

            } catch (t: Throwable) {
                logger.error("", t)
                throw FlowException(t.message)
            }
        }
    }
}