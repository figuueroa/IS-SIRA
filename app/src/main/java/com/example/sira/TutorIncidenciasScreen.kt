package com.example.sira

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.launch

sealed interface EstadoMisIncidencias {
    data object Cargando : EstadoMisIncidencias
    data object Error : EstadoMisIncidencias
    data class Listo(val alumnos: List<Alumno>, val incidencias: List<Incidencia>) : EstadoMisIncidencias
}

class TutorIncidenciasViewModel(
    private val correoTutor: String,
    private val tutoresRepo: TutoresRepository = ServiciosTutores.repositorio,
    private val incidenciasRepo: IncidenciasRepository = Servicios.incidencias
) : ViewModel() {
    private val _estado = MutableStateFlow<EstadoMisIncidencias>(EstadoMisIncidencias.Cargando)
    val estado = _estado.asStateFlow()

    init { actualizar() }

    fun actualizar() {
        _estado.value = EstadoMisIncidencias.Cargando
        viewModelScope.launch {
            _estado.value = try {
                val vinculaciones = tutoresRepo.listar().filter {
                    it.correo.trim().equals(correoTutor.trim(), ignoreCase = true)
                }
                val alumnos = vinculaciones.map { it.alumno }.distinctBy { it.matricula }
                val matriculas = alumnos.mapTo(mutableSetOf()) { it.matricula }
                val incidencias = incidenciasRepo.listarIncidencias()
                    .filter { it.alumno.matricula in matriculas }
                    .sortedByDescending { it.fecha }
                EstadoMisIncidencias.Listo(alumnos, incidencias)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                EstadoMisIncidencias.Error
            }
        }
    }
}

@Composable
fun TutorIncidenciasScreen(
    correoTutor: String,
    onNavegar: (Seccion) -> Unit,
    onCerrarSesion: () -> Unit,
    vm: TutorIncidenciasViewModel = viewModel(key = "tutor-$correoTutor") {
        TutorIncidenciasViewModel(correoTutor)
    }
) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    SiraScaffold(Seccion.TutorIncidencias, RolDemo.Tutor, onNavegar, onCerrarSesion) {
        Text("INFORMACIÓN DE MIS HIJOS", color = Gris, fontSize = 12.sp, letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 10.dp))
        Text("Cuenta familiar: $correoTutor", color = Gris, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
        OutlinedButton(
            onClick = { onNavegar(Seccion.Notificaciones) },
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
        ) { Text("Ver notificaciones de mis hijos", color = Azul) }

        when (val actual = estado) {
            EstadoMisIncidencias.Cargando -> Row(
                Modifier.fillMaxWidth().padding(vertical = 28.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(color = Azul)
                Text("Cargando tus alumnos e incidencias…", color = Gris, modifier = Modifier.padding(start = 12.dp))
            }
            EstadoMisIncidencias.Error -> Column {
                Mensaje("No se pudo cargar la información. Intenta de nuevo.", ErrFondo, ErrBorde, ErrTexto)
                Button(onClick = vm::actualizar, modifier = Modifier.padding(top = 8.dp),
                    shape = RoundedCornerShape(4.dp), colors = ButtonDefaults.buttonColors(containerColor = Azul)) {
                    Text("Reintentar")
                }
            }
            is EstadoMisIncidencias.Listo -> {
                if (actual.alumnos.isEmpty()) {
                    Mensaje("Aún no hay alumnos asociados a esta cuenta. Solicita al administrador que complete la asociación.",
                        FondoTarjeta, BordeTarjeta, Gris)
                } else {
                    Text("Hijos asociados (${actual.alumnos.size})", fontSize = 17.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                    actual.alumnos.forEach { alumno ->
                        Tarjeta(Modifier.padding(bottom = 8.dp)) {
                            Text(alumno.nombre, color = Azul, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text("Matrícula ${alumno.matricula}", color = Gris, fontSize = 13.sp)
                        }
                    }

                    Text("Historial de incidencias", fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                    if (actual.incidencias.isEmpty()) {
                        Mensaje("No hay incidencias registradas para tus hijos.", FondoTarjeta, BordeTarjeta, Gris)
                    } else {
                        actual.incidencias.forEach { incidencia ->
                            Tarjeta(Modifier.padding(bottom = 10.dp)) {
                                Text(incidencia.tipo, color = Azul, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text(incidencia.alumno.nombre, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                                Text("${formatearFechaHora(incidencia.fecha)} · Gravedad ${incidencia.gravedad.lowercase()}",
                                    color = colorGravedad(incidencia.gravedad), fontSize = 13.sp)
                                if (incidencia.descripcion.isNotBlank()) {
                                    Text(incidencia.descripcion, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                                }
                            }
                        }
                    }
                    TextButton(onClick = vm::actualizar, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Actualizar", color = Azul)
                    }
                }
            }
        }
    }
}
