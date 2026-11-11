package com.miogarage

import org.junit.Assert.assertEquals
import org.junit.Test

class StationsTest {
    @Test fun keepsCheapestPriceOfTheVehicleFuel() {
        val json = """{"results":[
            {"name":"A","brand":"IP","location":{"lat":41.9,"lng":12.5},"distance":"1.92","fuels":[
                {"name":"Benzina","price":2.299,"isSelf":false},{"name":"Benzina","price":1.999,"isSelf":true},{"name":"Gasolio","price":2.2,"isSelf":true}]},
            {"name":"B","brand":"Q8","location":{"lat":41.8,"lng":12.4},"distance":"0.5","fuels":[{"name":"GPL","price":0.859,"isSelf":false}]}]}"""
        val s = parseStations(json, pumpFuel("Ibrida")!!).single()
        assertEquals(Station("A", "IP", 41.9, 12.5, 1.92, 1.999, true), s)
        assertEquals("B", parseStations(json, pumpFuel("GPL")!!).single().name)
        assertEquals("Gasolio", pumpFuel("Diesel"))
        assertEquals(null, pumpFuel("Elettrica"))
    }
}
