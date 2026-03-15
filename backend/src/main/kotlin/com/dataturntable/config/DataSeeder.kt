package com.dataturntable.config

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes

@ApplicationScoped
class DataSeeder {
    
    fun onStart(@Observes ev: StartupEvent) {
        println("DataTurntable application started")
    }
}
