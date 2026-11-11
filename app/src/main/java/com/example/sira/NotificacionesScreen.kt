package com.example.sira

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

sealed interface EstadoNotificaciones {
    data object Cargando : EstadoNotificaciones
    data object Error : EstadoNotificaciones
    data class Listo(val avisos: List<EventoBitacora>) : EstadoNotificaciones
}

class NotificacionesViewModel(
    private val correoTutor: String? = null,
    private val repo: IncidenciasRepository = Servicios.incidencias,
    private val tutoresRepo: TutoresRepository = ServiciosTutores.repositorio
) : ViewModel() {
    private val _estado = MutableStateFlow<EstadoNotificaciones>(EstadoNotificaciones.Cargando)
    val estado = _estado.asStateFlow()

    init { viewModelScope.launch { repo.version.collectLatest { cargar() } } }

    fun reintentar() { viewModelScope.launch { cargar() } }

    private suspend fun cargar() {
        _estado.value = EstadoNotificaciones.Cargando
        _estado.value = try {
            val registradas = repo.listarBitacora().filter { it.accion == "Registrada" }
            val propias = if (correoTutor.isNullOrBlank()) registradas else {
                val matriculas = tutoresRepo.listar()
                    .filter { it.correo.trim().equals(correoTutor.trim(), ignoreCase = true) }
                    .mapTo(mutableSetOf()) { it.alumno.matricula }
                registradas.filter { it.alumno.matricula in matriculas }
            }
            EstadoNotificaciones.Listo(propias)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EstadoNotificaciones.Error
        }
    }
}

@Composable
fun NotificacionesScreen(
    onNavegar: (Seccion) -> Unit,
    onCerrarSesion: () -> Unit,
    correoTutor: String? = null,
    rol: RolDemo = RolDemo.Director,
    vm: NotificacionesViewModel = viewModel(key = "notificaciones-${correoTutor ?: rol.name}") {
        NotificacionesViewModel(correoTutor)
    }
) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    var soloFuertes by rememberSaveable { mutableStateOf(false) }
    val avisos = (estado as? EstadoNotificaciones.Listo)?.avisos.orEmpty()
    val visibles = if (soloFuertes) avisos.filter { it.gravedad == "Fuerte" } else avisos
    androidx.compose.runtime.LaunchedEffect(correoTutor) { vm.reintentar() }

    SiraScaffold(Seccion.Notificaciones, rol, onNavegar, onCerrarSesion) {
        Text("NOTIFICACIONES · BANDEJA LOCAL", color = Gris, fontSize = 12.sp, letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 10.dp))
        Mensaje(
            if (correoTutor == null) {
                "Avisos locales generados a partir de incidencias registradas. No se envían por correo, SMS ni notificación push."
            } else {
                "Aquí aparecen las incidencias de tus hijos asociados a esta cuenta. Por ahora son avisos dentro de SIRA; no se envía una notificación al teléfono cuando la app está cerrada."
            },
            FondoTarjeta, BordeTarjeta, Gris
        )

        androidx.compose.material3.FilterChip(
            selected = soloFuertes,
            onClick = { soloFuertes = !soloFuertes },
            label = { Text("Solo gravedad fuerte") },
            modifier = Modifier.padding(top = 12.dp)
        )

        when (val actual = estado) {
            EstadoNotificaciones.Cargando -> Row(Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = Azul, modifier = Modifier.size(22.dp))
                Text("Cargando avisos…", color = Gris, modifier = Modifier.padding(start = 10.dp))
            }
            EstadoNotificaciones.Error -> Column {
                Mensaje("No se pudieron cargar los avisos locales.", ErrFondo, ErrBorde, ErrTexto)
                Button(onClick = vm::reintentar, modifier = Modifier.padding(top = 8.dp),
                    shape = RoundedCornerShape(4.dp), colors = ButtonDefaults.buttonColors(containerColor = Azul)) {
                    Text("Reintentar")
                }
            }
            is EstadoNotificaciones.Listo -> when {
                actual.avisos.isEmpty() -> Mensaje(
                    if (correoTutor == null) "Aún no se han registrado incidencias durante esta ejecución."
                    else "No hay incidencias nuevas para tus hijos asociados.",
                    FondoTarjeta, BordeTarjeta, Gris
                )
                visibles.isEmpty() -> Mensaje("No hay avisos de gravedad fuerte.", FondoTarjeta, BordeTarjeta, Gris)
                else -> {
                    Text("${visibles.size} aviso${if (visibles.size == 1) "" else "s"}", color = Gris,
                        fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                    visibles.forEach { aviso ->
                        Tarjeta(Modifier.padding(bottom = 10.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(if (aviso.gravedad == "Fuerte") "Requiere atención" else "Incidencia registrada",
                                    color = if (aviso.gravedad == "Fuerte") ErrTexto else Azul,
                                    fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(aviso.gravedad, color = colorGravedad(aviso.gravedad), fontWeight = FontWeight.Bold)
                            }
                            Text(aviso.alumno.nombre, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                            Text("Matrícula ${aviso.alumno.matricula}", color = Gris, fontSize = 13.sp)
                            Text("${aviso.tipo} · ${formatearFechaHora(aviso.fechaIncidente)}", fontSize = 14.sp,
                                modifier = Modifier.padding(top = 6.dp))
                            Text("Aviso local generado ${formatearFechaHora(aviso.registradoEn)}", color = Gris, fontSize = 12.sp,
                                modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
    }
}
