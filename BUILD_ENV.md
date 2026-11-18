# SnapSolve「秒解」Ubuntu 24.04 无头（headless）打包环境配置

> 目标：在一台无图形界面的 Ubuntu 24.04 LTS（x86_64）机器上，纯命令行完成本项目 APK 的构建。
> 产物：`app/build/outputs/apk/debug/app-debug.apk`（debug 签名，可直接安装）。
> 全程不需要 Android Studio；SDK 组件、Gradle、依赖全部走命令行。

## 一、版本总表（务必按此对齐）

| 组件 | 版本 | 来源 |
|---|---|---|
| 操作系统 | Ubuntu 24.04 LTS，**x86_64**（build-tools 的 aapt2 仅提供 x86_64 二进制，ARM 机器不可用） | — |
| JDK | **OpenJDK 17**（apt 包 `openjdk-17-jdk-headless`） | Ubuntu 官方源 |
| Android commandline-tools | **12.0**（build `11076708`，即 `commandlinetools-linux-11076708_latest.zip`） | dl.google.com |
| platform-tools | 最新（约 35.0.x，sdkmanager 自动拉） | sdkmanager |
| Android 平台 | **platforms;android-34**（Android 14） | sdkmanager |
| build-tools | **34.0.0** | sdkmanager |
| Gradle | **8.7**（AGP 8.5.x 的最低要求版本） | services.gradle.org |
| Android Gradle Plugin | **8.5.2**（与根 `build.gradle` 已声明一致，勿改） | google() Maven |
| compileSdk / targetSdk | **34** | app/build.gradle |
| minSdk | **24**（Android 7.0；代码使用的 CameraX / Java API 均可覆盖） | app/build.gradle |
| AndroidX AppCompat | **1.7.0**（主题 `Theme.AppCompat.DayNight.NoActionBar` 依赖它） | google() Maven |
| CameraX | **1.3.4**（core / camera2 / lifecycle / view 四件套，版本必须一致） | google() Maven |
| OkHttp | **4.12.0**（含 okio，SSE 流式读取用） | Maven Central |
| Guava | **33.2.1-android**（提供 `ListenableFuture`；CameraX 也会传递引入，显式声明最稳） | Maven Central |

磁盘占用约 1.5–2 GB；内存建议 ≥ 2 GB 空闲（`gradle.properties` 已配 `-Xmx2048m`）。

## 二、系统依赖与 JDK 17

```bash
sudo apt update
sudo apt install -y openjdk-17-jdk-headless unzip wget
java -version   # 应输出 openjdk 17.0.x
```

> 不要装 JDK 21+：AGP 8.5.2 的官方支持区间是 JDK 17，避免兼容性问题。

## 三、安装 Android SDK（commandline-tools 方式）

```bash
export ANDROID_HOME=$HOME/android-sdk
mkdir -p $ANDROID_HOME/cmdline-tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-11076708_latest.zip -d $ANDROID_HOME/cmdline-tools
mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest
```

接受许可证并安装组件：

```bash
SDKMANAGER=$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager
yes | $SDKMANAGER --licenses
$SDKMANAGER "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

写入环境变量（追加到 `~/.bashrc`）：

```bash
echo "export ANDROID_HOME=$HOME/android-sdk" >> ~/.bashrc
echo 'export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools' >> ~/.bashrc
source ~/.bashrc
```

## 四、安装 Gradle 8.7

项目当前**没有** Gradle Wrapper，先手动装一份 Gradle 用于首次构建（或生成 wrapper）：

```bash
wget https://services.gradle.org/distributions/gradle-8.7-bin.zip
unzip gradle-8.7-bin.zip -d $HOME/tools
echo 'export PATH=$PATH:$HOME/tools/gradle-8.7/bin' >> ~/.bashrc
source ~/.bashrc
gradle --version   # 应显示 Gradle 8.7 / JVM 17.0.x
```

## 五、项目侧需要补的文件（当前缺失，必须先补）

### 1. 新建 `app/build.gradle`

```groovy
plugins {
    id 'com.android.application'
}

android {
    namespace 'com.jone.snapsolve'
    compileSdk 34

    defaultConfig {
        applicationId "com.jone.snapsolve"
        minSdk 24
        targetSdk 34
        versionCode 1
        versionName "1.0"
    }

    buildTypes {
        release {
            minifyEnabled false
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}

dependencies {
    implementation 'androidx.appcompat:appcompat:1.7.0'
    implementation 'androidx.camera:camera-core:1.3.4'
    implementation 'androidx.camera:camera-camera2:1.3.4'
    implementation 'androidx.camera:camera-lifecycle:1.3.4'
    implementation 'androidx.camera:camera-view:1.3.4'
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
    implementation 'com.google.guava:guava:33.2.1-android'
}
```

### 2. 处理 `local.properties`

当前内容是 Windows 残留路径（`D:\Android\sdk`），在 Linux 上必须替换：

```bash
echo "sdk.dir=$HOME/android-sdk" > local.properties
```

（设置了 `ANDROID_HOME` 环境变量时，直接删除该文件也可以。）

### 3. （推荐）生成 Gradle Wrapper，固化版本

```bash
cd <项目根目录>
gradle wrapper --gradle-version 8.7
# 之后构建统一用 ./gradlew，不再依赖手动安装的 Gradle
```

## 六、打包命令

```bash
cd <项目根目录>
./gradlew assembleDebug        # 或： gradle assembleDebug
```

首次构建会联网下载 AGP 8.5.2 与全部依赖（google() + mavenCentral），耗时取决于带宽。
产物位置：

```
app/build/outputs/apk/debug/app-debug.apk
```

传到手机安装验证：

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 七、（可选）release 签名包

```bash
# 1. 生成 keystore（仅一次，妥善保管）
keytool -genkeypair -v -keystore release.keystore -alias snapsolve \
  -keyalg RSA -keysize 2048 -validity 10000

# 2. 在 app/build.gradle 的 android {} 内加 signingConfigs（密码走环境变量更安全）
# 3. 构建
./gradlew assembleRelease
# 或对未签名包手动签名：
$ANDROID_HOME/build-tools/34.0.0/zipalign -f 4 app-release-unsigned.apk app-release-aligned.apk
$ANDROID_HOME/build-tools/34.0.0/apksigner sign --ks release.keystore --out app-release.apk app-release-aligned.apk
```

## 八、构建后自检清单

| 检查项 | 命令 | 预期 |
|---|---|---|
| JDK | `java -version` | 17.0.x |
| Gradle | `gradle --version` 或 `./gradlew --version` | Gradle 8.7，JVM 17 |
| SDK 组件 | `sdkmanager --list_installed` | platform-tools / android-34 / build-tools 34.0.0 |
| APK 产物 | `ls -lh app/build/outputs/apk/debug/` | app-debug.apk 存在 |
| 安装冒烟 | `adb install` 后启动 | 相机预览正常，设置页可保存 |

## 九、常见问题

1. **`aapt2` 无法执行 / “cannot execute binary”**：机器是 ARM 架构。build-tools 仅提供 x86_64 二进制，需换 x86_64 机器或用 x86_64 容器。
2. **`Failed to install the following SDK components: platforms;android-34`**：未接受许可证，重跑 `yes | sdkmanager --licenses`。
3. **`Unsupported class file major version`**：JDK 版本不对（用了 21+ 或 11），确认 `JAVA_HOME` 指向 JDK 17。
4. **依赖拉取超时**：google()/mavenCentral 需可访问外网；必要时为 Gradle 配置代理（`systemProp.http(s).proxyHost/Port` 写入 `gradle.properties`）。
5. **`SDK location not found`**：`local.properties` 的 `sdk.dir` 与实际路径不符，或 `ANDROID_HOME` 未生效。
6. **应用闪退在相机页**：无头机器只管构建；运行验证需真机（App 需要 CAMERA 权限与摄像头硬件，manifest 中 `uses-feature camera required=true`）。
