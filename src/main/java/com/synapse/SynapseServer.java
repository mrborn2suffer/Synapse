package com.synapse;

import com.fasterxml.jackson.jr.ob.JSON;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class SynapseServer {
    private static final int PORT = 8000;
    private static final String DB_PATH = "candidates.db";
    private static final String MODEL_DIR = ".model_cache";
    private static final String MODEL_PATH = MODEL_DIR + "/model.onnx";
    private static final String VOCAB_PATH = MODEL_DIR + "/vocab.txt";

    private static final Map<String, Map<String, Object>> PROCESSING_TASKS = new ConcurrentHashMap<>();
    private static SemanticScorer semanticScorer = null;
    private static final BM25Scorer bm25Scorer = new BM25Scorer();
    private static String currentJdText = "";

    public static void main(String[] args) {
        try {
            initDb();
            loadCurrentJd();
            initializeModels();
            startServer();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void initDb() throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
             Statement stmt = conn.createStatement()) {
            
            stmt.execute("CREATE TABLE IF NOT EXISTS candidates (" +
                    "candidate_id TEXT PRIMARY KEY," +
                    "rank INTEGER," +
                    "score REAL," +
                    "reasoning TEXT," +
                    "anonymized_name TEXT," +
                    "headline TEXT," +
                    "summary TEXT," +
                    "years_of_experience REAL," +
                    "location TEXT," +
                    "country TEXT," +
                    "current_title TEXT," +
                    "current_company TEXT," +
                    "current_industry TEXT," +
                    "notice_period_days INTEGER," +
                    "is_product_co INTEGER," +
                    "skills_json TEXT," +
                    "career_history_json TEXT," +
                    "education_json TEXT," +
                    "redrob_signals_json TEXT" +
                    ")");

            stmt.execute("CREATE TABLE IF NOT EXISTS system_config (" +
                    "key TEXT PRIMARY KEY," +
                    "value TEXT" +
                    ")");

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_candidates_score ON candidates(score)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_candidates_exp ON candidates(years_of_experience)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_candidates_notice ON candidates(notice_period_days)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_candidates_is_product ON candidates(is_product_co)");
        }
    }

    private static void loadCurrentJd() {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
             PreparedStatement ps = conn.prepareStatement("SELECT value FROM system_config WHERE key = 'jd'")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    currentJdText = rs.getString("value");
                }
            }
        } catch (Exception e) {
            System.err.println("Could not load JD from DB: " + e.getMessage());
        }
        if (currentJdText.isEmpty()) {
            try {
                if (Files.exists(Paths.get("job_description.txt"))) {
                    currentJdText = Files.readString(Paths.get("job_description.txt"), StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                currentJdText = "Senior AI Engineer Applied ML Machine Learning NLP embeddings based retrieval systems";
            }
        }
    }

    private static void initializeModels() {
        new Thread(() -> {
            try {
                File dir = new File(MODEL_DIR);
                if (!dir.exists()) dir.mkdirs();

                File vocabFile = new File(VOCAB_PATH);
                if (!vocabFile.exists()) {
                    System.out.println("Downloading Hugging Face WordPiece vocab.txt...");
                    downloadFile("https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/vocab.txt", vocabFile);
                }

                File modelFile = new File(MODEL_PATH);
                if (!modelFile.exists()) {
                    System.out.println("Downloading Hugging Face all-MiniLM-L6-v2.onnx model (~90MB)...");
                    downloadFile("https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/onnx/model.onnx", modelFile);
                }

                System.out.println("Initializing ONNX Runtime semantic model...");
                semanticScorer = new SemanticScorer(MODEL_PATH, VOCAB_PATH);
                System.out.println("ONNX Runtime semantic model initialized successfully.");

                // Re-build BM25 index on startup
                System.out.println("Building in-memory BM25 structural index...");
                List<Map<String, Object>> allCands = loadAllCandidatesFromDb();
                bm25Scorer.buildIndex(allCands);
                System.out.println("BM25 structural index built on " + allCands.size() + " candidates.");

            } catch (Exception e) {
                System.err.println("Failed to initialize models/index: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }

    private static void downloadFile(String urlStr, File destination) throws IOException {
        java.net.URL url = new java.net.URL(urlStr);
        try (InputStream in = url.openStream();
             OutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[16384];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
    }

    private static void startServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        server.createContext("/", new StaticHandler());
        server.createContext("/api/candidates", new CandidatesHandler());
        server.createContext("/api/jd", new JdHandler());
        server.createContext("/api/upload-chunk", new UploadChunkHandler());
        server.createContext("/api/import-status", new ImportStatusHandler());
        server.createContext("/api/reset", new ResetHandler());
        server.createContext("/api/export-xlsx", new ExportHandler());

        Executor executor;
        try {
            var method = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
            executor = (Executor) method.invoke(null);
            System.out.println("Using Loom Virtual Threads Executor.");
        } catch (Exception e) {
            executor = Executors.newCachedThreadPool();
            System.out.println("Using Cached Thread Pool Executor (Java 17 compatibility).");
        }
        server.setExecutor(executor);

        server.start();
        System.out.println("Synapse Java High-Performance Server running on port " + PORT);
    }

    private static void setCorsHeaders(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type, Authorization, Accept, Origin, X-Requested-With");
    }

    private static Map<String, String> parseQueryParams(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null || query.isEmpty()) return result;
        for (String param : query.split("&")) {
            String[] entry = param.split("=");
            if (entry.length > 1) {
                result.put(URLDecoder.decode(entry[0], StandardCharsets.UTF_8),
                           URLDecoder.decode(entry[1], StandardCharsets.UTF_8));
            } else {
                result.put(URLDecoder.decode(entry[0], StandardCharsets.UTF_8), "");
            }
        }
        return result;
    }

    private static List<Map<String, Object>> loadAllCandidatesFromDb() throws Exception {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM candidates");
             ResultSet rs = ps.executeQuery()) {
            
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    private static Map<String, Object> mapRow(ResultSet rs) throws Exception {
        Map<String, Object> map = new HashMap<>();
        map.put("candidate_id", rs.getString("candidate_id"));
        map.put("rank", rs.getInt("rank"));
        map.put("score", rs.getDouble("score"));
        map.put("reasoning", rs.getString("reasoning"));

        Map<String, Object> profile = new HashMap<>();
        profile.put("anonymized_name", rs.getString("anonymized_name"));
        profile.put("headline", rs.getString("headline"));
        profile.put("summary", rs.getString("summary"));
        profile.put("years_of_experience", rs.getDouble("years_of_experience"));
        profile.put("location", rs.getString("location"));
        profile.put("country", rs.getString("country"));
        profile.put("current_title", rs.getString("current_title"));
        profile.put("current_company", rs.getString("current_company"));
        profile.put("current_industry", rs.getString("current_industry"));
        map.put("profile", profile);

        map.put("skills", JSON.std.anyFrom(rs.getString("skills_json")));
        map.put("career_history", JSON.std.anyFrom(rs.getString("career_history_json")));
        map.put("education", JSON.std.anyFrom(rs.getString("education_json")));
        map.put("redrob_signals", JSON.std.anyFrom(rs.getString("redrob_signals_json")));

        return map;
    }

    private static void saveCandidatesToDb(List<Map<String, Object>> candidates, Connection conn) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO candidates (" +
                        "candidate_id, rank, score, reasoning, anonymized_name, headline, summary, " +
                        "years_of_experience, location, country, current_title, current_company, current_industry, " +
                        "notice_period_days, is_product_co, skills_json, career_history_json, education_json, redrob_signals_json" +
                        ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            
            for (Map<String, Object> c : candidates) {
                String cid = (String) c.get("candidate_id");
                Map<String, Object> profile = (Map<String, Object>) c.get("profile");
                Map<String, Object> signals = (Map<String, Object>) c.get("redrob_signals");
                List<Object> career = (List<Object>) c.get("career_history");

                double years = profile != null && profile.get("years_of_experience") != null ? ((Number) profile.get("years_of_experience")).doubleValue() : 0.0;
                int notice = signals != null && signals.get("notice_period_days") != null ? ((Number) signals.get("notice_period_days")).intValue() : 90;

                double servicesMonths = 0;
                double totalMonths = 0;
                if (career != null) {
                    for (Object jobObj : career) {
                        if (jobObj instanceof Map) {
                            Map<String, Object> job = (Map<String, Object>) jobObj;
                            String comp = ((String) job.getOrDefault("company", "")).toLowerCase();
                            double dur = 0.0;
                            Object durObj = job.get("duration_months");
                            if (durObj instanceof Number) dur = ((Number) durObj).doubleValue();
                            totalMonths += dur;
                            for (String s : Ranker.SERVICES_COMPANIES) {
                                if (comp.contains(s)) {
                                    servicesMonths += dur;
                                    break;
                                }
                            }
                        }
                    }
                }
                int isProduct = (totalMonths > 0 && servicesMonths < totalMonths) ? 1 : 0;

                ps.setString(1, cid);
                ps.setInt(2, c.get("rank") != null ? ((Number) c.get("rank")).intValue() : 0);
                ps.setDouble(3, c.get("score") != null ? ((Number) c.get("score")).doubleValue() : 0.0);
                ps.setString(4, (String) c.getOrDefault("reasoning", ""));
                ps.setString(5, profile != null ? (String) profile.get("anonymized_name") : "");
                ps.setString(6, profile != null ? (String) profile.get("headline") : "");
                ps.setString(7, profile != null ? (String) profile.get("summary") : "");
                ps.setDouble(8, years);
                ps.setString(9, profile != null ? (String) profile.get("location") : "");
                ps.setString(10, profile != null ? (String) profile.get("country") : "");
                ps.setString(11, profile != null ? (String) profile.get("current_title") : "");
                ps.setString(12, profile != null ? (String) profile.get("current_company") : "");
                ps.setString(13, profile != null ? (String) profile.get("current_industry") : "");
                ps.setInt(14, notice);
                ps.setInt(15, isProduct);
                ps.setString(16, JSON.std.asString(c.getOrDefault("skills", new ArrayList<>())));
                ps.setString(17, JSON.std.asString(c.getOrDefault("career_history", new ArrayList<>())));
                ps.setString(18, JSON.std.asString(c.getOrDefault("education", new ArrayList<>())));
                ps.setString(19, JSON.std.asString(c.getOrDefault("redrob_signals", new HashMap<>())));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                setCorsHeaders(exchange);
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) {
                path = "/index.html";
            }

            File file = new File("." + path);
            if (!file.exists() || file.isDirectory()) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            setCorsHeaders(exchange);
            String contentType = "text/plain";
            if (path.endsWith(".html")) contentType = "text/html";
            else if (path.endsWith(".js")) contentType = "application/javascript";
            else if (path.endsWith(".css")) contentType = "text/css";
            else if (path.endsWith(".gif")) contentType = "image/gif";

            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, file.length());
            try (OutputStream os = exchange.getResponseBody();
                 FileInputStream fis = new FileInputStream(file)) {
                fis.transferTo(os);
            }
        }
    }

    private static class CandidatesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            try {
                Map<String, String> params = parseQueryParams(exchange.getRequestURI().getQuery());
                int page = Integer.parseInt(params.getOrDefault("page", "1"));
                int limit = Integer.parseInt(params.getOrDefault("limit", "20"));
                String search = params.getOrDefault("search", "");
                String activeFilter = params.getOrDefault("filter", "all");
                double minScore = Double.parseDouble(params.getOrDefault("min_score", "0"));

                try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH)) {
                    StringBuilder querySb = new StringBuilder("SELECT * FROM candidates WHERE 1=1");
                    List<Object> sqlParams = new ArrayList<>();

                    if (!search.isEmpty()) {
                        String matchStr = "%" + search.toLowerCase() + "%";
                        querySb.append(" AND (lower(anonymized_name) LIKE ? OR lower(headline) LIKE ? OR lower(current_title) LIKE ? OR lower(skills_json) LIKE ?)");
                        sqlParams.add(matchStr);
                        sqlParams.add(matchStr);
                        sqlParams.add(matchStr);
                        sqlParams.add(matchStr);
                    }

                    if ("exp".equalsIgnoreCase(activeFilter)) {
                        querySb.append(" AND years_of_experience >= 5.0 AND years_of_experience <= 9.0");
                    } else if ("product".equalsIgnoreCase(activeFilter)) {
                        querySb.append(" AND is_product_co = 1");
                    } else if ("notice".equalsIgnoreCase(activeFilter)) {
                        querySb.append(" AND notice_period_days <= 30");
                    } else if ("local".equalsIgnoreCase(activeFilter)) {
                        querySb.append(" AND (lower(location) LIKE ? OR lower(location) LIKE ? OR lower(location) LIKE ? OR lower(location) LIKE ? OR lower(location) LIKE ?)");
                        sqlParams.add("%pune%");
                        sqlParams.add("%noida%");
                        sqlParams.add("%delhi%");
                        sqlParams.add("%ncr%");
                        sqlParams.add("%gurgaon%");
                    }

                    if (minScore > 0) {
                        querySb.append(" AND score * 100 >= ?");
                        sqlParams.add(minScore);
                    }

                    // Count Query
                    String countQuery = querySb.toString().replace("SELECT *", "SELECT COUNT(*)");
                    int totalCount = 0;
                    try (PreparedStatement psCount = conn.prepareStatement(countQuery)) {
                        for (int i = 0; i < sqlParams.size(); i++) {
                            psCount.setObject(i + 1, sqlParams.get(i));
                        }
                        try (ResultSet rsCount = psCount.executeQuery()) {
                            if (rsCount.next()) totalCount = rsCount.getInt(1);
                        }
                    }

                    // Pagination and sorting
                    querySb.append(" ORDER BY score DESC, rank ASC");
                    if (limit != -1) {
                        querySb.append(" LIMIT ? OFFSET ?");
                        sqlParams.add(limit);
                        sqlParams.add((page - 1) * limit);
                    }

                    List<Map<String, Object>> resultCandidates = new ArrayList<>();
                    try (PreparedStatement psData = conn.prepareStatement(querySb.toString())) {
                        for (int i = 0; i < sqlParams.size(); i++) {
                            psData.setObject(i + 1, sqlParams.get(i));
                        }
                        try (ResultSet rs = psData.executeQuery()) {
                            while (rs.next()) {
                                Map<String, Object> cand = mapRow(rs);
                                String reason = (String) cand.get("reasoning");
                                if (reason == null || reason.isEmpty() || reason.contains("skipped for performance")) {
                                    cand.put("reasoning", Ranker.generateReasoning(cand, (Double) cand.get("score"), new HashMap<>()));
                                }
                                resultCandidates.add(cand);
                            }
                        }
                    }

                    int totalPages = limit == -1 ? 1 : Math.max(1, (totalCount + limit - 1) / limit);
                    Map<String, Object> respMap = new HashMap<>();
                    respMap.put("candidates", resultCandidates);
                    respMap.put("total_count", totalCount);
                    respMap.put("total_pages", totalPages);
                    respMap.put("page", limit == -1 ? 1 : page);
                    respMap.put("limit", limit == -1 ? totalCount : limit);

                    String jsonResp = JSON.std.asString(respMap);
                    byte[] rawResp = jsonResp.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, rawResp.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(rawResp);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendError(exchange, 500, e.getMessage());
            }
        }
    }

    private static class JdHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            String method = exchange.getRequestMethod();

            if ("OPTIONS".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            try {
                if ("GET".equalsIgnoreCase(method)) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("jd", currentJdText);
                    byte[] resp = JSON.std.asString(map).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, resp.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(resp);
                    }
                } else if ("POST".equalsIgnoreCase(method)) {
                    byte[] reqBytes = exchange.getRequestBody().readAllBytes();
                    Map<String, Object> req = (Map<String, Object>) JSON.std.anyFrom(reqBytes);
                    String jd = (String) req.get("jd");
                    if (jd == null) jd = "";

                    currentJdText = jd;

                    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
                         PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO system_config (key, value) VALUES ('jd', ?)")) {
                        ps.setString(1, currentJdText);
                        ps.executeUpdate();
                    }

                    // Recalculate ranks in background
                    new Thread(() -> {
                        try {
                            System.out.println("Recalculating scores for new Job Description...");
                            List<Map<String, Object>> allCands = loadAllCandidatesFromDb();
                            List<Map<String, Object>> ranked = Ranker.rankCandidates(allCands, -1, currentJdText, semanticScorer, bm25Scorer);
                            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH)) {
                                conn.setAutoCommit(false);
                                saveCandidatesToDb(ranked, conn);
                                conn.commit();
                            }
                            System.out.println("Successfully recalculated ranks for " + allCands.size() + " candidates.");
                        } catch (Exception e) {
                            System.err.println("Recalculation error: " + e.getMessage());
                        }
                    }).start();

                    Map<String, Object> map = new HashMap<>();
                    map.put("status", "success");
                    byte[] resp = JSON.std.asString(map).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, resp.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(resp);
                    }
                }
            } catch (Exception e) {
                sendError(exchange, 500, e.getMessage());
            }
        }
    }

    private static class UploadChunkHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            try {
                Map<String, String> params = parseQueryParams(exchange.getRequestURI().getQuery());
                String uploadId = params.get("upload_id");
                int chunkIndex = Integer.parseInt(params.get("chunk_index"));
                int totalChunks = Integer.parseInt(params.get("total_chunks"));
                String filename = params.get("filename");
                boolean replaceMode = "true".equalsIgnoreCase(params.get("replace"));

                if (uploadId == null || filename == null) {
                    sendError(exchange, 400, "Missing parameters");
                    return;
                }

                String tempDir = "tmp_uploads/" + uploadId;
                Files.createDirectories(Paths.get(tempDir));

                File chunkFile = new File(tempDir + "/chunk_" + chunkIndex);
                try (InputStream is = exchange.getRequestBody();
                     FileOutputStream fos = new FileOutputStream(chunkFile)) {
                    is.transferTo(fos);
                }

                // If last chunk, start background processing
                if (chunkIndex == totalChunks - 1) {
                    PROCESSING_TASKS.put(uploadId, new ConcurrentHashMap<>(Map.of("status", "processing", "progress", 10)));
                    
                    new Thread(() -> processChunkedUpload(uploadId, totalChunks, filename, replaceMode)).start();

                    Map<String, Object> respMap = new HashMap<>();
                    respMap.put("task_id", uploadId);
                    respMap.put("status", "processing");

                    byte[] resp = JSON.std.asString(respMap).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(202, resp.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(resp);
                    }
                } else {
                    Map<String, Object> respMap = new HashMap<>();
                    respMap.put("status", "chunk_received");
                    byte[] resp = JSON.std.asString(respMap).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, resp.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(resp);
                    }
                }
            } catch (Exception e) {
                sendError(exchange, 500, e.getMessage());
            }
        }
    }

    private static void processChunkedUpload(String uploadId, int totalChunks, String filename, boolean replaceMode) {
        String tempDir = "tmp_uploads/" + uploadId;
        String mergedPath = tempDir + "/merged_compressed";
        Connection conn = null;

        try {
            // Merge chunks
            try (FileOutputStream fos = new FileOutputStream(mergedPath)) {
                for (int i = 0; i < totalChunks; i++) {
                    File chunk = new File(tempDir + "/chunk_" + i);
                    if (chunk.exists()) {
                        Files.copy(chunk.toPath(), fos);
                    }
                }
            }

            updateTaskProgress(uploadId, 40);

            // Decompress on-the-fly
            InputStream fis = new FileInputStream(mergedPath);
            String fnLower = filename.toLowerCase();
            if (fnLower.endsWith(".gz") || fnLower.endsWith(".gzip")) {
                fis = new GZIPInputStream(fis);
            } else if (fnLower.endsWith(".zip")) {
                ZipInputStream zis = new ZipInputStream(fis);
                ZipEntry entry = zis.getNextEntry();
                if (entry != null) {
                    fis = zis;
                }
            }

            conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
            conn.setAutoCommit(false);

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA synchronous = OFF");
                stmt.execute("PRAGMA journal_mode = MEMORY");
                if (replaceMode) {
                    stmt.execute("DELETE FROM candidates");
                }
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8));
            String line;
            List<Map<String, Object>> batch = new ArrayList<>();
            int processedCount = 0;

            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                if (line.startsWith("\uFEFF")) line = line.substring(1); // BOM removal

                try {
                    Map<String, Object> cand = (Map<String, Object>) JSON.std.anyFrom(line);
                    String cid = null;
                    for (String key : Arrays.asList("candidate_id", "id", "cid")) {
                        if (cand.containsKey(key)) {
                            cid = String.valueOf(cand.get(key));
                            break;
                        }
                    }
                    if (cid == null) cid = "cand_" + processedCount + "_" + System.currentTimeMillis();
                    cand.put("candidate_id", cid);

                    // Ensure basic mappings are present
                    if (!cand.containsKey("profile")) cand.put("profile", new HashMap<String, Object>());
                    if (!cand.containsKey("redrob_signals")) cand.put("redrob_signals", new HashMap<String, Object>());
                    if (!cand.containsKey("skills")) cand.put("skills", new ArrayList<Object>());
                    if (!cand.containsKey("career_history")) cand.put("career_history", new ArrayList<Object>());
                    if (!cand.containsKey("education")) cand.put("education", new ArrayList<Object>());

                    batch.add(cand);

                    if (batch.size() >= 200) {
                        List<Map<String, Object>> scoredBatch = Ranker.rankCandidates(batch, -1, currentJdText, null, null);
                        saveCandidatesToDb(scoredBatch, conn);
                        batch.clear();
                        processedCount += 200;
                        updateTaskProgress(uploadId, Math.min(90, 40 + (int) ((processedCount / 100000.0) * 50)));
                    }
                } catch (Exception parseError) {
                    // Ignore parse errors on individual lines
                }
            }

            if (!batch.isEmpty()) {
                List<Map<String, Object>> scoredBatch = Ranker.rankCandidates(batch, -1, currentJdText, null, null);
                saveCandidatesToDb(scoredBatch, conn);
            }

            br.close();
            conn.commit();

            updateTaskProgress(uploadId, 92);

            // Build in-memory BM25 index on the newly loaded candidate profiles
            System.out.println("Updating BM25 indexing in background...");
            List<Map<String, Object>> allCands = loadAllCandidatesFromDb();
            bm25Scorer.buildIndex(allCands);

            // Re-rank globally with Semantic ONNX and update
            System.out.println("Re-ranking global database with full ONNX Semantic Pipeline...");
            List<Map<String, Object>> finalRanked = Ranker.rankCandidates(allCands, -1, currentJdText, semanticScorer, bm25Scorer);
            
            conn.setAutoCommit(false);
            saveCandidatesToDb(finalRanked, conn);
            conn.commit();

            PROCESSING_TASKS.put(uploadId, Map.of("status", "completed", "progress", 100));
            System.out.println("Processing completed successfully.");

        } catch (Exception e) {
            e.printStackTrace();
            PROCESSING_TASKS.put(uploadId, Map.of("status", "failed", "error", e.getMessage() != null ? e.getMessage() : "Unknown processing error"));
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (Exception e) {}
            }
            try {
                // Delete temp directory
                File dir = new File(tempDir);
                if (dir.exists()) {
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File f : files) f.delete();
                    }
                    dir.delete();
                }
            } catch (Exception e) {}
        }
    }

    private static void updateTaskProgress(String taskId, int progress) {
        Map<String, Object> task = PROCESSING_TASKS.get(taskId);
        if (task != null) {
            Map<String, Object> copy = new HashMap<>(task);
            copy.put("progress", progress);
            PROCESSING_TASKS.put(taskId, copy);
        }
    }

    private static class ImportStatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            try {
                Map<String, String> params = parseQueryParams(exchange.getRequestURI().getQuery());
                String taskId = params.get("task_id");
                Map<String, Object> status = PROCESSING_TASKS.getOrDefault(taskId, Map.of("status", "unknown"));

                byte[] resp = JSON.std.asString(status).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            } catch (Exception e) {
                sendError(exchange, 500, e.getMessage());
            }
        }
    }

    private static class ResetHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            try {
                try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
                     Statement stmt = conn.createStatement()) {
                    stmt.execute("DELETE FROM candidates");
                }
                
                Map<String, Object> map = new HashMap<>();
                map.put("status", "success");
                
                byte[] resp = JSON.std.asString(map).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            } catch (Exception e) {
                sendError(exchange, 500, e.getMessage());
            }
        }
    }

    private static class ExportHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            try {
                Map<String, String> params = parseQueryParams(exchange.getRequestURI().getQuery());
                double minScore = Double.parseDouble(params.getOrDefault("min_score", "0"));
                int limit = Integer.parseInt(params.getOrDefault("limit", "-1"));

                List<Map<String, Object>> resultCandidates = new ArrayList<>();
                try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH)) {
                    StringBuilder querySb = new StringBuilder("SELECT * FROM candidates WHERE 1=1");
                    List<Object> sqlParams = new ArrayList<>();

                    if (minScore > 0) {
                        querySb.append(" AND score * 100 >= ?");
                        sqlParams.add(minScore);
                    }

                    querySb.append(" ORDER BY score DESC, rank ASC");
                    if (limit != -1) {
                        querySb.append(" LIMIT ?");
                        sqlParams.add(limit);
                    }

                    try (PreparedStatement ps = conn.prepareStatement(querySb.toString())) {
                        for (int i = 0; i < sqlParams.size(); i++) {
                            ps.setObject(i + 1, sqlParams.get(i));
                        }
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                resultCandidates.add(mapRow(rs));
                            }
                        }
                    }
                }

                byte[] xlsxBytes = Exporter.exportToXlsx(resultCandidates);
                exchange.getResponseHeaders().set("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"shortlisted_candidates.xlsx\"");
                exchange.sendResponseHeaders(200, xlsxBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(xlsxBytes);
                }
            } catch (Exception e) {
                sendError(exchange, 500, e.getMessage());
            }
        }
    }

    private static void sendError(HttpExchange exchange, int code, String msg) throws IOException {
        Map<String, Object> errMap = new HashMap<>();
        errMap.put("error", msg != null ? msg : "Internal server error");
        byte[] resp = JSON.std.asString(errMap).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, resp.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
        }
    }
}
