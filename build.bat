@echo off
chcp 65001 >nul 2>&1
title AppTimeTracker Build

cd /d "%~dp0"

echo ========================================
echo   AppTimeTracker Build Script
echo ========================================
echo.

where javac >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未找到 javac，请安装 JDK
    pause
    exit /b 1
)

echo [信息] Java 版本:
java -version 2>&1 | findstr /i "version"
javac -version 2>&1
echo.

echo [1/3] 清理旧编译文件...
del /Q dist\*.class 2>nul
if exist dist\AppTimeTracker.jar del /Q dist\AppTimeTracker.jar 2>nul
echo       已清理
echo.

echo [2/3] 编译源文件...
javac --release 21 -encoding UTF-8 -cp "dist;dist\sqlite-jdbc.jar;dist\jna.jar;dist\slf4j-api.jar;dist\slf4j-nop.jar" -d dist *.java
if %errorlevel% neq 0 (
    echo.
    echo [错误] 编译失败！
    pause
    exit /b 1
)
echo       编译成功
echo.

echo [3/3] 验证编译结果...
set count=0
for %%f in (dist\*.class) do set /a count+=1
echo       生成 %count% 个 class 文件
echo.

echo ========================================
echo   构建完成！
echo ========================================
echo.
echo 运行方式:
echo   后台追踪: dist\AppTimeTracker.bat
echo   GUI 查看: dist\AppTimeTrackerGUI.bat
echo.
pause
