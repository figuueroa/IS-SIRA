package com.example.sira

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import kotlin.math.max

/* ---------------- Estado ---------------- */
sealed interface EstadoIncidencia {
    data object Inactivo : EstadoIncidencia
    data object Cargando : EstadoIncidencia
    data object Exito : EstadoIncidencia
    data object Error : EstadoIncidencia
}

class IncidenciaViewModel(
    private val repo: IncidenciasRepository = Servicios.incidencias
) : ViewModel() {
    private val _estado = MutableStateFlow<EstadoIncidencia>(EstadoIncidencia.Inactivo)
    val estado: StateFlow<EstadoIncidencia> = _estado.asStateFlow()

    fun registrar(alumnoTexto: String, tipo: String, gravedad: String, fecha: String, descripcion: String, foto: Uri?) {
        val fechaDate = parsearFecha(fecha)
        if (alumnoTexto.isBlank() || tipo.isEmpty() || gravedad.isEmpty() || fechaDate == null || foto == null) {
            _estado.value = EstadoIncidencia.Error; return
        }
        viewModelScope.launch {
            _estado.value = EstadoIncidencia.Cargando
            val ok = try {
                val alumno = repo.buscarAlumno(alumnoTexto)
                alumno != null && repo.registrar(alumno, tipo, gravedad, fechaDate, descripcion.trim(), foto)
            } catch (e: Exception) { false }
            _estado.value = if (ok) EstadoIncidencia.Exito else EstadoIncidencia.Error
        }
    }

    fun reiniciar() { _estado.value = EstadoIncidencia.Inactivo }
}

/* ---------------- Pantalla ---------------- */
@Composable
fun IncidenciaScreen(
    onNavegar: (Seccion) -> Unit = {},
    onCerrarSesion: () -> Unit = {},
    vm: IncidenciaViewModel = viewModel()
) {
    val context = LocalContext.current
    val estado by vm.estado.collectAsStateWithLifecycle()

    var alumno by rememberSaveable { mutableStateOf("") }
    var tipo by rememberSaveable { mutableStateOf("") }
    var gravedad by rememberSaveable { mutableStateOf("") }
    var fecha by rememberSaveable { mutableStateOf(formatearFecha(Date())) }
    var descripcion by rememberSaveable { mutableStateOf("") }
    var foto by rememberSaveable { mutableStateOf<Uri?>(null) }
    var fotoPendiente by rememberSaveable { mutableStateOf<Uri?>(null) }
    var menuFoto by remember { mutableStateOf(false) }

    val galeria = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) foto = uri
    }
    val camara = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { tomada ->
        if (tomada) foto = fotoPendiente
    }
    val miniatura by produceState<Bitmap?>(null, foto) {
        value = foto?.let { uri -> withContext(Dispatchers.IO) { cargarMiniatura(context, uri) } }
    }

    SiraScaffold(
        seccion = Seccion.Incidencias,
        rol = "Director",
        onNavegar = { vm.reiniciar(); onNavegar(it) },
        onCerrarSesion = { vm.reiniciar(); onCerrarSesion() }
    ) {
        Text("REGISTRO DE INCIDENCIA · I-02", color = Gris, fontSize = 12.sp, letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 10.dp))

        Tarjeta {
            Etiqueta("Alumno (matrícula)")
            OutlinedTextField(
                value = alumno, onValueChange = { alumno = it },
                placeholder = { Text("Ej: 19036816 — Nombre del alumno", fontSize = 13.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth(), colors = camposColores()
            )

            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Etiqueta("Tipo de incidencia")
                    Selector(tipo, TiposIncidencia, { tipo = it })
                }
                Column(Modifier.weight(1f)) {
                    Etiqueta("Gravedad")
                    Selector(gravedad, Gravedades, { gravedad = it })
                }
            }

            Etiqueta("Fecha", Modifier.padding(top = 12.dp))
            OutlinedTextField(
                value = fecha, onValueChange = { fecha = it },
                placeholder = { Text("dd/mm/aaaa") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), colors = camposColores()
            )

            Etiqueta("Descripción", Modifier.padding(top = 12.dp))
            OutlinedTextField(
                value = descripcion, onValueChange = { descripcion = it },
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
                        val archivo = File(File(context.cacheDir, "fotos").apply { mkdirs() }, "inc_${System.currentTimeMillis()}.jpg")
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archivo)
                        fotoPendiente = uri
                        camara.launch(uri)
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
                    TextButton(onClick = { foto = null }) { Text("Quitar foto", color = ErrTexto) }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { vm.registrar(alumno, tipo, gravedad, fecha, descripcion, foto) },
                    enabled = estado != EstadoIncidencia.Cargando,
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Azul, contentColor = Color.White),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text("Registrar incidencia", fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center) }
                OutlinedButton(
                    onClick = {
                        alumno = ""; tipo = ""; gravedad = ""; fecha = formatearFecha(Date()); descripcion = ""; foto = null
                        vm.reiniciar()
                    },
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, Azul),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Azul),
                    modifier = Modifier.weight(0.7f).height(48.dp)
                ) { Text("Cancelar", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            }

            when (estado) {
                EstadoIncidencia.Exito -> Mensaje("Incidencia registrada con éxito. Se notificó al tutor.", OkFondo, OkBorde, OkTexto)
                EstadoIncidencia.Error -> Mensaje("Formato de datos o captura de foto inválida.", ErrFondo, ErrBorde, ErrTexto)
                else -> {}
            }
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