import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Main {
    private static final String HOST = env("BIND_ADDRESS", "127.0.0.1");
    private static final int PORT = integerEnv("PORT", 8080);
    private static final Path ROOT = Paths.get("").toAbsolutePath().normalize();
    private static final Path WEB_ROOT = ROOT.resolve("web").normalize();
    private static final Path DATA_FILE = Paths.get(env("DATA_FILE",
            ROOT.resolve("makerere-election-monitor.tsv").toString())).toAbsolutePath().normalize();
    private static final boolean SECURE_COOKIE = Boolean.parseBoolean(env("SESSION_COOKIE_SECURE", "false"));
    private static final Object DATA_LOCK = new Object();
    private static final int PASSWORD_ITERATIONS = 210000;
    private static final int SESSION_HOURS = 12;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<String, Session>();
    private static final List<SseClient> LIVE_CLIENTS = new CopyOnWriteArrayList<SseClient>();
    private static Data data;

    public static void main(String[] args) throws IOException {
        data = loadData();
        initializeAdministrator();
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName(HOST), PORT), 0);
        server.createContext("/healthz", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method not allowed.", "text/plain; charset=utf-8");
                return;
            }
            sendText(exchange, 200, "ok", "text/plain; charset=utf-8");
        });
        server.createContext("/api/", Main::handleApi);
        server.createContext("/", Main::handleStaticFile);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        String address = "http://" + (HOST.equals("0.0.0.0") ? "127.0.0.1" : HOST) + ":" + PORT;
        System.out.println("Makerere Student Election Monitor is running at " + address);
        System.out.println("Election data: " + DATA_FILE);
        System.out.println("Public deployment must terminate HTTPS at a trusted reverse proxy.");
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(address));
            }
        } catch (Exception e) {
            System.out.println("Open " + address + " in your browser to use the dashboard.");
        }
    }

    private static void handleApi(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if ("GET".equals(method) && "/api/session".equals(path)) {
            sendJson(exchange, 200, sessionJson(currentUser(exchange)));
            return;
        }
        if ("GET".equals(method) && "/api/public-state".equals(path)) {
            synchronized (DATA_LOCK) {
                sendJson(exchange, 200, stateJson(null));
            }
            return;
        }
        if ("GET".equals(method) && "/api/state".equals(path)) {
            User user = currentUser(exchange);
            if (user == null) {
                sendJson(exchange, 401, "{\"error\":\"Sign in to view the monitoring workspace.\"}");
                return;
            }
            synchronized (DATA_LOCK) {
                sendJson(exchange, 200, stateJson(user));
            }
            return;
        }
        if ("GET".equals(method) && "/api/events".equals(path)) {
            streamEvents(exchange);
            return;
        }
        if (!"POST".equals(method)) {
            sendJson(exchange, 405, "{\"error\":\"Method not allowed.\"}");
            return;
        }
        if (!isAllowedOrigin(exchange)
                || !"fetch".equals(exchange.getRequestHeaders().getFirst("X-Requested-With"))) {
            sendJson(exchange, 403, "{\"error\":\"Requests from other websites are not allowed.\"}");
            return;
        }

        try {
            Map<String, String> fields = readForm(exchange);
            if ("/api/login".equals(path)) {
                login(exchange, fields);
                return;
            }
            if ("/api/logout".equals(path)) {
                logout(exchange);
                sendJson(exchange, 200, "{\"ok\":true}");
                return;
            }
            User user = currentUser(exchange);
            if (user == null) {
                sendJson(exchange, 401, "{\"error\":\"Sign in before submitting reports.\"}");
                return;
            }
            if (user.mustChangePassword && !"/api/account/password".equals(path)) {
                sendJson(exchange, 403, "{\"error\":\"Change your one-time password before continuing.\"}");
                return;
            }
            synchronized (DATA_LOCK) {
                switch (path) {
                    case "/api/account/password":
                        changePassword(user, fields, sessionToken(exchange));
                        break;
                    case "/api/title":
                        requireAdmin(user);
                        updateTitle(fields);
                        break;
                    case "/api/candidates":
                        requireAdmin(user);
                        addCandidate(fields);
                        break;
                    case "/api/races":
                        requireAdmin(user);
                        addRace(fields);
                        break;
                    case "/api/stations":
                        requireAdmin(user);
                        addStation(fields);
                        break;
                    case "/api/agents":
                        requireAdmin(user);
                        addAgent(fields);
                        break;
                    case "/api/agents/disable":
                        requireAdmin(user);
                        disableAgent(fields);
                        break;
                    case "/api/agents/enable":
                        requireAdmin(user);
                        enableAgent(fields);
                        break;
                    case "/api/agents/assignment":
                        requireAdmin(user);
                        updateAgentAssignment(fields);
                        break;
                    case "/api/agents/reset-password":
                        requireAdmin(user);
                        resetAgentPassword(fields);
                        break;
                    case "/api/tallies":
                        recordDeclaration(fields, user);
                        break;
                    case "/api/incidents":
                        addIncident(fields, user);
                        break;
                    case "/api/incidents/resolve":
                        requireAdmin(user);
                        setIncidentStatus(fields, user, "OPEN", "RESOLVED");
                        break;
                    case "/api/incidents/reopen":
                        requireAdmin(user);
                        setIncidentStatus(fields, user, "RESOLVED", "OPEN");
                        break;
                    default:
                        sendJson(exchange, 404, "{\"error\":\"Endpoint not found.\"}");
                        return;
                }
                sendJson(exchange, 200, stateJson(user));
            }
            notifyLiveClients();
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, "{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}");
        } catch (IOException e) {
            System.err.println("Could not save election data: " + e.getMessage());
            sendJson(exchange, 500, "{\"error\":\"Could not save the change. Check the configured persistent data storage.\"}");
        }
    }

    private static void initializeAdministrator() throws IOException {
        for (User user : data.users.values()) {
            if ("ADMIN".equals(user.role) && user.enabled) {
                return;
            }
        }
        String initialPassword = System.getenv("ADMIN_INITIAL_PASSWORD");
        String username = env("ADMIN_INITIAL_USERNAME", "admin");
        if (initialPassword == null || initialPassword.isEmpty()) {
            if ("0.0.0.0".equals(HOST)) {
                throw new IOException("Set ADMIN_INITIAL_PASSWORD before starting a public deployment.");
            }
            initialPassword = randomToken(18);
            System.out.println("Initial administrator username: " + username);
            System.out.println("Initial administrator password (save it now): " + initialPassword);
        }
        if (initialPassword.length() < 12) {
            throw new IOException("ADMIN_INITIAL_PASSWORD must contain at least 12 characters.");
        }
        User administrator = createUser(nextId(data.users), username, "Election Administrator",
                "ADMIN", 0, initialPassword);
        data.users.put(administrator.id, administrator);
        saveData();
    }

    private static void login(HttpExchange exchange, Map<String, String> fields) throws IOException {
        String username = requiredText(fields, "username");
        String password = fields.get("password");
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Enter your password.");
        }
        String attemptKey = exchange.getRemoteAddress().getAddress().getHostAddress()
                + ":" + username.toLowerCase();
        synchronized (LOGIN_FAILURES) {
            LoginFailure failure = LOGIN_FAILURES.get(attemptKey);
            if (failure != null && failure.lockedUntil > System.currentTimeMillis()) {
                sendJson(exchange, 429, "{\"error\":\"Too many sign-in attempts. Try again in 15 minutes.\"}");
                return;
            }
        }
        User user;
        synchronized (DATA_LOCK) {
            user = findUser(username);
            if (user == null || !user.enabled || !verifyPassword(password, user.passwordSalt, user.passwordHash)) {
                recordLoginFailure(attemptKey);
                sendJson(exchange, 401, "{\"error\":\"Username or password is incorrect.\"}");
                return;
            }
        }
        synchronized (LOGIN_FAILURES) {
            LOGIN_FAILURES.remove(attemptKey);
        }
        String token = randomToken(32);
        SESSIONS.put(token, new Session(user.id, System.currentTimeMillis()));
        exchange.getResponseHeaders().add("Set-Cookie", sessionCookie(token, SESSION_HOURS * 60 * 60));
        sendJson(exchange, 200, "{\"user\":" + userJson(user) + "}");
    }

    private static void logout(HttpExchange exchange) {
        String token = sessionToken(exchange);
        if (token != null) {
            SESSIONS.remove(token);
        }
        exchange.getResponseHeaders().add("Set-Cookie", sessionCookie("", 0));
    }

    private static void changePassword(User user, Map<String, String> fields, String currentToken)
            throws IOException {
        String current = fields.get("currentPassword");
        String next = fields.get("newPassword");
        if (current == null || !verifyPassword(current, user.passwordSalt, user.passwordHash)) {
            throw new IllegalArgumentException("Current password is incorrect.");
        }
        if (next == null || next.length() < 12 || next.length() > 256) {
            throw new IllegalArgumentException("New password must contain at least 12 characters.");
        }
        String oldSalt = user.passwordSalt;
        String oldHash = user.passwordHash;
        boolean oldMustChangePassword = user.mustChangePassword;
        setPassword(user, next);
        user.mustChangePassword = false;
        try {
            saveData();
        } catch (IOException e) {
            user.passwordSalt = oldSalt;
            user.passwordHash = oldHash;
            user.mustChangePassword = oldMustChangePassword;
            throw e;
        }
        invalidateUserSessions(user.id, currentToken);
    }

    private static void recordLoginFailure(String key) {
        synchronized (LOGIN_FAILURES) {
            long now = System.currentTimeMillis();
            LoginFailure failure = LOGIN_FAILURES.get(key);
            if (failure == null || now - failure.firstFailure > 15 * 60 * 1000L) {
                failure = new LoginFailure(now);
                LOGIN_FAILURES.put(key, failure);
            }
            failure.count++;
            if (failure.count >= 5) {
                failure.lockedUntil = now + 15 * 60 * 1000L;
            }
            if (LOGIN_FAILURES.size() > 2000) {
                Iterator<String> iterator = LOGIN_FAILURES.keySet().iterator();
                if (iterator.hasNext()) {
                    LOGIN_FAILURES.remove(iterator.next());
                }
            }
        }
    }

    private static User findUser(String username) {
        for (User user : data.users.values()) {
            if (user.username.equalsIgnoreCase(username)) {
                return user;
            }
        }
        return null;
    }

    private static User currentUser(HttpExchange exchange) {
        String token = sessionToken(exchange);
        if (token == null) {
            return null;
        }
        Session session = SESSIONS.get(token);
        if (session == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now - session.lastSeen > TimeUnit.HOURS.toMillis(SESSION_HOURS)) {
            SESSIONS.remove(token);
            return null;
        }
        session.lastSeen = now;
        synchronized (DATA_LOCK) {
            User user = data.users.get(session.userId);
            return user != null && user.enabled ? user : null;
        }
    }

    private static void invalidateUserSessions(int userId, String exceptToken) {
        for (Map.Entry<String, Session> entry : SESSIONS.entrySet()) {
            if (entry.getValue().userId == userId && !entry.getKey().equals(exceptToken)) {
                SESSIONS.remove(entry.getKey());
            }
        }
    }

    private static String sessionToken(HttpExchange exchange) {
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) {
            return null;
        }
        for (String part : cookie.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && "monitor_session".equals(pair[0])) {
                return pair[1];
            }
        }
        return null;
    }

    private static String sessionCookie(String token, int maxAge) {
        return "monitor_session=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age="
                + maxAge + (SECURE_COOKIE ? "; Secure" : "");
    }

    private static void requireAdmin(User user) {
        if (!"ADMIN".equals(user.role)) {
            throw new IllegalArgumentException("This action requires an administrator account.");
        }
    }

    private static String userJson(User user) {
        return "{\"id\":" + user.id + ",\"name\":\"" + jsonEscape(user.name)
                + "\",\"username\":\"" + jsonEscape(user.username)
                + "\",\"role\":\"" + user.role + "\",\"centerId\":"
                + (user.centerId == 0 ? "null" : user.centerId)
                + ",\"mustChangePassword\":" + user.mustChangePassword + "}";
    }

    private static String userName(int userId, String fallback) {
        User user = data.users.get(userId);
        return user == null ? fallback : user.name;
    }

    private static String sessionJson(User user) {
        return "{\"user\":" + (user == null ? "null" : userJson(user)) + "}";
    }

    private static void streamEvents(HttpExchange exchange) throws IOException {
        if (LIVE_CLIENTS.size() >= 250) {
            sendText(exchange, 503, "Live update capacity reached.", "text/plain; charset=utf-8");
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);
        SseClient client = new SseClient();
        LIVE_CLIENTS.add(client);
        try {
            exchange.getResponseBody().write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            while (true) {
                String event = client.queue.poll(25, TimeUnit.SECONDS);
                String message = event == null ? ": keepalive\n\n" : "event: update\ndata: refresh\n\n";
                exchange.getResponseBody().write(message.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            }
        } catch (IOException e) {
            // Browser closed its live connection.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            LIVE_CLIENTS.remove(client);
            exchange.close();
        }
    }

    private static void notifyLiveClients() {
        for (SseClient client : LIVE_CLIENTS) {
            client.queue.offer("update");
        }
    }

    private static String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static User createUser(int id, String username, String name, String role,
                                   int centerId, String password) {
        String normalized = username.trim().toLowerCase();
        if (!normalized.matches("[a-z0-9._-]{3,40}")) {
            throw new IllegalArgumentException("Username must be 3-40 characters using letters, numbers, '.', '_' or '-'.");
        }
        User user = new User(id, normalized, name.trim(), role, centerId, "", "", true);
        setPassword(user, password);
        return user;
    }

    private static void setPassword(User user, String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        user.passwordSalt = Base64.getEncoder().encodeToString(salt);
        user.passwordHash = hashPassword(password, salt);
    }

    private static String hashPassword(String password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PASSWORD_ITERATIONS, 256);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return Base64.getEncoder().encodeToString(factory.generateSecret(spec).getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("Password hashing is unavailable.", e);
        } finally {
            spec.clearPassword();
        }
    }

    private static boolean verifyPassword(String password, String saltValue, String hashValue) {
        try {
            byte[] salt = Base64.getDecoder().decode(saltValue);
            byte[] expected = Base64.getDecoder().decode(hashValue);
            byte[] actual = Base64.getDecoder().decode(hashPassword(password, salt));
            return java.security.MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static int integerEnv(String key, int fallback) {
        try {
            return Integer.parseInt(env(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a whole number.", e);
        }
    }

    private static boolean isAllowedOrigin(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null) {
            return true;
        }
        String host = exchange.getRequestHeaders().getFirst("Host");
        String forwardedProto = exchange.getRequestHeaders().getFirst("X-Forwarded-Proto");
        String scheme = forwardedProto == null ? "http" : forwardedProto.split(",", 2)[0].trim();
        return host != null && (scheme + "://" + host).equalsIgnoreCase(origin);
    }

    private static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.startsWith("application/x-www-form-urlencoded")) {
            throw new IllegalArgumentException("Expected form-encoded request data.");
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int total = 0;
        int count;
        while ((count = exchange.getRequestBody().read(chunk)) != -1) {
            total += count;
            if (total > 32768) {
                throw new IllegalArgumentException("Request is too large.");
            }
            buffer.write(chunk, 0, count);
        }
        String body = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        Map<String, String> fields = new LinkedHashMap<String, String>();
        if (body.isEmpty()) {
            return fields;
        }
        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 1
                    ? ""
                    : URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            if (fields.put(key, value) != null) {
                throw new IllegalArgumentException("Duplicate form field: " + key);
            }
        }
        return fields;
    }

    private static void updateTitle(Map<String, String> fields) throws IOException {
        String title = requiredText(fields, "title");
        String previous = data.title;
        data.title = title;
        try {
            saveData();
        } catch (IOException e) {
            data.title = previous;
            throw e;
        }
    }

    private static void addCandidate(Map<String, String> fields) throws IOException {
        String name = requiredText(fields, "name");
        int raceId = positiveInt(fields, "raceId");
        if (!data.races.containsKey(raceId)) {
            throw new IllegalArgumentException("Select a valid post.");
        }
        int id = nextId(data.candidates);
        data.candidates.put(id, new Candidate(id, raceId, name));
        try {
            saveData();
        } catch (IOException e) {
            data.candidates.remove(id);
            throw e;
        }
    }

    private static void addRace(Map<String, String> fields) throws IOException {
        String name = requiredText(fields, "name");
        String category = requiredText(fields, "category");
        if (!isValidRaceCategory(category)) {
            throw new IllegalArgumentException("Select a valid official post category.");
        }
        for (Race race : data.races.values()) {
            if (race.name.equalsIgnoreCase(name)) {
                throw new IllegalArgumentException("A post with this name already exists.");
            }
        }
        int id = nextId(data.races);
        data.races.put(id, new Race(id, category, name));
        try {
            saveData();
        } catch (IOException e) {
            data.races.remove(id);
            throw e;
        }
    }

    private static void addStation(Map<String, String> fields) throws IOException {
        String name = requiredText(fields, "name");
        String college = optionalText(fields, "college");
        String school = optionalText(fields, "school");
        if (college.isEmpty() && school.isEmpty()) {
            throw new IllegalArgumentException("Select a college or a standalone school / institute.");
        }
        if (!college.isEmpty()) {
            school = "";
        }
        if (name.length() > 100 || college.length() > 120 || school.length() > 120) {
            throw new IllegalArgumentException("Tally-centre and academic-unit names are too long.");
        }
        for (Station existing : data.stations.values()) {
            if (existing.name.equalsIgnoreCase(name)
                    && existing.college.equalsIgnoreCase(college)
                    && existing.school.equalsIgnoreCase(school)) {
                throw new IllegalArgumentException("This tally centre is already configured.");
            }
        }
        int id = nextId(data.stations);
        data.stations.put(id, new Station(id, name, college, school));
        try {
            saveData();
        } catch (IOException e) {
            data.stations.remove(id);
            throw e;
        }
    }

    private static void addAgent(Map<String, String> fields) throws IOException {
        String name = requiredText(fields, "name");
        String username = requiredText(fields, "username");
        String password = fields.get("password");
        if (name.length() > 100 || password == null
                || password.length() < 12 || password.length() > 256) {
            throw new IllegalArgumentException("Enter a name up to 100 characters and a one-time password of 12-256 characters.");
        }
        int centerId = positiveInt(fields, "centerId");
        if (!data.stations.containsKey(centerId)) {
            throw new IllegalArgumentException("Assign the agent to a valid tally centre.");
        }
        if (findUser(username) != null) {
            throw new IllegalArgumentException("That username is already in use.");
        }
        User user = createUser(nextId(data.users), username, name, "AGENT", centerId, password);
        user.mustChangePassword = true;
        data.users.put(user.id, user);
        try {
            saveData();
        } catch (IOException e) {
            data.users.remove(user.id);
            throw e;
        }
    }

    private static void disableAgent(Map<String, String> fields) throws IOException {
        int id = positiveInt(fields, "id");
        User agent = data.users.get(id);
        if (agent == null || !"AGENT".equals(agent.role) || !agent.enabled) {
            throw new IllegalArgumentException("Select an active agent account.");
        }
        agent.enabled = false;
        try {
            saveData();
        } catch (IOException e) {
            agent.enabled = true;
            throw e;
        }
        invalidateUserSessions(agent.id, null);
    }

    private static void enableAgent(Map<String, String> fields) throws IOException {
        int id = positiveInt(fields, "id");
        User agent = data.users.get(id);
        if (agent == null || !"AGENT".equals(agent.role) || agent.enabled) {
            throw new IllegalArgumentException("Select a disabled agent account.");
        }
        agent.enabled = true;
        try {
            saveData();
        } catch (IOException e) {
            agent.enabled = false;
            throw e;
        }
    }

    private static void updateAgentAssignment(Map<String, String> fields) throws IOException {
        int id = positiveInt(fields, "id");
        User agent = data.users.get(id);
        if (agent == null || !"AGENT".equals(agent.role)) {
            throw new IllegalArgumentException("Select a valid field-agent account.");
        }
        int centerId = positiveInt(fields, "centerId");
        if (!data.stations.containsKey(centerId)) {
            throw new IllegalArgumentException("Assign the agent to a valid tally centre.");
        }
        if (agent.centerId == centerId) {
            throw new IllegalArgumentException("The agent is already assigned to that tally centre.");
        }
        int oldCenterId = agent.centerId;
        agent.centerId = centerId;
        try {
            saveData();
        } catch (IOException e) {
            agent.centerId = oldCenterId;
            throw e;
        }
        invalidateUserSessions(agent.id, null);
    }

    private static void resetAgentPassword(Map<String, String> fields) throws IOException {
        int id = positiveInt(fields, "id");
        User agent = data.users.get(id);
        String password = fields.get("password");
        if (agent == null || !"AGENT".equals(agent.role) || !agent.enabled) {
            throw new IllegalArgumentException("Select an active field-agent account.");
        }
        if (password == null || password.length() < 12 || password.length() > 256) {
            throw new IllegalArgumentException("One-time password must contain 12-256 characters.");
        }
        String oldSalt = agent.passwordSalt;
        String oldHash = agent.passwordHash;
        boolean oldMustChangePassword = agent.mustChangePassword;
        setPassword(agent, password);
        agent.mustChangePassword = true;
        try {
            saveData();
        } catch (IOException e) {
            agent.passwordSalt = oldSalt;
            agent.passwordHash = oldHash;
            agent.mustChangePassword = oldMustChangePassword;
            throw e;
        }
        invalidateUserSessions(agent.id, null);
    }

    private static void recordDeclaration(Map<String, String> fields, User user) throws IOException {
        int raceId = positiveInt(fields, "raceId");
        if (!data.races.containsKey(raceId)) {
            throw new IllegalArgumentException("Select a valid post.");
        }
        int centerId = positiveInt(fields, "centerId");
        Station station = data.stations.get(centerId);
        if (station == null) {
            throw new IllegalArgumentException("Select a valid tally centre.");
        }
        if ("AGENT".equals(user.role) && user.centerId != centerId) {
            throw new IllegalArgumentException("You can submit reports only for your assigned tally centre.");
        }
        List<Candidate> raceCandidates = new ArrayList<Candidate>();
        for (Candidate candidate : data.candidates.values()) {
            if (candidate.raceId == raceId) {
                raceCandidates.add(candidate);
            }
        }
        if (raceCandidates.isEmpty()) {
            throw new IllegalArgumentException("Add candidates to this post before recording results.");
        }
        String source = requiredText(fields, "source");
        if (source.length() > 240) {
            throw new IllegalArgumentException("The declaration reference must be 240 characters or fewer.");
        }
        Map<Integer, Integer> votes = new LinkedHashMap<Integer, Integer>();
        for (Candidate candidate : raceCandidates) {
            int count = nonNegativeInt(fields, "vote_" + candidate.id);
            votes.put(candidate.id, count);
        }
        int reportId = nextId(data.reports);
        Declaration report = new Declaration(reportId, raceId, centerId, user.id, source, votes, LocalDateTime.now());
        data.reports.put(reportId, report);
        try {
            saveData();
        } catch (IOException e) {
            data.reports.remove(reportId);
            throw e;
        }
    }

    private static void addIncident(Map<String, String> fields, User user) throws IOException {
        String description = requiredText(fields, "description");
        String category = requiredText(fields, "category");
        String priority = requiredText(fields, "priority");
        if (description.length() > 1000) {
            throw new IllegalArgumentException("Observation must be 1000 characters or fewer.");
        }
        if (!isValidIncidentCategory(category)) {
            throw new IllegalArgumentException("Select a valid observation type.");
        }
        if (!isValidIncidentPriority(priority)) {
            throw new IllegalArgumentException("Select a valid observation priority.");
        }
        String stationValue = fields.get("centerId");
        Integer stationId = null;
        if ("AGENT".equals(user.role)) {
            if (stationValue != null && !stationValue.isEmpty()
                    && parseNonNegative(stationValue, "Tally centre") != user.centerId) {
                throw new IllegalArgumentException("You can report observations only for your assigned tally centre.");
            }
            stationId = user.centerId;
        } else if (stationValue != null && !stationValue.isEmpty()) {
            int parsed = parseNonNegative(stationValue, "Polling station");
            if (parsed > 0) {
                if (!data.stations.containsKey(parsed)) {
                    throw new IllegalArgumentException("Select a valid tally centre.");
                }
                stationId = parsed;
            }
        }
        int id = nextId(data.incidents);
        Incident incident = new Incident(id, stationId, description, category, priority,
                user.id, "OPEN", LocalDateTime.now(), null, 0, "");
        data.incidents.put(id, incident);
        try {
            saveData();
        } catch (IOException e) {
            data.incidents.remove(id);
            throw e;
        }
    }

    private static void setIncidentStatus(Map<String, String> fields, User resolver,
                                          String expectedStatus, String targetStatus) throws IOException {
        int id = positiveInt(fields, "id");
        Incident incident = data.incidents.get(id);
        if (incident == null || !expectedStatus.equals(incident.status)) {
            throw new IllegalArgumentException("That observation is no longer " + expectedStatus.toLowerCase() + ".");
        }
        String oldStatus = incident.status;
        LocalDateTime oldResolvedAt = incident.resolvedAt;
        int oldResolvedBy = incident.resolvedBy;
        String oldResolution = incident.resolution;
        incident.status = targetStatus;
        if ("RESOLVED".equals(targetStatus)) {
            incident.resolvedAt = LocalDateTime.now();
            incident.resolvedBy = resolver.id;
            incident.resolution = optionalText(fields, "resolution");
            if (incident.resolution.length() > 500) {
                incident.status = oldStatus;
                incident.resolvedAt = oldResolvedAt;
                incident.resolvedBy = oldResolvedBy;
                incident.resolution = oldResolution;
                throw new IllegalArgumentException("Resolution note must be 500 characters or fewer.");
            }
        } else {
            incident.resolvedAt = null;
            incident.resolvedBy = 0;
            incident.resolution = "";
        }
        try {
            saveData();
        } catch (IOException e) {
            incident.status = oldStatus;
            incident.resolvedAt = oldResolvedAt;
            incident.resolvedBy = oldResolvedBy;
            incident.resolution = oldResolution;
            throw e;
        }
    }

    private static String stateJson(User viewer) {
        StringBuilder json = new StringBuilder("{\"title\":\"")
                .append(jsonEscape(data.title)).append("\",\"races\":[");
        boolean first = true;
        for (Race race : data.races.values()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"id\":").append(race.id)
                    .append(",\"category\":\"").append(race.category)
                    .append("\",\"name\":\"").append(jsonEscape(race.name)).append("\"}");
        }
        json.append("],\"candidates\":[");
        first = true;
        for (Candidate candidate : data.candidates.values()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"id\":").append(candidate.id)
                    .append(",\"raceId\":").append(candidate.raceId)
                    .append(",\"name\":\"").append(jsonEscape(candidate.name)).append("\"}");
        }
        json.append("],\"stations\":[");
        first = true;
        for (Station station : data.stations.values()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"id\":").append(station.id)
                    .append(",\"name\":\"").append(jsonEscape(station.name))
                    .append("\",\"college\":\"").append(jsonEscape(station.college))
                    .append("\",\"school\":\"").append(jsonEscape(station.school)).append("\"}");
        }
        json.append("],\"reports\":[");
        first = true;
        for (Declaration report : data.reports.values()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            User reporter = data.users.get(report.submittedBy);
            json.append("{\"id\":").append(report.id)
                    .append(",\"raceId\":").append(report.raceId)
                    .append(",\"centerId\":").append(report.centerId)
                    .append(",\"source\":\"").append(jsonEscape(report.source))
                    .append("\",\"recordedAt\":\"").append(report.recordedAt)
                    .append("\",\"reportedBy\":\"")
                    .append(viewer != null && "ADMIN".equals(viewer.role) && reporter != null
                            ? jsonEscape(reporter.name) : "Field agent")
                    .append("\",\"votes\":{");
            boolean firstVote = true;
            for (Map.Entry<Integer, Integer> vote : report.votes.entrySet()) {
                if (!firstVote) {
                    json.append(',');
                }
                firstVote = false;
                json.append('"').append(vote.getKey()).append("\":").append(vote.getValue());
            }
            json.append("}}");
        }
        json.append("],\"incidents\":[");
        first = true;
        for (Incident incident : data.incidents.values()) {
            if (viewer == null || ("AGENT".equals(viewer.role)
                    && (incident.stationId == null || incident.stationId != viewer.centerId))) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"id\":").append(incident.id)
                    .append(",\"centerId\":")
                    .append(incident.stationId == null ? "null" : incident.stationId)
                    .append(",\"description\":\"").append(jsonEscape(incident.description))
                    .append("\",\"category\":\"").append(incident.category)
                    .append("\",\"priority\":\"").append(incident.priority)
                    .append("\",\"status\":\"").append(incident.status)
                    .append("\",\"createdAt\":\"").append(incident.createdAt)
                    .append("\",\"reportedBy\":\"")
                    .append(viewer != null && "ADMIN".equals(viewer.role)
                            ? jsonEscape(userName(incident.submittedBy, "Field agent")) : "Field agent")
                    .append("\",\"resolvedAt\":")
                    .append(incident.resolvedAt == null ? "null" : "\"" + incident.resolvedAt + "\"")
                    .append(",\"resolvedBy\":\"")
                    .append(viewer != null && "ADMIN".equals(viewer.role)
                            ? jsonEscape(userName(incident.resolvedBy, "")) : "")
                    .append("\",\"resolution\":\"").append(jsonEscape(incident.resolution)).append("\"}");
        }
        json.append("],\"users\":[");
        first = true;
        if (viewer != null && "ADMIN".equals(viewer.role)) {
            for (User user : data.users.values()) {
                if (!"AGENT".equals(user.role)) {
                    continue;
                }
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append("{\"id\":").append(user.id)
                        .append(",\"name\":\"").append(jsonEscape(user.name))
                        .append("\",\"username\":\"").append(jsonEscape(user.username))
                        .append("\",\"centerId\":").append(user.centerId)
                        .append(",\"enabled\":").append(user.enabled)
                        .append(",\"mustChangePassword\":").append(user.mustChangePassword).append("}");
            }
        }
        return json.append("]}").toString();
    }

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '\b':
                    escaped.append("\\b");
                    break;
                case '\f':
                    escaped.append("\\f");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
            }
        }
        return escaped.toString();
    }

    private static String requiredText(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Please enter " + key + ".");
        }
        return value.trim();
    }

    private static String optionalText(Map<String, String> fields, String key) {
        String value = fields.get(key);
        return value == null ? "" : value.trim();
    }

    private static int positiveInt(Map<String, String> fields, String key) {
        int value = parseNonNegative(requiredText(fields, key), key);
        if (value == 0) {
            throw new IllegalArgumentException(key + " must be greater than zero.");
        }
        return value;
    }

    private static int nonNegativeInt(Map<String, String> fields, String key) {
        return parseNonNegative(requiredText(fields, key), key);
    }

    private static int parseNonNegative(String value, String label) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new IllegalArgumentException(label + " cannot be negative.");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be a whole number.");
        }
    }

    private static int nextId(Map<Integer, ?> records) {
        int highest = 0;
        for (Integer id : records.keySet()) {
            highest = Math.max(highest, id);
        }
        if (highest == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("No IDs remain for another record.");
        }
        return highest + 1;
    }

    private static String tallyKey(int raceId, int stationId) {
        return raceId + ":" + stationId;
    }

    private static boolean isValidRaceCategory(String category) {
        switch (category) {
            case "GUILD_PRESIDENT":
            case "CGC_CHAIRPERSON":
            case "GRC":
            case "CGC":
            case "GUILD_TRIBUNAL":
            case "DEBATING_UNION":
            case "GAMES_UNION":
            case "HALL_CHAIRPERSON":
            case "GRC_HALL":
            case "SCR_GENERAL_SECRETARY":
            case "SCR_FINANCE_SECRETARY":
            case "SCR_DISCIPLINARY_SECRETARY":
            case "SCR_HEALTH_SECRETARY":
            case "SCR_SPORTS_SECRETARY":
            case "SCR_DISABILITIES_SECRETARY":
            case "GRC_EXTERNAL_CAMPUS":
            case "EXTERNAL_CAMPUS_EC":
            case "DISABILITY_REPRESENTATIVES":
            case "INTERNATIONAL_REPRESENTATIVES":
            case "POSTGRADUATE_UNION_REPRESENTATIVES":
            case "OTHER":
                return true;
            default:
                return false;
        }
    }

    private static boolean isValidIncidentCategory(String category) {
        switch (category) {
            case "TALLY_DISCREPANCY":
            case "ACCESS_QUEUE":
            case "EQUIPMENT_CONNECTIVITY":
            case "STAFFING_PROCESS":
            case "VOTER_ASSISTANCE":
            case "OTHER":
                return true;
            default:
                return false;
        }
    }

    private static boolean isValidIncidentPriority(String priority) {
        return "NORMAL".equals(priority) || "URGENT".equals(priority);
    }

    private static void handleStaticFile(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method not allowed.", "text/plain; charset=utf-8");
            return;
        }
        String requested = exchange.getRequestURI().getPath();
        String relative = "/".equals(requested) ? "index.html" : requested.substring(1);
        Path file = WEB_ROOT.resolve(relative).normalize();
        if (!file.startsWith(WEB_ROOT) || !Files.isRegularFile(file)) {
            sendText(exchange, 404, "Not found.", "text/plain; charset=utf-8");
            return;
        }
        byte[] content = Files.readAllBytes(file);
        String type = contentType(file);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                        + "connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'none'; "
                        + "frame-ancestors 'none'; form-action 'self'");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, content.length);
        exchange.getResponseBody().write(content);
        exchange.close();
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (name.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (name.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (name.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        sendText(exchange, status, json, "application/json; charset=utf-8");
    }

    private static void sendText(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (SECURE_COOKIE) {
            exchange.getResponseHeaders().set("Strict-Transport-Security", "max-age=31536000");
        }
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static Data loadData() throws IOException {
        Data loaded = new Data();
        if (!Files.exists(DATA_FILE)) {
            return loaded;
        }
        List<String> lines = Files.readAllLines(DATA_FILE, StandardCharsets.UTF_8);
        if (lines.isEmpty() || (!"MAKERERE_MONITOR\t2".equals(lines.get(0))
                && !"MAKERERE_MONITOR\t3".equals(lines.get(0))
                && !"MAKERERE_MONITOR\t4".equals(lines.get(0)))) {
            throw new IOException("unrecognized or unsupported data-file format");
        }
        boolean legacy = "MAKERERE_MONITOR\t2".equals(lines.get(0));
        boolean current = "MAKERERE_MONITOR\t4".equals(lines.get(0));
        Map<String, Declaration> legacyReports = new LinkedHashMap<String, Declaration>();
        for (int lineNumber = 1; lineNumber < lines.size(); lineNumber++) {
            String[] fields = lines.get(lineNumber).split("\t", -1);
            try {
                switch (fields[0]) {
                    case "E":
                        requireFields(fields, 2);
                        loaded.title = decode(fields[1]);
                        break;
                    case "R": {
                        requireFields(fields, 4);
                        int id = positiveId(fields[1]);
                        String category = fields[2];
                        if (!isValidRaceCategory(category)) {
                            throw new IllegalArgumentException("invalid post category");
                        }
                        putUnique(loaded.races, id, new Race(id, category, decode(fields[3])));
                        break;
                    }
                    case "C": {
                        requireFields(fields, 4);
                        int id = positiveId(fields[1]);
                        int raceId = positiveId(fields[2]);
                        putUnique(loaded.candidates, id,
                                new Candidate(id, raceId, decode(fields[3])));
                        break;
                    }
                    case "P": {
                        requireFields(fields, legacy ? 3 : 5);
                        int id = positiveId(fields[1]);
                        putUnique(loaded.stations, id, new Station(id, decode(fields[2]),
                                legacy ? "" : decode(fields[3]), legacy ? "" : decode(fields[4])));
                        break;
                    }
                    case "T": {
                        if (legacy) {
                            requireFields(fields, 5);
                            int raceId = positiveId(fields[1]);
                            int centerId = positiveId(fields[2]);
                            String key = tallyKey(raceId, centerId);
                            putUnique(legacyReports, key, new Declaration(
                                    legacyReports.size() + 1, raceId, centerId, 0,
                                    "Imported legacy tally; source was not recorded.",
                                    new LinkedHashMap<Integer, Integer>(), LocalDateTime.parse(fields[4])));
                        } else {
                            requireFields(fields, 7);
                            int id = positiveId(fields[1]);
                            int raceId = positiveId(fields[2]);
                            int centerId = positiveId(fields[3]);
                            Declaration report = new Declaration(id, raceId, centerId,
                                    nonNegativeInteger(fields[4]), decode(fields[5]),
                                    new LinkedHashMap<Integer, Integer>(), LocalDateTime.parse(fields[6]));
                            putUnique(loaded.reports, id, report);
                        }
                        break;
                    }
                    case "V": {
                        if (legacy) {
                            requireFields(fields, 5);
                            int raceId = positiveId(fields[1]);
                            int centerId = positiveId(fields[2]);
                            Declaration report = legacyReports.get(tallyKey(raceId, centerId));
                            if (report == null) {
                                throw new IllegalArgumentException("vote record has no race tally");
                            }
                            putUnique(report.votes, positiveId(fields[3]), nonNegativeInteger(fields[4]));
                        } else {
                            requireFields(fields, 4);
                            int reportId = positiveId(fields[1]);
                            Declaration report = findReport(loaded.reports, reportId);
                            if (report == null) {
                                throw new IllegalArgumentException("vote record has no declaration");
                            }
                            putUnique(report.votes, positiveId(fields[2]), nonNegativeInteger(fields[3]));
                        }
                        break;
                    }
                    case "U": {
                        requireFields(fields, current ? 10 : 9);
                        int id = positiveId(fields[1]);
                        String role = fields[4];
                        if (!"ADMIN".equals(role) && !"AGENT".equals(role)) {
                            throw new IllegalArgumentException("invalid user role");
                        }
                        User user = new User(id, decode(fields[2]), decode(fields[3]), role,
                                nonNegativeInteger(fields[5]), fields[6], fields[7],
                                Boolean.parseBoolean(fields[8]));
                        user.mustChangePassword = current && Boolean.parseBoolean(fields[9]);
                        putUnique(loaded.users, id, user);
                        break;
                    }
                    case "I": {
                        requireFields(fields, current ? 12 : 6);
                        int id = positiveId(fields[1]);
                        int station = nonNegativeInteger(fields[2]);
                        String status = fields[4];
                        if (!"OPEN".equals(status) && !"RESOLVED".equals(status)) {
                            throw new IllegalArgumentException("invalid incident status");
                        }
                        String category = current ? fields[6] : "OTHER";
                        String priority = current ? fields[7] : "NORMAL";
                        if (!isValidIncidentCategory(category) || !isValidIncidentPriority(priority)) {
                            throw new IllegalArgumentException("invalid observation classification");
                        }
                        LocalDateTime resolvedAt = current && !fields[9].isEmpty()
                                ? LocalDateTime.parse(fields[9]) : null;
                        Incident incident = new Incident(id, station == 0 ? null : station,
                                decode(fields[5]), category, priority,
                                current ? nonNegativeInteger(fields[8]) : 0,
                                status, LocalDateTime.parse(fields[3]), resolvedAt,
                                current ? nonNegativeInteger(fields[10]) : 0,
                                current ? decode(fields[11]) : "");
                        putUnique(loaded.incidents, id, incident);
                        break;
                    }
                    default:
                        throw new IllegalArgumentException("unknown record type");
                }
            } catch (RuntimeException e) {
                throw new IOException("invalid data on line " + (lineNumber + 1) + ": " + e.getMessage(), e);
            }
        }
        if (legacy) {
            for (Declaration report : legacyReports.values()) {
                putUnique(loaded.reports, report.id, report);
            }
        }
        validateReferences(loaded);
        return loaded;
    }

    private static void validateReferences(Data loaded) throws IOException {
        for (Declaration report : loaded.reports.values()) {
            if (!loaded.races.containsKey(report.raceId)) {
                throw new IOException("report references missing race #" + report.raceId);
            }
            if (!loaded.stations.containsKey(report.centerId)) {
                throw new IOException("report references missing tally centre #" + report.centerId);
            }
            long total = 0;
            for (Map.Entry<Integer, Integer> vote : report.votes.entrySet()) {
                Candidate candidate = loaded.candidates.get(vote.getKey());
                if (candidate == null || candidate.raceId != report.raceId) {
                    throw new IOException("report references a candidate outside race #" + report.raceId);
                }
                total += vote.getValue();
            }
        }
        for (Candidate candidate : loaded.candidates.values()) {
            if (!loaded.races.containsKey(candidate.raceId)) {
                throw new IOException("candidate #" + candidate.id + " references a missing race");
            }
        }
        for (Incident incident : loaded.incidents.values()) {
            if (incident.stationId != null && !loaded.stations.containsKey(incident.stationId)) {
                throw new IOException("incident #" + incident.id + " references a missing tally centre");
            }
            if (incident.submittedBy != 0 && !loaded.users.containsKey(incident.submittedBy)) {
                throw new IOException("observation #" + incident.id + " references a missing reporter");
            }
            if (incident.resolvedBy != 0 && !loaded.users.containsKey(incident.resolvedBy)) {
                throw new IOException("observation #" + incident.id + " references a missing resolver");
            }
        }
        for (User user : loaded.users.values()) {
            if ("AGENT".equals(user.role) && !loaded.stations.containsKey(user.centerId)) {
                throw new IOException("agent #" + user.id + " references a missing tally centre");
            }
        }
    }

    private static Declaration findReport(Map<Integer, Declaration> reports, int id) {
        return reports.get(id);
    }

    private static void saveData() throws IOException {
        List<String> lines = new ArrayList<String>();
        lines.add("MAKERERE_MONITOR\t4");
        lines.add("E\t" + encode(data.title));
        for (Race race : data.races.values()) {
            lines.add("R\t" + race.id + "\t" + race.category + "\t" + encode(race.name));
        }
        for (Candidate candidate : data.candidates.values()) {
            lines.add("C\t" + candidate.id + "\t" + candidate.raceId + "\t" + encode(candidate.name));
        }
        for (User user : data.users.values()) {
            lines.add("U\t" + user.id + "\t" + encode(user.username) + "\t" + encode(user.name)
                    + "\t" + user.role + "\t" + user.centerId + "\t" + user.passwordSalt
                    + "\t" + user.passwordHash + "\t" + user.enabled + "\t" + user.mustChangePassword);
        }
        for (Station station : data.stations.values()) {
            lines.add("P\t" + station.id + "\t" + encode(station.name) + "\t"
                    + encode(station.college) + "\t" + encode(station.school));
        }
        for (Declaration report : data.reports.values()) {
            lines.add("T\t" + report.id + "\t" + report.raceId + "\t" + report.centerId + "\t"
                    + report.submittedBy + "\t" + encode(report.source) + "\t" + report.recordedAt);
            for (Map.Entry<Integer, Integer> vote : report.votes.entrySet()) {
                lines.add("V\t" + report.id + "\t" + vote.getKey() + "\t" + vote.getValue());
            }
        }
        for (Incident incident : data.incidents.values()) {
            lines.add("I\t" + incident.id + "\t" + (incident.stationId == null ? 0 : incident.stationId)
                    + "\t" + incident.createdAt + "\t" + incident.status + "\t" + encode(incident.description)
                    + "\t" + incident.category + "\t" + incident.priority + "\t" + incident.submittedBy
                    + "\t" + (incident.resolvedAt == null ? "" : incident.resolvedAt)
                    + "\t" + incident.resolvedBy + "\t" + encode(incident.resolution));
        }

        Path temporaryFile = DATA_FILE.resolveSibling(DATA_FILE.getFileName().toString() + ".tmp");
        try {
            Files.write(temporaryFile, lines, StandardCharsets.UTF_8);
            try {
                Files.move(temporaryFile, DATA_FILE, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporaryFile, DATA_FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temporaryFile);
            } catch (IOException cleanupError) {
                e.addSuppressed(cleanupError);
            }
            throw e;
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static int positiveId(String value) {
        int id = Integer.parseInt(value);
        if (id <= 0) {
            throw new IllegalArgumentException("ID must be positive");
        }
        return id;
    }

    private static int nonNegativeInteger(String value) {
        int number = Integer.parseInt(value);
        if (number < 0) {
            throw new IllegalArgumentException("number must not be negative");
        }
        return number;
    }

    private static void requireFields(String[] fields, int expected) {
        if (fields.length != expected) {
            throw new IllegalArgumentException("expected " + expected + " fields");
        }
    }

    private static <K, T> void putUnique(Map<K, T> records, K id, T record) {
        if (records.put(id, record) != null) {
            throw new IllegalArgumentException("duplicate ID #" + id);
        }
    }

    private static final class Data {
        String title = "Makerere University Student Elections";
        final Map<Integer, Race> races = new LinkedHashMap<Integer, Race>();
        final Map<Integer, Candidate> candidates = new LinkedHashMap<Integer, Candidate>();
        final Map<Integer, Station> stations = new LinkedHashMap<Integer, Station>();
        final Map<Integer, User> users = new LinkedHashMap<Integer, User>();
        final Map<Integer, Declaration> reports = new LinkedHashMap<Integer, Declaration>();
        final Map<Integer, Incident> incidents = new LinkedHashMap<Integer, Incident>();
    }

    private static final class Race {
        final int id;
        final String category;
        final String name;

        Race(int id, String category, String name) {
            this.id = id;
            this.category = category;
            this.name = name;
        }
    }

    private static final class Candidate {
        final int id;
        final int raceId;
        final String name;

        Candidate(int id, int raceId, String name) {
            this.id = id;
            this.raceId = raceId;
            this.name = name;
        }
    }

    private static final class Station {
        final int id;
        final String name;
        final String college;
        final String school;

        Station(int id, String name, String college, String school) {
            this.id = id;
            this.name = name;
            this.college = college;
            this.school = school;
        }
    }

    private static final class Declaration {
        final int id;
        final int raceId;
        final int centerId;
        final int submittedBy;
        final String source;
        final Map<Integer, Integer> votes;
        final LocalDateTime recordedAt;

        Declaration(int id, int raceId, int centerId, int submittedBy, String source,
                    Map<Integer, Integer> votes, LocalDateTime recordedAt) {
            this.id = id;
            this.raceId = raceId;
            this.centerId = centerId;
            this.submittedBy = submittedBy;
            this.source = source;
            this.votes = votes;
            this.recordedAt = recordedAt;
        }
    }

    private static final class User {
        final int id;
        final String username;
        final String name;
        final String role;
        int centerId;
        String passwordSalt;
        String passwordHash;
        boolean enabled;
        boolean mustChangePassword;

        User(int id, String username, String name, String role, int centerId,
             String passwordSalt, String passwordHash, boolean enabled) {
            this.id = id;
            this.username = username;
            this.name = name;
            this.role = role;
            this.centerId = centerId;
            this.passwordSalt = passwordSalt;
            this.passwordHash = passwordHash;
            this.enabled = enabled;
        }
    }

    private static final class Session {
        final int userId;
        volatile long lastSeen;

        Session(int userId, long lastSeen) {
            this.userId = userId;
            this.lastSeen = lastSeen;
        }
    }

    private static final class SseClient {
        final BlockingQueue<String> queue = new ArrayBlockingQueue<String>(1);
    }

    private static final class LoginFailure {
        final long firstFailure;
        int count;
        long lockedUntil;

        LoginFailure(long firstFailure) {
            this.firstFailure = firstFailure;
        }
    }

    private static final class Incident {
        final int id;
        final Integer stationId;
        final String description;
        final String category;
        final String priority;
        final int submittedBy;
        String status;
        final LocalDateTime createdAt;
        LocalDateTime resolvedAt;
        int resolvedBy;
        String resolution;

        Incident(int id, Integer stationId, String description, String category, String priority,
                 int submittedBy, String status, LocalDateTime createdAt, LocalDateTime resolvedAt,
                 int resolvedBy, String resolution) {
            this.id = id;
            this.stationId = stationId;
            this.description = description;
            this.category = category;
            this.priority = priority;
            this.submittedBy = submittedBy;
            this.status = status;
            this.createdAt = createdAt;
            this.resolvedAt = resolvedAt;
            this.resolvedBy = resolvedBy;
            this.resolution = resolution;
        }
    }

    private static final Map<String, LoginFailure> LOGIN_FAILURES =
            new ConcurrentHashMap<String, LoginFailure>();
}
