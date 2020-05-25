package science.atlarge.grade10.monitor.util

import science.atlarge.grade10.util.Time
import science.atlarge.grade10.util.TimesliceArray
import science.atlarge.grade10.util.TimestampNsArray

fun convertEndTimestampsToTimeslices(timestamps: TimestampNsArray, T: Time): TimesliceArray {
    return LongArray(timestamps.size) { i -> T.timeSliceForEndTimestamp(timestamps[i])}
}