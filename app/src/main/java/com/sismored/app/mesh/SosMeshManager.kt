private var tiempoInicioMovimiento: Long = 0
    private var contadorPicos = 0
    private val DURACION_MINIMA_MS = 3000 // 3 segundos de movimiento sostenido
    private val PICOS_MINIMOS = 5 // al menos 5 picos de aceleración en ese tiempo

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
                            callbackSismoDetectado?.invoke()
                        }
                        tiempoInicioMovimiento = 0
                        contadorPicos = 0
                    }
                }
            } else {
                // Si pasan más de 1.5s sin picos, se reinicia la cuenta (fue un golpe aislado)
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
