package com.sismored.app.mesh

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.ParcelUuid
import androidx.core.app.ActivityCompat
import java.util.UUID
import kotlin.math.sqrt

data class SenalVecino(
    val id: String,
    val latitud: Double,
    val longitud: Double,
    val necesitaAyuda: Boolean
)

class SosMeshManager(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000b00b-0000-1000-8000-00805f9b34fb")
        private const val UMBRAL_SISMO = 18f
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bleScanner: BluetoothLeScanner? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var escaneando = false

    private var miLatitud: Double = 0.0
    private var miLongitud: Double = 0.0

    private val vecinosDetectados = mutableMapOf<String, SenalVecino>()

    var onVecinosActualizados: ((Int) -> Unit)? = null
    var onSenalRecibida: ((SenalVecino) -> Unit)? = null
    var onSismoDetectado: (() -> Unit)? = null

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private var tiempoInicioMovimiento: Long = 0
    private var contadorPicos = 0
    private val DURACION_MINIMA_MS = 3000
    private val PICOS_MINIMOS = 5

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
            val magnitud = sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH

            if (magnitud > UMBRAL_SISMO) {
                val ahora = System.currentTimeMillis()
                if (tiempoInicioMovimiento == 0L) {
                    tiempoInicioMovimiento = ahora
                    contadorPicos = 1
                } else {
                    contadorPicos++
                    if (ahora - tiempoInicioMovimiento > DURACION_MINIMA_MS) {
                        if (contadorPicos >= PICOS_MINIMOS) {
                            onSismoDetectado?.invoke()
                        }
                        tiempoInicioMovimiento = 0
                        contadorPicos = 0
                    }
                }
            } else {
                if (tiempoInicioMovimiento != 0L &&
                    System.currentTimeMillis() - tiempoInicioMovimiento > 1500 &&
                    contadorPicos < PICOS_MINIMOS) {
                    tiempoInicioMovimiento = 0
                    contadorPicos = 0
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun activarDeteccionSismo() {
        val acelerometro = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager.registerListener(sensorListener, acelerometro, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun actualizarUbicacion(lat: Double, lon: Double) {
        miLatitud = lat
        miLongitud = lon
    }

    fun enviarSos(lat: Double, lon: Double) {
        actualizarUbicacion(lat, lon)
        emitirSenal(necesitaAyuda = true)
    }

    private fun tienePermisoBluetooth(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED &&
        ActivityCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_ADVERTISE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun construirPayload(necesitaAyuda: Boolean): ByteArray {
        val latInt = (miLatitud * 100000).toInt()
        val lonInt = (miLongitud * 100000).toInt()
        val estado: Byte = if (necesitaAyuda) 1 else 0
        val buffer = java.nio.ByteBuffer.allocate(9)
        buffer.putInt(latInt)
        buffer.putInt(lonInt)
        buffer.put(estado)
        return buffer.array()
    }

    private fun leerPayload(data: ByteArray): Triple<Double, Double, Boolean> {
        val buffer = java.nio.ByteBuffer.wrap(data)
        val lat = buffer.int / 100000.0
        val lon = buffer.int / 100000.0
        val ayuda = buffer.get().toInt() == 1
        return Triple(lat, lon, ayuda)
    }

    fun iniciarRed() {
        if (escaneando || !tienePermisoBluetooth()) return
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        bleScanner?.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),
            scanCallback
        )
        escaneando = true
        activarDeteccionSismo()
        emitirSenal(necesitaAyuda = false)
    }

    private fun emitirSenal(necesitaAyuda: Boolean) {
        if (!tienePermisoBluetooth()) return
        bleAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addServiceData(ParcelUuid(SERVICE_UUID), construirPayload(necesitaAyuda))
            .build()

        bleAdvertiser?.stopAdvertising(advertiseCallback)
        bleAdvertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {}

    fun detenerRed() {
        if (tienePermisoBluetooth()) {
            bleScanner?.stopScan(scanCallback)
            bleAdvertiser?.stopAdvertising(advertiseCallback)
        }
        sensorManager.unregisterListener(sensorListener)
        escaneando = false
        vecinosDetectados.clear()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val datos = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
            val (lat, lon, ayuda) = leerPayload(datos)
            val id = result.device.address
            val senal = SenalVecino(id, lat, lon, ayuda)
            vecinosDetectados[id] = senal
            onVecinosActualizados?.invoke(vecinosDetectados.size)
            onSenalRecibida?.invoke(senal)
        }
    }
}
