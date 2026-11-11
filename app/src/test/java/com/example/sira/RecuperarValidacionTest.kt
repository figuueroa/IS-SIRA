package com.example.sira

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pruebas de la validación de correo de I-02 (solo JUnit; no necesita dependencias nuevas). */
class RecuperarValidacionTest {

    @Test
    fun aceptaCorreosValidos() {
        listOf("admin@institucion.edu.mx", "  a.b+c@dominio.com ", "nombre_1@sub.dominio.edu.mx")
            .forEach { assertTrue(it, correoValido(it)) }
    }

    @Test
    fun rechazaCorreosInvalidos() {
        listOf("", "   ", "sin-arroba", "a@b", "a@@b.com", "a b@c.com", "@x.com", "a@.com", "a@b..com")
            .forEach { assertFalse(it, correoValido(it)) }
    }
}
