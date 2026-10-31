# 0.6.0 构建说明

包名com.dailyflow.app，versionName0.6.0-reminders，versionCode7。工具链保持Gradle Wrapper8.9、AGP8.7.3、Kotlin/Compose插件2.0.21、KSP2.0.21-1.0.28、SDK35/min26、JDK21.0.12.1/字节码17。
独立英文路径解压源码，配置JAVA_HOME、ANDROID_HOME/ANDROID_SDK_ROOT；如用local.properties只保存本机，不入包。使用已有官方Google/MavenCentral/插件Portal，不添加镜像、不关闭TLS、不伪造缓存。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --console=plain --rerun-tasks
```

本机若已准备Robolectric JAR，用-ProbolectricDependencyDir=<目录>。保留Debug签名时指定-Duser.home=<原密钥所在构建用户目录>；不生成替代密钥、不交付私钥。APK在app/build/outputs/apk/debug，JUnit在app/build/test-results/testDebugUnitTest，Lint在app/build/reports。
离线lifecycle-livedata:2.8.7是历史未解决问题，本阶段正常联网构建与离线可复现性分开记账。无需ADB、安装、手机或模拟器任务。测试口径见 [测试说明](testing.md)。
