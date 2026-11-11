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
        ServiciosExpedientes.inicializar(applicationContext)
        setContent {
            MaterialTheme {
                var sesionIniciada by rememberSaveable { mutableStateOf(false) }
                var seccion by rememberSaveable { mutableStateOf(Seccion.Panel) }
                var recuperando by rememberSaveable { mutableStateOf(false) }
                var probarNuevaPassword by rememberSaveable { mutableStateOf(false) }
                var rolNombre by rememberSaveable { mutableStateOf(RolDemo.Director.name) }
                var correoSesion by rememberSaveable { mutableStateOf("") }
                val rolActual = RolDemo.entries.firstOrNull { it.name == rolNombre } ?: RolDemo.Director

                if (!sesionIniciada) {
                    if (probarNuevaPassword) {
                        NuevaPasswordScreen(onVolver = { probarNuevaPassword = false })
                    } else if (recuperando) {
                        // I-02 Recuperar contraseña (también responde al botón Atrás del sistema).
                        RecuperarScreen(
                            onVolver = { recuperando = false },
                            onAbrirNuevaPasswordDemo = { probarNuevaPassword = true }
                        )
                    } else {
                        // Después de iniciar sesión se entra al Panel.
                        LoginScreen(
                            onExito = { rol, correo ->
                                rolNombre = rol.name
                                correoSesion = correo
                                seccion = when (rol) {
                                    RolDemo.Administrador -> Seccion.GestionUsuarios
                                    RolDemo.Tutor -> Seccion.TutorIncidencias
                                    else -> Seccion.Panel
                                }
                                sesionIniciada = true
                            },
                            onOlvido = { recuperando = true }
                        )
                    }
                } else {
                    val cerrarSesion = { sesionIniciada = false; seccion = Seccion.Panel }
                    val navegar: (Seccion) -> Unit = { destino ->
                        if (destino.permitidaPara(rolActual)) seccion = destino
                    }
                    when (seccion) {
                        Seccion.Panel -> PanelScreen(onNavegar = navegar, onCerrarSesion = cerrarSesion, rol = rolActual)
                        Seccion.Incidencias -> IncidenciaScreen(onNavegar = navegar, onCerrarSesion = cerrarSesion, rol = rolActual)
                        Seccion.ConsultaIncidencias -> ConsultaIncidenciasScreen(
                            onNavegar = navegar,
                            onCerrarSesion = cerrarSesion,
                            rol = rolActual
                        )
                        Seccion.Reportes -> ReportesScreen(onNavegar = navegar, onCerrarSesion = cerrarSesion, rol = rolActual)
                        Seccion.HistorialMedico -> HistorialMedicoScreen(onNavegar = navegar, onCerrarSesion = cerrarSesion)
                        Seccion.RegistroExpediente -> RegistrarExpedienteScreen(onNavegar = navegar, onCerrarSesion = cerrarSesion)
                        Seccion.GestionTutores -> GestionTutoresScreen(onNavegar = navegar, onCerrarSesion = cerrarSesion)
                        Seccion.GestionUsuarios -> GestionUsuariosScreen(onNavegar = navegar, onCerrarSesion = cerrarSesion)
                        Seccion.Bitacora -> BitacoraScreen(onNavegar = navegar, onCerrarSesion = cerrarSesion)
                        Seccion.Notificaciones -> NotificacionesScreen(
                            onNavegar = navegar,
                            onCerrarSesion = cerrarSesion,
                            correoTutor = correoSesion.takeIf { rolActual == RolDemo.Tutor },
                            rol = rolActual
                        )
                        Seccion.TutorIncidencias -> TutorIncidenciasScreen(
                            correoTutor = correoSesion,
                            onNavegar = navegar,
                            onCerrarSesion = cerrarSesion
                        )
                    }
                }
            }
        }
    }
}
