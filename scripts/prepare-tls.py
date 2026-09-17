#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Prepare the explicitly requested, pinned, platform-local TLS SDK. Never run by ironwoodc."""
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


def properties(path):
    return dict(line.split('=', 1) for line in path.read_text().splitlines()
                if line and not line.startswith('#'))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def output(args):
    return subprocess.check_output([str(x) for x in args], text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prefix', type=Path, required=True)
    parser.add_argument('--llvm-home', type=Path, required=True)
    parser.add_argument('--cache', type=Path, default=ROOT / 'workspace/tls-downloads')
    parser.add_argument('--verify', action='store_true', help='validate an existing SDK without rebuilding')
    parser.add_argument('--jobs', type=int, default=min(8, os.cpu_count() or 1))
    args = parser.parse_args()
    pins_path = ROOT / 'packaging/tls-dependencies.properties'
    pins = properties(pins_path)
    prefix, llvm = args.prefix.resolve(), args.llvm_home.resolve()
    if args.verify:
        manifest = properties(prefix / 'build.properties')
        expected = {('Darwin', 'arm64'): 'macos-arm64', ('Linux', 'aarch64'): 'linux-arm64',
                    ('Linux', 'x86_64'): 'linux-x86_64'}[(platform.system(), platform.machine())]
        if properties(prefix / 'dependencies.properties') != pins or manifest['platform'] != expected:
            raise SystemExit('TLS SDK pin/platform mismatch')
        if manifest['pins.sha256'] != digest(pins_path) or manifest['configuration'] != pins['configuration']:
            raise SystemExit('TLS SDK build configuration mismatch')
        for key in ('llvm.version', 'perl.version', 'make.version'):
            if manifest[key] != pins[key]: raise SystemExit('TLS SDK tool mismatch: ' + key)
        for key, value in manifest.items():
            if key.startswith('sha256.'):
                relative = Path(key[7:])
                if relative.is_absolute() or '..' in relative.parts or digest(prefix / relative) != value:
                    raise SystemExit('TLS SDK checksum mismatch: ' + key)
        if digest(prefix / 'share/cacert.pem') != pins['ca.sha256']:
            raise SystemExit('TLS SDK CA mismatch')
        print('Verified TLS SDK: ' + str(prefix))
        return
    if prefix.exists():
        raise SystemExit(f'refusing to replace existing prefix: {prefix}; choose a new build directory')
    version = output([llvm / 'bin/llvm-config', '--version'])
    if version != pins['llvm.version']:
        raise SystemExit(f"TLS recipe requires LLVM {pins['llvm.version']}, found {version}")
    system, machine = platform.system(), platform.machine()
    target = {('Darwin', 'arm64'): ('macos-arm64', 'darwin64-arm64-cc'),
              ('Linux', 'aarch64'): ('linux-arm64', 'linux-aarch64'),
              ('Linux', 'x86_64'): ('linux-x86_64', 'linux-x86_64')}.get((system, machine))
    if not target:
        raise SystemExit(f'unsupported native target: {system}/{machine}')
    flags = ['-O3', '-fPIC', '-ffunction-sections', '-fdata-sections']
    metadata = {'platform': target[0], 'llvm.version': version,
                'perl.version': output(['perl', '-e', 'print $^V']),
                'make.version': output(['make', '--version']).splitlines()[0],
                'python.version': platform.python_version()}
    for key in ('perl.version', 'make.version'):
        if metadata[key] != pins[key]:
            raise SystemExit(f"TLS recipe requires {key}={pins[key]}, found {metadata[key]}; use packaging/idk-environment.yml")
    if not metadata['python.version'].startswith(pins['python.version'] + '.'):
        raise SystemExit(f"TLS recipe requires Python {pins['python.version']}.x")
    if system == 'Darwin':
        sdk = Path(os.environ.get('SDKROOT') or output(['xcrun', '--show-sdk-path'])).resolve()
        flags += ['-isysroot', str(sdk), '-mmacosx-version-min=' + pins['macos.deployment']]
        metadata.update({'sdk.path': str(sdk), 'sdk.version': output(['xcrun', '--show-sdk-version']),
                         'deployment': pins['macos.deployment'], 'link.flags': ''})
    else:
        package = 'sysroot_linux-aarch64' if machine == 'aarch64' else 'sysroot_linux-64'
        records = list((llvm / 'conda-meta').glob(package + '-*.json'))
        if len(records) != 1 or json.loads(records[0].read_text())['version'] != pins['linux.glibc']:
            raise SystemExit(f'TLS requires matching {package}=2.17 in --llvm-home')
        triple = 'aarch64-conda-linux-gnu' if machine == 'aarch64' else 'x86_64-conda-linux-gnu'
        sysroot = llvm / triple / 'sysroot'
        if not (sysroot / 'usr/include/features.h').is_file():
            raise SystemExit(f'missing glibc 2.17 sysroot: {sysroot}')
        flags += ['--sysroot=' + str(sysroot)]
        metadata.update({'sysroot.relative': str(sysroot.relative_to(llvm)),
                         'sysroot.package': package, 'glibc': '2.17', 'link.flags': ''})
    cache = args.cache.resolve()
    cache.mkdir(parents=True, exist_ok=True)
    downloads = {}
    for component in ('openssl', 'ca'):
        dest = cache / pins[component + '.url'].rsplit('/', 1)[1]
        if not dest.exists():
            temporary = dest.with_suffix(dest.suffix + '.download')
            urllib.request.urlretrieve(pins[component + '.url'], temporary)
            if digest(temporary) != pins[component + '.sha256']:
                raise SystemExit(f'{component}: downloaded checksum mismatch')
            temporary.rename(dest)
        if digest(dest) != pins[component + '.sha256']:
            raise SystemExit(f'{component}: cached checksum mismatch: {dest}')
        downloads[component] = dest
    prefix.parent.mkdir(parents=True, exist_ok=True)
    # Staging is a sibling, so install and manifest become visible together.
    with tempfile.TemporaryDirectory(prefix='.tls-build-', dir=prefix.parent) as temporary:
        work = Path(temporary)
        with tarfile.open(downloads['openssl']) as archive:
            for member in archive.getmembers():
                path = Path(member.name)
                if path.is_absolute() or '..' in path.parts or member.issym() or member.islnk():
                    raise SystemExit('unexpected archive path or link')
            archive.extractall(work, filter='data')
        source = work / ('openssl-' + pins['openssl.version'])
        stage = work / 'sdk'
        env = os.environ.copy()
        env.update({'CC': str(llvm / 'bin/clang'), 'AR': str(llvm / 'bin/llvm-ar'),
                    'RANLIB': str(llvm / 'bin/llvm-ranlib'), 'SOURCE_DATE_EPOCH': '1787616000'})
        # Relative installation prefix avoids embedding the temporary build path.
        configure = ['perl', 'Configure', target[1], '--prefix=/ironwood-tls',
                     '--openssldir=/ironwood-tls/unused', '--libdir=lib']
        configure += pins['configuration'].split() + flags
        subprocess.run(configure, cwd=source, env=env, check=True)
        subprocess.run(['make', '-j', str(args.jobs), 'build_libs'], cwd=source, env=env, check=True)
        install = work / 'install'
        subprocess.run(['make', 'install_dev', 'DESTDIR=' + str(install)], cwd=source, env=env, check=True)
        shutil.move(install / 'ironwood-tls', stage)
        (stage / 'share').mkdir(exist_ok=True)
        (stage / 'sources').mkdir()
        (stage / 'licenses').mkdir()
        shutil.copy2(downloads['openssl'], stage / 'sources' / downloads['openssl'].name)
        shutil.copy2(downloads['ca'], stage / 'share/cacert.pem')
        shutil.copy2(source / 'LICENSE.txt', stage / 'licenses/OpenSSL.txt')
        shutil.copy2(ROOT / 'LICENSES/MPL-2.0.txt', stage / 'licenses/MPL-2.0.txt')
        shutil.copy2(pins_path, stage / 'dependencies.properties')
        shutil.copy2(source / 'configdata.pm', stage / 'share/configdata.pm')
        # Embed only PEM certificates; preserve the unmodified source and its notices too.
        pem = downloads['ca'].read_text()
        certs = re.findall(r'-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----', pem, re.S)
        if not certs:
            raise SystemExit('pinned CA snapshot has no certificates')
        (stage / 'share/ironwood_ca_data.h').write_text(
            '/* Generated from pinned Mozilla CA data; MPL-2.0. */\n'
            'static const char ironwood_ca_pem[] =\n' +
            '\n'.join(json.dumps(line + '\n') for line in '\n'.join(certs).splitlines()) + ';\n')
        metadata.update({'format': '1', 'pins.sha256': digest(pins_path),
                         'configuration': pins['configuration'], 'ca.certificates': str(len(certs)),
                         'compile.flags': json.dumps(flags), 'configure.target': target[1]})
        for name in ('lib/libssl.a', 'lib/libcrypto.a'):
            description = output([llvm / 'bin/llvm-readobj', '--file-headers', stage / name])
            expected = 'aarch64' if machine in ('arm64', 'aarch64') else 'x86_64'
            architectures = re.findall(r'^Arch: (.+)$', description, re.M)
            if not architectures or any(arch != expected for arch in architectures):
                raise SystemExit(f'wrong archive architecture: {name}: {set(architectures)}')
        for file in sorted(stage.rglob('*')):
            if file.is_file():
                metadata['sha256.' + file.relative_to(stage).as_posix()] = digest(file)
        (stage / 'build.properties').write_text(''.join(f'{k}={v}\n' for k, v in sorted(metadata.items())))
        stage.rename(prefix)
    print(f'Prepared {target[0]} TLS SDK: {prefix}', flush=True)


if __name__ == '__main__':
    main()
