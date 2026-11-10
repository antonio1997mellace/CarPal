package com.miogarage

import org.junit.Assert.*
import org.junit.Test

class BackupTest {
    private val backup = Backup(
        listOf(Vehicle(1, "AB123CD", "Fiat", "Panda", null, "Benzina", 10_000), Vehicle(2, "EF456GH", "VW", "Golf", 2019, "Diesel", 5)),
        listOf(Deadline(7, 1, "Bollo", 20_000)),
        listOf(FuelEntry(3, 2, 20_001, 38.5, 68.9, 10_500)),
        listOf(Expense(4, 1, 20_002, "Gomme", 412.5, null, "4 \"stagioni\""), Expense(5, 1, 20_003, "Tagliando", 289.0, 44_000, "")),
    )

    @Test fun roundTrip() = assertEquals(backup, parseBackup(backup.toJson()))

    @Test fun rejectsBadFiles() {
        fun fails(text: String) = assertTrue(runCatching { parseBackup(text) }.isFailure)
        fails("not json")
        fails("{}")
        fails(backup.toJson().replace("MioGarage", "Other"))
        fails(backup.copy(vehicles = backup.vehicles.take(1)).toJson()) // fuel entry points at a missing vehicle
        fails(backup.copy(fuel = listOf(FuelEntry(3, 2, 20_001, 0.0, 10.0, 1))).toJson())
    }
}
