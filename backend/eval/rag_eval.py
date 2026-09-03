# -*- coding: utf-8 -*-
"""
RAG 离线评测框架 —— student-agent 知识库检索 + 端到端问答评估。

用法示例：
  # 检索层评估（需后端已启动）
  python rag_eval.py --phase retrieval --variant-name baseline
  # 端到端评估（含 LLM judge，key 默认取环境变量 SILICONFLOW_API_KEY）
  python rag_eval.py --phase e2e --variant-name baseline
  # 参数对比实验（不同阈值各跑一次检索评估后对比）
  python rag_eval.py --phase retrieval --threshold 0.4 --variant-name thr04
  python rag_eval.py --phase retrieval --threshold 0.6 --variant-name thr06
  python rag_eval.py --compare report/thr04_*.json report/thr06_*.json

依赖：仅 Python 3.8+ 标准库。judge 指标可选（无 key 时自动跳过）。
扩展：
  - 加用例：向 golden_set.json 的 cases 数组追加（字段见 _schema）
  - 加指标：metrics.py 实现 (pred, ref)->float 并注册进 TEXT_METRICS
"""
import argparse
import json
import re
import sys
import time
import urllib.request
import urllib.error
from datetime import datetime
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")
sys.path.insert(0, str(Path(__file__).parent))
from metrics import TEXT_METRICS, recall_at_k, mrr, source_match, parse_judge_json, JUDGE_SYSTEM_PROMPT  # noqa: E402

EVAL_DIR = Path(__file__).parent


# ---------------- HTTP 工具 ----------------

def http_json(method, url, body=None, token=None, timeout=180):
    req = urllib.request.Request(url, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode("utf-8") if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return {"code": e.code, "msg": e.read().decode("utf-8", "ignore")[:300], "data": None}


def resolve_judge_key(args):
    """judge key 优先级：CLI 参数 > 进程环境变量 > Windows 用户级环境变量 > application.yml 明文。"""
    if args.judge_api_key:
        return args.judge_api_key
    import os
    env = os.environ.get("SILICONFLOW_API_KEY")
    if env:
        return env
    if sys.platform == "win32":
        try:
            import winreg
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment") as k:
                val, _ = winreg.QueryValueEx(k, "SILICONFLOW_API_KEY")
                if val:
                    return val
        except OSError:
            pass
    yml = Path(__file__).parent.parent / "src/main/resources/application.yml"
    if yml.exists():
        text = yml.read_text(encoding="utf-8")
        m = re.search(r"api-key:\s*(sk-[A-Za-z0-9]+)", text)
        if m:
            return m.group(1)
    return None


def judge_answer(question, reference, answer, base_url, api_key, model):
    """LLM-as-Judge：返回 {relevance, accuracy, completeness, hallucination, reason} 或 None。"""
    user_msg = f"【问题】{question}\n【参考答案】{reference}\n【系统回答】{answer}"
    body = {
        "model": model,
        "messages": [
            {"role": "system", "content": JUDGE_SYSTEM_PROMPT},
            {"role": "user", "content": user_msg},
        ],
        "temperature": 0,
        "max_tokens": 200,
    }
    for attempt in (1, 2):
        try:
            req = urllib.request.Request(
                base_url.rstrip("/") + "/chat/completions",
                data=json.dumps(body).encode("utf-8"), method="POST")
            req.add_header("Content-Type", "application/json")
            req.add_header("Authorization", "Bearer " + api_key)
            with urllib.request.urlopen(req, timeout=60) as resp:
                content = json.loads(resp.read().decode("utf-8"))["choices"][0]["message"]["content"]
            parsed = parse_judge_json(content)
            if parsed:
                return parsed
        except Exception:
            if attempt == 2:
                return None
            time.sleep(2)
    return None


# ---------------- Phase 1: 检索层评估 ----------------

def run_retrieval_eval(cases, args):
    results = []
    for c in cases:
        url = (f"{args.base_url}/api/debug/rag/search?subject={urllib.request.quote(c['subject'])}"
               f"&keyword={urllib.request.quote(c['keyword'])}&topk={args.max_topk}"
               f"&threshold={args.threshold}")
        resp = http_json("GET", url, token=args._token, timeout=30)
        rec = {"id": c["id"], "difficulty": c["difficulty"], "query": c["query"],
               "relevant": c["relevant_sources"], "expected_hit": c["expected_hit"],
               "retriever": None, "candidates": [], "costMs": None, "error": None}
        if resp.get("code") == 200 and resp.get("data"):
            d = resp["data"]
            rec["retriever"] = d.get("retriever")
            rec["candidates"] = [{"source": x["source"], "score": x["score"]} for x in d.get("candidates", [])]
            rec["costMs"] = d.get("costMs")
        else:
            rec["error"] = str(resp.get("msg"))[:200]
        results.append(rec)
        flag = rec["retriever"] or "error"
        print(f"  [{rec['id']}] {flag:8s} top1={rec['candidates'][0]['source'] if rec['candidates'] else '-':<28s}"
              f" {rec['costMs']}ms")
    return results


def aggregate_retrieval(results, topk_list):
    hit_cases = [r for r in results if r["expected_hit"]]
    ood_cases = [r for r in results if not r["expected_hit"]]
    agg = {"n_total": len(results), "n_hit_expected": len(hit_cases), "n_ood": len(ood_cases)}
    for k in topk_list:
        scores = [recall_at_k([c["source"] for c in r["candidates"]], r["relevant"], k) for r in hit_cases]
        agg[f"recall@{k}"] = round(sum(scores) / max(len(scores), 1), 4)
    mrrs = [mrr([c["source"] for c in r["candidates"]], r["relevant"]) for r in hit_cases]
    agg["mrr"] = round(sum(mrrs) / max(len(mrrs), 1), 4)
    retriever_count = {}
    for r in results:
        retriever_count[r["retriever"] or "error"] = retriever_count.get(r["retriever"] or "error", 0) + 1
    agg["retriever_distribution"] = retriever_count
    # 领域外拒答：ood 用例检索应 miss（内存跨科目误命中则计为未拒答）
    if ood_cases:
        rejected = sum(1 for r in ood_cases if r["retriever"] == "miss")
        agg["ood_rejection_rate"] = round(rejected / len(ood_cases), 4)
    costs = [r["costMs"] for r in results if r["costMs"] is not None]
    agg["avg_cost_ms"] = round(sum(costs) / max(len(costs), 1), 1) if costs else None
    return agg


# ---------------- Phase 2: 端到端评估 ----------------

def run_e2e_eval(cases, args, judge_cfg):
    results = []
    for idx, c in enumerate(cases, 1):
        rec = {"id": c["id"], "difficulty": c["difficulty"], "query": c["query"],
               "relevant": c["relevant_sources"], "expected_hit": c["expected_hit"],
               "answer": None, "cited_source": False, "text_metrics": {}, "judge": None, "error": None}
        try:
            # 每条用例独立会话，避免上下文串扰
            sess = http_json("POST", f"{args.base_url}/api/session",
                             {"title": f"rag-eval-{c['id']}"}, token=args._token, timeout=30)
            sid = sess["data"]["sessionId"]
            t0 = time.time()
            chat = http_json("POST", f"{args.base_url}/api/chat",
                             {"sessionId": sid, "message": c["query"]}, token=args._token, timeout=240)
            rec["latency_s"] = round(time.time() - t0, 1)
            answer = (chat.get("data") or {}).get("content") if chat.get("code") == 200 else None
            if not answer:
                rec["error"] = str(chat.get("msg"))[:200]
                results.append(rec)
                print(f"  [{c['id']}] ERROR: {rec['error']}")
                continue
            rec["answer"] = answer
            rec["cited_source"] = "【知识来源】" in answer or "知识来源" in answer
            rec["text_metrics"] = {name: round(fn(answer, c["reference_answer"]), 4)
                                   for name, fn in TEXT_METRICS.items()}
            if judge_cfg:
                rec["judge"] = judge_answer(c["query"], c["reference_answer"], answer, *judge_cfg)
            j = rec["judge"]
            jdesc = f" judge={j['relevance']}/{j['accuracy']}/{j['completeness']}" + \
                    (" H!" if j.get("hallucination") else "") if j else " judge=skipped"
            print(f"  [{c['id']}] {rec['latency_s']:>5.1f}s bleu4={rec['text_metrics']['bleu4']:.3f}"
                  f" rougeL={rec['text_metrics']['rougeL']:.3f} cite={rec['cited_source']}{jdesc}")
        except Exception as e:
            rec["error"] = repr(e)[:300]
        results.append(rec)
    return results


def aggregate_e2e(results):
    ok = [r for r in results if not r["error"]]
    agg = {"n_total": len(results), "n_answered": len(ok),
           "n_failed": len(results) - len(ok)}
    for name in TEXT_METRICS:
        vals = [r["text_metrics"][name] for r in ok if name in r["text_metrics"]]
        agg[name] = round(sum(vals) / max(len(vals), 1), 4)
    agg["source_cited_rate"] = round(
        sum(1 for r in ok if r["cited_source"]) / max(len(ok), 1), 4)
    agg["avg_latency_s"] = round(
        sum(r["latency_s"] for r in ok if "latency_s" in r) / max(len(ok), 1), 1)
    judged = [r for r in ok if r["judge"]]
    if judged:
        for dim in ("relevance", "accuracy", "completeness"):
            agg[f"judge_{dim}"] = round(
                sum(r["judge"][dim] for r in judged) / len(judged), 2)
        agg["judge_hallucination_rate"] = round(
            sum(1 for r in judged if r["judge"].get("hallucination")) / len(judged), 4)
        # 分难度明细
        by_diff = {}
        for r in judged:
            d = r["difficulty"]
            by_diff.setdefault(d, []).append(r["judge"]["accuracy"])
        agg["judge_accuracy_by_difficulty"] = {
            d: round(sum(v) / len(v), 2) for d, v in sorted(by_diff.items())}
    return agg


# ---------------- 报告生成 ----------------

def build_suggestions(agg_r, agg_e):
    tips = []
    if agg_r:
        if agg_r.get("recall@3", 1) < 0.8:
            tips.append(f"检索 recall@3 仅 {agg_r['recall@3']}：优先补充未命中用例对应的知识条目，"
                        "或下调向量阈值/调整分词策略后重跑对比。")
        if agg_r.get("ood_rejection_rate", 1) < 1.0:
            tips.append(f"领域外拒答率 {agg_r['ood_rejection_rate']}：存在跨科目模糊匹配误命中，"
                        "建议收紧 matchTopic 的宽松包含策略。")
        if agg_r.get("avg_cost_ms") and agg_r["avg_cost_ms"] > 2000:
            tips.append(f"平均检索耗时 {agg_r['avg_cost_ms']}ms：向量兜底触发频繁，"
                        "可考虑扩充内存关键词覆盖以减少远程 embedding 调用。")
    if agg_e:
        if agg_e.get("judge_hallucination_rate", 0) > 0:
            tips.append(f"幻觉率 {agg_e['judge_hallucination_rate']}：检查明细表中标 H 的用例，"
                        "确认是模型编造还是 judge 误判；可在系统提示词中强化『未收录须明说』约束。")
        if agg_e.get("source_cited_rate", 0) < 0.8:
            tips.append(f"知识来源引用率 {agg_e['source_cited_rate']}：部分回答未标注来源，"
                        "评估时无法溯源，建议在提示词中要求引用检索结果的【知识来源】标注。")
        b, jr = agg_e.get("bleu4", 0), agg_e.get("judge_relevance", 0)
        if b < 0.3 and jr >= 4:
            tips.append(f"BLEU-4({b}) 低而 judge 相关性({jr}/5) 高：模型对检索内容做了合理改写而非逐字复述，"
                        "属正常现象——文本相似度指标仅作参考，以 judge 指标为准。")
        if agg_e.get("ref_coverage", 1) < 0.5:
            tips.append(f"要点覆盖率 {agg_e['ref_coverage']}：回答未充分复述参考答案要点，"
                        "可在提示词中要求按『核心概念/学习重点/常见考点』结构组织回答。")
        if agg_e.get("judge_accuracy", 5) < 4:
            tips.append(f"judge 准确性 {agg_e['judge_accuracy']}/5 偏低：结合明细定位是检索未命中"
                        "（先看 recall@K）还是生成环节偏离检索内容。")
    if not tips:
        tips.append("各项指标均在健康区间，当前配置可直接作为基线；可尝试改动 threshold/topk 跑对比实验。")
    return tips


def bar_svg(label, value, max_val=1.0, color="#409EFF"):
    pct = max(0.0, min(1.0, value / max_val))
    return (f'<div class="bar-row"><span class="bar-label">{label}</span>'
            f'<svg width="420" height="16"><rect x="0" y="2" width="420" height="12" fill="#eee" rx="3"/>'
            f'<rect x="0" y="2" width="{420 * pct:.0f}" height="12" fill="{color}" rx="3"/></svg>'
            f'<span class="bar-val">{value}</span></div>')


def build_html(report):
    agg_r, agg_e = report.get("retrieval_summary"), report.get("e2e_summary")
    rows_r, rows_e = report.get("retrieval_results", []), report.get("e2e_results", [])
    parts = ["""<!DOCTYPE html><html><head><meta charset="utf-8">
<title>RAG 评估报告</title><style>
body{font-family:'Microsoft YaHei',sans-serif;max-width:1080px;margin:24px auto;padding:0 16px;color:#333}
h2{border-left:4px solid #409EFF;padding-left:10px;margin-top:32px}
.bar-row{display:flex;align-items:center;margin:6px 0}.bar-label{width:190px;font-size:14px}
.bar-val{margin-left:8px;font-weight:bold}
table{border-collapse:collapse;width:100%;font-size:13px;margin:12px 0}
td,th{border:1px solid #ddd;padding:6px 8px;text-align:left}
th{background:#f5f7fa}.low{background:#fde2e2}.warn{background:#fef0e6}
.meta{background:#f5f7fa;padding:10px 14px;border-radius:6px;font-size:13px}
.tips li{margin:8px 0}.jH{color:#c0392b;font-weight:bold}
</style></head><body>"""]
    cfg = report["config"]
    parts.append(f"<h1>RAG 评估报告 · {report['variant']}</h1>"
                 f"<div class='meta'>时间：{report['timestamp']}　|　端点：{cfg['base_url']}　|　"
                 f"用例数：{cfg['n_cases']}　|　threshold：{cfg['threshold']}　|　"
                 f"phase：{cfg['phase']}　|　judge：{'启用(' + cfg['judge_model'] + ')' if cfg['judge'] else '未启用'}</div>")

    if agg_r:
        parts.append("<h2>检索层指标</h2>")
        for k in sorted(agg_r):
            if k.startswith("recall@") or k in ("mrr", "ood_rejection_rate"):
                v = agg_r[k]
                color = "#409EFF" if v >= 0.8 else ("#E6A23C" if v >= 0.6 else "#F56C6C")
                parts.append(bar_svg(k, v, color=color))
        parts.append(f"<p>retriever 分布：{agg_r['retriever_distribution']}　|　"
                     f"平均耗时：{agg_r['avg_cost_ms']}ms</p>")
        if rows_r:
            parts.append("<table><tr><th>ID</th><th>难度</th><th>检索器</th><th>Top-1 来源</th>"
                         "<th>Recall@3</th><th>MRR</th><th>耗时ms</th></tr>")
            for r in rows_r:
                srcs = [c["source"] for c in r["candidates"]]
                r3, rm = recall_at_k(srcs, r["relevant"], 3) if r["expected_hit"] else "-yse", \
                    mrr(srcs, r["relevant"]) if r["expected_hit"] else "-"
                cls = " class='low'" if (r3 == 0 and r["expected_hit"]) or r["error"] else ""
                parts.append(f"<tr{cls}><td>{r['id']}</td><td>{r['difficulty']}</td>"
                             f"<td>{r['retriever']}</td><td>{srcs[0] if srcs else '-'}</td>"
                             f"<td>{r3}</td><td>{rm}</td><td>{r['costMs']}</td></tr>")
            parts.append("</table>")

    if agg_e:
        parts.append("<h2>端到端指标（文本相似度）</h2>")
        for name in TEXT_METRICS:
            if name in agg_e:
                v = agg_e[name]
                color = "#67C23A" if v >= 0.5 else ("#409EFF" if v >= 0.3 else "#E6A23C")
                parts.append(bar_svg(name, v, color=color))
        parts.append("<h2>端到端指标（LLM-as-Judge，1-5 分）</h2>" if agg_e.get("judge_relevance")
                     else "")
        if agg_e.get("judge_relevance"):
            for dim, label in (("judge_relevance", "相关性"), ("judge_accuracy", "准确性"),
                               ("judge_completeness", "完整性")):
                v = agg_e[dim]
                color = "#67C23A" if v >= 4 else ("#E6A23C" if v >= 3 else "#F56C6C")
                parts.append(bar_svg(label, v, max_val=5, color=color))
            parts.append(f"<p>幻觉率：{agg_e['judge_hallucination_rate']}　|　"
                         f"来源引用率：{agg_e['source_cited_rate']}　|　平均时延：{agg_e['avg_latency_s']}s　|　"
                         f"分难度准确性：{agg_e.get('judge_accuracy_by_difficulty', {})}</p>")
        if rows_e:
            parts.append("<h2>用例明细</h2><table><tr><th>ID</th><th>难度</th><th>提问</th><th>BLEU4</th>"
                         "<th>ROUGE-L</th><th>要点覆盖</th><th>Judge(相/准/完)</th><th>幻觉</th><th>引用来源</th>"
                         "<th>时延s</th></tr>")
            for r in rows_e:
                if r["error"]:
                    parts.append(f"<tr class='low'><td>{r['id']}</td><td colspan='9'>失败：{r['error']}</td></tr>")
                    continue
                t, j = r["text_metrics"], r["judge"]
                hcls = " class='jH'" if (j and j.get("hallucination")) else ""
                low = " class='warn'" if j and (j["accuracy"] <= 2 or j["completeness"] <= 2) else ""
                parts.append(f"<tr{low}><td>{r['id']}</td><td>{r['difficulty']}</td>"
                             f"<td>{r['query'][:40]}</td>"
                             f"<td>{t.get('bleu4', 0):.3f}</td><td>{t.get('rougeL', 0):.3f}</td>"
                             f"<td>{t.get('ref_coverage', 0):.3f}</td>"
                             f"<td>{('%d/%d/%d' % (j['relevance'], j['accuracy'], j['completeness'])) if j else '-'}</td>"
                             f"<td{hcls}>{'是' if (j and j.get('hallucination')) else '否'}</td>"
                             f"<td>{'是' if r['cited_source'] else '否'}</td><td>{r.get('latency_s', '-')}</td></tr>")
            parts.append("</table>")

    tips = report.get("suggestions", [])
    if tips:
        parts.append("<h2>改进建议（自动生成）</h2><ul class='tips'>"
                     + "".join(f"<li>{t}</li>" for t in tips) + "</ul>")
    parts.append("</body></html>")
    return "".join(parts)


def print_compare(json_paths):
    reports = [json.loads(Path(p).read_text(encoding="utf-8")) for p in json_paths]
    keys = sorted({k for r in reports
                   for k in list((r.get("retrieval_summary") or {}).keys())
                   + list((r.get("e2e_summary") or {}).keys())
                   if k not in ("retriever_distribution", "judge_accuracy_by_difficulty")})
    print("\n===== 对比结果 =====")
    header = f"{'指标':<24s}" + "".join(f"{r['variant']:>16s}" for r in reports)
    print(header)
    print("-" * len(header))
    for k in keys:
        row = f"{k:<24s}"
        for r in reports:
            v = (r.get("retrieval_summary") or {}).get(k)
            if v is None:
                v = (r.get("e2e_summary") or {}).get(k)
            cell = "-" if v is None else f"{v!s}"
            row += f"{cell:>16s}"
        print(row)


# ---------------- 主流程 ----------------

def main():
    ap = argparse.ArgumentParser(description="RAG 离线评测框架")
    ap.add_argument("--base-url", default="http://localhost:8080")
    ap.add_argument("--golden-set", default=str(EVAL_DIR / "golden_set.json"))
    ap.add_argument("--phase", choices=["retrieval", "e2e", "all"], default="all")
    ap.add_argument("--topk-list", default="1,3,5", help="Recall@K 的 K 列表")
    ap.add_argument("--max-topk", type=int, default=5, help="检索接口返回候选数上限")
    ap.add_argument("--threshold", type=float, default=0.5, help="向量检索相似度阈值（实验变量）")
    ap.add_argument("--username", default="kaoyan_zhang")
    ap.add_argument("--password", default="123456")
    ap.add_argument("--judge-api-key", default=None, help="LLM judge key；默认取 SILICONFLOW_API_KEY 或 application.yml")
    ap.add_argument("--judge-base-url", default="https://api.siliconflow.cn/v1")
    ap.add_argument("--judge-model", default="deepseek-ai/DeepSeek-V3")
    ap.add_argument("--variant-name", default=None, help="本次运行的参数标识（默认含时间戳）")
    ap.add_argument("--limit", type=int, default=None, help="只跑前 N 条（调试用）")
    ap.add_argument("--difficulty", default=None, help="只跑指定难度：easy/medium/hard/ood")
    ap.add_argument("--output-dir", default=str(EVAL_DIR / "report"))
    ap.add_argument("--compare", nargs="+", help="对比两份以上评测 JSON，输出对比表后退出")
    ap.add_argument("--reuse-report", default=None,
                    help="复用已有 e2e 报告中的回答，只补跑 LLM judge 后重新出报告（不重新对话）")
    args = ap.parse_args()

    if args.compare:
        print_compare(args.compare)
        return

    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    # 复用模式：从旧报告载入 e2e 结果，仅补 judge
    if args.reuse_report:
        old = json.loads(Path(args.reuse_report).read_text(encoding="utf-8"))
        if not old.get("e2e_results"):
            print("指定报告中没有 e2e_results，无法复用")
            sys.exit(1)
        key = resolve_judge_key(args)
        if not key:
            print("未找到 judge API key，无法补跑 judge")
            sys.exit(1)
        judge_cfg = (args.judge_base_url, key, args.judge_model)
        print(f"复用 {len(old['e2e_results'])} 条已有回答，仅补跑 judge...")
        e_results = old["e2e_results"]
        for r in e_results:
            if r["error"] or not r["answer"]:
                continue
            ref = next((c["reference_answer"] for c in
                        json.loads(Path(args.golden_set).read_text(encoding="utf-8"))["cases"]
                        if c["id"] == r["id"]), None)
            if ref:
                r["judge"] = judge_answer(r["query"], ref, r["answer"], *judge_cfg)
                j = r["judge"]
                if j:
                    print(f"  [{r['id']}] judge={j['relevance']}/{j['accuracy']}/{j['completeness']}"
                          + (" H!" if j.get("hallucination") else ""))
                else:
                    print(f"  [{r['id']}] judge=failed")
        report = {"variant": args.variant_name or (old["variant"] + "_judged"),
                  "timestamp": datetime.now().isoformat(timespec="seconds"),
                  "config": {**old["config"], "judge": True, "judge_model": args.judge_model},
                  "retrieval_summary": old.get("retrieval_summary"),
                  "retrieval_results": old.get("retrieval_results"),
                  "e2e_summary": aggregate_e2e(e_results), "e2e_results": e_results}
        report["suggestions"] = build_suggestions(report["retrieval_summary"], report["e2e_summary"])
        json_path = out_dir / f"rag_eval_{report['variant']}.json"
        html_path = out_dir / f"rag_eval_{report['variant']}.html"
        json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        html_path.write_text(build_html(report), encoding="utf-8")
        print("改进建议:")
        for t in report["suggestions"]:
            print(f"  - {t}")
        print(f"\n报告已生成：\n  {json_path}\n  {html_path}")
        return
    cases = json.loads(Path(args.golden_set).read_text(encoding="utf-8"))["cases"]
    if args.difficulty:
        cases = [c for c in cases if c["difficulty"] == args.difficulty]
    if args.limit:
        cases = cases[:args.limit]
    topk_list = [int(k) for k in args.topk_list.split(",")]
    variant = args.variant_name or datetime.now().strftime("%Y%m%d_%H%M%S")

    # 登录
    login = http_json("POST", f"{args.base_url}/api/user/login",
                      {"username": args.username, "password": args.password})
    if login.get("code") != 200 or not (login.get("data") or {}).get("token"):
        print(f"登录失败：{login}")
        sys.exit(1)
    args._token = login["data"]["token"]
    print(f"登录成功，用例数 {len(cases)}，phase={args.phase}\n")

    report = {"variant": variant, "timestamp": datetime.now().isoformat(timespec="seconds"),
              "config": {"base_url": args.base_url, "phase": args.phase, "threshold": args.threshold,
                         "max_topk": args.max_topk, "n_cases": len(cases),
                         "judge": False, "judge_model": args.judge_model},
              "retrieval_summary": None, "retrieval_results": None,
              "e2e_summary": None, "e2e_results": None}

    if args.phase in ("retrieval", "all"):
        print("===== Phase 1 检索层评估 =====")
        r_results = run_retrieval_eval(cases, args)
        report["retrieval_summary"] = aggregate_retrieval(r_results, topk_list)
        report["retrieval_results"] = r_results
        print("汇总:", json.dumps(report["retrieval_summary"], ensure_ascii=False), "\n")

    judge_cfg = None
    if args.phase in ("e2e", "all"):
        key = resolve_judge_key(args)
        if key:
            judge_cfg = (args.judge_base_url, key, args.judge_model)
            report["config"]["judge"] = True
        else:
            print("未找到 judge API key（--judge-api-key / SILICONFLOW_API_KEY），跳过 LLM 评审指标\n")
        print("===== Phase 2 端到端评估 =====")
        e_results = run_e2e_eval(cases, args, judge_cfg)
        report["e2e_summary"] = aggregate_e2e(e_results)
        report["e2e_results"] = e_results
        print("汇总:", json.dumps(report["e2e_summary"], ensure_ascii=False), "\n")

    report["suggestions"] = build_suggestions(report["retrieval_summary"], report["e2e_summary"])

    json_path = out_dir / f"rag_eval_{variant}.json"
    html_path = out_dir / f"rag_eval_{variant}.html"
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    html_path.write_text(build_html(report), encoding="utf-8")
    print("改进建议:")
    for t in report["suggestions"]:
        print(f"  - {t}")
    print(f"\n报告已生成：\n  {json_path}\n  {html_path}")


if __name__ == "__main__":
    main()
