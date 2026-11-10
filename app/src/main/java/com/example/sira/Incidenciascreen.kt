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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/* ---------------- Colores y medidas ---------------- */
private val Azul = Color(0xFF1F5B73)
private val FondoTarjeta = Color(0xFFF8F9FB)
private val BordeTarjeta = Color(0xFFD5DCE1)
private val Gris = Color(0xFF6B7780)
private val OkFondo = Color(0xFFE8F5EC);  private val OkBorde = Color(0xFF8CC7A0);  private val OkTexto = Color(0xFF14612E)
private val ErrFondo = Color(0xFFFDEAEA); private val ErrBorde = Color(0xFFD9534F); private val ErrTexto = Color(0xFFB02A2A)

private val LogoAlto = 16.dp
private val LogoAncho = 23.dp
/** Logo: pon tu imagen en res/drawable y cambia el nombre aquí (igual que en LoginScreen). */
private val LogoImagen = R.drawable.ic_launcher_foreground

/** Opciones de los menús desplegables (puedes agregar o quitar). */
private val TiposIncidencia = listOf(
    "Golpe", "Caída", "Herida o corte", "Pelea o agresión", "Mareo o desmayo",
    "Reacción alérgica", "Fiebre o malestar general", "Daño a pertenencias o instalaciones", "Otro"
)
private val Gravedades = listOf("Baja", "Media", "Fuerte")

/* ---------------- Datos ----------------
 * Hoy: un alumno de ejemplo guardado en el código.
 * Después: crea otra clase que implemente IncidenciasRepository y consulte tu servidor/base de datos. */
data class Alumno(val matricula: String, val nombre: String)

interface IncidenciasRepository {
    suspend fun buscarAlumno(texto: String): Alumno?
    suspend fun registrar(alumno: Alumno, tipo: String, gravedad: String, fecha: String, descripcion: String, foto: Uri): Boolean
}

class FakeIncidenciasRepository : IncidenciasRepository {
    private val alumnos = listOf(
        Alumno(matricula = "19071726", nombre = "Bañuelos Martinez Jesus Rogelio")
    )

    /** Acepta matrícula, nombre, o ambos (ej. "19071726 — Bañuelos Martinez Jesus Rogelio").
     *  Ignora mayúsculas y acentos. Si escriben ambos, deben ser del mismo alumno. */
    override suspend fun buscarAlumno(texto: String): Alumno? {
        delay(400)
        val t = normalizar(texto)
        val numeros = Regex("\\d+").findAll(t).map { it.value }.toList()
        val palabras = Regex("\\p{L}+").findAll(t).map { it.value }.toList()
        if (numeros.isEmpty() && palabras.size < 2) return null   // nombre incompleto
        return alumnos.firstOrNull { a ->
            val palabrasAlumno = Regex("\\p{L}+").findAll(normalizar(a.nombre)).map { it.value }.toSet()
            (numeros.isEmpty() || numeros == listOf(a.matricula)) && palabras.all { it in palabrasAlumno }
        }
    }

    override suspend fun registrar(alumno: Alumno, tipo: String, gravedad: String, fecha: String, descripcion: String, foto: Uri): Boolean {
        delay(400)
        return true   // aquí iría el guardado en la base de datos y el aviso al tutor
    }

    private fun normalizar(s: String) =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
}

/* ---------------- Estado ---------------- */
sealed interface EstadoIncidencia {
    data object Inactivo : EstadoIncidencia
    data object Cargando : EstadoIncidencia
    data object Exito : EstadoIncidencia
    data object Error : EstadoIncidencia
}

class IncidenciaViewModel(
    private val repo: IncidenciasRepository = FakeIncidenciasRepository()
) : ViewModel() {
    private val _estado = MutableStateFlow<EstadoIncidencia>(EstadoIncidencia.Inactivo)
    val estado: StateFlow<EstadoIncidencia> = _estado.asStateFlow()

    fun registrar(alumnoTexto: String, tipo: String, gravedad: String, fecha: String, descripcion: String, foto: Uri?) {
        if (alumnoTexto.isBlank() || tipo.isEmpty() || gravedad.isEmpty() || !fechaValida(fecha) || foto == null) {
            _estado.value = EstadoIncidencia.Error; return
        }
        viewModelScope.launch {
            _estado.value = EstadoIncidencia.Cargando
            val ok = try {
                val alumno = repo.buscarAlumno(alumnoTexto)
                alumno != null && repo.registrar(alumno, tipo, gravedad, fecha.trim(), descripcion.trim(), foto)
            } catch (e: Exception) { false }
            _estado.value = if (ok) EstadoIncidencia.Exito else EstadoIncidencia.Error
        }
    }

    fun reiniciar() { _estado.value = EstadoIncidencia.Inactivo }
}

private fun fechaValida(s: String): Boolean = try {
    val t = s.trim()
    t.length == 10 && SimpleDateFormat("dd/MM/yyyy", Locale.US).apply { isLenient = false }.parse(t) != null
} catch (e: Exception) { false }

private fun hoy(): String = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date())

/* ---------------- Pantalla ---------------- */
@Composable
fun IncidenciaScreen(
    vm: IncidenciaViewModel = viewModel(),
    onCerrarSesion: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val estado by vm.estado.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    var alumno by rememberSaveable { mutableStateOf("") }
    var tipo by rememberSaveable { mutableStateOf("") }
    var gravedad by rememberSaveable { mutableStateOf("") }
    var fecha by rememberSaveable { mutableStateOf(hoy()) }
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

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text("SIRA", color = Azul, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp))
                // Solo "Incidencias" está habilitada; las demás se ven atenuadas y no hacen nada.
                ItemMenu("Panel", seleccionado = false, habilitado = false) {}
                ItemMenu("Incidencias", seleccionado = true, habilitado = true) { scope.launch { drawerState.close() } }
                ItemMenu("Reportes", seleccionado = false, habilitado = false) {}
                ItemMenu("Notificaciones", seleccionado = false, habilitado = false) {}
            }
        }
    ) {
        Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding()) {
            // Barra superior
            Row(
                Modifier.fillMaxWidth().height(46.dp).background(Azul).padding(horizontal = 4.dp, vertical = 0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                    Text("☰", color = Color.White, fontSize = 22.sp)
                }
                Image(painterResource(LogoImagen), "Logo", contentScale = ContentScale.Fit,
                    modifier = Modifier.size(LogoAncho, LogoAlto))
                Spacer(Modifier.weight(1f))
                Text("Director / Tutor  |  ", color = Color.White, fontSize = 11.sp)
                Text("Cerrar sesión", color = Color.White, fontSize = 11.sp,
                    modifier = Modifier.clickable { onCerrarSesion() }.padding(end = 12.dp))
            }

            Column(
                Modifier.weight(1f).fillMaxWidth().imePadding()
                    .verticalScroll(rememberScrollState()).padding(16.dp)
            ) {
                Text("REGISTRO DE INCIDENCIA · I-02", color = Gris, fontSize = 12.sp, letterSpacing = 1.sp,
                    modifier = Modifier.padding(bottom = 10.dp))

                Column(
                    Modifier.fillMaxWidth()
                        .background(FondoTarjeta, RoundedCornerShape(6.dp))
                        .border(1.dp, BordeTarjeta, RoundedCornerShape(6.dp))
                        .padding(16.dp)
                ) {
                    Etiqueta("Alumno (matrícula)")
                    OutlinedTextField(
                        value = alumno, onValueChange = { alumno = it },
                        placeholder = { Text("19071726 — Nombre del alumno", fontSize = 13.sp) },
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

                    Etiqueta("Adjuntar evidencia (foto)", Modifier.padding(top = 12.dp))
                    Box {
                        Row(
                            Modifier.fillMaxWidth().height(52.dp)
                                .background(Color.White, RoundedCornerShape(4.dp))
                                .border(1.dp, Color(0xFF9AA7B0), RoundedCornerShape(4.dp))
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
                                alumno = ""; tipo = ""; gravedad = ""; fecha = hoy(); descripcion = ""; foto = null
                                vm.reiniciar()
                            },
                            shape = RoundedCornerShape(4.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Azul),
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
    }
}

/* ---------------- Componentes ---------------- */
@Composable
private fun ItemMenu(texto: String, seleccionado: Boolean, habilitado: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(texto) },
        selected = seleccionado,
        onClick = onClick,
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = Azul, selectedTextColor = Color.White
        ),
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding).alpha(if (habilitado) 1f else 0.4f)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Selector(valor: String, opciones: List<String>, onElegir: (String) -> Unit) {
    var abierto by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = abierto, onExpandedChange = { abierto = it }) {
        OutlinedTextField(
            value = valor, onValueChange = {}, readOnly = true, singleLine = true,
            placeholder = { Text("Seleccionar", fontSize = 13.sp) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = abierto) },
            colors = camposColores(),
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = abierto, onDismissRequest = { abierto = false }) {
            opciones.forEach { opcion ->
                DropdownMenuItem(text = { Text(opcion) }, onClick = { onElegir(opcion); abierto = false })
            }
        }
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
