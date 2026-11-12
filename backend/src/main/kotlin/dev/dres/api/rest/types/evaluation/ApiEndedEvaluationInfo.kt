package dev.dres.api.rest.types.evaluation

import dev.dres.data.model.run.interfaces.EvaluationId
import kotlinx.serialization.Serializable

/**
 * Summary of an evaluation that is no longer executed by the [dev.dres.run.RunExecutor] (i.e., it was stopped).
 *
 * Used by administrators to review and delete old evaluations.
 */
@Serializable
data class ApiEndedEvaluationInfo(
    val evaluationId: EvaluationId,
    val name: String,
    val templateName: String,
    val templateDescription: String?,
    val type: ApiEvaluationType,
    val created: Long,
    val started: Long?,
    val ended: Long?,
    val taskCount: Int,
    val submissionCount: Int
)
