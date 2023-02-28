@echo off

set DONT_INSTALL=true

@rd /q /s "./gen"

call ./build_debug.cmd
call ./build_main.cmd
call ./build_global_debug.cmd
call ./build_global.cmd
call ./build_play.cmd
call ./build_snap.cmd
call ./build_kitkat.cmd
call ./build_global_kitkat.cmd
call ./build_play_kitkat.cmd

call ./pack_source.cmd