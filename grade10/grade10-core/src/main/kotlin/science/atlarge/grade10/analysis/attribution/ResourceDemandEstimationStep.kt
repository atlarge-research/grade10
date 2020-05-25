package science.atlarge.grade10.analysis.attribution

import science.atlarge.grade10.model.execution.Phase
import science.atlarge.grade10.model.resources.Metric
import science.atlarge.grade10.serialization.Grade10Deserializer
import science.atlarge.grade10.serialization.Grade10Serializer
import science.atlarge.grade10.util.TimesliceId

object ResourceDemandEstimationStep {

    fun execute(
            phaseMetricMappingCache: PhaseMetricMappingCache,
            resourceAttributionRules: ResourceAttributionRuleProvider,
            activePhaseDetectionStepResult: ActivePhaseDetectionStepResult
    ): ResourceDemandEstimationStepResult {
        val exactDemandPerMetric = mutableMapOf<Metric.Consumable, DoubleArray>()
        val variableDemandPerMetric = mutableMapOf<Metric.Consumable, DoubleArray>()
        val startTimeSlice = phaseMetricMappingCache.phases.map { it.firstTimeslice }.min() ?: 0L
        val endTimeSliceInclusive = phaseMetricMappingCache.phases.map { it.lastTimeslice }.max() ?: -1L

        phaseMetricMappingCache.consumableMetrics.forEach { consumableMetric ->
            val (exact, variable) = computeExactAndVariableDemandForMetric(
                    consumableMetric,
                    phaseMetricMappingCache.consumableMetricToLeafPhaseMapping[consumableMetric] ?: emptyList(),
                    resourceAttributionRules,
                    activePhaseDetectionStepResult,
                    startTimeSlice,
                    endTimeSliceInclusive
            )
            exactDemandPerMetric[consumableMetric] = exact
            variableDemandPerMetric[consumableMetric] = variable
        }

        return ResourceDemandEstimationStepResult(exactDemandPerMetric, variableDemandPerMetric, startTimeSlice, endTimeSliceInclusive)
    }

    private fun computeExactAndVariableDemandForMetric(
            metric: Metric.Consumable,
            phases: List<Phase>,
            resourceAttributionRules: ResourceAttributionRuleProvider,
            activePhaseDetectionStepResult: ActivePhaseDetectionStepResult,
            startTimeslice: TimesliceId,
            endTimesliceInclusive: TimesliceId
    ): Pair<DoubleArray, DoubleArray> {
        val timeSliceCount = (endTimesliceInclusive - startTimeslice + 1).toInt()
        val exactDemand = DoubleArray(timeSliceCount)
        val variableDemand = DoubleArray(timeSliceCount)

        phases.forEach { phase ->
            val rule = resourceAttributionRules[phase, metric]
            val activeIterator = activePhaseDetectionStepResult.activeIterator(phase)
            when (rule) {
                is ConsumableResourceAttributionRule.Exact -> {
                    var index = (phase.firstTimeslice - startTimeslice).toInt()
                    val demand = rule.exactDemand
                    while (activeIterator.hasNext()) {
                        if (activeIterator.nextIsActive()) {
                            exactDemand[index] += demand
                        }
                        index++
                    }
                }
                is ConsumableResourceAttributionRule.Variable -> {
                    var index = (phase.firstTimeslice - startTimeslice).toInt()
                    val demand = rule.relativeDemand
                    while (activeIterator.hasNext()) {
                        if (activeIterator.nextIsActive()) {
                            variableDemand[index] += demand
                        }
                        index++
                    }
                }
                else -> {
                    // Ignore
                }
            }
        }

        return exactDemand to variableDemand
    }

}

class ResourceDemandEstimationStepResult(
        private val exactDemandPerMetric: Map<Metric.Consumable, DoubleArray>,
        private val variableDemandPerMetric: Map<Metric.Consumable, DoubleArray>,
        private val startTimeslice: TimesliceId,
        private val endTimesliceInclusive: TimesliceId
) {

    val metrics: Set<Metric.Consumable>
        get() = exactDemandPerMetric.keys

    init {
        val numTimeSlices = (endTimesliceInclusive - startTimeslice + 1).toInt()
        exactDemandPerMetric.values.forEach {
            require(it.size == numTimeSlices) {
                "All demand arrays must span the full range of time slices"
            }
        }
        variableDemandPerMetric.values.forEach {
            require(it.size == numTimeSlices) {
                "All demand arrays must span the full range of time slices"
            }
        }

        require(exactDemandPerMetric.keys == variableDemandPerMetric.keys) {
            "Exact and variable demands must be defined for the same metrics"
        }
    }

    fun demandIterator(
            metric: Metric.Consumable,
            fromTimeslice: TimesliceId = startTimeslice,
            toTimesliceInclusive: TimesliceId = endTimesliceInclusive
    ): ResourceDemandIterator {
        require(metric in exactDemandPerMetric) { "Demand for metric is not defined" }
        return ResourceDemandIterator(
                exactDemandPerMetric.getValue(metric),
                variableDemandPerMetric[metric]!!,
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
            // 4.2. Exact demand array
            output.writeDoubles(exactDemandPerMetric[metric]!!)
            // 4.3. Variable demand array
            output.writeDoubles(variableDemandPerMetric[metric]!!)
        }
    }

    companion object {

        fun deserialize(input: Grade10Deserializer): ResourceDemandEstimationStepResult {
            // 1. First time slice
            val startTimeSlice = input.readLong()
            // 2. Number of time slices
            val numTimeSlices = input.readVarInt(true)
            val endTimeSliceInclusive = startTimeSlice + numTimeSlices - 1
            // 3. Number of metrics
            val numMetrics = input.readVarInt(true)
            // 4. For each metric:
            val exactDemandPerMetric = mutableMapOf<Metric.Consumable, DoubleArray>()
            val variableDemandPerMetric = mutableMapOf<Metric.Consumable, DoubleArray>()
            repeat(numMetrics) {
                // 4.1. The metric
                val metric = input.readMetric() as Metric.Consumable
                // 4.2. Exact demand array
                exactDemandPerMetric[metric] = input.readDoubles(numTimeSlices)
                // 4.3. Variable demand array
                variableDemandPerMetric[metric] = input.readDoubles(numTimeSlices)
            }
            return ResourceDemandEstimationStepResult(exactDemandPerMetric, variableDemandPerMetric, startTimeSlice,
                    endTimeSliceInclusive)
        }

    }

}

class ResourceDemandIterator(
        private val exactDemandArray: DoubleArray,
        private val variableDemandArray: DoubleArray,
        startIndex: Int = 0,
        private val endIndexInclusive: Int = exactDemandArray.lastIndex,
        startTimeslice: TimesliceId
) {

    private var nextIndex = startIndex

    var timeslice: TimesliceId = startTimeslice - 1
        private set

    var exactDemand: Double = 0.0
        private set

    var variableDemand: Double = 0.0
        private set

    init {
        require(startIndex >= 0) {
            "Iterator cannot start before the start of the array"
        }
        require(endIndexInclusive <= exactDemandArray.lastIndex) {
            "Iterator cannot end past the end of the array"
        }
        require(exactDemandArray.size == variableDemandArray.size) {
            "Exact demand and variable demand arrays must be of the same size"
        }
    }

    fun hasNext(): Boolean = nextIndex <= endIndexInclusive

    fun computeNext() {
        exactDemand = exactDemandArray[nextIndex]
        variableDemand = variableDemandArray[nextIndex]
        timeslice++
        nextIndex++
    }

}
