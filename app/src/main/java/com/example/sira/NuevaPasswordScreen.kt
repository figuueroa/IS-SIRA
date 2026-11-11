package com.example.sira

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface EstadoNuevaPassword {
    data object Inactivo : EstadoNuevaPassword
    data object PoliticaInvalida : EstadoNuevaPassword
    data object NoCoincide : EstadoNuevaPassword
    data object ValidadaEnDemo : EstadoNuevaPassword
}

class NuevaPasswordViewModel : ViewModel() {
    private val _estado = MutableStateFlow<EstadoNuevaPassword>(EstadoNuevaPassword.Inactivo)
    val estado = _estado.asStateFlow()

    fun validar(password: String, confirmacion: String) {
        _estado.value = when {
            !cumplePoliticaDemo(password) -> EstadoNuevaPassword.PoliticaInvalida
            password != confirmacion -> EstadoNuevaPassword.NoCoincide
            else -> EstadoNuevaPassword.ValidadaEnDemo
        }
    }

    fun alEditar() { _estado.value = EstadoNuevaPassword.Inactivo }
}

private fun cumplePoliticaDemo(password: String): Boolean =
    password.length >= 8 && password.any(Char::isUpperCase) && password.any(Char::isLowerCase) &&
        password.any(Char::isDigit) && password.any { !it.isLetterOrDigit() && !it.isWhitespace() }

@Composable
fun NuevaPasswordScreen(
    onVolver: () -> Unit,
    vm: NuevaPasswordViewModel = viewModel()
) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    var password by rememberSaveable { mutableStateOf("") }
    var confirmacion by rememberSaveable { mutableStateOf("") }
    var mostrarPassword by rememberSaveable { mutableStateOf(false) }
    var mostrarConfirmacion by rememberSaveable { mutableStateOf(false) }
    BackHandler { onVolver() }

    Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding().imePadding()) {
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxWidth().height(46.dp).background(Azul).padding(horizontal = 20.dp),
            contentAlignment = androidx.compose.ui.Alignment.CenterStart
        ) { LogoSira() }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 40.dp)
        ) {
            Tarjeta {
                Text("Crear nueva contraseña", color = Azul, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text("Elija una contraseña nueva y confírmela para continuar.", color = Gris, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))

                Mensaje(
                    "Vista de demostración: el enlace temporal y la actualización real requieren el servicio de autenticación. Esta validación no cambia ninguna cuenta.",
                    FondoTarjeta, BordeTarjeta, Gris
                )

                Etiqueta("Nueva contraseña", Modifier.padding(top = 14.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; vm.alEditar() },
                    singleLine = true,
                    visualTransformation = if (mostrarPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { mostrarPassword = !mostrarPassword }) {
                            Text(if (mostrarPassword) "Ocultar" else "Mostrar", color = Azul)
                        }
                    },
                    isError = estado == EstadoNuevaPassword.PoliticaInvalida,
                    modifier = Modifier.fillMaxWidth(),
                    colors = camposColores()
                )

                Text("Política provisional para esta demo:", color = Gris, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                RequisitoPassword("8 caracteres o más", password.length >= 8)
                RequisitoPassword("Una mayúscula y una minúscula", password.any(Char::isUpperCase) && password.any(Char::isLowerCase))
                RequisitoPassword("Un número", password.any(Char::isDigit))
                RequisitoPassword("Un símbolo", password.any { !it.isLetterOrDigit() && !it.isWhitespace() })

                Etiqueta("Confirmar contraseña", Modifier.padding(top = 14.dp))
                OutlinedTextField(
                    value = confirmacion,
                    onValueChange = { confirmacion = it; vm.alEditar() },
                    singleLine = true,
                    visualTransformation = if (mostrarConfirmacion) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { mostrarConfirmacion = !mostrarConfirmacion }) {
                            Text(if (mostrarConfirmacion) "Ocultar" else "Mostrar", color = Azul)
                        }
                    },
                    isError = estado == EstadoNuevaPassword.NoCoincide,
                    modifier = Modifier.fillMaxWidth(),
                    colors = camposColores()
                )

                when (estado) {
                    EstadoNuevaPassword.PoliticaInvalida -> Mensaje("La contraseña no cumple todos los requisitos.", ErrFondo, ErrBorde, ErrTexto)
                    EstadoNuevaPassword.NoCoincide -> Mensaje("Las contraseñas no coinciden.", ErrFondo, ErrBorde, ErrTexto)
                    EstadoNuevaPassword.ValidadaEnDemo -> Mensaje(
                        "La contraseña cumple la validación local, pero no se guardó. El servicio todavía no está conectado.",
                        OkFondo, OkBorde, OkTexto
                    )
                    EstadoNuevaPassword.Inactivo -> Unit
                }

                Button(
                    onClick = { vm.validar(password, confirmacion) },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Azul),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(48.dp)
                ) { Text("Validar contraseña", fontWeight = FontWeight.Bold) }
                TextButton(onClick = onVolver, modifier = Modifier.fillMaxWidth()) {
                    Text("Volver a recuperación", color = Azul)
                }
            }
        }
    }
}

@Composable
private fun RequisitoPassword(texto: String, cumplido: Boolean) {
    Text(
        "${if (cumplido) "Cumple" else "Pendiente"} · $texto",
        color = if (cumplido) OkTexto else Gris,
        fontSize = 13.sp,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}
