package com.dataturntable

import io.quarkus.runtime.Quarkus
import io.quarkus.runtime.QuarkusApplication
import io.quarkus.runtime.annotations.QuarkusMain
import io.vertx.ext.web.Router

@QuarkusMain
class Main : QuarkusApplication {
    override fun run(args: Array<String>): Int {
        Quarkus.registerRouter { router: Router ->
            router.route("/test").handler { rc ->
                rc.response().end("Hello from Vert.x!")
            }
        }
        Quarkus.run(*args)
        return 0
    }
}
