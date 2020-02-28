#! /bin/sh
set -x

docker pull therecipe/qt:windows_64_static
qtdeploy -docker build windows_64_static
