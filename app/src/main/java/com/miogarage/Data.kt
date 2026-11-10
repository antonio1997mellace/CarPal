package com.miogarage

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// Dates are stored as epochDay (LocalDate.toEpochDay) so comparisons are plain Long math.

@Entity
data class Vehicle(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val targa: String,
    val marca: String,
    val modello: String,
    val anno: Int?,
    val alimentazione: String,
    val km: Int,
)

@Entity(foreignKeys = [ForeignKey(Vehicle::class, ["id"], ["vehicleId"], onDelete = ForeignKey.CASCADE)], indices = [Index("vehicleId")])
data class Deadline(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: Long,
    val tipo: String,
    val data: Long,
)

@Entity(foreignKeys = [ForeignKey(Vehicle::class, ["id"], ["vehicleId"], onDelete = ForeignKey.CASCADE)], indices = [Index("vehicleId")])
data class FuelEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: Long,
    val data: Long,
    val litri: Double,
    val importo: Double,
    val km: Int,
)

// One table for maintenance, care and other costs: they differ only by categoria.
@Entity(foreignKeys = [ForeignKey(Vehicle::class, ["id"], ["vehicleId"], onDelete = ForeignKey.CASCADE)], indices = [Index("vehicleId")])
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: Long,
    val data: Long,
    val categoria: String,
    val importo: Double,
    val km: Int?,
    val note: String,
)

val DEADLINE_TYPES = listOf("Bollo", "Revisione", "Assicurazione", "Tagliando", "Altro")
val EXPENSE_CATEGORIES = listOf("Tagliando", "Gomme", "Riparazione", "Lavaggio", "Bollo", "Assicurazione", "Pedaggio", "Parcheggio", "Altro")
val FUEL_TYPES = listOf("Benzina", "Diesel", "GPL", "Metano", "Ibrida", "Elettrica")

@Dao
interface GarageDao {
    @Query("SELECT * FROM Vehicle ORDER BY id") fun vehicles(): Flow<List<Vehicle>>
    @Query("SELECT * FROM Vehicle WHERE id = :id") fun vehicle(id: Long): Flow<Vehicle?>
    @Query("SELECT * FROM Deadline WHERE vehicleId = :v ORDER BY data") fun deadlines(v: Long): Flow<List<Deadline>>
    @Query("SELECT * FROM FuelEntry WHERE vehicleId = :v ORDER BY km DESC") fun fuel(v: Long): Flow<List<FuelEntry>>
    @Query("SELECT * FROM Expense WHERE vehicleId = :v ORDER BY data DESC, id DESC") fun expenses(v: Long): Flow<List<Expense>>

    @Query("SELECT * FROM Deadline") fun allDeadlinesFlow(): Flow<List<Deadline>>

    @Query("SELECT Deadline.*, Vehicle.targa AS targa FROM Deadline JOIN Vehicle ON Vehicle.id = Deadline.vehicleId")
    suspend fun allDeadlines(): List<DeadlineWithTarga>

    @Upsert suspend fun save(v: Vehicle)
    @Insert suspend fun insert(d: Deadline)
    @Insert suspend fun insert(f: FuelEntry)
    @Insert suspend fun insert(e: Expense)
    @Delete suspend fun delete(v: Vehicle)
    @Delete suspend fun delete(d: Deadline)
    @Delete suspend fun delete(f: FuelEntry)
    @Delete suspend fun delete(e: Expense)
}

data class DeadlineWithTarga(@Embedded val deadline: Deadline, val targa: String)

@Database(entities = [Vehicle::class, Deadline::class, FuelEntry::class, Expense::class], version = 1)
abstract class GarageDb : RoomDatabase() {
    abstract fun dao(): GarageDao

    companion object {
        @Volatile private var instance: GarageDb? = null
        fun get(ctx: Context): GarageDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, GarageDb::class.java, "garage.db").build().also { instance = it }
        }
    }
}
