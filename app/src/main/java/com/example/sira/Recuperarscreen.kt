package com.example.sira

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/* ---------------- I-02 · Recuperar contraseña (RF-21 / HU-22) ----------------
 * Dos fases, como en el mockup del documento:
 *   1) Formulario: el usuario escribe su correo y pide el enlace.
 *   2) "Revise su correo": mensaje neutro (sea o no un correo registrado) + reenvío con espera.
 * Falta (otra pantalla): "Nueva contraseña", que se abre desde el enlace del correo (deep link). */

/* ---------------- Validación (función pura: se prueba sin Android) ---------------- */
private val PatronCorreo = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$")

internal fun correoValido(correo: String): Boolean = PatronCorreo.matches(correo.trim())

/* ---------------- Estado ---------------- */
private const val SEGUNDOS_REENVIO = 60

enum class FaseRecuperacion { Formulario, Enviado }

enum class ErrorRecuperacion { CorreoVacio, CorreoInvalido, SinConexion }

data class RecuperarUiState(
    val fase: FaseRecuperacion = FaseRecuperacion.Formulario,
    val cargando: Boolean = false,
    val error: ErrorRecuperacion? = null,
    /** Correo al que se pidió el enlace (se usa para reenviar). */
    val correo: String = "",
    /** Segundos que faltan para poder reenviar; 0 = ya se puede. */
    val segundosReenvio: Int = 0
)

class RecuperarViewModel(
    private val repo: AuthRepository = FakeAuthRepository()
) : ViewModel() {
    private val _ui = MutableStateFlow(RecuperarUiState())
    val ui: StateFlow<RecuperarUiState> = _ui.asStateFlow()
    private var cuentaAtras: Job? = null

    fun enviar(correo: String) {
        val c = correo.trim()
        val error = when {
            c.isEmpty() -> ErrorRecuperacion.CorreoVacio
            !correoValido(c) -> ErrorRecuperacion.CorreoInvalido
            else -> null
        }
        if (error != null) { _ui.update { it.copy(error = error) }; return }
        solicitar(c)
    }

    fun reenviar() {
        val s = _ui.value
        if (s.segundosReenvio > 0) return
        solicitar(s.correo)
    }

    /** Quita el error en cuanto el usuario vuelve a escribir. */
    fun alEditar() {
        if (_ui.value.error != null) _ui.update { it.copy(error = null) }
    }

    /** Vuelve al estado inicial (al salir de la pantalla). */
    fun reiniciar() {
        cuentaAtras?.cancel()
        _ui.value = RecuperarUiState()
    }

    private fun solicitar(correo: String) {
        if (_ui.value.cargando) return                       // evita doble envío
        _ui.update { it.copy(cargando = true, error = null) }
        viewModelScope.launch {
            val ok = try {
                repo.solicitarRecuperacion(correo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (ok) {
                _ui.update { it.copy(cargando = false, fase = FaseRecuperacion.Enviado, correo = correo) }
                iniciarCuentaAtras()
            } else {
                _ui.update { it.copy(cargando = false, error = ErrorRecuperacion.SinConexion) }
            }
        }
    }

    private fun iniciarCuentaAtras() {
        cuentaAtras?.cancel()
        cuentaAtras = viewModelScope.launch {
            for (s in SEGUNDOS_REENVIO downTo 1) {
                _ui.update { it.copy(segundosReenvio = s) }
                delay(1000)
            }
            _ui.update { it.copy(segundosReenvio = 0) }
        }
    }
}

/* ---------------- Pantalla ---------------- */
@Composable
fun RecuperarScreen(onVolver: () -> Unit, vm: RecuperarViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val volver = { vm.reiniciar(); onVolver() }
    BackHandler(onBack = volver)

    Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding().imePadding()) {
        // Misma barra superior que el login: solo el logo.
        Box(
            Modifier.fillMaxWidth().height(46.dp).background(Azul).padding(horizontal = 20.dp),
            contentAlignment = Alignment.CenterStart
        ) { LogoSira() }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 48.dp)
        ) {
            Crossfade(targetState = ui.fase, label = "faseRecuperar") { fase ->
                when (fase) {
                    FaseRecuperacion.Formulario -> FormularioRecuperar(
                        ui = ui, onEnviar = vm::enviar, onEditar = vm::alEditar, onVolver = volver
                    )
                    FaseRecuperacion.Enviado -> EnviadoRecuperar(
                        ui = ui, onReenviar = vm::reenviar, onVolver = volver
                    )
                }
            }
        }

        // Etiqueta de trazabilidad, igual que en las demás pantallas (quitar o dejar solo en debug al final).
        Text("RF-21 · Recuperar contraseña", color = ErrTexto, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(end = 16.dp, bottom = 10.dp))
    }
}

/* ---------------- Fase 1: formulario ---------------- */
@Composable
private fun FormularioRecuperar(
    ui: RecuperarUiState,
    onEnviar: (String) -> Unit,
    onEditar: () -> Unit,
    onVolver: () -> Unit
) {
    var correo by rememberSaveable { mutableStateOf("") }
    val foco = LocalFocusManager.current
    val enviar = { foco.clearFocus(); onEnviar(correo) }

    val errorCampo = when (ui.error) {
        ErrorRecuperacion.CorreoVacio -> "Ingrese su correo electrónico."
        ErrorRecuperacion.CorreoInvalido -> "Revise el formato: nombre@institucion.edu.mx"
        else -> null
    }

    Tarjeta {
        Titulo("Recuperar contraseña")
        Text("Sistema de Incidencias y Respuestas Auxiliares", color = Gris, fontSize = 12.sp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 12.dp))
        Text("Ingrese el correo electrónico asociado a su cuenta. Le enviaremos un enlace para restablecer su contraseña.",
            fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))

        Etiqueta("Correo electrónico")
        OutlinedTextField(
            value = correo,
            onValueChange = { correo = it; onEditar() },
            placeholder = { Text("nombre@institucion.edu.mx") },
            singleLine = true,
            enabled = !ui.cargando,
            isError = errorCampo != null,
            supportingText = if (errorCampo != null) { { Text(errorCampo) } } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { enviar() }),
            colors = camposColores(),
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = { enviar() },
            enabled = !ui.cargando,
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Azul, disabledContainerColor = AzulClaro,
                contentColor = Color.White, disabledContentColor = Color.White),
            modifier = Modifier.padding(top = 16.dp).fillMaxWidth().height(48.dp)
        ) {
            if (ui.cargando) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Enviando…", fontWeight = FontWeight.Bold)
            } else {
                Text("Enviar enlace", fontWeight = FontWeight.Bold)
            }
        }

        if (ui.error == ErrorRecuperacion.SinConexion) AvisoError("No se pudo contactar al servidor. Revise su conexión e intente de nuevo.")

        TextButton(
            onClick = onVolver,
            colors = ButtonDefaults.textButtonColors(contentColor = Azul),
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp)
        ) { Text("← Volver a iniciar sesión", fontSize = 12.sp) }
    }
}

/* ---------------- Fase 2: "Revise su correo" ---------------- */
@Composable
private fun EnviadoRecuperar(
    ui: RecuperarUiState,
    onReenviar: () -> Unit,
    onVolver: () -> Unit
) {
    val puedeReenviar = ui.segundosReenvio == 0 && !ui.cargando

    Tarjeta {
        Titulo("Revise su correo")
        Text("Sistema de Incidencias y Respuestas Auxiliares", color = Gris, fontSize = 12.sp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp))

        // Mensaje neutro a propósito: no revela si el correo está o no registrado.
        Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            Mensaje("Si el correo está registrado en SIRA, recibirá un enlace para restablecer su contraseña en unos minutos.",
                OkFondo, OkBorde, OkTexto)
        }
        Text("Si no lo encuentra, revise la carpeta de correo no deseado.", fontSize = 13.sp,
            modifier = Modifier.padding(top = 14.dp, bottom = 16.dp))

        Button(
            onClick = onVolver,
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Azul, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text("Volver a iniciar sesión", fontWeight = FontWeight.Bold) }

        if (ui.error == ErrorRecuperacion.SinConexion) AvisoError("No se pudo reenviar el enlace. Revise su conexión e intente de nuevo.")

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("¿No recibió el correo?", color = Gris, fontSize = 12.sp)
            TextButton(
                onClick = onReenviar,
                enabled = puedeReenviar,
                colors = ButtonDefaults.textButtonColors(contentColor = Azul, disabledContentColor = Gris)
            ) {
                Text(
                    when {
                        ui.cargando -> "Enviando…"
                        ui.segundosReenvio > 0 -> "Reenviar en ${ui.segundosReenvio} s"
                        else -> "Reenviar enlace"
                    },
                    fontSize = 12.sp
                )
            }
        }
    }
}

/* ---------------- Piezas pequeñas ---------------- */
@Composable
private fun Titulo(texto: String) =
    Text(texto, color = Azul, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().semantics { heading() })

/** Mensaje de error que TalkBack lee solo al aparecer. */
@Composable
private fun AvisoError(texto: String) =
    Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) { Mensaje(texto, ErrFondo, ErrBorde, ErrTexto) }

/* ---------------- Vistas previas (Android Studio → Split/Design) ---------------- */
@Preview(showBackground = true, name = "Formulario")
@Composable
private fun PreviewFormulario() = MaterialTheme {
    Column(Modifier.padding(16.dp)) { FormularioRecuperar(RecuperarUiState(), {}, {}, {}) }
}

@Preview(showBackground = true, name = "Formulario con error")
@Composable
private fun PreviewFormularioError() = MaterialTheme {
    Column(Modifier.padding(16.dp)) {
        FormularioRecuperar(RecuperarUiState(error = ErrorRecuperacion.SinConexion), {}, {}, {})
    }
}

@Preview(showBackground = true, name = "Revise su correo")
@Composable
private fun PreviewEnviado() = MaterialTheme {
    Column(Modifier.padding(16.dp)) {
        EnviadoRecuperar(RecuperarUiState(fase = FaseRecuperacion.Enviado, correo = "a@b.mx", segundosReenvio = 42), {}, {})
    }
}
