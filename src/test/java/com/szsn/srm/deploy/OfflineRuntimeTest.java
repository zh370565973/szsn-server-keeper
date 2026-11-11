package com.szsn.srm.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 确认页面、依赖名和离线检查脚本不会去下载 npm、Maven、字体、CDN 或发送遥测。
 * 假 java 不启动 Spring，也不做 CPU 或内存压力。
 */
class OfflineRuntimeTest {
    @Test
    void sourcesDoNotReferenceExternalHostsOrClients() throws Exception {
        String probe = Files.readString(repo().resolve("deploy/OfflineProbe.java"));
        String script = Files.readString(repo().resolve("deploy/verify-offline.sh"));
        assertFalse(probe.contains("\r"), "OfflineProbe.java 必须使用 LF");
        assertFalse(script.contains("\r"), "verify-offline.sh 必须使用 LF");
        assertTrue(script.contains("command -v npm"));
        assertTrue(script.contains("command -v mvn"));
        assertFalse(script.contains("curl"));
        assertFalse(script.contains("wget"));
        assertFalse(script.contains("apt-get"));
        assertFalse(script.contains("npm ci"));
        assertFalse(script.contains("npm install"));
        assertFalse(script.contains("mvn package"));

        List<String> hosts = between(probe, "BEGIN DENY_HOSTS", "END DENY_HOSTS");
        assertTrue(hosts.contains("fonts.googleapis"));
        assertTrue(hosts.contains("unpkg.com"));
        List<Path> roots = new ArrayList<>();
        roots.add(repo().resolve("frontend/src"));
        roots.add(repo().resolve("frontend/index.html"));
        Path built = repo().resolve("src/main/resources/static");
        if (Files.exists(built.resolve("index.html"))) {
            roots.add(built);
        }
        for (Path root : roots) {
            if (Files.isDirectory(root)) {
                try (Stream<Path> walk = Files.walk(root)) {
                    for (Path path : walk.filter(Files::isRegularFile).toList()) {
                        assertNoHost(path, hosts);
                    }
                }
            } else if (Files.isRegularFile(root)) {
                assertNoHost(root, hosts);
            }
        }
        try (Stream<Path> walk = Files.walk(repo().resolve("src/main/java"))) {
            for (Path path : walk.filter(file -> file.toString().endsWith(".java")).toList()) {
                String text = Files.readString(path);
                for (String token : List.of("java.net.http", "HttpURLConnection", "RestTemplate", "WebClient",
                        "RestClient", "DatagramSocket")) {
                    assertFalse(text.contains(token), path + " 含有 " + token);
                }
            }
        }
        String pom = Files.readString(repo().resolve("pom.xml"));
        for (String token : List.of("actuator", "sentry", "opentelemetry", "spring-cloud")) {
            assertFalse(pom.contains(token), token);
        }
    }

    @Test
    void verifierRefusesIncompleteInputAndDoesNotCallPackageManagers(@TempDir Path temp) throws Exception {
        Run missing = bash(repo().resolve("deploy/verify-offline.sh"), Map.of(), temp);
        assertEquals(2, missing.code, missing.output());

        Path occupied = temp.resolve("occupied");
        Files.createDirectories(occupied);
        Files.writeString(occupied.resolve("keep.txt"), "keep");
        Path present = temp.resolve("present.jar");
        Files.writeString(present, "jar");
        Run refused = bash(repo().resolve("deploy/verify-offline.sh"), Map.of("PATH", toolPath(temp.resolve("unused"))), temp,
                "--jar", unix(present), "--work", unix(occupied));
        assertEquals(2, refused.code, refused.output());
        assertTrue(refused.stderr.contains("工作目录必须为空"), refused.output());
        assertEquals("keep", Files.readString(occupied.resolve("keep.txt")));

        Path javaHome = temp.resolve("jdk");
        Path bin = javaHome.resolve("bin");
        Path decoy = temp.resolve("decoy");
        Files.createDirectories(bin);
        Files.createDirectories(decoy);
        Path log = temp.resolve("java.log");
        Path mark = temp.resolve("called.txt");
        Files.writeString(bin.resolve("java"), script("""
                #!/bin/bash
                printf 'java %s\\n' "$*" >> "$SRM_JAVA_LOG"
                exit 0
                """));
        Files.writeString(bin.resolve("javac"), script("""
                #!/bin/bash
                printf 'javac %s\\n' "$*" >> "$SRM_JAVA_LOG"
                exit 0
                """));
        Files.writeString(decoy.resolve("npm"), script("""
                #!/bin/bash
                printf 'npm\\n' >> "$SRM_DECOY_MARK"
                exit 99
                """));
        Files.writeString(decoy.resolve("mvn"), script("""
                #!/bin/bash
                printf 'mvn\\n' >> "$SRM_DECOY_MARK"
                exit 99
                """));
        Path bash = bash();
        Run chmod = bash(bash, Map.of(), temp, "-c",
                "chmod +x " + unix(bin.resolve("java")) + " " + unix(bin.resolve("javac")) + " "
                        + unix(decoy.resolve("npm")) + " " + unix(decoy.resolve("mvn")));
        assertEquals(0, chmod.code, chmod.output());

        Path jar = temp.resolve("app.jar");
        Files.writeString(jar, "not-a-real-jar");
        Path work = temp.resolve("work");
        Run isolated = bash(repo().resolve("deploy/verify-offline.sh"), Map.of(
                "JAVA_HOME", unix(javaHome),
                "PATH", toolPath(decoy),
                "SRM_JAVA_LOG", unix(log),
                "SRM_DECOY_MARK", unix(mark)), temp,
                "--jar", unix(jar), "--work", unix(work));
        assertEquals(0, isolated.code, isolated.output());
        assertFalse(Files.exists(mark), Files.exists(mark) ? Files.readString(mark) : "");
        String logged = Files.readString(log);
        assertTrue(logged.contains("javac "), logged);
        assertTrue(logged.contains("-encoding UTF-8"), logged);
        assertTrue(logged.contains("OfflineProbe"), logged);
        assertTrue(logged.contains("--jar"), logged);
        assertTrue(logged.contains("--work"), logged);
        assertFalse(logged.contains("npm"));
        assertFalse(logged.contains("mvn"));
    }

    @Test
    void scannerRejectsExternalUiAndTelemetryJars(@TempDir Path temp) throws Exception {
        Path classes = temp.resolve("classes");
        Files.createDirectories(classes);
        Run compiled = run(List.of(tool("javac"), "-encoding", "UTF-8", "-d", classes.toString(),
                repo().resolve("deploy/OfflineProbe.java").toString()), Map.of(), temp, 60);
        assertEquals(0, compiled.code, compiled.output());

        Path clean = temp.resolve("clean.jar");
        jar(clean, Map.of(
                "BOOT-INF/classes/static/index.html", "<script src=\"/assets/app.js\"></script>\n".getBytes(StandardCharsets.UTF_8),
                "BOOT-INF/classes/static/assets/app.js", "console.log('local');\n".getBytes(StandardCharsets.UTF_8),
                "BOOT-INF/lib/h2-2.3.232.jar", new byte[0]));
        Run ok = probe(classes, temp, "--scan-jar", clean.toString());
        assertEquals(0, ok.code, ok.output());
        assertTrue(ok.stdout.contains("scan_ok"));

        Path font = temp.resolve("font.jar");
        jar(font, Map.of(
                "BOOT-INF/classes/static/index.html", "<script src=\"/assets/app.js\"></script>\n".getBytes(StandardCharsets.UTF_8),
                "BOOT-INF/classes/static/assets/app.js", "/* fonts.googleapis.com */\n".getBytes(StandardCharsets.UTF_8)));
        Run remoteFont = probe(classes, temp, "--scan-jar", font.toString());
        assertEquals(1, remoteFont.code, remoteFont.output());
        assertTrue(remoteFont.stderr.contains("fonts.googleapis"));

        Path link = temp.resolve("link.jar");
        jar(link, Map.of(
                "BOOT-INF/classes/static/index.html",
                "<link rel=\"stylesheet\" href=\"https://example.com/a.css\">\n".getBytes(StandardCharsets.UTF_8)));
        Run remoteLink = probe(classes, temp, "--scan-jar", link.toString());
        assertEquals(1, remoteLink.code, remoteLink.output());
        assertTrue(remoteLink.stderr.contains("外站"));

        Path telemetry = temp.resolve("telemetry.jar");
        jar(telemetry, Map.of(
                "BOOT-INF/classes/static/index.html", "<script src=\"/assets/app.js\"></script>\n".getBytes(StandardCharsets.UTF_8),
                "BOOT-INF/lib/sentry-1.0.jar", new byte[0]));
        Run remoteLib = probe(classes, temp, "--scan-jar", telemetry.toString());
        assertEquals(1, remoteLib.code, remoteLib.output());
        assertTrue(remoteLib.stderr.contains("sentry"));

        Path empty = temp.resolve("empty.jar");
        jar(empty, Map.of("BOOT-INF/lib/h2-2.3.232.jar", new byte[0]));
        Run missingPage = probe(classes, temp, "--scan-jar", empty.toString());
        assertEquals(1, missingPage.code, missingPage.output());
        assertTrue(missingPage.stderr.contains("首页"));
    }

    private static void assertNoHost(Path path, List<String> hosts) throws Exception {
        String name = path.getFileName().toString().toLowerCase();
        if (!(name.endsWith(".html") || name.endsWith(".js") || name.endsWith(".css") || name.endsWith(".vue")
                || name.endsWith(".ts"))) {
            return;
        }
        String text = Files.readString(path).toLowerCase();
        for (String host : hosts) {
            assertFalse(text.contains(host), path + " 含有 " + host);
        }
        if (name.endsWith(".html") || name.endsWith(".vue")) {
            assertFalse(text.contains("src=\"http"), path.toString());
            assertFalse(text.contains("href=\"http"), path.toString());
        }
    }

    private static List<String> between(String text, String begin, String end) {
        int start = text.indexOf(begin);
        int stop = text.indexOf(end);
        assertTrue(start >= 0 && stop > start);
        Matcher matcher = Pattern.compile("\"([^\"]+)\"").matcher(text.substring(start, stop));
        List<String> values = new ArrayList<>();
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private static void jar(Path path, Map<String, byte[]> entries) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }

    private static Run probe(Path classes, Path directory, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(tool("java"));
        command.add("-Dfile.encoding=UTF-8");
        command.add("-cp");
        command.add(classes.toString());
        command.add("OfflineProbe");
        command.addAll(List.of(args));
        return run(command, Map.of(), directory, 30);
    }

    private static String tool(String name) {
        String file = System.getProperty("os.name", "").toLowerCase().contains("win") ? name + ".exe" : name;
        return Path.of(System.getProperty("java.home"), "bin", file).toString();
    }

    private static Run bash(Path program, Map<String, String> env, Path directory, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        Path bash = program.getFileName().toString().toLowerCase().contains("bash") ? program : bash();
        command.add(bash.toString());
        if (program.getFileName().toString().toLowerCase().contains("bash")) {
            command.addAll(List.of(args));
        } else {
            command.add(unix(program));
            command.addAll(List.of(args));
        }
        return run(command, env, directory, 30);
    }

    private static String toolPath(Path prepend) throws Exception {
        Path shell = bash();
        Path usrBin = shell.getParent().getParent().resolve("usr").resolve("bin");
        return unix(prepend) + java.io.File.pathSeparator + usrBin + java.io.File.pathSeparator
                + shell.getParent() + java.io.File.pathSeparator + System.getenv().getOrDefault("PATH", "");
    }

    private static Path bash() throws Exception {
        String override = System.getenv("SRM_BASH");
        if (override != null && !override.isBlank() && Files.isExecutable(Path.of(override))) {
            return Path.of(override);
        }
        List<String> found = new ArrayList<>();
        try {
            Process process = new ProcessBuilder(System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? List.of("where.exe", "git")
                    : List.of("sh", "-c", "command -v bash"))
                    .redirectErrorStream(true)
                    .start();
            String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) {
                for (String line : text.split("\\R")) {
                    if (!line.isBlank()) {
                        found.add(line.trim());
                    }
                }
            }
        } catch (Exception ignored) {
            // 下面还有固定候选路径。
        }
        List<Path> candidates = new ArrayList<>();
        for (String line : found) {
            Path git = Path.of(line);
            if (line.endsWith("bash") || line.endsWith("bash.exe")) {
                candidates.add(git);
            }
            candidates.add(git.getParent().getParent().resolve("bin").resolve("bash.exe"));
            candidates.add(git.getParent().resolve("bash.exe"));
        }
        candidates.add(Path.of("/bin/bash"));
        candidates.add(Path.of("/usr/bin/bash"));
        for (Path candidate : candidates) {
            if (candidate != null && Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("找不到 bash，无法验收离线检查脚本");
    }

    private static Run run(List<String> command, Map<String, String> env, Path directory, int seconds) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        builder.environment().putAll(env);
        Process process = builder.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread outThread = new Thread(() -> transfer(process.getInputStream(), out));
        Thread errThread = new Thread(() -> transfer(process.getErrorStream(), err));
        outThread.start();
        errThread.start();
        boolean finished = process.waitFor(seconds, TimeUnit.SECONDS);
        outThread.join(5000);
        errThread.join(5000);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("命令超时：" + command);
        }
        return new Run(process.exitValue(), out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static void transfer(java.io.InputStream input, ByteArrayOutputStream output) {
        try {
            input.transferTo(output);
        } catch (Exception ignored) {
            // 进程结束时流会关闭。
        }
    }

    private static String script(String text) {
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        int min = Integer.MAX_VALUE;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            int index = 0;
            while (index < line.length() && line.charAt(index) == ' ') {
                index++;
            }
            min = Math.min(min, index);
        }
        if (min == Integer.MAX_VALUE) {
            min = 0;
        }
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            int cut = Math.min(min, line.length());
            builder.append(line.substring(cut)).append('\n');
        }
        String result = builder.toString();
        while (result.startsWith("\n")) {
            result = result.substring(1);
        }
        return result;
    }

    private static Path repo() {
        return Path.of("").toAbsolutePath();
    }

    private static String unix(Path path) {
        String value = path.toAbsolutePath().toString().replace('\\', '/');
        if (value.length() >= 2 && value.charAt(1) == ':') {
            return "/" + Character.toLowerCase(value.charAt(0)) + value.substring(2);
        }
        return value;
    }

    private record Run(int code, String stdout, String stderr) {
        String output() {
            return stdout + stderr;
        }
    }
}
