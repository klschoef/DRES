package dev.dres.api.rest.types.evaluation

import kotlinx.serialization.Serializable

/**
 * Number of submitted answer sets of an evaluation per verdict.
 *
 * @param correct Judged (or automatically validated) as correct.
 * @param wrong Judged as wrong.
 * @param indeterminate Not judged yet.
 * @param undecidable Marked as undecidable by a judge.
 */
@Serializable
data class ApiVerdictCounts(val correct: Int, val wrong: Int, val indeterminate: Int, val undecidable: Int)
