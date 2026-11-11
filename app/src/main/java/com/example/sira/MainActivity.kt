package com.example.sira

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var sesionIniciada by rememberSaveable { mutableStateOf(false) }
                var seccion by rememberSaveable { mutableStateOf(Seccion.Panel) }

                if (!sesionIniciada) {
                    // Después de iniciar sesión se entra al Panel.
                    LoginScreen(onExito = { seccion = Seccion.Panel; sesionIniciada = true })
                } else {
                    val cerrarSesion = { sesionIniciada = false }
                    when (seccion) {
                        Seccion.Panel -> PanelScreen(onNavegar = { seccion = it }, onCerrarSesion = cerrarSesion)
                        Seccion.Incidencias -> IncidenciaScreen(onNavegar = { seccion = it }, onCerrarSesion = cerrarSesion)
                        Seccion.Reportes -> ReportesScreen(onNavegar = { seccion = it }, onCerrarSesion = cerrarSesion)
                    }
                }
            }
        }
    }
}