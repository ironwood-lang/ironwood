#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Measure real seed compile/link pipelines and separate LLVM/Clang children."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import sys
import time

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def tree(root_pid):
    process = subprocess.run(['/bin/ps', '-axo', 'pid=,ppid=,rss='], capture_output=True, check=True)
    rows = [tuple(map(int, line.split())) for line in process.stdout.splitlines()]
    selected = {root_pid}
    while True:
        expanded = selected | {pid for pid, parent, rss in rows if parent in selected}
        if expanded == selected:
            break
        selected = expanded
    return [{'pid': pid, 'parent': parent, 'rss_bytes': rss * 1024}
            for pid, parent, rss in rows if pid in selected]


def timed(command, destination, phase, profiled, env):
    began = time.monotonic(); previous = began; samples = []; gap = 0
    with (destination / (phase + '.stdout.txt')).open('wb') as stdout, (destination / (phase + '.stderr.txt')).open('wb') as stderr:
        process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=stdout, stderr=stderr, start_new_session=True)
        if profiled:
            while process.poll() is None:
                now = time.monotonic(); gap = max(gap, now - previous); previous = now
                samples.append({'elapsed_seconds': now - began, 'processes': tree(process.pid)})
                time.sleep(.01)
        returncode = process.wait()
    wall = time.monotonic() - began
    (destination / (phase + '.tree-samples.json')).write_text(json.dumps(samples, indent=2) + '\n')
    raw = (destination / (phase + '.time.txt')).read_text()
    rss = re.search(r'^\s*(\d+)\s+maximum resident set size\s*$', raw, re.MULTILINE)
    timing = re.search(r'(\d+(?:\.\d+)?) real\s+(\d+(?:\.\d+)?) user\s+(\d+(?:\.\d+)?) sys', raw)
    return {'argv': command, 'returncode': returncode, 'fresh_wall_seconds': wall,
            'process_rusage_rss_bytes': int(rss.group(1)) if rss else None,
            'real_user_sys_seconds': list(map(float, timing.groups())) if timing else None,
            'samples': len(samples), 'requested_sample_period_seconds': .01,
            'maximum_sample_gap_nanos': int(gap * 1e9),
            'sampled_tree_rss_high_bytes': max((sum(row['rss_bytes'] for row in sample['processes']) for sample in samples), default=0)}


def qualify(out):
    report = json.loads((out / 'measurement.json').read_text())
    identity = json.loads((out / 'identity.json').read_text())
    assert sha(out / 'identity.json') == report['identity_sha256']
    assert report['repeat'] >= 2 and report['seed_sha256'] == identity['seed_jar_sha256']
    for name, digest in report['tool_sha256'].items():
        assert sha(out / 'tooling' / name) == digest, name
    for name, digest in report['source_sha256'].items():
        assert sha(out / 'sources' / (name + '.iron')) == digest, name
    expected = {(name, profiled, repeat) for name in report['selected']
                for profiled in (False, True) for repeat in range(report['repeat'])}
    actual = set(); artifacts = {}; groups = {}; child_groups = {}
    for run in report['runs']:
        key = (run['workload'], run['profiled'], run['repeat'])
        assert key in expected and key not in actual
        actual.add(key)
        path = out / run['label']
        for name, digest in run['artifact_sha256'].items():
            assert sha(path / name) == digest, (run['label'], name)
        assert run['returncode'] == run['executable_returncode'] == 0
        assert run['compile']['returncode'] == run['link']['returncode'] == 0
        assert run['compile']['process_rusage_rss_bytes'] > 0 and run['link']['process_rusage_rss_bytes'] > 0
        assert run['process_rusage_rss_bytes'] > 0 and run['fresh_pipeline_wall_seconds'] > 0
        digest = run['artifact_sha256']['output.ll']
        assert artifacts.setdefault(run['workload'], digest) == digest, 'LLVM changed'
        groups.setdefault(key[:2], []).append(run)
        if run['profiled']:
            assert run['samples'] > 0 and run['sampled_tree_rss_high_bytes'] > 0
            assert {child.name for child in (path / 'children').iterdir()} == set(map(str, range(11)))
            children = []
            for index in range(11):
                child = json.loads((path / 'children' / str(index) / 'measurement.json').read_text())
                assert child['returncode'] == 0 and child['rss_bytes'] > 0
                children.append(child)
            assert [child['tool'] for child in children] == ['llvm-config', 'clang', 'llvm-as', 'opt', 'llvm-as', 'llc', 'clang', 'clang', 'clang', 'clang', 'clang']
            assert '-passes=default<O3>' in children[3]['argv']
            assert '-inline-threshold=1000' in children[3]['argv'] and '-enable-partial-inlining' in children[3]['argv']
            assert '-O=3' in children[5]['argv']
            runtime = [Path(child['argv'][child['argv'].index('-c') + 1]).name for child in children[6:10]]
            assert runtime == ['ironwood_runtime.c', 'ironwood_case.c', 'ironwood_tcp.c', 'ironwood_host.c']
            assert all('-O3' in child['argv'] and '-fPIC' in child['argv'] for child in children[6:10])
            assert '--driver-mode=g++' in children[10]['argv'] and '-Wl,-dead_strip' in children[10]['argv']
            for index, child in enumerate(children):
                child_groups.setdefault(index, []).append(child)
        else:
            assert run['samples'] == run['sampled_tree_rss_high_bytes'] == run['maximum_sample_gap_nanos'] == 0
    assert actual == expected, 'missing configuration or repeat'
    comparisons = []
    for name in report['selected']:
        plain = groups[(name, False)]; profiled = groups[(name, True)]
        comparisons.append({'workload': name,
                            'profiled_over_plain_wall_medians': statistics.median(run['fresh_pipeline_wall_seconds'] for run in profiled) / statistics.median(run['fresh_pipeline_wall_seconds'] for run in plain),
                            'maximum_plain_wall_seconds': max(run['fresh_pipeline_wall_seconds'] for run in plain),
                            'maximum_profiled_wall_seconds': max(run['fresh_pipeline_wall_seconds'] for run in profiled),
                            'maximum_sampled_tree_rss_bytes': max(run['sampled_tree_rss_high_bytes'] for run in profiled)})
    result = {'qualification_passed': True, 'baseline_label': report['baseline_label'],
              'seed_sha256': report['seed_sha256'], 'runs': len(actual), 'overhead': comparisons,
              'maximum_process_rusage_rss_bytes': max(run['process_rusage_rss_bytes'] for run in report['runs']),
              'maximum_sample_gap_nanos': max(run['maximum_sample_gap_nanos'] for run in report['runs']),
              'children_by_stage': [{'index': index, 'tool': children[0]['tool'],
                                      'maximum_fresh_child_wall_seconds': max(child['fresh_child_wall_seconds'] for child in children),
                                      'maximum_rss_bytes': max(child['rss_bytes'] for child in children)} for index, children in child_groups.items()],
              'limits': ['whole pipeline includes JVM compilation, discovery, wrappers and output',
                         'pipeline rusage can include descendants; isolated JVM measurements remain separate',
                         'sampled maxima lower-bound peak summed per-process RSS; shared pages can be counted repeatedly, so this is not a unique physical-memory bound',
                         'profiled child time includes time launch/output capture, not wrapper setup or hashing',
                         'Clang link cost includes Apple linker descendants; not an isolated linker process',
                         'no native compiler pilot or numerical budget evaluation']}
    (out / 'qualification.json').write_text(json.dumps(result, indent=2) + '\n')
    print('PASS:', len(actual), 'native pipelines; exact LLVM, child stages and executable outcomes')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--identity', type=Path, default=ROOT / 'docs/self-hosting/m0/ordered/qualified-identity.json')
    parser.add_argument('--baseline-label', default='J0-D247')
    parser.add_argument('--repeat', type=int, default=2)
    parser.add_argument('--qualify-only', action='store_true')
    args = parser.parse_args(); out = args.output.resolve()
    if args.qualify_only:
        qualify(out); return
    if out.exists() or args.repeat < 2:
        raise ValueError('new output and at least two repeats required')
    raw_identity = args.identity.read_bytes(); identity = json.loads(raw_identity)
    if (args.baseline_label == 'original-J0') != (identity['revision'] == '6bde84df320e9dbc32977a2b665d214ac09bc2d4'):
        raise ValueError('original/ordered label mismatch')
    install = Path(identity['launcher']).parent.parent; jar = install / 'lib/ironwoodc.jar'
    assert sha(jar) == identity['seed_jar_sha256']
    assert sha(install / 'lib/ironwood-stdlib.ironjar') == identity['stdlib_archive_sha256']
    llvm = Path(identity['llvm'])
    for name, digest in identity['llvm_sha256'].items():
        assert sha(llvm / 'bin' / name) == digest, name
    for path, digest in identity['input_sha256'].items():
        if path.startswith('runtime/'):
            assert sha(install / path) == digest, path
    out.mkdir(parents=True); (out / 'identity.json').write_bytes(raw_identity)
    tooling = out / 'tooling'; tooling.mkdir(); (tooling / 'bin').mkdir()
    tool_names = ('profile-native-tool.py', 'measure-native.py', 'measure-resources.py')
    for name in tool_names:
        (tooling / name).write_bytes((ROOT / 'scripts/self-hosting' / name).read_bytes())
    (tooling / 'profile-native-tool.py').chmod(0o755)
    for name in identity['llvm_sha256']:
        # A hard link retains argv[0]'s tool name without changing the script bytes.
        os.link(tooling / 'profile-native-tool.py', tooling / 'bin' / name)
    spec = importlib.util.spec_from_file_location('resource_corpus', tooling / 'measure-resources.py')
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    module.ROOT = ROOT
    cases = module.corpus(identity['revision'])
    selected = ['BranchJoin', 'Volume256', 'Control128', 'Ownership128']
    (out / 'sources').mkdir()
    for name in selected:
        data = cases[name]['data']; data.decode('utf-8', errors='strict')
        (out / 'sources' / (name + '.iron')).write_bytes(data)
    env = dict(os.environ)
    cleared = ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')
    for name in cleared:
        env.pop(name, None)
    env.update(IRONWOOD_STDLIB_HOME=str(install), IRONWOOD_RUNTIME_HOME=str(install),
               IRONWOOD_M0_REAL_LLVM=str(llvm), SDKROOT=identity['sdk']['path'])
    report = {'schema': 1, 'baseline_label': args.baseline_label, 'revision': identity['revision'],
              'seed_sha256': identity['seed_jar_sha256'], 'identity_sha256': hashlib.sha256(raw_identity).hexdigest(),
              'profile': identity['profile'], 'hardware': identity['hardware'], 'platform': identity['platform'],
              'cleared_environment': list(cleared), 'runtime_home': str(install), 'library_home': str(install),
              'sdk': identity['sdk'], 'real_llvm': str(llvm), 'wrapper_python': sys.executable,
              'tool_sha256': {name: sha(tooling / name) for name in tool_names},
              'source_sha256': {name: sha(out / 'sources' / (name + '.iron')) for name in selected},
              'selected': selected, 'repeat': args.repeat, 'runs': []}
    def checkpoint():
        (out / 'measurement.json').write_text(json.dumps(report, indent=2) + '\n')
    checkpoint()
    for name in selected:
        for profiled in (False, True):
            for repeat in range(args.repeat):
                label = f'{name}-profile{int(profiled)}-r{repeat}'
                destination = out / label; destination.mkdir(); (destination / 'children').mkdir()
                env['IRONWOOD_M0_NATIVE_PROFILE'] = str(destination / 'children')
                prefix = [identity['jdk'] + '/bin/java', *identity['profile'], '-jar', str(jar)]
                compile_command = ['/usr/bin/time', '-l', '-o', str(destination / 'compile.time.txt'),
                                   *prefix, str(out / 'sources' / (name + '.iron')),
                                   '-d', str(destination / 'classes'), '--unfreed=warn']
                command = ['/usr/bin/time', '-l', '-o', str(destination / 'link.time.txt'),
                           *prefix, '--link', '-cp', str(destination / 'classes'),
                           '--main-class', name, '-o', str(destination / 'program'),
                           '--emit-llvm', str(destination / 'output.ll'), '--unfreed=warn', '-O3',
                           '--llvm-home', str(tooling if profiled else llvm)]
                began = time.monotonic()
                compiled = timed(compile_command, destination, 'compile', profiled, env)
                if compiled['returncode']:
                    raise ValueError('source compile failed; raw output retained')
                linked = timed(command, destination, 'link', profiled, env)
                wall = time.monotonic() - began; returncode = linked['returncode']
                run = {'label': label, 'workload': name, 'profiled': profiled, 'repeat': repeat,
                       'compile': compiled, 'link': linked,
                       'returncode': returncode, 'fresh_pipeline_wall_seconds': wall,
                       'process_rusage_rss_bytes': max(compiled['process_rusage_rss_bytes'], linked['process_rusage_rss_bytes']),
                       'samples': compiled['samples'] + linked['samples'],
                       'maximum_sample_gap_nanos': max(compiled['maximum_sample_gap_nanos'], linked['maximum_sample_gap_nanos']),
                       'sampled_tree_rss_high_bytes': max(compiled['sampled_tree_rss_high_bytes'], linked['sampled_tree_rss_high_bytes'])}
                if not returncode:
                    executable = subprocess.run([str(destination / 'program')], cwd=ROOT, env=env, capture_output=True)
                    (destination / 'run.stdout.txt').write_bytes(executable.stdout)
                    (destination / 'run.stderr.txt').write_bytes(executable.stderr)
                    run.update(executable_argv=[str(destination / 'program')], executable_returncode=executable.returncode)
                run['artifact_sha256'] = {path.relative_to(destination).as_posix(): sha(path) for path in sorted(destination.rglob('*')) if path.is_file()}
                report['runs'].append(run); checkpoint()
                print(label, 'exit=' + str(returncode), 'wall=' + format(wall, '.3f'), flush=True)
                if returncode or run.get('executable_returncode') != 0:
                    raise ValueError('pipeline/executable failed; output retained')
    qualify(out)


if __name__ == '__main__':
    main()
