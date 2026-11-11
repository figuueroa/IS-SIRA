package com.example.sira

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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

sealed interface EstadoRegistroExpediente {
    data object Inicial : EstadoRegistroExpediente
    data object Buscando : EstadoRegistroExpediente
    data object Guardando : EstadoRegistroExpediente
    data object SinAlumno : EstadoRegistroExpediente
    data class DatosInvalidos(val alumno: Alumno) : EstadoRegistroExpediente
    data object Duplicado : EstadoRegistroExpediente
    data object Exito : EstadoRegistroExpediente
    data class Error(val alumno: Alumno? = null) : EstadoRegistroExpediente
    data class AlumnoDisponible(val alumno: Alumno) : EstadoRegistroExpediente
}

class RegistrarExpedienteViewModel(
    private val alumnos: IncidenciasRepository = Servicios.incidencias,
    private val expedientes: ExpedienteMedicoRepository = ServiciosExpedientes.repositorio
) : ViewModel() {
    private val _estado = MutableStateFlow<EstadoRegistroExpediente>(EstadoRegistroExpediente.Inicial)
    val estado = _estado.asStateFlow()
    private var alumnoSeleccionado: Alumno? = null

    fun buscarAlumno(consulta: String) {
        if (consulta.isBlank()) {
            alumnoSeleccionado = null
            _estado.value = EstadoRegistroExpediente.SinAlumno
            return
        }
        if (_estado.value == EstadoRegistroExpediente.Buscando || _estado.value == EstadoRegistroExpediente.Guardando) return
        alumnoSeleccionado = null
        _estado.value = EstadoRegistroExpediente.Buscando
        viewModelScope.launch {
            _estado.value = try {
                val alumno = alumnos.buscarAlumno(consulta.trim())
                when {
                    alumno == null -> EstadoRegistroExpediente.SinAlumno
                    expedientes.obtener(alumno.matricula) != null -> EstadoRegistroExpediente.Duplicado
                    else -> {
                        alumnoSeleccionado = alumno
                        EstadoRegistroExpediente.AlumnoDisponible(alumno)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EstadoRegistroExpediente.Error()
            }
        }
    }

    fun guardar(alumno: Alumno, tipoSangre: String, alergias: String, padecimientos: String,
                medicamentos: String, contacto: String, telefono: String) {
        if (_estado.value == EstadoRegistroExpediente.Guardando || _estado.value == EstadoRegistroExpediente.Exito) return
        if (alumnoSeleccionado?.matricula != alumno.matricula) return
        if (tipoSangre.isBlank() || alergias.isBlank() || padecimientos.isBlank() || contacto.isBlank() || telefono.isBlank()) {
            _estado.value = EstadoRegistroExpediente.DatosInvalidos(alumno)
            return
        }
        _estado.value = EstadoRegistroExpediente.Guardando
        viewModelScope.launch {
            _estado.value = try {
                val expediente = ExpedienteMedico(
                    alumno.matricula, tipoSangre.trim(), alergias.trim(), padecimientos.trim(),
                    medicamentos.trim(), contacto.trim(), telefono.trim()
                )
                if (expedientes.insertar(expediente)) EstadoRegistroExpediente.Exito
                else EstadoRegistroExpediente.Duplicado
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EstadoRegistroExpediente.Error(alumno)
            }
        }
    }

    fun limpiarMensaje() {
        when (val actual = _estado.value) {
            is EstadoRegistroExpediente.DatosInvalidos -> _estado.value = EstadoRegistroExpediente.AlumnoDisponible(actual.alumno)
            is EstadoRegistroExpediente.Error -> _estado.value = actual.alumno?.let { EstadoRegistroExpediente.AlumnoDisponible(it) }
                ?: EstadoRegistroExpediente.Inicial
            else -> if (actual !is EstadoRegistroExpediente.AlumnoDisponible) _estado.value = EstadoRegistroExpediente.Inicial
        }
    }
}

@Composable
fun RegistrarExpedienteScreen(
    onNavegar: (Seccion) -> Unit,
    onCerrarSesion: () -> Unit,
    vm: RegistrarExpedienteViewModel = viewModel()
) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    var consulta by rememberSaveable { mutableStateOf("") }
    var tipoSangre by rememberSaveable { mutableStateOf("") }
    var alergias by rememberSaveable { mutableStateOf("") }
    var padecimientos by rememberSaveable { mutableStateOf("") }
    var medicamentos by rememberSaveable { mutableStateOf("") }
    var contacto by rememberSaveable { mutableStateOf("") }
    var telefono by rememberSaveable { mutableStateOf("") }

    SiraScaffold(Seccion.RegistroExpediente, "Enfermería", onNavegar, onCerrarSesion) {
        Text("REGISTRO MÉDICO DEL ALUMNO · HU-6", color = Gris, fontSize = 12.sp, letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 10.dp))

        Tarjeta {
            Etiqueta("Alumno (matrícula o nombre)")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = consulta, onValueChange = { consulta = it; vm.limpiarMensaje() },
                    placeholder = { Text("Nombre completo o matrícula") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    colors = camposColores(), modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { vm.buscarAlumno(consulta) },
                    enabled = estado != EstadoRegistroExpediente.Buscando && estado != EstadoRegistroExpediente.Guardando,
                    shape = RoundedCornerShape(4.dp), colors = ButtonDefaults.buttonColors(containerColor = Azul),
                    modifier = Modifier.height(52.dp)
                ) { Text("Buscar") }
            }
        }

        when (val actual = estado) {
            EstadoRegistroExpediente.Inicial -> Unit
            EstadoRegistroExpediente.Buscando, EstadoRegistroExpediente.Guardando -> Row(
                Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(color = Azul, modifier = Modifier.padding(end = 12.dp))
                Text(if (actual == EstadoRegistroExpediente.Buscando) "Buscando alumno…" else "Guardando expediente…")
            }
            EstadoRegistroExpediente.SinAlumno -> Mensaje(
                "No se encontró un alumno único. Comprueba la matrícula o escribe el nombre completo.", ErrFondo, ErrBorde, ErrTexto
            )
            is EstadoRegistroExpediente.DatosInvalidos -> Column {
                Mensaje("Completa todos los campos obligatorios. Si un dato no se conoce, indícalo explícitamente.", ErrFondo, ErrBorde, ErrTexto)
                TextButton(onClick = { vm.limpiarMensaje() }) { Text("Volver al formulario", color = Azul) }
            }
            EstadoRegistroExpediente.Duplicado -> Column {
                Mensaje("Este alumno ya tiene un expediente médico registrado. Consulta Historial médico para revisarlo.", ErrFondo, ErrBorde, ErrTexto)
                Button(
                    onClick = { onNavegar(Seccion.HistorialMedico) },
                    shape = RoundedCornerShape(4.dp), colors = ButtonDefaults.buttonColors(containerColor = Azul)
                ) { Text("Abrir historial") }
            }
            EstadoRegistroExpediente.Exito -> Mensaje(
                "Expediente guardado en esta sesión de demostración.", OkFondo, OkBorde, OkTexto
            )
            is EstadoRegistroExpediente.Error -> Column {
                Mensaje("No se pudo guardar el expediente. Intenta de nuevo.", ErrFondo, ErrBorde, ErrTexto)
                if (actual.alumno != null) TextButton(onClick = { vm.limpiarMensaje() }) { Text("Volver al formulario", color = Azul) }
            }
            is EstadoRegistroExpediente.AlumnoDisponible -> {
                val alumno = actual.alumno
                Text("Alumno seleccionado", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
                Tarjeta {
                    Text(alumno.nombre, color = Azul, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("Matrícula ${alumno.matricula}", color = Gris, fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 14.dp))

                    Etiqueta("Tipo de sangre (obligatorio)")
                    Selector(tipoSangre, listOf("A+", "A−", "B+", "B−", "AB+", "AB−", "O+", "O−", "No identificado"),
                        { tipoSangre = it; vm.limpiarMensaje() })

                    CampoExpediente("Alergias (obligatorio)", alergias, { alergias = it; vm.limpiarMensaje() },
                        "Escribe las alergias o ‘Ninguna conocida’")
                    CampoExpediente("Padecimientos crónicos (obligatorio)", padecimientos,
                        { padecimientos = it; vm.limpiarMensaje() }, "Escribe los padecimientos o ‘Ninguno referido’")
                    CampoExpediente("Medicamentos", medicamentos, { medicamentos = it }, "Opcional")

                    Spacer(Modifier.height(12.dp))
                    Text("Contacto de emergencia", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    CampoExpediente("Nombre del tutor (obligatorio)", contacto,
                        { contacto = it; vm.limpiarMensaje() }, "Nombre completo")
                    OutlinedTextField(
                        value = telefono,
                        onValueChange = { telefono = it.filter { c -> c.isDigit() || c in "+ ()-" }.take(20); vm.limpiarMensaje() },
                        label = { Text("Teléfono (obligatorio)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                        colors = camposColores(), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                    )

                    Button(
                        onClick = { vm.guardar(alumno, tipoSangre, alergias, padecimientos, medicamentos, contacto, telefono) },
                        enabled = estado != EstadoRegistroExpediente.Guardando,
                        shape = RoundedCornerShape(4.dp), colors = ButtonDefaults.buttonColors(containerColor = Azul),
                        modifier = Modifier.fillMaxWidth().height(50.dp).padding(top = 4.dp)
                    ) { Text("Guardar expediente", fontWeight = FontWeight.Bold) }
                }
            }
        }

        Text("Los datos se guardan cifrados en este dispositivo. Aún no se sincronizan con un servidor.",
            color = Gris, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun CampoExpediente(etiqueta: String, valor: String, onChange: (String) -> Unit, ayuda: String) {
    OutlinedTextField(
        value = valor, onValueChange = onChange, label = { Text(etiqueta) }, placeholder = { Text(ayuda) },
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp), colors = camposColores(), minLines = 2
    )
}
