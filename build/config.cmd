rem		Your application name
set APP_NAME=HedgeCam
set APP_PACKAGE=com.caddish_hedgehog.hedgecam2

set VERSION=2.14b
set VERSION_CODE=47

set MIN_SDK=21
set TARGET_SDK=30
set RS_TARGET_SDK=21

set ANDROID_MANIFEST=AndroidManifest.xml

set JDK_HOME=C:\Program Files\Java\jdk1.8.0_151
set BUILD_TOOLS_PATH=D:\Android\sdk\build-tools\28.0.3
rem set NDK_PATH=K:\android\android-ndk-r11c
set PLATFORM_SDK_PATH=D:\Android\sdk\platforms\android-30

set CLASS_PATH=%PLATFORM_SDK_PATH%\android.jar;libs\annotations.jar;libs\support-v4.jar;libs\billing.jar

rem		ProGuard 5 home directory
set PROGUARD_HOME=D:\Android\proguard

rem		ADB executable file. Delete this variable if you don't want to install apk.
set ADB=D:\Android\adb\adb.exe

rem		For source code packing
set WINRAR="D:\Program Files\WinRAR\WinRAR.exe"

rem		Launch this activity after installing the apk. Delete this variable if you don't want to launch activity. 
set MAIN_ACTIVITY=%APP_PACKAGE%/%APP_PACKAGE%.MainActivity
