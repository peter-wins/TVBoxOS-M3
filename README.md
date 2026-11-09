# TVBoxOS-M3

基于 [q215613905/TVBoxOS](https://github.com/q215613905/TVBoxOS) 的 Android TV 播放器，主要改动是**升级到 Media3、修复直播 ANR、优化内存与首页界面**。

> 本项目不含任何影视源。首次启动需要自行配置数据源（设置 → 配置地址）。
> 请只使用你有权访问的内容，作者不对第三方源的内容与合法性负责。

## 相对上游的主要改动

### 1. 播放内核升级：ExoPlayer2 2.19.1 → Media3 1.8.0

| 项目 | 上游 | 本项目 |
|---|---|---|
| 播放器 | `com.google.android.exoplayer2:2.19.1` | `androidx.media3:1.8.0` |
| FFmpeg | jellyfin exoplayer2 扩展 | `org.jellyfin.media3:media3-ffmpeg-decoder:1.8.0+1` |
| AGP / Gradle | 7.2.2 / 7.5 | 8.1.4 / 8.9 |
| compileSdk / minSdk | 33 / 19 | 35 / 21 |

### 2. 启用 FFmpeg 软解（硬解优先）

上游打包的 `libffmpegJNI.so` 从未被初始化，软解形同虚设。本项目：

- 新增 `FfmpegSupport`：探测并缓存 `FfmpegLibrary.isAvailable()` / `getVersion()`
- 新增 `FfmpegRenderersFactory`：注册 `FfmpegAudioRenderer` 与 `ExperimentalFfmpegVideoRenderer`
- 渲染模式用 `EXTENSION_RENDERER_MODE_ON`（硬解优先，软解兜底），
  避免 `PREFER` 让普通 H.264 也走软解导致电视 CPU 吃力
- `App.onCreate` 输出 FFmpeg 状态日志，便于排查

### 3. 修复直播切台 ANR

上游在连续切台时会出现 `Input dispatching timed out`（主线程阻塞超过 5 秒）。本项目：

- EPG 请求加 generation 版本号，切台时主动 `cancel()` 旧请求
- `pendingChannelStart` 切台队列化，避免任务堆积
- `channelPlaybackGeneration` 播放代际校验，丢弃过期回调
- 播放器加 `setReleaseTimeoutMs(100)` / `setDetachSurfaceTimeoutMs(500)`，
  防止停滞的电视硬解拖住应用线程
- 新增 `XmlTvParser`：SAX 流式解析替代 DOM 全量加载，可中断、内存更低

### 4. 内存优化

| 优化项 | 说明 |
|---|---|
| EPG 缓存上限 | `MAX_EPG_CACHE_SIZE = 60`，防止静态 Hashtable 无限增长 |
| `onTrimMemory` / `onLowMemory` | 系统内存紧张时主动清 EPG 与 Glide 缓存 |
| Glide 内存缓存 | 限制为 16MB（默认约为屏幕尺寸 4 倍） |
| 切台防抖 | 280ms 窗口内只响应一次 |

实测直播场景 PSS：IJK 方案 350MB → Media3 方案约 190-215MB。

### 5. 界面

- 首页 Hero 区：影片元数据（简介等）异步加载与展示
- 沉浸式背景：焦点卡片切换时全屏背景跟随淡入
- 配色统一：卡片标签改深色半透明胶囊，去除高饱和多色堆叠
- 焦点态强化：描边加粗、圆角统一
- 全局背景：原蓝色调图片改为深色渐变

### 6. 配置迁移

升级后首次启动会把播放器重置为 **EXO（Media3）+ SurfaceView**，
避免旧配置停留在 IJK 而让上述改进失效。只执行一次，之后可在设置中自行调整。

## 编译

### 环境要求

```yaml
JDK:         17（必须，21 与 AGP 8.1.4 存在兼容问题）
Android SDK: platform 35 / build-tools 35
Gradle:      8.9（wrapper 已内置）
```

### 命令

```bash
# 32 位（armeabi-v7a，多数电视盒子与老电视）
./gradlew :app:assembleJava32Release

# 64 位（arm64-v8a，较新设备）
./gradlew :app:assembleJava64Release
```

产物位于 `app/build/outputs/apk/`。

### 签名

本地编译需要提供签名信息（`local.properties` 或 `gradle.properties`）：

```properties
RELEASE_STORE_FILE=./your-release.jks
RELEASE_STORE_PASSWORD=***
RELEASE_KEY_ALIAS=***
RELEASE_KEY_PASSWORD=***
```

生成密钥：

```bash
keytool -genkeypair -v -keystore your-release.jks -alias tvbox \
  -keyalg RSA -keysize 2048 -validity 10000
```

CI 通过 GitHub Secrets 读取：`RELEASE_KEYSTORE_BASE64`、`RELEASE_KEY_ALIAS`、
`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_PASSWORD`。未配置时跳过签名。

## 使用

1. 安装 APK（需允许未知来源）
2. 打开应用 → 设置 → 配置地址 → 填入你自己的数据源
3. 设置 → 播放器，确认是 **EXO 播放器**
4. 设置 → 渲染方式，确认是 **SurfaceView**（画质更好、可走硬件直通）

## 已知限制

- **视频软解为实验性**：jellyfin 的 `ExperimentalFfmpegVideoRenderer` 上游标注
  `TODO: Implement`，实际以硬解为主，FFmpeg 主要提供音频软解能力
- **RTMP / RTSP 未充分实测**：协议入口已迁移到 Media3 对应模块，但缺少真实源验证
- **仅测试过 armeabi-v7a**：arm64 包可编译，未在设备上验证

## 致谢与许可

- 上游：[q215613905/TVBoxOS](https://github.com/q215613905/TVBoxOS)
- 播放器框架：[Doikki DKVideoPlayer](https://github.com/Doikki/DKVideoPlayer)
- FFmpeg 扩展：[jellyfin/media3-ffmpeg-decoder](https://github.com/jellyfin)
- 本项目的修改同样以 **AGPL-3.0** 发布，详见 [LICENSE](LICENSE)

上游 README 保留在 [README-UPSTREAM.md](README-UPSTREAM.md)。
