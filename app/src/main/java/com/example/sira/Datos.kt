package com.example.sira

import android.net.Uri
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/* ---------------- Modelos ---------------- */
data class Alumno(val matricula: String, val nombre: String)

data class Incidencia(
    val id: Int,
    val alumno: Alumno,
    val tipo: String,
    val gravedad: String,
    val fecha: Date,
    val descripcion: String = ""
)

data class ResumenTipo(val tipo: String, val total: Int, val baja: Int, val media: Int, val fuerte: Int)

/** Opciones de los menús desplegables (puedes agregar o quitar). */
val TiposIncidencia = listOf(
    "Golpe", "Caída", "Herida o corte", "Pelea o agresión", "Mareo o desmayo",
    "Reacción alérgica", "Fiebre o malestar general", "Daño a pertenencias o instalaciones", "Otro"
)
val Gravedades = listOf("Baja", "Media", "Fuerte")

/** Nombre corto para tablas y gráficas. */
fun etiquetaCorta(tipo: String) = when (tipo) {
    "Herida o corte" -> "Herida"
    "Pelea o agresión" -> "Pelea"
    "Mareo o desmayo" -> "Mareo"
    "Reacción alérgica" -> "Alergia"
    "Fiebre o malestar general" -> "Malestar"
    "Daño a pertenencias o instalaciones" -> "Daños"
    else -> tipo
}

/* ---------------- Fechas ---------------- */
fun parsearFecha(s: String): Date? = try {
    val t = s.trim()
    if (t.length != 10) null
    else SimpleDateFormat("dd/MM/yyyy", Locale.US).apply { isLenient = false }.parse(t)
} catch (e: Exception) { null }

fun formatearFecha(d: Date): String = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(d)

fun primerDiaDelMes(): String {
    val c = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
    return formatearFecha(c.time)
}

fun esMesActual(d: Date): Boolean {
    val a = Calendar.getInstance()
    val b = Calendar.getInstance().apply { time = d }
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.MONTH) == b.get(Calendar.MONTH)
}

/* ---------------- Cálculos de estadísticas y reportes ---------------- */
fun resumirPorTipo(lista: List<Incidencia>): List<ResumenTipo> =
    lista.groupBy { it.tipo }.map { (tipo, l) ->
        ResumenTipo(
            tipo = tipo, total = l.size,
            baja = l.count { it.gravedad == "Baja" },
            media = l.count { it.gravedad == "Media" },
            fuerte = l.count { it.gravedad == "Fuerte" }
        )
    }.sortedByDescending { it.total }

/** Incidencias entre dos fechas (ambas incluidas), opcionalmente de un solo tipo. */
fun filtrarIncidencias(lista: List<Incidencia>, desde: Date, hasta: Date, tipo: String?): List<Incidencia> =
    lista.filter {
        !it.fecha.before(desde) && !it.fecha.after(hasta) && (tipo == null || it.tipo == tipo)
    }.sortedBy { it.fecha }

/* ---------------- Acceso a datos ----------------
 * Hoy: todo vive en memoria (se reinicia al cerrar la app).
 * Después: crea otra clase que implemente IncidenciasRepository y consulte tu base de datos,
 * y cambia UNA línea en `Servicios` para usarla. */
interface IncidenciasRepository {
    suspend fun buscarAlumno(texto: String): Alumno?
    suspend fun registrar(alumno: Alumno, tipo: String, gravedad: String, fecha: Date, descripcion: String, foto: Uri): Boolean
    suspend fun listarIncidencias(): List<Incidencia>

    /** Número que sube cada vez que se registra una incidencia nueva.
     *  Con base de datos: haz que cambie cuando el servidor avise de datos nuevos (o revisando cada cierto tiempo). */
    val version: StateFlow<Int>
}

object Servicios {
    val incidencias: IncidenciasRepository = FakeIncidenciasRepository()
}

private object AlmacenLocal {
    val alumnos = listOf(
        Alumno("19071726", "Bañuelos Martinez Jesus Rogelio"),
        Alumno("19071801", "Ramirez Lopez Ana Sofia"),
        Alumno("19071845", "Hernandez Garcia Carlos Daniel"),
        Alumno("19071902", "Torres Mendoza Maria Fernanda")
    )

    val incidencias = mutableListOf<Incidencia>()
    val version = MutableStateFlow(0)
    private var siguienteId = 1

    fun agregar(alumno: Alumno, tipo: String, gravedad: String, fecha: Date, descripcion: String) {
        incidencias += Incidencia(siguienteId++, alumno, tipo, gravedad, fecha, descripcion)
        version.value = version.value + 1
    }
}

class FakeIncidenciasRepository : IncidenciasRepository {

    /** Acepta matrícula, nombre, o ambos (ej. "19071726 — Bañuelos Martinez Jesus Rogelio").
     *  Ignora mayúsculas y acentos. Si escriben ambos, deben ser del mismo alumno. */
    override suspend fun buscarAlumno(texto: String): Alumno? {
        delay(400)
        val t = normalizar(texto)
        val numeros = Regex("\\d+").findAll(t).map { it.value }.toList()
        val palabras = Regex("\\p{L}+").findAll(t).map { it.value }.toList()
        if (numeros.isEmpty() && palabras.size < 2) return null   // nombre incompleto
        return AlmacenLocal.alumnos.firstOrNull { a ->
            val palabrasAlumno = Regex("\\p{L}+").findAll(normalizar(a.nombre)).map { it.value }.toSet()
            (numeros.isEmpty() || numeros == listOf(a.matricula)) && palabras.all { it in palabrasAlumno }
        }
    }

    override suspend fun registrar(alumno: Alumno, tipo: String, gravedad: String, fecha: Date, descripcion: String, foto: Uri): Boolean {
        delay(400)
        AlmacenLocal.agregar(alumno, tipo, gravedad, fecha, descripcion)   // aquí iría el guardado en la base y el aviso al tutor
        return true
    }

    override suspend fun listarIncidencias(): List<Incidencia> = AlmacenLocal.incidencias.toList()

    override val version: StateFlow<Int> = AlmacenLocal.version.asStateFlow()

    private fun normalizar(s: String) =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
}