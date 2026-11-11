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

sealed interface EstadoHistorial {
    data object Inicial : EstadoHistorial
    data object Cargando : EstadoHistorial
    data object ConsultaVacia : EstadoHistorial
    data object SinResultados : EstadoHistorial
    data object Error : EstadoHistorial
    data class Encontrado(val alumno: Alumno, val expediente: ExpedienteMedico?) : EstadoHistorial
}

class HistorialMedicoViewModel(
    private val alumnos: IncidenciasRepository = Servicios.incidencias,
    private val expedientes: ExpedienteMedicoRepository = ServiciosExpedientes.repositorio
) : ViewModel() {
    private val _estado = MutableStateFlow<EstadoHistorial>(EstadoHistorial.Inicial)
    val estado = _estado.asStateFlow()

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
}

@Composable
fun HistorialMedicoScreen(
    onNavegar: (Seccion) -> Unit,
    onCerrarSesion: () -> Unit,
    vm: HistorialMedicoViewModel = viewModel()
) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    var consulta by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (consulta.isNotBlank()) vm.buscar(consulta)
    }

    SiraScaffold(Seccion.HistorialMedico, "Enfermería", onNavegar, onCerrarSesion) {
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
                    DatoMedico("Tipo de sangre", expediente?.tipoSangre ?: "Sin registro")
                    DatoMedico("Alergias", expediente?.alergias ?: "Sin registro")
                    DatoMedico("Padecimientos", expediente?.padecimientos ?: "Sin registro")
                    DatoMedico("Medicamentos", expediente?.medicamentos?.ifBlank { "Sin registro" } ?: "Sin registro")

                    Spacer(Modifier.height(14.dp))
                    Text("Contacto de emergencia", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    DatoMedico("Tutor", expediente?.contactoEmergencia ?: "Sin registro")
                    DatoMedico("Teléfono", expediente?.telefonoEmergencia ?: "Sin registro")
                }
                if (expediente == null) {
                    Button(
                        onClick = { onNavegar(Seccion.RegistroExpediente) },
                        shape = RoundedCornerShape(4.dp), colors = ButtonDefaults.buttonColors(containerColor = Azul),
                        modifier = Modifier.padding(top = 12.dp)
                    ) { Text("Registrar expediente") }
                }
                Text(
                    if (expediente == null) "Aún no hay un expediente médico para este alumno."
                    else "Expediente cifrado guardado en este dispositivo; aún no sincroniza con un servidor.",
                    color = Gris, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp)
                )
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
