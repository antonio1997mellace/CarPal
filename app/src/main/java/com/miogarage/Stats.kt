package com.miogarage

enum class Status { OK, WARNING, EXPIRED }

const val WARNING_DAYS = 30

fun daysLeft(d: Deadline, today: Long) = d.data - today

fun status(d: Deadline, today: Long) = when {
    daysLeft(d, today) < 0 -> Status.EXPIRED
    daysLeft(d, today) <= WARNING_DAYS -> Status.WARNING
    else -> Status.OK
}

// Total km over total litres (the first refuel only sets the baseline). Stays right with partial refuels too:
// the error is at most one tank and shrinks as entries add up.
fun avgKmPerLiter(fuel: List<FuelEntry>): Double? {
    val s = fuel.sortedBy { it.km }
    if (s.size < 2) return null
    val km = s.last().km - s.first().km
    val litri = s.drop(1).sumOf { it.litri }
    return if (km > 0 && litri > 0) km / litri else null
}

fun currentKm(v: Vehicle, fuel: List<FuelEntry>, expenses: List<Expense>) =
    maxOf(v.km, fuel.maxOfOrNull { it.km } ?: 0, expenses.maxOfOrNull { it.km ?: 0 } ?: 0)

/** A dated odometer reading and where it came from. */
data class KmReading(val data: Long, val km: Int, val fonte: String)

/** Every refuel, and every expense that recorded the km, newest first. */
fun kmReadings(fuel: List<FuelEntry>, expenses: List<Expense>): List<KmReading> =
    (fuel.map { KmReading(it.data, it.km, "Rifornimento") } + expenses.mapNotNull { e -> e.km?.let { KmReading(e.data, it, e.categoria) } })
        .sortedWith(compareByDescending<KmReading> { it.data }.thenByDescending { it.km })

/** Km driven since day [from]: current km minus the last reading before that day (the km at registration when there is none). */
fun kmDriven(v: Vehicle, fuel: List<FuelEntry>, expenses: List<Expense>, from: Long): Int {
    val start = kmReadings(fuel, expenses).filter { it.data < from }.maxOfOrNull { it.km } ?: v.km
    return (currentKm(v, fuel, expenses) - start).coerceAtLeast(0)
}

fun totalCost(fuel: List<FuelEntry>, expenses: List<Expense>) = fuel.sumOf { it.importo } + expenses.sumOf { it.importo }

fun costPerKm(v: Vehicle, fuel: List<FuelEntry>, expenses: List<Expense>): Double? {
    val driven = currentKm(v, fuel, expenses) - v.km
    return if (driven > 0) totalCost(fuel, expenses) / driven else null
}

/** Accepts Italian "1,5" as well as "1.5". */
fun parseNum(s: String) = s.trim().replace(',', '.').toDoubleOrNull()

/** km/l of each refuel after the first, in odometer order. Only indicative when a refuel was not a full tank. */
fun consumptionSeries(fuel: List<FuelEntry>): List<Pair<FuelEntry, Double>> =
    fuel.sortedBy { it.km }.zipWithNext().mapNotNull { (prev, cur) ->
        val km = cur.km - prev.km
        if (km > 0 && cur.litri > 0) cur to km / cur.litri else null
    }

/**
 * Packs items of the given widths into the fewest rows of width `max` (first-fit, widest first),
 * so long and short labels end up sharing a row. Returns the item indices of each row.
 */
fun packRows(widths: List<Int>, max: Int, gap: Int): List<List<Int>> {
    val rows = mutableListOf<MutableList<Int>>()
    val used = mutableListOf<Int>()
    widths.indices.sortedByDescending { widths[it] }.forEach { i ->
        val r = used.indices.firstOrNull { used[it] + gap + widths[i] <= max }
        if (r == null) {
            rows += mutableListOf(i)
            used += widths[i]
        } else {
            rows[r] += i
            used[r] += gap + widths[i]
        }
    }
    return rows
}
