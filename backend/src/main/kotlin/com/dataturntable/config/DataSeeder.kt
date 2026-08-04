package com.dataturntable.config

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes

@ApplicationScoped
/**
 * Defines the class `DataSeeder`, the contract implemented by this source unit.
 */
class DataSeeder {
    
    /**
     * Initializes, connects, or acquires the named runtime resource.
     */
    fun onStart(@Observes ev: StartupEvent) {
        println("DataTurntable application started")
    }
}
