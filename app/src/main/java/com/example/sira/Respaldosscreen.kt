package com.example.sira

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

/** I-14 · Respaldo de información (solo administrador). */
sealed interface EstadoRespaldo {
    data object Inactivo : EstadoRespaldo
    data object Generando : EstadoRespaldo
    data object Error : EstadoRespaldo
    data object Exito : EstadoRespaldo
}

class RespaldosViewModel(
    private val repo: RespaldosRepository = ServiciosRespaldos.repositorio
) : ViewModel() {
    private val _estado = MutableStateFlow<EstadoRespaldo>(EstadoRespaldo.Inactivo)
    val estado = _estado.asStateFlow()
    private val _respaldos = MutableStateFlow<List<Respaldo>>(emptyList())
    val respaldos = _respaldos.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                _respaldos.value = repo.listar()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _estado.value = EstadoRespaldo.Error
            }
        }
    }

    fun generar() {
        if (_estado.value == EstadoRespaldo.Generando) return
        _estado.value = EstadoRespaldo.Generando
        viewModelScope.launch {
            _estado.value = try {
                repo.generar()
                _respaldos.value = repo.listar()
                EstadoRespaldo.Exito
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EstadoRespaldo.Error
            }
        }
    }
}

@Composable
fun RespaldosScreen(
    onNavegar: (Seccion) -> Unit,
    onCerrarSesion: () -> Unit,
    vm: RespaldosViewModel = viewModel()
) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    val respaldos by vm.respaldos.collectAsStateWithLifecycle()
    val ultimo = respaldos.firstOrNull { it.correcto }

    SiraScaffold(Seccion.Respaldos, RolDemo.Administrador, onNavegar, onCerrarSesion) {
        Text("RESPALDO DE INFORMACIÓN · I-14", color = Gris, fontSize = 12.sp, letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 10.dp))
        Mensaje(
            if (BuildConfig.USAR_SERVIDOR) "El servidor ejecuta BACKUP DATABASE y guarda el .bak en la carpeta configurada (BACKUP_DIR)."
            else "Prototipo: por ahora solo se registra el respaldo en memoria; todavía no se crea un archivo real.",
            FondoTarjeta, BordeTarjeta, Gris
        )

        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tarjeta(Modifier.weight(1f)) {
                Text("02:00 AM", color = Azul, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text("Respaldo automático diario", color = Gris, fontSize = 11.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            Tarjeta(Modifier.weight(1f)) {
                Text(ultimo?.let { formatearFecha(it.fecha) } ?: "—", color = Azul, fontSize = 22.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text("Último respaldo exitoso", color = Gris, fontSize = 11.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }

        Text("Respaldos", fontSize = 17.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        if (respaldos.isEmpty()) {
            Mensaje("Todavía no hay respaldos generados.", FondoTarjeta, BordeTarjeta, Gris)
        } else {
            respaldos.forEach { r ->
                Tarjeta(Modifier.padding(bottom = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.archivo, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(formatearFechaHora(r.fecha), color = Gris, fontSize = 13.sp)
                        }
                        Text(if (r.correcto) "Correcto" else "Error", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = if (r.correcto) OkTexto else ErrTexto)
                    }
                }
            }
        }

        when (estado) {
            EstadoRespaldo.Exito -> Mensaje("Respaldo generado correctamente.", OkFondo, OkBorde, OkTexto)
            EstadoRespaldo.Error -> Mensaje("No se pudo generar el respaldo.", ErrFondo, ErrBorde, ErrTexto)
            else -> Unit
        }

        Button(
            onClick = { vm.generar() },
            enabled = estado != EstadoRespaldo.Generando,
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Azul, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(50.dp).padding(top = 4.dp)
        ) {
            if (estado == EstadoRespaldo.Generando) CircularProgressIndicator(
                modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp
            ) else Text("Generar respaldo ahora", fontWeight = FontWeight.Bold)
        }

        Text("RF-18 · Respaldo de información", color = ErrTexto, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
    }
}