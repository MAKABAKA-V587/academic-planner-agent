# -*- coding: utf-8 -*-
"""
RAG 评测指标库（零第三方依赖，纯 Python 标准库实现）。

文本指标（基于中文适配的 tokenize：连续 ASCII 字母数字为 1 个 token，汉字单字成 token）：
  - bleu(pred, ref)        BLEU-4（+1 平滑 + 简短惩罚）
  - rouge_1 / rouge_2      ROUGE-N（unigram / bigram，F1）
  - rouge_l(pred, ref)     ROUGE-L（LCS，F1）
  - meteor(pred, ref)      简化 METEOR（LCS 对齐 + recall 偏置调和平均 + 碎片惩罚；
                           标准版的同义词/词干匹配需外部词库，中文场景省略）

检索指标（输入为候选来源列表 + 相关来源集合）：
  - recall_at_k(candidates, relevant, k)   top-K 中含任一相关条目 → 1，否则 0
  - mrr(candidates, relevant)              第一个相关条目排名的倒数；无命中 → 0
  - source_match(source, relevant)         来源串与标注集合的宽松匹配（双向包含）

扩展方式：实现 (pred_text, ref_text) -> float 的函数后加入 TEXT_METRICS 注册表即可
被 rag_eval.py 自动纳入评测与报告。
"""
import math
import re
from collections import Counter

TOKEN_RE = re.compile(r"[A-Za-z0-9]+|[\u4e00-\u9fff]")


def tokenize(text):
    """中文适配分词：ASCII 连续串成词，汉字逐字。"""
    if not text:
        return []
    return TOKEN_RE.findall(text)


def _ngrams(tokens, n):
    return Counter(tuple(tokens[i:i + n]) for i in range(len(tokens) - n + 1))


# ---------------- 文本相似度指标 ----------------

def bleu(pred, ref, max_n=4):
    """BLEU-4，+1 平滑（避免任一 n-gram 精度为 0 时整体归零），带简短惩罚。"""
    p_toks, r_toks = tokenize(pred), tokenize(ref)
    if not p_toks or not r_toks:
        return 0.0
    log_sum, eps = 0.0, 1e-9
    for n in range(1, max_n + 1):
        p_ng, r_ng = _ngrams(p_toks, n), _ngrams(r_toks, n)
        overlap = sum(min(c, r_ng[g]) for g, c in p_ng.items() if g in r_ng)
        total = max(sum(p_ng.values()), 1)
        # +1 平滑
        log_sum += math.log((overlap + 1.0) / (total + 1.0) + eps)
    bp = 1.0 if len(p_toks) >= len(r_toks) else math.exp(1 - len(r_toks) / len(p_toks))
    return bp * math.exp(log_sum / max_n)


def rouge_n(pred, ref, n):
    """ROUGE-N F1。"""
    p_toks, r_toks = tokenize(pred), tokenize(ref)
    p_ng, r_ng = _ngrams(p_toks, n), _ngrams(r_toks, n)
    if not p_ng or not r_ng:
        return 0.0
    overlap = sum((p_ng & r_ng).values())
    if overlap == 0:
        return 0.0
    precision = overlap / sum(p_ng.values())
    recall = overlap / sum(r_ng.values())
    return 2 * precision * recall / (precision + recall)


def rouge_1(pred, ref):
    return rouge_n(pred, ref, 1)


def rouge_2(pred, ref):
    return rouge_n(pred, ref, 2)


def _lcs_table(a, b):
    """LCS 动态规划表，O(len(a)*len(b))。"""
    m, n = len(a), len(b)
    dp = [[0] * (n + 1) for _ in range(m + 1)]
    for i in range(1, m + 1):
        ai = a[i - 1]
        row, prev = dp[i], dp[i - 1]
        for j in range(1, n + 1):
            row[j] = prev[j - 1] + 1 if ai == b[j - 1] else max(prev[j], row[j - 1])
    return dp


def _lcs_match_pairs(a, b):
    """回溯 LCS 得到匹配 token 的 (i, j) 下标对，按 i 升序。"""
    dp = _lcs_table(a, b)
    pairs, i, j = [], len(a), len(b)
    while i > 0 and j > 0:
        if a[i - 1] == b[j - 1]:
            pairs.append((i - 1, j - 1))
            i, j = i - 1, j - 1
        elif dp[i - 1][j] >= dp[i][j - 1]:
            i -= 1
        else:
            j -= 1
    pairs.reverse()
    return pairs


def rouge_l(pred, ref):
    """ROUGE-L：LCS 长度的 Precision/Recall 调和平均（F1）。"""
    p_toks, r_toks = tokenize(pred), tokenize(ref)
    if not p_toks or not r_toks:
        return 0.0
    lcs_len = len(_lcs_match_pairs(p_toks, r_toks))
    if lcs_len == 0:
        return 0.0
    precision, recall = lcs_len / len(p_toks), lcs_len / len(r_toks)
    return 2 * precision * recall / (precision + recall)


def meteor(pred, ref):
    """
    简化 METEOR：LCS 位置对齐近似词对齐；
    Fmean = 10PR / (R + 9P)（recall 偏置），碎片惩罚 = 0.5*(chunks/matches)^2。
    注：标准 METEOR 的同义词/词干匹配需要词库，中文无现成语义库故省略。
    """
    p_toks, r_toks = tokenize(pred), tokenize(ref)
    if not p_toks or not r_toks:
        return 0.0
    pairs = _lcs_match_pairs(p_toks, r_toks)
    matches = len(pairs)
    if matches == 0:
        return 0.0
    precision, recall = matches / len(p_toks), matches / len(r_toks)
    fmean = (10 * precision * recall) / (recall + 9 * precision)
    # 碎片数：匹配对中预测侧下标连续的段落数（对齐越碎片化惩罚越重）
    chunks, prev_i = 0, -2
    for i, _ in pairs:
        if i != prev_i + 1:
            chunks += 1
        prev_i = i
    penalty = 0.5 * (chunks / matches) ** 2
    return fmean * (1 - penalty)


def exact_token_coverage(pred, ref):
    """自定义：参考答案的信息 token 在回答中的覆盖率（汉字 bigram 口径，衡量要点覆盖）。"""
    r_bigrams = set(zip(tokenize(ref), tokenize(ref)[1:]))
    if not r_bigrams:
        return 0.0
    p_bigrams = set(zip(tokenize(pred), tokenize(pred)[1:]))
    return len(r_bigrams & p_bigrams) / len(r_bigrams)


# 指标注册表：rag_eval.py 遍历此表计算所有文本指标；新增指标在此注册即可
TEXT_METRICS = {
    "bleu4": bleu,
    "rouge1": rouge_1,
    "rouge2": rouge_2,
    "rougeL": rouge_l,
    "meteor": meteor,
    "ref_coverage": exact_token_coverage,
}


# ---------------- 检索指标 ----------------

def source_match(source, relevant):
    """宽松匹配：候选来源与任一标注条目双向包含即命中（容忍 '数据结构-链表' vs '数据结构 - 链表'）。"""
    if not source or not relevant:
        return False
    s = re.sub(r"[\s\-—_]+", "", source).lower()
    for rel in relevant:
        r = re.sub(r"[\s\-—_]+", "", rel).lower()
        if not r:
            continue
        if r in s or s in r:
            return True
    return False


def recall_at_k(candidate_sources, relevant, k):
    return 1.0 if any(source_match(s, relevant) for s in candidate_sources[:k]) else 0.0


def mrr(candidate_sources, relevant):
    for rank, s in enumerate(candidate_sources, start=1):
        if source_match(s, relevant):
            return 1.0 / rank
    return 0.0


# ---------------- LLM-as-Judge ----------------

JUDGE_SYSTEM_PROMPT = """你是一个严格的 RAG 系统评估员。给定【问题】【参考答案】【系统回答】，请从三个维度打分（1-5 整数）：
1. relevance 相关性：回答是否针对问题所问的知识点展开（5=完全针对；1=答非所问）。
2. accuracy 准确性：回答与参考答案的事实是否一致（5=无任何事实错误；1=大量错误）。
3. completeness 完整性：参考答案的要点被覆盖的程度（5=全部覆盖；1=几乎未覆盖）。
4. hallucination：回答是否编造了参考答案中没有的具体事实或概念（true/false）。通用的学习建议、客套话、知识库未收录的声明不算编造。
只输出 JSON：{"relevance": int, "accuracy": int, "completeness": int, "hallucination": bool, "reason": "一句话理由"}"""


def parse_judge_json(text):
    """从 judge 回复中容错解析 JSON。"""
    m = re.search(r"\{[^{}]*\}", text, re.S)
    if not m:
        return None
    try:
        import json
        obj = json.loads(m.group(0))
        if all(k in obj for k in ("relevance", "accuracy", "completeness", "hallucination")):
            return obj
    except Exception:
        pass
    return None


# ---------------- 自检 ----------------

if __name__ == "__main__":
    ref = "链表是通过指针串联的非连续存储结构，分为单链表、双向链表、循环链表，常见考点包括链表反转和LRU缓存实现。"
    good = "链表是通过指针串联的非连续存储结构，分为单链表、双向链表、循环链表。常见考点：链表反转、LRU缓存实现。"
    bad = "今天是天气很好，我们去公园散步吧。"
    for name, fn in TEXT_METRICS.items():
        print(f"{name:12s} good={fn(good, ref):.4f}  bad={fn(bad, ref):.4f}")
    cands = ["数据结构 - 数组", "数据结构 - 链表", "数据结构 - 栈"]
    rel = ["数据结构 - 链表"]
    print("recall@1 =", recall_at_k(cands, rel, 1), "| recall@3 =", recall_at_k(cands, rel, 3),
          "| mrr =", mrr(cands, rel))
    print("judge parse:", parse_judge_json('前置说明 {"relevance": 4, "accuracy": 5, "completeness": 3, "hallucination": false, "reason": "ok"} 后缀'))
