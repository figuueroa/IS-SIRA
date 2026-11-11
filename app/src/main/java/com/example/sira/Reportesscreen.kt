package com.example.sira

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TODAS = "Todas"

private enum class MensajeReporte { Ninguno, Generado, PeriodoInvalido, GenerarPrimero, ErrorExportar }

@Composable
fun ReportesScreen(onNavegar: (Seccion) -> Unit, onCerrarSesion: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val datos = rememberIncidencias()
    val lista = datos.lista

    var desde by rememberSaveable { mutableStateOf(primerDiaDelMes()) }
    var hasta by rememberSaveable { mutableStateOf(formatearFecha(Date())) }
    var tipo by rememberSaveable { mutableStateOf(TODAS) }
    var formato by rememberSaveable { mutableStateOf("PDF") }
    var generado by remember { mutableStateOf<List<Incidencia>?>(null) }
    var mensaje by remember { mutableStateOf(MensajeReporte.Ninguno) }

    val fDesde = parsearFecha(desde)
    val fHasta = parsearFecha(hasta)
    val periodoOk = fDesde != null && fHasta != null && !fDesde.after(fHasta)
    val filtradas =
        if (fDesde != null && fHasta != null && !fDesde.after(fHasta))
            filtrarIncidencias(lista, fDesde, fHasta, tipo.takeIf { it != TODAS })
        else emptyList()

    // Si cambian los filtros, el reporte generado ya no corresponde.
    fun cambio() { generado = null; mensaje = MensajeReporte.Ninguno }

    SiraScaffold(Seccion.Reportes, "Director", onNavegar, onCerrarSesion) {
        if (datos.hayNuevas) AvisoActualizar { datos.actualizar(); cambio() }
        Text("GENERACIÓN DE REPORTES · I-07", color = Gris, fontSize = 12.sp, letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 10.dp))

        Tarjeta {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Etiqueta("Periodo: desde")
                    OutlinedTextField(
                        value = desde, onValueChange = { desde = it; cambio() },
                        placeholder = { Text("dd/mm/aaaa", fontSize = 13.sp) },
                        singleLine = true, colors = camposColores(), modifier = Modifier.fillMaxWidth()
                    )
                }
                Column(Modifier.weight(1f)) {
                    Etiqueta("Hasta")
                    OutlinedTextField(
                        value = hasta, onValueChange = { hasta = it; cambio() },
                        placeholder = { Text("dd/mm/aaaa", fontSize = 13.sp) },
                        singleLine = true, colors = camposColores(), modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Etiqueta("Tipo de incidencia")
                    Selector(tipo, listOf(TODAS) + TiposIncidencia, { tipo = it; cambio() })
                }
                Column(Modifier.weight(1f)) {
                    Etiqueta("Formato")
                    Selector(formato, listOf("PDF", "Excel"), { formato = it })
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (periodoOk) { generado = filtradas; mensaje = MensajeReporte.Generado }
                        else { generado = null; mensaje = MensajeReporte.PeriodoInvalido }
                    },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Azul, contentColor = Color.White),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text("Generar reporte", fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center) }

                OutlinedButton(
                    onClick = {
                        val datos = generado
                        if (datos == null) {
                            mensaje = MensajeReporte.GenerarPrimero
                        } else scope.launch {
                            try {
                                val archivo = withContext(Dispatchers.IO) {
                                    exportarReporte(context, datos, desde.trim(), hasta.trim(), tipo, formato)
                                }
                                compartirArchivo(context, archivo, formato)
                            } catch (e: Exception) {
                                mensaje = MensajeReporte.ErrorExportar
                            }
                        }
                    },
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, Azul),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Azul),
                    modifier = Modifier.weight(0.8f).height(48.dp)
                ) { Text("Exportar", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            }

            when (mensaje) {
                MensajeReporte.Generado -> Mensaje("Reporte generado correctamente.", OkFondo, OkBorde, OkTexto)
                MensajeReporte.PeriodoInvalido -> Mensaje("Periodo inválido: revise las fechas (dd/mm/aaaa).", ErrFondo, ErrBorde, ErrTexto)
                MensajeReporte.GenerarPrimero -> Mensaje("Primero genere el reporte para poder exportarlo.", ErrFondo, ErrBorde, ErrTexto)
                MensajeReporte.ErrorExportar -> Mensaje("No se pudo exportar el reporte.", ErrFondo, ErrBorde, ErrTexto)
                MensajeReporte.Ninguno -> {}
            }
        }

        // Vista previa: resumen por tipo y por gravedad del periodo elegido
        Text("Vista previa", fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
        if (filtradas.isEmpty()) {
            Text(if (periodoOk) "No hay incidencias en este periodo." else "Revise las fechas del periodo.",
                color = Gris, fontSize = 13.sp)
        } else {
            TablaResumen(resumirPorTipo(filtradas))
        }

        // Reporte detallado: aparece al generar; incluye nombre y matrícula del alumno
        generado?.let { detalle ->
            Text("Detalle de incidencias (${detalle.size})", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
            if (detalle.isEmpty()) {
                Text("No hay incidencias en este periodo.", color = Gris, fontSize = 13.sp)
            }
            detalle.forEach { inc ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(inc.alumno.nombre, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Matrícula ${inc.alumno.matricula}", fontSize = 12.sp, color = Gris)
                    Text(
                        "${formatearFecha(inc.fecha)} · ${inc.tipo} · Gravedad ${inc.gravedad.lowercase()}",
                        fontSize = 12.sp,
                        color = if (inc.gravedad == "Fuerte") ErrTexto else Color(0xFF222222)
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(BordeTarjeta))
            }
        }

        Text("RF-07 · Generación de reportes", color = ErrTexto, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
    }
}

/* ---------------- Tabla de resumen ---------------- */
@Composable
private fun TablaResumen(filas: List<ResumenTipo>) {
    Column(Modifier.fillMaxWidth().border(1.dp, BordeTarjeta)) {
        FilaTabla(listOf("Tipo", "Total", "Baja", "Media", "Fuerte"), cabecera = true)
        filas.forEach {
            FilaTabla(listOf(etiquetaCorta(it.tipo), "${it.total}", "${it.baja}", "${it.media}", "${it.fuerte}"))
        }
        FilaTabla(
            listOf("Total", "${filas.sumOf { it.total }}", "${filas.sumOf { it.baja }}",
                "${filas.sumOf { it.media }}", "${filas.sumOf { it.fuerte }}"),
            negrita = true
        )
    }
}

@Composable
private fun FilaTabla(celdas: List<String>, cabecera: Boolean = false, negrita: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().background(if (cabecera) Color(0xFFF0F2F4) else Color.White)
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        celdas.forEachIndexed { i, texto ->
            Text(
                texto, fontSize = 12.sp,
                fontWeight = if (cabecera || negrita) FontWeight.Bold else FontWeight.Normal,
                textAlign = if (i == 0) TextAlign.Start else TextAlign.Center,
                modifier = Modifier.weight(if (i == 0) 2f else 1f)
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(BordeTarjeta))
}

/* ---------------- Exportar (PDF o Excel) ----------------
 * "Excel" genera un archivo .csv, que Excel abre directamente.
 * El archivo se comparte con el menú de Android (guardar, enviar por correo/WhatsApp, etc.). */
private fun exportarReporte(context: Context, filas: List<Incidencia>, desde: String, hasta: String, tipo: String, formato: String): File {
    val carpeta = File(context.cacheDir, "reportes").apply { mkdirs() }
    val sello = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    return if (formato == "PDF") {
        File(carpeta, "reporte_incidencias_$sello.pdf").also { escribirPdf(it, lineasReporte(filas, desde, hasta, tipo)) }
    } else {
        File(carpeta, "reporte_incidencias_$sello.csv").also { escribirCsv(it, filas) }
    }
}

private fun compartirArchivo(context: Context, archivo: File, formato: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archivo)
    val envio = Intent(Intent.ACTION_SEND).apply {
        type = if (formato == "PDF") "application/pdf" else "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(envio, "Exportar reporte"))
}

private fun escribirCsv(archivo: File, filas: List<Incidencia>) {
    fun celda(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
    val sb = StringBuilder("﻿Fecha,Matrícula,Alumno,Tipo,Gravedad\n")
    filas.forEach {
        sb.append(listOf(formatearFecha(it.fecha), it.alumno.matricula, it.alumno.nombre, it.tipo, it.gravedad)
            .joinToString(",") { c -> celda(c) }).append('\n')
    }
    archivo.writeText(sb.toString(), Charsets.UTF_8)
}

private fun lineasReporte(filas: List<Incidencia>, desde: String, hasta: String, tipo: String): List<Pair<String, Boolean>> {
    val l = mutableListOf<Pair<String, Boolean>>()
    val resumen = resumirPorTipo(filas)
    fun fila(t: String, total: Any, b: Any, m: Any, f: Any) =
        t.take(37).padEnd(38) + "$total".padStart(6) + "$b".padStart(6) + "$m".padStart(7) + "$f".padStart(8)

    l += "SIRA - Reporte de incidencias" to true
    l += "Periodo: $desde al $hasta    Tipo: $tipo" to false
    l += "" to false
    l += "Resumen por tipo y gravedad" to true
    l += fila("Tipo", "Total", "Baja", "Media", "Fuerte") to false
    resumen.forEach { l += fila(it.tipo, it.total, it.baja, it.media, it.fuerte) to false }
    l += fila("TOTAL", resumen.sumOf { it.total }, resumen.sumOf { it.baja }, resumen.sumOf { it.media }, resumen.sumOf { it.fuerte }) to false
    l += "" to false
    l += "Detalle de incidencias (${filas.size})" to true
    l += "Fecha".padEnd(12) + "Matricula".padEnd(11) + "Alumno".padEnd(36) + "Tipo".padEnd(38) + "Gravedad" to false
    filas.forEach {
        l += formatearFecha(it.fecha).padEnd(12) + it.alumno.matricula.padEnd(11) +
                it.alumno.nombre.take(34).padEnd(36) + it.tipo.take(36).padEnd(38) + it.gravedad to false
    }
    return l
}

private fun escribirPdf(archivo: File, lineas: List<Pair<String, Boolean>>) {
    val doc = PdfDocument()
    val texto = Paint().apply { textSize = 7.5f; typeface = Typeface.MONOSPACE; isAntiAlias = true }
    val titulo = Paint().apply { textSize = 12f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
    fun nuevaPagina(n: Int) = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, n).create())

    var numero = 1
    var pagina = nuevaPagina(numero)
    var y = 50f
    for ((linea, esTitulo) in lineas) {
        if (y > 800f) {
            doc.finishPage(pagina)
            numero++
            pagina = nuevaPagina(numero)
            y = 50f
        }
        pagina.canvas.drawText(linea, 30f, y, if (esTitulo) titulo else texto)
        y += if (esTitulo) 18f else 12f
    }
    doc.finishPage(pagina)
    archivo.outputStream().use { doc.writeTo(it) }
    doc.close()
}