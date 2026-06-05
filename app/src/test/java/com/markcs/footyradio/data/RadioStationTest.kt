package com.markcs.footyradio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RadioStationTest {

    @Test
    fun testStationUniqueness() {
        val s1 = RadioStation(name = "3AW", streamURL = "url1", imageURL = "", desc = "")
        val s2 = RadioStation(name = "3AW", streamURL = "url1", imageURL = "", desc = "")
        
        // Before repository processing, they might have same (empty) ID
        assertEquals(s1.id, s2.id)
        
        val stations = listOf(s1, s2)
        
        // Mocking the repository logic (simplified)
        val uniqueStations = stations.mapIndexed { index, station ->
            val finalId = if (station.id.isBlank()) "station_$index" else station.id
            station.copy(id = finalId)
        }
        
        assertEquals("station_0", uniqueStations[0].id)
        assertEquals("station_1", uniqueStations[1].id)
        assertNotEquals(uniqueStations[0].id, uniqueStations[1].id)
    }
}
