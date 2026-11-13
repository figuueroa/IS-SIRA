package com.example.sira

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Date

/* =====================================================================
 * Conexión con el servidor SIRA (carpeta /server), que es quien habla con SQL Server.
 * La app NUNCA se conecta directo a la base: así la contraseña de SQL Server no viaja
 * dentro del APK y los permisos se validan en el servidor.
 *
 * Se activa con BuildConfig.USAR_SERVIDOR (app/build.gradle.kts). La URL sale de
 * BuildConfig.API_BASE_URL; en el emulador 10.0.2.2 es "la computadora donde corre el servidor".
 *
 * Sin dependencias nuevas: HttpURLConnection + org.json vienen con Android.
 * ===================================================================== */

class ApiException(val codigo: Int, val error: String?) : IOException("HTTP $codigo ${error.orEmpty()}")

object ApiCliente {
    private val base = BuildConfig.API_BASE_URL.trimEnd('/') + "/"

    @Volatile
    private var token: String? = null

    /** Se pone en true si el servidor rechaza la sesión (expiró o la revocaron): MainActivity vuelve al login. */
    private val _sesionExpirada = MutableStateFlow(false)
    val sesionExpirada: StateFlow<Boolean> = _sesionExpirada.asStateFlow()

    val haySesion: Boolean get() = token != null

    internal fun iniciarSesion(nuevoToken: String) {
        token = nuevoToken
        _sesionExpirada.value = false
    }

    /** Cierra la sesión local y avisa al servidor para revocarla (si falla, igual se olvida el token). */
    fun cerrarSesion() {
        val anterior = token ?: return
        token = null
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { llamar("POST", "auth/logout", null, tokenForzado = anterior) }
        }
    }

    suspend fun get(ruta: String): String = llamar("GET", ruta, null)
    suspend fun post(ruta: String, cuerpo: JSONObject?): String = llamar("POST", ruta, cuerpo)
    suspend fun put(ruta: String, cuerpo: JSONObject?): String = llamar("PUT", ruta, cuerpo)

    fun q(valor: String): String = URLEncoder.encode(valor, "UTF-8").replace("+", "%20")

    private suspend fun llamar(metodo: String, ruta: String, cuerpo: JSONObject?, tokenForzado: String? = null): String =
        withContext(Dispatchers.IO) {
            val conexion = URL(base + ruta.trimStart('/')).openConnection() as HttpURLConnection
            try {
                conexion.requestMethod = metodo
                conexion.connectTimeout = 10_000
                conexion.readTimeout = 30_000
                conexion.setRequestProperty("Accept", "application/json")
                (tokenForzado ?: token)?.let { conexion.setRequestProperty("Authorization", "Bearer $it") }
                if (cuerpo != null) {
                    conexion.doOutput = true
                    conexion.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conexion.outputStream.use { it.write(cuerpo.toString().toByteArray(Charsets.UTF_8)) }
                }
                val codigo = conexion.responseCode
                val flujo = if (codigo in 200..299) conexion.inputStream else conexion.errorStream
                val texto = flujo?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (codigo !in 200..299) {
                    val error = runCatching { JSONObject(texto).optString("error") }.getOrNull()
                    if (codigo == 401 && tokenForzado == null && token != null) {
                        token = null
                        _sesionExpirada.value = true
                    }
                    throw ApiException(codigo, error)
                }
                texto
            } finally {
                conexion.disconnect()
            }
        }
}

/* ---------- Ayudas para leer JSON (org.json devuelve "null" como texto si no se cuida) ---------- */
private fun JSONObject.texto(clave: String): String = if (isNull(clave)) "" else getString(clave)
private fun JSONObject.textoONulo(clave: String): String? = if (isNull(clave)) null else getString(clave)
private fun JSONObject.largoONulo(clave: String): Long? = if (isNull(clave)) null else getLong(clave)
private inline fun <T> JSONArray.mapear(f: (JSONObject) -> T): List<T> = List(length()) { f(getJSONObject(it)) }
private fun json(vararg pares: Pair<String, Any?>) = JSONObject().apply { pares.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }

private fun JSONObject.aAlumno() = Alumno(texto("matricula"), texto("nombre"))

/** Códigos de rol de la base (dbo.Roles.RoleCode) ↔ roles que conoce la app. */
internal fun RolDemo.codigo(): String = when (this) {
    RolDemo.Administrador -> "ADMIN"
    RolDemo.Director -> "DIRECTOR"
    RolDemo.Enfermeria -> "NURSE"
    RolDemo.Tutor -> "GUARDIAN"
}
internal fun rolDesdeCodigo(codigo: String): RolDemo? = RolDemo.entries.firstOrNull { it.codigo() == codigo }

/* ===================== Autenticación ===================== */
class ApiAuthRepository : AuthRepository {
    override suspend fun iniciarSesion(correo: String, clave: String): ResultadoAutenticacion = try {
        val r = JSONObject(ApiCliente.post("auth/login", json("correo" to correo, "clave" to clave)))
        val rol = rolDesdeCodigo(r.texto("rol"))
        if (rol == null) ResultadoAutenticacion.CredencialesInvalidas
        else {
            ApiCliente.iniciarSesion(r.texto("token"))
            ResultadoAutenticacion.Exito(rol, r.texto("correo"))
        }
    } catch (e: ApiException) {
        when (e.codigo) {
            401, 403 -> ResultadoAutenticacion.CredencialesInvalidas
            423 -> ResultadoAutenticacion.CuentaBloqueada
            else -> throw e
        }
    }

    override suspend fun solicitarRecuperacion(correo: String): ResultadoSolicitudRecuperacion {
        ApiCliente.post("auth/recuperar", json("correo" to correo))
        return ResultadoSolicitudRecuperacion.AceptadaPorServidor
    }
}

/* ===================== Incidencias, alumnos y bitácora ===================== */
class ApiIncidenciasRepository : IncidenciasRepository {
    private val _version = MutableStateFlow(0)
    override val version: StateFlow<Int> = _version.asStateFlow()

    /* El servidor no "empuja" avisos: se pregunta cada 15 s si cambió algo (aviso "actualice"). */
    private var ultimaVersionServidor: String? = null

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (isActive) {
                delay(15_000)
                if (!ApiCliente.haySesion) { ultimaVersionServidor = null; continue }
                runCatching { JSONObject(ApiCliente.get("incidencias/version")).texto("version") }
                    .onSuccess { v ->
                        if (ultimaVersionServidor != null && v != ultimaVersionServidor) _version.update { it + 1 }
                        ultimaVersionServidor = v
                    }
            }
        }
    }

    private fun cambioLocal() { _version.update { it + 1 } }

    override suspend fun buscarAlumno(texto: String): Alumno? = try {
        JSONObject(ApiCliente.get("alumnos/buscar?q=" + ApiCliente.q(texto))).aAlumno()
    } catch (e: ApiException) {
        if (e.codigo == 404) null else throw e
    }

    override suspend fun listarAlumnos(): List<Alumno> =
        JSONArray(ApiCliente.get("alumnos")).mapear { it.aAlumno() }

    override suspend fun registrar(alumno: Alumno, tipo: String, gravedad: String, fecha: Date, descripcion: String, foto: Uri?): Boolean {
        // TODO: la foto (IncidentAttachments) aún no se sube al servidor.
        ApiCliente.post("incidencias", json(
            "matricula" to alumno.matricula, "tipo" to tipo, "gravedad" to gravedad,
            "fecha" to fecha.time, "descripcion" to descripcion
        ))
        cambioLocal()
        return true
    }

    override suspend fun actualizar(incidencia: Incidencia): Boolean = try {
        ApiCliente.put("incidencias/${incidencia.id}", json(
            "tipo" to incidencia.tipo, "gravedad" to incidencia.gravedad, "descripcion" to incidencia.descripcion
        ))
        cambioLocal()
        true
    } catch (e: ApiException) {
        if (e.codigo == 403 || e.codigo == 404) false else throw e
    }

    override suspend fun eliminar(id: String, motivo: String): Boolean = try {
        ApiCliente.post("incidencias/$id/eliminar", json("motivo" to motivo))
        cambioLocal()
        true
    } catch (e: ApiException) {
        if (e.codigo in listOf(400, 403, 404)) false else throw e
    }

    override suspend fun listarIncidencias(): List<Incidencia> =
        JSONArray(ApiCliente.get("incidencias")).mapear {
            Incidencia(
                id = it.texto("id"), folio = it.texto("folio"),
                alumno = it.getJSONObject("alumno").aAlumno(),
                tipo = it.texto("tipo"), gravedad = it.texto("gravedad"),
                fecha = Date(it.getLong("fecha")), descripcion = it.texto("descripcion")
            )
        }

    override suspend fun listarBitacora(): List<EventoBitacora> =
        JSONArray(ApiCliente.get("bitacora")).mapear {
            EventoBitacora(
                id = it.texto("id"), incidenciaId = it.texto("incidenciaId"), folio = it.texto("folio"),
                accion = it.texto("accion"), alumno = it.getJSONObject("alumno").aAlumno(),
                tipo = it.texto("tipo"), gravedad = it.texto("gravedad"),
                fechaIncidente = Date(it.getLong("fechaIncidente")), registradoEn = Date(it.getLong("registradoEn")),
                resumen = it.texto("resumen"), actor = it.textoONulo("actor")
            )
        }
}

/* ===================== Usuarios y roles ===================== */
class ApiUsuariosRepository : UsuariosRepository {
    override suspend fun listar(): List<UsuarioDemo> =
        JSONArray(ApiCliente.get("usuarios")).mapear { u ->
            rolDesdeCodigo(u.texto("rol"))?.let { UsuarioDemo(u.texto("nombre"), u.texto("correo"), it) }
        }.filterNotNull()

    override suspend fun registrar(usuario: UsuarioDemo): ResultadoRegistroUsuario = try {
        ApiCliente.post("usuarios", json("nombre" to usuario.nombre, "correo" to usuario.correo, "rol" to usuario.rol.codigo()))
        ResultadoRegistroUsuario.Exito
    } catch (e: ApiException) {
        if (e.codigo == 409) ResultadoRegistroUsuario.CorreoDuplicado else throw e
    }

    override suspend fun cambiarRol(correo: String, rol: RolDemo): Boolean = try {
        ApiCliente.put("usuarios/rol", json("correo" to correo, "rol" to rol.codigo()))
        true
    } catch (e: ApiException) {
        if (e.codigo == 404 || e.codigo == 409) false else throw e
    }
}

/* ===================== Tutores ===================== */
class ApiTutoresRepository : TutoresRepository {
    override suspend fun listar(): List<TutorVinculado> =
        JSONArray(ApiCliente.get("tutores")).mapear {
            TutorVinculado(it.texto("nombre"), it.texto("correo"), it.texto("telefono"), it.getJSONObject("alumno").aAlumno())
        }

    override suspend fun registrar(tutor: TutorVinculado): ResultadoVinculoTutor = try {
        ApiCliente.post("tutores", json(
            "nombre" to tutor.nombre, "correo" to tutor.correo, "telefono" to tutor.telefono,
            "matricula" to tutor.alumno.matricula
        ))
        ResultadoVinculoTutor.Exito
    } catch (e: ApiException) {
        when {
            e.codigo == 409 && e.error == "CORREO_EN_USO" -> ResultadoVinculoTutor.CorreoEnUso
            e.codigo == 409 -> ResultadoVinculoTutor.VinculoDuplicado
            else -> throw e
        }
    }
}

/* ===================== Expediente médico ===================== */
class ApiExpedienteMedicoRepository : ExpedienteMedicoRepository {
    override suspend fun obtener(matricula: String): ExpedienteMedico? = try {
        val e = JSONObject(ApiCliente.get("expedientes/" + ApiCliente.q(matricula)))
        ExpedienteMedico(
            matriculaAlumno = e.texto("matriculaAlumno"), tipoSangre = e.texto("tipoSangre"),
            alergias = e.texto("alergias"), padecimientos = e.texto("padecimientos"),
            medicamentos = e.texto("medicamentos"), contactoEmergencia = e.texto("contactoEmergencia"),
            telefonoEmergencia = e.texto("telefonoEmergencia"), creadoEn = e.largoONulo("creadoEn"),
            actualizadoEn = e.largoONulo("actualizadoEn"), actualizadoPor = e.textoONulo("actualizadoPor")
        )
    } catch (e: ApiException) {
        if (e.codigo == 404) null else throw e
    }

    private fun ExpedienteMedico.aJson() = json(
        "matriculaAlumno" to matriculaAlumno, "tipoSangre" to tipoSangre, "alergias" to alergias,
        "padecimientos" to padecimientos, "medicamentos" to medicamentos,
        "contactoEmergencia" to contactoEmergencia, "telefonoEmergencia" to telefonoEmergencia
    )

    override suspend fun insertar(expediente: ExpedienteMedico): Boolean = try {
        ApiCliente.post("expedientes", expediente.aJson())
        true
    } catch (e: ApiException) {
        if (e.codigo == 409) false else throw e
    }

    override suspend fun actualizar(expediente: ExpedienteMedico): Boolean = try {
        ApiCliente.put("expedientes/" + ApiCliente.q(expediente.matriculaAlumno), expediente.aJson())
        true
    } catch (e: ApiException) {
        if (e.codigo == 404) false else throw e
    }
}

/* ===================== Ingresos a enfermería ===================== */
class ApiIngresosMedicosRepository : IngresosMedicosRepository {
    private fun JSONObject.aIngreso() =
        IngresoMedico(texto("id"), texto("matricula"), Date(getLong("fecha")), texto("motivo"), texto("tratamiento"))

    override suspend fun listar(matricula: String): List<IngresoMedico> =
        JSONArray(ApiCliente.get("alumnos/${ApiCliente.q(matricula)}/ingresos")).mapear { it.aIngreso() }

    override suspend fun registrar(matricula: String, motivo: String, tratamiento: String): IngresoMedico =
        JSONObject(ApiCliente.post("alumnos/${ApiCliente.q(matricula)}/ingresos",
            json("motivo" to motivo, "tratamiento" to tratamiento))).aIngreso()
}

/* ===================== Respaldos ===================== */
class ApiRespaldosRepository : RespaldosRepository {
    private fun JSONObject.aRespaldo() = Respaldo(texto("archivo"), Date(getLong("fecha")), optBoolean("correcto", false))

    override suspend fun listar(): List<Respaldo> = JSONArray(ApiCliente.get("respaldos")).mapear { it.aRespaldo() }
    override suspend fun generar(): Respaldo = JSONObject(ApiCliente.post("respaldos", JSONObject())).aRespaldo()
}
