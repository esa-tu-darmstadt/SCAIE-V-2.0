#!/bin/bash

#Optional, only required if bsc is not already installed.

#Requires (Fedora packages):
# make automake gcc gcc-c++
# ghc ghc-regex-compat-devel ghc-syb-devel ghc-old-time-devel ghc-split-devel tcl-devel
# pkgconf-pkg-config autoconf gperf
# flex bison

set -e

which ghc || (echo "Could not find ghc" && exit 1)

[ -d bsc-sources ] || git clone https://github.com/B-Lang-org/bsc.git --branch 2025.07 bsc-sources

MAKEJOBS=$(nproc --all || echo 20)
BSC_PREFIX=$(pwd)/bsc-prefix
cd bsc-sources
git submodule update --init --recursive
make -j${MAKEJOBS} GHCJOBS=${MAKEJOBS} install-src
rm -rf ../bsc-prefix
mv inst ../bsc-prefix

cd ..

rm -f bsc-source.sh
echo '#!/bin/bash' > bsc-source.sh
echo "export PATH=${BSC_PREFIX}/bin:\${PATH}" >> bsc-source.sh
chmod +x bsc-source.sh

