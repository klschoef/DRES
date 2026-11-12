package dres.integration

import dev.dres.api.rest.types.evaluation.ApiVerdictCounts
import dev.dres.data.model.media.DbMediaItem
import dev.dres.data.model.run.DbEvaluation
import dev.dres.data.model.submissions.DbAnswer
import dev.dres.data.model.submissions.DbAnswerSet
import dev.dres.data.model.submissions.DbAnswerType
import dev.dres.data.model.submissions.DbSubmission
import dev.dres.data.model.submissions.DbVerdictStatus
import dev.dres.data.model.template.DbEvaluationTemplate
import dev.dres.mgmt.EvaluationManager
import dev.dres.mgmt.TemplateManager
import kotlinx.dnq.query.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Tests that deleting a stopped evaluation also removes its submissions and its template copy, so that the media
 * items those referenced can be deleted afterwards.
 */
class EvaluationDeletionTest : AbstractDresIntegrationTest() {

    @BeforeAll
    fun initManagers() {
        TemplateManager.init(store)
        EvaluationManager.init(store)
    }

    private data class Fixture(
        val templateId: String,
        val instanceId: String,
        val evaluationId: String,
        val itemId: String,
        val submissionIds: List<String>
    )

    /** Builds template → evaluation → task with two submissions whose answers reference a video item. */
    private fun buildFixture(): Fixture {
        val col = createTestCollection()
        val template = createTemplateShell("eval-del-${UUID.randomUUID()}")
        val group = addTaskTypeAndGroup(template, "KIS", "G", "KIS", "MEDIA_ITEM")
        val task = addTask(template, group, col, "T1")
        val itemId = store.transactional {
            val item = col.items.filter { it.name eq "video1" }.first()
            task.targets.first().item = item
            item.id
        }
        val evaluation = createEvaluation(template, "eval-del-run-${UUID.randomUUID()}", "INTERACTIVE_SYNCHRONOUS")
        val instanceTaskId = store.transactional(true) { evaluation.template.tasks.first().id }
        val dbTask = createPersistedTask(evaluation, instanceTaskId)
        val user = createTestUser()
        val submissionIds = (0 until 2).map {
            store.transactional {
                val team = evaluation.template.teams.first()
                val submission = DbSubmission.new {
                    timestamp = System.currentTimeMillis()
                    this.team = team
                    this.user = user
                }
                val answerSet = DbAnswerSet.new {
                    this.submission = submission
                    status = DbVerdictStatus.filter { it.description eq "WRONG" }.first()
                    this.task = dbTask
                }
                DbAnswer.new {
                    this.answerSet = answerSet
                    type = DbAnswerType.filter { it.description eq "ITEM" }.first()
                    item = DbMediaItem.filter { it.id eq itemId }.first()
                }
                submission.id
            }
        }
        return store.transactional(true) {
            Fixture(template.id, evaluation.template.id, evaluation.id, itemId, submissionIds)
        }
    }

    private fun tryDeleteItem(itemId: String): Boolean = try {
        store.transactional { DbMediaItem.filter { it.id eq itemId }.first().delete() }
        true
    } catch (e: Exception) {
        false
    }

    @Test
    fun `deleting an evaluation removes its submissions and template copy`() {
        val f = buildFixture()

        val deleted = EvaluationManager.deleteEvaluation(f.evaluationId)
        assertNotNull(deleted)
        assertEquals(2, deleted!!.submissions)

        store.transactional(true) {
            assertTrue(DbEvaluation.filter { it.id eq f.evaluationId }.isEmpty, "Evaluation should be gone")
            assertTrue(DbEvaluationTemplate.filter { it.id eq f.instanceId }.isEmpty, "Template copy should be gone")
            assertFalse(DbEvaluationTemplate.filter { it.id eq f.templateId }.isEmpty, "Original template must remain")
            f.submissionIds.forEach { id ->
                assertTrue(DbSubmission.filter { it.id eq id }.isEmpty, "Submission $id should be gone")
            }
        }
    }

    @Test
    fun `media item can be deleted once evaluation and template are deleted`() {
        val f = buildFixture()

        assertFalse(tryDeleteItem(f.itemId), "Item is referenced by submissions and must not be deletable")

        EvaluationManager.deleteEvaluation(f.evaluationId)
        assertFalse(tryDeleteItem(f.itemId), "Item is still referenced by the original template's target")

        assertNotNull(TemplateManager.deleteTemplate(f.templateId))
        assertTrue(tryDeleteItem(f.itemId), "Item should be deletable after evaluation and template are gone")
    }

    @Test
    fun `stopped evaluation is listed and disappears after deletion`() {
        val f = buildFixture()
        val listed = EvaluationManager.listEnded().find { it.evaluationId == f.evaluationId }
        assertNotNull(listed)
        assertEquals(2, listed!!.submissionCount)
        assertEquals(1, listed.taskCount)
        assertEquals(ApiVerdictCounts(correct = 0, wrong = 2, indeterminate = 0, undecidable = 0), listed.verdicts)
        assertEquals(listed.verdicts, EvaluationManager.verdictCounts(f.evaluationId))

        EvaluationManager.deleteEvaluation(f.evaluationId)
        assertNull(EvaluationManager.listEnded().find { it.evaluationId == f.evaluationId })
    }

    @Test
    fun `deleting an unknown evaluation returns null`() {
        assertNull(EvaluationManager.deleteEvaluation(UUID.randomUUID().toString()))
    }
}
