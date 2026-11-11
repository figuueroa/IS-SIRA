package com.example.sira

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ColorBarra = Color(0xFF4A8FA8)

@Composable
fun PanelScreen(onNavegar: (Seccion) -> Unit, onCerrarSesion: () -> Unit, rol: RolDemo = RolDemo.Director) {
    // Se lee al entrar al panel; si se registra algo nuevo mientras está abierto, aparece el aviso para actualizar.
    val datos = rememberIncidencias()
    val lista = (datos.estado as? EstadoCargaIncidencias.Lista)?.incidencias.orEmpty()

    val delMes = lista.filter { esMesActual(it.fecha) }
    val porTipo = delMes.groupingBy { it.tipo }.eachCount().toList().sortedByDescending { it.second }
    val alertas = delMes.count { it.gravedad == "Fuerte" }
    val masFrecuente = porTipo.firstOrNull()?.first?.let { etiquetaCorta(it) } ?: "—"
    val maximo = porTipo.firstOrNull()?.second ?: 1

    SiraScaffold(Seccion.Panel, rol, onNavegar, onCerrarSesion) {
        if (datos.hayNuevas) AvisoActualizar(datos.actualizar)
        Text("PANEL DE ESTADÍSTICAS · I-06", color = Gris, fontSize = 12.sp, letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 10.dp))

        when (datos.estado) {
            EstadoCargaIncidencias.Cargando -> Row(
                Modifier.fillMaxWidth().padding(vertical = 28.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(color = Azul, modifier = Modifier.size(24.dp))
                Text("Cargando incidencias…", color = Gris, modifier = Modifier.padding(start = 12.dp))
            }
            EstadoCargaIncidencias.Error -> Column {
                Mensaje("No se pudieron cargar las incidencias.", ErrFondo, ErrBorde, ErrTexto)
                TextButton(onClick = datos.actualizar) { Text("Reintentar", color = Azul) }
            }
            is EstadoCargaIncidencias.Lista -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dato("${delMes.size}", "Incidencias del mes", 26.sp, Modifier.weight(1f))
                    Dato("$alertas", "Alertas de gravedad alta", 26.sp, Modifier.weight(1f))
                    Dato(masFrecuente, "Tipo más frecuente", 17.sp, Modifier.weight(1f))
                }

                Text("Incidencias por tipo", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))

                if (porTipo.isEmpty()) {
                    Text("Todavía no hay incidencias este mes.", color = Gris, fontSize = 13.sp)
                    OutlinedButton(
                        onClick = { onNavegar(Seccion.Incidencias) },
                        shape = RoundedCornerShape(4.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Azul),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Azul),
                        modifier = Modifier.padding(top = 8.dp).height(44.dp)
                    ) { Text("Registrar incidencia", fontWeight = FontWeight.SemiBold) }
                } else {
                    porTipo.forEach { (tipo, cantidad) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(etiquetaCorta(tipo), fontSize = 12.sp, modifier = Modifier.width(72.dp))
                            Box(Modifier.weight(1f).height(18.dp)) {
                                Box(
                                    Modifier.fillMaxWidth(cantidad.toFloat() / maximo).fillMaxHeight()
                                        .background(ColorBarra, RoundedCornerShape(2.dp))
                                )
                            }
                            Text("$cantidad", fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.width(28.dp))
                        }
                    }
                }
            }
        }

        OutlinedButton(
            onClick = { onNavegar(Seccion.Reportes) },
            shape = RoundedCornerShape(4.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Azul),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Azul),
            modifier = Modifier.padding(top = 20.dp).height(46.dp)
        ) { Text("Ir a reportes", fontWeight = FontWeight.Bold, fontSize = 13.sp) }

        Text("RF-06 · Panel de estadísticas", color = ErrTexto, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
    }
}

@Composable
private fun Dato(valor: String, etiqueta: String, tamano: TextUnit, modifier: Modifier = Modifier) {
    Column(
        modifier.background(FondoTarjeta, RoundedCornerShape(6.dp))
            .border(1.dp, BordeTarjeta, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(valor, color = Azul, fontSize = tamano, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(etiqueta, color = Gris, fontSize = 11.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp))
    }
}
