#!/usr/bin/env python3
"""Empaqueta documentación y evidencias; no incluye secretos ni binarios de la app."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

root = Path(__file__).resolve().parents[1]
files = [root / 'README.md', root / 'GUIA_EVALUACION.md']
allowed = {'.md', '.pdf', '.png', '.jpg', '.jpeg', '.json', '.sh', '.py', '.yml'}
for directory in ['docs', 'postman', 'deploy', 'scripts', '.github/workflows']:
    files.extend(p for p in (root / directory).rglob('*')
                 if p.is_file() and not p.is_symlink() and p.suffix.lower() in allowed)
output = root / 'documentacion.zip'
with ZipFile(output, 'w', ZIP_DEFLATED) as archive:
    for path in sorted(set(files)):
        archive.write(path, path.relative_to(root))
with ZipFile(output) as archive:
    assert archive.testzip() is None
print(f'{output.name}: {len(files)} archivos. Completa docs/ENTREGA.md y tus evidencias antes de entregar.')
