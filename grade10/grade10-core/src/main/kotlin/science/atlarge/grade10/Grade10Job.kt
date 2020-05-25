package science.atlarge.grade10

import science.atlarge.grade10.analysis.attribution.ResourceAttribution
import science.atlarge.grade10.analysis.attribution.ResourceAttributionResult
import science.atlarge.grade10.analysis.attribution.ResourceAttributionSettings
import science.atlarge.grade10.analysis.bottlenecks.BottleneckIdentification
import science.atlarge.grade10.analysis.bottlenecks.BottleneckIdentificationResult
import science.atlarge.grade10.analysis.bottlenecks.BottleneckIdentificationSettings
import science.atlarge.grade10.analysis.perfissues.*
import science.atlarge.grade10.model.execution.ExecutionModel
import science.atlarge.grade10.model.resources.ResourceModel
import science.atlarge.grade10.util.*
import java.nio.file.Path

class Grade10Job(
        private val platformJob: Grade10PlatformJob
) {

    var state: Grade10JobState = Grade10JobState.Initialized
        private set

    fun execute(): Grade10JobResult {
        /* Check if the job has already been run or is running */
        val cachedResult = synchronized(this) {
            when (val currentState = state) {
                is Grade10JobState.Completed -> currentState.result
                Grade10JobState.Initialized -> null
                Grade10JobState.Executing -> throw IllegalStateException("Job is already executing")
            }
        }
        if (cachedResult != null) {
            return cachedResult
        }

        /* Ensure that the cache directory exists */
        val cacheDir = platformJob.outputDirectory.resolve(CACHE_DIR)
        if (!cacheDir.toFile().exists())
            cacheDir.toFile().mkdirs()

        /* Read the desired timeslice granularity from the job input */
        val T = Time(platformJob.timeGranularity())

        /* Parse the input for the execution and resource models */
        println("-- Parsing input data")
        val parsedModels = platformJob.parseInput(T)
        val executionModel = parsedModels.first
        val resourceModel = parsedModels.second

        /* Annotate execution model */
        println("-- Annotating execution model")
        platformJob.annotateExecutionModel(executionModel)

        /* Analysis step 1: Attribute resource utilization */
        println("-- Attributing resource utilization to execution phases")
        val resourceAttributionSettings = platformJob.resourceAttributionSettings(executionModel, resourceModel)
        val resourceAttributionResult = ResourceAttribution.execute(executionModel, resourceModel,
                resourceAttributionSettings, cacheDir)

        /* Analysis step 2: Identify bottlenecks */
        println("-- Identifying resource bottlenecks")
        val bottleneckIdentificationSettings = platformJob.bottleneckIdentificationSettings()
        val bottleneckIdentificationResult = BottleneckIdentification.execute(executionModel,
                bottleneckIdentificationSettings, resourceAttributionResult)

        /* Analysis step 3: Identify performance issues */
        println("-- Identifying performance issues")
        val performanceIssueIdentificationSettings = platformJob.performanceIssueIdentificationSettings()
        val performanceIssueIdentificationResult = PerformanceIssueIdentification.execute(executionModel, resourceModel,
                performanceIssueIdentificationSettings, resourceAttributionResult, bottleneckIdentificationResult)

        /* Cache and return result */
        val result = Grade10JobResult(executionModel, resourceModel, resourceAttributionResult,
                bottleneckIdentificationResult, performanceIssueIdentificationResult)
        state = Grade10JobState.Completed(result)
        return result
    }


    companion object {

        const val CACHE_DIR = "cache"

    }

}

interface Grade10PlatformJob {

    val platform: Grade10Platform

    val inputDirectories: List<Path>

    val outputDirectory: Path

    fun parseInput(T: Time): Pair<ExecutionModel, ResourceModel>

    fun annotateExecutionModel(executionModel: ExecutionModel) {}

    fun timeGranularity(): DurationNs = DEFAULT_TIMESLICE_LENGTH

    fun resourceAttributionSettings(
            executionModel: ExecutionModel,
            resourceModel: ResourceModel
    ): ResourceAttributionSettings

    fun bottleneckIdentificationSettings(): BottleneckIdentificationSettings {
        return BottleneckIdentificationSettings()
    }

    fun performanceIssueIdentificationSettings(): PerformanceIssueIdentificationSettings {
        return PerformanceIssueIdentificationSettings(listOf(
                PhaseDurationImbalancePass,
                BottleneckDurationPass(),
                CriticalPathBottleneckDurationPass()
        ))
    }

}

sealed class Grade10JobState {

    object Initialized : Grade10JobState()

    object Executing : Grade10JobState()

    data class Completed(val result: Grade10JobResult) : Grade10JobState()

}

data class Grade10JobResult(
        val executionModel: ExecutionModel,
        val resourceModel: ResourceModel,
        val resourceAttributionResult: ResourceAttributionResult,
        val bottleneckIdentificationResult: BottleneckIdentificationResult,
        val performanceIssueIdentificationResult: PerformanceIssueIdentificationResult
) {

    private val minTimeSliceId = collectTreeNodes(executionModel.rootPhase) { it.subphases.values }
            .map { it.firstTimeslice }.min() ?: 0L

    fun absoluteTimeSliceToRelative(absoluteTimeslice: TimesliceId): Int {
        return (absoluteTimeslice - minTimeSliceId).toInt()
    }

    fun relativeTimeSliceToAbsolute(relativeTimeSlice: Int): TimesliceId {
        return relativeTimeSlice + minTimeSliceId
    }

}
