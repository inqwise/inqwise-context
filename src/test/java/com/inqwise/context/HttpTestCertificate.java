package com.inqwise.context;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.vertx.core.net.PfxOptions;

final class HttpTestCertificate {
    private HttpTestCertificate() {
    }

    static PfxOptions create(Path directory) throws Exception {
        String password = UUID.randomUUID().toString();
        Path log = directory.resolve("keytool.log");
        Path certificate = directory.resolve("server.p12");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
            "-genkeypair", "-alias", "localhost", "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", "CN=localhost", "-ext", "SAN=DNS:localhost,IP:127.0.0.1", "-validity", "2",
            "-storetype", "PKCS12", "-keystore", certificate.toString(),
            "-storepass", password, "-keypass", password, "-noprompt")
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Cannot terminate temporary certificate generation");
            }
            throw new IllegalStateException("Timed out generating the temporary test certificate");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Certificate generation failed:\n" + Files.readString(log));
        }
        return new PfxOptions().setPath(certificate.toString()).setPassword(password);
    }
}
