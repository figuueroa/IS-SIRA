package com.example.sira

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

/* ---------------- Estado ---------------- */
sealed interface EstadoIncidencia {
    data object Inactivo : EstadoIncidencia
    data object Cargando : EstadoIncidencia
    data object Exito : EstadoIncidencia
    data object ErrorValidacion : EstadoIncidencia
    data object AlumnoNoEncontrado : EstadoIncidencia
    data object ErrorRegistro : EstadoIncidencia
}

private enum class AccionSalidaIncidencia { Cancelar, Navegar, CerrarSesion }

class IncidenciaViewModel(
    private val repo: IncidenciasRepository = Servicios.incidencias
) : ViewModel() {
    private val _estado = MutableStateFlow<EstadoIncidencia>(EstadoIncidencia.Inactivo)
    val estado: StateFlow<EstadoIncidencia> = _estado.asStateFlow()

    fun registrar(alumnoTexto: String, tipo: String, gravedad: String, fecha: Date, descripcion: String, foto: Uri?) {
        if (_estado.value == EstadoIncidencia.Cargando || _estado.value == EstadoIncidencia.Exito) return
        if (alumnoTexto.isBlank() || tipo.isEmpty() || gravedad.isEmpty() || fecha.after(Date())) {
            _estado.value = EstadoIncidencia.ErrorValidacion
            return
        }
        _estado.value = EstadoIncidencia.Cargando
        viewModelScope.launch {
            val resultado = try {
                val alumno = repo.buscarAlumno(alumnoTexto)
                when {
                    alumno == null -> EstadoIncidencia.AlumnoNoEncontrado
                    repo.registrar(alumno, tipo, gravedad, fecha, descripcion.trim(), foto) -> EstadoIncidencia.Exito
                    else -> EstadoIncidencia.ErrorRegistro
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EstadoIncidencia.ErrorRegistro
            }
            _estado.value = resultado
        }
    }

    fun reiniciar() { _estado.value = EstadoIncidencia.Inactivo }

    fun mostrarErrorRegistro() {
        if (_estado.value != EstadoIncidencia.Cargando && _estado.value != EstadoIncidencia.Exito) {
            _estado.value = EstadoIncidencia.ErrorRegistro
        }
    }
}

/* ---------------- Pantalla ---------------- */
@Composable
fun IncidenciaScreen(
    onNavegar: (Seccion) -> Unit = {},
    onCerrarSesion: () -> Unit = {},
    vm: IncidenciaViewModel = viewModel(),
    rol: RolDemo = RolDemo.Director
) {
    val context = LocalContext.current
    val estado by vm.estado.collectAsStateWithLifecycle()

    var alumno by rememberSaveable { mutableStateOf("") }
    var tipo by rememberSaveable { mutableStateOf("") }
    var gravedad by rememberSaveable { mutableStateOf("") }
    var fechaInicialMillis by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    var fechaHoraMillis by rememberSaveable { mutableLongStateOf(fechaInicialMillis) }
    var descripcion by rememberSaveable { mutableStateOf("") }
    var foto by rememberSaveable { mutableStateOf<Uri?>(null) }
    var fotoPendiente by rememberSaveable { mutableStateOf<Uri?>(null) }
    var archivoFotoPendiente by remember { mutableStateOf<File?>(null) }
    var menuFoto by remember { mutableStateOf(false) }
    var confirmarSalida by remember { mutableStateOf(false) }
    var accionSalida by remember { mutableStateOf<AccionSalidaIncidencia?>(null) }
    var destinoPendiente by remember { mutableStateOf<Seccion?>(null) }
    val fechaHora = Date(fechaHoraMillis)
    val hayBorrador = alumno.isNotBlank() || tipo.isNotBlank() || gravedad.isNotBlank() ||
        descripcion.isNotBlank() || foto != null || fotoPendiente != null || fechaHoraMillis != fechaInicialMillis

    fun limpiarFormulario(reiniciarEstado: Boolean = true) {
        alumno = ""
        tipo = ""
        gravedad = ""
        descripcion = ""
        foto = null
        fotoPendiente = null
        fechaInicialMillis = System.currentTimeMillis()
        fechaHoraMillis = fechaInicialMillis
        if (reiniciarEstado) vm.reiniciar()
    }

    fun ejecutarSalida(accion: AccionSalidaIncidencia, destino: Seccion? = null) {
        when (accion) {
            AccionSalidaIncidencia.Cancelar -> limpiarFormulario()
            AccionSalidaIncidencia.Navegar -> {
                limpiarFormulario()
                destino?.let(onNavegar)
            }
            AccionSalidaIncidencia.CerrarSesion -> {
                limpiarFormulario()
                onCerrarSesion()
            }
        }
    }

    fun solicitarSalida(accion: AccionSalidaIncidencia, destino: Seccion? = null) {
        if (hayBorrador) {
            accionSalida = accion
            destinoPendiente = destino
            confirmarSalida = true
        } else ejecutarSalida(accion, destino)
    }

    LaunchedEffect(estado) {
        if (estado == EstadoIncidencia.Exito) {
            limpiarFormulario(reiniciarEstado = false)
            delay(1800)
            vm.reiniciar()
        }
    }

    val galeria = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { foto = uri; vm.reiniciar() }
    }
    val camara = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { tomada ->
        if (tomada) {
            foto = fotoPendiente
            vm.reiniciar()
        } else {
            archivoFotoPendiente?.delete()
        }
        archivoFotoPendiente = null
        fotoPendiente = null
    }
    val miniatura by produceState<Bitmap?>(null, foto) {
        value = foto?.let { uri -> withContext(Dispatchers.IO) { cargarMiniatura(context, uri) } }
    }

    SiraScaffold(
        seccion = Seccion.Incidencias,
        rol = rol,
        onNavegar = { solicitarSalida(AccionSalidaIncidencia.Navegar, it) },
        onCerrarSesion = { solicitarSalida(AccionSalidaIncidencia.CerrarSesion) }
    ) {
        Text("REGISTRO DE INCIDENCIA · I-02", color = Gris, fontSize = 12.sp, letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 10.dp))

        Tarjeta {
            Etiqueta("Alumno (matrícula)")
            OutlinedTextField(
                value = alumno, onValueChange = { alumno = it; vm.reiniciar() },
                placeholder = { Text("Ej: 19036816 — Nombre del alumno", fontSize = 13.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth(), colors = camposColores()
            )

            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Etiqueta("Tipo de incidencia")
                    Selector(tipo, TiposIncidencia, { tipo = it; vm.reiniciar() })
                }
                Column(Modifier.weight(1f)) {
                    Etiqueta("Gravedad")
                    Selector(gravedad, Gravedades, { gravedad = it; vm.reiniciar() })
                }
            }

            Etiqueta("Fecha y hora del incidente", Modifier.padding(top = 12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = formatearFecha(fechaHora), onValueChange = {}, readOnly = true,
                    label = { Text("Fecha") }, singleLine = true,
                    modifier = Modifier.weight(1.2f).clickable {
                        val actual = Calendar.getInstance().apply { time = fechaHora }
                        DatePickerDialog(
                            context,
                            { _, year, month, day ->
                                actual.set(Calendar.YEAR, year)
                                actual.set(Calendar.MONTH, month)
                                actual.set(Calendar.DAY_OF_MONTH, day)
                                fechaHoraMillis = actual.timeInMillis
                                vm.reiniciar()
                            },
                            actual.get(Calendar.YEAR), actual.get(Calendar.MONTH), actual.get(Calendar.DAY_OF_MONTH)
                        ).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
                    },
                    colors = camposColores()
                )
                OutlinedTextField(
                    value = SimpleDateFormat("HH:mm", Locale.getDefault()).format(fechaHora),
                    onValueChange = {}, readOnly = true, label = { Text("Hora") }, singleLine = true,
                    modifier = Modifier.weight(0.8f).clickable {
                        val actual = Calendar.getInstance().apply { time = fechaHora }
                        TimePickerDialog(
                            context,
                            { _, hour, minute ->
                                actual.set(Calendar.HOUR_OF_DAY, hour)
                                actual.set(Calendar.MINUTE, minute)
                                actual.set(Calendar.SECOND, 0)
                                actual.set(Calendar.MILLISECOND, 0)
                                fechaHoraMillis = actual.timeInMillis
                                vm.reiniciar()
                            },
                            actual.get(Calendar.HOUR_OF_DAY), actual.get(Calendar.MINUTE), true
                        ).show()
                    },
                    colors = camposColores()
                )
            }

            Etiqueta("Descripción", Modifier.padding(top = 12.dp))
            OutlinedTextField(
                value = descripcion, onValueChange = { descripcion = it; vm.reiniciar() },
                placeholder = { Text("Describa lo ocurrido", fontSize = 13.sp) },
                minLines = 3, modifier = Modifier.fillMaxWidth(), colors = camposColores()
            )

            Etiqueta("Adjuntar evidencia (opcional)", Modifier.padding(top = 12.dp))
            Box {
                Row(
                    Modifier.fillMaxWidth().height(52.dp)
                        .background(Color.White, RoundedCornerShape(4.dp))
                        .border(1.dp, BordeCampo, RoundedCornerShape(4.dp))
                        .clickable { menuFoto = true }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) { Text("📷  Tomar / adjuntar foto", color = Gris, fontSize = 13.sp) }
                DropdownMenu(expanded = menuFoto, onDismissRequest = { menuFoto = false }) {
                    DropdownMenuItem(text = { Text("Tomar foto") }, onClick = {
                        menuFoto = false
                        try {
                            val archivo = File(File(context.cacheDir, "fotos").apply { mkdirs() }, "inc_${System.currentTimeMillis()}.jpg")
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archivo)
                            archivoFotoPendiente = archivo
                            fotoPendiente = uri
                            camara.launch(uri)
                        } catch (e: Exception) {
                            archivoFotoPendiente?.delete()
                            archivoFotoPendiente = null
                            fotoPendiente = null
                            vm.mostrarErrorRegistro()
                        }
                    })
                    DropdownMenuItem(text = { Text("Elegir de la galería") }, onClick = {
                        menuFoto = false
                        galeria.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                }
            }

            // Vista previa pequeña de la foto
            if (foto != null) {
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(110.dp).clip(RoundedCornerShape(6.dp))
                            .border(1.dp, BordeTarjeta, RoundedCornerShape(6.dp)).background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        miniatura?.let {
                            Image(it.asImageBitmap(), "Vista previa de la evidencia",
                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                    TextButton(onClick = { foto = null; vm.reiniciar() }) { Text("Quitar foto", color = ErrTexto) }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { vm.registrar(alumno, tipo, gravedad, fechaHora, descripcion, foto) },
                    enabled = estado != EstadoIncidencia.Cargando && estado != EstadoIncidencia.Exito,
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Azul, contentColor = Color.White),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text("Registrar incidencia", fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center) }
                OutlinedButton(
                    onClick = { solicitarSalida(AccionSalidaIncidencia.Cancelar) },
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, Azul),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Azul),
                    modifier = Modifier.weight(0.7f).height(48.dp)
                ) { Text("Cancelar", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            }

            when (estado) {
                EstadoIncidencia.Exito -> Mensaje("Incidencia registrada con éxito.", OkFondo, OkBorde, OkTexto)
                EstadoIncidencia.ErrorValidacion -> Mensaje("Revisa los datos y asegúrate de que la fecha no sea futura.", ErrFondo, ErrBorde, ErrTexto)
                EstadoIncidencia.AlumnoNoEncontrado -> Mensaje("No se encontró un alumno único con esos datos.", ErrFondo, ErrBorde, ErrTexto)
                EstadoIncidencia.ErrorRegistro -> Mensaje("No se pudo registrar la incidencia. Intenta de nuevo.", ErrFondo, ErrBorde, ErrTexto)
                else -> {}
            }
        }

        if (confirmarSalida) {
            AlertDialog(
                onDismissRequest = { confirmarSalida = false; accionSalida = null; destinoPendiente = null },
                title = { Text("¿Descartar el borrador?") },
                text = { Text("La incidencia todavía no se ha registrado. Si sales ahora, perderás los datos capturados.") },
                confirmButton = {
                    TextButton(onClick = {
                        val accion = accionSalida
                        val destino = destinoPendiente
                        confirmarSalida = false
                        accionSalida = null
                        destinoPendiente = null
                        if (accion != null) ejecutarSalida(accion, destino)
                    }) { Text("Descartar", color = ErrTexto) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmarSalida = false; accionSalida = null; destinoPendiente = null }) {
                        Text("Seguir editando", color = Azul)
                    }
                }
            )
        }

        Text("RF-12 · Registro de incidencia", color = ErrTexto, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}

/** Carga la foto reducida (para no gastar memoria) y corrige la rotación de las fotos de la cámara. */
private fun cargarMiniatura(context: Context, uri: Uri, maxLado: Int = 480): Bitmap? = try {
    val cr = context.contentResolver
    val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, medidas) }
    var muestra = 1
    while (max(medidas.outWidth, medidas.outHeight) / muestra > maxLado * 2) muestra *= 2
    val bmp = cr.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = muestra })
    }
    val giro = cr.openInputStream(uri)?.use {
        when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } ?: 0f
    if (bmp != null && giro != 0f)
        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(giro) }, true)
    else bmp
} catch (e: Exception) { null }
