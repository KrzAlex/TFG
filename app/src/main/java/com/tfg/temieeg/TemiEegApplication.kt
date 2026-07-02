package com.tfg.temieeg

import android.app.Application

/**
 * Application class — punto de inicialización global.
 * De momento sin lógica; útil para registrar componentes
 * (loggers, DI) cuando crezca el proyecto.
 */
class TemiEegApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // TODO: inicializar componentes globales si fuera necesario.
    }
}
