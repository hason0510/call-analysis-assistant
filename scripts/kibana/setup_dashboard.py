"""Dựng dashboard Kibana cho kết quả phân tích (Sprint 3 T11).

MVP 7.1 T11: "Kibana dashboard trên kết quả phân tích: phân bố verdict, UNKNOWN rate, fallback rate, latency".
Dữ liệu là index `analysis-requests` mà ứng dụng (profile web) ghi mỗi request một bản ghi (T9) — chỉ mã, số, cờ.

Dashboard "Kết quả phân tích cuộc gọi" gồm:
- số report, tỉ lệ UNKNOWN, tỉ lệ fallback (report dựng từ rule), độ trễ P50 / P95;
- phân bố kết luận, loại lỗi, độ tin cậy; lý do fallback;
- độ trễ P50 / P95 theo thời gian.

Tỉ lệ tính trên request ra report (result_status REPORT) — câu ngoài phạm vi và request hỏng không có kết luận.

Cần data view `analysis-requests` của T9:  python scripts/kibana/setup_views.py
Chạy lại bao nhiêu lần cũng được: mọi đối tượng có id cố định và được ghi đè.

    python scripts/kibana/setup_dashboard.py                      # Kibana ở http://localhost:5601
    python scripts/kibana/setup_dashboard.py --kibana http://host:5601

Chỉ dùng thư viện chuẩn của Python.
"""

import argparse
import json
import sys
import urllib.error

from setup_views import DATA_VIEW_ID, call, wait_for_kibana

DASHBOARD_ID = 'analysis-results-dashboard'
REPORTS = 'result_status : "REPORT"'


def count(label, kql=None):
    column = {'label': label, 'customLabel': True, 'dataType': 'number', 'operationType': 'count',
              'isBucketed': False, 'scale': 'ratio', 'sourceField': '___records___', 'params': {'emptyAsNull': False}}
    if kql:
        column['filter'] = {'query': kql, 'language': 'kuery'}
    return column


def percentile(label, field, value):
    return {'label': label, 'customLabel': True, 'dataType': 'number', 'operationType': 'percentile',
            'isBucketed': False, 'scale': 'ratio', 'sourceField': field, 'params': {'percentile': value}}


def terms(label, field, order_by, size=10):
    return {'label': label, 'customLabel': True, 'dataType': 'string', 'operationType': 'terms', 'isBucketed': True,
            'scale': 'ordinal', 'sourceField': field,
            'params': {'size': size, 'orderBy': {'type': 'column', 'columnId': order_by}, 'orderDirection': 'desc',
                       'otherBucket': True, 'missingBucket': False}}


def ratio_columns(column_id, label, numerator_kql, denominator_kql):
    """Cột formula `count(kql=…) / count(kql=…)` theo đúng dạng Lens lưu: cột formula + 2 cột count + 1 cột math."""
    formula = f"count(kql='{numerator_kql}') / count(kql='{denominator_kql}')"
    a, b, m = f'{column_id}X0', f'{column_id}X1', f'{column_id}X2'
    part = f'Part of {label}'
    return {
        column_id: {'label': label, 'customLabel': True, 'dataType': 'number', 'operationType': 'formula',
                    'isBucketed': False, 'scale': 'ratio', 'references': [m],
                    'params': {'formula': formula, 'isFormulaBroken': False,
                               'format': {'id': 'percent', 'params': {'decimals': 1}}}},
        a: {**count(part, numerator_kql)},
        b: {**count(part, denominator_kql)},
        m: {'label': part, 'customLabel': True, 'dataType': 'number', 'operationType': 'math', 'isBucketed': False,
            'scale': 'ratio', 'references': [a, b],
            'params': {'tinymathAst': {'type': 'function', 'name': 'divide', 'args': [a, b],
                                       'location': {'min': 0, 'max': len(formula)}, 'text': formula}}},
    }


def lens(object_id, title, vis_type, columns, column_order, visualization, query=''):
    layer = 'layer1'
    return {
        'type': 'lens', 'id': object_id,
        'attributes': {
            'title': title, 'visualizationType': vis_type,
            'state': {
                'datasourceStates': {'formBased': {'layers': {layer: {
                    'columns': columns, 'columnOrder': column_order, 'incompleteColumns': {}}}}},
                'visualization': visualization,
                'query': {'query': query, 'language': 'kuery'}, 'filters': [],
                'adHocDataViews': {}, 'internalReferences': [],
            },
        },
        'references': [{'type': 'index-pattern', 'id': DATA_VIEW_ID, 'name': f'indexpattern-datasource-layer-{layer}'}],
    }


def metric(object_id, title, columns, column_order, accessor, query=''):
    return lens(object_id, title, 'lnsMetric', columns, column_order,
                {'layerId': 'layer1', 'layerType': 'data', 'metricAccessor': accessor}, query)


def bar(object_id, title, field, label, query=REPORTS):
    return lens(object_id, title, 'lnsXY', {'g': terms(label, field, 'm'), 'm': count('Số request')}, ['g', 'm'],
                {'preferredSeriesType': 'bar_horizontal', 'legend': {'isVisible': False, 'position': 'right'},
                 'valueLabels': 'show',
                 'layers': [{'layerId': 'layer1', 'layerType': 'data', 'seriesType': 'bar_horizontal',
                             'xAccessor': 'g', 'accessors': ['m']}]}, query)


def visualizations():
    return [
        metric('ar-reports', 'Số report', {'m': count('Số report', REPORTS)}, ['m'], 'm'),
        metric('ar-unknown-rate', 'Tỉ lệ UNKNOWN',
               ratio_columns('r', 'Tỉ lệ UNKNOWN', 'verdict : "UNKNOWN"', REPORTS),
               ['r', 'rX0', 'rX1', 'rX2'], 'r'),
        metric('ar-fallback-rate', 'Tỉ lệ fallback (report dựng từ rule)',
               ratio_columns('r', 'Tỉ lệ fallback', f'degraded : true and {REPORTS}', REPORTS),
               ['r', 'rX0', 'rX1', 'rX2'], 'r'),
        metric('ar-latency-p50', 'Độ trễ P50 (ms)', {'m': percentile('Độ trễ P50 (ms)', 'latency_ms', 50)}, ['m'],
               'm', REPORTS),
        metric('ar-latency-p95', 'Độ trễ P95 (ms)', {'m': percentile('Độ trễ P95 (ms)', 'latency_ms', 95)}, ['m'],
               'm', REPORTS),
        lens('ar-verdict', 'Phân bố kết luận', 'lnsPie', {'g': terms('Kết luận', 'verdict', 'm', 5),
                                                          'm': count('Số report')}, ['g', 'm'],
             {'shape': 'donut', 'layers': [{'layerId': 'layer1', 'layerType': 'data', 'primaryGroups': ['g'],
                                            'metrics': ['m'], 'numberDisplay': 'value', 'categoryDisplay': 'default',
                                            'legendDisplay': 'show'}]}, REPORTS),
        bar('ar-category', 'Loại lỗi (FAIL và SUCCESS có cờ)', 'issue_category', 'Loại lỗi',
            f'{REPORTS} and issue_category : *'),
        bar('ar-confidence', 'Độ tin cậy', 'confidence', 'Độ tin cậy'),
        bar('ar-fallback-reason', 'Lý do fallback', 'fallback_reason', 'Lý do', f'{REPORTS} and degraded : true'),
        lens('ar-latency-time', 'Độ trễ theo thời gian (ms)', 'lnsXY',
             {'t': {'label': 'Thời điểm', 'dataType': 'date', 'operationType': 'date_histogram', 'isBucketed': True,
                    'scale': 'interval', 'sourceField': '@timestamp',
                    'params': {'interval': 'auto', 'includeEmptyRows': False}},
              'p50': percentile('P50', 'latency_ms', 50), 'p95': percentile('P95', 'latency_ms', 95)},
             ['t', 'p50', 'p95'],
             {'preferredSeriesType': 'line', 'legend': {'isVisible': True, 'position': 'bottom'},
              'yTitle': 'Độ trễ (ms)',
              'layers': [{'layerId': 'layer1', 'layerType': 'data', 'seriesType': 'line', 'xAccessor': 't',
                          'accessors': ['p50', 'p95']}]}, REPORTS),
    ]


# (id, x, y, w, h) trên lưới 48 cột
LAYOUT = [
    ('ar-reports', 0, 0, 12, 7), ('ar-unknown-rate', 12, 0, 12, 7), ('ar-fallback-rate', 24, 0, 12, 7),
    ('ar-latency-p50', 36, 0, 6, 7), ('ar-latency-p95', 42, 0, 6, 7),
    ('ar-verdict', 0, 7, 16, 14), ('ar-category', 16, 7, 16, 14), ('ar-confidence', 32, 7, 16, 14),
    ('ar-latency-time', 0, 21, 32, 14), ('ar-fallback-reason', 32, 21, 16, 14),
]


def dashboard():
    panels, references = [], []
    for i, (object_id, x, y, w, h) in enumerate(LAYOUT):
        name = f'panel_{i}'
        panels.append({'type': 'lens', 'panelIndex': str(i), 'panelRefName': name,
                       'gridData': {'x': x, 'y': y, 'w': w, 'h': h, 'i': str(i)}, 'embeddableConfig': {}})
        references.append({'type': 'lens', 'id': object_id, 'name': name})
    return {
        'type': 'dashboard', 'id': DASHBOARD_ID,
        'attributes': {
            'title': 'Kết quả phân tích cuộc gọi',
            'description': 'Sprint 3 T11 — index analysis-requests (T9). Tỉ lệ tính trên request ra report.',
            'panelsJSON': json.dumps(panels), 'optionsJSON': json.dumps({'useMargins': True, 'hidePanelTitles': False}),
            'timeRestore': True, 'timeFrom': 'now-7d', 'timeTo': 'now', 'refreshInterval': {'pause': True, 'value': 0},
            'kibanaSavedObjectMeta': {'searchSourceJSON': json.dumps({'query': {'query': '', 'language': 'kuery'},
                                                                      'filter': []})},
        },
        'references': references,
    }


def main():
    sys.stdout.reconfigure(encoding='utf-8')   # console Windows mặc định cp1252, không in được tiếng Việt
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--kibana', default='http://localhost:5601')
    args = parser.parse_args()
    base = args.kibana.rstrip('/')

    wait_for_kibana(base, 120)
    try:
        call(base, 'GET', f'/api/data_views/data_view/{DATA_VIEW_ID}')
    except urllib.error.HTTPError:
        sys.exit(f'Chưa có data view "{DATA_VIEW_ID}". Chạy trước: python scripts/kibana/setup_views.py')

    objects = visualizations() + [dashboard()]
    result = call(base, 'POST', '/api/saved_objects/_bulk_create?overwrite=true', objects)
    failed = [o for o in result.get('saved_objects', []) if 'error' in o]
    if failed:
        sys.exit('Lỗi khi tạo: ' + json.dumps(failed, ensure_ascii=False))
    print(f'Đã tạo {len(objects) - 1} biểu đồ và dashboard "Kết quả phân tích cuộc gọi".')
    print(f'Mở: {base}/app/dashboards#/view/{DASHBOARD_ID}')


if __name__ == '__main__':
    main()
