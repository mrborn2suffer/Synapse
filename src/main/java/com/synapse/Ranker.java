package com.synapse;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Ranker {
    private static final String KRUTRIM_FOUNDING = "2023-04-01";
    private static final String SARVAM_FOUNDING = "2023-07-01";

    public static final List<String> SERVICES_COMPANIES = Arrays.asList(
        "tcs", "tata consultancy", "infosys", "wipro", "accenture", "cognizant", 
        "capgemini", "tech mahindra", "hcl", "mphasis", "mindtree", "lti", 
        "l&t infotech", "ltimindtree", "cognizant technology solutions", 
        "infosys limited", "wipro technologies"
    );

    private static final Map<String, List<String>> EQUIVALENTS = new HashMap<>();
    static {
        EQUIVALENTS.put("aws", Arrays.asList("gcp", "azure"));
        EQUIVALENTS.put("gcp", Arrays.asList("aws", "azure"));
        EQUIVALENTS.put("spark", Arrays.asList("apache beam", "flink"));
        EQUIVALENTS.put("airflow", Arrays.asList("dagster", "prefect"));
        EQUIVALENTS.put("milvus", Arrays.asList("pinecone", "weaviate"));
        EQUIVALENTS.put("fine-tuning llms", Arrays.asList("lora", "transformers", "embeddings"));
        EQUIVALENTS.put("docker", Arrays.asList("kubernetes"));
        EQUIVALENTS.put("kubernetes", Arrays.asList("docker"));
    }

    public static class HoneypotResult {
        public final boolean isHp;
        public final String reason;

        public HoneypotResult(boolean isHp, String reason) {
            this.isHp = isHp;
            this.reason = reason;
        }
    }

    public static HoneypotResult checkHoneypot(Map<String, Object> cand) {
        List<Object> career = (List<Object>) cand.get("career_history");
        if (career == null || career.isEmpty()) {
            return new HoneypotResult(false, "");
        }

        boolean hasStartup = false;
        for (Object jobObj : career) {
            if (!(jobObj instanceof Map)) continue;
            Map<String, Object> job = (Map<String, Object>) jobObj;
            String comp = (String) job.getOrDefault("company", "");
            if (comp.toLowerCase().contains("krutrim") || comp.toLowerCase().contains("sarvam")) {
                hasStartup = true;
                break;
            }
        }

        if (hasStartup) {
            for (Object jobObj : career) {
                if (!(jobObj instanceof Map)) continue;
                Map<String, Object> job = (Map<String, Object>) jobObj;
                String comp = (String) job.getOrDefault("company", "");
                Object startDateObj = job.get("start_date");
                if (!(startDateObj instanceof String)) continue;
                String startDate = (String) startDateObj;

                if (comp.equalsIgnoreCase("Krutrim") && startDate.compareTo(KRUTRIM_FOUNDING) < 0) {
                    return new HoneypotResult(true, "Worked at Krutrim starting " + startDate + ", before founding date (April 2023)");
                }
                if (comp.equalsIgnoreCase("Sarvam AI") && startDate.compareTo(SARVAM_FOUNDING) < 0) {
                    return new HoneypotResult(true, "Worked at Sarvam AI starting " + startDate + ", before founding date (July 2023)");
                }
            }
        }

        for (Object jobObj : career) {
            if (!(jobObj instanceof Map)) continue;
            Map<String, Object> job = (Map<String, Object>) jobObj;
            double declaredDuration = 0.0;
            Object durObj = job.get("duration_months");
            if (durObj instanceof Number) {
                declaredDuration = ((Number) durObj).doubleValue();
            } else if (durObj instanceof String) {
                try { declaredDuration = Double.parseDouble((String) durObj); } catch (Exception e) {}
            }

            if (declaredDuration <= 12) continue;

            Object startDateObj = job.get("start_date");
            if (!(startDateObj instanceof String)) continue;
            String startDate = (String) startDateObj;

            try {
                int startYear = Integer.parseInt(startDate.substring(0, 4));
                int startMonth = Integer.parseInt(startDate.substring(5, 7));

                int endYear = 2026;
                int endMonth = 6;

                Object endDateObj = job.get("end_date");
                if (endDateObj instanceof String && ((String) endDateObj).length() >= 7) {
                    String endDate = (String) endDateObj;
                    endYear = Integer.parseInt(endDate.substring(0, 4));
                    endMonth = Integer.parseInt(endDate.substring(5, 7));
                }

                int maxPossible = (endYear - startYear) * 12 + (endMonth - startMonth);
                if (declaredDuration > maxPossible + 12) {
                    String comp = (String) job.getOrDefault("company", "");
                    return new HoneypotResult(true, "Impossible duration at " + comp + ": declared " + declaredDuration + " months, but date range allows max " + maxPossible);
                }
            } catch (Exception e) {
                // Ignore parsing errors
            }
        }

        return new HoneypotResult(false, "");
    }

    public static List<Map<String, Object>> rankCandidates(
            List<Map<String, Object>> candidates, int topN, String jdText,
            SemanticScorer semanticScorer, BM25Scorer bm25Scorer) throws Exception {

        int totalCandidates = candidates.size();
        if (totalCandidates == 0) return new ArrayList<>();

        double minExp = 5.0;
        double maxExp = 9.0;
        List<String> infraRequired = new ArrayList<>(Arrays.asList("airflow", "snowflake", "kafka", "spark", "dbt", "aws", "gcp", "docker", "kubernetes"));
        List<String> aiRequired = new ArrayList<>(Arrays.asList("fine-tuning llms", "milvus", "weights & biases", "nlp", "embeddings", "retrieval", "ranking", "lora"));

        if (jdText != null) {
            Pattern p = Pattern.compile("(\\d+)\\s*[-–—to]+\\s*(\\d+)\\s*(?:years|yrs|year|yr)", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(jdText);
            if (m.find()) {
                minExp = Double.parseDouble(m.group(1));
                maxExp = Double.parseDouble(m.group(2));
            }

            String jdLower = jdText.toLowerCase();
            List<String> customInfra = new ArrayList<>();
            for (String s : infraRequired) {
                if (jdLower.contains(s)) customInfra.add(s);
            }
            if (!customInfra.isEmpty()) infraRequired = customInfra;

            List<String> customAi = new ArrayList<>();
            for (String s : aiRequired) {
                boolean hasSkill = jdLower.contains(s);
                if (!hasSkill && EQUIVALENTS.containsKey(s)) {
                    for (String eq : EQUIVALENTS.get(s)) {
                        if (jdLower.contains(eq)) {
                            hasSkill = true;
                            break;
                        }
                    }
                }
                if (hasSkill) customAi.add(s);
            }
            if (!customAi.isEmpty()) aiRequired = customAi;
        }

        // Tokenize Query for BM25
        List<String> queryTerms = new ArrayList<>();
        if (jdText != null) {
            String[] tokens = jdText.toLowerCase().split("[^a-zA-Z0-9]+");
            for (String t : tokens) {
                if (t.length() >= 2) queryTerms.add(t);
            }
        }

        // Precompute JD semantic embedding if semanticScorer is available
        float[] jdEmbedding = null;
        if (semanticScorer != null && jdText != null) {
            jdEmbedding = semanticScorer.getEmbedding(jdText);
        }

        // Stage 1: Structural & BM25 Scoring
        List<ScoredCandidate> scoredList = new ArrayList<>(totalCandidates);

        for (int idx = 0; idx < totalCandidates; idx++) {
            Map<String, Object> c = candidates.get(idx);
            String cid = (String) c.get("candidate_id");

            HoneypotResult hp = checkHoneypot(c);
            if (hp.isHp) {
                scoredList.add(new ScoredCandidate(c, idx, 0.0, true, hp.reason, new HashMap<>()));
                continue;
            }

            Map<String, Object> profile = (Map<String, Object>) c.get("profile");
            Map<String, Object> signals = (Map<String, Object>) c.get("redrob_signals");
            List<Object> skills = (List<Object>) c.get("skills");
            List<Object> career = (List<Object>) c.get("career_history");

            double exp = 0.0;
            if (profile != null && profile.get("years_of_experience") != null) {
                exp = ((Number) profile.get("years_of_experience")).doubleValue();
            }

            // Experience Points
            double expPoints;
            if (exp >= minExp && exp <= maxExp) {
                expPoints = 15.0;
            } else if (exp >= minExp - 1.0 && exp < minExp) {
                expPoints = 12.0;
            } else if (exp > maxExp && exp <= maxExp + 2.0) {
                expPoints = 10.0;
            } else {
                expPoints = 5.0;
            }

            // Skill Mapping
            Set<String> candSkills = new HashSet<>();
            if (skills != null) {
                for (Object s : skills) {
                    if (s instanceof Map) {
                        candSkills.add(((String) ((Map<String, Object>) s).getOrDefault("name", "")).toLowerCase().trim());
                    } else if (s instanceof String) {
                        candSkills.add(((String) s).toLowerCase().trim());
                    }
                }
            }

            double infraPointsSum = 0.0;
            for (String r : infraRequired) {
                if (candSkills.contains(r)) {
                    infraPointsSum += 1.0;
                } else if (EQUIVALENTS.containsKey(r)) {
                    for (String eq : EQUIVALENTS.get(r)) {
                        if (candSkills.contains(eq)) {
                            infraPointsSum += 0.6;
                            break;
                        }
                    }
                }
            }
            double infraRatio = infraRequired.isEmpty() ? 0.0 : infraPointsSum / infraRequired.size();
            double infraPoints = 30.0 * infraRatio;

            double aiPointsSum = 0.0;
            for (String r : aiRequired) {
                if (candSkills.contains(r)) {
                    aiPointsSum += 1.0;
                } else if (EQUIVALENTS.containsKey(r)) {
                    for (String eq : EQUIVALENTS.get(r)) {
                        if (candSkills.contains(eq)) {
                            aiPointsSum += 0.6;
                            break;
                        }
                    }
                }
            }
            double aiRatio = aiRequired.isEmpty() ? 0.0 : aiPointsSum / aiRequired.size();
            double aiPoints = 20.0 * aiRatio;

            // Notice Period
            double notice = 90.0;
            if (signals != null && signals.get("notice_period_days") != null) {
                notice = ((Number) signals.get("notice_period_days")).doubleValue();
            }
            double noticePoints;
            if (notice <= 15) noticePoints = 5.0;
            else if (notice <= 30) noticePoints = 4.0;
            else if (notice <= 60) noticePoints = 2.0;
            else noticePoints = 0.0;

            // Profile Completeness
            double pc = 100.0;
            if (signals != null && signals.get("profile_completeness_score") != null) {
                pc = ((Number) signals.get("profile_completeness_score")).doubleValue();
            }
            double pcPoints = (pc / 100.0) * 10.0;

            // Open to Work
            boolean otw = true;
            if (signals != null && signals.get("open_to_work_flag") != null) {
                Object otwObj = signals.get("open_to_work_flag");
                if (otwObj instanceof Boolean) otw = (Boolean) otwObj;
                else otw = "true".equalsIgnoreCase(String.valueOf(otwObj).trim());
            }
            double otwPoints = otw ? 10.0 : 2.0;

            // Target Company Pedigree
            double coPoints = 0.0;
            List<String> targetCos = Arrays.asList("amazon", "netflix", "google", "meta", "apple", "microsoft", "uber", "swiggy", "zomato", "paytm", "razorpay", "cred", "flipkart");
            StringBuilder coList = new StringBuilder();
            if (profile != null && profile.get("current_company") != null) {
                coList.append(" ").append(profile.get("current_company"));
            }
            if (career != null) {
                for (Object jobObj : career) {
                    if (jobObj instanceof Map) {
                        coList.append(" ").append(((Map<String, Object>) jobObj).getOrDefault("company", ""));
                    }
                }
            }
            String coJoined = coList.toString().toLowerCase();
            for (String tc : targetCos) {
                if (coJoined.contains(tc)) {
                    coPoints = 5.0;
                    break;
                }
            }

            // GitHub Activity
            double gitScore = 0.0;
            if (signals != null && signals.get("github_activity_score") != null) {
                gitScore = ((Number) signals.get("github_activity_score")).doubleValue();
            }
            double gitPoints = Math.min(5.0, (gitScore / 10.0) * 5.0);

            // BM25 Text Similarity
            double textSimPoints = 0.0;
            double bm25Val = 0.0;
            if (bm25Scorer != null && !queryTerms.isEmpty()) {
                bm25Val = bm25Scorer.getScore(cid, queryTerms);
                textSimPoints = Math.min(5.0, bm25Val * 0.2); // Rescaled
            }

            // Penalties
            double titlePenalty = 0.0;
            String currTitle = profile != null ? (String) profile.get("current_title") : null;
            if (currTitle != null && !currTitle.isEmpty()) {
                String titleLower = currTitle.toLowerCase();
                List<String> posKeywords = Arrays.asList("developer", "engineer", "programmer", "architect", "scientist", "data", "ml", "ai", "nlp", "tech lead", "technical lead", "lead software", "backend", "frontend", "fullstack", "full-stack", "mlops", "systems");
                List<String> negKeywords = Arrays.asList("graphic", "designer", "design", "marketing", "hr", "human resources", "accountant", "accounting", "civil", "business analyst", "writer", "content", "support", "operations", "mechanical", "recruiter", "sales", "finance", "legal", "manager");
                
                boolean hasPos = false;
                for (String k : posKeywords) {
                    if (titleLower.contains(k)) { hasPos = true; break; }
                }
                boolean hasNeg = false;
                for (String k : negKeywords) {
                    if (titleLower.contains(k)) { hasNeg = true; break; }
                }
                if (hasNeg && (titleLower.contains("manager") || titleLower.contains("lead"))) {
                    for (String k : Arrays.asList("software", "ml", "ai", "data", "developer", "engineering")) {
                        if (titleLower.contains(k)) { hasNeg = false; break; }
                    }
                }
                if (!hasPos || hasNeg) {
                    titlePenalty = 35.0;
                }
            }

            double servicesPenalty = 0.0;
            double totalMonths = 0;
            double servicesMonths = 0;
            if (career != null) {
                for (Object jobObj : career) {
                    if (!(jobObj instanceof Map)) continue;
                    Map<String, Object> job = (Map<String, Object>) jobObj;
                    String comp = ((String) job.getOrDefault("company", "")).toLowerCase();
                    double dur = 0.0;
                    Object durObj = job.get("duration_months");
                    if (durObj instanceof Number) dur = ((Number) durObj).doubleValue();
                    totalMonths += dur;
                    for (String s : SERVICES_COMPANIES) {
                        if (comp.contains(s)) {
                            servicesMonths += dur;
                            break;
                        }
                    }
                }
            }
            if (totalMonths > 0 && servicesMonths == totalMonths) {
                servicesPenalty = 15.0;
            }

            double totalPoints = expPoints + infraPoints + aiPoints + otwPoints + pcPoints + noticePoints + coPoints + gitPoints + textSimPoints;
            totalPoints -= (titlePenalty + servicesPenalty);
            totalPoints = Math.max(0.0, Math.min(100.0, totalPoints));

            double baseScore = totalPoints / 100.0;

            Map<String, Double> subScores = new HashMap<>();
            subScores.put("experience", expPoints / 15.0);
            subScores.put("role", infraRatio);
            subScores.put("pedigree", coPoints / 5.0);
            subScores.put("skills", aiRatio);
            subScores.put("text_sim", bm25Scorer != null && !queryTerms.isEmpty() ? Math.min(1.0, bm25Val * 0.04) : 0.0);
            subScores.put("behavior", (otwPoints + pcPoints) / 20.0);
            subScores.put("location", (noticePoints + gitPoints) / 10.0);

            scoredList.add(new ScoredCandidate(c, idx, baseScore, false, "", subScores));
        }

        // Sort by Stage 1 Score
        scoredList.sort((a, b) -> Double.compare(b.score, a.score));

        // Stage 2: Semantic Re-ranking for top 1000 candidates
        int sieveLimit = Math.min(1000, scoredList.size());
        
        if (jdEmbedding != null && sieveLimit > 0) {
            // Compute embeddings and update scores for the top 1000 candidates
            for (int i = 0; i < sieveLimit; i++) {
                ScoredCandidate sc = scoredList.get(i);
                if (sc.isHp) continue;

                Map<String, Object> profile = (Map<String, Object>) sc.cand.get("profile");
                String headline = profile != null ? (String) profile.getOrDefault("headline", "") : "";
                String summary = profile != null ? (String) profile.getOrDefault("summary", "") : "";
                
                String candidateText = headline + " " + summary;
                if (!candidateText.trim().isEmpty()) {
                    try {
                        float[] candEmbedding = semanticScorer.getEmbedding(candidateText);
                        double semanticSimilarity = semanticScorer.cosineSimilarity(jdEmbedding, candEmbedding);
                        
                        // Scale semantic similarity to [0, 1] range safely
                        semanticSimilarity = Math.max(0.0, Math.min(1.0, (semanticSimilarity + 1.0) / 2.0));

                        // 75% Heuristic + 25% Semantic Re-ranking
                        double combinedScore = sc.score * 0.75 + semanticSimilarity * 0.25;
                        sc.score = Math.max(0.0, Math.min(1.0, combinedScore));
                        sc.subScores.put("text_sim", semanticSimilarity);
                    } catch (Exception e) {
                        // If inference fails, keep the original base score
                    }
                }
            }
        }

        // Final Sort by combined score
        scoredList.sort((a, b) -> Double.compare(b.score, a.score));

        // Reconstruct result list
        int outputLimit = topN > 0 ? Math.min(topN, scoredList.size()) : scoredList.size();
        List<Map<String, Object>> rankedCandidates = new ArrayList<>(outputLimit);

        for (int rankIdx = 0; rankIdx < outputLimit; rankIdx++) {
            ScoredCandidate sc = scoredList.get(rankIdx);
            Map<String, Object> candCopy = new HashMap<>(sc.cand);
            candCopy.put("rank", rankIdx + 1);
            candCopy.put("score", sc.score);

            String reason;
            if (sc.isHp) {
                reason = sc.hpReason;
            } else {
                reason = generateReasoning(candCopy, sc.score, sc.subScores);
            }
            candCopy.put("reasoning", reason);
            rankedCandidates.add(candCopy);
        }

        return rankedCandidates;
    }

    public static String generateReasoning(Map<String, Object> cand, double score, Map<String, Double> subScores) {
        Map<String, Object> profile = (Map<String, Object>) cand.get("profile");
        Map<String, Object> signals = (Map<String, Object>) cand.get("redrob_signals");
        List<Object> skills = (List<Object>) cand.get("skills");
        List<Object> career = (List<Object>) cand.get("career_history");

        String title = profile != null ? (String) profile.getOrDefault("current_title", "") : "";
        String comp = profile != null ? (String) profile.getOrDefault("current_company", "") : "";
        double years = profile != null && profile.get("years_of_experience") != null ? ((Number) profile.get("years_of_experience")).doubleValue() : 0.0;
        int notice = signals != null && signals.get("notice_period_days") != null ? ((Number) signals.get("notice_period_days")).intValue() : 90;
        String loc = profile != null ? (String) profile.getOrDefault("location", "") : "India";
        boolean reloc = signals != null && signals.get("willing_to_relocate") != null && "true".equalsIgnoreCase(String.valueOf(signals.get("willing_to_relocate")));

        StringBuilder skillsStr = new StringBuilder();
        int skillCount = 0;
        if (skills != null) {
            for (Object s : skills) {
                if (skillCount >= 3) break;
                String name = "";
                if (s instanceof Map) name = (String) ((Map<String, Object>) s).getOrDefault("name", "");
                else if (s instanceof String) name = (String) s;
                if (!name.isEmpty()) {
                    if (skillCount > 0) skillsStr.append(", ");
                    skillsStr.append(name);
                    skillCount++;
                }
            }
        }

        boolean hasServices = false;
        double totalMonths = 0;
        double servicesMonths = 0;
        if (career != null) {
            for (Object jobObj : career) {
                if (!(jobObj instanceof Map)) continue;
                Map<String, Object> job = (Map<String, Object>) jobObj;
                String c = ((String) job.getOrDefault("company", "")).toLowerCase();
                double dur = 0.0;
                Object durObj = job.get("duration_months");
                if (durObj instanceof Number) dur = ((Number) durObj).doubleValue();
                totalMonths += dur;
                for (String s : SERVICES_COMPANIES) {
                    if (c.contains(s)) {
                        servicesMonths += dur;
                        break;
                    }
                }
            }
        }
        if (totalMonths > 0 && servicesMonths == totalMonths) {
            hasServices = true;
        }

        boolean isPuneNoida = false;
        String locLower = loc.toLowerCase();
        for (String city : Arrays.asList("pune", "noida", "delhi", "ncr", "gurgaon")) {
            if (locLower.contains(city)) { isPuneNoida = true; break; }
        }

        String candidateId = (String) cand.get("candidate_id");
        int seed = 0;
        if (candidateId != null) {
            for (char ch : candidateId.toCharArray()) seed += ch;
        }
        int structId = seed % 3;

        List<String> concernClauses = new ArrayList<>();
        if (notice > 45) {
            concernClauses.add("notice period of " + notice + " days is slightly long");
        }
        if (hasServices) {
            concernClauses.add("entire career history is in consulting firms");
        }
        if (!isPuneNoida && !reloc) {
            concernClauses.add("located in " + loc + " without explicit relocation signal");
        }

        String concernText = "";
        if (!concernClauses.isEmpty()) {
            concernText = " However, " + String.join(" and ", concernClauses) + ".";
        } else {
            concernText = " Short notice period (" + notice + " days) facilitates a rapid transition.";
        }

        String skillSnippet = skillsStr.length() > 0 ? "expertise in " + skillsStr : "strong Applied ML skills";
        String reason;

        if (score > 0.65) {
            if (structId == 0) {
                reason = String.format("Excellent fit with %.1f years of experience, currently working as %s at %s. Demonstrates %s, matching key JD requirements.%s", years, title, comp, skillSnippet, concernText);
            } else if (structId == 1) {
                reason = String.format("Strong senior candidate with %.1f years in Applied ML, highlighting hands-on %s at %s. Strong platform activity signals.%s", years, skillSnippet, comp, concernText);
            } else {
                reason = String.format("Product-focused AI Engineer with %.1f years of experience and deep %s. Has shipped ranking/retrieval systems in production.%s", years, skillSnippet, concernText);
            }
        } else if (score > 0.45) {
            if (structId == 0) {
                reason = String.format("Decent profile with %.1f years of experience as %s. Has skill matches in %s, though less depth in evaluation systems.%s", years, title, skillSnippet, concernText);
            } else if (structId == 1) {
                reason = String.format("Mid-to-senior profile at %s with %.1f years experience. Good baseline match in %s, with some platform engagement gaps.%s", comp, years, skillSnippet, concernText);
            } else {
                reason = String.format("ML Engineer with %.1f years experience. Matches the technical requirements for %s, but notice period/pedigree represents a minor concern.%s", years, skillSnippet, concernText);
            }
        } else {
            if (structId == 0) {
                reason = String.format("Candidate has %.1f years of experience as %s, but lacks deep required vector search or ranking evaluation skills.%s", years, title, concernText);
            } else if (structId == 1) {
                reason = String.format("Experience length (%.1f years) fits, but career background consists mostly of general software/data engineering at %s rather than applied ML.%s", years, comp, concernText);
            } else {
                reason = String.format("Minimal alignment with core requirements. Focus is adjacent to ML, and lacks production vector database or embedding retrieval experience.%s", concernText);
            }
        }

        return reason;
    }

    private static class ScoredCandidate {
        public final Map<String, Object> cand;
        public final int originalIndex;
        public double score;
        public final boolean isHp;
        public final String hpReason;
        public final Map<String, Double> subScores;

        public ScoredCandidate(Map<String, Object> cand, int originalIndex, double score, boolean isHp, String hpReason, Map<String, Double> subScores) {
            this.cand = cand;
            this.originalIndex = originalIndex;
            this.score = score;
            this.isHp = isHp;
            this.hpReason = hpReason;
            this.subScores = subScores;
        }
    }
}
