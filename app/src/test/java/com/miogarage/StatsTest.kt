package com.miogarage

import org.junit.Assert.*
import org.junit.Test

class StatsTest {
    private val v = Vehicle(1, "AB123CD", "Fiat", "Panda", 2020, "Benzina", 10_000)
    private fun fuel(km: Int, litri: Double, importo: Double = 50.0) = FuelEntry(0, 1, 0, litri, importo, km)

    @Test fun kmPerLiterSkipsFirstFill() {
        // first fill only sets the baseline; 1000 km on 40+40 liters
        assertEquals(12.5, avgKmPerLiter(listOf(fuel(11_000, 40.0), fuel(10_000, 99.0), fuel(10_500, 40.0)))!!, 1e-9)
        assertNull(avgKmPerLiter(listOf(fuel(10_000, 40.0))))
        assertNull(avgKmPerLiter(listOf(fuel(10_000, 40.0), fuel(10_000, 40.0))))
    }

    @Test fun seriesPerRefuel() {
        val s = consumptionSeries(listOf(fuel(11_000, 50.0), fuel(10_000, 99.0), fuel(10_500, 40.0), fuel(10_500, 10.0)))
        // the duplicate-km entry is skipped rather than producing a 0 km/l bar
        assertEquals(listOf(12.5, 10.0), s.map { it.second })
        assertEquals(listOf(10_500, 11_000), s.map { it.first.km })
    }

    @Test fun deadlineStatus() {
        val d = Deadline(0, 1, "Bollo", 100)
        assertEquals(Status.EXPIRED, status(d, 101))
        assertEquals(Status.WARNING, status(d, 100))
        assertEquals(Status.WARNING, status(d, 70))
        assertEquals(Status.OK, status(d, 69))
    }

    @Test fun costs() {
        val f = listOf(fuel(10_500, 30.0, 60.0))
        val e = listOf(Expense(0, 1, 0, "Gomme", 340.0, 11_000, ""))
        assertEquals(11_000, currentKm(v, f, e))
        assertEquals(400.0, totalCost(f, e), 1e-9)
        assertEquals(0.4, costPerKm(v, f, e)!!, 1e-9)
        assertNull(costPerKm(v, emptyList(), emptyList()))
    }

    @Test fun parsesItalianDecimals() {
        assertEquals(1.5, parseNum(" 1,5 ")!!, 1e-9)
        assertNull(parseNum("abc"))
    }
}
