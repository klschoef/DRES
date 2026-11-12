package dev.dres.api.rest.handler.evaluation.admin

import dev.dres.api.rest.handler.GetRestHandler
import dev.dres.api.rest.types.evaluation.ApiEndedEvaluationInfo
import dev.dres.api.rest.types.status.ErrorStatus
import dev.dres.mgmt.EvaluationManager
import io.javalin.http.Context
import io.javalin.openapi.*

/**
 * [GetRestHandler] that lists all evaluations that are stored but no longer running. This is a method for administrators.
 */
class ListEndedEvaluationsHandler : AbstractEvaluationAdminHandler(), GetRestHandler<List<ApiEndedEvaluationInfo>> {

    override val route: String = "evaluation/admin/ended/list"

    @OpenApi(
        summary = "Lists all stopped evaluations that are still stored. This is a method for administrators.",
        path = "/api/v2/evaluation/admin/ended/list",
        operationId = OpenApiOperation.AUTO_GENERATE,
        methods = [HttpMethod.GET],
        tags = ["Evaluation Administrator"],
        responses = [
            OpenApiResponse("200", [OpenApiContent(Array<ApiEndedEvaluationInfo>::class)]),
            OpenApiResponse("401", [OpenApiContent(ErrorStatus::class)])
        ]
    )
    override fun doGet(ctx: Context): List<ApiEndedEvaluationInfo> = EvaluationManager.listEnded()
}
