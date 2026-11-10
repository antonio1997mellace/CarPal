package com.miogarage

enum class Status { OK, WARNING, EXPIRED }

const val WARNING_DAYS = 30

fun daysLeft(d: Deadline, today: Long) = d.data - today

fun status(d: Deadline, today: Long) = when {
    daysLeft(d, today) < 0 -> Status.EXPIRED
    daysLeft(d, today) <= WARNING_DAYS -> Status.WARNING
    else -> Status.OK
}

// shortcut: assumes every refuel is a full tank (full-to-full method); partial fills skew the average, add a "pieno" flag if users need it.
fun avgKmPerLiter(fuel: List<FuelEntry>): Double? {
    val s = fuel.sortedBy { it.km }
    if (s.size < 2) return null
    val km = s.last().km - s.first().km
    val litri = s.drop(1).sumOf { it.litri }
    return if (km > 0 && litri > 0) km / litri else null
}

fun currentKm(v: Vehicle, fuel: List<FuelEntry>, expenses: List<Expense>) =
    maxOf(v.km, fuel.maxOfOrNull { it.km } ?: 0, expenses.maxOfOrNull { it.km ?: 0 } ?: 0)

fun totalCost(fuel: List<FuelEntry>, expenses: List<Expense>) = fuel.sumOf { it.importo } + expenses.sumOf { it.importo }

fun costPerKm(v: Vehicle, fuel: List<FuelEntry>, expenses: List<Expense>): Double? {
    val driven = currentKm(v, fuel, expenses) - v.km
    return if (driven > 0) totalCost(fuel, expenses) / driven else null
}

/** Accepts Italian "1,5" as well as "1.5". */
fun parseNum(s: String) = s.trim().replace(',', '.').toDoubleOrNull()
