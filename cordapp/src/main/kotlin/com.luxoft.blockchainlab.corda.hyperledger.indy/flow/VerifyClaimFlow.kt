package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.IndyCredentialContract
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyClaimProof
import com.luxoft.blockchainlab.hyperledger.indy.*
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.unwrap

/**
 * Flows to verify predicates on attributes
 * */
object VerifyClaimFlow {

    /**
     * A proof of a string Attribute with an optional check against [value]
     * The Attribute is contained in a field [field] in a schema by [schemaId] in a credential definition by [credDefOwner]
     *
     * @param value             an optional value the Attribute is checked against
     * @param field             the name of the field that provides this Attribute
     * @param schemaId          id of the Schema that contains field [field]
     * @param credDefId         id of the Credential Definition that produced by issuer
     * @param credDefOwner      owner of the Credential Definition that contains Schema [schemaId]
     * */
    @CordaSerializable
    data class ProofAttribute(val schemaId: String, val credDefId: String, val credDefOwner: String, val field: String, val value: String = "")

    /**
     * A proof of a logical Predicate on an integer Attribute in the form `Attribute >= [value]`
     * The Attribute is contained in a field [field] in a schema by [schemaId] in a credential definition by [credDefOwner]
     *
     * @param value             value in the predicate to compare the Attribute against
     * @param field             the name of the field that provides the Attribute
     * @param schemaId          id of the Schema that contains field [field]
     * @param credDefId         id of the Credential Definition that produced by issuer
     * @param credDefOwner      owner of the Credential Definition that contains Schema [schemaId]
     * */
    @CordaSerializable
    data class ProofPredicate(val schemaId: String, val credDefId: String, val credDefOwner: String, val field: String, val value: Int)

    /**
     * A flow to verify a set of predicates [predicates] on a set of attributes [attributes]
     *
     * @param identifier        new unique ID for the new proof to allow searching Proofs by [identifier]
     * @param attributes        unordered list of attributes that are needed for verification
     * @param predicates        unordered list of predicates that will be checked
     * @param proverName        node that will prove the credentials
     *
     * @param nonRevoked        <optional> time interval to verify non-revocation
     *                          if not specified then revocation is not verified
     *
     * @returns TRUE if verification succeeds
     *
     * TODO: make it return false in case of failed verification
     * */
    @InitiatingFlow
    @StartableByRPC
    open class Verifier(
            private val identifier: String,
            private val attributes: List<ProofAttribute>,
            private val predicates: List<ProofPredicate>,
            private val proverName: CordaX500Name,
            private val nonRevoked: Interval? = null
    ) : FlowLogic<Boolean>() {

        @Suspendable
        override fun call(): Boolean {
            try {
                val prover: Party = whoIs(proverName)
                val flowSession: FlowSession = initiateFlow(prover)

                val fieldRefAttr = attributes.map {
                    CredFieldRef(it.field, it.schemaId, it.credDefId)
                }

                val fieldRefPred =  predicates.map {
                    val fieldRef = CredFieldRef(it.field, it.schemaId, it.credDefId)
                    CredPredicate(fieldRef, it.value)
                }

                val proofRequest = IndyUser.createProofRequest(
                        attributes = fieldRefAttr,
                        predicates = fieldRefPred,
                        nonRevoked = nonRevoked
                )

                val verifyClaimOut = flowSession.sendAndReceive<ProofInfo>(proofRequest).unwrap { proof ->
                    val usedData = indyUser().getDataUsedInProof(proofRequest, proof)
                    val claimProofOut = IndyClaimProof(identifier, proofRequest, proof, usedData, listOf(ourIdentity, prover))

                    if (!indyUser().verifyProof(claimProofOut.proofReq, proof, usedData)) throw FlowException("Proof verification failed")

                    StateAndContract(claimProofOut, IndyCredentialContract::class.java.name)
                }

                val expectedAttrs = attributes
                        .filter { it.value.isNotEmpty() }
                        .associateBy({ it.field }, { it.value })
                        .map { IndyCredentialContract.ExpectedAttr(it.key, it.value) }

                val verifyClaimCmdType = IndyCredentialContract.Command.Verify(expectedAttrs)
                val verifyClaimCmd = Command(verifyClaimCmdType, listOf(ourIdentity.owningKey, prover.owningKey))

                val trxBuilder = TransactionBuilder(whoIsNotary())
                        .withItems(verifyClaimOut, verifyClaimCmd)

                trxBuilder.toWireTransaction(serviceHub)
                        .toLedgerTransaction(serviceHub)
                        .verify()

                val selfSignedTx = serviceHub.signInitialTransaction(trxBuilder)
                val signedTrx = subFlow(CollectSignaturesFlow(selfSignedTx, listOf(flowSession)))

                // Notarise and record the transaction in both parties' vaults.
                subFlow(FinalityFlow(signedTrx))

                return true

            } catch (e: Exception) {
                logger.error("", e)
                return false
            }
        }
    }

    @InitiatedBy(VerifyClaimFlow.Verifier::class)
    open class Prover(private val flowSession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            try {
                flowSession.receive(ProofRequest::class.java).unwrap { indyProofReq ->
                    // TODO: Master Secret should be received from the outside
                    val masterSecretId = indyUser().defaultMasterSecretId
                    flowSession.send(indyUser().createProof(indyProofReq, masterSecretId))
                }

                val flow = object : SignTransactionFlow(flowSession) {
                    // TODO: Add some checks here.
                    override fun checkTransaction(stx: SignedTransaction) = Unit
                }

                subFlow(flow)

            } catch (e: Exception) {
                logger.error("", e)
                throw FlowException(e.message)
            }
        }
    }
}