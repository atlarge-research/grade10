package science.atlarge.grade10.monitor.serialization

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import science.atlarge.grade10.metrics.RateObservations
import science.atlarge.grade10.monitor.procfs.DiskUtilizationData

class DiskUtilizationDataSerializer : Serializer<DiskUtilizationData>(true, true) {

    override fun write(kryo: Kryo, output: Output, diskUtilizationData: DiskUtilizationData) {
        diskUtilizationData.apply {
            output.writeString(diskId)
            output.writeVarInt(timestamps.size, true)
            output.writeDeltaLongs(timestamps)
            output.writeDoubles(bytesRead)
            output.writeDoubles(bytesWritten)
            output.writeDoubles(readTimeFraction)
            output.writeDoubles(writeTimeFraction)
            output.writeBoolean(totalTimeSpentFraction != null)
            if (totalTimeSpentFraction != null) {
                output.writeDoubles(totalTimeSpentFraction)
            }
        }
    }

    override fun read(kryo: Kryo, input: Input, type: Class<DiskUtilizationData>): DiskUtilizationData {
        val diskId = input.readString()
        val timestampCount = input.readVarInt(true)
        val timestamps = input.readDeltaLongs(timestampCount)
        val bytesRead = input.readDoubles(timestampCount)
        val bytesWritten = input.readDoubles(timestampCount)
        val readTimeFraction = input.readDoubles(timestampCount)
        val writeTimeFraction = input.readDoubles(timestampCount)
        val hasTotalTimeSpentFraction = input.readBoolean()
        val totalTimeSpentFraction = when {
            hasTotalTimeSpentFraction -> input.readDoubles(timestampCount)
            else -> null
        }
        return DiskUtilizationData(diskId, timestamps, bytesRead, bytesWritten, readTimeFraction, writeTimeFraction,
                totalTimeSpentFraction)
    }

}