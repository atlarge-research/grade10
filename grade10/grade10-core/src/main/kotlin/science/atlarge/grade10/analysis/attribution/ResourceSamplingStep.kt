package science.atlarge.grade10.analysis.attribution

import science.atlarge.grade10.model.execution.Phase
import science.atlarge.grade10.model.resources.Metric
import science.atlarge.grade10.serialization.Grade10Deserializer
import science.atlarge.grade10.serialization.Grade10Serializer
import science.atlarge.grade10.util.TimesliceId

interface ResourceSamplingStep {

    fun execute(
            metrics: List<Metric.Consumable>,
            resourceDemandEstimationStepResult: ResourceDemandEstimationStepResult,
            startTimeslice: TimesliceId,
            endTimesliceInclusive: TimesliceId
    ): ResourceSamplingStepResult {
        val metricSamples = metrics.map { metric ->
            metric to sampleMetric(
                    metric,
                    resourceDemandEstimationStepResult,
                    startTimeslice,
                    endTimesliceInclusive
            )
        }.toMap()
        return ResourceSamplingStepResult(metricSamples, startTimeslice, endTimesliceInclusive)
    }

    fun execute(
            metrics: List<Metric.Consumable>,
            resourceDemandEstimationStepResult: ResourceDemandEstimationStepResult,
            phases: List<Phase>
    ): ResourceSamplingStepResult {
        val startTimeSlice = phases.map { it.firstTimeslice }.min() ?: 0L
        val endTimeSliceInclusive = phases.map { it.lastTimeslice }.max() ?: -1L
        return execute(metrics, resourceDemandEstimationStepResult, startTimeSlice, endTimeSliceInclusive)
    }

    fun sampleMetric(
            metric: Metric.Consumable,
            resourceDemandEstimationStepResult: ResourceDemandEstimationStepResult,
            startTimeslice: TimesliceId,
            endTimesliceInclusive: TimesliceId
    ): DoubleArray

}

object DefaultResourceSamplingStep : ResourceSamplingStep {

    override fun sampleMetric(
            metric: Metric.Consumable,
            resourceDemandEstimationStepResult: ResourceDemandEstimationStepResult,
            startTimeslice: TimesliceId,
            endTimesliceInclusive: TimesliceId
    ): DoubleArray {
        val timeSliceCount = (endTimesliceInclusive - startTimeslice + 1).toInt()
        if (timeSliceCount <= 0) {
            return DoubleArray(0)
        }

        val samples = DoubleArray(timeSliceCount)
        val observationIterator = metric.observedUsage.observationIterator()

        while (observationIterator.hasNext()) {
            observationIterator.nextObservationPeriod()
            if (observationIterator.periodStartTimeslice > endTimesliceInclusive) {
                break
            } else {
                val sample = observationIterator.observation
                val fromIndex = maxOf(0,
                        (observationIterator.periodStartTimeslice - startTimeslice).toInt())
                val toIndex = minOf(timeSliceCount - 1,
                        (observationIterator.periodEndTimeslice - startTimeslice).toInt())
                for (i in fromIndex..toIndex) {
                    samples[i] = sample
                }
            }
        }

        return samples
    }

}

object PhaseAwareResourceSamplingStep : ResourceSamplingStep {

    override fun sampleMetric(
            metric: Metric.Consumable,
            resourceDemandEstimationStepResult: ResourceDemandEstimationStepResult,
            startTimeslice: TimesliceId,
            endTimesliceInclusive: TimesliceId
    ): DoubleArray {
        val timeSliceCount = (endTimesliceInclusive - startTimeslice + 1).toInt()
        if (timeSliceCount <= 0) {
            return DoubleArray(0)
        }

        val samples = DoubleArray(timeSliceCount)
        var lastSampleIndexSet = -1

        var exactDemandCache = DoubleArray(0)
        var variableDemandCache = DoubleArray(0)
        var capacityCache = DoubleArray(0)

        val observationIterator = metric.observedUsage.observationIterator()
        while (observationIterator.hasNext()) {
            observationIterator.nextObservationPeriod()
            if (observationIterator.periodStartTimeslice > endTimesliceInclusive) {
                break
            } else if (observationIterator.periodEndTimeslice < startTimeslice) {
                continue
            } else {
                val fromTimeSlice = maxOf(startTimeslice, observationIterator.periodStartTimeslice)
                val toTimeSlice = minOf(endTimesliceInclusive, observationIterator.periodEndTimeslice)
                val periodLength = (toTimeSlice - fromTimeSlice + 1).toInt()
                val indexOffset = (fromTimeSlice - startTimeslice).toInt()

                // Sanity check: filling samples in increasing order of index
                require(indexOffset > lastSampleIndexSet)
                lastSampleIndexSet = indexOffset + periodLength - 1

                // Resize exactDemandCache, variableDemandCache, and capacityCache if needed
                if (periodLength > exactDemandCache.size) {
                    exactDemandCache = DoubleArray(periodLength * 2)
                    variableDemandCache = DoubleArray(periodLength * 2)
                    capacityCache = DoubleArray(periodLength * 2)
                }

                // Fill capacityCache
                for (i in 0 until periodLength) {
                    capacityCache[i] = metric.capacity
                }

                // Fill exactDemandCache and variableDemandCache from the DemandComputationStep results
                val demandIterator = resourceDemandEstimationStepResult.demandIterator(metric, fromTimeSlice, toTimeSlice)
                var cacheIndex = 0
                while (demandIterator.hasNext()) {
                    demandIterator.computeNext()
                    exactDemandCache[cacheIndex] = demandIterator.exactDemand
                    variableDemandCache[cacheIndex] = demandIterator.variableDemand
                    cacheIndex++
                }

                // Distribute the total sample over exact, variable, and background demand
                var remainingSample = observationIterator.observation * observationIterator.periodTimesliceCount
                // - Exact
                if (remainingSample > 0.0) {
                    var remainingExactDemand = 0.0
                    for (i in 0 until periodLength) {
                        if (capacityCache[i] > 0.0) {
                            remainingExactDemand += exactDemandCache[i]
                        }
                    }
                    val exactAssignmentOrder = (0 until periodLength)
                            .filter { exactDemandCache[it] > 0.0 && capacityCache[it] > 0.0 }
                            .sortedByDescending { exactDemandCache[it] / capacityCache[it] }
                    exactAssignmentOrder.forEach { i ->
                        val deltaSample = minOf(remainingSample * exactDemandCache[i] / remainingExactDemand,
                                capacityCache[i])
                        samples[indexOffset + i] += deltaSample
                        capacityCache[i] -= deltaSample
                        remainingSample -= deltaSample
                        remainingExactDemand -= exactDemandCache[i]
                    }
                }
                // - Variable
                if (remainingSample > 0.0) {
                    var remainingVariableDemand = 0.0
                    for (i in 0 until periodLength) {
                        if (capacityCache[i] > 0.0) {
                            remainingVariableDemand += variableDemandCache[i]
                        }
                    }
                    val variableAssignmentOrder = (0 until periodLength)
                            .filter { variableDemandCache[it] > 0.0 && capacityCache[it] > 0.0 }
                            .sortedByDescending { variableDemandCache[it] / capacityCache[it] }
                    variableAssignmentOrder.forEach { i ->
                        val deltaSample = minOf(remainingSample * variableDemandCache[i] / remainingVariableDemand,
                                capacityCache[i])
                        samples[indexOffset + i] += deltaSample
                        capacityCache[i] -= deltaSample
                        remainingSample -= deltaSample
                        remainingVariableDemand--
                    }
                }
                // - Background
                if (remainingSample > 0.0) {
                    var remainingDemand = 0
                    for (i in 0 until periodLength) {
                        if (capacityCache[i] > 0.0) {
                            remainingDemand++
                        }
                    }
                    val assignmentOrder = (0 until periodLength)
                            .filter { capacityCache[it] > 0.0 }
                            .sortedByDescending { 1.0 / capacityCache[it] }
                    assignmentOrder.forEach { i ->
                        val deltaSample = minOf(remainingSample / remainingDemand, capacityCache[i])
                        samples[indexOffset + i] += deltaSample
                        capacityCache[i] -= deltaSample
                        remainingSample -= deltaSample
                        remainingDemand--
                    }
                }
            }
        }

        return samples
    }

}

class ResourceSamplingStepResult(
        private val metricSamples: Map<Metric.Consumable, DoubleArray>,
        private val startTimeslice: TimesliceId,
        private val endTimesliceInclusive: TimesliceId
) {

    val metrics: Set<Metric.Consumable>
        get() = metricSamples.keys

    init {
        val numTimeSlices = (endTimesliceInclusive - startTimeslice + 1).toInt()
        metricSamples.values.forEach {
            require(it.size == numTimeSlices) {
                "All sample arrays must span the full range of time slices"
            }
        }
    }

    fun sampleIterator(
            metric: Metric.Consumable,
            fromTimeslice: TimesliceId = startTimeslice,
            toTimesliceInclusive: TimesliceId = endTimesliceInclusive
    ): MetricSampleIterator {
        require(metric in metricSamples) { "No result found for metric \"${metric.path}" }
        return MetricSampleIterator(
                metric.capacity,
                metricSamples[metric]!!,
                startIndex = (fromTimeslice - startTimeslice).toInt(),
                endIndexInclusive = (toTimesliceInclusive - startTimeslice).toInt(),
                startTimeslice = fromTimeslice
        )
    }

    fun serialize(output: Grade10Serializer) {
        // 1. First time slice
        output.writeLong(startTimeslice)
        // 2. Number of time slices
        val numTimeSlices = (endTimesliceInclusive - startTimeslice + 1).toInt()
        output.writeVarInt(numTimeSlices, true)
        // 3. Number of metrics
        output.writeVarInt(metrics.size, true)
        // 4. For each metric:
        metrics.forEach { metric ->
            // 4.1. The metric
            output.write(metric)
            // 4.2. Sample array
            output.writeDoubles(metricSamples[metric]!!)
        }
    }

    companion object {

        fun deserialize(input: Grade10Deserializer): ResourceSamplingStepResult {
            // 1. First time slice
            val startTimeSlice = input.readLong()
            // 2. Number of time slices
            val numTimeSlices = input.readVarInt(true)
            val endTimeSliceInclusive = startTimeSlice + numTimeSlices - 1
            // 3. Number of metrics
            val numMetrics = input.readVarInt(true)
            // 4. For each metric:
            val metricSamples = mutableMapOf<Metric.Consumable, DoubleArray>()
            repeat(numMetrics) {
                // 4.1. The metric
                val metric = input.readMetric() as Metric.Consumable
                // 4.2. Sample array
                metricSamples[metric] = input.readDoubles(numTimeSlices)
            }
            return ResourceSamplingStepResult(metricSamples, startTimeSlice, endTimeSliceInclusive)
        }

    }

}

class MetricSampleIterator(
        val capacity: Double,
        private val sampleArray: DoubleArray,
        startIndex: Int = 0,
        private val endIndexInclusive: Int = sampleArray.lastIndex,
        private val startTimeslice: TimesliceId
) {

    private var nextIndex = startIndex

    val nextTimeslice: TimesliceId
        get() = startTimeslice + nextIndex

    init {
        require(startIndex >= 0) {
            "Iterator cannot start before the start of the array"
        }
        require(endIndexInclusive <= sampleArray.lastIndex) {
            "Iterator cannot end past the end of the array"
        }
    }

    fun hasNext(): Boolean = nextIndex <= endIndexInclusive

    fun nextSample(): Double {
        val sample = sampleArray[nextIndex]
        nextIndex++
        return sample
    }

}
