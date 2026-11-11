import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 对已经构建好的 JAR 做断网冷启动。只连接 127.0.0.1，不调用 npm 或 Maven，也不启动 CPU/内存负载。
 */
public class OfflineProbe {
    // BEGIN DENY_HOSTS
    private static final List<String> DENY_HOSTS = List.of(
            "fonts.googleapis",
            "fonts.gstatic",
            "cdn.jsdelivr",
            "unpkg.com",
            "cdnjs.cloudflare",
            "ajax.googleapis",
            "googletagmanager",
            "google-analytics",
            "sentry.io",
            "opentelemetry",
            "cdn.bootcss",
            "bootcdn.net",
            "at.alicdn.com",
            "plausible.io");
    // END DENY_HOSTS

    // BEGIN DENY_LIBS
    private static final List<String> DENY_LIBS = List.of(
            "sentry",
            "opentelemetry",
            "micrometer-registry",
            "spring-boot-devtools",
            "spring-cloud",
            "actuator",
            "newrelic",
            "datadog",
            "telemetry");
    // END DENY_LIBS

    private static final Pattern REF = Pattern.compile("(?:src|href)=\"([^\"]+)\"");
    private static final int BODY_LIMIT = 20_000_000;

    public static void main(String[] args) {
        Session session = new Session();
        int code = 1;
        try {
            code = run(args, session);
        } catch (ProbeException exception) {
            System.err.println(exception.getMessage());
            code = exception.code;
        } catch (Exception exception) {
            exception.printStackTrace(System.err);
            code = 1;
        } finally {
            session.close();
        }
        System.exit(code);
    }

    private static int run(String[] args, Session session) throws Exception {
        if (args.length == 0) {
            throw new ProbeException(2, usage());
        }
        Map<String, String> opts = parse(args);
        if (opts.containsKey("scan-jar")) {
            Scan scan = scanJar(Path.of(opts.get("scan-jar")));
            System.out.println("static_files=" + scan.staticFiles);
            System.out.println("libraries=" + scan.libraries);
            System.out.println("scan_ok");
            return 0;
        }
        if (!opts.containsKey("jar") || !opts.containsKey("work")) {
            throw new ProbeException(2, usage());
        }
        Path jar = Path.of(opts.get("jar"));
        Path work = Path.of(opts.get("work"));
        int port = opts.containsKey("port") ? Integer.parseInt(opts.get("port")) : freePort();
        if (port < 1 || port > 65535) {
            throw new ProbeException(2, "端口超出范围。");
        }
        if (Files.exists(work)) {
            try (Stream<Path> children = Files.list(work)) {
                if (children.findAny().isPresent()) {
                    throw new ProbeException(2, "工作目录必须为空：" + work);
                }
            }
        } else {
            Files.createDirectories(work);
        }
        Scan scan = scanJar(jar);
        System.out.println("static_files=" + scan.staticFiles);
        System.out.println("libraries=" + scan.libraries);

        Path javaExe = javaBinary();
        String serverPath = childPath(javaExe);
        if (visible(serverPath, "npm") || visible(serverPath, "mvn")) {
            throw new ProbeException(1, "服务进程的 PATH 仍能找到 npm 或 Maven：" + serverPath);
        }
        System.out.println("npm_on_path=false");
        System.out.println("mvn_on_path=false");
        System.out.println("server_path=" + serverPath);

        if (opts.containsKey("block-outbound")) {
            session.firewall = true;
            blockOutbound(javaExe);
            System.out.println("outbound_blocked=true");
        } else {
            System.out.println("outbound_blocked=false");
        }

        Path data = work.resolve("data");
        Files.createDirectories(data);
        String dataPath = slash(data);
        if (dataPath.indexOf(';') >= 0) {
            throw new ProbeException(2, "数据目录不能包含分号。");
        }
        String password = password();
        List<String> command = List.of(
                javaExe.toString(),
                "-Djava.net.preferIPv4Stack=true",
                "-Djava.awt.headless=true",
                "-jar", jar.toAbsolutePath().toString(),
                "--server.address=127.0.0.1",
                "--server.port=" + port,
                "--srm.data-dir=" + dataPath,
                "--srm.admin.password-file=" + dataPath + "/admin.password",
                "--spring.datasource.url=jdbc:h2:file:" + dataPath + "/srm;DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=FALSE",
                "--logging.file.name=" + slash(work.resolve("service.log")));
        int initCode = runJava(command, "init-admin", password, serverPath, work.resolve("init.out"), work.resolve("init.err"), 120);
        if (initCode != 0) {
            throw new ProbeException(1, "init-admin 退出码 " + initCode + "\n" + tail(work.resolve("init.err")));
        }
        session.server = startJava(command, null, serverPath, work.resolve("server.out"), work.resolve("server.err"));
        String base = "http://127.0.0.1:" + port;
        LocalHttp http = new LocalHttp(port);
        if (!waitUntilReady(session.server, http)) {
            throw new ProbeException(1, "服务没有在回环地址上起来。\n" + tail(work.resolve("server.err")) + tail(work.resolve("server.out")));
        }
        Exchange page = http.send("GET", "/", Map.of(), "");
        if (page.status != 200) {
            throw new ProbeException(1, "首页状态 " + page.status);
        }
        List<String> assets = localRefs(page.body);
        if (assets.isEmpty()) {
            throw new ProbeException(1, "首页没有本地静态资源。");
        }
        for (String asset : assets) {
            Exchange response = http.send("GET", asset, Map.of(), "");
            if (response.status != 200) {
                throw new ProbeException(1, "静态资源状态 " + response.status + "：" + asset);
            }
            assertClean(asset, response.body);
        }
        Exchange anonymous = http.send("GET", "/api/v1/auth/me", Map.of(), "");
        if (anonymous.status != 401) {
            throw new ProbeException(1, "未登录读取会话时期望 401，实际 " + anonymous.status);
        }
        Exchange csrf = http.send("GET", "/api/v1/auth/csrf", Map.of(), "");
        if (csrf.status != 200) {
            throw new ProbeException(1, "CSRF 状态 " + csrf.status);
        }
        String header = jsonString(csrf.body, "headerName");
        String token = jsonString(csrf.body, "token");
        Exchange login = http.send("POST", "/api/v1/auth/login",
                Map.of("Content-Type", "application/json", header, token),
                "{\"username\":\"admin\",\"password\":\"" + password + "\"}");
        if (login.status != 200 || !login.body.contains("\"username\":\"admin\"")) {
            throw new ProbeException(1, "登录状态 " + login.status + "：" + login.body);
        }
        Exchange me = http.send("GET", "/api/v1/auth/me", Map.of(), "");
        if (me.status != 200 || !me.body.contains("\"username\":\"admin\"")) {
            throw new ProbeException(1, "登录后读取会话失败：" + me.status + " " + me.body);
        }
        inspectSockets(session.server.pid(), port);
        System.out.println("listening=127.0.0.1:" + port);
        System.out.println("assets=" + assets.size());
        System.out.println("login=admin");
        System.out.println("offline_ok");
        System.out.println("base=" + base);
        return 0;
    }

    private static Scan scanJar(Path jar) throws Exception {
        if (!Files.isRegularFile(jar)) {
            throw new ProbeException(1, "找不到 JAR：" + jar);
        }
        int staticFiles = 0;
        int libraries = 0;
        boolean sawIndex = false;
        try (JarFile file = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("BOOT-INF/lib/") && name.endsWith(".jar")) {
                    libraries++;
                    String lower = name.toLowerCase(Locale.ROOT);
                    for (String deny : DENY_LIBS) {
                        if (lower.contains(deny)) {
                            throw new ProbeException(1, "依赖名不允许出现 " + deny + "：" + name);
                        }
                    }
                }
                if (!name.startsWith("BOOT-INF/classes/static/") || entry.isDirectory()) {
                    continue;
                }
                if (name.equals("BOOT-INF/classes/static/index.html")) {
                    sawIndex = true;
                }
                String lowerName = name.toLowerCase(Locale.ROOT);
                if (!(lowerName.endsWith(".html") || lowerName.endsWith(".js") || lowerName.endsWith(".css"))) {
                    continue;
                }
                staticFiles++;
                String text = readEntry(file, entry);
                assertClean(name, text);
            }
        } catch (IOException exception) {
            throw new ProbeException(1, "无法读取 JAR：" + exception.getMessage());
        }
        if (!sawIndex) {
            throw new ProbeException(1, "JAR 里没有内嵌首页。");
        }
        return new Scan(staticFiles, libraries);
    }

    private static String readEntry(JarFile file, JarEntry entry) throws Exception {
        try (InputStream input = file.getInputStream(entry)) {
            byte[] body = input.readNBytes(BODY_LIMIT + 1);
            if (body.length > BODY_LIMIT) {
                throw new ProbeException(1, "静态文件过大：" + entry.getName());
            }
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private static void assertClean(String name, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String deny : DENY_HOSTS) {
            if (lower.contains(deny)) {
                throw new ProbeException(1, "静态资源引用了外站 " + deny + "：" + name);
            }
        }
        String lowerName = name.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".html") || lowerName.endsWith(".css")) {
            if (lower.contains("src=\"http") || lower.contains("href=\"http") || lower.contains("url(http")
                    || lower.contains("url(\"http")) {
                throw new ProbeException(1, "静态资源包含外站地址：" + name);
            }
        }
    }

    private static List<String> localRefs(String html) {
        String lower = html.toLowerCase(Locale.ROOT);
        if (lower.contains("src=\"http") || lower.contains("href=\"http")) {
            throw new ProbeException(1, "首页指向外站。");
        }
        Matcher matcher = REF.matcher(html);
        List<String> refs = new ArrayList<>();
        while (matcher.find()) {
            String ref = matcher.group(1);
            if (ref.startsWith("//")) {
                throw new ProbeException(1, "首页指向外站：" + ref);
            }
            if (ref.contains("..")) {
                throw new ProbeException(1, "静态路径不允许 .. ：" + ref);
            }
            if (ref.startsWith("/")) {
                refs.add(ref);
            }
        }
        return refs;
    }

    private static boolean waitUntilReady(Process server, LocalHttp http) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (System.nanoTime() < deadline) {
            if (!server.isAlive()) {
                return false;
            }
            try {
                Exchange exchange = http.send("GET", "/api/v1/auth/csrf", Map.of(), "");
                if (exchange.status == 200 && exchange.body.contains("token")) {
                    return true;
                }
            } catch (IOException ignored) {
                // 端口还没打开。
            }
            Thread.sleep(250);
        }
        return false;
    }

    private static void inspectSockets(long pid, int port) throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<Row> rows = os.contains("win") ? windowsRows(pid) : linuxRows(pid);
        boolean listening = false;
        int remote = 0;
        StringBuilder owned = new StringBuilder();
        for (Row row : rows) {
            owned.append(row.raw).append('\n');
            if (!row.local.loopback()) {
                throw new ProbeException(1, "进程套接字不在回环地址：" + row.raw);
            }
            if (row.local.portIs(port) && row.foreign.closed()) {
                listening = true;
            }
            if (!row.foreign.closed() && !row.foreign.loopback()) {
                remote++;
                System.err.println("对外连接：" + row.raw);
            }
        }
        if (!listening) {
            throw new ProbeException(1, "没有看到 127.0.0.1:" + port + " 的监听。\n" + owned);
        }
        if (remote != 0) {
            throw new ProbeException(1, "进程存在对外连接：" + remote);
        }
        System.out.println("sockets=" + rows.size());
        System.out.println("remote_sockets=0");
    }

    private static List<Row> windowsRows(long pid) throws Exception {
        String root = System.getenv("SystemRoot");
        if (root == null || root.isBlank()) {
            throw new ProbeException(1, "找不到 SystemRoot，无法运行 netstat。");
        }
        Path netstat = Path.of(root, "System32", "netstat.exe");
        List<Row> rows = new ArrayList<>();
        for (String protocol : List.of("tcp", "udp")) {
            Command command = runCommand(List.of(netstat.toString(), "-ano", "-p", protocol), Map.of(), 20);
            if (command.code != 0) {
                throw new ProbeException(1, "netstat 失败：" + command.output());
            }
            for (String line : command.stdout.split("\\R")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 4) {
                    continue;
                }
                String proto = parts[0].toUpperCase(Locale.ROOT);
                if (!proto.equals("TCP") && !proto.equals("UDP")) {
                    continue;
                }
                if (!parts[parts.length - 1].equals(Long.toString(pid))) {
                    continue;
                }
                rows.add(new Row(parseText(parts[1]), parseText(parts[2]), line.trim()));
            }
        }
        return rows;
    }

    private static List<Row> linuxRows(long pid) throws Exception {
        Set<String> inodes = socketInodes(pid);
        List<Row> rows = new ArrayList<>();
        for (String name : List.of("tcp", "tcp6", "udp", "udp6")) {
            Path table = Path.of("/proc", Long.toString(pid), "net", name);
            if (!Files.isRegularFile(table)) {
                continue;
            }
            boolean v6 = name.endsWith("6");
            for (String line : Files.readAllLines(table)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 10 || !parts[0].endsWith(":") || !inodes.contains(parts[9])) {
                    continue;
                }
                rows.add(new Row(parseHex(parts[1], v6), parseHex(parts[2], v6), line.trim()));
            }
        }
        return rows;
    }

    private static Set<String> socketInodes(long pid) throws IOException {
        Set<String> inodes = new java.util.HashSet<>();
        Path fdDir = Path.of("/proc", Long.toString(pid), "fd");
        try (DirectoryStream<Path> fds = Files.newDirectoryStream(fdDir)) {
            for (Path fd : fds) {
                try {
                    String target = Files.readSymbolicLink(fd).toString();
                    if (target.startsWith("socket:[") && target.endsWith("]")) {
                        inodes.add(target.substring("socket:[".length(), target.length() - 1));
                    }
                } catch (IOException ignored) {
                    // 描述符可能刚好关闭。
                }
            }
        }
        return inodes;
    }

    private static void blockOutbound(Path javaExe) throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            throw new ProbeException(1, "这个系统没有为本次检查实现出站阻断。不要把未阻断的启动记成断网。");
        }
        String root = System.getenv("SystemRoot");
        if (root == null || root.isBlank()) {
            throw new ProbeException(1, "找不到 SystemRoot，无法阻断出站。");
        }
        Path powershell = Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        String program = javaExe.toAbsolutePath().toString().replace("'", "''");
        String command = "$ErrorActionPreference='Stop';"
                + "$existing = Get-NetFirewallRule -DisplayName 'srm-offline-probe' -ErrorAction SilentlyContinue;"
                + "if ($existing) { $existing | Remove-NetFirewallRule };"
                + "New-NetFirewallRule -DisplayName 'srm-offline-probe' -Direction Outbound -Program '" + program
                + "' -Action Block -Profile Any | Out-Null";
        Command result = runCommand(List.of(powershell.toString(), "-NoProfile", "-NonInteractive", "-Command", command),
                Map.of(), 40);
        if (result.code != 0) {
            throw new ProbeException(1, "无法阻断 java.exe 出站：" + result.output());
        }
    }

    private static void clearFirewall() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String root = System.getenv("SystemRoot");
        if (!os.contains("win") || root == null) {
            return;
        }
        Path powershell = Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        String command = "$existing = Get-NetFirewallRule -DisplayName 'srm-offline-probe' -ErrorAction SilentlyContinue;"
                + "if ($existing) { $existing | Remove-NetFirewallRule }";
        try {
            runCommand(List.of(powershell.toString(), "-NoProfile", "-NonInteractive", "-Command", command), Map.of(), 40);
        } catch (Exception exception) {
            System.err.println("移除出站阻断失败：" + exception.getMessage());
        }
    }

    private static boolean visible(String path, String command) throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Command result;
        if (os.contains("win")) {
            String root = System.getenv("SystemRoot");
            Path where = Path.of(root, "System32", "where.exe");
            result = runCommand(List.of(where.toString(), command), Map.of("PATH", path, "PATHEXT", ".COM;.EXE;.BAT;.CMD"), 15);
        } else {
            result = runCommand(List.of("/bin/sh", "-c", "command -v " + command + " >/dev/null"), Map.of("PATH", path), 15);
        }
        return result.code == 0;
    }

    private static int runJava(List<String> command, String extra, String password, String path, Path out, Path err, int seconds)
            throws Exception {
        Process process = startJava(append(command, extra), password, path, out, err);
        if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new ProbeException(1, extra + " 超时。");
        }
        return process.exitValue();
    }

    private static Process startJava(List<String> command, String password, String path, Path out, Path err) throws Exception {
        List<String> args = new ArrayList<>(command);
        stripAgents(args);
        ProcessBuilder builder = new ProcessBuilder(args);
        builder.redirectOutput(out.toFile());
        builder.redirectError(err.toFile());
        builder.environment().put("PATH", path);
        for (String key : List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JAVA_OPTIONS", "JDK_JAVA_OPTIONS",
                "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy")) {
            builder.environment().remove(key);
        }
        if (password == null) {
            builder.redirectInput(ProcessBuilder.Redirect.PIPE);
            Process process = builder.start();
            process.getOutputStream().close();
            return process;
        }
        Process process = builder.start();
        try (OutputStream input = process.getOutputStream()) {
            input.write((password + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return process;
    }

    private static void stripAgents(List<String> args) {
        args.removeIf(arg -> arg.startsWith("-javaagent") || arg.startsWith("-agentlib") || arg.startsWith("-agentpath"));
    }

    private static List<String> append(List<String> command, String extra) {
        List<String> copy = new ArrayList<>(command);
        if (extra != null) {
            copy.add(extra);
        }
        return copy;
    }

    private static Command runCommand(List<String> command, Map<String, String> env, int seconds) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().putAll(env);
        Process process = builder.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread outThread = new Thread(() -> transfer(process.getInputStream(), out));
        Thread errThread = new Thread(() -> transfer(process.getErrorStream(), err));
        outThread.start();
        errThread.start();
        if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new ProbeException(1, "命令超时：" + command.get(0));
        }
        outThread.join(2000);
        errThread.join(2000);
        return new Command(process.exitValue(), out.toString(java.nio.charset.Charset.defaultCharset()),
                err.toString(java.nio.charset.Charset.defaultCharset()));
    }

    private static void transfer(InputStream input, ByteArrayOutputStream output) {
        try {
            input.transferTo(output);
        } catch (IOException ignored) {
            // 进程结束时流会关闭。
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    private static Path javaBinary() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name);
    }

    private static String childPath(Path javaExe) {
        Path bin = javaExe.getParent();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return bin + java.io.File.pathSeparator + Path.of(System.getenv("SystemRoot"), "System32");
        }
        return bin.toString();
    }

    private static String slash(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    private static String password() {
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        return "A1a" + HexFormat.of().formatHex(bytes);
    }

    private static String tail(Path path) {
        if (!Files.isRegularFile(path)) {
            return "";
        }
        try {
            String text = Files.readString(path);
            return text.length() <= 4000 ? text : text.substring(text.length() - 4000);
        } catch (IOException exception) {
            return exception.getMessage();
        }
    }

    private static String jsonString(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        if (!matcher.find()) {
            throw new ProbeException(1, "响应里没有 " + field + "：" + json);
        }
        return matcher.group(1);
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--scan-jar", "--jar", "--work", "--port" -> {
                    if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                        throw new ProbeException(2, "缺少参数值：" + arg);
                    }
                    opts.put(arg.substring(2), args[++i]);
                }
                case "--block-outbound" -> opts.put("block-outbound", "true");
                default -> throw new ProbeException(2, "未知参数：" + arg + "\n" + usage());
            }
        }
        if (opts.containsKey("port")) {
            try {
                Integer.parseInt(opts.get("port"));
            } catch (NumberFormatException exception) {
                throw new ProbeException(2, "端口必须是数字。");
            }
        }
        return opts;
    }

    private static String usage() {
        return "用法：OfflineProbe --jar <文件> --work <空目录> [--port <端口>] [--block-outbound]\n"
                + "或：OfflineProbe --scan-jar <文件>";
    }

    private static Endpoint parseText(String value) {
        if ("*:*".equals(value) || "*".equals(value)) {
            return new Endpoint("*", "*");
        }
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return new Endpoint(value.substring(1, end), value.substring(end + 2));
        }
        int colon = value.lastIndexOf(':');
        return new Endpoint(value.substring(0, colon), value.substring(colon + 1));
    }

    private static Endpoint parseHex(String value, boolean v6) {
        String[] pair = value.split(":");
        int port = Integer.parseInt(pair[1], 16);
        String host = v6 ? ipv6(pair[0]) : ipv4(pair[0]);
        return new Endpoint(host, Integer.toString(port));
    }

    private static String ipv4(String hex) {
        long value = Long.parseLong(hex, 16);
        return (value & 0xff) + "." + ((value >> 8) & 0xff) + "." + ((value >> 16) & 0xff) + "." + ((value >> 24) & 0xff);
    }

    private static String ipv6(String hex) {
        int[] parts = new int[8];
        for (int index = 0; index < 4; index++) {
            int word = Integer.reverseBytes((int) Long.parseLong(hex.substring(index * 8, index * 8 + 8), 16));
            parts[index * 2] = (word >>> 16) & 0xffff;
            parts[index * 2 + 1] = word & 0xffff;
        }
        boolean any = false;
        for (int part : parts) {
            if (part != 0) {
                any = true;
                break;
            }
        }
        if (!any) {
            return "::";
        }
        boolean loopback = parts[7] == 1;
        for (int index = 0; index < 7; index++) {
            if (parts[index] != 0) {
                loopback = false;
            }
        }
        return loopback ? "::1" : "external-v6";
    }

    private static final class Session {
        private Process server;
        private boolean firewall;

        private void close() {
            if (server != null) {
                server.destroy();
                try {
                    if (!server.waitFor(5, TimeUnit.SECONDS)) {
                        server.destroyForcibly();
                    }
                } catch (InterruptedException exception) {
                    server.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
            if (firewall) {
                clearFirewall();
            }
        }
    }

    private static final class Scan {
        private final int staticFiles;
        private final int libraries;

        private Scan(int staticFiles, int libraries) {
            this.staticFiles = staticFiles;
            this.libraries = libraries;
        }
    }

    private static final class Endpoint {
        private final String host;
        private final String port;

        private Endpoint(String host, String port) {
            this.host = host;
            this.port = port;
        }

        private boolean loopback() {
            return "127.0.0.1".equals(host) || "::1".equals(host) || "localhost".equalsIgnoreCase(host);
        }

        private boolean closed() {
            return ("0".equals(port) || "*".equals(port))
                    && ("0.0.0.0".equals(host) || "::".equals(host) || "*".equals(host));
        }

        private boolean portIs(int expected) {
            return Integer.toString(expected).equals(port);
        }
    }

    private static final class Row {
        private final Endpoint local;
        private final Endpoint foreign;
        private final String raw;

        private Row(Endpoint local, Endpoint foreign, String raw) {
            this.local = local;
            this.foreign = foreign;
            this.raw = raw;
        }
    }

    private static final class Command {
        private final int code;
        private final String stdout;
        private final String stderr;

        private Command(int code, String stdout, String stderr) {
            this.code = code;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        private String output() {
            return stdout + stderr;
        }
    }

    private static final class Exchange {
        private final int status;
        private final String body;

        private Exchange(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static final class LocalHttp {
        private final int port;
        private final Map<String, String> cookies = new LinkedHashMap<>();

        private LocalHttp(int port) {
            this.port = port;
        }

        private Exchange send(String method, String path, Map<String, String> headers, String body) throws IOException {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
                socket.setSoTimeout(10000);
                ByteArrayOutputStream request = new ByteArrayOutputStream();
                request.write((method + " " + path + " HTTP/1.1\r\n").getBytes(StandardCharsets.US_ASCII));
                request.write(("Host: 127.0.0.1:" + port + "\r\n").getBytes(StandardCharsets.US_ASCII));
                request.write("Connection: close\r\n".getBytes(StandardCharsets.US_ASCII));
                if (!cookies.isEmpty()) {
                    StringBuilder cookie = new StringBuilder();
                    for (Map.Entry<String, String> entry : cookies.entrySet()) {
                        if (cookie.length() > 0) {
                            cookie.append("; ");
                        }
                        cookie.append(entry.getKey()).append('=').append(entry.getValue());
                    }
                    request.write(("Cookie: " + cookie + "\r\n").getBytes(StandardCharsets.UTF_8));
                }
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    request.write((header.getKey() + ": " + header.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
                }
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                if (!body.isEmpty()) {
                    request.write(("Content-Length: " + payload.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
                }
                request.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                request.write(payload);
                OutputStream out = socket.getOutputStream();
                out.write(request.toByteArray());
                out.flush();
                InputStream in = socket.getInputStream();
                byte[] headerBytes = readHeader(in);
                String headerText = new String(headerBytes, StandardCharsets.ISO_8859_1);
                int split = headerText.indexOf("\r\n\r\n");
                String[] lines = headerText.substring(0, split).split("\r\n");
                int status = Integer.parseInt(lines[0].split(" ")[1]);
                Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
                for (int index = 1; index < lines.length; index++) {
                    int colon = lines[index].indexOf(':');
                    if (colon < 0) {
                        continue;
                    }
                    String name = lines[index].substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    String value = lines[index].substring(colon + 1).trim();
                    responseHeaders.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
                }
                for (String cookie : responseHeaders.getOrDefault("set-cookie", List.of())) {
                    String pair = cookie.split(";", 2)[0];
                    int eq = pair.indexOf('=');
                    if (eq > 0) {
                        cookies.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                    }
                }
                byte[] responseBody = readBody(in, responseHeaders);
                return new Exchange(status, new String(responseBody, StandardCharsets.UTF_8));
            }
        }

        private static byte[] readHeader(InputStream in) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int matched = 0;
            byte[] marker = {'\r', '\n', '\r', '\n'};
            while (matched < marker.length) {
                int value = in.read();
                if (value < 0) {
                    throw new IOException("响应头不完整");
                }
                out.write(value);
                if (value == marker[matched]) {
                    matched++;
                } else {
                    matched = value == '\r' ? 1 : 0;
                }
                if (out.size() > 100_000) {
                    throw new IOException("响应头过大");
                }
            }
            return out.toByteArray();
        }

        private static byte[] readBody(InputStream in, Map<String, List<String>> headers) throws IOException {
            List<String> encoding = headers.getOrDefault("transfer-encoding", List.of());
            if (!encoding.isEmpty() && encoding.get(0).toLowerCase(Locale.ROOT).contains("chunked")) {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                while (true) {
                    String sizeLine = readLine(in);
                    int semi = sizeLine.indexOf(';');
                    if (semi >= 0) {
                        sizeLine = sizeLine.substring(0, semi);
                    }
                    int size = Integer.parseInt(sizeLine.trim(), 16);
                    if (size == 0) {
                        while (!readLine(in).isEmpty()) {
                            // 丢掉 chunk 尾部头。
                        }
                        break;
                    }
                    if (body.size() + size > BODY_LIMIT) {
                        throw new IOException("响应体过大");
                    }
                    byte[] chunk = in.readNBytes(size);
                    if (chunk.length != size) {
                        throw new IOException("分块响应不完整");
                    }
                    body.write(chunk);
                    readLine(in);
                }
                return body.toByteArray();
            }
            List<String> lengths = headers.getOrDefault("content-length", List.of());
            if (!lengths.isEmpty()) {
                int length = Integer.parseInt(lengths.get(0).trim());
                if (length > BODY_LIMIT) {
                    throw new IOException("响应体过大");
                }
                byte[] body = in.readNBytes(length);
                if (body.length != length) {
                    throw new IOException("响应体不完整");
                }
                return body;
            }
            byte[] body = in.readNBytes(BODY_LIMIT + 1);
            if (body.length > BODY_LIMIT) {
                throw new IOException("响应体过大");
            }
            return body;
        }

        private static String readLine(InputStream in) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (true) {
                int value = in.read();
                if (value < 0 || value == '\n') {
                    break;
                }
                if (value != '\r') {
                    out.write(value);
                }
            }
            return out.toString(StandardCharsets.ISO_8859_1);
        }
    }

    private static final class ProbeException extends RuntimeException {
        private final int code;

        private ProbeException(int code, String message) {
            super(message);
            this.code = code;
        }
    }
}
