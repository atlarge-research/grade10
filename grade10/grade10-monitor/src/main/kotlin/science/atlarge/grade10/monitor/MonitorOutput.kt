package science.atlarge.grade10.monitor

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import science.atlarge.grade10.metrics.RateObservations
import science.atlarge.grade10.model.resources.ResourceModel
import science.atlarge.grade10.model.resources.buildResourceModel
import science.atlarge.grade10.monitor.PerfMonitorResourceModel.CPU_CORE_UTILIZATION_NAME
import science.atlarge.grade10.monitor.PerfMonitorResourceModel.CPU_NAME
import science.atlarge.grade10.monitor.PerfMonitorResourceModel.MACHINE_NAME
import science.atlarge.grade10.monitor.PerfMonitorResourceModel.NETWORK_BYTES_RECEIVED_NAME
import science.atlarge.grade10.monitor.PerfMonitorResourceModel.NETWORK_BYTES_TRANSMITTED_NAME
import science.atlarge.grade10.monitor.PerfMonitorResourceModel.NETWORK_NAME
import science.atlarge.grade10.monitor.PerfMonitorResourceModel.ROOT_NAME
import science.atlarge.grade10.monitor.PerfMonitorResourceModel.TOTAL_CPU_UTILIZATION_NAME
import science.atlarge.grade10.monitor.serialization.KryoConfiguration
import science.atlarge.grade10.monitor.util.convertEndTimestampsToTimeslices
import science.atlarge.grade10.util.Time
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class MonitorOutput(machinesData: Iterable<MachineUtilizationData>) {

    val machines = machinesData.associateBy(MachineUtilizationData::hostname)

    fun toResourceModel(
            T: Time,
            includePerCoreUtilization: Boolean = true,
            interfaceSelectionPredicate: (interfaceId: String, hostname: String) -> Boolean = { _, _ -> true },
            interfaceIdAndHostnameToCapacity: (interfaceId: String, hostname: String) -> Double = { _, _ -> 0.0 },
            diskSelectionPredicate: (diskId: String, hostname: String) -> Boolean = { _, _ -> true },
            diskIdAndHostnameToCapacity: (interfaceId: String, hostname: String) -> Double = { _, _ -> 0.0 }
    ): ResourceModel {
        return buildResourceModel(PerfMonitorResourceModel.MODEL, T) {
            newSubresource(ROOT_NAME) {
                machines.forEach { (hostname, machineData) ->
                    newSubresource(MACHINE_NAME, hostname) {
                        // Add CPU data to machine
                        newConsumableMetric(TOTAL_CPU_UTILIZATION_NAME,
                                RateObservations.from(convertEndTimestampsToTimeslices(machineData.cpu.timestamps, T), machineData.cpu.totalCoreUtilization),
                                machineData.cpu.numCpuCores.toDouble())
                        if (includePerCoreUtilization) {
                            machineData.cpu.cores.forEachIndexed { i, coreData ->
                                newSubresource(CPU_NAME, i.toString()) {
                                    newConsumableMetric(CPU_CORE_UTILIZATION_NAME,
                                            RateObservations.from(convertEndTimestampsToTimeslices(coreData.timestamps, T), coreData.utilization),
                                            1.0)
                                }
                            }
                        }

                        // Add network data to machine
                        machineData.network.interfaces.forEach { (interfaceId, netData) ->
                            if (interfaceSelectionPredicate(interfaceId, hostname)) {
                                val timeslices = convertEndTimestampsToTimeslices(netData.timestamps, T)
                                newSubresource(NETWORK_NAME, interfaceId) {
                                    newConsumableMetric(NETWORK_BYTES_RECEIVED_NAME,
                                            RateObservations.from(timeslices, netData.bytesReceived),
                                            interfaceIdAndHostnameToCapacity(interfaceId, hostname))
                                    newConsumableMetric(NETWORK_BYTES_TRANSMITTED_NAME,
                                            RateObservations.from(timeslices, netData.bytesSent),
                                            interfaceIdAndHostnameToCapacity(interfaceId, hostname))
                                }
                            }
                        }

                        // Add disk data to machine
                        machineData.disk.disks.forEach { (diskId, diskData) ->
                            if (diskSelectionPredicate(diskId, hostname)) {
                                val timeslices = convertEndTimestampsToTimeslices(diskData.timestamps, T)
                                newSubresource(PerfMonitorResourceModel.DISK_NAME, diskId) {
                                    newConsumableMetric(PerfMonitorResourceModel.DISK_BYTES_READ,
                                            RateObservations.from(timeslices, diskData.bytesRead),
                                            diskIdAndHostnameToCapacity(diskId, hostname))
                                    newConsumableMetric(PerfMonitorResourceModel.DISK_BYTES_WRITTEN,
                                            RateObservations.from(timeslices, diskData.bytesWritten),
                                            diskIdAndHostnameToCapacity(diskId, hostname))
                                    if (diskData.totalTimeSpentFraction != null) {
                                        newConsumableMetric(PerfMonitorResourceModel.DISK_UTILIZATION,
                                                RateObservations.from(timeslices, diskData.totalTimeSpentFraction),
                                                1.0)
                                    }
//                                    newConsumableMetric(DISK_READ_TIME, diskData.readTimeFraction, 1.0)
//                                    newConsumableMetric(DISK_WRITE_TIME, diskData.writeTimeFraction, 1.0)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun writeToFile(file: File, kryo: Kryo = KryoConfiguration.defaultInstance) {
        Output(GZIPOutputStream(file.outputStream())).use {
            it.writeVarInt(SERIALIZATION_VERSION, true)
            kryo.writeObject(it, this)
        }
    }

    companion object {

        const val SERIALIZATION_VERSION = 2

        fun readFromFile(file: File, kryo: Kryo = KryoConfiguration.defaultInstance): MonitorOutput? {
            Input(GZIPInputStream(file.inputStream())).use {
                return if (it.readVarInt(true) == SERIALIZATION_VERSION) {
                    kryo.readObject(it, MonitorOutput::class.java)
                } else {
                    null
                }
            }
        }

    }

}
