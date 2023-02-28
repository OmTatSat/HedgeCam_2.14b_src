@md %GEN_DIR%\dex > nul 2> nul
@md %GEN_DIR%\java > nul 2> nul
@md %GEN_DIR%\obj > nul 2> nul


rem echo Building jni binaries...
rem set NDK_PROJECT_PATH=%CD%
rem set NDK_OUT=%GEN_DIR%\obj-jni
rem set NDK_LIBS_OUT=%GEN_DIR%\dex\lib
rem call %NDK_PATH%\ndk-build.cmd
rem @if errorlevel 1 goto err_jni

IF [%MAX_SDK%] == [] goto no_max_sdk
set MAX_SDK= --max-sdk-version %MAX_SDK%
:no_max_sdk

if [%RS_TARGET_SDK%] == [19] set /a VERSION_CODE=%VERSION_CODE%-1

echo:
echo:
echo Compiling renderscripts...
FOR /R "%CD%\rs" %%i IN (*.rs) DO call set RS_LIST=%%RS_LIST%% %%i
call "%BUILD_TOOLS_PATH%\llvm-rs-cc.exe" -target-api %RS_TARGET_SDK% -I "%BUILD_TOOLS_PATH%\renderscript\include" -I "%BUILD_TOOLS_PATH%\renderscript\clang-include" -o "%GEN_DIR%\res\raw" -java-reflection-path-base "%GEN_DIR%\java" %RS_LIST%
@if errorlevel 1 goto err_compile_rs

rem		Ugly preprocessor replacement for Java
echo package %APP_PACKAGE%;public class MyDebug {public static final boolean LOG = %DEBUG%;} > "%GEN_DIR%\java\MyDebug.java"

echo:
echo:
echo Creating R.java...
call "%BUILD_TOOLS_PATH%\aapt.exe" package -f -m -G "%GEN_DIR%\proguard.pro" --auto-add-overlay %AAPT_MORE_OPTS% --version-code %VERSION_CODE% --version-name %VERSION% --min-sdk-version %MIN_SDK% %MAX_SDK% --target-sdk-version %TARGET_SDK% %RES% -J "%CD%\src" -M "%CD%\%ANDROID_MANIFEST%" -I "%PLATFORM_SDK_PATH%\android.jar" -J "%GEN_DIR%\java"
@if errorlevel 1 goto err_r

IF NOT EXIST "%GEN_DIR%\sources.lst" (
	echo:
	echo:
	echo Creating sources.lst
	FOR /R "%CD%\src" %%i IN (*.java) DO ECHO %%i >> "%GEN_DIR%\sources.lst"
	FOR /R "%GEN_DIR%\java" %%i IN (*.java) DO ECHO %%i >> "%GEN_DIR%\sources.lst"
)

echo:
echo:
echo Compiling...
call "%JDK_HOME%\bin\javac" -nowarn -d "%GEN_DIR%\obj" -cp "%CLASS_PATH%" -sourcepath "%CD%\src" @"%GEN_DIR%\sources.lst"
@if errorlevel 1 goto err_compile

IF [%PROGUARD_HOME%] == [] goto dex_no_shrink


@rmdir /S /Q "%GEN_DIR%\opt" > nul 2> nul
echo:
echo:
echo Shrinking classes...
call "%JDK_HOME%\bin\java" -jar "%PROGUARD_HOME%\lib\proguard.jar" @"%CD%\proguard.pro" @"%GEN_DIR%\proguard.pro" -injars "%GEN_DIR%\obj" -injars "%CD%\libs" -outjars "%GEN_DIR%\opt" -libraryjars %PLATFORM_SDK_PATH%\android.jar -verbose -printusage "%GEN_DIR%\unused.txt" -printmapping "%GEN_DIR%\mapping.txt"
@if errorlevel 1 goto err_proguard


echo:
echo:
echo Creating classes.dex...
call "%JDK_HOME%\bin\java" -jar "%BUILD_TOOLS_PATH%\lib\dx.jar" --dex --output="%GEN_DIR%\dex\classes.dex" "%GEN_DIR%\opt"
@if errorlevel 1 goto err_dx
goto package


:dex_no_shrink
echo:
echo:
echo Creating classes.dex...
call "%JDK_HOME%\bin\java" -jar "%BUILD_TOOLS_PATH%\lib\dx.jar" --dex --output="%GEN_DIR%\dex\classes.dex" "%GEN_DIR%\obj" "%CD%\libs"
@if errorlevel 1 goto err_dx

:package
echo:
echo:
echo Creating package...
call "%BUILD_TOOLS_PATH%\aapt.exe" package -f --auto-add-overlay %AAPT_MORE_OPTS% --version-code %VERSION_CODE% --version-name %VERSION% --min-sdk-version %MIN_SDK% %MAX_SDK% --target-sdk-version %TARGET_SDK% -M "%CD%\%ANDROID_MANIFEST%" %RES% -A "%CD%\assets" -I "%PLATFORM_SDK_PATH%\android.jar" -F "%GEN_DIR%\%APP_NAME%_unaligned.apk" %GEN_DIR%\dex
@if errorlevel 1 goto err_pack

if %TARGET_SDK% LSS 30 goto sign_legacy

:sign
echo:
echo:
echo Aligning...
call "%BUILD_TOOLS_PATH%\zipalign.exe" -f 4 "%GEN_DIR%\%APP_NAME%_unaligned.apk" "%GEN_DIR%\%APP_NAME%_unsigned.apk"
@if errorlevel 1 goto err_align

echo:
echo:
echo Signing...
call "%JDK_HOME%\bin\java" -jar "%BUILD_TOOLS_PATH%\lib\apksigner.jar" sign --v1-signing-enabled true --v2-signing-enabled true --ks %KEYSTORE% --ks-pass pass:%KEYSTORE_PASS% --ks-key-alias %KEY% --key-pass pass:%KEY_PASS% --out "%CD%\%APP_NAME%_%VERSION%%FILENAME_SUFFIX%.apk" "%GEN_DIR%\%APP_NAME%_unsigned.apk"
@if errorlevel 1 goto err_sign
goto done

:sign_legacy
echo:
echo:
echo Signing (legacy)...
ren "%GEN_DIR%\%APP_NAME%_unaligned.apk" "%APP_NAME%_unsigned.apk"
call "%JDK_HOME%\bin\jarsigner" -sigalg MD5withRSA -digestalg SHA1 -keystore %KEYSTORE% -storepass %KEYSTORE_PASS% -keypass %KEY_PASS% -signedjar "%GEN_DIR%\%APP_NAME%_unaligned.apk" "%GEN_DIR%\%APP_NAME%_unsigned.apk" %KEY%
@if errorlevel 1 goto err_sign

echo:
echo:
echo Aligning...
call "%BUILD_TOOLS_PATH%\zipalign.exe" -f 4 "%GEN_DIR%\%APP_NAME%_unaligned.apk" "%CD%\%APP_NAME%_%VERSION%%FILENAME_SUFFIX%.apk"
@if errorlevel 1 goto err_align

:done
@del "%GEN_DIR%\%APP_NAME%_unsigned.apk"
@del "%GEN_DIR%\%APP_NAME%_unaligned.apk"

IF [%DONT_INSTALL%] == [true] goto exit
IF [%ADB%] == [] goto exit
echo:
echo:
echo Installing...
call "%ADB%" wait-for-device
call "%ADB%" install -r "%CD%\%APP_NAME%_%VERSION%%FILENAME_SUFFIX%.apk"
@if errorlevel 1 goto err_install

IF [%MAIN_ACTIVITY%] == [] goto exit

echo:
echo:
echo Launching...
call "%ADB%" shell input keyevent KEYCODE_WAKEUP
call "%ADB%" shell am start -n "%MAIN_ACTIVITY%"
@if errorlevel 1 goto err_launch

goto exit


:err_r
echo Error creating R.java
goto make_pause

:err_jni
echo Building jni libs error
goto make_pause

:err_compile_rs
echo Compile renderscripts error
goto make_pause

:err_compile
echo Compile error
goto make_pause

:err_proguard
echo Error executing proguard
goto make_pause

:err_dx
echo Error creating classes.dex
goto make_pause

:err_pack
echo Error creating package
goto make_pause

:err_sign
echo Sign error
goto make_pause

:err_align
echo Align error
goto make_pause

:err_install
echo Installing error
goto make_pause

:err_launch
echo Launching error
goto make_pause

:make_pause
pause
:exit
