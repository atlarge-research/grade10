package science.atlarge.grade10.analysis.attribution

import science.atlarge.grade10.metrics.TimeSlicePeriodList
import science.atlarge.grade10.model.execution.Phase
import science.atlarge.grade10.model.resources.Metric
import science.atlarge.grade10.serialization.Grade10Deserializer
import science.atlarge.grade10.serialization.Grade10Serializer
import science.atlarge.grade10.util.TimesliceId

// TODO: Add resource attribution for composite phases?
object ResourceAttributionStep {

    fun execute(
            phaseMetricMappingCache: PhaseMetricMappingCache,
            resourceAttributionRules: ResourceAttributionRuleProvider,
            activePhaseDetectionStepResult: ActivePhaseDetectionStepResult,
            resourceDemandEstimationStepResult: ResourceDemandEstimationStepResult,
            samplingStepResult: ResourceSamplingStepResult
    ): ResourceAttributionStepResult {
        return ResourceAttributionStepResult(phaseMetricMappingCache.leafPhases.map { phase ->
            createPhaseResult(phase, phaseMetricMappingCache, resourceAttributionRules, activePhaseDetectionStepResult,
                    resourceDemandEstimationStepResult, samplingStepResult)
        })
    }

    private fun createPhaseResult(
            phase: Phase,
            phaseMetricMappingCache: PhaseMetricMappingCache,
            resourceAttributionRules: ResourceAttributionRuleProvider,
            activePhaseDetectionStepResult: ActivePhaseDetectionStepResult,
            resourceDemandEstimationStepResult: ResourceDemandEstimationStepResult,
            samplingStepResult: ResourceSamplingStepResult
    ): ResourceAttributionStepPhaseResult {
        val fromTimeSlice = phase.firstTimeslice
        val toTimeSliceInclusive = phase.lastTimeslice

        val (unusedBlockingMetrics, blockingMetrics) =
                (phaseMetricMappingCache.leafPhaseToMetricMapping[phase] ?: emptyList())
                        .filterIsInstance<Metric.Blocking>()
                        .partition { resourceAttributionRules[phase, it] == BlockingResourceAttributionRule.None }
        val (unusedConsumableMetrics, consumableMetrics) =
                (phaseMetricMappingCache.leafPhaseToMetricMapping[phase] ?: emptyList())
                        .filterIsInstance<Metric.Consumable>()
                        .partition { resourceAttributionRules[phase, it] == ConsumableResourceAttributionRule.None }
        val metricRulePairs = consumableMetrics.map { it to resourceAttributionRules[phase, it] }.toMap()
        val demandIteratorSuppliers = metricRulePairs.keys
                .map { metric ->
                    metric to { resourceDemandEstimationStepResult.demandIterator(metric, fromTimeSlice, toTimeSliceInclusive) }
                }
                .toMap()
        val sampleIteratorSuppliers = metricRulePairs.keys
                .map { metric ->
                    metric to { samplingStepResult.sampleIterator(metric, fromTimeSlice, toTimeSliceInclusive) }
                }
                .toMap()
        val phaseActiveIteratorSupplier = {
            activePhaseDetectionStepResult.activeIterator(phase, fromTimeSlice, toTimeSliceInclusive)
        }

        return ResourceAttributionStepPhaseResult(
                phase,
                blockingMetrics.toSet(),
                unusedBlockingMetrics.toSet(),
                unusedConsumableMetrics.toSet(),
                metricRulePairs,
                demandIteratorSuppliers,
                sampleIteratorSuppliers,
                phaseActiveIteratorSupplier
        )
    }

}

class ResourceAttributionStepResult(
        phaseResults: Iterable<ResourceAttributionStepPhaseResult>
) {

    private val phaseResults = phaseResults.associateBy { it.phase }

    val phases: Set<Phase>
        get() = phaseResults.keys

    operator fun get(phase: Phase): ResourceAttributionStepPhaseResult =
            phaseResults[phase] ?: throw IllegalArgumentException("No result found for phase \"${phase.path}\"")

    fun serialize(output: Grade10Serializer) {
        // 1. Number of results
        output.writeVarInt(phaseResults.size, true)
        // 2. For each result:
        phaseResults.forEach { _, result ->
            // 2.1. The result
            result.serialize(output)
        }
    }

    companion object {

        fun deserialize(
                input: Grade10Deserializer,
                activePhaseDetectionStepResult: ActivePhaseDetectionStepResult,
                resourceDemandEstimationStepResult: ResourceDemandEstimationStepResult,
                samplingStepResult: ResourceSamplingStepResult
        ): ResourceAttributionStepResult {
            // 1. Number of results
            val numResults = input.readVarInt(true)
            // 2. For each result:
            val results = arrayListOf<ResourceAttributionStepPhaseResult>()
            repeat(numResults) {
                // 2.1. The result
                results.add(ResourceAttributionStepPhaseResult.deserialize(input, activePhaseDetectionStepResult,
                        resourceDemandEstimationStepResult, samplingStepResult))
            }
            return ResourceAttributionStepResult(results)
        }

    }

}

class ResourceAttributionStepPhaseResult(
        val phase: Phase,
        val blockingMetrics: Set<Metric.Blocking>,
        val unusedBlockingMetrics: Set<Metric.Blocking>,
        val unusedConsumableMetrics: Set<Metric.Consumable>,
        private val metricRuleMap: Map<Metric.Consumable, ConsumableResourceAttributionRule>,
        private val resourceDemandIterators: Map<Metric.Consumable, () -> ResourceDemandIterator>,
        private val sampleIterators: Map<Metric.Consumable, () -> MetricSampleIterator>,
        private val phaseActiveIterator: () -> PhaseActiveIterator
) {

    val consumableMetrics: Set<Metric.Consumable>
        get() = metricRuleMap.keys

    val metrics: Set<Metric>
        get() = blockingMetrics + consumableMetrics

    val unusedMetrics: Set<Metric>
        get() = unusedBlockingMetrics + unusedConsumableMetrics

    init {
        require(metricRuleMap.keys == resourceDemandIterators.keys && metricRuleMap.keys == sampleIterators.keys) {
            "Attribution rules, demand iterators, and sample iterators must be defined for the same metrics"
        }
    }

    fun iterator(metric: Metric.Blocking): BlockingMetricAttributionIterator {
        require(metric in blockingMetrics) { "No results found for metric \"${metric.path}\"" }
        return BlockingMetricAttributionIterator(phase, metric)
    }

    fun iterator(metric: Metric.Consumable): ConsumableMetricAttributionIterator {
        val rule = metricRuleMap[metric]
        return when (rule) {
            is ConsumableResourceAttributionRule.Exact ->
                ExactConsumableMetricAttributionIterator(
                        phase.firstTimeslice,
                        rule.exactDemand,
                        resourceDemandIterators[metric]!!.invoke(),
                        sampleIterators[metric]!!.invoke(),
                        phaseActiveIterator()
                )
            is ConsumableResourceAttributionRule.Variable ->
                VariableConsumableMetricAttributionIterator(
                        phase.firstTimeslice,
                        rule.relativeDemand,
                        resourceDemandIterators[metric]!!.invoke(),
                        sampleIterators[metric]!!.invoke(),
                        phaseActiveIterator()
                )
            ConsumableResourceAttributionRule.None ->
                throw IllegalStateException("Metrics with rule None should not have results")
            null -> throw IllegalArgumentException("No results found for metric \"${metric.path}\"")
        }
    }

    fun serialize(output: Grade10Serializer) {
        // 1. The phase
        output.write(phase)
        // 2. Number of blocking metrics
        output.writeVarInt(blockingMetrics.size, true)
        // 3. For each blocking metric:
        blockingMetrics.forEach { metric ->
            // 3.1. The metric
            output.write(metric)
        }
        // 4. Number of unused blocking metrics
        output.writeVarInt(unusedBlockingMetrics.size, true)
        // 5. For each unused blocking metric:
        unusedBlockingMetrics.forEach { metric ->
            // 5.1. The metric
            output.write(metric)
        }
        // 6. Number of unused consumable metrics
        output.writeVarInt(unusedConsumableMetrics.size, true)
        // 7. For each unused consumable metric:
        unusedConsumableMetrics.forEach { metric ->
            // 7.1. The metric
            output.write(metric)
        }
        // 8. Number of consumable metrics
        output.writeVarInt(consumableMetrics.size, true)
        // 9. For each consumable metric:
        consumableMetrics.forEach { metric ->
            // 9.1. The metric
            output.write(metric)
            // 9.2. Its corresponding resource attribution rule
            metricRuleMap[metric]!!.serialize(output)
        }
    }

    companion object {

        fun deserialize(
                input: Grade10Deserializer,
                activePhaseDetectionStepResult: ActivePhaseDetectionStepResult,
                resourceDemandEstimationStepResult: ResourceDemandEstimationStepResult,
                samplingStepResult: ResourceSamplingStepResult
        ): ResourceAttributionStepPhaseResult {
            // 1. The phase
            val phase = input.readPhase()!!
            // 2. Number of blocking metrics
            val numBlockingMetrics = input.readVarInt(true)
            val blockingMetrics = mutableSetOf<Metric.Blocking>()
            // 3. For each blocking metric:
            repeat(numBlockingMetrics) {
                // 3.1. The metric
                blockingMetrics.add(input.readMetric() as Metric.Blocking)
            }
            // 4. Number of unused blocking metrics
            val numUnusedBlockingMetrics = input.readVarInt(true)
            val unusedBlockingMetrics = mutableSetOf<Metric.Blocking>()
            // 5. For each unused blocking metric:
            repeat(numUnusedBlockingMetrics) {
                // 5.1. The metric
                unusedBlockingMetrics.add(input.readMetric() as Metric.Blocking)
            }
            // 6. Number of unused consumable metrics
            val numUnusedConsumableMetrics = input.readVarInt(true)
            val unusedConsumableMetrics = mutableSetOf<Metric.Consumable>()
            // 7. For each unused consumable metric:
            repeat(numUnusedConsumableMetrics) {
                // 7.1. The metric
                unusedConsumableMetrics.add(input.readMetric() as Metric.Consumable)
            }
            // 8. Number of consumable metrics
            val numConsumableMetrics = input.readVarInt(true)
            val metricRuleMap = mutableMapOf<Metric.Consumable, ConsumableResourceAttributionRule>()
            // 9. For each consumable metric:
            repeat(numConsumableMetrics) {
                // 9.1. The metric
                val metric = input.readMetric() as Metric.Consumable
                // 9.2. Its corresponding resource attribution rule
                metricRuleMap[metric] = ConsumableResourceAttributionRule.deserialize(input)
            }

            val fromTimeSlice = phase.firstTimeslice
            val toTimeSliceInclusive = phase.lastTimeslice
            val demandIterators = metricRuleMap.mapValues { (metric, _) ->
                { resourceDemandEstimationStepResult.demandIterator(metric, fromTimeSlice, toTimeSliceInclusive) }
            }
            val sampleIterators = metricRuleMap.mapValues { (metric, _) ->
                { samplingStepResult.sampleIterator(metric, fromTimeSlice, toTimeSliceInclusive) }
            }
            val phaseActiveIterator = {
                activePhaseDetectionStepResult.activeIterator(phase, fromTimeSlice, toTimeSliceInclusive)
            }
            return ResourceAttributionStepPhaseResult(phase, blockingMetrics, unusedBlockingMetrics,
                    unusedConsumableMetrics, metricRuleMap, demandIterators, sampleIterators, phaseActiveIterator)
        }

    }

}

class BlockingMetricAttributionIterator(
        phase: Phase,
        metric: Metric.Blocking
) {

    private val phaseActiveIterator = PhaseActiveIterator(
            TimeSlicePeriodList(phase.timesliceRange) - metric.blockedTimeSlices,
            phase.firstTimeslice,
            phase.lastTimeslice
    )

    fun hasNext(): Boolean = phaseActiveIterator.hasNext()

    fun nextIsBlocked(): Boolean {
        return !phaseActiveIterator.nextIsActive()
    }

}

interface ConsumableMetricAttributionIterator {

    val timeslice: TimesliceId

    val attributedUsage: Double

    val availableCapacity: Double

    fun hasNext(): Boolean

    fun computeNext()

}

class ExactConsumableMetricAttributionIterator(
        startTimeslice: TimesliceId,
        private val exactDemand: Double,
        private val resourceDemandIterator: ResourceDemandIterator,
        private val sampleIterator: MetricSampleIterator,
        private val phaseActiveIterator: PhaseActiveIterator
) : ConsumableMetricAttributionIterator {

    override var timeslice: TimesliceId = startTimeslice - 1
        private set

    override var attributedUsage: Double = 0.0
        private set

    override var availableCapacity: Double = 0.0
        private set

    override fun hasNext(): Boolean = resourceDemandIterator.hasNext()

    override fun computeNext() {
        resourceDemandIterator.computeNext()
        val sample = sampleIterator.nextSample()
        val isActive = phaseActiveIterator.nextIsActive()

        if (isActive) {
            val exactFraction = exactDemand / resourceDemandIterator.exactDemand
            attributedUsage = minOf(exactDemand, sample * exactFraction)
            availableCapacity = minOf(exactDemand, sampleIterator.capacity * exactFraction)
        } else {
            attributedUsage = 0.0
            availableCapacity = 0.0
        }

        timeslice++
    }

}

class VariableConsumableMetricAttributionIterator(
        startTimeslice: TimesliceId,
        private val relativeDemand: Double,
        private val resourceDemandIterator: ResourceDemandIterator,
        private val sampleIterator: MetricSampleIterator,
        private val phaseActiveIterator: PhaseActiveIterator
) : ConsumableMetricAttributionIterator {

    override var timeslice: TimesliceId = startTimeslice - 1
        private set

    override var attributedUsage: Double = 0.0
        private set

    override var availableCapacity: Double = 0.0
        private set

    override fun hasNext(): Boolean = resourceDemandIterator.hasNext()

    override fun computeNext() {
        resourceDemandIterator.computeNext()
        val sample = sampleIterator.nextSample()
        val isActive = phaseActiveIterator.nextIsActive()

        if (isActive) {
            val variableSample = maxOf(0.0, sample - resourceDemandIterator.exactDemand)
            val variableCapacity = maxOf(0.0, sampleIterator.capacity - resourceDemandIterator.exactDemand)
            val variableFraction = relativeDemand / resourceDemandIterator.variableDemand
            attributedUsage = variableSample * variableFraction
            availableCapacity = variableCapacity * variableFraction
        } else {
            attributedUsage = 0.0
            availableCapacity = 0.0
        }

        timeslice++
    }

}
