package com.dataturntable.security

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import java.nio.file.Files
import java.nio.file.Path

class SigningKeyAbsenceTest {

    @Test
    fun `no repository signing keys in main resources`() {
        val repoRoot = Path.of(System.getProperty("user.dir"))
        val mainResources = repoRoot.resolve("src").resolve("main").resolve("resources")

        val privateKey = mainResources.resolve("privateKey.pem")
        val publicKey = mainResources.resolve("publicKey.pem")

        assertFalse(Files.exists(privateKey), "Repository private signing key found at src/main/resources/privateKey.pem — remove it and use an external secret or environment mount")
        assertFalse(Files.exists(publicKey), "Repository public key found at src/main/resources/publicKey.pem — remove or relocate to test resources if needed")
    }
}
