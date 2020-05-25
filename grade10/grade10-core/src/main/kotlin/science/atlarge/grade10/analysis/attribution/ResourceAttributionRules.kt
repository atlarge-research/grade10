package science.atlarge.grade10.analysis.attribution

import science.atlarge.grade10.model.execution.Phase
import science.atlarge.grade10.model.execution.PhaseType
import science.atlarge.grade10.model.resources.Metric
import science.atlarge.grade10.model.resources.MetricClass
import science.atlarge.grade10.model.resources.MetricType
import science.atlarge.grade10.serialization.Grade10Deserializer
import science.atlarge.grade10.serialization.Grade10Serializer

abstract class ResourceAttributionRuleProvider {

    operator fun get(phaseType: PhaseType, metricType: MetricType): ResourceAttributionRule {
        return when (metricType.metricClass) {
            MetricClass.CONSUMABLE -> getRuleForConsumableResource(phaseType, metricType)
            MetricClass.BLOCKING -> getRuleForBlockingResource(phaseType, metricType)
        }
    }

    operator fun get(phase: Phase, blockingMetric: Metric.Blocking): BlockingResourceAttributionRule {
        return getRuleForBlockingResource(phase.type, blockingMetric.type)
    }

    operator fun get(phase: Phase, consumableMetric: Metric.Consumable): ConsumableResourceAttributionRule {
        return getRuleForConsumableResource(phase.type, consumableMetric.type)
    }

    protected abstract fun getRuleForBlockingResource(phaseType: PhaseType, metricType: MetricType):
            BlockingResourceAttributionRule

    protected abstract fun getRuleForConsumableResource(phaseType: PhaseType, metricType: MetricType):
            ConsumableResourceAttributionRule

}

sealed class ResourceAttributionRule

sealed class ConsumableResourceAttributionRule : ResourceAttributionRule() {

    data class Exact(val exactDemand: Double) : ConsumableResourceAttributionRule() {
        init {
            require(exactDemand > 0.0)
            require(exactDemand.isFinite())
        }
    }

    data class Variable(val relativeDemand: Double) : ConsumableResourceAttributionRule() {
        init {
            require(relativeDemand > 0.0)
            require(relativeDemand.isFinite())
        }
    }

    object None : ConsumableResourceAttributionRule()

    fun serialize(output: Grade10Serializer) {
        when (this) {
            None -> output.writeByte(0)
            is Variable -> {
                output.writeByte(1)
                output.writeDouble(relativeDemand)
            }
            is Exact -> {
                output.writeByte(2)
                output.writeDouble(exactDemand)
            }
        }
    }

    companion object {

        fun deserialize(input: Grade10Deserializer): ConsumableResourceAttributionRule {
            val type = input.readByte()
            return when (type) {
                0.toByte() -> None
                1.toByte() -> Variable(input.readDouble())
                2.toByte() -> Exact(input.readDouble())
                else -> throw IllegalArgumentException("Unknown ID of ConsumableAttributionRule: $type")
            }
        }

    }

}

sealed class BlockingResourceAttributionRule : ResourceAttributionRule() {

    object Full : BlockingResourceAttributionRule()
    object None : BlockingResourceAttributionRule()

}
