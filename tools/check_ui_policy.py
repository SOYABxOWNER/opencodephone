#!/usr/bin/env python3
"""Reject a small set of concrete UI regressions. DS palettes/shapes are allowlisted by scope."""
from pathlib import Path
import re
import sys
root = Path(__file__).resolve().parent.parent
errors = []
for base in (root / 'app/src/main/java', root / 'core/designsystem/src/main/java'):
    for file in base.rglob('*.kt'):
        text = file.read_text()
        rules = [(r'import androidx\.compose\.material\.', 'M2 import alongside M3')]
        if 'app/src/main/java' in str(file):
            rules += [(r'Color\(0x', 'screen-level hex color: use colorScheme'),
                      (r'fontSize\s*=\s*\d+\.sp', 'screen-level font size: use typography/code roles')]
        for pattern, reason in rules:
            for match in re.finditer(pattern, text):
                line = text.count('\n', 0, match.start()) + 1
                errors.append(f'{file.relative_to(root)}:{line}: {reason}')
if errors:
    print('\n'.join(errors), file=sys.stderr)
    sys.exit(1)
print('UI policy: no M2 imports, screen-level hex colors or screen-level font sizes')
