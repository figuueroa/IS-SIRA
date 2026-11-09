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

/* ---------------- Colores y medidas (tomados del documento) ---------------- */
private val Azul = Color(0xFF1F5B73)
private val AzulClaro = Color(0xFF7096A8)
private val FondoTarjeta = Color(0xFFF8F9FB)
private val BordeTarjeta = Color(0xFFD5DCE1)
private val Gris = Color(0xFF6B7780)
private val OkFondo = Color(0xFFE8F5EC);  private val OkBorde = Color(0xFF8CC7A0);  private val OkTexto = Color(0xFF14612E)
private val ErrFondo = Color(0xFFFDEAEA); private val ErrBorde = Color(0xFFD9534F); private val ErrTexto = Color(0xFFB02A2A)

/** Logo: misma altura que el texto "SIRA" y la mitad de su ancho. Cambia aquí el tamaño. */
private val LogoAlto = 16.dp
private val LogoAncho = 23.dp

/** LOGO: para cambiarlo, copia tu imagen a app/src/main/res/drawable/ (ej. logo_sira.png)
 *  y cambia aquí el nombre: R.drawable.logo_sira. Nombres en minúsculas, sin espacios. */
private val LogoImagen = R.drawable.ic_launcher_foreground

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
}

/* ---------------- Pantalla ---------------- */
@Composable
fun LoginScreen(vm: LoginViewModel = viewModel()) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    var correo by remember { mutableStateOf("") }
    var clave by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding()) {
        // Barra superior: solo el logo editable (sin texto a la derecha)
        Box(
            Modifier.fillMaxWidth().height(46.dp).background(Azul).padding(horizontal = 20.dp),
            contentAlignment = Alignment.CenterStart
        ) { LogoEditable() }

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

@Composable private fun Etiqueta(texto: String, modifier: Modifier = Modifier) =
    Text(texto, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = modifier.padding(bottom = 4.dp))

@Composable private fun camposColores() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
    focusedBorderColor = Azul, unfocusedBorderColor = Color(0xFF9AA7B0)
)

@Composable private fun Mensaje(texto: String, fondo: Color, borde: Color, color: Color) =
    Text(texto, color = color, fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
            .background(fondo, RoundedCornerShape(4.dp))
            .border(1.dp, borde, RoundedCornerShape(4.dp))
            .padding(14.dp))

/* ---------------- Logo ---------------- */
@Composable
private fun LogoEditable() {
    Image(
        painter = painterResource(LogoImagen),
        contentDescription = "Logo",
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(LogoAncho, LogoAlto)
    )
}