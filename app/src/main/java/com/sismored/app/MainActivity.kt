package com.sismored.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import com.sismored.app.mesh.SenalVecino
import com.sismored.app.mesh.SosForegroundService
import com.sismored.app.mesh.SosMeshManager
import com.sismored.app.ui.theme.*
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private lateinit var meshManager: SosMeshManager
    private var ultimaUbicacion: Location? = null

    private val permisosNecesarios: Array<String>
        get() {
            val lista = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                lista += Manifest.permission.BLUETOOTH_SCAN
                lista += Manifest.permission.BLUETOOTH_ADVERTISE
                lista += Manifest.permission.BLUETOOTH_CONNECT
                lista += Manifest.permission.NEARBY_WIFI_DEVICES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                lista += Manifest.permission.POST_NOTIFICATIONS
            }
            return lista.toTypedArray()
        }

    private val estadoPermisos = mutableStateOf(false)
    private val vecinosConectados = mutableStateOf(0)
    private val sosActivo = mutableStateOf(false)
    private val listaVecinos = mutableStateListOf<SenalVecino>()

    private val lanzadorPermisos = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { resultados ->
        val todosConcedidos = resultados.values.all { it }
        estadoPermisos.value = todosConcedidos
        if (todosConcedidos) iniciarRedYServicio()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        meshManager = SosMeshManager(applicationContext)
        meshManager.onVecinosActualizados = { cantidad -> vecinosConectados.value = cantidad }
        meshManager.onSenalRecibida = { senal ->
            val index = listaVecinos.indexOfFirst { it.id == senal.id }
            if (index >= 0) listaVecinos[index] = senal else listaVecinos.add(senal)
        }
        meshManager.onSismoDetectado = { enviarSos() }

        setContent {
            SismoRedTheme {
                PantallaPrincipal(
                    permisosConcedidos = estadoPermisos.value,
                    vecinos = vecinosConectados.value,
                    listaVecinos = listaVecinos,
                    sosActivo = sosActivo.value,
                    onPedirPermisos = { lanzadorPermisos.launch(permisosNecesarios) },
                    onEnviarSos = { enviarSos() }
                )
            }
        }

        if (permisosYaConcedidos()) {
            estadoPermisos.value = true
            iniciarRedYServicio()
        }
    }

    private fun permisosYaConcedidos(): Boolean =
        permisosNecesarios.all {
            ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun iniciarRedYServicio() {
        meshManager.iniciarRed()
        startForegroundService(Intent(this, SosForegroundService::class.java))
        obtenerUltimaUbicacion()
    }

    private fun obtenerUltimaUbicacion() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        LocationServices.getFusedLocationProviderClient(this).lastLocation
            .addOnSuccessListener { ubicacion -> ultimaUbicacion = ubicacion }
    }

    private fun enviarSos() {
        val lat = ultimaUbicacion?.latitude ?: 0.0
        val lon = ultimaUbicacion?.longitude ?: 0.0
        meshManager.enviarSos(lat, lon)
        sosActivo.value = true
    }

    override fun onDestroy() {
        meshManager.detenerRed()
        super.onDestroy()
    }
}

@Composable
fun PantallaPrincipal(
    permisosConcedidos: Boolean,
    vecinos: Int,
    listaVecinos: List<SenalVecino>,
    sosActivo: Boolean,
    onPedirPermisos: () -> Unit,
    onEnviarSos: () -> Unit
) {
    if (!permisosConcedidos) {
        Scaffold(containerColor = Fondo) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(24.dp))
                Encabezado("SISMORED", "Faltan permisos")
                TarjetaPermisos(onPedirPermisos)
            }
        }
        return
    }

    var tabActual by remember { mutableStateOf("inicio") }
    var mostrarPantallaSos by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Fondo,
        bottomBar = {
            if (!mostrarPantallaSos) {
                BarraTabs(tabActual) { tabActual = it }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            if (mostrarPantallaSos) {
                PantallaSos(onVolver = { mostrarPantallaSos = false })
            } else {
                when (tabActual) {
                    "inicio" -> PantallaInicio(vecinos, listaVecinos, sosActivo) {
                        onEnviarSos()
                        mostrarPantallaSos = true
                    }
                    "red" -> PantallaRed(listaVecinos)
                    "mapa" -> PantallaMapa(listaVecinos)
                    "ajustes" -> PantallaAjustes()
                }
            }
        }
    }
}

@Composable
private fun Encabezado(eyebrow: String, titulo: String, colorEyebrow: Color = Verde) {
    Text(eyebrow, color = colorEyebrow, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 2.sp)
    Spacer(Modifier.height(2.dp))
    if (titulo.isNotEmpty()) {
        Text(titulo, color = Texto, fontWeight = FontWeight.Bold, fontSize = 26.sp)
    }
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun TarjetaPermisos(onPedirPermisos: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Superficie, RoundedCornerShape(18.dp))
            .padding(20.dp)
    ) {
        Text(
            "SismoRed necesita permisos de ubicación y Bluetooth para formar la red de emergencia con otros teléfonos cercanos.",
            color = TextoTenue, fontSize = 14.sp
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onPedirPermisos,
            colors = ButtonDefaults.buttonColors(containerColor = Verde),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Activar red de emergencia", color = Fondo, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PantallaInicio(
    vecinos: Int,
    listaVecinos: List<SenalVecino>,
    sosActivo: Boolean,
    onEnviarSos: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Encabezado("SISMORED", if (vecinos > 0) "Red activa" else "Sin señal celular")

        Radar(vecinos)

        Spacer(Modifier.height(14.dp))
        Text(
            "Escuchando en Bluetooth\nlisto para retransmitir tu SOS",
            color = TextoTenue, fontSize = 13.sp, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onEnviarSos,
            enabled = !sosActivo,
            colors = ButtonDefaults.buttonColors(containerColor = Rojo, disabledContainerColor = Rojo.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        ) {
            Text(
                if (sosActivo) "SOS ENVIADO" else "ENVIAR SOS",
                color = Texto, fontWeight = FontWeight.Bold, fontSize = 18.sp
            )
        }
        Spacer(Modifier.height(6.dp))
        Text("Se compartirá tu última ubicación conocida", color = TextoTenue, fontSize = 12.sp)

        Spacer(Modifier.height(18.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .background(Superficie, RoundedCornerShape(18.dp))
                .padding(16.dp)
        ) {
            Text("Vecinos detectados", color = TextoTenue, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            if (listaVecinos.isEmpty()) {
                Text("Aún no se detecta nadie cerca", color = TextoTenue, fontSize = 13.sp)
            } else {
                listaVecinos.forEach { vecino ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (vecino.necesitaAyuda) "🆘" else "✅", fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Lat: ${"%.4f".format(vecino.latitud)}, Lon: ${"%.4f".format(vecino.longitud)}",
                            color = Texto, fontSize = 13.sp
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Radar(vecinos: Int) {
    Box(modifier = Modifier.size(220.dp).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {

        Box(
            Modifier.size(144.dp).clip(CircleShape).border(1.dp, VerdeTenue, CircleShape)
        )

        repeat(3) { index ->
            val infinite = rememberInfiniteTransition(label = "pulse$index")
            val anim by infinite.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2600, easing = LinearOutSlowInEasing, delayMillis = index * 900)
                ),
                label = "pulseVal$index"
            )
            val tamano = 220.dp * (0.28f + anim * 0.72f)
            val alfa = (1f - anim) * 0.9f
            Box(
                Modifier.size(tamano).clip(CircleShape).border(1.dp, Verde.copy(alpha = alfa), CircleShape)
            )
        }

        Box(
            Modifier.size(64.dp).clip(CircleShape).background(Verde),
            contentAlignment = Alignment.Center
        ) {
            Text("$vecinos", color = Fondo, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        }

        val posiciones = listOf(
            Pair((-70).dp, (-80).dp),
            Pair(80.dp, (-40).dp),
            Pair((-90).dp, 60.dp)
        )
        posiciones.forEachIndexed { i, (x, y) ->
            val activo = i < vecinos
            Box(
                Modifier
                    .offset(x = x, y = y)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (activo) Superficie2 else Superficie)
                    .border(2.dp, if (activo) Verde else TextoTenue, CircleShape)
            )
        }
    }
}

@Composable
private fun PantallaSos(onVolver: () -> Unit) {
    val pasos = listOf(
        "Mensaje creado en tu teléfono" to 0,
        "Retransmitido por Vecino_A2F" to 900,
        "Retransmitido por Vecino_91C" to 2200,
        "Vecino_91C detectó señal celular" to 3600,
        "SOS enviado por SMS a Cruz Roja" to 4600
    )
    var revelados by remember { mutableStateOf(0) }
    var confirmado by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        var anterior = 0
        pasos.forEachIndexed { i, (_, tiempo) ->
            delay((tiempo - anterior).toLong())
            anterior = tiempo
            revelados = i + 1
        }
        delay(800)
        confirmado = true
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(24.dp))
        Encabezado("SOS EN CURSO", "Buscando salida a internet", colorEyebrow = Rojo)

        pasos.forEachIndexed { i, (texto, _) ->
            val lit = i < revelados
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape).background(if (lit) Verde else Linea)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    texto,
                    color = if (lit) Texto else TextoTenue,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (confirmado) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().background(Superficie, RoundedCornerShape(18.dp)).padding(16.dp)
            ) {
                Text("✓ Confirmado — ayuda en camino", color = Verde, fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onVolver) {
            Text("Volver al inicio", color = TextoTenue)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun PantallaRed(listaVecinos: List<SenalVecino>) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(24.dp))
        Encabezado("RED MESH", "Dispositivos cerca")

        Column(
            Modifier.fillMaxWidth().background(Superficie, RoundedCornerShape(18.dp)).padding(16.dp)
        ) {
            if (listaVecinos.isEmpty()) {
                Text("Sin dispositivos detectados todavía", color = TextoTenue, fontSize = 14.sp)
            } else {
                listaVecinos.forEach { vecino ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Superficie2)
                                .border(1.dp, Linea, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(vecino.id.takeLast(3), color = Verde, fontSize = 11.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Vecino_${vecino.id.takeLast(3)}", color = Texto, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (vecino.necesitaAyuda) "🆘 pidiendo ayuda" else "1 salto · Bluetooth",
                                color = TextoTenue, fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Encabezado("ESTADO DEL ENLACE", "", colorEyebrow = TextoTenue)
        Column(
            Modifier.fillMaxWidth().background(Superficie, RoundedCornerShape(18.dp)).padding(16.dp)
        ) {
            Row(Modifier.fillMaxWidth()) {
                Text("Dispositivos activos", color = TextoTenue, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("${listaVecinos.size}", color = Texto, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun PantallaMapa(listaVecinos: List<SenalVecino>) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(24.dp))
        Encabezado("MAPA SIN CONEXIÓN", "Auxilios cercanos")

        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(Superficie)
                .border(1.dp, Linea, RoundedCornerShape(18.dp))
        ) {
            val ancho = maxWidth
            val alto = maxHeight

            Box(
                Modifier
                    .offset(x = ancho * 0.48f - 8.dp, y = alto * 0.5f - 8.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Verde)
            )

            val posiciones = listOf(Pair(0.68f, 0.26f), Pair(0.22f, 0.68f), Pair(0.8f, 0.72f))
            listaVecinos.filter { it.necesitaAyuda }.take(3).forEachIndexed { i, _ ->
                val (px, py) = posiciones[i]
                Box(
                    Modifier
                        .offset(x = ancho * px - 7.dp, y = alto * py - 7.dp)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Rojo)
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Column(
            Modifier.fillMaxWidth().background(Superficie, RoundedCornerShape(18.dp)).padding(16.dp)
        ) {
            Row(Modifier.fillMaxWidth()) {
                Text("● Tú", color = TextoTenue, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("en línea", color = Verde, fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "▲ ${listaVecinos.count { it.necesitaAyuda }} solicitudes de ayuda",
                    color = TextoTenue, fontSize = 13.sp, modifier = Modifier.weight(1f)
                )
                Text("sin atender", color = Rojo, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun PantallaAjustes() {
    var redActiva by remember { mutableStateOf(true) }
    var compartirUbicacion by remember { mutableStateOf(true) }
    var ahorroBateria by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(24.dp))
        Encabezado("AJUSTES", "Configuración")

        Column(
            Modifier.fillMaxWidth().background(Superficie, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp)
        ) {
            FilaAjuste("Red de emergencia", "Bluetooth siempre listo", redActiva) { redActiva = it }
            FilaAjuste("Compartir ubicación con la red", "Solo durante un SOS activo", compartirUbicacion) { compartirUbicacion = it }
            FilaAjuste("Ahorro de batería", "Reduce el escaneo cuando la batería es < 20%", ahorroBateria) { ahorroBateria = it }
        }

        Spacer(Modifier.height(22.dp))
        Encabezado("CONTACTO DE EMERGENCIA", "", colorEyebrow = TextoTenue)
        Column(
            Modifier.fillMaxWidth().background(Superficie, RoundedCornerShape(18.dp)).padding(16.dp)
        ) {
            Row(Modifier.fillMaxWidth()) {
                Text("Nombre", color = TextoTenue, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("Sin configurar", color = Texto, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun FilaAjuste(titulo: String, sub: String, valor: Boolean, onCambio: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(titulo, color = Texto, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = TextoTenue, fontSize = 12.sp)
        }
        Switch(
            checked = valor,
            onCheckedChange = onCambio,
            colors = SwitchDefaults.colors(checkedTrackColor = Verde, checkedThumbColor = Fondo)
        )
    }
}

@Composable
private fun BarraTabs(actual: String, onCambiar: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Superficie)
            .border(width = 1.dp, color = Linea)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        TabItem("inicio", "🏠", "Inicio", actual, onCambiar)
        TabItem("red", "📡", "Red", actual, onCambiar)
        TabItem("mapa", "🗺️", "Mapa", actual, onCambiar)
        TabItem("ajustes", "⚙️", "Ajustes", actual, onCambiar)
    }
}

@Composable
private fun TabItem(id: String, icono: String, texto: String, actual: String, onCambiar: (String) -> Unit) {
    val activo = actual == id
    Column(
        Modifier
            .clickable { onCambiar(id) }
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icono, fontSize = 18.sp)
        Spacer(Modifier.height(2.dp))
        Text(texto, color = if (activo) Verde else TextoTenue, fontSize = 10.sp)
    }
}
