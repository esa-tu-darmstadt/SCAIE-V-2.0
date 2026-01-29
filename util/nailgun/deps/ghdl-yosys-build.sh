#!/usr/bin/env bash

#Builds ghdl-yosys for VHDL synthesis (as a makeshift VHDL-to-Verilog 'conversion')

#Run from within the dep directory.

# Dependencies: yosys yosys-devel
# ghdl extra dependencies: gcc, gcc-gnat, llvm14-devel
# ghdl-yosys-plugin dependencies: readline-static
# (Fedora ghdl appears to be broken for F38+)

# Known working: yosys Fedora package 0.34-1.20231006git8367f06.fc38, 0.40-1.20240411git47bdb3e.fc40
#                ghdl 1ec1eb6d9eccbe27fb940c65352151243e7524c6 (4.0-dev)
#                ghdl-yosys-plugin commit 0c4740a4f8f1e615cc587b3cd3849fa23a623862

set -e
LLVM_CONFIG=llvm-config-14

GHDL_PREFIX=$(pwd)/ghdl-prefix
mkdir -p ${GHDL_PREFIX}
if ! ls ${GHDL_PREFIX}/bin/ghdl ; then
	if ! which ${LLVM_CONFIG} ; then
		echo Could not find ${LLVM_CONFIG}. Check the setting in the build script or make sure to install an appropriate llvm devel package.
		exit 1
	fi
	echo Building ghdl
	[ -d ghdl-sources ] || git clone https://github.com/ghdl/ghdl ghdl-sources
	pushd ghdl-sources
	#git checkout 1ec1eb6d9eccbe27fb940c65352151243e7524c6
	git checkout d8f8e3dfe2f1768718f936b3ea6de87840bc3b25
	rm -rf build && mkdir build && cd build
	../configure "--with-llvm-config=${LLVM_CONFIG}" "--prefix=${GHDL_PREFIX}"
	make
	make install
	popd
fi
PATH=${GHDL_PREFIX}/bin:$PATH
GHDL_YOSYS_MODULE="${GHDL_PREFIX}/lib/ghdl_yosys.so"

if ! [ -d ghdl-yosys-plugin-sources ]; then
    git clone https://github.com/ghdl/ghdl-yosys-plugin ghdl-yosys-plugin-sources
    cd ghdl-yosys-plugin-sources
    #git checkout 511412f984d64ed7c46c4bdbd839f4b3c48f6fa5
    git checkout 07a30ed39fb6a078f1bf7e9e88ce9ed712380ec2
    cd ..
fi
pushd ghdl-yosys-plugin-sources
make clean
RPM_ARCH=$(uname -m) RPM_PACKAGE_NAME="ghdl-yosys-plugin" RPM_PACKAGE_VERSION="custom" RPM_PACKAGE_RELEASE="internal" make
# Based on https://github.com/ghdl/ghdl-yosys-plugin/blob/master/README.md
cp ghdl.so "${GHDL_YOSYS_MODULE}"
popd

rm -f ghdl-yosys-source.sh
echo '#!/usr/bin/env bash' > ghdl-yosys-source.sh
echo "export PATH=\"${GHDL_PREFIX}/bin:\${PATH}\"" >> ghdl-yosys-source.sh
echo "export GHDL_YOSYS_MODULE=\"${GHDL_YOSYS_MODULE}\"" >> ghdl-yosys-source.sh
chmod +x ghdl-yosys-source.sh

#yosys-config --exec mkdir -p --datdir/plugins
#yosys-config --exec ln -s "$GHDL_PREFIX/lib/ghdl_yosys.so" --datdir/plugins/ghdl.so

