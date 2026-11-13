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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/* ---------------- Autenticación ----------------
 * Con USAR_SERVIDOR: ApiAuthRepository (Api.kt); el servidor compara el hash bcrypt y emite la sesión.
 * Sin servidor: FakeAuthRepository con las cuentas demo de BuildConfig (solo debug). */
object ServiciosAuth {
    val repositorio: AuthRepository =
        if (BuildConfig.USAR_SERVIDOR) ApiAuthRepository() else FakeAuthRepository()
}

interface AuthRepository {
    suspend fun iniciarSesion(correo: String, clave: String): ResultadoAutenticacion

    /** Pide al servidor que envíe el enlace de restablecimiento (RF-21 / HU-22).
     *  Debe responder true exista o no el correo (así no se pueden descubrir cuentas).
     *  Devuelve false o lanza excepción solo si no se pudo contactar al servidor. */
    suspend fun solicitarRecuperacion(correo: String): ResultadoSolicitudRecuperacion
}

sealed interface ResultadoAutenticacion {
    data class Exito(val rol: RolDemo, val correo: String) : ResultadoAutenticacion
    data object CredencialesInvalidas : ResultadoAutenticacion
    data object CuentaBloqueada : ResultadoAutenticacion
}

sealed interface ResultadoSolicitudRecuperacion {
    /** El backend aceptó la solicitud y enviará el enlace sin revelar si existe la cuenta. */
    data object AceptadaPorServidor : ResultadoSolicitudRecuperacion
    /** Resultado exclusivo del repositorio local: no se envió ningún correo. */
    data object DemostracionLocal : ResultadoSolicitudRecuperacion
}

class FakeAuthRepository : AuthRepository {
    // Solo demostración local. En producción, el backend debe aplicar y persistir
    // el bloqueo, validar el hash de la contraseña y emitir el token de sesión.
    private val lock = Any()
    private val intentosFallidos = mutableMapOf<String, Int>()
    private val cuentasBloqueadas = mutableSetOf<String>()

    override suspend fun iniciarSesion(correo: String, clave: String): ResultadoAutenticacion {
        delay(300) // simula la red
        val cuenta = correo.trim().lowercase()
        return synchronized(lock) {
            if (cuentasBloqueadas.contains(cuenta)) return@synchronized ResultadoAutenticacion.CuentaBloqueada

            val rolDemo = if (BuildConfig.DEBUG && clave == BuildConfig.DEMO_LOGIN_PASSWORD) {
                when {
                    correo.equals(BuildConfig.DEMO_LOGIN_EMAIL, ignoreCase = true) -> RolDemo.Director
                    correo.equals(BuildConfig.DEMO_ADMIN_EMAIL, ignoreCase = true) -> RolDemo.Administrador
                    correo.equals(BuildConfig.DEMO_NURSE_EMAIL, ignoreCase = true) -> RolDemo.Enfermeria
                    correo.equals(BuildConfig.DEMO_TUTOR_EMAIL, ignoreCase = true) -> RolDemo.Tutor
                    else -> null
                }
            } else null
            if (rolDemo != null) {
                intentosFallidos.remove(cuenta)
                return@synchronized ResultadoAutenticacion.Exito(rolDemo, cuenta)
            }

            val intentos = (intentosFallidos[cuenta] ?: 0) + 1
            intentosFallidos[cuenta] = intentos
            if (intentos >= 3) {
                cuentasBloqueadas.add(cuenta)
                return@synchronized ResultadoAutenticacion.CuentaBloqueada
            }
            ResultadoAutenticacion.CredencialesInvalidas
        }
    }

    override suspend fun solicitarRecuperacion(correo: String): ResultadoSolicitudRecuperacion {
        delay(800) // simula la red
        // Para probar el estado de error sin conexión: escribe un correo que empiece con "sinred".
        if (correo.startsWith("sinred", ignoreCase = true)) throw java.io.IOException("Sin conexión simulada")
        return ResultadoSolicitudRecuperacion.DemostracionLocal
    }
}

/* ---------------- Estado ---------------- */
sealed interface EstadoLogin {
    data object Inactivo : EstadoLogin
    data object Cargando : EstadoLogin
    data class Exito(val rol: RolDemo, val correo: String) : EstadoLogin
    data object Error : EstadoLogin
    data object Bloqueado : EstadoLogin
    data object SinConexion : EstadoLogin
}

class LoginViewModel(
    private val repo: AuthRepository = ServiciosAuth.repositorio
) : ViewModel() {
    private val _estado = MutableStateFlow<EstadoLogin>(EstadoLogin.Inactivo)
    val estado: StateFlow<EstadoLogin> = _estado.asStateFlow()

    fun ingresar(correo: String, clave: String) {
        if (correo.isBlank() || clave.isEmpty()) { _estado.value = EstadoLogin.Error; return }
        viewModelScope.launch {
            _estado.value = EstadoLogin.Cargando
            val resultado = try {
                repo.iniciarSesion(correo.trim(), clave)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Servidor apagado, URL equivocada o error interno: no es culpa de la contraseña.
                _estado.value = EstadoLogin.SinConexion
                return@launch
            }
            _estado.value = when (resultado) {
                is ResultadoAutenticacion.Exito -> EstadoLogin.Exito(resultado.rol, resultado.correo)
                ResultadoAutenticacion.CredencialesInvalidas -> EstadoLogin.Error
                ResultadoAutenticacion.CuentaBloqueada -> EstadoLogin.Bloqueado
            }
        }
    }

    fun reiniciar() { _estado.value = EstadoLogin.Inactivo }
}

/* ---------------- Pantalla ---------------- */
@Composable
fun LoginScreen(vm: LoginViewModel = viewModel(), onExito: (RolDemo, String) -> Unit = { _, _ -> }, onOlvido: () -> Unit = {}) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    LaunchedEffect(estado) {
        if (estado is EstadoLogin.Exito) {
            delay(1200)        // deja ver el mensaje verde
            val exito = estado as? EstadoLogin.Exito ?: return@LaunchedEffect
            vm.reiniciar()
            onExito(exito.rol, exito.correo)
        }
    }
    var correo by rememberSaveable { mutableStateOf("") }
    var clave by rememberSaveable { mutableStateOf("") }
    var perfilDemo by rememberSaveable { mutableStateOf(RolDemo.Director.etiqueta) }

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
                    placeholder = { Text("") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = camposColores()
                )

                Text("¿Olvidó su contraseña?", color = Azul, fontSize = 12.sp, textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth()
                        .clickable(role = Role.Button, onClick = onOlvido)   // abre I-02 Recuperar contraseña
                        .padding(top = 14.dp, bottom = 18.dp))               // área táctil ≈ 48 dp

                if (BuildConfig.DEBUG) {
                    Etiqueta("Perfil para probar", Modifier.padding(top = 8.dp))
                    Selector(perfilDemo, listOf(RolDemo.Director, RolDemo.Enfermeria, RolDemo.Administrador, RolDemo.Tutor).map { it.etiqueta }, { etiqueta ->
                        perfilDemo = etiqueta
                        correo = when (etiqueta) {
                            RolDemo.Director.etiqueta -> BuildConfig.DEMO_LOGIN_EMAIL
                            RolDemo.Enfermeria.etiqueta -> BuildConfig.DEMO_NURSE_EMAIL
                            RolDemo.Tutor.etiqueta -> BuildConfig.DEMO_TUTOR_EMAIL
                            else -> BuildConfig.DEMO_ADMIN_EMAIL
                        }
                        clave = BuildConfig.DEMO_LOGIN_PASSWORD
                        vm.reiniciar()
                    })
                    TextButton(
                        onClick = {
                            correo = when (perfilDemo) {
                                RolDemo.Enfermeria.etiqueta -> BuildConfig.DEMO_NURSE_EMAIL
                                RolDemo.Administrador.etiqueta -> BuildConfig.DEMO_ADMIN_EMAIL
                                RolDemo.Tutor.etiqueta -> BuildConfig.DEMO_TUTOR_EMAIL
                                else -> BuildConfig.DEMO_LOGIN_EMAIL
                            }
                            clave = BuildConfig.DEMO_LOGIN_PASSWORD
                            vm.reiniciar()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Autocompletar cuenta demo", color = Azul) }
                }

                Button(
                    onClick = { vm.ingresar(correo, clave) },
                    enabled = estado != EstadoLogin.Cargando && estado !is EstadoLogin.Exito && estado != EstadoLogin.Bloqueado,
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Azul, disabledContainerColor = AzulClaro,
                        contentColor = Color.White, disabledContentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("Ingresar", fontWeight = FontWeight.Bold) }

                when (val actual = estado) {
                    is EstadoLogin.Exito -> Mensaje("Inicio de sesión exitoso como ${actual.rol.etiqueta}. Redirigiendo…", OkFondo, OkBorde, OkTexto)
                    EstadoLogin.Error -> Mensaje("Correo o contraseña incorrectos.", ErrFondo, ErrBorde, ErrTexto)
                    EstadoLogin.Bloqueado -> Mensaje("Cuenta bloqueada tras 3 intentos fallidos. Contacte al administrador.", ErrFondo, ErrBorde, ErrTexto)
                    EstadoLogin.SinConexion -> Mensaje("No se pudo conectar con el servidor de SIRA. Revise su conexión e intente de nuevo.", ErrFondo, ErrBorde, ErrTexto)
                    else -> {}
                }
            }
        }

        Text("RF-01 / RF-21 · Inicio de sesión", color = ErrTexto, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(end = 16.dp, bottom = 10.dp))
    }
}
