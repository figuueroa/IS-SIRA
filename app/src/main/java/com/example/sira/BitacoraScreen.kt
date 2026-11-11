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
import androidx.compose.material3.OutlinedTextField
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

sealed interface EstadoBitacora {
    data object Cargando : EstadoBitacora
    data object Error : EstadoBitacora
    data class Listo(val eventos: List<EventoBitacora>) : EstadoBitacora
}

class BitacoraViewModel(
    private val repo: IncidenciasRepository = Servicios.incidencias
) : ViewModel() {
    private val _estado = MutableStateFlow<EstadoBitacora>(EstadoBitacora.Cargando)
    val estado = _estado.asStateFlow()

    init {
        viewModelScope.launch { repo.version.collectLatest { cargar() } }
    }

    fun reintentar() { viewModelScope.launch { cargar() } }

    private suspend fun cargar() {
        _estado.value = EstadoBitacora.Cargando
        _estado.value = try {
            EstadoBitacora.Listo(repo.listarBitacora())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EstadoBitacora.Error
        }
    }
}

@Composable
fun BitacoraScreen(
    onNavegar: (Seccion) -> Unit,
    onCerrarSesion: () -> Unit,
    vm: BitacoraViewModel = viewModel()
) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    var consulta by rememberSaveable { mutableStateOf("") }
    var accion by rememberSaveable { mutableStateOf("Todas") }
    val eventos = (estado as? EstadoBitacora.Listo)?.eventos.orEmpty()
    val filtrados = eventos.filter { evento ->
        val buscaCoincide = consulta.isBlank() || evento.alumno.nombre.contains(consulta.trim(), true) ||
            evento.alumno.matricula.contains(consulta.trim(), true) || evento.tipo.contains(consulta.trim(), true)
        val accionCoincide = accion == "Todas" || evento.accion == accion
        buscaCoincide && accionCoincide
    }

    SiraScaffold(Seccion.Bitacora, RolDemo.Administrador, onNavegar, onCerrarSesion) {
        Text("BITÁCORA DE INCIDENCIAS · VISTA LOCAL", color = Gris, fontSize = 12.sp, letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 10.dp))
        Mensaje(
            "Eventos disponibles solo durante esta ejecución. El usuario responsable no se identifica y el registro no se conserva al cerrar la app.",
            FondoTarjeta, BordeTarjeta, Gris
        )

        Tarjeta(Modifier.padding(top = 14.dp)) {
            OutlinedTextField(
                value = consulta, onValueChange = { consulta = it },
                label = { Text("Buscar alumno o tipo de incidencia") }, singleLine = true,
                colors = camposColores(), modifier = Modifier.fillMaxWidth()
            )
            Etiqueta("Evento", Modifier.padding(top = 12.dp))
            Selector(accion, listOf("Todas", "Registrada", "Editada", "Eliminada"), { accion = it })
            androidx.compose.material3.TextButton(onClick = { consulta = ""; accion = "Todas" }) {
                Text("Limpiar filtros", color = Azul)
            }
        }

        when (val actual = estado) {
            EstadoBitacora.Cargando -> Row(Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = Azul, modifier = Modifier.size(22.dp))
                Text("Cargando bitácora…", color = Gris, modifier = Modifier.padding(start = 10.dp))
            }
            EstadoBitacora.Error -> Column {
                Mensaje("No se pudo cargar la bitácora.", ErrFondo, ErrBorde, ErrTexto)
                Button(onClick = vm::reintentar, modifier = Modifier.padding(top = 8.dp),
                    shape = RoundedCornerShape(4.dp), colors = ButtonDefaults.buttonColors(containerColor = Azul)) {
                    Text("Reintentar")
                }
            }
            is EstadoBitacora.Listo -> when {
                actual.eventos.isEmpty() -> Mensaje(
                    "Aún no hay registros ni ediciones de incidencias en esta ejecución.", FondoTarjeta, BordeTarjeta, Gris
                )
                filtrados.isEmpty() -> Mensaje("No hay eventos que coincidan con estos filtros.", FondoTarjeta, BordeTarjeta, Gris)
                else -> {
                    Text("${filtrados.size} evento${if (filtrados.size == 1) "" else "s"}", color = Gris,
                        fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
                    filtrados.forEach { evento ->
                        Tarjeta(Modifier.padding(bottom = 10.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(evento.accion, color = Azul, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text("#${evento.incidenciaId}", color = Gris, fontSize = 13.sp)
                            }
                            Text(evento.alumno.nombre, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                            Text("Matrícula ${evento.alumno.matricula}", color = Gris, fontSize = 13.sp)
                            Text("${evento.tipo} · ${evento.gravedad}", fontSize = 14.sp,
                                modifier = Modifier.padding(top = 6.dp))
                            Text(evento.resumen, color = Gris, fontSize = 13.sp,
                                modifier = Modifier.padding(top = 6.dp))
                            Text("Fecha de la incidencia: ${formatearFechaHora(evento.fechaIncidente)}", color = Gris, fontSize = 12.sp,
                                modifier = Modifier.padding(top = 6.dp))
                            Text("Evento registrado: ${formatearFechaHora(evento.registradoEn)}", color = Gris, fontSize = 12.sp)
                            Text("Responsable: ${evento.actor ?: "No identificado en esta sesión"}", color = Gris, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
