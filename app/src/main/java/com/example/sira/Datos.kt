package com.example.sira

import android.net.Uri
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/* ---------------- Modelos ---------------- */
data class Alumno(val matricula: String, val nombre: String)

data class TutorVinculado(val nombre: String, val correo: String, val telefono: String, val alumno: Alumno)

sealed interface ResultadoVinculoTutor {
    data object Exito : ResultadoVinculoTutor
    data object VinculoDuplicado : ResultadoVinculoTutor
    data object CorreoEnUso : ResultadoVinculoTutor
}

interface TutoresRepository {
    suspend fun listar(): List<TutorVinculado>
    suspend fun registrar(tutor: TutorVinculado): ResultadoVinculoTutor
}

object ServiciosTutores {
    val repositorio: TutoresRepository = FakeTutoresRepository()
}

enum class RolDemo(val etiqueta: String) {
    Administrador("Administrador"), Director("Director"), Enfermeria("Enfermería"), Tutor("Madre, padre o tutor")
}

data class UsuarioDemo(val nombre: String, val correo: String, val rol: RolDemo)

sealed interface ResultadoRegistroUsuario {
    data object Exito : ResultadoRegistroUsuario
    data object CorreoDuplicado : ResultadoRegistroUsuario
}

interface UsuariosRepository {
    suspend fun listar(): List<UsuarioDemo>
    suspend fun registrar(usuario: UsuarioDemo): ResultadoRegistroUsuario
}

object ServiciosUsuarios {
    val repositorio: UsuariosRepository = FakeUsuariosRepository()
}

private class FakeUsuariosRepository : UsuariosRepository {
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private val usuarios = mutableListOf<UsuarioDemo>()

    override suspend fun listar(): List<UsuarioDemo> = mutex.withLock { usuarios.toList() }

    override suspend fun registrar(usuario: UsuarioDemo): ResultadoRegistroUsuario = mutex.withLock {
        if (usuarios.any { it.correo.equals(usuario.correo.trim(), ignoreCase = true) }) {
            return@withLock ResultadoRegistroUsuario.CorreoDuplicado
        }
        usuarios += usuario.copy(correo = usuario.correo.trim().lowercase())
        ResultadoRegistroUsuario.Exito
    }
}

private class FakeTutoresRepository : TutoresRepository {
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private val vinculados = mutableListOf<TutorVinculado>()

    override suspend fun listar(): List<TutorVinculado> = mutex.withLock { vinculados.toList() }

    override suspend fun registrar(tutor: TutorVinculado): ResultadoVinculoTutor = mutex.withLock {
        val correo = tutor.correo.trim().lowercase()
        val existente = vinculados.firstOrNull { it.correo.trim().lowercase() == correo }
        if (existente != null && (existente.nombre != tutor.nombre || existente.telefono != tutor.telefono)) {
            return@withLock ResultadoVinculoTutor.CorreoEnUso
        }
        if (vinculados.any { it.correo.trim().equals(correo, ignoreCase = true) && it.alumno.matricula == tutor.alumno.matricula }) {
            return@withLock ResultadoVinculoTutor.VinculoDuplicado
        }
        vinculados += tutor.copy(correo = correo)
        ResultadoVinculoTutor.Exito
    }
}

data class Incidencia(
    val id: Int,
    val alumno: Alumno,
    val tipo: String,
    val gravedad: String,
    val fecha: Date,
    val descripcion: String = ""
)

data class EventoBitacora(
    val id: Int,
    val incidenciaId: Int,
    val accion: String,
    val alumno: Alumno,
    val tipo: String,
    val gravedad: String,
    val fechaIncidente: Date,
    val registradoEn: Date,
    val resumen: String,
    val actor: String? = null
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

fun formatearFechaHora(d: Date): String =
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(d)

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
    run {
        // El formulario recibe fechas sin hora; incluye todo el día final.
        val finDelDia = Calendar.getInstance().apply {
            time = hasta
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.time
        lista.filter {
            !it.fecha.before(desde) && !it.fecha.after(finDelDia) && (tipo == null || it.tipo == tipo)
        }.sortedBy { it.fecha }
    }

/* ---------------- Acceso a datos ----------------
 * Hoy: todo vive en memoria (se reinicia al cerrar la app).
 * Después: crea otra clase que implemente IncidenciasRepository y consulte tu base de datos,
 * y cambia UNA línea en `Servicios` para usarla. */
interface IncidenciasRepository {
    suspend fun buscarAlumno(texto: String): Alumno?
    suspend fun registrar(alumno: Alumno, tipo: String, gravedad: String, fecha: Date, descripcion: String, foto: Uri?): Boolean
    suspend fun actualizar(incidencia: Incidencia): Boolean
    suspend fun eliminar(id: Int): Boolean
    suspend fun listarIncidencias(): List<Incidencia>
    suspend fun listarBitacora(): List<EventoBitacora>

    /** Número que sube cada vez que se registra una incidencia nueva.
     *  Con base de datos: haz que cambie cuando el servidor avise de datos nuevos (o revisando cada cierto tiempo). */
    val version: StateFlow<Int>
}

object Servicios {
    val incidencias: IncidenciasRepository = FakeIncidenciasRepository()
}

private object AlmacenLocal {
    val alumnos = alumnosPrueba

    val incidencias = mutableListOf<Incidencia>()
    val bitacora = mutableListOf<EventoBitacora>()
    val version = MutableStateFlow(0)
    val versionBitacora = MutableStateFlow(0)
    private var siguienteId = 1
    private var siguienteEventoId = 1

    fun agregar(alumno: Alumno, tipo: String, gravedad: String, fecha: Date, descripcion: String) {
        val incidencia = Incidencia(siguienteId++, alumno, tipo, gravedad, fecha, descripcion)
        incidencias += incidencia
        agregarEvento(incidencia, "Registrada", "Tipo: $tipo · Gravedad: $gravedad")
        version.value = version.value + 1
    }

    fun editar(anterior: Incidencia, actualizada: Incidencia) {
        val indice = incidencias.indexOfFirst { it.id == actualizada.id }
        if (indice < 0) return
        incidencias[indice] = actualizada
        val cambios = buildList {
            if (anterior.tipo != actualizada.tipo) add("Tipo: ${anterior.tipo} → ${actualizada.tipo}")
            if (anterior.gravedad != actualizada.gravedad) add("Gravedad: ${anterior.gravedad} → ${actualizada.gravedad}")
            if (anterior.descripcion != actualizada.descripcion) add("Descripción actualizada")
        }.joinToString(" · ").ifBlank { "Se guardó la edición sin cambios en los campos." }
        agregarEvento(actualizada, "Editada", cambios)
        version.value = version.value + 1
    }

    fun eliminar(id: Int): Boolean {
        val indice = incidencias.indexOfFirst { it.id == id }
        if (indice < 0) return false
        val eliminada = incidencias.removeAt(indice)
        agregarEvento(eliminada, "Eliminada", "Incidencia eliminada")
        version.value = version.value + 1
        return true
    }

    private fun agregarEvento(incidencia: Incidencia, accion: String, resumen: String) {
        bitacora += EventoBitacora(
            id = siguienteEventoId++, incidenciaId = incidencia.id, accion = accion,
            alumno = incidencia.alumno, tipo = incidencia.tipo, gravedad = incidencia.gravedad,
            fechaIncidente = incidencia.fecha, registradoEn = Date(), resumen = resumen
        )
        versionBitacora.value = versionBitacora.value + 1
    }
}

internal val alumnosPrueba = listOf(
        Alumno("19071726", "Bañuelos Martinez Jesus Rogelio"),
        Alumno("19071801", "Ramirez Lopez Ana Sofia"),
        Alumno("19071845", "Hernandez Garcia Carlos Daniel"),
        Alumno("19071902", "Torres Mendoza Maria Fernanda"),
        Alumno(matricula= "00000000", nombre = "ajo")
    )

class FakeIncidenciasRepository : IncidenciasRepository {

    /** Acepta matrícula, nombre, o ambos (ej. "19071726 — Bañuelos Martinez Jesus Rogelio").
     *  Ignora mayúsculas y acentos. Si escriben ambos, deben ser del mismo alumno. */
    override suspend fun buscarAlumno(texto: String): Alumno? {
        delay(400)
        val t = normalizar(texto)
        val tokens = Regex("[\\p{L}\\p{N}]+").findAll(t).map { it.value }.toList()
        val matricula = tokens.firstOrNull { token ->
            AlmacenLocal.alumnos.any { normalizar(it.matricula) == token }
        }
        val palabras = tokens.filter { it != matricula && it.any(Char::isLetter) }
        if (matricula == null && palabras.size < 2) return null // nombre incompleto o matrícula desconocida

        val coincidencias = AlmacenLocal.alumnos.filter { a ->
            val palabrasAlumno = Regex("\\p{L}+").findAll(normalizar(a.nombre)).map { it.value }.toSet()
            (matricula == null || normalizar(a.matricula) == matricula) && palabras.all { it in palabrasAlumno }
        }
        return coincidencias.singleOrNull() // nunca asignar silenciosamente al primer alumno coincidente
    }

    override suspend fun registrar(alumno: Alumno, tipo: String, gravedad: String, fecha: Date, descripcion: String, foto: Uri?): Boolean {
        delay(400)
        AlmacenLocal.agregar(alumno, tipo, gravedad, fecha, descripcion)   // aquí iría el guardado en la base y el aviso al tutor
        return true
    }

    override suspend fun actualizar(incidencia: Incidencia): Boolean {
        delay(300)
        val indice = AlmacenLocal.incidencias.indexOfFirst { it.id == incidencia.id }
        if (indice < 0) return false
        AlmacenLocal.editar(AlmacenLocal.incidencias[indice], incidencia)
        return true
    }

    override suspend fun eliminar(id: Int): Boolean {
        delay(300)
        return AlmacenLocal.eliminar(id)
    }

    override suspend fun listarIncidencias(): List<Incidencia> = AlmacenLocal.incidencias.toList()

    override suspend fun listarBitacora(): List<EventoBitacora> = AlmacenLocal.bitacora.asReversed().toList()

    override val version: StateFlow<Int> = AlmacenLocal.version.asStateFlow()

    private fun normalizar(s: String) =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
}
