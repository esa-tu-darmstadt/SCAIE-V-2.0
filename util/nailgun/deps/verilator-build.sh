#!/usr/bin/env bash

#https://verilator.org/guide/latest/install.html
# Dependencies
# git help2man perl python3 make
# g++
# (Non-Ubuntu libgz)
# (Ubuntu: libfl2, libfl-dev, zlibc, zlib1g, zlib1g-dev)
# ccache libgoogle-perftools-dev (or gperftools-devel) numactl
# perl-doc

set -e

MAKEJOBS=$(nproc --all || echo 20)
[ -d verilator ] || git clone https://github.com/verilator/verilator.git --branch v5.044 verilator

unset VERILATOR_ROOT
SIM_PREFIX=$(pwd)/sim-prefix
cd verilator

mkdir -p $SIM_PREFIX
export VERILATOR_ROOT=$(pwd)
autoconf
./configure --prefix "${SIM_PREFIX}"
make -j${MAKEJOBS}
make install

cd ..

rm -f verilator-source.sh
echo '#!/usr/bin/env bash' > verilator-source.sh
echo "export PATH=${SIM_PREFIX}/bin:\${PATH}" >> verilator-source.sh
#echo "export VERILATOR_ROOT=${VERILATOR_ROOT}" >> verilator-source.sh
echo "export PKG_CONFIG_PATH=\${PKG_CONFIG_PATH}:${SIM_PREFIX}/share/pkgconfig" >> verilator-source.sh
chmod +x verilator-source.sh

