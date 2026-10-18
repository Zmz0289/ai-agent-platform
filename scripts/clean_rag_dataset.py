# -*- coding: utf-8 -*-
"""RAG 训练/知识库 JSONL 清洗脚本（电商客服单轮对话）

流水线：
  解析 -> 文本规范化 -> 空值过滤 -> 寒暄/无意义词表过滤 -> PII 过滤
      -> 精确去重(问题+答案归一化键) -> 一问多答冲突消解(频次/长度/最新)
      -> 长度质量过滤 -> 输出 JSONL + 清洗报告 JSON

输出保持 {"messages":[system,user,assistant]} 结构，可直接走
CsDataImportService.importFromJsonl 入库。
"""
import collections
import glob
import json
import os
import re
import sys

ROOT = r"d:\Work\Code\AiAgent\ai-agent-platform"
SRC_CANDIDATES = [p for p in glob.glob(os.path.join(ROOT, "dataset", "*", "train_clean_v2_small.jsonl"))]
if not SRC_CANDIDATES:
    print("source dataset not found", file=sys.stderr)
    sys.exit(1)
SRC = SRC_CANDIDATES[0]
OUT = os.path.join(os.path.dirname(SRC), "train_clean_v3.jsonl")
REPORT = os.path.join(os.path.dirname(SRC), "clean_report_v3.json")

# ---------------------------------------------------------------------------
# 规则
# ---------------------------------------------------------------------------
CONTROL_RE = re.compile(r"[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f\ufeff\u200b\u200c\u200d\ufeff]+")
WS_RE = re.compile(r"\s+")
# 归一化键：只保留“字/数字/下划线”（unicode 感知），忽略标点/空白/大小写
KEY_RE = re.compile(r"[^\w]+", flags=re.U)
MOBILE_RE = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")
IDCARD_RE = re.compile(r"(?<!\d)\d{17}[\dXx](?!\d)")

DENY_Q = {"在吗", "在么", "在不在", "你好", "您好", "嗨", "哈喽", "hi", "hello",
          "嗯", "嗯嗯", "嗯呢", "恩", "恩恩", "好的", "好", "哦", "哦哦", "对",
          "对的", "是的", "是", "嗯嗯呢", "哈哈", "哈", "呵呵", "谢谢", "感谢",
          "ok", "收到", "明白", "可以", "能", "行", "看", "看看", "嗯呢亲"}
DENY_A = {"嗯", "嗯嗯", "嗯呢", "恩", "恩恩", "哦", "哦哦", "对", "对的", "是的",
          "是", "哈哈", "哈", "呵呵", "好", "好的", "好滴", "ok", "可以", "能",
          "行", "收到", "明白", "谢谢", "感谢", "亲", "亲亲", "嗯呢亲", "您好",
          "你好"}


def clean_text(s):
    s = CONTROL_RE.sub("", s or "")
    s = WS_RE.sub(" ", s).strip()
    return s


def key_of(s):
    return KEY_RE.sub("", s or "", ).lower()


def in_deny(s, table):
    t = s.strip().lower()
    return t in table


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
def main():
    stage = collections.OrderedDict()
    stage["rows_read"] = 0

    system_prompt = None
    # q_key -> Counter(answer_key -> count) ；并记录每个 (q_key,answer_key) 首次出现原文与最新序号
    answer_stats = collections.defaultdict(collections.Counter)
    first_text = {}      # (q_key, a_key) -> (question, answer)
    last_index = {}      # (q_key, a_key) -> index

    dropped = collections.Counter()

    with open(SRC, "r", encoding="utf-8") as f:
        idx = 0
        for line in f:
            line = line.strip()
            if not line:
                continue
            idx += 1
            stage["rows_read"] += 1

            try:
                data = json.loads(line)
            except Exception:
                dropped["parse_fail"] += 1
                continue

            msgs = data.get("messages")
            if not isinstance(msgs, list) or len(msgs) < 3:
                dropped["structure"] += 1
                continue

            system = None
            q = a = None
            for m in msgs:
                role = m.get("role")
                content = m.get("content")
                if role == "system" and system is None:
                    system = content
                elif role == "user":
                    q = content
                elif role == "assistant":
                    a = content

            q = clean_text(q)
            a = clean_text(a)
            if not q or not a:
                dropped["empty_qa"] += 1
                continue
            if in_deny(q, DENY_Q) or in_deny(a, DENY_A):
                dropped["chit_chat"] += 1
                continue
            if MOBILE_RE.search(q) or MOBILE_RE.search(a) or IDCARD_RE.search(q) or IDCARD_RE.search(a):
                dropped["pii"] += 1
                continue

            qk = key_of(q)
            ak = key_of(a)
            if not qk or not ak:
                dropped["empty_key"] += 1
                continue

            answer_stats[qk][ak] += 1
            if (qk, ak) not in first_text:
                first_text[(qk, ak)] = (q, a)
            last_index[(qk, ak)] = idx
            if system and system_prompt is None:
                system_prompt = system

    stage["valid_after_filter"] = sum(sum(c.values()) for c in answer_stats.values())
    stage["unique_qa_pairs"] = len(first_text)

    # 冲突消解：同一问题，选 (频次最高, 答案最长, 最新)
    final = []
    conflict_questions = 0
    for qk, counters in answer_stats.items():
        if len(counters) > 1:
            conflict_questions += 1
        best_key = max(counters.items(), key=lambda kv: (kv[1], len(kv[0]), last_index.get((qk, kv[0]), 0)))[0]
        q, a = first_text[(qk, best_key)]
        if len(q) < 4 or len(a) < 4:
            dropped["short_after_resolve"] += 1
            continue
        final.append((q, a))

    stage["conflict_questions"] = conflict_questions
    stage["final_kept"] = len(final)

    # 输出
    sysp = system_prompt or "你是一个专业的电商客服。"
    with open(OUT, "w", encoding="utf-8") as out:
        for q, a in final:
            out.write(json.dumps({"messages": [
                {"role": "system", "content": sysp},
                {"role": "user", "content": q},
                {"role": "assistant", "content": a},
            ]}, ensure_ascii=False) + "\n")

    report = {
        "source": SRC,
        "output": OUT,
        "stages": stage,
        "dropped_detail": dict(dropped),
    }
    with open(REPORT, "w", encoding="utf-8") as rp:
        json.dump(report, rp, ensure_ascii=False, indent=2)

    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
