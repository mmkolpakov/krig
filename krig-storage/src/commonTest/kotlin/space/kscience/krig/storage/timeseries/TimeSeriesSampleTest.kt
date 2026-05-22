package space.kscience.krig.storage.timeseries

import space.kscience.dataforge.names.asName
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.data.QualityCode
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.storage.profile.StorageProfiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class TimeSeriesSampleTest {

    @Test
    fun observedValueMapsToTypedSample() {
        val time = Instant.fromEpochMilliseconds(42)
        val quality = DataQuality(QualitySeverity.UNCERTAIN, QualityCode("demo.stale"))
        val observed = ObservedValue(12.5, time, quality)

        val sample = timeSeriesSample("pump.rpm".asName(), observed)

        assertEquals("pump.rpm".asName(), sample.series)
        assertEquals(12.5, sample.value)
        assertEquals(time, sample.time)
        assertEquals(quality, sample.quality)
        assertEquals(observed, sample.observed)
    }

    @Test
    fun storageProfilesUseNamePathIds() {
        assertEquals("journal.compact", StorageProfiles.JournalCompact.toString())
        assertEquals("timeseries.deadband", StorageProfiles.TimeSeriesDeadband.toString())
    }

    @Test
    fun denseRowsKeepPerSeriesQualityOverrides() {
        val bad = DataQuality(QualitySeverity.BAD, QualityCode("demo.bad"))
        val row = DenseDoubleTimeSeriesRow(
            time = Instant.fromEpochMilliseconds(42),
            values = doubleArrayOf(1.0, 2.0, 3.0),
            qualityOverrides = mapOf(1 to bad),
        )

        assertEquals(DataQuality.GOOD, row.qualityAt(0))
        assertEquals(bad, row.qualityAt(1))
        assertEquals(DataQuality.GOOD, row.qualityAt(2))
        assertEquals(bad, row.aggregateQuality)
    }
}
