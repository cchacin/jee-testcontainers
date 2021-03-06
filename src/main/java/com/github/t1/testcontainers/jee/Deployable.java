package com.github.t1.testcontainers.jee;

import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.github.t1.testcontainers.jee.JeeContainer.CLIENT;
import static java.util.concurrent.TimeUnit.SECONDS;
import static javax.ws.rs.core.Response.Status.OK;

@Slf4j
abstract class Deployable {

    abstract Path getLocalPath();

    abstract String getFileName();

    static Deployable create(URI deployable) {
        switch (scheme(deployable)) {
            case "file":
                return new FileDeployable(deployable);
            case "urn":
                return new UrnDeployable(deployable);
            default:
                return new UrlDeployable(deployable);
        }
    }

    private static String scheme(URI deployment) {
        String scheme = deployment.getScheme();
        return (scheme == null) ? "file" : scheme;
    }


    private static class FileDeployable extends Deployable {
        @Getter private final Path localPath;
        @Getter private final String fileName;

        private FileDeployable(URI deployment) {
            this.localPath = (deployment.getScheme() == null)
                ? Paths.get(deployment.toString()) : Paths.get(deployment);
            this.fileName = fileName(deployment);
        }
    }


    private static class UrnDeployable extends Deployable {
        private final String groupId;
        private final String artifactId;
        private final String version;
        private final String type;

        private UrnDeployable(URI deployment) {
            String[] split = deployment.getSchemeSpecificPart().split(":");
            if (!"mvn".equals(split[0]))
                throw new IllegalArgumentException("unsupported urn scheme '" + split[0] + "'");
            if (split.length != 5)
                throw new IllegalArgumentException("expected exactly 5 elements in 'mvn' urn '" + deployment + "': " +
                    "`urn:mvn:<group-id>:<artifact-id>:<version>:<type>`");
            this.groupId = split[1];
            this.artifactId = split[2];
            this.version = split[3];
            this.type = split[4];
        }

        @Override Path getLocalPath() {
            Path path = Paths.get(System.getProperty("user.home"))
                .resolve(".m2/repository")
                .resolve(groupId.replace('.', '/'))
                .resolve(artifactId)
                .resolve(version)
                .resolve(artifactId + "-" + version + "." + type);

            if (Files.notExists(path)) {
                download(groupId + ":" + artifactId + ":" + version + ":" + type);
            }

            return path;
        }

        @SneakyThrows({IOException.class, InterruptedException.class})
        private void download(String gavt) {
            ProcessBuilder builder = new ProcessBuilder("mvn", "dependency:get", "-Dartifact=" + gavt)
                .redirectErrorStream(true);
            Process process = builder.start();
            boolean inTime = process.waitFor(60, SECONDS);
            if (!inTime) {
                throw new RuntimeException("timeout download " + gavt);
            }
        }

        @Override String getFileName() {
            return artifactId + "." + type;
        }
    }


    private static class UrlDeployable extends Deployable {
        private final URI deployment;
        @Getter private final String fileName;

        private UrlDeployable(URI deployment) {
            this.deployment = deployment;
            this.fileName = fileName(deployment);
        }

        @Override Path getLocalPath() {
            return download(deployment);
        }

        @SneakyThrows(IOException.class)
        private Path download(URI deployment) {
            Path tempFile = Files.createTempDirectory("downloads").resolve(fileName);
            log.info("download " + deployment + " to " + tempFile);

            Response get = CLIENT.target(deployment).request().buildGet().invoke();
            if (get.getStatusInfo() != OK)
                throw new IllegalStateException("can't download " + deployment
                    + ": " + get.getStatus() + " " + get.getStatusInfo());
            InputStream inputStream = get.readEntity(InputStream.class);

            Files.copy(inputStream, tempFile);

            return tempFile;
        }
    }

    private static String fileName(URI uri) {
        Path path = Paths.get(uri.getSchemeSpecificPart());
        return path.getFileName().toString();
    }
}
