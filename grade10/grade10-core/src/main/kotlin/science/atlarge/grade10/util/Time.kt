package science.atlarge.grade10.util

/*
 * Type aliases to help document the time units used throughout Grade10 (i.e., nanoseconds and timeslices)
 * without adding overhead over primitive long values.
 */

typealias TimestampNs = Long
typealias TimestampNsRange = LongRange
typealias TimestampNsArray = LongArray
typealias DurationNs = Long

typealias TimesliceId = Long
typealias TimesliceRange = LongRange
typealias TimesliceArray = LongArray
typealias TimesliceCount = Long
typealias FractionalTimesliceCount = Double

// Default to 10 millisecond timeslices
const val DEFAULT_TIMESLICE_LENGTH: DurationNs = 10_000_000L

data class Time(
        val nanosecondsPerTimeslice: Long
) {

    private fun timeSliceContainingTimestamp(timestamp: TimestampNs): TimesliceId {
        return timestamp / nanosecondsPerTimeslice
    }

    fun timeSliceForStartTimestamp(startTimestamp: TimestampNs): TimesliceId {
        val timeSliceId = timeSliceContainingTimestamp(startTimestamp)
        return if (startTimestamp - timeSliceId * nanosecondsPerTimeslice >= nanosecondsPerTimeslice / 2) {
            timeSliceId + 1
        } else {
            timeSliceId
        }
    }

    fun timeSliceForEndTimestamp(endTimestamp: TimestampNs): TimesliceId {
        val timeSliceId = timeSliceContainingTimestamp(endTimestamp)
        return if (endTimestamp - timeSliceId * nanosecondsPerTimeslice < nanosecondsPerTimeslice / 2) {
            timeSliceId - 1
        } else {
            timeSliceId
        }
    }

    fun startOfTimeSlice(timeslice: TimesliceId): TimestampNs = timeslice * nanosecondsPerTimeslice
    fun endOfTimeSlice(timeslice: TimesliceId): TimestampNs = startOfTimeSlice(timeslice + 1) - 1

}
