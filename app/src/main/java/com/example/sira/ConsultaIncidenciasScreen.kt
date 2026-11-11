package com.example.sira

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date

sealed interface EstadoConsultaIncidencias {
    data object Cargando : EstadoConsultaIncidencias
    data object Error : EstadoConsultaIncidencias
    data class Listo(
        val incidencias: List<Incidencia>,
        val actualizando: Boolean = false,
        val errorActualizacion: Boolean = false,
        val eliminandoId: Int? = null,
        val errorEliminacion: Boolean = false
    ) : EstadoConsultaIncidencias
}

class ConsultaIncidenciasViewModel(
    private val repo: IncidenciasRepository = Servicios.incidencias
) : ViewModel() {
    private val _estado = MutableStateFlow<EstadoConsultaIncidencias>(EstadoConsultaIncidencias.Cargando)
    val estado = _estado.asStateFlow()

    init {
        viewModelScope.launch {
            repo.version.collectLatest { cargar() }
        }
    }

    fun reintentar() {
        viewModelScope.launch { cargar() }
    }

    fun actualizar(incidencia: Incidencia, alTerminar: (Boolean) -> Unit) {
        val anterior = _estado.value as? EstadoConsultaIncidencias.Listo ?: return
        if (anterior.actualizando) return
        _estado.value = anterior.copy(actualizando = true, errorActualizacion = false)
        viewModelScope.launch {
            val exito = try {
                repo.actualizar(incidencia)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!exito) {
                val lista = (_estado.value as? EstadoConsultaIncidencias.Listo)?.incidencias ?: anterior.incidencias
                _estado.value = EstadoConsultaIncidencias.Listo(lista, errorActualizacion = true)
            } else {
                try {
                    _estado.value = EstadoConsultaIncidencias.Listo(repo.listarIncidencias())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _estado.value = EstadoConsultaIncidencias.Error
                }
            }
            alTerminar(exito)
        }
    }

    fun eliminar(incidencia: Incidencia, alTerminar: (Boolean) -> Unit) {
        val anterior = _estado.value as? EstadoConsultaIncidencias.Listo ?: return
        if (anterior.actualizando || anterior.eliminandoId != null) return
        _estado.value = anterior.copy(eliminandoId = incidencia.id, errorEliminacion = false)
        viewModelScope.launch {
            val exito = try {
                repo.eliminar(incidencia.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (exito) {
                try {
                    _estado.value = EstadoConsultaIncidencias.Listo(repo.listarIncidencias())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _estado.value = EstadoConsultaIncidencias.Error
                }
            } else {
                val lista = (_estado.value as? EstadoConsultaIncidencias.Listo)?.incidencias ?: anterior.incidencias
                _estado.value = EstadoConsultaIncidencias.Listo(lista, errorEliminacion = true)
            }
            alTerminar(exito)
        }
    }

    private suspend fun cargar() {
        _estado.value = EstadoConsultaIncidencias.Cargando
        _estado.value = try {
            EstadoConsultaIncidencias.Listo(repo.listarIncidencias())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EstadoConsultaIncidencias.Error
        }
    }
}

@Composable
fun ConsultaIncidenciasScreen(
    onNavegar: (Seccion) -> Unit,
    onCerrarSesion: () -> Unit,
    vm: ConsultaIncidenciasViewModel = viewModel(),
    rol: RolDemo = RolDemo.Director
) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    val contexto = LocalContext.current
    val inicioMes = remember {
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    var desde by rememberSaveable { mutableLongStateOf(inicioMes) }
    var hasta by rememberSaveable {
        mutableLongStateOf(Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis)
    }
    var alumno by rememberSaveable { mutableStateOf("") }
    var tipo by rememberSaveable { mutableStateOf("Todas") }
    var gravedad by rememberSaveable { mutableStateOf("Todas") }
    var incidenciaEditando by remember { mutableStateOf<Incidencia?>(null) }
    var incidenciaEliminando by remember { mutableStateOf<Incidencia?>(null) }

    val listaCompleta = (estado as? EstadoConsultaIncidencias.Listo)?.incidencias.orEmpty()
    val rangoValido = desde <= hasta
    val resultados = if (rangoValido) {
        filtrarIncidencias(listaCompleta, Date(desde), Date(hasta), tipo.takeUnless { it == "Todas" })
            .filter { incidencia ->
                (gravedad == "Todas" || incidencia.gravedad == gravedad) &&
                    (alumno.isBlank() || incidencia.alumno.nombre.contains(alumno.trim(), ignoreCase = true) ||
                        incidencia.alumno.matricula.contains(alumno.trim(), ignoreCase = true))
            }
    } else emptyList()

    SiraScaffold(Seccion.ConsultaIncidencias, rol, onNavegar, onCerrarSesion) {
        Text("CONSULTA Y FILTRADO DE INCIDENCIAS", color = Gris, fontSize = 12.sp, letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 10.dp))

        Tarjeta {
            Etiqueta("Periodo")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CampoFecha("Desde", desde, Modifier.weight(1f)) { desde = it }
                CampoFecha("Hasta", hasta, Modifier.weight(1f)) { hasta = it }
            }
            if (!rangoValido) {
                Mensaje("La fecha inicial debe ser anterior o igual a la fecha final.", ErrFondo, ErrBorde, ErrTexto)
            }

            Etiqueta("Alumno (nombre o matrícula)", Modifier.padding(top = 12.dp))
            OutlinedTextField(
                value = alumno, onValueChange = { alumno = it },
                placeholder = { Text("Buscar alumno") }, singleLine = true,
                colors = camposColores(), modifier = Modifier.fillMaxWidth()
            )

            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Etiqueta("Tipo")
                    Selector(tipo, listOf("Todas") + TiposIncidencia, { tipo = it })
                }
                Column(Modifier.weight(1f)) {
                    Etiqueta("Gravedad")
                    Selector(gravedad, listOf("Todas") + Gravedades, { gravedad = it })
                }
            }
            TextButtonConsulta(onClick = {
                desde = inicioMes
                hasta = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                alumno = ""
                tipo = "Todas"
                gravedad = "Todas"
            })
        }

        when (val actual = estado) {
            EstadoConsultaIncidencias.Cargando -> Row(
                Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(color = Azul, modifier = Modifier.padding(end = 12.dp))
                Text("Cargando incidencias…", color = Gris)
            }
            EstadoConsultaIncidencias.Error -> Column {
                Mensaje("No se pudieron cargar las incidencias.", ErrFondo, ErrBorde, ErrTexto)
                Button(onClick = vm::reintentar, modifier = Modifier.padding(top = 8.dp),
                    shape = RoundedCornerShape(4.dp), colors = ButtonDefaults.buttonColors(containerColor = Azul)) {
                    Text("Reintentar")
                }
            }
            is EstadoConsultaIncidencias.Listo -> when {
                actual.incidencias.isEmpty() -> Mensaje(
                    "Aún no hay incidencias registradas en este dispositivo.", FondoTarjeta, BordeTarjeta, Gris
                )
                !rangoValido -> Unit
                resultados.isEmpty() -> Mensaje(
                    "No hay incidencias que coincidan con estos filtros.", FondoTarjeta, BordeTarjeta, Gris
                )
                else -> {
                    Text("${resultados.size} resultado${if (resultados.size == 1) "" else "s"}",
                        color = Gris, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
                    resultados.forEach { incidencia ->
                        Tarjeta(Modifier.padding(bottom = 10.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(incidencia.tipo, color = Azul, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text(incidencia.gravedad, color = colorGravedad(incidencia.gravedad), fontWeight = FontWeight.Bold)
                            }
                            Text(incidencia.alumno.nombre, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                            Text("Matrícula ${incidencia.alumno.matricula}", color = Gris, fontSize = 13.sp)
                            Text(formatearFechaHora(incidencia.fecha), color = Gris, fontSize = 13.sp,
                                modifier = Modifier.padding(top = 4.dp))
                            if (incidencia.descripcion.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(incidencia.descripcion, fontSize = 14.sp)
                            }
                            TextButton(onClick = { incidenciaEditando = incidencia }, modifier = Modifier.padding(top = 4.dp)) {
                                Text("Editar incidencia", color = Azul)
                            }
                            if (rol == RolDemo.Administrador) {
                                TextButton(
                                    onClick = { incidenciaEliminando = incidencia },
                                    enabled = (estado as? EstadoConsultaIncidencias.Listo)?.eliminandoId == null
                                ) { Text("Eliminar incidencia", color = ErrTexto) }
                            }
                        }
                    }
                }
            }
        }

        if (incidenciaEditando != null) {
            val actualizacion = estado as? EstadoConsultaIncidencias.Listo
            DialogoEditarIncidencia(
                incidencia = incidenciaEditando!!,
                guardando = actualizacion?.actualizando == true,
                error = actualizacion?.errorActualizacion == true,
                onCerrar = { if (actualizacion?.actualizando != true) incidenciaEditando = null },
                onGuardar = { editada -> vm.actualizar(editada) { ok -> if (ok) incidenciaEditando = null } }
            )
        }

        incidenciaEliminando?.let { incidencia ->
            val actual = estado as? EstadoConsultaIncidencias.Listo
            val eliminando = actual?.eliminandoId == incidencia.id
            ConfirmarEliminarIncidencia(
                incidencia = incidencia,
                eliminando = eliminando,
                error = actual?.errorEliminacion == true,
                onCancelar = { if (!eliminando) incidenciaEliminando = null },
                onConfirmar = { vm.eliminar(incidencia) { ok -> if (ok) incidenciaEliminando = null } }
            )
        }
    }
}

@Composable
private fun ConfirmarEliminarIncidencia(
    incidencia: Incidencia,
    eliminando: Boolean,
    error: Boolean,
    onCancelar: () -> Unit,
    onConfirmar: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancelar,
        title = { Text("Eliminar incidencia") },
        text = {
            Column {
                Text("¿Eliminar permanentemente esta incidencia?")
                Text("${incidencia.alumno.nombre} · ${incidencia.alumno.matricula}",
                    color = Gris, modifier = Modifier.padding(top = 8.dp))
                Text("${incidencia.tipo} · ${formatearFechaHora(incidencia.fecha)}",
                    color = Gris, modifier = Modifier.padding(top = 4.dp))
                if (error) Mensaje("No se pudo eliminar. Puede que el registro ya no exista; vuelve a intentarlo.",
                    ErrFondo, ErrBorde, ErrTexto)
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmar,
                enabled = !eliminando,
                colors = ButtonDefaults.buttonColors(containerColor = ErrTexto),
                shape = RoundedCornerShape(4.dp)
            ) {
                if (eliminando) CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Azul)
                else Text("Eliminar")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancelar, enabled = !eliminando) { Text("Cancelar", color = Azul) }
        }
    )
}

@Composable
private fun DialogoEditarIncidencia(
    incidencia: Incidencia,
    guardando: Boolean,
    error: Boolean,
    onCerrar: () -> Unit,
    onGuardar: (Incidencia) -> Unit
) {
    var tipo by remember(incidencia.id) { mutableStateOf(incidencia.tipo) }
    var gravedad by remember(incidencia.id) { mutableStateOf(incidencia.gravedad) }
    var descripcion by remember(incidencia.id) { mutableStateOf(incidencia.descripcion) }

    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Editar incidencia") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                Text("${incidencia.alumno.nombre} · ${incidencia.alumno.matricula}", color = Gris, fontSize = 13.sp)
                Text("Fecha: ${formatearFechaHora(incidencia.fecha)}", color = Gris, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
                Etiqueta("Tipo")
                Selector(tipo, TiposIncidencia, { tipo = it })
                Etiqueta("Gravedad", Modifier.padding(top = 12.dp))
                Selector(gravedad, Gravedades, { gravedad = it })
                Etiqueta("Descripción", Modifier.padding(top = 12.dp))
                OutlinedTextField(
                    value = descripcion, onValueChange = { descripcion = it },
                    modifier = Modifier.fillMaxWidth(), minLines = 3, colors = camposColores()
                )
                if (error) Mensaje("No se pudo guardar el cambio. Intenta de nuevo.", ErrFondo, ErrBorde, ErrTexto)
            }
        },
        confirmButton = {
            Button(
                onClick = { onGuardar(incidencia.copy(tipo = tipo, gravedad = gravedad, descripcion = descripcion.trim())) },
                enabled = !guardando,
                colors = ButtonDefaults.buttonColors(containerColor = Azul), shape = RoundedCornerShape(4.dp)
            ) {
                if (guardando) CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Azul)
                else Text("Guardar")
            }
        },
        dismissButton = { TextButton(onClick = onCerrar, enabled = !guardando) { Text("Cancelar", color = Azul) } }
    )
}

@Composable
private fun CampoFecha(etiqueta: String, millis: Long, modifier: Modifier = Modifier, onFecha: (Long) -> Unit) {
    val contexto = LocalContext.current
    OutlinedTextField(
        value = formatearFecha(Date(millis)), onValueChange = {}, readOnly = true,
        label = { Text(etiqueta) }, singleLine = true, colors = camposColores(), modifier = modifier.clickable {
            val fecha = Calendar.getInstance().apply { timeInMillis = millis }
            DatePickerDialog(contexto, { _, year, month, day ->
                fecha.set(year, month, day, 0, 0, 0)
                fecha.set(Calendar.MILLISECOND, 0)
                onFecha(fecha.timeInMillis)
            }, fecha.get(Calendar.YEAR), fecha.get(Calendar.MONTH), fecha.get(Calendar.DAY_OF_MONTH)).apply {
                datePicker.maxDate = System.currentTimeMillis()
            }.show()
        }
    )
}

@Composable
private fun TextButtonConsulta(onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) { Text("Limpiar filtros", color = Azul) }
}

internal fun colorGravedad(gravedad: String) = when (gravedad) {
    "Fuerte" -> ErrTexto
    "Media" -> ColorGravedadMedia
    else -> OkTexto
}

private val ColorGravedadMedia = androidx.compose.ui.graphics.Color(0xFF9A6700)
