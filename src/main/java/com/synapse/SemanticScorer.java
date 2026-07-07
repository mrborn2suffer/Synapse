package com.synapse;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

public class SemanticScorer {
    private final OrtEnvironment env;
    private final OrtSession session;
    private final WordPieceTokenizer tokenizer;

    public SemanticScorer(String modelPath, String vocabPath) throws Exception {
        this.env = OrtEnvironment.getEnvironment();
        this.session = env.createSession(modelPath, new OrtSession.SessionOptions());
        this.tokenizer = new WordPieceTokenizer(vocabPath);
    }

    public synchronized float[] getEmbedding(String text) throws Exception {
        int maxLen = 256;
        WordPieceTokenizer.TokenizedResult tokenized = tokenizer.tokenize(text, maxLen);

        long[] shape = new long[]{1, maxLen};
        
        try (OnnxTensor inputIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenized.inputIds), shape);
             OnnxTensor maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenized.attentionMask), shape);
             OnnxTensor typeTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenized.tokenTypeIds), shape)) {

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", inputIdsTensor);
            inputs.put("attention_mask", maskTensor);
            inputs.put("token_type_ids", typeTensor);

            try (OrtSession.Result results = session.run(inputs)) {
                OnnxValue outputVal = results.get(0);
                float[][][] outputData = (float[][][]) outputVal.getValue();

                int hiddenDim = outputData[0][0].length;
                float[] embedding = new float[hiddenDim];

                // Mean pooling
                int validTokens = 0;
                for (int i = 0; i < maxLen; i++) {
                    if (tokenized.attentionMask[i] == 1) {
                        validTokens++;
                        for (int d = 0; d < hiddenDim; d++) {
                            embedding[d] += outputData[0][i][d];
                        }
                    }
                }

                if (validTokens > 0) {
                    for (int d = 0; d < hiddenDim; d++) {
                        embedding[d] /= validTokens;
                    }
                }

                // L2 normalization
                double sumSq = 0.0;
                for (int d = 0; d < hiddenDim; d++) {
                    sumSq += embedding[d] * embedding[d];
                }
                double norm = Math.sqrt(sumSq);
                if (norm > 0.0) {
                    for (int d = 0; d < hiddenDim; d++) {
                        embedding[d] /= norm;
                    }
                }

                return embedding;
            }
        }
    }

    public double cosineSimilarity(float[] emb1, float[] emb2) {
        double dotProduct = 0.0;
        for (int i = 0; i < emb1.length; i++) {
            dotProduct += emb1[i] * emb2[i];
        }
        return dotProduct;
    }
}
