package helloai.rag;

import helloai.rag.RagCorpus.Doc;
import java.util.ArrayList;
import java.util.List;

/**
 * 三种切分策略。切分是 RAG 里唯一「不花钱但最影响效果」的决策。
 */
final class Chunker {

    private Chunker() {}

    record Chunk(String docId, String title, String permission, int seq, String text) {}

    static List<Chunk> chunk(Doc doc, String strategy, int size, int overlap) {
        List<String> pieces = switch (strategy) {
            case "paragraph" -> byParagraph(doc.text(), size);
            case "fixed" -> byFixedWindow(doc.text(), size, overlap);
            case "whole" -> List.of(doc.text().replaceAll("\\s+", " ").trim());
            default -> throw new IllegalArgumentException("strategy 只能是 paragraph / fixed / whole");
        };

        List<Chunk> chunks = new ArrayList<>();
        int seq = 0;
        for (String piece : pieces) {
            String text = piece.strip();
            if (text.length() < 8) {
                continue;                      // 太短的碎片直接丢，它对检索只有噪声
            }
            chunks.add(new Chunk(doc.id(), doc.title(), doc.permission(), seq++, text));
        }
        return chunks;
    }

    /** 按自然段落切；段落仍然过长时按句号再拆。 */
    private static List<String> byParagraph(String text, int maxLen) {
        List<String> parts = new ArrayList<>();
        for (String para : text.split("\\n\\s*\\n")) {
            String p = para.replaceAll("\\s+", " ").strip();
            if (p.isEmpty()) {
                continue;
            }
            if (p.length() <= maxLen) {
                parts.add(p);
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (String sentence : p.split("(?<=。)")) {
                if (sb.length() + sentence.length() > maxLen && sb.length() > 0) {
                    parts.add(sb.toString());
                    sb.setLength(0);
                }
                sb.append(sentence);
            }
            if (sb.length() > 0) {
                parts.add(sb.toString());
            }
        }
        return parts;
    }

    /** 定长窗口 + 重叠。重叠是为了不让一句话正好被切断。 */
    private static List<String> byFixedWindow(String text, int size, int overlap) {
        String flat = text.replaceAll("\\s+", " ").strip();
        List<String> parts = new ArrayList<>();
        int step = Math.max(1, size - overlap);
        for (int start = 0; start < flat.length(); start += step) {
            int end = Math.min(flat.length(), start + size);
            parts.add(flat.substring(start, end));
            if (end == flat.length()) {
                break;
            }
        }
        return parts;
    }
}