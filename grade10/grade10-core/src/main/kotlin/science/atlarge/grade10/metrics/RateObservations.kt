package science.atlarge.grade10.metrics

import science.atlarge.grade10.util.*

abstract class RateObservations {

    abstract val firstTimeslice: TimesliceId
    abstract val lastTimeslice: TimesliceId
    abstract val numObservations: Int

    abstract fun observationIterator(): RateObservationPeriodIterator

    abstract fun observationIteratorForTimeSlices(
            startTimeslice: TimesliceId = firstTimeslice,
            endTimesliceInclusive: TimesliceId = lastTimeslice
    ): RateObservationPeriodIterator

    companion object {

        fun from(timeslices: TimesliceArray, values: DoubleArray): RateObservations {
            return RateObservationsImpl.from(timeslices, values)
        }

    }

}

interface RateObservationPeriodIterator {

    val periodStartTimeslice: TimesliceId
    val periodEndTimeslice: TimesliceId
    val periodTimesliceCount: TimesliceCount
        get() = periodEndTimeslice - periodStartTimeslice + 1

    val observation: Double

    fun hasNext(): Boolean

    fun nextObservationPeriod()

}

class RateObservationsImpl private constructor(
        private val timeslices: TimesliceArray,
        private val observations: DoubleArray
) : RateObservations() {

    override val firstTimeslice = timeslices.first() + 1
    override val lastTimeslice = timeslices.last()
    override val numObservations = observations.size

    override fun observationIterator(): RateObservationPeriodIterator {
        return Iterator(0, numObservations - 1)
    }

    override fun observationIteratorForTimeSlices(
            startTimeslice: TimesliceId,
            endTimesliceInclusive: TimesliceId
    ): RateObservationPeriodIterator {
        require(startTimeslice >= firstTimeslice) { "Iterator cannot start before the first observation" }
        require(endTimesliceInclusive <= lastTimeslice) {
            "Iterator cannot end after the last observation"
        }

        if (startTimeslice > endTimesliceInclusive) {
            return Iterator(0, -1)
        }

        // Find the periods corresponding to the start and end timeslice by binary search
        val firstPeriodIndex = timeslices.binarySearch(startTimeslice)
        // If the exact timeslice is found at index j, it matches the end of period j - 1,
        // otherwise, the timeslice comes between index j - 1 and j, and thus also matches period j - 1.
        val firstPeriod = if (firstPeriodIndex >= 0) firstPeriodIndex - 1 else firstPeriodIndex.inv() - 1

        // Idem for the end timeslice
        val endPeriodIndex = timeslices.binarySearch(endTimesliceInclusive)
        val endPeriod = if (endPeriodIndex >= 0) endPeriodIndex - 1 else endPeriodIndex.inv() - 1

        return Iterator(firstPeriod, endPeriod)
    }

    private fun validate() {
        for (i in 0 until timeslices.size - 1) {
            require(timeslices[i] < timeslices[i + 1]) {
                "Observation periods must be at least one time slice long"
            }
        }
    }

    private inner class Iterator(firstPeriodIndex: Int, val lastPeriodIndex: Int) : RateObservationPeriodIterator {

        override var periodStartTimeslice: TimesliceId = 0L
        override var periodEndTimeslice: TimesliceId = -1L
        override var observation: Double = 0.0

        var nextPeriodIndex = firstPeriodIndex

        override fun hasNext() = nextPeriodIndex <= lastPeriodIndex

        override fun nextObservationPeriod() {
            periodStartTimeslice = timeslices[nextPeriodIndex] + 1
            periodEndTimeslice = timeslices[nextPeriodIndex + 1]
            observation = observations[nextPeriodIndex]
            nextPeriodIndex++
        }

    }

    companion object {

        fun from(timeslices: TimesliceArray, observations: DoubleArray): RateObservationsImpl {
            return if (timeslices.isEmpty() && observations.isEmpty()) {
                RateObservationsImpl(longArrayOf(0L), doubleArrayOf())
            } else {
                require(timeslices.size == observations.size + 1) {
                    "There must be an equal number of observations and observation periods"
                }
                RateObservationsImpl(timeslices, observations).also { it.validate() }
            }
        }

    }

}
