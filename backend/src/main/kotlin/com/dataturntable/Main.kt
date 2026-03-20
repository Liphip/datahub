package com.dataturntable

import io.quarkus.runtime.Quarkus
import io.quarkus.runtime.QuarkusApplication
import io.quarkus.runtime.annotations.QuarkusMain

@QuarkusMain
class Main : QuarkusApplication {
    override fun run(args: Array<String>): Int {
        Quarkus.waitForExit()
        return 0
    }
}
