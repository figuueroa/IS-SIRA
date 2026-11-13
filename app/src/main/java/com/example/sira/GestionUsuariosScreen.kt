package com.example.sira

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface EstadoGestionUsuarios {
    data object Inactivo : EstadoGestionUsuarios
    data object Guardando : EstadoGestionUsuarios
    data object DatosInvalidos : EstadoGestionUsuarios
    data object CorreoDuplicado : EstadoGestionUsuarios
    data object Error : EstadoGestionUsuarios
    data object Exito : EstadoGestionUsuarios
}

class GestionUsuariosViewModel(
    private val repo: UsuariosRepository = ServiciosUsuarios.repositorio
) : ViewModel() {
    private val _estado = MutableStateFlow<EstadoGestionUsuarios>(EstadoGestionUsuarios.Inactivo)
    val estado = _estado.asStateFlow()
    private val _usuarios = MutableStateFlow<List<UsuarioDemo>>(emptyList())
    val usuarios = _usuarios.asStateFlow()

    init { cargar() }

    private fun cargar() {
        viewModelScope.launch {
            try {
                _usuarios.value = repo.listar()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _estado.value = EstadoGestionUsuarios.Error
            }
        }
    }

    fun registrar(nombre: String, correo: String, rol: RolDemo) {
        if (_estado.value == EstadoGestionUsuarios.Guardando) return
        if (nombre.isBlank() || !correoValido(correo)) {
            _estado.value = EstadoGestionUsuarios.DatosInvalidos
            return
        }
        _estado.value = EstadoGestionUsuarios.Guardando
        viewModelScope.launch {
            _estado.value = try {
                when (repo.registrar(UsuarioDemo(nombre.trim(), correo.trim(), rol))) {
                    ResultadoRegistroUsuario.Exito -> {
                        _usuarios.value = repo.listar()
                        EstadoGestionUsuarios.Exito
                    }
                    ResultadoRegistroUsuario.CorreoDuplicado -> EstadoGestionUsuarios.CorreoDuplicado
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EstadoGestionUsuarios.Error
            }
        }
    }

    fun alEditar() {
        if (_estado.value != EstadoGestionUsuarios.Guardando) _estado.value = EstadoGestionUsuarios.Inactivo
    }
}

@Composable
fun GestionUsuariosScreen(
    onNavegar: (Seccion) -> Unit,
    onCerrarSesion: () -> Unit,
    vm: GestionUsuariosViewModel = viewModel()
) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    val usuarios by vm.usuarios.collectAsStateWithLifecycle()
    var nombre by rememberSaveable { mutableStateOf("") }
    var correo by rememberSaveable { mutableStateOf("") }
    var rolEtiqueta by rememberSaveable { mutableStateOf(RolDemo.Director.etiqueta) }
    val rol = RolDemo.entries.first { it.etiqueta == rolEtiqueta }

    SiraScaffold(Seccion.GestionUsuarios, RolDemo.Administrador, onNavegar, onCerrarSesion) {
        Text("REGISTRO DE USUARIOS · PROTOTIPO", color = Gris, fontSize = 12.sp, letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 10.dp))
        Mensaje(
            if (BuildConfig.USAR_SERVIDOR) "La cuenta se crea en el servidor pendiente de activación; la persona fija su contraseña con el enlace de activación."
            else "Esta pantalla crea perfiles solo en memoria mientras la app está abierta. No genera credenciales, no inicia sesión ni aplica permisos; eso dependerá del backend y sus reglas.",
            FondoTarjeta, BordeTarjeta, Gris
        )

        Tarjeta(Modifier.padding(top = 14.dp)) {
            OutlinedTextField(
                value = nombre, onValueChange = { nombre = it; vm.alEditar() },
                label = { Text("Nombre completo") }, singleLine = true,
                colors = camposColores(), modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = correo, onValueChange = { correo = it; vm.alEditar() },
                label = { Text("Correo institucional") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                colors = camposColores(), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            )
            Etiqueta("Rol de muestra", Modifier.padding(top = 12.dp))
            Selector(rolEtiqueta, RolDemo.entries.map { it.etiqueta }, { rolEtiqueta = it; vm.alEditar() })

            when (estado) {
                EstadoGestionUsuarios.DatosInvalidos -> Mensaje("Escribe el nombre y un correo válido.", ErrFondo, ErrBorde, ErrTexto)
                EstadoGestionUsuarios.CorreoDuplicado -> Mensaje("Ya existe un perfil de muestra con ese correo.", ErrFondo, ErrBorde, ErrTexto)
                EstadoGestionUsuarios.Error -> Mensaje("No se pudo guardar el perfil de muestra.", ErrFondo, ErrBorde, ErrTexto)
                EstadoGestionUsuarios.Exito -> Mensaje("Perfil agregado a la sesión de demostración.", OkFondo, OkBorde, OkTexto)
                else -> Unit
            }

            Button(
                onClick = { vm.registrar(nombre, correo, rol) },
                enabled = estado != EstadoGestionUsuarios.Guardando,
                shape = RoundedCornerShape(4.dp), colors = ButtonDefaults.buttonColors(containerColor = Azul),
                modifier = Modifier.fillMaxWidth().height(50.dp).padding(top = 4.dp)
            ) {
                if (estado == EstadoGestionUsuarios.Guardando) CircularProgressIndicator(
                    modifier = Modifier.size(18.dp), color = Azul
                ) else Text("Agregar perfil", fontWeight = FontWeight.Bold)
            }
        }

        Text("Perfiles de esta sesión", fontSize = 17.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
        if (usuarios.isEmpty()) {
            Mensaje("Aún no hay perfiles de muestra registrados.", FondoTarjeta, BordeTarjeta, Gris)
        } else {
            usuarios.forEach { usuario ->
                Tarjeta(Modifier.padding(bottom = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(usuario.nombre, color = Azul, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(usuario.correo, color = Gris, fontSize = 14.sp)
                        }
                        Text(usuario.rol.etiqueta, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
