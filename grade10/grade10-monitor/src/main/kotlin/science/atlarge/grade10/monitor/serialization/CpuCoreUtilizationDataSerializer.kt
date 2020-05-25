package science.atlarge.grade10.monitor.serialization

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import science.atlarge.grade10.metrics.RateObservations
import science.atlarge.grade10.monitor.procfs.CpuCoreUtilizationData

class CpuCoreUtilizationDataSerializer : Serializer<CpuCoreUtilizationData>(true, true) {

    override fun write(kryo: Kryo, output: Output, cpuCoreUtilizationData: CpuCoreUtilizationData) {
        cpuCoreUtilizationData.apply {
            output.writeVarInt(coreId, true)
            output.writeVarInt(timestamps.size, true)
            output.writeDeltaLongs(timestamps)
            output.writeDoubles(utilization)
        }
    }

    override fun read(kryo: Kryo, input: Input, type: Class<CpuCoreUtilizationData>): CpuCoreUtilizationData {
        val coreId = input.readVarInt(true)
        val timestampCount = input.readVarInt(true)
        val timestamps = input.readDeltaLongs(timestampCount)
        val utilization = input.readDoubles(timestampCount)
        return CpuCoreUtilizationData(coreId, timestamps, utilization)
    }

}
