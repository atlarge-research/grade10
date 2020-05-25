package science.atlarge.grade10.monitor.serialization

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import science.atlarge.grade10.metrics.RateObservations
import science.atlarge.grade10.monitor.procfs.NetworkInterfaceUtilizationData

class NetworkInterfaceUtilizationDataSerializer : Serializer<NetworkInterfaceUtilizationData>(true, true) {

    override fun write(kryo: Kryo, output: Output, networkInterfaceUtilizationData: NetworkInterfaceUtilizationData) {
        networkInterfaceUtilizationData.apply {
            output.writeString(interfaceId)
            output.writeVarInt(timestamps.size, true)
            output.writeDeltaLongs(timestamps)
            output.writeDoubles(bytesReceived)
            output.writeDoubles(bytesSent)
        }
    }

    override fun read(kryo: Kryo, input: Input, type: Class<NetworkInterfaceUtilizationData>): NetworkInterfaceUtilizationData {
        val interfaceId = input.readString()
        val timestampCount = input.readVarInt(true)
        val timestamps = input.readDeltaLongs(timestampCount)
        val bytesReceived = input.readDoubles(timestampCount)
        val bytesSent = input.readDoubles(timestampCount)
        return NetworkInterfaceUtilizationData(interfaceId, timestamps, bytesReceived, bytesSent)
    }

}
