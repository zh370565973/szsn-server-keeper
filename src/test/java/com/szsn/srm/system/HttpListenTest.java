package com.szsn.srm.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.szsn.srm.SrmApplication;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.context.ConfigurableApplicationContext;

class HttpListenTest {
    @Test
    void plainHttpListensOnAllInterfacesWithoutCertificate() throws Exception {
        Path dir = Files.createTempDirectory("srm-http-listen");
        String dataDir = dir.resolve("data").toString().replace('\\', '/');
        String absentConfig = dir.resolve("missing-application.yml").toString().replace('\\', '/');
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SrmApplication.class)
                .run(
                        "--server.address=0.0.0.0",
                        "--server.port=0",
                        "--server.ssl.enabled=false",
                        "--srm.remote.cidr=",
                        "--srm.deploy.config=" + absentConfig,
                        "--srm.data-dir=" + dataDir,
                        "--srm.admin.password=http-listen-pass-1",
                        "--spring.datasource.url=jdbc:h2:mem:httplisten;DB_CLOSE_DELAY=-1;AUTO_SERVER=FALSE",
                        "--logging.file.name=" + dir.resolve("test.log").toString().replace('\\', '/'))) {
            String port = context.getEnvironment().getProperty("local.server.port");
            assertNotNull(port);
            TomcatWebServer tomcat = (TomcatWebServer) ((WebServerApplicationContext) context).getWebServer();
            String bound = String.valueOf(tomcat.getTomcat().getConnector().getProperty("address"));
            if (bound.startsWith("/")) {
                bound = bound.substring(1);
            }
            assertEquals("0.0.0.0", bound);

            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            assertEquals(200, get(client, "127.0.0.1", port));
            InetAddress external = firstExternalIpv4();
            if (external != null) {
                assertEquals(200, get(client, external.getHostAddress(), port));
            }
        }
    }

    private static int get(HttpClient client, String host, String port) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + "/api/v1/auth/csrf"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static InetAddress firstExternalIpv4() throws Exception {
        var interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface nic = interfaces.nextElement();
            if (!nic.isUp() || nic.isLoopback()) {
                continue;
            }
            var addresses = nic.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                    return address;
                }
            }
        }
        return null;
    }
}
