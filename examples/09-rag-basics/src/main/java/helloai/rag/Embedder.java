package helloai.rag;

/**
 * 向量化。整个 RAG 里唯一需要外部模型的地方，所以单独抽出来 ——
 * 换成真模型（OpenAI / 通义 / bge）只需要换这一个实现。
 */
public interface Embedder {

    float[] embed(String text);

    int dim();
}