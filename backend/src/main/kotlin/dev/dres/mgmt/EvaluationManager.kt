package dev.dres.mgmt

import dev.dres.api.rest.types.evaluation.ApiEndedEvaluationInfo
import dev.dres.data.model.run.DbEvaluation
import dev.dres.data.model.run.interfaces.EvaluationId
import dev.dres.data.model.submissions.DbSubmission
import dev.dres.run.RunExecutor
import jetbrains.exodus.database.TransientEntityStore
import kotlinx.dnq.query.asSequence
import kotlinx.dnq.query.eq
import kotlinx.dnq.query.firstOrNull
import kotlinx.dnq.query.query
import kotlinx.dnq.query.size

/**
 * Management functions for stored (i.e., no longer running) evaluations.
 */
object EvaluationManager {

    private lateinit var store: TransientEntityStore

    fun init(store: TransientEntityStore) {
        this.store = store
    }

    /** Result of a successful [deleteEvaluation] call. */
    data class DeletedEvaluation(val evaluationId: EvaluationId, val name: String, val submissions: Int)

    /** Returns true, if the evaluation with the given ID is currently executed by the [RunExecutor]. */
    fun isActive(evaluationId: EvaluationId): Boolean = RunExecutor.managers().any { it.id == evaluationId }

    /**
     * Lists all evaluations that are stored in the database but are not executed by the [RunExecutor] anymore.
     * The most recently ended evaluations come first.
     */
    fun listEnded(): List<ApiEndedEvaluationInfo> {
        val active = RunExecutor.managers().map { it.id }.toSet()
        return this.store.transactional(true) {
            DbEvaluation.all().asSequence().filter { it.evaluationId !in active }.map { evaluation ->
                ApiEndedEvaluationInfo(
                    evaluationId = evaluation.evaluationId,
                    name = evaluation.name,
                    templateName = evaluation.template.name,
                    templateDescription = evaluation.template.description,
                    type = evaluation.type.toApi(),
                    created = evaluation.created,
                    started = evaluation.started,
                    ended = evaluation.ended,
                    taskCount = evaluation.tasks.size(),
                    submissionCount = submissionsOf(evaluation).size
                )
            }.sortedByDescending { it.ended ?: it.started ?: it.created }.toList()
        }
    }

    /**
     * Deletes a stopped evaluation including all of its submissions and the template copy that was created for it.
     *
     * Submissions are not children of an evaluation in the data model (they only link to its tasks), and the template
     * copy references media items through its task targets and hints. Both are removed here so that neither leaves
     * dangling links nor blocks the deletion of media items later on.
     *
     * @param evaluationId The ID of the evaluation to delete.
     * @return [DeletedEvaluation] or null, if no such evaluation exists.
     * @throws IllegalStateException If the evaluation is still executed by the [RunExecutor].
     */
    fun deleteEvaluation(evaluationId: EvaluationId): DeletedEvaluation? {
        check(!isActive(evaluationId)) { "Evaluation $evaluationId is still running. Terminate it first." }
        return this.store.transactional {
            val evaluation = DbEvaluation.query(DbEvaluation::id eq evaluationId).firstOrNull() ?: return@transactional null
            val name = evaluation.name
            val submissions = submissionsOf(evaluation)
            submissions.forEach { it.delete() }
            val template = evaluation.template
            evaluation.delete()
            if (template.instance) {
                template.delete()
            }
            DeletedEvaluation(evaluationId, name, submissions.size)
        }
    }

    /** All [DbSubmission]s that contain an answer set for one of the tasks of the given [DbEvaluation]. Requires a transaction. */
    private fun submissionsOf(evaluation: DbEvaluation): List<DbSubmission> =
        evaluation.tasks.asSequence()
            .flatMap { task -> task.answerSets.asSequence().map { it.submission } }
            .distinctBy { it.id }
            .toList()
}
