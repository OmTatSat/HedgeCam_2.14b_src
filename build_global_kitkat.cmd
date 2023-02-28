@echo off
rem		Command file for building apk. Written by Caddish Hedgehog.
rem		No Eclipse, or Android Studio, or other buggy shit! Just JDK and Android SDK!

rem	Main configuration
call ./build/config.cmd

set MIN_SDK=15
set MAX_SDK=20
set RS_TARGET_SDK=19

set DEBUG=false

set FILENAME_SUFFIX=_global_android4
rem		Directory for generated files
set GEN_DIR=%CD%\gen\global_kitkat
rem		Resource directories for aapt.exe
set RES=-S "%CD%\res" -S "%CD%\res-release" -S "%CD%\res-lang" -S "%CD%\res-parsed" -S "%GEN_DIR%\res"

set AAPT_MORE_OPTS=

call ../key.cmd
rem		File key.cmd must contain following variables:
rem		set KEYSTORE=<your keystore>
rem		set KEYSTORE_PASS=<your keystore password>
rem		set KEY=<certificate name>
rem		set KEY_PASS=<certificate password>

call ./build/main.cmd