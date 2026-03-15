package com.dataturntable

import jakarta.ws.rs.ApplicationPath
import jakarta.ws.rs.core.Application

@ApplicationPath("/api/internal/v1")
class RestApplication : Application()
