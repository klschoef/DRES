package dev.dres.mgmt

import com.github.doyaaaaaken.kotlincsv.dsl.csvWriter
import com.github.kokorin.jaffree.StreamType
import com.github.kokorin.jaffree.ffprobe.FFprobe
import com.github.kokorin.jaffree.ffprobe.FFprobeResult
import dev.dres.api.rest.types.collection.*
import dev.dres.data.model.media.CollectionId
import dev.dres.data.model.media.DbMediaCollection
import dev.dres.data.model.media.DbMediaItem
import dev.dres.data.model.run.DbEvaluation
import dev.dres.data.model.submissions.DbAnswer
import dev.dres.data.model.template.DbEvaluationTemplate
import dev.dres.data.model.template.task.DbHint
import dev.dres.data.model.template.task.DbTaskTemplateTarget
import jetbrains.exodus.database.TransientEntityStore
import kotlinx.dnq.query.*
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.relativeTo

/**
 * Maintenance operations on media collections that are shared by the CLI and the REST API:
 * scanning the collection folder, checking files, exporting the item list as CSV and deleting items in bulk.
 */
object MediaCollectionMaintenance {

    private val logger = LoggerFactory.getLogger(this.javaClass)

    private lateinit var store: TransientEntityStore

    /** Provides the folder that contains the ffprobe binary. */
    private lateinit var ffmpegPath: () -> Path

    /** Number of new items that are written to the database per transaction during a scan. */
    private const val SCAN_CHUNK_SIZE = 200

    /** Number of items that are deleted per transaction. */
    private const val DELETE_CHUNK_SIZE = 500

    /** Maximum number of issues returned to clients (the total is always reported). */
    private const val MAX_SCAN_ISSUES = 200
    private const val MAX_REPORTED_ISSUES = 500

    fun init(store: TransientEntityStore, ffmpegPath: () -> Path) {
        this.store = store
        this.ffmpegPath = ffmpegPath
    }

    /* ---------------------------------------------------------------- scan */

    /** Mutable progress of a running scan; read through [status]. */
    private class ScanJob(val collectionId: CollectionId) {
        @Volatile var state = ApiCollectionScanState.RUNNING
        @Volatile var filesFound = 0
        @Volatile var filesProcessed = 0
        @Volatile var itemsAdded = 0
        @Volatile var itemsSkipped = 0
        @Volatile var message: String? = null
        val issues: MutableList<ApiCollectionIssue> = java.util.Collections.synchronizedList(mutableListOf())
        val started = System.currentTimeMillis()
        @Volatile var finished: Long? = null

        fun status() = ApiCollectionScanStatus(
            collectionId = collectionId,
            state = state,
            filesFound = filesFound,
            filesProcessed = filesProcessed,
            itemsAdded = itemsAdded,
            itemsSkipped = itemsSkipped,
            issues = synchronized(issues) { issues.take(MAX_SCAN_ISSUES) },
            issueCount = issues.size,
            message = message,
            started = started,
            finished = finished
        )
    }

    /** The most recent scan per collection (running or finished). */
    private val scans = ConcurrentHashMap<CollectionId, ScanJob>()

    /** Returns the status of the most recent scan of the given collection. */
    fun scanStatus(collectionId: CollectionId): ApiCollectionScanStatus =
        this.scans[collectionId]?.status() ?: ApiCollectionScanStatus(
            collectionId, ApiCollectionScanState.NONE, 0, 0, 0, 0, emptyList(), 0, null, null, null
        )

    /**
     * Starts scanning the folder of the given collection in the background and adds every file with one of the given
     * endings that is not yet part of the collection. Progress can be followed with [scanStatus].
     *
     * @throws IllegalArgumentException If the collection does not exist, its folder cannot be read or no endings are given.
     * @throws IllegalStateException If a scan of this collection is already running.
     */
    fun startScan(collectionId: CollectionId, videoTypes: Collection<String>, imageTypes: Collection<String>): ApiCollectionScanStatus {
        val videos = normaliseTypes(videoTypes)
        val images = normaliseTypes(imageTypes)
        require(videos.isNotEmpty() || images.isNotEmpty()) { "No file types specified." }
        val base = checkedBasePath(collectionId)

        val job = ScanJob(collectionId)
        val previous = this.scans.compute(collectionId) { _, current ->
            if (current?.state == ApiCollectionScanState.RUNNING) current else job
        }
        check(previous === job) { "A scan of this collection is already running." }

        Thread({
            try {
                runScan(job, base, videos, images) { }
            } catch (e: Throwable) {
                logger.error("Scan of collection $collectionId failed.", e)
                job.message = "Scan failed: ${e.message}"
                job.state = ApiCollectionScanState.FAILED
            } finally {
                job.finished = System.currentTimeMillis()
            }
        }, "collection-scan-$collectionId").apply { isDaemon = true }.start()

        return job.status()
    }

    /**
     * Scans the folder of the given collection in the calling thread (used by the CLI).
     *
     * @param log Receives a line for every analysed file.
     */
    fun scan(collectionId: CollectionId, videoTypes: Collection<String>, imageTypes: Collection<String>, log: (String) -> Unit): ApiCollectionScanStatus {
        val videos = normaliseTypes(videoTypes)
        val images = normaliseTypes(imageTypes)
        require(videos.isNotEmpty() || images.isNotEmpty()) { "No file types specified." }
        val job = ScanJob(collectionId)
        runScan(job, checkedBasePath(collectionId), videos, images, log)
        job.finished = System.currentTimeMillis()
        return job.status()
    }

    private fun normaliseTypes(types: Collection<String>) =
        types.map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }.toSet()

    /** Returns the base folder of the collection after making sure it is a readable directory. */
    private fun checkedBasePath(collectionId: CollectionId): Path {
        val collection = MediaCollectionManager.getCollection(collectionId)
            ?: throw IllegalArgumentException("Collection $collectionId not found.")
        val base = Paths.get(collection.basePath ?: throw IllegalArgumentException("Collection has no base path."))
        require(Files.exists(base)) { "The collection folder '$base' does not exist." }
        require(Files.isDirectory(base)) { "'$base' is not a folder." }
        require(Files.isReadable(base)) { "The collection folder '$base' is not readable." }
        return base
    }

    private fun runScan(job: ScanJob, base: Path, videoTypes: Set<String>, imageTypes: Set<String>, log: (String) -> Unit) {
        val collectionId = job.collectionId
        val files = Files.walk(base).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.extension.lowercase().let { e -> e in videoTypes || e in imageTypes } }
                .sorted().toList()
        }
        job.filesFound = files.size

        /* Files that are already part of the collection are skipped without analysing them again. */
        val existing = this.store.transactional(true) {
            val collection = DbMediaCollection.query(DbMediaCollection::id eq collectionId).first()
            collection.items.asSequence().map { it.location }.toHashSet()
        }

        files.chunked(SCAN_CHUNK_SIZE).forEach { chunk ->
            val items = chunk.mapNotNull { file ->
                val location = file.relativeTo(base).toString()
                val item = if (location in existing) {
                    job.itemsSkipped++
                    null
                } else try {
                    when (file.extension.lowercase()) {
                        in imageTypes -> {
                            log("Found image $file.")
                            ApiMediaItem(mediaItemId = "", name = file.fileName.nameWithoutExtension, type = ApiMediaType.IMAGE,
                                collectionId = collectionId, location = location)
                        }
                        else -> {
                            log("Found video $file; analysing...")
                            val (durationMs, fps) = analyseVideo(file)
                            log("Frame rate $fps fps, duration $durationMs ms.")
                            ApiMediaItem(mediaItemId = "", name = file.fileName.nameWithoutExtension, type = ApiMediaType.VIDEO,
                                collectionId = collectionId, location = location, durationMs = durationMs, fps = fps)
                        }
                    }
                } catch (e: Throwable) {
                    logger.warn("Could not analyse $file during scan of collection $collectionId.", e)
                    log("Could not analyse $file: ${e.message}. Skipping.")
                    job.issues.add(ApiCollectionIssue(file.fileName.nameWithoutExtension, location, e.message ?: e.javaClass.simpleName))
                    null
                }
                job.filesProcessed++
                item
            }
            if (items.isNotEmpty()) {
                MediaCollectionManager.addMediaItems(collectionId, items)
                job.itemsAdded += items.size
            }
        }

        job.message = "Added ${job.itemsAdded} new items, skipped ${job.itemsSkipped} existing ones" +
                if (job.issues.isNotEmpty()) ", ${job.issues.size} files could not be analysed." else "."
        job.state = ApiCollectionScanState.DONE
    }

    private fun ffprobe(file: Path, countFrames: Boolean): FFprobeResult =
        FFprobe.atPath(this.ffmpegPath())
            .setInput(file)
            .setShowStreams(true)
            .setCountFrames(countFrames)
            .setSelectStreams(StreamType.VIDEO)
            .execute()

    /** Returns duration in milliseconds and frame rate of a video; counts frames if the duration is not stored in the file. */
    private fun analyseVideo(file: Path): Pair<Long, Float> {
        val stream = ffprobe(file, false).streams.firstOrNull() ?: throw IllegalStateException("No video stream found.")
        val fps = (stream.rFrameRate ?: stream.avgFrameRate ?: throw IllegalStateException("Frame rate unknown.")).toFloat()
        val duration = stream.duration?.let { (it.toDouble() * 1000.0).toLong() } ?: run {
            val frames = ffprobe(file, true).streams.first().nbReadFrames
            ((frames * 1000) / fps).toLong()
        }
        return duration to fps
    }

    /* ---------------------------------------------------------------- check */

    /** Checks that the file of every item of the collection exists and is readable; null if the collection does not exist. */
    fun check(collectionId: CollectionId): ApiCollectionCheckResult? {
        val populated = MediaCollectionManager.getPopulatedCollection(collectionId) ?: return null
        val base = populated.collection.basePath ?: ""
        val issues = mutableListOf<ApiCollectionIssue>()
        for (item in populated.items) {
            val path = Paths.get(base, item.location)
            when {
                !Files.exists(path) -> issues.add(ApiCollectionIssue(item.name, item.location, "File not found: $path"))
                !Files.isReadable(path) -> issues.add(ApiCollectionIssue(item.name, item.location, "File not readable: $path"))
            }
        }
        return ApiCollectionCheckResult(
            total = populated.items.size,
            ok = populated.items.size - issues.size,
            issues = issues.sortedBy { it.location }.take(MAX_REPORTED_ISSUES),
            issueCount = issues.size
        )
    }

    /* ---------------------------------------------------------------- export */

    /** Header of the CSV export; the same format is read by the CLI command `collection import`. */
    private val CSV_HEADER = listOf("itemType", "name", "location", "duration", "fps")

    /** Returns the items of the collection as CSV; null if the collection does not exist. */
    fun exportCsv(collectionId: CollectionId): String? {
        val populated = MediaCollectionManager.getPopulatedCollection(collectionId) ?: return null
        val out = ByteArrayOutputStream()
        csvWriter().open(out) {
            writeRow(CSV_HEADER)
            populated.items.sortedBy { it.location }.forEach {
                writeRow(listOf(it.type, it.name, it.location, it.durationMs?.toString(), it.fps?.toString()))
            }
        }
        return out.toString(Charsets.UTF_8)
    }

    /* ---------------------------------------------------------------- delete */

    /**
     * Deletes the items of a collection that match [selector] (by default all of them).
     *
     * Items that are still used as task targets or hints in a template, or that were submitted in an evaluation,
     * cannot be deleted. They are kept and reported together with what uses them.
     *
     * @return The result, or null if the collection does not exist.
     */
    fun deleteItems(collectionId: CollectionId, selector: (id: String, name: String) -> Boolean = { _, _ -> true }): ApiCollectionItemsDeletion? {
        val candidates = this.store.transactional(true) {
            val collection = DbMediaCollection.query(DbMediaCollection::id eq collectionId).firstOrNull()
                ?: return@transactional null
            collection.items.asSequence().filter { selector(it.id, it.name) }.map { item ->
                Triple(item.id, item.name, item.location) to usagesOf(item)
            }.toList()
        } ?: return null

        val blocked = candidates.filter { it.second.isNotEmpty() }.map { (item, usages) ->
            ApiCollectionIssue(item.second, item.third, usages.joinToString("; "))
        }.toMutableList()
        var deleted = 0

        candidates.filter { it.second.isEmpty() }.map { it.first }.chunked(DELETE_CHUNK_SIZE).forEach { chunk ->
            try {
                this.store.transactional {
                    val ids = chunk.map { it.first }
                    DbMediaItem.filter { it.id isIn ids }.asSequence().toList().forEach { it.delete() }
                }
                deleted += chunk.size
            } catch (e: Exception) {
                /* Something started using an item in the meantime: fall back to deleting the items one by one. */
                chunk.forEach { (id, name, location) ->
                    try {
                        this.store.transactional { DbMediaItem.query(DbMediaItem::id eq id).firstOrNull()?.delete() }
                        deleted++
                    } catch (e: Exception) {
                        blocked.add(ApiCollectionIssue(name, location, e.message?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: "Could not be deleted."))
                    }
                }
            }
        }

        return ApiCollectionItemsDeletion(
            deleted = deleted,
            blocked = blocked.sortedBy { it.location }.take(MAX_REPORTED_ISSUES),
            blockedCount = blocked.size
        )
    }

    /** Describes everything that still references the given item. Requires a transaction. */
    private fun usagesOf(item: DbMediaItem): List<String> {
        val usages = linkedSetOf<String>()
        DbTaskTemplateTarget.query(DbTaskTemplateTarget::item eq item).asSequence().forEach {
            usages.add("target of task '${it.task.name}' in ${describe(it.task.evaluation)}")
        }
        DbHint.query(DbHint::item eq item).asSequence().forEach {
            usages.add("hint of task '${it.task.name}' in ${describe(it.task.evaluation)}")
        }
        DbAnswer.query(DbAnswer::item eq item).asSequence().forEach {
            usages.add("submitted in evaluation '${it.answerSet.task.evaluation.name}'")
        }
        return usages.toList()
    }

    /** Names a template; template copies that belong to an evaluation are named after that evaluation. */
    private fun describe(template: DbEvaluationTemplate): String =
        if (template.instance) {
            val evaluation = DbEvaluation.query(DbEvaluation::template eq template).firstOrNull()
            "evaluation '${evaluation?.name ?: template.name}'"
        } else {
            "template '${template.name}'"
        }
}
