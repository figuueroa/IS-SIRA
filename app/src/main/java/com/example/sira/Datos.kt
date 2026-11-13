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
    val repositorio: TutoresRepository =
        if (BuildConfig.USAR_SERVIDOR) ApiTutoresRepository() else FakeTutoresRepository()
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
    /** Cambia el rol de un perfil ya registrado. Devuelve false si el correo no existe. */
    suspend fun cambiarRol(correo: String, rol: RolDemo): Boolean
}

object ServiciosUsuarios {
    val repositorio: UsuariosRepository =
        if (BuildConfig.USAR_SERVIDOR) ApiUsuariosRepository() else FakeUsuariosRepository()
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

    override suspend fun cambiarRol(correo: String, rol: RolDemo): Boolean = mutex.withLock {
        val i = usuarios.indexOfFirst { it.correo.equals(correo.trim(), ignoreCase = true) }
        if (i < 0) return@withLock false
        usuarios[i] = usuarios[i].copy(rol = rol)
        true
    }
}

/* ---------------- Respaldos (I-14) ----------------
 * Con USAR_SERVIDOR: el servidor ejecuta BACKUP DATABASE y registra dbo.BackupRuns.
 * Sin servidor: la lista vive en memoria y "generar" solo agrega un registro (no crea archivo real). */
data class Respaldo(val archivo: String, val fecha: Date, val correcto: Boolean = true)

interface RespaldosRepository {
    /** Del más reciente al más antiguo. */
    suspend fun listar(): List<Respaldo>
    suspend fun generar(): Respaldo
}

object ServiciosRespaldos {
    val repositorio: RespaldosRepository =
        if (BuildConfig.USAR_SERVIDOR) ApiRespaldosRepository() else FakeRespaldosRepository()
}

private class FakeRespaldosRepository : RespaldosRepository {
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private val respaldos = mutableListOf<Respaldo>()

    override suspend fun listar(): List<Respaldo> = mutex.withLock { respaldos.asReversed().toList() }

    override suspend fun generar(): Respaldo = mutex.withLock {
        val ahora = Date()
        val nombre = "sira_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(ahora) + ".bak"
        Respaldo(nombre, ahora).also { respaldos += it }
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
    /** En la base es un UNIQUEIDENTIFIER (texto); en la demo local, un número como texto. */
    val id: String,
    val alumno: Alumno,
    val tipo: String,
    val gravedad: String,
    val fecha: Date,
    val descripcion: String = "",
    /** Folio legible (INC-2026-000123) generado por la base. */
    val folio: String = ""
)

data class EventoBitacora(
    val id: String,
    val incidenciaId: String,
    val accion: String,
    val alumno: Alumno,
    val tipo: String,
    val gravedad: String,
    val fechaIncidente: Date,
    val registradoEn: Date,
    val resumen: String,
    val actor: String? = null,
    val folio: String = ""
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
 * BuildConfig.USAR_SERVIDOR = true  → ApiIncidenciasRepository (Api.kt) → servidor → SQL Server.
 * BuildConfig.USAR_SERVIDOR = false → FakeIncidenciasRepository: todo en memoria (demo sin servidor). */
interface IncidenciasRepository {
    suspend fun buscarAlumno(texto: String): Alumno?
    /** Alumnos que el usuario puede ver (para listas desplegables). */
    suspend fun listarAlumnos(): List<Alumno>
    suspend fun registrar(alumno: Alumno, tipo: String, gravedad: String, fecha: Date, descripcion: String, foto: Uri?): Boolean
    suspend fun actualizar(incidencia: Incidencia): Boolean
    /** El motivo es obligatorio: queda en dbo.IncidentDeletionAudit (HU-18 / RF-17). */
    suspend fun eliminar(id: String, motivo: String): Boolean
    suspend fun listarIncidencias(): List<Incidencia>
    suspend fun listarBitacora(): List<EventoBitacora>

    /** Número que sube cada vez que se registra una incidencia nueva.
     *  Con base de datos: haz que cambie cuando el servidor avise de datos nuevos (o revisando cada cierto tiempo). */
    val version: StateFlow<Int>
}

object Servicios {
    val incidencias: IncidenciasRepository =
        if (BuildConfig.USAR_SERVIDOR) ApiIncidenciasRepository() else FakeIncidenciasRepository()
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
        val id = siguienteId++
        val incidencia = Incidencia(id.toString(), alumno, tipo, gravedad, fecha, descripcion, folio = "LOCAL-$id")
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

    fun eliminar(id: String, motivo: String): Boolean {
        val indice = incidencias.indexOfFirst { it.id == id }
        if (indice < 0) return false
        val eliminada = incidencias.removeAt(indice)
        agregarEvento(eliminada, "Eliminada", "Incidencia eliminada. Motivo: $motivo")
        version.value = version.value + 1
        return true
    }

    private fun agregarEvento(incidencia: Incidencia, accion: String, resumen: String) {
        bitacora += EventoBitacora(
            id = (siguienteEventoId++).toString(), incidenciaId = incidencia.id, folio = incidencia.folio, accion = accion,
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

    override suspend fun listarAlumnos(): List<Alumno> = AlmacenLocal.alumnos

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

    override suspend fun eliminar(id: String, motivo: String): Boolean {
        delay(300)
        return AlmacenLocal.eliminar(id, motivo)
    }

    override suspend fun listarIncidencias(): List<Incidencia> = AlmacenLocal.incidencias.toList()

    override suspend fun listarBitacora(): List<EventoBitacora> = AlmacenLocal.bitacora.asReversed().toList()

    override val version: StateFlow<Int> = AlmacenLocal.version.asStateFlow()

    private fun normalizar(s: String) =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
}