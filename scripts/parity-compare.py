#!/usr/bin/env python3
import json, sys, pathlib


def load(p):
    return json.loads(pathlib.Path(p).read_text())


def fail(msg):
    print(msg, file=sys.stderr)
    sys.exit(1)

mode = sys.argv[1]
case_dir = pathlib.Path(sys.argv[2])
node_json = load(sys.argv[3])
java_json = load(sys.argv[4])

if mode == 'runtime':
    expected = load(case_dir / 'expected.json')
    if not node_json.get('compileOk', False):
        fail(f'Node runtime case unexpectedly failed to compile: {case_dir}\n{json.dumps(node_json, indent=2, ensure_ascii=False)}')
    if not java_json.get('compileOk', False):
        fail(f'Java runtime case unexpectedly failed to compile: {case_dir}\n{json.dumps(java_json, indent=2, ensure_ascii=False)}')
    if node_json != java_json:
        fail(f'Node/Java runtime mismatch for {case_dir}\nNODE={json.dumps(node_json, indent=2, ensure_ascii=False)}\nJAVA={json.dumps(java_json, indent=2, ensure_ascii=False)}')
    actual = {
        'status': node_json['status'],
        'control': node_json['control'],
        'issues': node_json['issues'],
    }
    if actual != expected:
        fail(f'Runtime result does not match expected for {case_dir}\nEXPECTED={json.dumps(expected, indent=2, ensure_ascii=False)}\nACTUAL={json.dumps(actual, indent=2, ensure_ascii=False)}')
    print(f'OK runtime {case_dir.name}')
elif mode == 'compile-fail':
    expected = load(case_dir / 'assert.json')
    if node_json.get('compileOk', True):
        fail(f'Node compile-fail case unexpectedly compiled: {case_dir}\n{json.dumps(node_json, indent=2, ensure_ascii=False)}')
    if java_json.get('compileOk', True):
        fail(f'Java compile-fail case unexpectedly compiled: {case_dir}\n{json.dumps(java_json, indent=2, ensure_ascii=False)}')
    for runtime_name, result in [('Node', node_json), ('Java', java_json)]:
        text = '\n'.join(result.get('errors', []))
        for needle in expected.get('errorSubstrings', []):
            if needle not in text:
                fail(f'{runtime_name} compile-fail result for {case_dir} does not contain required substring {needle!r}\n{text}')
    print(f'OK compile-fail {case_dir.name}')
else:
    fail(f'Unknown mode: {mode}')
