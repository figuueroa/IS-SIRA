package com.example.sira

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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
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

/** I-13 · Central de roles y permisos (solo administrador). */
sealed interface EstadoRoles {
    data object Inactivo : EstadoRoles
    data object Guardando : EstadoRoles
    data object SinCambios : EstadoRoles
    data object Error : EstadoRoles
    data object Exito : EstadoRoles
}

class RolesPermisosViewModel(
    private val repo: UsuariosRepository = ServiciosUsuarios.repositorio
) : ViewModel() {
    private val _estado = MutableStateFlow<EstadoRoles>(EstadoRoles.Inactivo)
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
                _estado.value = EstadoRoles.Error
            }
        }
    }

    /** `cambios` = correo → rol elegido. Solo se guardan los que son distintos al rol actual. */
    fun guardar(cambios: Map<String, RolDemo>) {
        if (_estado.value == EstadoRoles.Guardando) return
        val pendientes = _usuarios.value.filter { u -> cambios[u.correo]?.let { it != u.rol } == true }
        if (pendientes.isEmpty()) {
            _estado.value = EstadoRoles.SinCambios
            return
        }
        _estado.value = EstadoRoles.Guardando
        viewModelScope.launch {
            _estado.value = try {
                val resultados = pendientes.map { repo.cambiarRol(it.correo, cambios.getValue(it.correo)) }
                _usuarios.value = repo.listar()
                if (resultados.all { it }) EstadoRoles.Exito else EstadoRoles.Error
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EstadoRoles.Error
            }
        }
    }

    fun alEditar() {
        if (_estado.value != EstadoRoles.Guardando) _estado.value = EstadoRoles.Inactivo
    }
}

@Composable
fun RolesPermisosScreen(
    onNavegar: (Seccion) -> Unit,
    onCerrarSesion: () -> Unit,
    vm: RolesPermisosViewModel = viewModel()
) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    val usuarios by vm.usuarios.collectAsStateWithLifecycle()
    val elegidos = remember { mutableStateMapOf<String, String>() }   // correo → etiqueta del rol elegido

    SiraScaffold(Seccion.RolesPermisos, RolDemo.Administrador, onNavegar, onCerrarSesion) {
        Text("CENTRAL DE ROLES Y PERMISOS · I-13", color = Gris, fontSize = 12.sp, letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 10.dp))
        Mensaje(
            "Prototipo: los cambios de rol se guardan solo en memoria mientras la app está abierta. " +
                    "Los permisos reales dependerán del backend.",
            FondoTarjeta, BordeTarjeta, Gris
        )

        if (usuarios.isEmpty()) {
            Mensaje("Aún no hay perfiles registrados. Créalos primero en «Registro de usuarios».",
                FondoTarjeta, BordeTarjeta, Gris)
        } else {
            usuarios.forEach { u ->
                Tarjeta(Modifier.padding(top = 10.dp)) {
                    Text(u.nombre, color = Azul, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(u.correo, color = Gris, fontSize = 14.sp)
                    Text("Rol actual: ${u.rol.etiqueta}", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    Etiqueta("Cambiar a", Modifier.padding(top = 10.dp))
                    Selector(
                        valor = elegidos[u.correo] ?: u.rol.etiqueta,
                        opciones = RolDemo.entries.map { it.etiqueta },
                        onElegir = { elegidos[u.correo] = it; vm.alEditar() }
                    )
                }
            }

            when (estado) {
                EstadoRoles.Exito -> Mensaje("Cambios de rol aplicados de forma inmediata.", OkFondo, OkBorde, OkTexto)
                EstadoRoles.SinCambios -> Mensaje("No hay cambios por guardar.", FondoTarjeta, BordeTarjeta, Gris)
                EstadoRoles.Error -> Mensaje("No se pudieron aplicar los cambios de rol.", ErrFondo, ErrBorde, ErrTexto)
                else -> Unit
            }

            Button(
                onClick = {
                    vm.guardar(elegidos.toMap().mapValues { (_, etiqueta) -> RolDemo.entries.first { it.etiqueta == etiqueta } })
                },
                enabled = estado != EstadoRoles.Guardando,
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Azul, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(50.dp).padding(top = 4.dp)
            ) {
                if (estado == EstadoRoles.Guardando) CircularProgressIndicator(
                    modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp
                ) else Text("Guardar cambios", fontWeight = FontWeight.Bold)
            }
        }

        Text("RF-16 · Central de roles y permisos", color = ErrTexto, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
    }
}