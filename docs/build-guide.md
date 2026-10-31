# 0.5.0 构建说明

应用版本0.5.0-task-organize，versionCode 6，包名com.dailyflow.app。保留Gradle Wrapper8.9、AGP8.7.3、Kotlin/Compose插件2.0.21、KSP2.0.21-1.0.28；SDK35、minSdk26，JDK21.0.12.1，字节码目标17。

源码解压到独立英文路径，配置JAVA_HOME、ANDROID_HOME及本地local.properties的sdk.dir。本机路径和Debug私钥不进入源码ZIP。
使用原有Google Maven、Maven Central和插件需要的Gradle Plugin Portal，不添加镜像或关闭TLS验证。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --console=plain --rerun-tasks
```

已有本地Robolectric JAR可传 `-ProbolectricDependencyDir=<本机目录>`；沿用历史Debug证书时，用 `-Duser.home=<已有构建用户目录>` 指向原密钥所在用户目录，避免生成替代密钥。不把私钥交付到源码包。
测试输出在app/build/test-results/testDebugUnitTest，Lint在app/build/reports，APK在app/build/outputs/apk/debug。
本候选交付APK复用原已验证APK；本轮另在整改副本执行构建检查，不能把复用文件称为新构建产物。

离线依赖解析是已知独立环境问题。此前checkDebugAarMetadata --offline在lifecycle-livedata:2.8.7失败，本轮不声称修复，不手工复制AAR或清空缓存。
测试说明见 [当前验证口径](testing.md)，历史证据见 [0.4.0基线](phase2e-baseline-20261029.md)。不运行安装、ADB或模拟器任务。
