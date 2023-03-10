@echo off

rem	Main configuration
call ./build/config.cmd

@set FILELIST=pack_source.lst

set ARCHIVE=%APP_NAME%_%VERSION%_src.zip

IF EXIST %ARCHIVE% goto err_exist

rem	Stupid WinRAR add all files with the specified name, ignoring directories. So, we need to remove directory with auto-generated files.
@rd /q /s "./gen"

%WINRAR% a -afzip -k -m5 -r -dh -zgpl-3.0.txt %ARCHIVE% @%FILELIST%

goto exit

:err_exist
echo The archive already exists
goto make_pause

:make_pause
pause

:exit