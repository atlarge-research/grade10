package science.atlarge.grade10.monitor.serialization

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import science.atlarge.grade10.metrics.RateObservations
import science.atlarge.grade10.monitor.procfs.CpuCoreUtilizationData
import science.atlarge.grade10.monitor.procfs.CpuUtilizationData

class CpuUtilizationDataSerializer : Serializer<CpuUtilizationData>(true, true) {

    override fun write(kryo: Kryo, output: Output, cpuUtilizationData: CpuUtilizationData) {
        cpuUtilizationData.apply {
            output.writeVarInt(timestamps.size, true)
            output.writeDeltaLongs(timestamps)
            output.writeDoubles(totalCoreUtilization)
            output.writeVarInt(numCpuCores, true)
            output.writeVarInt(cores.size, true)
            cores.forEach { core -> kryo.writeObject(output, core) }
        }
    }

    override fun read(kryo: Kryo, input: Input, type: Class<CpuUtilizationData>): CpuUtilizationData {
        val timestampCount = input.readVarInt(true)
        val timestamps = input.readDeltaLongs(timestampCount)
        val totalCoreUtilization = input.readDoubles(timestampCount)
        val coreCount = input.readVarInt(true)
        val coreDataCount = input.readVarInt(true)
        val cores = Array<CpuCoreUtilizationData>(coreDataCount) {
            kryo.readObject(input, CpuCoreUtilizationData::class.java)
        }
        return CpuUtilizationData(timestamps, totalCoreUtilization, coreCount, cores)
    }

}
