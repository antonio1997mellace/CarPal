package com.miogarage

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentsTest {
    @Test fun namesAreSafeAndUnique() {
        assertEquals("_.._etc_passwd", safeName("../../etc/passwd"))
        assertEquals("documento", safeName(".."))
        assertEquals("bollo.pdf", uniqueName("bollo.pdf", listOf("altro.pdf")))
        assertEquals("bollo (3).pdf", uniqueName("bollo.pdf", listOf("bollo.pdf", "bollo (2).pdf")))
        assertEquals("foto (2)", uniqueName("foto", listOf("foto")))
    }
}
