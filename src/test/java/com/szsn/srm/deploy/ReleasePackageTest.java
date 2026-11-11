package com.szsn.srm.deploy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验收离线发布目录含 JAR、SHA-256、依赖清单、OpenAPI 和部署说明。
 * 使用假 JAR，不启动 Spring，也不做 CPU 或内存压力。
 */
class ReleasePackageTest {
    private static final List<String> SCRIPT_NAMES = List.of(
            "srm-common.sh", "srm-run", "install.sh", "backup.sh", "restore.sh", "upgrade.sh",
            "server-resource-manager.service", "application.yml.example", "README.md",
            "verify-offline.sh", "OfflineProbe.java");

    @Test
    void assemblerUsesLfAndRefusesIncompleteInput(@TempDir Path temp) throws Exception {
        String script = Files.readString(repo().resolve("deploy/assemble-release.sh"));
        assertFalse(script.contains("\r"), "assemble-release.sh 必须使用 LF");
        assertFalse(script.contains("java -jar"), "打包脚本不能启动应用");

        Path out = temp.resolve("out");
        Run missing = assemble(temp, Map.of("--out", unix(out)));
        assertEquals(2, missing.code, missing.output());
        assertFalse(Files.exists(out));
    }

    @Test
    void packagesJarChecksumsDependenciesOpenApiAndDeployDoc(@TempDir Path temp) throws Exception {
        Path jar = temp.resolve("built.jar");
        Files.writeString(jar, "not-a-real-jar");
        Path dependencies = temp.resolve("deps.txt");
        Files.writeString(dependencies, "com.example:sample:jar:1.2.3:runtime\n");
        Path out = temp.resolve("release");

        Run packed = assemble(temp, Map.of(
                "--jar", unix(jar),
                "--dependencies", unix(dependencies),
                "--openapi", unix(repo().resolve("docs/openapi.yaml")),
                "--deploy-doc", unix(repo().resolve("docs/DEPLOY.md")),
                "--scripts", unix(repo().resolve("deploy")),
                "--out", unix(out),
                "--version", "1.0.0-M1"));
        assertEquals(0, packed.code, packed.output());
        assertTrue(packed.stdout.contains("jar_sha256="));

        assertArrayEquals(Files.readAllBytes(jar), Files.readAllBytes(out.resolve("server-resource-manager.jar")));
        assertEquals(Files.readString(dependencies), Files.readString(out.resolve("DEPENDENCIES.txt")));
        assertArrayEquals(Files.readAllBytes(repo().resolve("docs/openapi.yaml")),
                Files.readAllBytes(out.resolve("openapi.yaml")));
        byte[] deployDoc = Files.readAllBytes(out.resolve("DEPLOY.md"));
        assertArrayEquals(Files.readAllBytes(repo().resolve("docs/DEPLOY.md")), deployDoc);
        String doc = new String(deployDoc, StandardCharsets.UTF_8);
        assertTrue(doc.contains("SHA256SUMS"));
        assertTrue(doc.contains("install.sh"));
        assertTrue(doc.contains("init-admin"));
        assertTrue(doc.contains("DEPENDENCIES.txt"));
        assertTrue(doc.contains("openapi.yaml"));
        assertTrue(doc.contains("目标环境待验收"));

        for (String name : SCRIPT_NAMES) {
            assertArrayEquals(Files.readAllBytes(repo().resolve("deploy").resolve(name)),
                    Files.readAllBytes(out.resolve("deploy").resolve(name)), name);
        }
        assertFalse(Files.exists(out.resolve("deploy/assemble-release.sh")));

        Map<String, String> sums = parseSums(out.resolve("SHA256SUMS"));
        assertFalse(sums.containsKey("SHA256SUMS"));
        assertFalse(sums.containsKey("MANIFEST"));
        List<String> packaged = listFiles(out).stream()
                .filter(rel -> !rel.equals("SHA256SUMS") && !rel.equals("MANIFEST"))
                .sorted()
                .toList();
        assertEquals(packaged, sums.keySet().stream().sorted().toList());
        for (Map.Entry<String, String> entry : sums.entrySet()) {
            assertFalse(entry.getKey().contains("\\"), entry.getKey());
            assertEquals(entry.getValue(), sha256(Files.readAllBytes(out.resolve(entry.getKey()))), entry.getKey());
        }

        String manifest = Files.readString(out.resolve("MANIFEST"));
        assertTrue(manifest.contains("product=server-resource-manager"));
        assertTrue(manifest.contains("version=1.0.0-M1"));
        assertTrue(manifest.contains("jar=server-resource-manager.jar"));
        assertTrue(manifest.contains("jar_sha256=" + sha256(Files.readAllBytes(jar))));
        assertTrue(manifest.contains("checksums_sha256=" + sha256(Files.readAllBytes(out.resolve("SHA256SUMS")))));
        assertFalse(manifest.contains("\r"));

        Path occupied = temp.resolve("occupied");
        Files.createDirectories(occupied);
        Files.writeString(occupied.resolve("keep.txt"), "keep");
        Run refused = assemble(temp, Map.of(
                "--jar", unix(jar),
                "--dependencies", unix(dependencies),
                "--openapi", unix(repo().resolve("docs/openapi.yaml")),
                "--deploy-doc", unix(repo().resolve("docs/DEPLOY.md")),
                "--scripts", unix(repo().resolve("deploy")),
                "--out", unix(occupied),
                "--version", "1.0.0-M1"));
        assertEquals(2, refused.code, refused.output());
        assertTrue(refused.stderr.contains("输出目录必须为空"));
        assertEquals("keep", Files.readString(occupied.resolve("keep.txt")));
        assertFalse(Files.exists(occupied.resolve("SHA256SUMS")));
    }

    @Test
    void refusesScriptDirectoryThatOmitsARequiredFile(@TempDir Path temp) throws Exception {
        Path scripts = temp.resolve("scripts");
        Files.createDirectories(scripts);
        Files.writeString(scripts.resolve("install.sh"), "#!/bin/bash\n");
        Path jar = temp.resolve("built.jar");
        Files.writeString(jar, "jar");
        Path dependencies = temp.resolve("deps.txt");
        Files.writeString(dependencies, "deps\n");
        Path openapi = temp.resolve("openapi.yaml");
        Files.writeString(openapi, "openapi: 3.0.3\n");
        Path doc = temp.resolve("DEPLOY.md");
        Files.writeString(doc, "说明\n");
        Path out = temp.resolve("out");

        Run missing = assemble(temp, Map.of(
                "--jar", unix(jar),
                "--dependencies", unix(dependencies),
                "--openapi", unix(openapi),
                "--deploy-doc", unix(doc),
                "--scripts", unix(scripts),
                "--out", unix(out),
                "--version", "1.0.0-M1"));
        assertEquals(2, missing.code, missing.output());
        assertTrue(missing.stderr.contains("缺少"));
        assertFalse(Files.exists(out.resolve("SHA256SUMS")));
    }

    private static Run assemble(Path temp, Map<String, String> args) throws Exception {
        List<String> command = new ArrayList<>();
        Path bash = bash();
        command.add(bash.toString());
        command.add(unix(repo().resolve("deploy/assemble-release.sh")));
        for (Map.Entry<String, String> entry : args.entrySet()) {
            command.add(entry.getKey());
            command.add(entry.getValue());
        }
        Map<String, String> env = new LinkedHashMap<>();
        String path = System.getenv("PATH");
        Path usrBin = bash.getParent().getParent().resolve("usr").resolve("bin");
        if (Files.isDirectory(usrBin)) {
            path = usrBin + java.io.File.pathSeparator + bash.getParent() + java.io.File.pathSeparator + path;
        }
        env.put("PATH", path);
        return run(command, env, temp);
    }

    private static List<String> listFiles(Path root) throws Exception {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    private static Map<String, String> parseSums(Path file) throws Exception {
        Map<String, String> sums = new LinkedHashMap<>();
        for (String line : Files.readString(file).split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            int split = line.indexOf("  ");
            assertTrue(split > 0, line);
            sums.put(line.substring(split + 2), line.substring(0, split));
        }
        return sums;
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
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
        throw new AssertionError("找不到 bash，无法验收发布打包");
    }

    private static Run run(List<String> command, Map<String, String> env, Path directory) throws Exception {
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
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
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

    private record Run(int code, String stdout, String stderr) {
        String output() {
            return stdout + stderr;
        }
    }
}
