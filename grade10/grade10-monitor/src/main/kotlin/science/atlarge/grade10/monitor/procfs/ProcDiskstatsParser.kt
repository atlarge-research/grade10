package science.atlarge.grade10.monitor.procfs

import science.atlarge.grade10.monitor.util.*
import science.atlarge.grade10.util.TimestampNsArray
import java.io.File
import java.io.IOException

class ProcDiskstatsParser {

    fun parse(logFile: File): DisksUtilizationData {
        return logFile.inputStream().buffered().use { inStream ->
            // Read first message to determine number and names of disks
            val initialTimestamp = inStream.readLELong()
            require(inStream.read() == 0) { "Expecting monitoring info to start with a DISK_LIST message" }
            val numDisks = inStream.readLEB128Int()
            val diskNames = Array(numDisks) { inStream.readString() }

            val timestamps = LongArrayBuilder()
            timestamps.append(initialTimestamp)
            val bytesReadMetric = Array(numDisks) { DoubleArrayBuilder() }
            val bytesWrittenMetric = Array(numDisks) { DoubleArrayBuilder() }
            val readTimeFractionMetric = Array(numDisks) { DoubleArrayBuilder() }
            val writeTimeFractionMetric = Array(numDisks) { DoubleArrayBuilder() }
            val totalTimeSpentFractionMetric = Array(numDisks) { DoubleArrayBuilder() }
            var lastTimestamp = 0L
            var skipNext = true // TODO: Figure out why (only) the first measurement in each dataset seems to have a <1ms delta
            while (true) {
                val timestamp = inStream.tryReadLELong() ?: break
                if (!skipNext) timestamps.append(timestamp)
                try {
                    require(inStream.read() == 1) { "Repeated DISK_LIST messages are currently not supported" }
                    inStream.readLEB128Int() // Skip number of disks

                    for (i in 0 until numDisks) {
                        /*val readsCompleted =*/ inStream.readLEB128Long()
                        val sectorsRead = inStream.readLEB128Long()
                        val readTimeMs = inStream.readLEB128Long()
                        /*val writesCompleted =*/ inStream.readLEB128Long()
                        val sectorsWritten = inStream.readLEB128Long()
                        val writeTimeMs = inStream.readLEB128Long()
                        val totalTimeMs = inStream.readLEB128Long()

                        if (!skipNext) {
                            bytesReadMetric[i].append(sectorsRead.toDouble() * 512 * 1_000_000_000L / (timestamp - lastTimestamp))
                            readTimeFractionMetric[i].append(readTimeMs.toDouble() * 1_000_000L / (timestamp - lastTimestamp))
                            bytesWrittenMetric[i].append(sectorsWritten.toDouble() * 512 * 1_000_000_000L / (timestamp - lastTimestamp))
                            writeTimeFractionMetric[i].append(writeTimeMs.toDouble() * 1_000_000L / (timestamp - lastTimestamp))
                            totalTimeSpentFractionMetric[i].append(
                                    minOf(1.0, totalTimeMs.toDouble() * 1_000_000L / (timestamp - lastTimestamp)))
                        }
                    }

                    lastTimestamp = timestamp
                } catch (e: IOException) {
                    timestamps.dropLast()
                    // Metric data should have one less element than the number of timestamps
                    arrayOf(
                            bytesReadMetric,
                            readTimeFractionMetric,
                            bytesWrittenMetric,
                            writeTimeFractionMetric,
                            totalTimeSpentFractionMetric
                    ).forEach { m ->
                        m.filter { it.size >= timestamps.size }.forEach { it.dropLast() }
                    }
                    break
                }
                skipNext = false
            }

            val timestampArray = timestamps.toArray()
            val disks = diskNames.mapIndexed { i, diskId ->
                val totalTimeSpentArr = totalTimeSpentFractionMetric[i].toArray()
                val totalTimeSpentValid = totalTimeSpentArr.any { it > 0.0 }
                DiskUtilizationData(diskId,
                        timestampArray,
                        bytesReadMetric[i].toArray(),
                        readTimeFractionMetric[i].toArray(),
                        bytesWrittenMetric[i].toArray(),
                        writeTimeFractionMetric[i].toArray(),
                        if (totalTimeSpentValid) totalTimeSpentArr else null)
            }

            DisksUtilizationData(disks)
        }
    }

}

class DisksUtilizationData(diskData: Iterable<DiskUtilizationData>) {

    val disks = diskData.associateBy(DiskUtilizationData::diskId)

}

class DiskUtilizationData(
        val diskId: String,
        val timestamps: TimestampNsArray,
        val bytesRead: DoubleArray,
        val bytesWritten: DoubleArray,
        val readTimeFraction: DoubleArray,
        val writeTimeFraction: DoubleArray,
        val totalTimeSpentFraction: DoubleArray?
)