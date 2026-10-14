"""Dựng view Kibana cho index analysis-requests (Sprint 3 T9).

MVP 7.1 T9: "Ghi log và kết quả phân tích vào ES local; Kibana view cho request, latency, fallback, lỗi".
Ứng dụng (profile web) ghi mỗi request một bản ghi vào index `analysis-requests`. Script này tạo trong Kibana:

- data view `analysis-requests` (trường thời gian `@timestamp`);
- 4 saved search trong Discover: tất cả request, fallback, lỗi, chậm (> 10 s).

Chạy lại bao nhiêu lần cũng được: mọi đối tượng có id cố định và được ghi đè.

    python scripts/kibana/setup_views.py                      # Kibana ở http://localhost:5601
    python scripts/kibana/setup_views.py --kibana http://host:5601

Chỉ dùng thư viện chuẩn của Python.
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.request

DATA_VIEW_ID = 'analysis-requests'
COLUMNS = ['result_status', 'verdict', 'issue_category', 'confidence', 'analysis_source', 'fallback_reason',
           'latency_ms', 'ai_ms', 'call_id', 'request_id']

SEARCHES = [
    ('analysis-requests-all', 'Requests — tất cả', ''),
    ('analysis-requests-fallback', 'Requests — fallback (report dựng từ rule)', 'degraded : true'),
    ('analysis-requests-errors', 'Requests — lỗi', 'result_status : "ERROR"'),
    ('analysis-requests-slow', 'Requests — chậm (> 10 s)', 'latency_ms > 10000'),
]


def call(base, method, path, body=None):
    request = urllib.request.Request(base + path, method=method,
                                     data=None if body is None else json.dumps(body).encode('utf-8'),
                                     headers={'kbn-xsrf': 'setup_views', 'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=30) as response:
        raw = response.read()
        return json.loads(raw) if raw else None


def wait_for_kibana(base, seconds):
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            status = call(base, 'GET', '/api/status')
            if status.get('status', {}).get('overall', {}).get('level') == 'available':
                return
        except (urllib.error.URLError, ConnectionError, TimeoutError, json.JSONDecodeError):
            pass
        time.sleep(3)
    sys.exit(f'Kibana ở {base} chưa sẵn sàng sau {seconds} giây. Bật bằng: docker compose up -d')


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--kibana', default='http://localhost:5601')
    parser.add_argument('--wait', type=int, default=180, help='số giây chờ Kibana sẵn sàng')
    args = parser.parse_args()
    base = args.kibana.rstrip('/')

    wait_for_kibana(base, args.wait)

    call(base, 'POST', '/api/data_views/data_view', {
        'data_view': {'id': DATA_VIEW_ID, 'title': 'analysis-requests', 'name': 'Call analysis — requests',
                      'timeFieldName': '@timestamp'},
        'override': True,
    })
    print(f'data view: {DATA_VIEW_ID}')

    for search_id, title, query in SEARCHES:
        call(base, 'POST', f'/api/saved_objects/search/{search_id}?overwrite=true', {
            'attributes': {
                'title': title,
                'columns': COLUMNS,
                'sort': [['@timestamp', 'desc']],
                # Discover mặc định chỉ xem 15 phút gần nhất: mở saved search sau một lúc là trống trơn
                'timeRestore': True,
                'timeRange': {'from': 'now-7d', 'to': 'now'},
                'kibanaSavedObjectMeta': {'searchSourceJSON': json.dumps({
                    'query': {'query': query, 'language': 'kuery'},
                    'filter': [],
                    'indexRefName': 'kibanaSavedObjectMeta.searchSourceJSON.index',
                })},
            },
            'references': [{'name': 'kibanaSavedObjectMeta.searchSourceJSON.index', 'type': 'index-pattern',
                            'id': DATA_VIEW_ID}],
        })
        print(f'saved search: {title}')

    print(f'Mở {base}/app/discover và chọn "Open" để xem các saved search.')


if __name__ == '__main__':
    main()
