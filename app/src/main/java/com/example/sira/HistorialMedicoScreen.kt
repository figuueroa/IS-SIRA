package com.example.sira

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Date

sealed interface EstadoHistorial {
    data object Inicial : EstadoHistorial
    data object Cargando : EstadoHistorial
    data object ConsultaVacia : EstadoHistorial
    data object SinResultados : EstadoHistorial
    data object Error : EstadoHistorial
    data class Encontrado(val alumno: Alumno, val expediente: ExpedienteMedico?) : EstadoHistorial
}

sealed interface EstadoActualizacionExpediente {
    data object Inactivo : EstadoActualizacionExpediente
    data object Guardando : EstadoActualizacionExpediente
    data object DatosInvalidos : EstadoActualizacionExpediente
    data object TelefonoInvalido : EstadoActualizacionExpediente
    data object Exito : EstadoActualizacionExpediente
    data object Error : EstadoActualizacionExpediente
}

class HistorialMedicoViewModel(
    private val alumnos: IncidenciasRepository = Servicios.incidencias,
    private val expedientes: ExpedienteMedicoRepository = ServiciosExpedientes.repositorio
) : ViewModel() {
    private val _estado = MutableStateFlow<EstadoHistorial>(EstadoHistorial.Inicial)
    val estado = _estado.asStateFlow()
    private val _estadoActualizacion = MutableStateFlow<EstadoActualizacionExpediente>(EstadoActualizacionExpediente.Inactivo)
    val estadoActualizacion = _estadoActualizacion.asStateFlow()

    fun buscar(consulta: String) {
        if (consulta.isBlank()) {
            _estado.value = EstadoHistorial.ConsultaVacia
            return
        }
        if (_estado.value == EstadoHistorial.Cargando) return

        _estado.value = EstadoHistorial.Cargando
        viewModelScope.launch {
            _estado.value = try {
                val alumno = alumnos.buscarAlumno(consulta.trim())
                if (alumno == null) EstadoHistorial.SinResultados
                else EstadoHistorial.Encontrado(alumno, expedientes.obtener(alumno.matricula))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EstadoHistorial.Error
            }
        }
    }

    fun limpiar() { _estado.value = EstadoHistorial.Inicial }

    fun actualizar(
        expediente: ExpedienteMedico,
        tipoSangre: String,
        alergias: String,
        padecimientos: String,
        medicamentos: String,
        contacto: String,
        telefono: String
    ) {
        if (_estadoActualizacion.value == EstadoActualizacionExpediente.Guardando) return
        if (tipoSangre.isBlank() || alergias.isBlank() || padecimientos.isBlank() || contacto.isBlank() || telefono.isBlank()) {
            _estadoActualizacion.value = EstadoActualizacionExpediente.DatosInvalidos
            return
        }
        if (!telefonoEmergenciaValido(telefono)) {
            _estadoActualizacion.value = EstadoActualizacionExpediente.TelefonoInvalido
            return
        }

        _estadoActualizacion.value = EstadoActualizacionExpediente.Guardando
        viewModelScope.launch {
            _estadoActualizacion.value = try {
                val actualizado = expediente.copy(
                    tipoSangre = tipoSangre.trim(),
                    alergias = alergias.trim(),
                    padecimientos = padecimientos.trim(),
                    medicamentos = medicamentos.trim(),
                    contactoEmergencia = contacto.trim(),
                    telefonoEmergencia = telefono.trim(),
                    actualizadoEn = System.currentTimeMillis(),
                    actualizadoPor = null // El login actual no expone una identidad de usuario.
                )
                if (expedientes.actualizar(actualizado)) EstadoActualizacionExpediente.Exito
                else EstadoActualizacionExpediente.Error
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EstadoActualizacionExpediente.Error
            }
        }
    }

    fun limpiarEstadoActualizacion() { _estadoActualizacion.value = EstadoActualizacionExpediente.Inactivo }
}

@Composable
fun HistorialMedicoScreen(
    onNavegar: (Seccion) -> Unit,
    onCerrarSesion: () -> Unit,
    vm: HistorialMedicoViewModel = viewModel()
) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    val estadoActualizacion by vm.estadoActualizacion.collectAsStateWithLifecycle()
    var consulta by rememberSaveable { mutableStateOf("") }
    var editando by rememberSaveable { mutableStateOf(false) }
    var tipoSangre by rememberSaveable { mutableStateOf("") }
    var alergias by rememberSaveable { mutableStateOf("") }
    var padecimientos by rememberSaveable { mutableStateOf("") }
    var medicamentos by rememberSaveable { mutableStateOf("") }
    var contacto by rememberSaveable { mutableStateOf("") }
    var telefono by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(estado) {
        val encontrado = estado as? EstadoHistorial.Encontrado
        val expediente = encontrado?.expediente
        if (expediente != null) {
            editando = false
            tipoSangre = expediente.tipoSangre
            alergias = expediente.alergias
            padecimientos = expediente.padecimientos
            medicamentos = expediente.medicamentos
            contacto = expediente.contactoEmergencia
            telefono = expediente.telefonoEmergencia
        }
    }

    LaunchedEffect(estadoActualizacion) {
        if (estadoActualizacion == EstadoActualizacionExpediente.Exito && consulta.isNotBlank()) {
            editando = false
            vm.buscar(consulta)
        }
    }

    LaunchedEffect(Unit) {
        if (consulta.isNotBlank()) vm.buscar(consulta)
    }

    SiraScaffold(Seccion.HistorialMedico, RolDemo.Enfermeria, onNavegar, onCerrarSesion) {
        Text("HISTORIAL MÉDICO ESCOLAR · HU-3", color = Gris, fontSize = 12.sp, letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 10.dp))

        Tarjeta {
            Etiqueta("Buscar alumno")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = consulta,
                    onValueChange = { consulta = it; vm.limpiar() },
                    placeholder = { Text("Nombre o matrícula") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    colors = camposColores(),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { vm.buscar(consulta) },
                    enabled = estado != EstadoHistorial.Cargando,
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Azul),
                    modifier = Modifier.height(52.dp)
                ) { Text("Buscar") }
            }
        }

        when (val resultado = estado) {
            EstadoHistorial.Inicial -> Text(
                "Busca por matrícula o por el nombre completo del alumno.",
                color = Gris, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp)
            )
            EstadoHistorial.ConsultaVacia -> Mensaje(
                "Escribe una matrícula o un nombre para iniciar la búsqueda.", ErrFondo, ErrBorde, ErrTexto
            )
            EstadoHistorial.Cargando -> Row(
                Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(color = Azul, modifier = Modifier.padding(end = 12.dp))
                Text("Buscando expediente…", color = Gris)
            }
            EstadoHistorial.SinResultados -> Mensaje(
                "No se encontró un alumno único. Verifica la matrícula o escribe el nombre completo.",
                ErrFondo, ErrBorde, ErrTexto
            )
            EstadoHistorial.Error -> Mensaje(
                "No se pudo consultar el expediente. Intenta de nuevo.", ErrFondo, ErrBorde, ErrTexto
            )
            is EstadoHistorial.Encontrado -> {
                val alumno = resultado.alumno
                val expediente = resultado.expediente
                Text("Expediente médico", fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                Tarjeta {
                    Text(alumno.nombre, color = Azul, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Matrícula ${alumno.matricula}", color = Gris, fontSize = 14.sp,
                        modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))

                    Text("Antecedentes de salud", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    if (!editando || expediente == null) {
                        DatoMedico("Tipo de sangre", expediente?.tipoSangre ?: "Sin registro")
                        DatoMedico("Alergias", expediente?.alergias ?: "Sin registro")
                        DatoMedico("Padecimientos", expediente?.padecimientos ?: "Sin registro")
                        DatoMedico("Medicamentos", expediente?.medicamentos?.ifBlank { "Sin registro" } ?: "Sin registro")

                        Spacer(Modifier.height(14.dp))
                        Text("Contacto de emergencia", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        DatoMedico("Tutor", expediente?.contactoEmergencia ?: "Sin registro")
                        DatoMedico("Teléfono", expediente?.telefonoEmergencia ?: "Sin registro")
                        if (expediente != null) {
                            Spacer(Modifier.height(14.dp))
                            Text("Trazabilidad", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            DatoMedico("Registrado", expediente.creadoEn?.let { formatearFechaHora(Date(it)) } ?: "Anterior al registro de auditoría")
                            DatoMedico("Actualizado", expediente.actualizadoEn?.let { formatearFechaHora(Date(it)) } ?: "Aún no registrado")
                            DatoMedico("Responsable", expediente.actualizadoPor ?: "No disponible: falta identificar la sesión")
                        }
                    } else {
                        Etiqueta("Tipo de sangre")
                        Selector(tipoSangre, listOf("A+", "A−", "B+", "B−", "AB+", "AB−", "O+", "O−", "No identificado"),
                            { tipoSangre = it; vm.limpiarEstadoActualizacion() })
                        CampoEdicionMedica("Alergias", alergias, { alergias = it; vm.limpiarEstadoActualizacion() })
                        CampoEdicionMedica("Padecimientos crónicos", padecimientos,
                            { padecimientos = it; vm.limpiarEstadoActualizacion() })
                        CampoEdicionMedica("Medicamentos", medicamentos, { medicamentos = it })
                        Spacer(Modifier.height(12.dp))
                        Text("Contacto de emergencia", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        CampoEdicionMedica("Nombre del tutor", contacto, { contacto = it; vm.limpiarEstadoActualizacion() })
                        OutlinedTextField(
                            value = telefono,
                            onValueChange = { telefono = it.filter { c -> c.isDigit() || c in "+ ()-" }.take(20); vm.limpiarEstadoActualizacion() },
                            label = { Text("Teléfono") }, singleLine = true,
                            isError = estadoActualizacion == EstadoActualizacionExpediente.TelefonoInvalido,
                            supportingText = if (estadoActualizacion == EstadoActualizacionExpediente.TelefonoInvalido) {
                                { Text("Usa entre 10 y 15 dígitos, con código de país si aplica.") }
                            } else null,
                            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone),
                            colors = camposColores(), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                        )
                        when (estadoActualizacion) {
                            EstadoActualizacionExpediente.DatosInvalidos -> Mensaje("Completa todos los campos obligatorios.", ErrFondo, ErrBorde, ErrTexto)
                            EstadoActualizacionExpediente.TelefonoInvalido -> Unit
                            EstadoActualizacionExpediente.Error -> Mensaje("No se pudo actualizar el expediente. Intenta de nuevo.", ErrFondo, ErrBorde, ErrTexto)
                            else -> Unit
                        }
                    }
                }
                if (expediente == null) {
                    Button(
                        onClick = { onNavegar(Seccion.RegistroExpediente) },
                        shape = RoundedCornerShape(4.dp), colors = ButtonDefaults.buttonColors(containerColor = Azul),
                        modifier = Modifier.padding(top = 12.dp)
                    ) { Text("Registrar expediente") }
                } else if (!editando) {
                    TextButton(onClick = { editando = true; vm.limpiarEstadoActualizacion() }) {
                        Text("Editar expediente", color = Azul)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                        Button(
                            onClick = {
                                vm.actualizar(expediente, tipoSangre, alergias, padecimientos, medicamentos, contacto, telefono)
                            },
                            enabled = estadoActualizacion != EstadoActualizacionExpediente.Guardando,
                            shape = RoundedCornerShape(4.dp), colors = ButtonDefaults.buttonColors(containerColor = Azul)
                        ) {
                            if (estadoActualizacion == EstadoActualizacionExpediente.Guardando) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Azul)
                            } else Text("Guardar cambios")
                        }
                        TextButton(onClick = { editando = false; vm.limpiarEstadoActualizacion() }) {
                            Text("Cancelar", color = Azul)
                        }
                    }
                }
                Text(
                    when {
                        expediente == null -> "Aún no hay un expediente médico para este alumno."
                        editando -> "El responsable aparecerá como no disponible hasta que la sesión identifique al usuario."
                        else -> "Expediente cifrado guardado en este dispositivo; aún no sincroniza con un servidor."
                    },
                    color = Gris, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp)
                )
                if (estadoActualizacion == EstadoActualizacionExpediente.Exito) {
                    Mensaje("Expediente actualizado.", OkFondo, OkBorde, OkTexto)
                }
            }
        }
    }
}

@Composable
private fun DatoMedico(etiqueta: String, valor: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(etiqueta, color = Gris, fontSize = 14.sp)
        Text(valor, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CampoEdicionMedica(etiqueta: String, valor: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = valor, onValueChange = onChange, label = { Text(etiqueta) },
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp), colors = camposColores(), minLines = 2
    )
}
