package com.example.sira

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/* ---------------- Autenticación ----------------
 * Hoy: repositorio de prueba. Cuando exista la base de datos, crea otra clase que
 * implemente AuthRepository (Retrofit al servidor, Room, etc.) y pásala al ViewModel.
 * La contraseña debe compararse cifrada en el servidor, nunca en la app. */
interface AuthRepository {
    suspend fun iniciarSesion(correo: String, clave: String): Boolean
}

class FakeAuthRepository : AuthRepository {
    override suspend fun iniciarSesion(correo: String, clave: String): Boolean {
        delay(600) // simula la red
        return correo.equals("admin@institucion.edu.mx", ignoreCase = true) && clave == "Sira1234"
    }
}

/* ---------------- Estado ---------------- */
sealed interface EstadoLogin {
    data object Inactivo : EstadoLogin
    data object Cargando : EstadoLogin
    data object Exito : EstadoLogin
    data object Error : EstadoLogin
}

class LoginViewModel(
    private val repo: AuthRepository = FakeAuthRepository()
) : ViewModel() {
    private val _estado = MutableStateFlow<EstadoLogin>(EstadoLogin.Inactivo)
    val estado: StateFlow<EstadoLogin> = _estado.asStateFlow()

    fun ingresar(correo: String, clave: String) {
        if (correo.isBlank() || clave.isEmpty()) { _estado.value = EstadoLogin.Error; return }
        viewModelScope.launch {
            _estado.value = EstadoLogin.Cargando
            val ok = try { repo.iniciarSesion(correo.trim(), clave) } catch (e: Exception) { false }
            // La pantalla se queda aquí. Más adelante: navegar al panel según el rol.
            _estado.value = if (ok) EstadoLogin.Exito else EstadoLogin.Error
        }
    }

    fun reiniciar() { _estado.value = EstadoLogin.Inactivo }
}

/* ---------------- Pantalla ---------------- */
@Composable
fun LoginScreen(vm: LoginViewModel = viewModel(), onExito: () -> Unit = {}) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    LaunchedEffect(estado) {
        if (estado == EstadoLogin.Exito) {
            delay(1200)        // deja ver el mensaje verde
            vm.reiniciar()
            onExito()
        }
    }
    var correo by remember { mutableStateOf("") }
    var clave by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding()) {
        // Barra superior: solo el logo editable (sin texto a la derecha)
        Box(
            Modifier.fillMaxWidth().height(46.dp).background(Azul).padding(horizontal = 20.dp),
            contentAlignment = Alignment.CenterStart
        ) { LogoSira() }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 48.dp)
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .background(FondoTarjeta, RoundedCornerShape(6.dp))
                    .border(1.dp, BordeTarjeta, RoundedCornerShape(6.dp))
                    .padding(horizontal = 18.dp, vertical = 18.dp)
            ) {
                Text("Iniciar sesión", color = Azul, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text("Sistema de Incidencias y Respuestas Auxiliares", color = Gris, fontSize = 12.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 12.dp))

                Etiqueta("Correo electrónico")
                OutlinedTextField(
                    value = correo, onValueChange = { correo = it },
                    placeholder = { Text("nombre@institucion.edu.mx") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    colors = camposColores()
                )

                Etiqueta("Contraseña", Modifier.padding(top = 12.dp))
                OutlinedTextField(
                    value = clave, onValueChange = { clave = it },
                    placeholder = { Text("••••••••") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = camposColores()
                )

                Text("¿Olvidó su contraseña?", color = Azul, fontSize = 12.sp, textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp)
                        .clickable { /* pendiente: pantalla I-02 Recuperar contraseña */ })

                Button(
                    onClick = { vm.ingresar(correo, clave) },
                    enabled = estado != EstadoLogin.Cargando && estado != EstadoLogin.Exito,
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Azul, disabledContainerColor = AzulClaro,
                        contentColor = Color.White, disabledContentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("Ingresar", fontWeight = FontWeight.Bold) }

                when (estado) {
                    EstadoLogin.Exito -> Mensaje("Inicio de sesión exitoso. Redirigiendo a su panel…", OkFondo, OkBorde, OkTexto)
                    EstadoLogin.Error -> Mensaje("Correo o contraseña incorrectos.", ErrFondo, ErrBorde, ErrTexto)
                    else -> {}
                }
            }
        }

        Text("RF-01 / RF-21 · Inicio de sesión", color = ErrTexto, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(end = 16.dp, bottom = 10.dp))
    }
}