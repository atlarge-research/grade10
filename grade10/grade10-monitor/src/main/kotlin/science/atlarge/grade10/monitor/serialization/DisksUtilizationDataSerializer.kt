package science.atlarge.grade10.monitor.serialization

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import science.atlarge.grade10.monitor.procfs.DiskUtilizationData
import science.atlarge.grade10.monitor.procfs.DisksUtilizationData

class DisksUtilizationDataSerializer : Serializer<DisksUtilizationData>(true, true) {

    override fun write(kryo: Kryo, output: Output, disksUtilizationData: DisksUtilizationData) {
        disksUtilizationData.apply {
            output.writeVarInt(disks.size, true)
            disks.values.forEach {
                kryo.writeObject(output, it)
            }
        }
    }

    override fun read(kryo: Kryo, input: Input, type: Class<DisksUtilizationData>): DisksUtilizationData {
        val numDisks = input.readVarInt(true)
        val disks = ArrayList<DiskUtilizationData>(numDisks)
        repeat(numDisks) {
            disks.add(kryo.readObject(input, DiskUtilizationData::class.java))
        }
        return DisksUtilizationData(disks)
    }

}