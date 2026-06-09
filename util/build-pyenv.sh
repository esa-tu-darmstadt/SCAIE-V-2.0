#!/usr/bin/env bash

#https://docs.cocotb.org/en/latest/install_devel.html
#Debian/Ubuntu: make gcc g++ python3 python3-dev python3-pip
#Red Hat: make gcc gcc-c++ libstdc++-devel libstdc++-static python3 python3-devel python3-pip

BASE=$(pwd)
COCOTB_ENV=$BASE/pyenv

# Create and activate a python environment.
if ! [ -f $COCOTB_ENV/bin/activate ]; then
	python -m venv $COCOTB_ENV
	rm -f .pyenv_installed.stamp
fi
source $COCOTB_ENV/bin/activate

set -e
SITEPACKAGES=$(python -c 'import sysconfig; print(sysconfig.get_paths()["purelib"])')
set +e
if [[ requirements.txt -nt .pyenv_installed.stamp ]] || ! [ -f .pyenv_installed.stamp ]; then
	set -e
	pip install -r requirements.txt
	touch .pyenv_installed.stamp
	set +e
fi

export CMAKE_PREFIX_PATH=$SITEPACKAGES/pybind11/share/cmake/pybind11:$CMAKE_PREFIX_PATH

set -e
pushd nailgun/deps/pyriscv-vp >/dev/null
make vps
cd vp
pip install -e .
popd >/dev/null
set +e
