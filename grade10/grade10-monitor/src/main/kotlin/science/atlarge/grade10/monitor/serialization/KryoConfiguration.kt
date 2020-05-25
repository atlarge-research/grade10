package science.atlarge.grade10.monitor.serialization

import com.esotericsoftware.kryo.Kryo
import science.atlarge.grade10.metrics.RateObservations
import science.atlarge.grade10.monitor.MachineUtilizationData
import science.atlarge.grade10.monitor.MonitorOutput
import science.atlarge.grade10.monitor.procfs.*

object KryoConfiguration {

    val defaultInstance: Kryo by lazy {
        configureKryoInstance(Kryo())
    }

    private fun configureKryoInstance(kryo: Kryo): Kryo {
        return kryo.apply {
            register(MonitorOutput::class.java, MonitorOutputSerializer())
            register(MachineUtilizationData::class.java, MachineUtilizationDataSerializer())
            register(CpuUtilizationData::class.java, CpuUtilizationDataSerializer())
            register(CpuCoreUtilizationData::class.java, CpuCoreUtilizationDataSerializer())
            register(NetworkUtilizationData::class.java, NetworkUtilizationDataSerializer())
            register(NetworkInterfaceUtilizationData::class.java, NetworkInterfaceUtilizationDataSerializer())
            register(DisksUtilizationData::class.java, DisksUtilizationDataSerializer())
            register(DiskUtilizationData::class.java, DiskUtilizationDataSerializer())
        }
    }

}
