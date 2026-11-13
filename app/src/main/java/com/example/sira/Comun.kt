package com.example.sira

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/* ---------------- Colores ---------------- */
internal val Azul = Color(0xFF1F5B73)
internal val AzulClaro = Color(0xFF7096A8)
internal val FondoTarjeta = Color(0xFFF8F9FB)
internal val BordeTarjeta = Color(0xFFD5DCE1)
internal val BordeCampo = Color(0xFF9AA7B0)
internal val Gris = Color(0xFF6B7780)
internal val OkFondo = Color(0xFFE8F5EC);  internal val OkBorde = Color(0xFF8CC7A0);  internal val OkTexto = Color(0xFF14612E)
internal val ErrFondo = Color(0xFFFDEAEA); internal val ErrBorde = Color(0xFFD9534F); internal val ErrTexto = Color(0xFFB02A2A)

/* ---------------- LOGO (se cambia aquí, en un solo lugar) ----------------
 * 1) Copia tu imagen a app/src/main/res/drawable/   2) Cambia el nombre en LogoImagen.
 * El logo nuevo tiene los mismos colores que la barra azul, así que va dentro de un círculo blanco.
 * Para quitarlo, pon LogoConInsignia = false (y baja LogoTamano si quieres). */
internal val LogoImagen = R.drawable.logo_sira_sin_fondo
internal val LogoConInsignia = true
internal val LogoTamano = 32.dp

@Composable
internal fun LogoSira() {
    Box(
        Modifier.size(LogoTamano)
            .then(if (LogoConInsignia) Modifier.clip(CircleShape).background(Color.White).padding(4.dp) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(LogoImagen),
            contentDescription = "Logo SIRA",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/* ---------------- Componentes de formulario ---------------- */
@Composable
internal fun Etiqueta(texto: String, modifier: Modifier = Modifier) =
    Text(texto, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = modifier.padding(bottom = 4.dp))

@Composable
internal fun camposColores() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
    focusedBorderColor = Azul, unfocusedBorderColor = BordeCampo
)

@Composable
internal fun Mensaje(texto: String, fondo: Color, borde: Color, color: Color) =
    Text(texto, color = color, fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
            .background(fondo, RoundedCornerShape(4.dp))
            .border(1.dp, borde, RoundedCornerShape(4.dp))
            .padding(14.dp))

@Composable
internal fun Tarjeta(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(
        modifier.fillMaxWidth()
            .background(FondoTarjeta, RoundedCornerShape(6.dp))
            .border(1.dp, BordeTarjeta, RoundedCornerShape(6.dp))
            .padding(16.dp),
        content = content
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Selector(valor: String, opciones: List<String>, onElegir: (String) -> Unit, modifier: Modifier = Modifier) {
    var abierto by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = abierto, onExpandedChange = { abierto = it }, modifier = modifier) {
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

/* ---------------- Estructura común: barra superior + menú de tres rayas ---------------- */
enum class Seccion(val titulo: String) {
    Panel("Panel"), Incidencias("Incidencias"), ConsultaIncidencias("Consulta de incidencias"), Reportes("Reportes"), HistorialMedico("Historial médico"),
    RegistroExpediente("Registro médico"), GestionTutores("Registro de tutores"), GestionUsuarios("Registro de usuarios"),
    Bitacora("Bitácora"), Notificaciones("Notificaciones"), TutorIncidencias("Mis hijos e incidencias"),
    RolesPermisos("Roles y permisos"), Respaldos("Respaldos")
}

/** Reglas de navegación de la demo, en espera de RBAC validado por el backend. */
internal fun Seccion.permitidaPara(rol: RolDemo): Boolean = when (rol) {
    RolDemo.Administrador -> this == Seccion.GestionUsuarios || this == Seccion.GestionTutores ||
            this == Seccion.Bitacora || this == Seccion.ConsultaIncidencias ||
            this == Seccion.RolesPermisos || this == Seccion.Respaldos
    RolDemo.Director -> this == Seccion.Panel || this == Seccion.Incidencias || this == Seccion.ConsultaIncidencias ||
            this == Seccion.Reportes || this == Seccion.Notificaciones
    RolDemo.Enfermeria -> this == Seccion.Panel || this == Seccion.HistorialMedico || this == Seccion.RegistroExpediente ||
            this == Seccion.Incidencias || this == Seccion.Reportes || this == Seccion.Notificaciones
    RolDemo.Tutor -> this == Seccion.TutorIncidencias || this == Seccion.Notificaciones
}

@Composable
internal fun SiraScaffold(
    seccion: Seccion,
    rol: RolDemo,
    onNavegar: (Seccion) -> Unit,
    onCerrarSesion: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text("SIRA", color = Azul, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp))
                Seccion.entries.filter { it.permitidaPara(rol) }.forEach { s ->
                    ItemMenu(s.titulo, seleccionado = s == seccion, habilitado = true) {
                        scope.launch { drawerState.close() }
                        if (s != seccion) onNavegar(s)
                    }
                }
            }
        }
    ) {
        Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().height(46.dp).background(Azul).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                    Text("☰", color = Color.White, fontSize = 22.sp)
                }
                LogoSira()
                Spacer(Modifier.weight(1f))
                Text("${rol.etiqueta}  |  ", color = Color.White, fontSize = 11.sp)
                Text("Cerrar sesión", color = Color.White, fontSize = 11.sp,
                    modifier = Modifier.clickable { onCerrarSesion() }.padding(end = 12.dp))
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().imePadding()
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                content = content
            )
        }
    }
}

@Composable
private fun ItemMenu(texto: String, seleccionado: Boolean, habilitado: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(texto) },
        selected = seleccionado,
        onClick = onClick,
        colors = NavigationDrawerItemDefaults.colors(selectedContainerColor = Azul, selectedTextColor = Color.White),
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding).alpha(if (habilitado) 1f else 0.4f)
    )
}

/* ---------------- Datos en vivo: aviso "Nueva incidencia registrada, actualice" ---------------- */
sealed interface EstadoCargaIncidencias {
    data object Cargando : EstadoCargaIncidencias
    data class Lista(val incidencias: List<Incidencia>) : EstadoCargaIncidencias
    data object Error : EstadoCargaIncidencias
}

class DatosIncidencias(
    val estado: EstadoCargaIncidencias,
    val hayNuevas: Boolean,
    val actualizar: () -> Unit
)

/** Carga las incidencias al entrar a la pantalla y avisa si se registra alguna nueva mientras tanto. */
@Composable
internal fun rememberIncidencias(): DatosIncidencias {
    val repo = Servicios.incidencias
    var recarga by remember { mutableStateOf(0) }
    var versionCargada by remember { mutableStateOf(repo.version.value) }
    val estado by produceState<EstadoCargaIncidencias>(EstadoCargaIncidencias.Cargando, recarga) {
        value = EstadoCargaIncidencias.Cargando
        try {
            val incidencias = repo.listarIncidencias()
            versionCargada = repo.version.value
            value = EstadoCargaIncidencias.Lista(incidencias)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            value = EstadoCargaIncidencias.Error
        }
    }
    val versionActual by repo.version.collectAsStateWithLifecycle()
    return DatosIncidencias(estado, versionActual != versionCargada) { recarga++ }
}

@Composable
internal fun AvisoActualizar(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp)
            .background(Color(0xFFFFF4D6), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0xFFE6C26B), RoundedCornerShape(6.dp))
            .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Nueva incidencia registrada, actualice", color = Color(0xFF6B4E00), fontSize = 13.sp,
            modifier = Modifier.weight(1f))
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Azul, contentColor = Color.White)
        ) { Text("Actualizar", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
    }
}