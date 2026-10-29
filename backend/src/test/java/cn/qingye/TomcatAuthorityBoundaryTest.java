package cn.qingye;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.http2.Http2Protocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Real loopback TLS/h2 connector; no application data or external host is contacted. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TomcatAuthorityBoundaryTest {
    private static final String HOST = "public.example.invalid";
    private static final String FIXTURE_PASSWORD = "fixture-only-password";
    @TempDir static Path directory;
    private Tomcat tomcat;
    private SSLContext clientTls;
    private final AtomicInteger dispatched = new AtomicInteger();

    @BeforeAll void start() throws Exception {
        Path keystore = directory.resolve("fixture.p12");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-dname", "CN=" + HOST, "-ext", "SAN=dns:" + HOST,
                "-storetype", "PKCS12", "-keystore", keystore.toString(),
                "-storepass", FIXTURE_PASSWORD, "-keypass", FIXTURE_PASSWORD, "-noprompt")
                .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("test certificate generation timed out");
        }
        assertThat(process.exitValue()).as("test certificate generation").isZero();
        var keys = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(keystore)) { keys.load(in, FIXTURE_PASSWORD.toCharArray()); }
        var trusted = KeyStore.getInstance("PKCS12");
        trusted.load(null, null);
        trusted.setCertificateEntry("fixture", keys.getCertificate("fixture"));
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trusted);
        clientTls = SSLContext.getInstance("TLS");
        clientTls.init(null, trust.getTrustManagers(), null);

        tomcat = new Tomcat();
        tomcat.setBaseDir(directory.resolve("tomcat").toString());
        tomcat.setHostname("localhost");
        var connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setPort(0);
        connector.setScheme("https");
        connector.setSecure(true);
        assertThat(connector.setProperty("address", "127.0.0.1")).isTrue();
        assertThat(connector.setProperty("SSLEnabled", "true")).isTrue();
        assertThat(connector.setProperty("strictSni", "true")).isTrue();
        connector.addUpgradeProtocol(new Http2Protocol());
        // Distinct SSLHostConfig instances make a cross-host request fail strict SNI.
        for (String name : List.of("_default_", HOST)) {
            var config = new SSLHostConfig();
            config.setHostName(name);
            var certificate = new SSLHostConfigCertificate(config, SSLHostConfigCertificate.Type.RSA);
            certificate.setCertificateKeystore(keys);
            certificate.setCertificateKeystorePassword(FIXTURE_PASSWORD);
            config.addCertificate(certificate);
            connector.addSslHostConfig(config);
        }
        tomcat.setConnector(connector);
        Path root = Files.createDirectories(directory.resolve("webroot"));
        var context = tomcat.addContext("", root.toString());
        Tomcat.addServlet(context, "fixture", new HttpServlet() {
            @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
                dispatched.incrementAndGet();
                response.setContentType("text/plain");
                response.getWriter().write("fixture-ok");
            }
        });
        context.addServletMappingDecoded("/", "fixture");
        tomcat.start();
    }

    @AfterAll void stop() throws Exception {
        if (tomcat != null) {
            try { tomcat.stop(); } finally { tomcat.destroy(); }
        }
    }

    @Test void missingAuthorityIsRejectedBeforeServletDispatch() throws Exception {
        int before = dispatched.get();
        var result = request(null, null);
        assertThat(result.reset()).as("missing authority must produce PROTOCOL_ERROR, not %s", result).isEqualTo(1);
        assertThat(dispatched.get()).isEqualTo(before);
    }

    @Test void invalidAuthorityAndHostRepresentationsAreRejected() throws Exception {
        for (boolean authority : List.of(true, false)) {
            int before = dispatched.get();
            var result = request(authority ? "local!host" : null, authority ? null : "local!host");
            assertThat(result.reset()).as("invalid authority/Host: %s", result).isEqualTo(1);
            assertThat(dispatched.get()).isEqualTo(before);
        }
    }

    @Test void validAuthorityHostAndConsistentBothStillReachServlet() throws Exception {
        for (String[] fields : List.of(new String[]{HOST, null}, new String[]{null, HOST}, new String[]{HOST, HOST})) {
            int before = dispatched.get();
            var result = request(fields[0], fields[1]);
            assertThat(result.reset()).isEqualTo(-1);
            assertThat(result.body()).isEqualTo("fixture-ok");
            assertThat(dispatched.get()).isEqualTo(before + 1);
        }
    }

    @Test void explicitHostFromAnotherSniConfigurationIsRejected() throws Exception {
        int before = dispatched.get();
        var result = request("localhost", null);
        assertThat(result.reset()).as("cross-SNI response: %s", result).isEqualTo(1);
        assertThat(dispatched.get()).isEqualTo(before);
    }

    private record Result(int reset, String body) { }

    private Result request(String authority, String host) throws Exception {
        try (var tcp = new Socket()) {
            tcp.connect(new InetSocketAddress("127.0.0.1", tomcat.getConnector().getLocalPort()), 3000);
            try (var socket = (SSLSocket) clientTls.getSocketFactory().createSocket(tcp, HOST,
                    tomcat.getConnector().getLocalPort(), true)) {
                socket.setSoTimeout(3000);
                var parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setServerNames(List.of(new SNIHostName(HOST)));
                parameters.setApplicationProtocols(new String[]{"h2"});
                socket.setSSLParameters(parameters);
                socket.startHandshake();
                assertThat(socket.getApplicationProtocol()).isEqualTo("h2");
                var out = new DataOutputStream(socket.getOutputStream());
                out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                frame(out, 4, 0, 0, new byte[0]); // SETTINGS
                var headers = new ByteArrayOutputStream();
                headers.write(0x82); headers.write(0x87); headers.write(0x84); // GET, https, /
                if (authority != null) { headers.write(1); literal(headers, authority); }
                if (host != null) { headers.write(0); literal(headers, "host"); literal(headers, host); }
                frame(out, 1, 5, 1, headers.toByteArray()); // HEADERS, END_HEADERS | END_STREAM
                out.flush();
                var in = new DataInputStream(socket.getInputStream());
                var body = new ByteArrayOutputStream();
                for (int count = 0; count < 32; count++) {
                    int length = (in.readUnsignedByte() << 16) | in.readUnsignedShort();
                    assertThat(length).isLessThanOrEqualTo(16384);
                    int type = in.readUnsignedByte(), flags = in.readUnsignedByte(), stream = in.readInt();
                    byte[] payload = in.readNBytes(length);
                    assertThat(payload.length).isEqualTo(length);
                    if (type == 4 && (flags & 1) == 0) { frame(out, 4, 1, 0, new byte[0]); out.flush(); }
                    if (stream == 1) {
                        if (type == 3) return new Result(new DataInputStream(new ByteArrayInputStream(payload)).readInt(), "");
                        if (type == 0) body.write(payload);
                        if ((type == 0 || type == 1) && (flags & 1) != 0)
                            return new Result(-1, body.toString(StandardCharsets.UTF_8));
                    }
                    if (type == 7) throw new IOException("unexpected GOAWAY");
                }
                throw new IOException("HTTP/2 response exceeded bounded fixture frame count");
            }
        }
    }

    private static void literal(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        assertThat(bytes.length).isLessThan(127);
        out.write(bytes.length); out.writeBytes(bytes); // HPACK literal, no Huffman or dynamic table
    }

    private static void frame(DataOutputStream out, int type, int flags, int stream, byte[] payload) throws IOException {
        out.writeByte(payload.length >>> 16); out.writeShort(payload.length);
        out.writeByte(type); out.writeByte(flags); out.writeInt(stream); out.write(payload);
    }
}
