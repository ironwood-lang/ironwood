#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Explicitly prepare or offline-verify the isolated GC1 engine SDK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
PINS = ROOT / 'packaging/bdwgc.json'
ADAPTER = ('runtime/include/ironwood_gc_engine.h', 'runtime/src/ironwood_gc_engine.c',
           'compiler/src/main/cpp/GcNonmovingStatepoints.cpp')
RUNTIME_INPUTS = ('runtime/src/ironwood_runtime.c', 'runtime/include/ironwood_runtime.h',
                  'runtime/src/ironwood_gc_roots.c', 'runtime/include/ironwood_gc_roots.h')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')


def output(command, **kwargs):
    return subprocess.check_output(list(map(str, command)), text=True, **kwargs).strip()


def host_target():
    return {('Darwin', 'arm64'): 'macos-arm64', ('Linux', 'aarch64'): 'linux-arm64',
            ('Linux', 'arm64'): 'linux-arm64', ('Linux', 'x86_64'): 'linux-x86_64'}.get(
                (platform.system(), platform.machine()))


def context(llvm, target):
    pins = json.loads(PINS.read_text())
    if target != host_target():
        raise ValueError('GC SDK requires a matching native host target: ' + target)
    if output([llvm / 'bin/llvm-config', '--version']) != pins['llvm.version']:
        raise ValueError('GC SDK requires pinned LLVM ' + pins['llvm.version'])
    if not re.search(r'\bclang version ' + re.escape(pins['llvm.version']) + r'\b',
                     output([llvm / 'bin/clang', '--version'])):
        raise ValueError('GC SDK clang/toolchain mismatch')
    flags = ['--target=' + pins['targets'][target]['triple']]
    if target == 'macos-arm64':
        sdk = Path(os.environ.get('SDKROOT') or output(['xcrun', '--show-sdk-path'])).resolve()
        flags += ['-isysroot', str(sdk)]
        system = {'sdk.version': json.loads((sdk / 'SDKSettings.json').read_text())['Version'],
                  'deployment': '14.0', 'sdk.settings.sha256': digest(sdk / 'SDKSettings.json')}
    else:
        package = 'sysroot_linux-aarch64' if target == 'linux-arm64' else 'sysroot_linux-64'
        records = list((llvm / 'conda-meta').glob(package + '-*.json'))
        if len(records) != 1 or json.loads(records[0].read_text())['version'] != '2.17':
            raise ValueError('Linux GC preparation requires the IDK glibc 2.17 sysroot')
        arch = 'aarch64' if target == 'linux-arm64' else 'x86_64'
        sdk = llvm / (arch + '-conda-linux-gnu/sysroot')
        if not sdk.is_dir():
            raise ValueError('missing IDK sysroot')
        flags += ['--sysroot=' + str(sdk)]
        system = {'sysroot.package': package, 'sysroot.version': '2.17',
                  'sysroot.record.sha256': digest(records[0])}
    ir = output([llvm / 'bin/clang', *flags, '-x', 'c', '-S', '-emit-llvm', '-o', '-', '-'], input='')
    triple = re.search(r'target triple = "([^"]+)"', ir).group(1)
    layout = re.search(r'target datalayout = "([^"]+)"', ir).group(1)
    macros = output([llvm / 'bin/clang', *flags, '-x', 'c', '-dM', '-E', '-'], input='')
    width = int(re.search(r'#define __SIZEOF_POINTER__ (\d+)', macros).group(1)) * 8
    if width != 64 or '#define __BYTE_ORDER__ __ORDER_LITTLE_ENDIAN__' not in macros:
        raise ValueError('unqualified pointer width or byte order')
    config = {
        'adapter.sha256': {name: digest(ROOT / name) for name in (*ADAPTER, *RUNTIME_INPUTS)},
        'recipe.sha256': digest(Path(__file__)), 'pins.sha256': digest(PINS),
        'archive.sha256': pins['collector']['sha256'], 'configure': pins['profile']['configure'],
        'cflags': pins['profile']['cflags'], 'build.cflags': pins['build.cflags'],
        'adapter.abi': pins['adapter.abi'], 'memory.abi': pins['memory.abi'],
        'layout.version': pins['layout.version'], 'roots.version': pins['roots.version'],
        'runtime.owner.version': pins['runtime.owner.version'],
        'statepoint.pass.sha256': digest(ROOT / ADAPTER[2]),
        'target': target, 'target.triple': triple, 'target.data-layout': layout,
        'pointer.width': width, 'endianness': 'little', 'llvm.version': pins['llvm.version'],
        'llvm.stackmap.version': pins['llvm.stackmap.version'],
        'isolation': pins['targets'][target]['isolation'], 'static.link': pins['static.link'],
        'startup': pins['profile']['startup'], 'system': system,
        'target.cflags': ['--target=' + triple, '-isysroot', '{sdk}'] if target.startswith('macos')
                         else ['--target=' + triple, '--sysroot={sdk}'],
        'target.ldflags': [] if target.startswith('macos') else ['-fuse-ld=lld'],
        'reproducibility.cflags': ['-ffile-prefix-map={preparation}=.'],
        'adapter.cflags': ['-O3', '-fPIC', '-fvisibility=hidden', '-std=c11',
                           '-Wall', '-Wextra', '-Werror', '-Wpedantic'],
    }
    return config, flags


def audit_archive(path, llvm, target):
    """Every externally bound definition must be hidden, including non-GC names."""
    headers = output([llvm / 'bin/llvm-readobj', '--file-headers', path])
    expected = 'aarch64' if target.endswith('arm64') else 'x86_64'
    architectures = re.findall(r'^Arch: (\S+)', headers, re.MULTILINE)
    formats = re.findall(r'^Format: (.+)', headers, re.MULTILINE)
    wanted_format = 'Mach-O' if target.startswith('macos') else 'elf'
    if (not architectures or set(architectures) != {expected}
            or not formats or any(not value.startswith(wanted_format) for value in formats)):
        raise ValueError('archive object target mismatch')
    if target.startswith('macos'):
        table = output([llvm / 'bin/llvm-nm', '-m', '--defined-only', '--extern-only', path])
        definitions = [line for line in table.splitlines() if 'external ' in line]
        bad = [line for line in definitions if 'private external ' not in line]
    else:
        table = output([llvm / 'bin/llvm-readelf', '-sW', path])
        definitions = [line for line in table.splitlines()
                       if re.search(r'\b(GLOBAL|WEAK)\s+\w+\s+(?!UND\b)\S+\s+\S+', line)]
        bad = [line for line in definitions if not re.search(r'\bHIDDEN\b', line)]
    if not definitions or bad:
        raise ValueError('non-private archive definitions: ' + '\n'.join(bad[:8]))
    names = output([llvm / 'bin/llvm-nm', '--defined-only', '--extern-only', path])
    if re.search(r'\b_?(malloc|calloc|free|realloc|pthread_create)\s*$', names, re.MULTILINE):
        raise ValueError('allocator interposition or worker definition')
    # ELF readelf includes the input archive path in member headings. Exclude
    # that location from audit identity; library bytes are hashed separately.
    table = table.replace(str(path), '{archive}')
    return {'external_definitions': len(definitions), 'all_hidden': True,
            'symbol_table.sha256': hashlib.sha256(table.encode()).hexdigest()}


def image_policy(target, path):
    if target.startswith('macos'):
        path.write_text('_ironwood_gc1_probe\n')
        return ['-Wl,-exported_symbols_list,' + str(path), '-Wl,-twolevel_namespace']
    path.write_text('{ global: ironwood_gc1_probe; local: *; };\n')
    return ['-Wl,--version-script=' + str(path), '-Wl,-Bsymbolic',
            '-Wl,--exclude-libs,ALL', '-Wl,-z,defs']


def audit_image(path, llvm, target):
    if target.startswith('macos'):
        table = output([llvm / 'bin/llvm-objdump', '--macho', '--exports-trie', path])
        names = re.findall(r'^0x[0-9A-Fa-f]+\s+(\S+)', table, re.MULTILINE)
        expected = ['_ironwood_gc1_probe']
        imports = output([llvm / 'bin/llvm-nm', '--undefined-only', path])
    else:
        table = output([llvm / 'bin/llvm-nm', '--dynamic', '--defined-only', '--extern-only', path])
        names = [line.split()[-1] for line in table.splitlines() if line.strip()]
        expected = ['ironwood_gc1_probe']
        imports = output([llvm / 'bin/llvm-nm', '--dynamic', '--undefined-only', path])
    if sorted(names) != expected or re.search(r'\b_?(GC_|iw_gc_)\w+', imports):
        raise ValueError('image export/import isolation failure: ' + str(names))
    return {'exports': names, 'no_collector_imports': True,
            'export_table.sha256': hashlib.sha256(table.encode()).hexdigest()}


def verify(prefix, llvm, target):
    expected, flags = context(llvm, target)
    manifest = json.loads((prefix / 'build.json').read_text())
    actual = (prefix / 'configuration.json').read_bytes()
    identity = hashlib.sha256(canonical(expected)).hexdigest()
    if (manifest.get('format') != 1 or actual != canonical(expected)
            or manifest.get('gc.config.sha256') != identity
            or manifest.get('configuration') != expected):
        raise ValueError('GC SDK configuration/target/ABI mismatch')
    hashes = manifest['files.sha256']
    files = {str(p.relative_to(prefix)) for p in prefix.rglob('*') if p.is_file() and p.name != 'build.json'}
    required = {'lib/libgc.a', 'lib/libironwood_gc_engine.a', 'adapter.o',
                'include/gc/gc.h', 'include/gc/gc_mark.h', 'include/config.h',
                'include/ironwood_gc_configuration.h', 'configuration.json', 'configuration-audit.json', 'build.properties',
                'packaging/bdwgc.json', 'scripts/prepare-gc.py', 'licenses/BDWGC-NOTICES.txt',
                'sources/gc-8.2.12.tar.gz', *ADAPTER, *RUNTIME_INPUTS}
    if files != set(hashes) or not required <= files:
        raise ValueError('GC SDK missing or unmanifested inputs')
    for name, value in hashes.items():
        relative = Path(name)
        if (relative.is_absolute() or '..' in relative.parts
                or (prefix / relative).is_symlink()
                or digest(prefix / relative) != value):
            raise ValueError('GC SDK checksum mismatch: ' + name)
    if digest(prefix / 'sources/gc-8.2.12.tar.gz') != expected['archive.sha256']:
        raise ValueError('GC SDK source pin mismatch')
    for name in (*ADAPTER, *RUNTIME_INPUTS, 'packaging/bdwgc.json', 'scripts/prepare-gc.py'):
        if digest(prefix / name) != digest(ROOT / name):
            raise ValueError('GC SDK does not match current adapter/recipe: ' + name)
    for name in ('libgc.a', 'libironwood_gc_engine.a'):
        audit_archive(prefix / 'lib' / name, llvm, target)
    return manifest, flags


def prepare(prefix, llvm, target, cache, jobs):
    config, flags = context(llvm, target)
    pins = json.loads(PINS.read_text())
    if prefix.exists():
        raise ValueError('refusing to replace an existing GC SDK: ' + str(prefix))
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / 'gc-8.2.12.tar.gz'
    if not archive.exists():
        with urllib.request.urlopen(pins['collector']['url'], timeout=60) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != pins['collector']['sha256']:
            raise ValueError('download checksum mismatch')
        archive.write_bytes(data)
    if digest(archive) != pins['collector']['sha256']:
        raise ValueError('cached archive checksum mismatch')
    prefix.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=prefix.parent, prefix='.gc-prepare-') as directory:
        work = Path(directory)
        staged = work / 'sdk'
        build = work / 'build'
        build.mkdir()
        for name in ('lib', 'include/gc', 'sources', 'licenses', 'scripts', 'packaging'):
            (staged / name).mkdir(parents=True, exist_ok=True)
        with tarfile.open(archive) as package:
            package.extractall(work / 'source', filter='data')
        source = work / 'source/gc-8.2.12'
        shutil.copyfile(archive, staged / 'sources' / archive.name)
        # Preserve every source header notice verbatim, alongside the full,
        # unmodified source archive including GNU build-helper license exceptions.
        notices = [source / 'README.md'] + sorted(source.rglob('*.c')) + sorted(source.rglob('*.h'))
        with (staged / 'licenses/BDWGC-NOTICES.txt').open('w') as destination:
            for path in notices:
                text = path.read_text(errors='strict')
                header = re.match(r'\s*(/\*.*?\*/)', text, re.DOTALL)
                destination.write('\nFILE: ' + str(path.relative_to(source)) + '\n')
                destination.write(text if path.name == 'README.md' else (header.group(1) if header else ''))
                destination.write('\n')
        env = {key: value for key, value in os.environ.items()
               if key not in {'CC', 'CXX', 'CFLAGS', 'CPPFLAGS', 'LDFLAGS', 'LIBS', 'CONFIG_SITE'}
               and not key.startswith('GC_')}
        env.update(SOURCE_DATE_EPOCH='0', ZERO_AR_DATE='1', CONFIG_SITE='/dev/null')
        env['LDFLAGS'] = ' '.join(config['target.ldflags'])
        cflags = pins['profile']['cflags'] + pins['build.cflags'] + flags
        cflags += ['-ffile-prefix-map=' + str(work) + '=.']
        command = [str(source / 'configure'), *pins['profile']['configure'],
                   'CC=' + str(llvm / 'bin/clang'), 'AR=' + str(llvm / 'bin/llvm-ar'),
                   'RANLIB=' + str(llvm / 'bin/llvm-ranlib'), 'CFLAGS=' + ' '.join(cflags)]
        # Raw logs belong in ignored preparation storage, not the SDK manifest.
        log_path = prefix.parent / (prefix.name + '-build.log')
        with log_path.open('w') as log:
            subprocess.run(command, cwd=build, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
            subprocess.run(['make', '-j' + str(jobs)], cwd=build, env=env,
                           stdout=log, stderr=subprocess.STDOUT, check=True)
        preprocess = [llvm / 'bin/clang', *cflags, '-DHAVE_CONFIG_H',
                      '-I' + str(build / 'include'), '-I' + str(source / 'include')]
        macros = output([*preprocess, '-dM', '-E', source / 'mark_rts.c'], env=env)
        required_macros = ['EAGER_SWEEP', 'GC_DISABLE_INCREMENTAL', 'STACK_NOT_SCANNED',
                           'DONT_ADD_BYTE_AT_END', 'USE_MARK_BYTES', 'LARGE_CONFIG', 'GC_DONT_REGISTER_MAIN_STATIC_DATA',
                           'IGNORE_DYNAMIC_LOADING']
        forbidden = ['THREADS', 'GC_THREADS', 'PARALLEL_MARK', 'THREAD_LOCAL_ALLOC',
                     'DYNAMIC_LOADING', 'REDIRECT_MALLOC', 'REDIRECT_FREE', 'REDIRECT_REALLOC']
        if (any('#define ' + name + ' 1\n' not in macros for name in required_macros)
                or '#define EXTRA_BYTES 0\n' not in macros
                or any(re.search(r'^#define ' + name + r'\b', macros, re.MULTILINE) for name in forbidden)):
            raise ValueError('effective collector configuration differs from the audited profile')
        preprocessed = output([*preprocess, '-E', '-P', source / 'mark_rts.c'], env=env)
        root_body = re.search(r'\bvoid GC_push_roots\([^{};]*\)\s*\{', preprocessed)
        if root_body is None or re.search(r'\bGC_push_regs_and_stack\s*\(', preprocessed[root_body.end():]):
            raise ValueError('ambient stack/register root code is active')
        marking = output([*preprocess, '-E', '-P', source / 'mark.c'], env=env)
        if any(name in marking for name in ('GC_push_marked1(', 'GC_push_marked2(', 'GC_push_marked4(')):
            raise ValueError('conservative small-cell overflow recovery is active')
        audit = {'exact_descriptor_recovery': True,
                 'preprocessed_mark.sha256': hashlib.sha256(marking.encode()).hexdigest(),
                 'required_macros': required_macros, 'absent_macros': forbidden, 'EXTRA_BYTES': 0,
                 'ambient_stack_register_root_call': False,
                 'preprocessed_mark_rts.sha256': hashlib.sha256(preprocessed.encode()).hexdigest()}
        (staged / 'configuration-audit.json').write_text(json.dumps(audit, indent=2) + '\n')
        shutil.copyfile(build / '.libs/libgc.a', staged / 'lib/libgc.a')
        shutil.copyfile(build / 'include/config.h', staged / 'include/config.h')
        for path in sorted((source / 'include').glob('*.h')):
            shutil.copyfile(path, staged / 'include/gc' / path.name)
        for name in (*ADAPTER, *RUNTIME_INPUTS, 'packaging/bdwgc.json', 'scripts/prepare-gc.py'):
            (staged / name).parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, staged / name)
        identity = hashlib.sha256(canonical(config)).hexdigest()
        (staged / 'configuration.json').write_bytes(canonical(config))
        (staged / 'include/ironwood_gc_configuration.h').write_text(
            '// SPDX-License-Identifier: MIT OR Apache-2.0\n'
            '#define IRONWOOD_GC_CONFIG_SHA256 "' + identity + '"\n')
        subprocess.run([str(llvm / 'bin/clang'), *flags, '-O3', '-fPIC', '-fvisibility=hidden',
                        '-std=c11', '-Wall', '-Wextra', '-Werror', '-Wpedantic',
                        '-I' + str(staged / 'include/gc'), '-I' + str(staged / 'include'),
                        '-I' + str(staged / 'runtime/include'),
                        '-ffile-prefix-map=' + str(work) + '=.',
                        '-c', str(staged / ADAPTER[1]), '-o', str(staged / 'adapter.o')], check=True, env=env)
        subprocess.run([str(llvm / 'bin/llvm-ar'), 'rcD', str(staged / 'lib/libironwood_gc_engine.a'),
                        str(staged / 'adapter.o')], check=True, env=env)
        symbols = {name: audit_archive(staged / 'lib' / name, llvm, target)
                   for name in ('libgc.a', 'libironwood_gc_engine.a')}
        properties = {'format': '1', 'configuration.sha256': identity, 'target': target,
                      'target.triple': config['target.triple'], 'target.data-layout': config['target.data-layout'],
                      'llvm.version': config['llvm.version'], 'adapter.abi': config['adapter.abi'],
                      'roots.version': str(config['roots.version']), 'runtime.owner.version': str(config['runtime.owner.version'])}
        properties.update({'sha256.' + str(p.relative_to(staged)): digest(p)
                           for p in sorted(staged.rglob('*')) if p.is_file()})
        (staged / 'build.properties').write_text('# SPDX-License-Identifier: MIT OR Apache-2.0\n'
                + ''.join(k + '=' + v + '\n' for k, v in sorted(properties.items())))
        manifest = {'format': 1, 'gc.config.sha256': identity, 'configuration': config,
                    'compiler': output([llvm / 'bin/clang', '--version']).splitlines()[0],
                    'make': output(['make', '--version']).splitlines()[0], 'archives': symbols,
                    'files.sha256': {str(p.relative_to(staged)): digest(p)
                                     for p in sorted(staged.rglob('*')) if p.is_file()}}
        (staged / 'build.json').write_text(json.dumps(manifest, indent=2, sort_keys=True) + '\n')
        verify(staged, llvm, target)
        staged.rename(prefix)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prefix', type=Path, required=True)
    parser.add_argument('--llvm-home', type=Path, required=True)
    parser.add_argument('--target', choices=('macos-arm64', 'linux-arm64', 'linux-x86_64'), default=host_target())
    parser.add_argument('--cache', type=Path, default=ROOT / 'workspace/gc-downloads')
    parser.add_argument('--jobs', type=int, default=4)
    parser.add_argument('--verify', action='store_true', help='offline validation only, never download/build')
    args = parser.parse_args()
    if args.jobs < 1 or not args.target:
        parser.error('a supported native target and positive job count are required')
    prefix, llvm = args.prefix.resolve(), args.llvm_home.resolve()
    if not args.verify:
        prepare(prefix, llvm, args.target, args.cache.resolve(), args.jobs)
    manifest, _ = verify(prefix, llvm, args.target)
    print('Verified isolated GC1 SDK: ' + manifest['gc.config.sha256'])


if __name__ == '__main__':
    main()
