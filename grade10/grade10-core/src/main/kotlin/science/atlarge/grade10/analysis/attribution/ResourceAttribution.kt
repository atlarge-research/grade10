package science.atlarge.grade10.analysis.attribution

import science.atlarge.grade10.model.PhaseToResourceMapping
import science.atlarge.grade10.model.execution.ExecutionModel
import science.atlarge.grade10.model.resources.ResourceModel
import science.atlarge.grade10.serialization.Grade10Deserializer
import science.atlarge.grade10.serialization.Grade10Serializer
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object ResourceAttribution {

    const val CACHE_FILENAME = "resource-attribution.bin.gz"

    fun execute(
            executionModel: ExecutionModel,
            resourceModel: ResourceModel,
            settings: ResourceAttributionSettings,
            cacheDirectory: Path
    ): ResourceAttributionResult {
        require(executionModel.T == resourceModel.T) {
            "Resource attribution requires that the execution and resource models are tuned to the same timeslice granularity"
        }

        if (settings.cacheSetting == ResourceAttributionCacheSetting.USE_OR_REFRESH_CACHE) {
            val cachedResult = tryLoadCache(cacheDirectory, executionModel, resourceModel)
            if (cachedResult != null) {
                return cachedResult
            }
        }

        val phaseMetricMappingCache = PhaseMetricMappingCache.from(executionModel, resourceModel,
                settings.phaseToResourceMapping)

        val activePhaseDetectionResult = ActivePhaseDetectionStep.execute(
                phaseMetricMappingCache,
                settings.resourceAttributionRules
        )

        val resourceDemandEstimationResult = ResourceDemandEstimationStep.execute(
                phaseMetricMappingCache,
                settings.resourceAttributionRules,
                activePhaseDetectionResult
        )

        val resourceSamplingResult = settings.resourceSamplingStep.execute(
                phaseMetricMappingCache.consumableMetrics,
                resourceDemandEstimationResult,
                phaseMetricMappingCache.phases
        )

        val resourceAttributionResult = ResourceAttributionStep.execute(
                phaseMetricMappingCache,
                settings.resourceAttributionRules,
                activePhaseDetectionResult,
                resourceDemandEstimationResult,
                resourceSamplingResult
        )

        val result = ResourceAttributionResult(
                activePhaseDetectionResult,
                resourceDemandEstimationResult,
                resourceSamplingResult,
                resourceAttributionResult
        )

        if (settings.cacheSetting != ResourceAttributionCacheSetting.DISABLE_CACHE) {
            tryWriteCache(cacheDirectory, executionModel, resourceModel, result)
        }

        return result
    }

    private fun tryLoadCache(
            cacheDirectory: Path,
            executionModel: ExecutionModel,
            resourceModel: ResourceModel
    ): ResourceAttributionResult? {
        val cacheFile = cacheDirectory.resolve(CACHE_FILENAME).toFile()
        if (!cacheFile.exists()) {
            return null
        }
        if (!cacheFile.isFile) {
            return null
        }

        try {
            val result = Grade10Deserializer.from(GZIPInputStream(cacheFile.inputStream()), executionModel,
                    resourceModel).use {
                ResourceAttributionResult.deserialize(it)
            }
            if (result == null) {
                println("WARNING: Resource attribution cache was created by a previous version, recomputing...")
            }
            return null
        } catch (t: Throwable) {
            println("WARNING: Failed to read resource attribution result from cache, recomputing...")
        }
        return null
    }

    private fun tryWriteCache(
            cacheDirectory: Path,
            executionModel: ExecutionModel,
            resourceModel: ResourceModel,
            resourceAttributionResult: ResourceAttributionResult
    ) {
        val cacheFile = cacheDirectory.resolve(CACHE_FILENAME).toFile()
        if (cacheFile.exists()) {
            if (!cacheFile.isFile) {
                println("WARNING: Cache file path \"${cacheFile.toPath().toAbsolutePath()}\" exists, but is not a file")
                return
            }
            cacheFile.delete()
        }

        Grade10Serializer.from(GZIPOutputStream(cacheFile.outputStream()), executionModel, resourceModel).use {
            resourceAttributionResult.serialize(it)
        }
    }

}

class ResourceAttributionSettings(
        val phaseToResourceMapping: PhaseToResourceMapping,
        val resourceAttributionRules: ResourceAttributionRuleProvider,
        val resourceSamplingStep: ResourceSamplingStep = DefaultResourceSamplingStep,
        val cacheSetting: ResourceAttributionCacheSetting
)

enum class ResourceAttributionCacheSetting {
    DISABLE_CACHE,
    REFRESH_CACHE,
    USE_OR_REFRESH_CACHE
}

class ResourceAttributionResult(
        val activePhaseDetection: ActivePhaseDetectionStepResult,
        private val resourceDemandEstimation: ResourceDemandEstimationStepResult,
        val resourceSampling: ResourceSamplingStepResult,
        val resourceAttribution: ResourceAttributionStepResult
) {

    fun serialize(output: Grade10Serializer) {
        // 0. Serialization version
        output.writeVarInt(SERIALIZATION_VERSION, true)
        // 1. Active phase detection result
        activePhaseDetection.serialize(output)
        // 2. Resource demand estimation result
        resourceDemandEstimation.serialize(output)
        // 3. Resource sampling result
        resourceSampling.serialize(output)
        // 4. Resource attribution result
        resourceAttribution.serialize(output)
    }

    companion object {

        const val SERIALIZATION_VERSION = 3

        fun deserialize(
                input: Grade10Deserializer
        ): ResourceAttributionResult? {
            // 0. Serialization version
            if (input.readVarInt(true) != SERIALIZATION_VERSION) {
                return null
            }
            // 1. Active phase detection result
            val activePhaseDetection = ActivePhaseDetectionStepResult.deserialize(input)
            // 2. Resource demand estimation computation result
            val resourceDemandEstimation = ResourceDemandEstimationStepResult.deserialize(input)
            // 3. Resource sampling result
            val resourceSampling = ResourceSamplingStepResult.deserialize(input)
            // 4. Resource attribution result
            val resourceAttribution = ResourceAttributionStepResult.deserialize(input, activePhaseDetection,
                    resourceDemandEstimation, resourceSampling)
            return ResourceAttributionResult(activePhaseDetection, resourceDemandEstimation, resourceSampling,
                    resourceAttribution)
        }

    }

}
