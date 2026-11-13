package com.example.sira

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Pantalla de bienvenida: aparece al abrir la app y pasa sola al inicio de sesión (o al tocar la pantalla). */
@Composable
fun BienvenidaScreen(onContinuar: () -> Unit) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
        delay(2500)          // tiempo que se ve la bienvenida (milisegundos)
        onContinuar()
    }

    Box(
        Modifier.fillMaxSize().background(Color.White).systemBarsPadding()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onContinuar() },
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(visible = visible, enter = fadeIn(tween(700))) {
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Bienvenido a SIRA", color = Azul, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center)
                Text("Sistema de Incidencias y Respuestas Auxiliares", color = Gris, fontSize = 14.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(36.dp))
                Image(
                    painter = painterResource(LogoImagen),
                    contentDescription = "Logo SIRA",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(170.dp)
                )
            }
        }
        Text("Toca para continuar", color = Gris, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
    }
}