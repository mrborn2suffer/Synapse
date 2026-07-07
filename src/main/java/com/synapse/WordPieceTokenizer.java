package com.synapse;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WordPieceTokenizer {
    private final Map<String, Integer> vocab = new HashMap<>();
    private final int clsId;
    private final int sepId;
    private final int padId;
    private final int unkId;

    public WordPieceTokenizer(String vocabPath) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(vocabPath))) {
            String line;
            int idx = 0;
            while ((line = reader.readLine()) != null) {
                vocab.put(line.trim(), idx++);
            }
        }
        this.clsId = vocab.getOrDefault("[CLS]", 101);
        this.sepId = vocab.getOrDefault("[SEP]", 102);
        this.padId = vocab.getOrDefault("[PAD]", 0);
        this.unkId = vocab.getOrDefault("[UNK]", 100);
    }

    public TokenizedResult tokenize(String text, int maxLen) {
        List<Integer> inputIds = new ArrayList<>();
        inputIds.add(clsId);

        String cleaned = text.toLowerCase()
                .replaceAll("[^a-z0-9\\s##-]", " ")
                .replaceAll("\\s+", " ");
        String[] words = cleaned.split(" ");

        for (String word : words) {
            if (word.isEmpty()) continue;
            List<Integer> wordTokens = tokenizeWord(word);
            if (inputIds.size() + wordTokens.size() + 1 > maxLen) {
                break;
            }
            inputIds.addAll(wordTokens);
        }

        inputIds.add(sepId);
        int actualLen = inputIds.size();

        long[] ids = new long[maxLen];
        long[] mask = new long[maxLen];
        long[] types = new long[maxLen];

        for (int i = 0; i < maxLen; i++) {
            if (i < actualLen) {
                ids[i] = inputIds.get(i);
                mask[i] = 1;
            } else {
                ids[i] = padId;
                mask[i] = 0;
            }
            types[i] = 0;
        }

        return new TokenizedResult(ids, mask, types);
    }

    private List<Integer> tokenizeWord(String word) {
        List<Integer> tokens = new ArrayList<>();
        int start = 0;
        while (start < word.length()) {
            int end = word.length();
            String curSub = null;
            int curId = -1;
            while (start < end) {
                String substr = word.substring(start, end);
                if (start > 0) {
                    substr = "##" + substr;
                }
                if (vocab.containsKey(substr)) {
                    curSub = substr;
                    curId = vocab.get(substr);
                    break;
                }
                end--;
            }
            if (curId == -1) {
                tokens.add(unkId);
                break;
            }
            tokens.add(curId);
            start = end;
        }
        return tokens;
    }

    public static class TokenizedResult {
        public final long[] inputIds;
        public final long[] attentionMask;
        public final long[] tokenTypeIds;

        public TokenizedResult(long[] inputIds, long[] attentionMask, long[] tokenTypeIds) {
            this.inputIds = inputIds;
            this.attentionMask = attentionMask;
            this.tokenTypeIds = tokenTypeIds;
        }
    }
}
