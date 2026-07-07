package com.synapse;

import java.util.*;

public class BM25Scorer {
    private final Map<String, Integer> docFreqs = new HashMap<>();
    private final Map<String, Map<String, Integer>> termFreqs = new HashMap<>(); // candidateId -> term -> freq
    private final Map<String, Integer> docLengths = new HashMap<>();
    private double avgDocLength = 0;
    private final double k1 = 1.2;
    private final double b = 0.75;
    private int N = 0;

    public synchronized void buildIndex(List<Map<String, Object>> candidates) {
        docFreqs.clear();
        termFreqs.clear();
        docLengths.clear();
        N = candidates.size();
        if (N == 0) return;

        long totalLength = 0;
        for (Map<String, Object> c : candidates) {
            String cid = (String) c.get("candidate_id");
            Map<String, Object> profile = (Map<String, Object>) c.get("profile");
            String headline = profile != null ? (String) profile.getOrDefault("headline", "") : "";
            String summary = profile != null ? (String) profile.getOrDefault("summary", "") : "";
            String title = profile != null ? (String) profile.getOrDefault("current_title", "") : "";
            
            // Build simple skills text
            StringBuilder skillsText = new StringBuilder();
            List<Object> skills = (List<Object>) c.get("skills");
            if (skills != null) {
                for (Object s : skills) {
                    if (s instanceof Map) {
                        skillsText.append(" ").append(((Map<String, Object>) s).getOrDefault("name", ""));
                    } else if (s instanceof String) {
                        skillsText.append(" ").append(s);
                    }
                }
            }

            String text = (headline + " " + summary + " " + skillsText + " " + title).toLowerCase();
            String[] tokens = text.split("[^a-zA-Z0-9]+");
            Map<String, Integer> freqs = new HashMap<>();
            int validTokensCount = 0;
            for (String token : tokens) {
                if (token.length() < 2) continue;
                freqs.put(token, freqs.getOrDefault(token, 0) + 1);
                validTokensCount++;
            }
            termFreqs.put(cid, freqs);
            docLengths.put(cid, validTokensCount);
            totalLength += validTokensCount;

            for (String term : freqs.keySet()) {
                docFreqs.put(term, docFreqs.getOrDefault(term, 0) + 1);
            }
        }
        avgDocLength = (double) totalLength / N;
    }

    public double getScore(String candidateId, List<String> queryTerms) {
        Map<String, Integer> freqs = termFreqs.get(candidateId);
        if (freqs == null) return 0.0;
        int docLen = docLengths.getOrDefault(candidateId, 0);

        double score = 0.0;
        for (String term : queryTerms) {
            if (!docFreqs.containsKey(term)) continue;
            int df = docFreqs.get(term);
            double idf = Math.log(1.0 + (N - df + 0.5) / (df + 0.5));
            int tf = freqs.getOrDefault(term, 0);
            
            double tfPart = (tf * (k1 + 1.0)) / (tf + k1 * (1.0 - b + b * (docLen / avgDocLength)));
            score += idf * tfPart;
        }
        return score;
    }
}
