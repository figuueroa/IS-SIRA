package com.example.sira

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Date

/* ---------------- I-04 · Registro de ingresos médicos ----------------
 * Cada visita de un alumno a enfermería: motivo de consulta y tratamiento aplicado.
 * Hoy: en memoria (se pierde al cerrar la app). Después: servidor/base de datos. */
data class IngresoMedico(
    val id: Int,
    val matricula: String,
    val fecha: Date,
    val motivo: String,
    val tratamiento: String
)

interface IngresosMedicosRepository {
    /** Del más reciente al más antiguo. */
    suspend fun listar(matricula: String): List<IngresoMedico>
    suspend fun registrar(matricula: String, motivo: String, tratamiento: String): IngresoMedico
}

object ServiciosIngresos {
    val repositorio: IngresosMedicosRepository = FakeIngresosMedicosRepository()
}

private class FakeIngresosMedicosRepository : IngresosMedicosRepository {
    private val mutex = Mutex()
    private val ingresos = mutableListOf<IngresoMedico>()
    private var siguienteId = 1

    override suspend fun listar(matricula: String): List<IngresoMedico> = mutex.withLock {
        ingresos.filter { it.matricula == matricula }.sortedByDescending { it.id }
    }

    override suspend fun registrar(matricula: String, motivo: String, tratamiento: String): IngresoMedico = mutex.withLock {
        IngresoMedico(siguienteId++, matricula, Date(), motivo, tratamiento).also { ingresos += it }
    }
}

sealed interface EstadoIngreso {
    data object Inactivo : EstadoIngreso
    data object Guardando : EstadoIngreso
    data object DatosInvalidos : EstadoIngreso
    data object Error : EstadoIngreso
    data object Exito : EstadoIngreso
}

class IngresosMedicosViewModel(
    private val repo: IngresosMedicosRepository = ServiciosIngresos.repositorio
) : ViewModel() {
    private val _ingresos = MutableStateFlow<List<IngresoMedico>>(emptyList())
    val ingresos = _ingresos.asStateFlow()
    private val _estado = MutableStateFlow<EstadoIngreso>(EstadoIngreso.Inactivo)
    val estado = _estado.asStateFlow()

    fun cargar(matricula: String) {
        _ingresos.value = emptyList()
        _estado.value = EstadoIngreso.Inactivo
        viewModelScope.launch {
            try {
                _ingresos.value = repo.listar(matricula)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _estado.value = EstadoIngreso.Error
            }
        }
    }

    fun registrar(matricula: String, motivo: String, tratamiento: String) {
        if (_estado.value == EstadoIngreso.Guardando) return
        if (motivo.isBlank() || tratamiento.isBlank()) {
            _estado.value = EstadoIngreso.DatosInvalidos
            return
        }
        _estado.value = EstadoIngreso.Guardando
        viewModelScope.launch {
            _estado.value = try {
                repo.registrar(matricula, motivo.trim(), tratamiento.trim())
                _ingresos.value = repo.listar(matricula)
                EstadoIngreso.Exito
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EstadoIngreso.Error
            }
        }
    }

    fun alEditar() {
        if (_estado.value != EstadoIngreso.Guardando) _estado.value = EstadoIngreso.Inactivo
    }
}

/** Sección que se muestra debajo del expediente del alumno encontrado (en Historial médico). */
@Composable
fun IngresosMedicos(alumno: Alumno, vm: IngresosMedicosViewModel = viewModel()) {
    val ingresos by vm.ingresos.collectAsStateWithLifecycle()
    val estado by vm.estado.collectAsStateWithLifecycle()
    // Los campos se reinician al cambiar de alumno.
    var motivo by rememberSaveable(alumno.matricula) { mutableStateOf("") }
    var tratamiento by rememberSaveable(alumno.matricula) { mutableStateOf("") }
    var verHistorial by rememberSaveable(alumno.matricula) { mutableStateOf(false) }

    LaunchedEffect(alumno.matricula) { vm.cargar(alumno.matricula) }
    LaunchedEffect(estado) {
        if (estado == EstadoIngreso.Exito) {
            motivo = ""
            tratamiento = ""
            verHistorial = true
        }
    }

    Text("Ingresos a enfermería", fontSize = 17.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
    Tarjeta {
        Text("Registrar nuevo ingreso médico", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        OutlinedTextField(
            value = motivo, onValueChange = { motivo = it; vm.alEditar() },
            label = { Text("Motivo de consulta") }, minLines = 2,
            colors = camposColores(), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
        )
        OutlinedTextField(
            value = tratamiento, onValueChange = { tratamiento = it; vm.alEditar() },
            label = { Text("Tratamiento aplicado") }, minLines = 2,
            colors = camposColores(), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
        )

        when (estado) {
            EstadoIngreso.DatosInvalidos -> Mensaje("Escribe el motivo de consulta y el tratamiento aplicado.", ErrFondo, ErrBorde, ErrTexto)
            EstadoIngreso.Error -> Mensaje("No se pudo registrar el ingreso. Intenta de nuevo.", ErrFondo, ErrBorde, ErrTexto)
            EstadoIngreso.Exito -> Mensaje("Ingreso médico registrado correctamente.", OkFondo, OkBorde, OkTexto)
            else -> Unit
        }

        Button(
            onClick = { vm.registrar(alumno.matricula, motivo, tratamiento) },
            enabled = estado != EstadoIngreso.Guardando,
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Azul, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(50.dp).padding(top = 4.dp)
        ) {
            if (estado == EstadoIngreso.Guardando) CircularProgressIndicator(
                modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp
            ) else Text("Registrar ingreso médico", fontWeight = FontWeight.Bold)
        }

        TextButton(onClick = { verHistorial = !verHistorial }) {
            Text(if (verHistorial) "Ocultar historial" else "Ver historial completo (${ingresos.size})", color = Azul)
        }

        if (verHistorial) {
            if (ingresos.isEmpty()) {
                Text("Este alumno aún no tiene ingresos registrados.", color = Gris, fontSize = 13.sp)
            }
            ingresos.forEach { ingreso ->
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(BordeTarjeta))
                    Text(formatearFechaHora(ingreso.fecha), color = Gris, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp))
                    Text(ingreso.motivo, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        modifier = Modifier.padding(top = 2.dp))
                    Text("Tratamiento: ${ingreso.tratamiento}", fontSize = 13.sp,
                        modifier = Modifier.padding(top = 2.dp))
                }
            }
        }

        Text("Prototipo: los ingresos se guardan solo en memoria mientras la app está abierta.",
            color = Gris, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
    }
}