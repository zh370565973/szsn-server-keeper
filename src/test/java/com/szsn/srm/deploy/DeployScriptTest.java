package com.szsn.srm.deploy;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 用假的 systemctl、curl 和 java 验收停服务冷备，以及失败升级只回滚同一份 JAR 和数据库。
 * 不启动 Spring，不分配负载内存，也不做 CPU 压力。
 */
class DeployScriptTest {
    @Test
    void unitFileAndScriptsDeclareTheRollbackContract() throws Exception {
        Path repo = repo();
        String unit = Files.readString(repo.resolve("deploy/server-resource-manager.service"));
        assertTrue(unit.contains("Restart=on-failure"));
        assertTrue(unit.contains("RestartSec=5"));
        assertTrue(unit.contains("StartLimitInterval=120"));
        assertTrue(unit.contains("StartLimitBurst=5"));
        assertTrue(unit.contains("TimeoutStopSec=10"));
        assertTrue(unit.contains("SuccessExitStatus=143"));
        assertTrue(unit.contains("User=srm"));
        for (String name : List.of(
                "srm-common.sh", "srm-run", "install.sh", "backup.sh", "restore.sh", "upgrade.sh",
                "server-resource-manager.service", "application.yml.example")) {
            String text = Files.readString(repo.resolve("deploy").resolve(name));
            assertFalse(text.contains("\r"), name + " 必须使用 LF，否则目标机上的 bash 无法执行");
        }
    }

    @Test
    void refusesBackupWhileServiceActiveOrDatabaseLocked(@TempDir Path temp) throws Exception {
        Sandbox box = Sandbox.install(temp, "OLD");
        Files.writeString(box.data.resolve("srm.mv.db"), "DB1");
        Files.writeString(box.stateFile, "active\n");

        Run refused = box.run("backup.sh");
        assertEquals(1, refused.code, refused.output());
        assertTrue(refused.stderr.contains("不能复制正在写入的 H2"));
        assertEquals(List.of(), box.backupIds());

        Files.writeString(box.stateFile, "inactive\n");
        Files.writeString(box.data.resolve("srm.lock.db"), "locked");
        Run locked = box.run("backup.sh");
        assertEquals(1, locked.code, locked.output());
        assertTrue(locked.stderr.contains("H2 锁文件"));
        assertEquals(List.of(), box.backupIds());
        assertEquals("DB1", Files.readString(box.data.resolve("srm.mv.db")));
    }

    @Test
    void coldBackupCopiesStoppedDatabaseAndRestarts(@TempDir Path temp) throws Exception {
        Sandbox box = Sandbox.install(temp, "OLD");
        Files.writeString(box.data.resolve("srm.mv.db"), "DB1");
        Files.writeString(box.stateFile, "inactive\n");

        Run backup = box.run("backup.sh");
        assertEquals(0, backup.code, backup.output());
        String id = idAfter(backup.stdout, "备份完成：");
        assertEquals("active", Files.readString(box.stateFile).trim());
        Path saved = box.backup.resolve(id);
        assertTreeChecksums(saved);
        assertEquals("OLD", Files.readString(saved.resolve("jar/server-resource-manager.jar")));
        assertEquals("DB1", Files.readString(saved.resolve("data/srm.mv.db")));
        assertPair(saved.resolve("data/release-pair"), saved.resolve("jar/server-resource-manager.jar"));
        assertFalse(Files.exists(box.data.resolve("admin.password")));
        String installLog = Files.readString(box.installLog);
        assertTrue(installLog.contains("kernel="));
        assertTrue(installLog.contains("memTotal="));
        assertTrue(installLog.contains("jdk="));
        assertTrue(installLog.contains("openjdk version \"17.0.test\""));
        assertTrue(installLog.contains("disk="));
        assertTrue(installLog.contains("cgroup="));
        assertTrue(installLog.contains("controlCapable=安装脚本不判断为已通过"));
        String unit = Files.readString(box.unitDir.resolve("server-resource-manager.service"));
        assertTrue(unit.contains("Restart=on-failure"));
        assertTrue(unit.contains("User=srm"));
        assertTrue(unit.contains("ExecStart=" + box.prefixUnix + "/srm-run"));
    }

    @Test
    void restoreWarnsAndRefusesTamperedBackup(@TempDir Path temp) throws Exception {
        Sandbox box = Sandbox.install(temp, "OLD");
        Files.writeString(box.data.resolve("srm.mv.db"), "DB1");
        Files.writeString(box.stateFile, "inactive\n");
        Run backup = box.run("backup.sh");
        assertEquals(0, backup.code, backup.output());
        String id = idAfter(backup.stdout, "备份完成：");
        assertEquals("active", Files.readString(box.stateFile).trim());

        Run missing = box.run("restore.sh");
        assertEquals(2, missing.code, missing.output());
        assertTrue(missing.stderr.contains("覆盖"));

        Run unconfirmed = box.run("restore.sh", "--backup", id);
        assertEquals(2, unconfirmed.code, unconfirmed.output());
        assertTrue(unconfirmed.stderr.contains("覆盖现有 JAR、数据库和配置"));
        assertTrue(unconfirmed.stderr.contains("未执行恢复"));
        assertEquals("DB1", Files.readString(box.data.resolve("srm.mv.db")));
        assertEquals("active", Files.readString(box.stateFile).trim());

        Files.writeString(box.backup.resolve(id).resolve("data/srm.mv.db"), "TAMPERED");
        Run tampered = box.run("restore.sh", "--backup", id, "--confirm");
        assertEquals(1, tampered.code, tampered.output());
        assertTrue(tampered.stderr.contains("校验和"));
        assertEquals("DB1", Files.readString(box.data.resolve("srm.mv.db")));
        assertEquals("OLD", Files.readString(box.prefix.resolve("server-resource-manager.jar")));
        assertEquals("active", Files.readString(box.stateFile).trim());
    }

    @Test
    void failedUpgradeRestoresTheSameJarAndDatabase(@TempDir Path temp) throws Exception {
        Sandbox box = Sandbox.install(temp, "OLD");
        Files.writeString(box.data.resolve("srm.mv.db"), "DB1");
        Files.writeString(box.healthMode, "fail-if-new\n");
        Path decoy = box.backup.resolve("zzz-decoy");
        Files.createDirectories(decoy.resolve("jar"));
        Files.createDirectories(decoy.resolve("data"));
        Files.writeString(decoy.resolve("jar/server-resource-manager.jar"), "DECOY");
        Files.writeString(decoy.resolve("data/srm.mv.db"), "DECOY");
        Path newer = temp.resolve("new.jar");
        Files.writeString(newer, "NEW");

        Run upgrade = box.run("upgrade.sh", "--jar", unix(newer));
        assertEquals(1, upgrade.code, upgrade.output());
        assertTrue(upgrade.stderr.contains("已回滚到备份"));
        assertEquals("OLD", Files.readString(box.prefix.resolve("server-resource-manager.jar")));
        assertEquals("DB1", Files.readString(box.data.resolve("srm.mv.db")));
        assertEquals("DECOY", Files.readString(decoy.resolve("data/srm.mv.db")));
        String marker = Files.readString(box.backup.resolve("upgrade-in-progress"));
        String id = idAfter(marker, "backup_id=");
        assertFalse(id.equals("zzz-decoy"));
        assertTrue(marker.contains("result=rolled-back"));
        Path saved = box.backup.resolve(id);
        assertEquals("OLD", Files.readString(saved.resolve("jar/server-resource-manager.jar")));
        assertEquals("DB1", Files.readString(saved.resolve("data/srm.mv.db")));
        assertPair(box.data.resolve("release-pair"), box.prefix.resolve("server-resource-manager.jar"));

        Files.writeString(box.javaLog, "");
        Run allowed = box.run(box.prefix.resolve("srm-run"));
        assertEquals(0, allowed.code, allowed.output());
        assertTrue(Files.readString(box.javaLog).contains("-jar"));

        Files.writeString(box.prefix.resolve("server-resource-manager.jar"), "NEW");
        Files.writeString(box.javaLog, "");
        Run rejected = box.run(box.prefix.resolve("srm-run"));
        assertEquals(1, rejected.code, rejected.output());
        assertTrue(rejected.stderr.contains("旧 JAR 不能直接打开已升级的数据库")
                || rejected.stderr.contains("release-pair 不一致"));
        assertEquals("", Files.readString(box.javaLog));
    }

    @Test
    void successfulUpgradeKeepsBackupAndRejectsOldJar(@TempDir Path temp) throws Exception {
        Sandbox box = Sandbox.install(temp, "OLD");
        Files.writeString(box.data.resolve("srm.mv.db"), "DB1");
        Files.writeString(box.healthMode, "ok\n");
        Path newer = temp.resolve("new.jar");
        Files.writeString(newer, "NEW");

        Run upgrade = box.run("upgrade.sh", "--jar", unix(newer));
        assertEquals(0, upgrade.code, upgrade.output());
        assertEquals("NEW", Files.readString(box.prefix.resolve("server-resource-manager.jar")));
        assertEquals("DB1MIGRATED\n", Files.readString(box.data.resolve("srm.mv.db")));
        String id = idAfter(Files.readString(box.backup.resolve("upgrade-in-progress")), "backup_id=");
        Path saved = box.backup.resolve(id);
        assertEquals("OLD", Files.readString(saved.resolve("jar/server-resource-manager.jar")));
        assertEquals("DB1", Files.readString(saved.resolve("data/srm.mv.db")));
        assertPair(box.data.resolve("release-pair"), box.prefix.resolve("server-resource-manager.jar"));

        Files.writeString(box.prefix.resolve("server-resource-manager.jar"), "OLD");
        Files.writeString(box.javaLog, "");
        Run rejected = box.run(box.prefix.resolve("srm-run"));
        assertEquals(1, rejected.code, rejected.output());
        assertTrue(rejected.stderr.contains("release-pair 不一致"));
        assertEquals("", Files.readString(box.javaLog));
    }

    private static void assertPair(Path releasePair, Path jar) throws Exception {
        String pair = Files.readString(releasePair).trim();
        assertEquals("jar_sha256=" + sha256(Files.readAllBytes(jar)), pair);
    }

    private static void assertTreeChecksums(Path backup) throws Exception {
        List<String> lines = Files.readAllLines(backup.resolve("CHECKSUMS"));
        assertFalse(lines.isEmpty());
        for (String line : lines) {
            int gap = line.indexOf("  ");
            assertTrue(gap > 0, line);
            String sum = line.substring(0, gap);
            String rel = line.substring(gap + 2);
            assertEquals(sum, sha256(Files.readAllBytes(backup.resolve(rel))), rel);
        }
    }

    private static String idAfter(String text, String marker) {
        int at = text.indexOf(marker);
        assertTrue(at >= 0, text);
        String rest = text.substring(at + marker.length()).trim();
        int end = rest.indexOf('\n');
        return (end < 0 ? rest : rest.substring(0, end)).trim();
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

    private static final class Sandbox {
        final Path root;
        final Path prefix;
        final Path etc;
        final Path data;
        final Path backup;
        final Path unitDir;
        final Path stateFile;
        final Path healthMode;
        final Path javaLog;
        final Path installLog;
        final Path bin;
        final String prefixUnix;
        final Map<String, String> env;

        private Sandbox(Path root, Map<String, String> env, Path prefix, Path etc, Path data, Path backup,
                        Path unitDir, Path stateFile, Path healthMode, Path javaLog, Path installLog, Path bin) {
            this.root = root;
            this.env = env;
            this.prefix = prefix;
            this.etc = etc;
            this.data = data;
            this.backup = backup;
            this.unitDir = unitDir;
            this.stateFile = stateFile;
            this.healthMode = healthMode;
            this.javaLog = javaLog;
            this.installLog = installLog;
            this.bin = bin;
            this.prefixUnix = unix(prefix);
        }

        static Sandbox install(Path temp, String jarText) throws Exception {
            Path bin = temp.resolve("bin");
            Path prefix = temp.resolve("opt");
            Path etc = temp.resolve("etc");
            Path data = temp.resolve("data");
            Path log = temp.resolve("log");
            Path backup = temp.resolve("backup");
            Path unitDir = temp.resolve("systemd");
            Path stateFile = temp.resolve("state");
            Path healthMode = temp.resolve("health-mode");
            Path javaLog = temp.resolve("java.log");
            Path onStart = temp.resolve("on-start.sh");
            Path installLog = temp.resolve("install.log");
            Files.createDirectories(bin);
            Files.writeString(healthMode, "ok\n");
            Files.writeString(javaLog, "");
            Files.writeString(bin.resolve("systemctl"), script("""
                    #!/bin/bash
                    set -euo pipefail
                    state_file=${SRM_STATE_FILE:?}
                    cmd=${1:-}
                    shift || true
                    case "$cmd" in
                      is-active)
                        if [ -f "$state_file" ]; then
                          state=$(cat "$state_file")
                        else
                          state=inactive
                        fi
                        printf '%s\\n' "$state"
                        case "$state" in
                          active|activating) exit 0 ;;
                          *) exit 3 ;;
                        esac
                        ;;
                      stop)
                        printf '%s\\n' inactive > "$state_file"
                        ;;
                      start)
                        if [ -n "${SRM_ON_START:-}" ]; then
                          bash "$SRM_ON_START"
                        fi
                        printf '%s\\n' active > "$state_file"
                        ;;
                      daemon-reload|enable|disable|reset-failed|restart)
                        exit 0
                        ;;
                      *)
                        echo "fake systemctl 不认识命令：$cmd" >&2
                        exit 1
                        ;;
                    esac
                    """));
            Files.writeString(bin.resolve("curl"), script("""
                    #!/bin/bash
                    mode=$(tr -d $'\\r' < "${SRM_HEALTH_MODE:?}")
                    jar=$(tr -d $'\\r' < "$SRM_PREFIX/server-resource-manager.jar")
                    if [ "$mode" = "fail-if-new" ] && [ "$jar" = "NEW" ]; then
                      exit 22
                    fi
                    exit 0
                    """));
            Files.writeString(bin.resolve("java"), script("""
                    #!/bin/bash
                    if [ "${1:-}" = "-version" ]; then
                      echo 'openjdk version "17.0.test"' >&2
                      exit 0
                    fi
                    printf '%s\\n' "$*" >> "${SRM_JAVA_LOG:?}"
                    exit 0
                    """));
            Files.writeString(onStart, script("""
                    #!/bin/bash
                    set -euo pipefail
                    jar=$(tr -d $'\\r' < "$SRM_PREFIX/server-resource-manager.jar")
                    if [ "$jar" = "NEW" ]; then
                      printf 'MIGRATED\\n' >> "$SRM_DATA/srm.mv.db"
                    fi
                    """));
            Path bash = bash();
            Run chmod = DeployScriptTest.run(bash, List.of(bash.toString(), "-c",
                    "chmod +x " + unix(bin.resolve("systemctl")) + " " + unix(bin.resolve("curl")) + " "
                            + unix(bin.resolve("java")) + " " + unix(onStart)),
                    Map.of(), temp);
            assertEquals(0, chmod.code, chmod.output());

            Path jar = temp.resolve("old.jar");
            Files.writeString(jar, jarText);
            Map<String, String> env = baseEnv(temp, bin, prefix, etc, data, log, backup, unitDir, stateFile,
                    healthMode, javaLog, onStart);
            Sandbox box = new Sandbox(temp, env, prefix, etc, data, backup, unitDir, stateFile, healthMode, javaLog,
                    installLog, bin);
            Run install = box.run("install.sh", "--jar", unix(jar));
            Files.writeString(installLog, install.output());
            assertEquals(0, install.code, install.output());
            return box;
        }

        Run run(String scriptName, String... args) throws Exception {
            return run(scriptName.equals("srm-run") ? prefix.resolve("srm-run") : repo().resolve("deploy").resolve(scriptName),
                    args);
        }

        Run run(Path script, String... args) throws Exception {
            Path bash = bash();
            List<String> command = new ArrayList<>();
            command.add(bash.toString());
            command.add(unix(script));
            command.addAll(List.of(args));
            return DeployScriptTest.run(bash, command, env, root);
        }

        List<String> backupIds() throws Exception {
            if (!Files.isDirectory(backup)) {
                return List.of();
            }
            try (var children = Files.list(backup)) {
                return children
                        .filter(Files::isDirectory)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> !name.startsWith("."))
                        .sorted()
                        .toList();
            }
        }

        private static Map<String, String> baseEnv(Path temp, Path bin, Path prefix, Path etc, Path data, Path log,
                                                   Path backup, Path unitDir, Path stateFile, Path healthMode,
                                                   Path javaLog, Path onStart) throws Exception {
            Map<String, String> env = new java.util.LinkedHashMap<>();
            env.put("SRM_ALLOW_NON_LINUX", "1");
            env.put("SRM_PREFIX", unix(prefix));
            env.put("SRM_ETC", unix(etc));
            env.put("SRM_DATA", unix(data));
            env.put("SRM_LOG", unix(log));
            env.put("SRM_BACKUP_ROOT", unix(backup));
            env.put("SRM_UNIT_DIR", unix(unitDir));
            env.put("SRM_USER", "srm");
            env.put("SRM_GROUP", "srm");
            env.put("SRM_SERVICE", "server-resource-manager");
            env.put("SRM_JAVA", unix(bin.resolve("java")));
            env.put("SRM_SYSTEMCTL", unix(bin.resolve("systemctl")));
            env.put("SRM_CURL", unix(bin.resolve("curl")));
            env.put("SRM_HEALTH_ATTEMPTS", "1");
            env.put("SRM_HEALTH_INTERVAL", "0");
            env.put("SRM_STOP_ATTEMPTS", "2");
            env.put("SRM_STOP_INTERVAL", "0");
            env.put("SRM_STATE_FILE", unix(stateFile));
            env.put("SRM_HEALTH_MODE", unix(healthMode));
            env.put("SRM_JAVA_LOG", unix(javaLog));
            env.put("SRM_ON_START", unix(onStart));
            String path = System.getenv("PATH");
            Path bash = bash();
            Path usrBin = bash.getParent().getParent().resolve("usr").resolve("bin");
            if (Files.isDirectory(usrBin)) {
                path = usrBin + java.io.File.pathSeparator + bash.getParent() + java.io.File.pathSeparator + path;
            }
            env.put("PATH", path);
            env.put("SRM_SANDBOX", unix(temp));
            return env;
        }
    }

    private static Run run(Path bash, List<String> command, Map<String, String> env, Path directory) throws Exception {
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
        throw new AssertionError("找不到 bash，无法验收部署脚本");
    }

    private record Run(int code, String stdout, String stderr) {
        String output() {
            return "stdout:\n" + stdout + "\nstderr:\n" + stderr;
        }
    }
}
