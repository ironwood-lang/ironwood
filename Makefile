# SPDX-License-Identifier: MIT OR Apache-2.0

.PHONY: all build test check-licenses package clean

all: test

build:
	./scripts/build.sh

test:
	./scripts/test.sh

check-licenses:
	./scripts/check-licenses.sh

package: test
	./scripts/package.sh

clean:
	rm -rf compiler/build build dist
