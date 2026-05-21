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
}
