package com.miogarage

import org.json.JSONArray
import org.json.JSONObject

data class Backup(val vehicles: List<Vehicle>, val deadlines: List<Deadline>, val fuel: List<FuelEntry>, val expenses: List<Expense>)

suspend fun GarageDao.backup() = Backup(allVehicles(), allDeadlineRows(), allFuel(), allExpenses())

private const val VERSION = 1

fun Backup.toJson(): String = JSONObject()
    .put("app", "MioGarage").put("version", VERSION)
    .put("vehicles", JSONArray(vehicles.map {
        JSONObject().put("id", it.id).put("targa", it.targa).put("marca", it.marca).put("modello", it.modello)
            .put("anno", it.anno).put("alimentazione", it.alimentazione).put("km", it.km)
    }))
    .put("deadlines", JSONArray(deadlines.map {
        JSONObject().put("id", it.id).put("vehicleId", it.vehicleId).put("tipo", it.tipo).put("data", it.data)
    }))
    .put("fuel", JSONArray(fuel.map {
        JSONObject().put("id", it.id).put("vehicleId", it.vehicleId).put("data", it.data).put("litri", it.litri).put("importo", it.importo).put("km", it.km)
    }))
    .put("expenses", JSONArray(expenses.map {
        JSONObject().put("id", it.id).put("vehicleId", it.vehicleId).put("data", it.data).put("categoria", it.categoria)
            .put("importo", it.importo).put("km", it.km).put("note", it.note)
    }))
    .toString(2)

/** Throws on anything that is not a consistent MioGarage backup, so a bad file never reaches the database. */
fun parseBackup(text: String): Backup {
    val o = JSONObject(text)
    require(o.optString("app") == "MioGarage" && o.optInt("version") == VERSION) { "not a MioGarage backup" }
    fun <T> rows(key: String, f: (JSONObject) -> T): List<T> = o.getJSONArray(key).let { a -> (0 until a.length()).map { f(a.getJSONObject(it)) } }
    fun JSONObject.intOrNull(key: String) = if (isNull(key)) null else getInt(key)

    val b = Backup(
        rows("vehicles") { Vehicle(it.getLong("id"), it.getString("targa"), it.getString("marca"), it.getString("modello"), it.intOrNull("anno"), it.getString("alimentazione"), it.getInt("km")) },
        rows("deadlines") { Deadline(it.getLong("id"), it.getLong("vehicleId"), it.getString("tipo"), it.getLong("data")) },
        rows("fuel") { FuelEntry(it.getLong("id"), it.getLong("vehicleId"), it.getLong("data"), it.getDouble("litri"), it.getDouble("importo"), it.getInt("km")) },
        rows("expenses") { Expense(it.getLong("id"), it.getLong("vehicleId"), it.getLong("data"), it.getString("categoria"), it.getDouble("importo"), it.intOrNull("km"), it.getString("note")) },
    )
    val ids = b.vehicles.map { it.id }.toSet()
    require(ids.size == b.vehicles.size) { "duplicate vehicle ids" }
    require((b.deadlines.map { it.vehicleId } + b.fuel.map { it.vehicleId } + b.expenses.map { it.vehicleId }).all { it in ids }) { "entry without vehicle" }
    require(b.fuel.all { it.litri > 0 && it.importo >= 0 } && b.expenses.all { it.importo >= 0 }) { "invalid amounts" }
    // out-of-range values would not corrupt the database but would crash the screens that format them
    val days = 0L..100_000L // epoch days: 1970 to about 2243
    require((b.deadlines.map { it.data } + b.fuel.map { it.data } + b.expenses.map { it.data }).all { it in days }) { "invalid date" }
    require((b.vehicles.map { it.km } + b.fuel.map { it.km } + b.expenses.mapNotNull { it.km }).all { it >= 0 }) { "invalid km" }
    return b
}
