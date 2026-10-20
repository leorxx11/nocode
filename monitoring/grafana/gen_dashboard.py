"""生成 NoCode AI 调用监控看板（Grafana JSON）

用法：python3 monitoring/grafana/gen_dashboard.py monitoring/grafana/dashboards/nocode-ai.json
改看板请改这个脚本再重新生成，不要直接改 JSON；发布：scripts/deploy-nocode.sh monitoring
"""
import json
import sys

DS = {"type": "prometheus", "uid": "prometheus"}


def total(metric, window="$__range"):
    """时间窗口内的增量。新出现的序列从 0 开始计数，increase() 会漏掉它的第一个样本，所以改取当前值"""
    return f"(({metric} unless {metric} offset {window}) or increase({metric}[{window}]))"


REQ = 'ai_model_requests_total{model_name=~"$model"}'
TOK = 'ai_model_tokens_total{model_name=~"$model"}'
ERR = 'ai_model_errors_total{model_name=~"$model"}'
DUR = 'ai_model_response_duration_seconds'
DUR_F = '{model_name=~"$model"}'

panels = []
pid = 0


def add(panel, x, y, w, h):
    global pid
    pid += 1
    panel.update({"id": pid, "datasource": DS, "gridPos": {"x": x, "y": y, "w": w, "h": h}})
    for t in panel.get("targets", []):
        t.setdefault("datasource", DS)
    panels.append(panel)


def stat(title, expr, unit, x, w, desc="", decimals=None, thresholds=None):
    p = {
        "type": "stat", "title": title, "description": desc,
        "targets": [{"refId": "A", "expr": expr, "instant": True}],
        "fieldConfig": {"defaults": {"unit": unit, "color": {"mode": "thresholds"},
                                     "thresholds": thresholds or {"mode": "absolute", "steps": [{"color": "blue", "value": None}]}},
                        "overrides": []},
        "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                    "colorMode": "value", "graphMode": "none", "textMode": "value", "justifyMode": "center"},
    }
    if decimals is not None:
        p["fieldConfig"]["defaults"]["decimals"] = decimals
    add(p, x, 0, w, 4)


def series(title, targets, unit, x, y, w, h, desc="", stack=False, bars=False):
    add({
        "type": "timeseries", "title": title, "description": desc, "interval": "1m",
        "targets": [{"refId": chr(65 + i), "expr": e, "legendFormat": l} for i, (e, l) in enumerate(targets)],
        "fieldConfig": {"defaults": {"unit": unit, "min": 0, "custom": {
            "drawStyle": "bars" if bars else "line", "lineWidth": 2, "fillOpacity": 60 if bars else 10,
            # 调用量小时数据点稀疏，单独的点也要画出来
            "showPoints": "auto", "pointSize": 6, "spanNulls": False,
            "stacking": {"mode": "normal" if stack else "none", "group": "A"}}},
            "overrides": []},
        "options": {"legend": {"displayMode": "list", "placement": "bottom"}, "tooltip": {"mode": "multi", "sort": "desc"}},
    }, x, y, w, h)


def top(title, expr, legend, unit, x, y, w, h, desc=""):
    """排行表：ID 列 + 带条形的数值列，ID 较长时也能完整显示"""
    label = legend.strip("{}")
    name = label.replace("_id", " ID").replace("model_name", "模型")
    add({
        "type": "table", "title": title, "description": desc,
        "targets": [{"refId": "A", "expr": expr, "instant": True, "format": "table"}],
        "transformations": [
            {"id": "organize", "options": {"excludeByName": {"Time": True},
                                           "renameByName": {label: name,
                                                            "Value": "数值"}}},
            {"id": "sortBy", "options": {"sort": [{"field": "数值", "desc": True}]}},
        ],
        "fieldConfig": {"defaults": {"custom": {"align": "auto"}}, "overrides": [
            # ID 是纯数字，不指定为文本会被当成数值格式化（比如显示成 470 Quadr）
            {"matcher": {"id": "byName", "options": name}, "properties": [{"id": "unit", "value": "string"}]},
            {"matcher": {"id": "byName", "options": "数值"}, "properties": [
                {"id": "unit", "value": unit},
                {"id": "custom.cellOptions", "value": {"type": "gauge", "mode": "gradient", "valueDisplayMode": "text"}},
                {"id": "min", "value": 0}, {"id": "color", "value": {"mode": "continuous-BlYlRd"}}]}]},
        "options": {"showHeader": True, "cellHeight": "sm", "footer": {"show": False}},
    }, x, y, w, h)


# —— 第一行：时间范围内的汇总 ——
stat("AI 调用次数", f'sum({total(REQ.replace("}", ",status=\"started\"}"))})', "short", 0, 5,
     "所选时间范围内向大模型发起的请求数（VUE 工程每轮工具调用都算一次）")
stat("成功率", f'sum({total(REQ.replace("}", ",status=\"success\"}"))}) / sum({total(REQ.replace("}", ",status=\"started\"}"))})',
     "percentunit", 5, 4, decimals=1, thresholds={"mode": "absolute", "steps": [
         {"color": "red", "value": None}, {"color": "orange", "value": 0.9}, {"color": "green", "value": 0.98}]})
stat("错误次数", f'sum({total(ERR)}) or vector(0)', "short", 9, 4, thresholds={"mode": "absolute", "steps": [
    {"color": "green", "value": None}, {"color": "red", "value": 1}]})
stat("Token 消耗", f'sum({total(TOK.replace("}", ",token_type=\"total\"}"))})', "short", 13, 5, decimals=1)
stat("平均响应时间", f'sum({total(DUR + "_sum" + DUR_F)}) / sum({total(DUR + "_count" + DUR_F)})', "s", 18, 6, decimals=1,
     desc="单次模型请求的平均耗时（流式请求从发出到最后一个片段）")

# —— 趋势 ——
series("调用次数趋势（每分钟）", [
    (f'sum by (status) ({total(REQ.replace("}", ",status!=\"started\"}"), "1m")})', "{{status}}")],
       "short", 0, 4, 12, 8, stack=True, bars=True)
series("Token 消耗趋势（每分钟）", [
    (f'sum by (token_type) ({total(TOK.replace("}", ",token_type!=\"total\"}"), "1m")})', "{{token_type}}")],
       "short", 12, 4, 12, 8, stack=True, bars=True, desc="input 为输入（含历史上下文），output 为模型输出")
series("响应时间百分位（5 分钟窗口）", [
    (f'histogram_quantile({q}, sum by (le) ({total(DUR + "_bucket" + DUR_F, "5m")}))', f"P{int(q * 100)}")
    for q in (0.5, 0.9, 0.99)], "s", 0, 12, 12, 8)
series("错误次数（每分钟，按异常类型）", [
    (f'sum by (error_type) ({total(ERR, "1m")})', "{{error_type}}")], "short", 12, 12, 12, 8, bars=True)

# —— 排行 ——
top("应用 Token 消耗 Top 10", f'topk(10, sum by (app_id) ({total(TOK.replace("}", ",token_type=\"total\"}"))}))',
    "{{app_id}}", "short", 0, 20, 12, 9, "app_id 为 unknown 的是创建应用时的路由选择等不属于具体对话的调用")
top("应用调用次数 Top 10", f'topk(10, sum by (app_id) ({total(REQ.replace("}", ",status=\"started\"}"))}))',
    "{{app_id}}", "short", 12, 20, 12, 9)
top("用户 Token 消耗 Top 10", f'topk(10, sum by (user_id) ({total(TOK.replace("}", ",token_type=\"total\"}"))}))',
    "{{user_id}}", "short", 0, 29, 12, 7)
top("模型 Token 消耗", f'sum by (model_name) ({total(TOK.replace("}", ",token_type=\"total\"}"))})',
    "{{model_name}}", "short", 12, 29, 12, 7)

dashboard = {
    "uid": "nocode-ai",
    "title": "NoCode · AI 调用监控",
    "description": "大模型调用次数、成功率、Token 消耗、响应时间与排行（教程第 13 节）",
    "tags": ["nocode", "ai"],
    "timezone": "browser",
    "schemaVersion": 41,
    "version": 1,
    "editable": False,
    "refresh": "30s",
    "time": {"from": "now-24h", "to": "now"},
    "templating": {"list": [{
        "type": "query", "name": "model", "label": "模型", "datasource": DS,
        "query": {"query": "label_values(ai_model_requests_total, model_name)", "refId": "model"},
        "definition": "label_values(ai_model_requests_total, model_name)",
        "includeAll": True, "multi": True, "allValue": ".*", "refresh": 2,
        "current": {"text": "All", "value": "$__all"},
    }]},
    "panels": panels,
}
json.dump(dashboard, open(sys.argv[1], "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print(f"{len(panels)} panels -> {sys.argv[1]}")
