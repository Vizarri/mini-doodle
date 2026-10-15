package com.src.minidoodle.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.src.minidoodle.MiniDoodleApplication;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs two independent application JVMs with separate connection pools against the fixture database. */
public abstract class AbstractMultiInstanceIntegrationTest extends AbstractIntegrationTest {
    private static final Pattern STARTED_PORT = Pattern.compile("Tomcat started on port (\\d+)");
    private static HttpClient httpClient;
    @Autowired protected ObjectMapper objectMapper;
    protected static ApplicationInstance firstInstance;
    protected static ApplicationInstance secondInstance;

    /** Starts both production entry points before accepting test traffic; startup failure also stops the first JVM. */
    @BeforeAll
    protected static void startApplicationInstances(TestInfo testInfo) throws Exception {
        String testClassName = testInfo.getTestClass().orElseThrow().getSimpleName();
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        firstInstance = null;
        secondInstance = null;
        try {
            firstInstance = startInstance(testClassName, "first");
            secondInstance = startInstance(testClassName, "second");
            firstInstance = awaitReady(firstInstance);
            secondInstance = awaitReady(secondInstance);
            assertThat(firstInstance.process().pid()).isNotEqualTo(secondInstance.process().pid());
            assertThat(firstInstance.process().pid()).isNotEqualTo(ProcessHandle.current().pid());
            assertThat(secondInstance.process().pid()).isNotEqualTo(ProcessHandle.current().pid());
        } catch (Exception | AssertionError failure) {
            stopApplicationInstances();
            throw failure;
        }
    }

    /** Terminates child processes on success and failure, keeping their logs under target for diagnosis. */
    @AfterAll
    protected static void stopApplicationInstances() {
        try {
            stopInstance(secondInstance);
        } finally {
            try {
                stopInstance(firstInstance);
            } finally {
                if (httpClient != null) {
                    httpClient.close();
                    httpClient = null;
                }
            }
        }
    }

    /** Sends real HTTP traffic to the chosen JVM with bounded connection and response waits. */
    protected HttpResponse<String> send(ApplicationInstance instance, String method, String path, Object body)
            throws IOException, InterruptedException {
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body));
        HttpRequest request = HttpRequest.newBuilder(instance.baseUri().resolve(path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").method(method, publisher).build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Parses an HTTP response using the same timestamp and enum configuration as the application. */
    protected JsonNode json(HttpResponse<String> response) throws JsonProcessingException {
        return objectMapper.readTree(response.body());
    }

    /** Requires PostgreSQL to observe this JVM waiting on a calendar lock before the test releases its holder. */
    protected void awaitCalendarWait(ApplicationInstance instance, Future<?> request, long deadline) {
        while (System.nanoTime() < deadline) {
            Long waiting = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM pg_stat_activity
                    WHERE application_name = ? AND wait_event_type = 'Lock'
                      AND cardinality(pg_blocking_pids(pid)) > 0
                      AND LOWER(query) LIKE '%calendars%'
                    """, Long.class, instance.applicationName());
            if (waiting != null && waiting > 0) {
                return;
            }
            assertThat(request.isDone()).as("%s must wait for the held calendar lock", instance.applicationName()).isFalse();
            pause();
        }
        throw new AssertionError("No calendar lock wait observed for " + instance.applicationName());
    }

    private static ApplicationInstance startInstance(String testClassName, String label) throws IOException {
        String applicationName = "mini-doodle-" + testClassName + "-" + label;
        Path logFile = Path.of("target", "multi-instance", testClassName + "-" + label + ".log").toAbsolutePath();
        Files.createDirectories(logFile.getParent());
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<String> command = List.of(java, "-Xms64m", "-Xmx192m", "-XX:ActiveProcessorCount=2", "-cp", classpath,
                MiniDoodleApplication.class.getName(), "--server.address=127.0.0.1", "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(), "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(), "--spring.datasource.hikari.maximum-pool-size=4",
                "--spring.datasource.hikari.minimum-idle=1", "--spring.datasource.hikari.data-source-properties.ApplicationName=" + applicationName,
                "--spring.main.banner-mode=off", "--springdoc.api-docs.enabled=false", "--springdoc.swagger-ui.enabled=false",
                "--management.endpoints.web.exposure.include=health", "--spring.lifecycle.timeout-per-shutdown-phase=3s");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
        return new ApplicationInstance(applicationName, null, process, logFile);
    }

    private static ApplicationInstance awaitReady(ApplicationInstance instance) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            String output = Files.readString(instance.logFile());
            if (!instance.process().isAlive()) {
                throw new AssertionError(instance.applicationName() + " exited during startup:\n" + output);
            }
            Matcher port = STARTED_PORT.matcher(output);
            if (port.find()) {
                ApplicationInstance started = new ApplicationInstance(instance.applicationName(),
                        URI.create("http://127.0.0.1:" + port.group(1)), instance.process(), instance.logFile());
                try {
                    HttpRequest request = HttpRequest.newBuilder(started.baseUri().resolve("/actuator/health"))
                            .timeout(Duration.ofSeconds(3)).GET().build();
                    HttpResponse<String> health = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    if (health.statusCode() == 200) {
                        return started;
                    }
                } catch (IOException exception) {
                    // Tomcat can publish its port immediately before it starts accepting connections.
                }
            }
            pause();
        }
        throw new AssertionError(instance.applicationName() + " did not become healthy:\n" + Files.readString(instance.logFile()));
    }

    private static void pause() {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Interrupted while waiting for an application instance");
        }
    }

    private static void stopInstance(ApplicationInstance instance) {
        if (instance == null || !instance.process().isAlive()) {
            return;
        }
        instance.process().destroy();
        try {
            if (!instance.process().waitFor(10, TimeUnit.SECONDS)) {
                instance.process().destroyForcibly();
                assertThat(instance.process().waitFor(5, TimeUnit.SECONDS)).as("child JVM must terminate").isTrue();
            }
        } catch (InterruptedException exception) {
            instance.process().destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping " + instance.applicationName(), exception);
        }
    }

    /** Process identity, assigned HTTP endpoint and connection label used to distinguish database lock waiters. */
    protected record ApplicationInstance(String applicationName, URI baseUri, Process process, Path logFile) { }
}
