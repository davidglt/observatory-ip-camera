#!/usr/bin/env bash
# Copyright (C) 2026 David González López-Tercero
# SPDX-License-Identifier: GPL-3.0-or-later
# See LICENSE in the repository root for licensing details.

set -euo pipefail
cd "$(dirname "$0")/.."

if ! head -n 2 LICENSE | grep -Fq 'GNU GENERAL PUBLIC LICENSE' ||
   ! head -n 2 LICENSE | grep -Fq 'Version 3, 29 June 2007'; then
    echo "LICENSE is missing the GNU GPL version 3 text." >&2
    exit 1
fi

check_header() {
    local file=$1
    local prefix
    case "$file" in
        *.java) prefix=' * ' ;;
        *) prefix='# ' ;;
    esac

    if ! head -n 8 "$file" | grep -Fq "${prefix}Copyright (C) 2026 David González López-Tercero" ||
       ! head -n 8 "$file" | grep -Fq "${prefix}SPDX-License-Identifier: GPL-3.0-or-later"; then
        echo "Missing GPL-3.0-or-later attribution header: $file" >&2
        return 1
    fi
}

while IFS= read -r file; do
    check_header "$file"
done < <(git ls-files -- 'src/**/*.java' 'test/**/*.java' 'test/*.sh')

while IFS= read -r file; do
    check_header "$file"
done < <(git ls-files -- 'build.sh' 'scripts/*.sh' 'scripts/*.py')

check_header test/license-headers-test.sh
echo "First-party source license headers are present."
