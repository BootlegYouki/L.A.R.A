package org.lara.app.data.repository

import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.Json
import org.lara.app.data.local.LaraDatabase
import org.lara.app.data.remote.dto.GradeReceiptDto

internal data class GradeColumn(val id: String, val title: String, val points: Long?)
internal data class GradeRow(val id: String, val name: String?, val scores: Map<String, Long>)
internal data class ClassGradebook(val work: List<GradeColumn>, val learners: List<GradeRow>)

internal fun observeGradebook(db: LaraDatabase, classroomId: String) = combine(
    db.classworkDao().observeAssignments(classroomId),
    db.classworkDao().observeClassGrades(classroomId),
    db.quizDao().observeByClassroom(classroomId),
    db.syncStateDao().observePrefix("grade_receipt:"),
    db.rosterDao().observeByClassroom(classroomId),
) { assignments, submissions, quizzes, receipts, roster ->
    val closed = quizzes.filter { it.status == "CLOSED" }
    val closedIds = closed.map { it.id }.toSet()
    val grades = receipts.mapNotNull {
        runCatching { Json.decodeFromString<GradeReceiptDto>(it.value) }.getOrNull()
    }.filter { it.quizId in closedIds }
    val work = assignments.map { GradeColumn(it.id, it.title, it.maxPoints) } +
        closed.map { quiz ->
            GradeColumn(quiz.id, quiz.title, grades.filter { it.quizId == quiz.id }.maxOfOrNull { it.totalPoints })
        }
    val assignmentsByLearner = submissions.groupBy { it.studentId }
    val quizzesByLearner = grades.groupBy { it.studentId }
    val learners = roster.filter { it.enrollment.status == "ACTIVE" }.map { person ->
        val id = person.enrollment.studentId
        val scores = buildMap {
            assignmentsByLearner[id].orEmpty().forEach { grade -> grade.score?.let { put(grade.assignmentId, it) } }
            quizzesByLearner[id].orEmpty().forEach { grade ->
                val quiz = grade.quizId
                val score = grade.score
                if (quiz != null && score != null) put(quiz, score)
            }
        }
        GradeRow(id, person.user?.fullName, scores)
    }.sortedBy { it.name.orEmpty().lowercase() }
    ClassGradebook(work, learners)
}
