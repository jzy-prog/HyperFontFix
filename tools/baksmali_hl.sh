
cd /d/Projects/hyperfontfix/refs
JH=/d/Projects/hyperfontfix/tools/jdk/jdk-21.0.12.1+1/bin
CP="D:/Projects/hyperfontfix/tools/jars\\baksmali-2.5.2.jar;D:/Projects/hyperfontfix/tools/jars\\dexlib2-2.5.2.jar;D:/Projects/hyperfontfix/tools/jars\\util-2.5.2.jar;D:/Projects/hyperfontfix/tools/jars\\guava-27.1-jre.jar;D:/Projects/hyperfontfix/tools/jars\\jcommander-1.48.jar;D:/Projects/hyperfontfix/tools/jars\\failureaccess-1.0.1.jar"
"$JH/java.exe" -cp "$CP" org.jf.baksmali.Main d hl_main.dex -o hl_smali_main && echo main_ok
"$JH/java.exe" -cp "$CP" org.jf.baksmali.Main d hl_assets.dex -o hl_smali_assets && echo assets_ok
find hl_smali_main hl_smali_assets -name "*.smali" | wc -l
