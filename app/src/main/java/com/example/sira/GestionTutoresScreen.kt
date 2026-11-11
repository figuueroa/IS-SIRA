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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface EstadoRegistroTutor {
    data object Inactivo : EstadoRegistroTutor
    data object Guardando : EstadoRegistroTutor
    data object DatosInvalidos : EstadoRegistroTutor
    data object Duplicado : EstadoRegistroTutor
    data object CorreoEnUso : EstadoRegistroTutor
    data object Error : EstadoRegistroTutor
    data object Exito : EstadoRegistroTutor
}

class GestionTutoresViewModel(
    private val repo: TutoresRepository = ServiciosTutores.repositorio
) : ViewModel() {
    private val _estado = MutableStateFlow<EstadoRegistroTutor>(EstadoRegistroTutor.Inactivo)
    val estado = _estado.asStateFlow()
    private val _vinculados = MutableStateFlow<List<TutorVinculado>>(emptyList())
    val vinculados = _vinculados.asStateFlow()
    private val _cargandoLista = MutableStateFlow(true)
    val cargandoLista = _cargandoLista.asStateFlow()
    private val _errorCarga = MutableStateFlow(false)
    val errorCarga = _errorCarga.asStateFlow()

    init {
        viewModelScope.launch { cargarVinculos() }
    }

    private suspend fun cargarVinculos() {
        _cargandoLista.value = true
        try {
            _vinculados.value = repo.listar()
            _cargandoLista.value = false
            _errorCarga.value = false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _cargandoLista.value = false
            _errorCarga.value = true
            _estado.value = EstadoRegistroTutor.Error
        }
    }

    fun reintentarCarga() { viewModelScope.launch { cargarVinculos() } }

    fun registrar(nombre: String, correo: String, telefono: String, alumno: Alumno?) {
        if (_estado.value == EstadoRegistroTutor.Guardando) return
        if (nombre.isBlank() || !correoValido(correo) || !telefonoEmergenciaValido(telefono) || alumno == null) {
            _estado.value = EstadoRegistroTutor.DatosInvalidos
            return
        }
        _estado.value = EstadoRegistroTutor.Guardando
        viewModelScope.launch {
            _estado.value = try {
                when (repo.registrar(TutorVinculado(nombre.trim(), correo.trim(), telefono.trim(), alumno))) {
                    ResultadoVinculoTutor.Exito -> {
                        _vinculados.value = repo.listar()
                        EstadoRegistroTutor.Exito
                    }
                    ResultadoVinculoTutor.VinculoDuplicado -> EstadoRegistroTutor.Duplicado
                    ResultadoVinculoTutor.CorreoEnUso -> EstadoRegistroTutor.CorreoEnUso
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                EstadoRegistroTutor.Error
            }
        }
    }

    fun alEditar() {
        if (_estado.value != EstadoRegistroTutor.Guardando) _estado.value = EstadoRegistroTutor.Inactivo
    }
}

@Composable
fun GestionTutoresScreen(
    onNavegar: (Seccion) -> Unit,
    onCerrarSesion: () -> Unit,
    vm: GestionTutoresViewModel = viewModel()
) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    val vinculados by vm.vinculados.collectAsStateWithLifecycle()
    val cargandoLista by vm.cargandoLista.collectAsStateWithLifecycle()
    val errorCarga by vm.errorCarga.collectAsStateWithLifecycle()
    val alumnos = alumnosPrueba
    val opcionesAlumno = alumnos.map { "${it.matricula} · ${it.nombre}" }
    var alumnoOpcion by rememberSaveable { mutableStateOf(opcionesAlumno.firstOrNull().orEmpty()) }
    var nombre by rememberSaveable { mutableStateOf("") }
    var correo by rememberSaveable { mutableStateOf("") }
    var telefono by rememberSaveable { mutableStateOf("") }
    val alumnoSeleccionado = alumnos.firstOrNull { "${it.matricula} · ${it.nombre}" == alumnoOpcion }

    SiraScaffold(Seccion.GestionTutores, RolDemo.Administrador, onNavegar, onCerrarSesion) {
        Text("REGISTRO DE TUTORES · DEMOSTRACIÓN", color = Gris, fontSize = 12.sp, letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 10.dp))

        Mensaje(
            "Los datos solo viven en memoria durante esta ejecución; se pierden al cerrar la app. No crea una cuenta, no inicia sesión, no envía notificaciones ni sincroniza con el servidor.",
            FondoTarjeta, BordeTarjeta, Gris
        )

        Tarjeta(Modifier.padding(top = 14.dp)) {
            Etiqueta("Alumno")
            Selector(alumnoOpcion, opcionesAlumno, { alumnoOpcion = it; vm.alEditar() })
            OutlinedTextField(
                value = nombre, onValueChange = { nombre = it; vm.alEditar() },
                label = { Text("Nombre de madre, padre o tutor legal") }, singleLine = true,
                colors = camposColores(), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            )
            OutlinedTextField(
                value = correo, onValueChange = { correo = it; vm.alEditar() },
                label = { Text("Correo electrónico") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                colors = camposColores(), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            )
            OutlinedTextField(
                value = telefono,
                onValueChange = { telefono = it.filter { c -> c.isDigit() || c in "+ ()-" }.take(20); vm.alEditar() },
                label = { Text("Teléfono") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                colors = camposColores(), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            )

            when (estado) {
                EstadoRegistroTutor.DatosInvalidos -> Mensaje(
                    "Verifica el nombre, el correo, el teléfono (10–15 dígitos) y el alumno seleccionado.", ErrFondo, ErrBorde, ErrTexto
                )
                EstadoRegistroTutor.Duplicado -> Mensaje(
                    "Ese correo ya está vinculado con este alumno.", ErrFondo, ErrBorde, ErrTexto
                )
                EstadoRegistroTutor.CorreoEnUso -> Mensaje(
                    "Ese correo ya está asociado con otros datos de tutor. Revisa la información ingresada.", ErrFondo, ErrBorde, ErrTexto
                )
                EstadoRegistroTutor.Error -> Mensaje(
                    "No se pudo guardar o cargar la vinculación. Intenta de nuevo.", ErrFondo, ErrBorde, ErrTexto
                )
                EstadoRegistroTutor.Exito -> Mensaje(
                    "Tutor vinculado en la sesión de demostración.", OkFondo, OkBorde, OkTexto
                )
                else -> Unit
            }

            Button(
                onClick = { vm.registrar(nombre, correo, telefono, alumnoSeleccionado) },
                enabled = estado != EstadoRegistroTutor.Guardando,
                shape = RoundedCornerShape(4.dp), colors = ButtonDefaults.buttonColors(containerColor = Azul),
                modifier = Modifier.fillMaxWidth().height(50.dp).padding(top = 4.dp)
            ) {
                if (estado == EstadoRegistroTutor.Guardando) CircularProgressIndicator(
                    modifier = Modifier.size(18.dp), color = Azul
                ) else Text("Vincular tutor con alumno", fontWeight = FontWeight.Bold)
            }
        }

        Text("Tutores vinculados en esta sesión", fontSize = 17.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
        if (cargandoLista) {
            Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = Azul, modifier = Modifier.size(22.dp))
                Text("Cargando vinculaciones…", color = Gris, modifier = Modifier.padding(start = 10.dp))
            }
        } else if (errorCarga) {
            Column {
                Mensaje("No se pudieron cargar las vinculaciones.", ErrFondo, ErrBorde, ErrTexto)
                androidx.compose.material3.TextButton(onClick = vm::reintentarCarga) { Text("Reintentar", color = Azul) }
            }
        } else if (vinculados.isEmpty()) {
            Mensaje("Aún no hay tutores vinculados.", FondoTarjeta, BordeTarjeta, Gris)
        } else {
            vinculados.forEach { tutor ->
                Tarjeta(Modifier.padding(bottom = 10.dp)) {
                    Text(tutor.nombre, color = Azul, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(tutor.correo, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                    Text(tutor.telefono, color = Gris, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Alumno:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("${tutor.alumno.nombre} · ${tutor.alumno.matricula}", color = Gris, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
