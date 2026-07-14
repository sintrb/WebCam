# WebCam

一个运行在 Android 手机上的轻量级摄像头抓拍服务。

APP 启动后会在手机本机开启一个 HTTP 服务，局域网内或经过端口转发的客户端可以直接通过浏览器或 HTTP 请求获取抓拍图片；同时 APP 也提供本地预览、手动拍照、设置管理、抓拍记录查看等功能。

## 下载 APK

推荐直接从 GitHub Releases 页面下载已打包好的安装包：

- [https://github.com/sintrb/WebCam/releases](https://github.com/sintrb/WebCam/releases)

建议优先下载最新版本的：

- `WebCam-v1.0-inruan-release.apk`

如果你下载的是 release 安装包，通常可以直接安装使用。

## 功能特性

- Android 本地摄像头预览
- HTTP 抓拍接口：`/snapshot.jpg`
- 支持自定义抓拍参数：
  - 图片宽高
  - 闪光灯开关
  - 延时拍照
  - 图片旋转角度
- 抓拍前自动对焦（可配置）
- 抓拍图片时间水印（可配置位置）
- 预览/抓拍记录列表
- 网页端最近抓拍缩略图展示
- 网页端当前页大图预览
- 网页端远程设置
- 设置持久化保存
- 支持浏览器标签页 favicon

## 适用场景

- 局域网设备抓拍
- 远程辅助查看现场画面
- 轻量级移动摄像头服务
- 调试、巡检、简易监控采图

## 界面说明

### APP 首页

- 上半部分：摄像头预览区域
- 右侧：预览、停止、拍照、清空记录等操作按钮
- 下半部分：照片记录列表

### 网页首页

- 最近抓拍记录
- 抓拍按钮
- 当前设置
- 远程设置表单
- 图片弹层查看器

## HTTP 接口

### 1. 抓拍图片

```http
GET /snapshot.jpg
```

支持参数：

| 参数 | 说明 |
|---|---|
| `width` | 目标宽度 |
| `height` | 目标高度 |
| `flash` | 是否开启闪光灯，`1/true` 为开启 |
| `delayMs` | 延时多少毫秒后拍照 |
| `rotate` | 输出旋转角度，支持 `0/90/180/270` |

示例：

```http
/snapshot.jpg?width=720&height=1280&flash=1&delayMs=500&rotate=90
```

### 2. 网页首页

```http
GET /
```

### 3. 查看原图

```http
GET /media?name=xxx.jpg
```

### 4. 查看缩略图

```http
GET /media?thumb=1&name=xxx.jpg
```

### 5. favicon

```http
GET /favicon.png
```

## 默认设置

- 默认 HTTP 端口：`8888`
- 默认图片尺寸：`720x1280`
- 默认旋转角度：`90`
- 默认自动对焦：开启
- 默认时间水印位置：右下角

## 图片处理规则

- 输出图片支持旋转
- 输出图片按目标尺寸进行**等比最大化裁剪缩放**
- 可在图片上叠加时间水印
- 水印位置支持：
  - 无
  - 左上角
  - 左下角
  - 右上角
  - 右下角

## 项目结构

```text
app/src/main/java/com/sintrb/webcam/
├── MainActivity.java              # 主界面、相机控制、网页 HTML 输出
├── SimpleHttpCameraServer.java    # 内置 HTTP 服务
├── SettingsActivity.java          # 设置页面
├── AppSettings.java               # 设置持久化
├── ImageUtils.java                # 图片处理
├── PhotoViewerActivity.java       # APP 内大图查看
├── PhotoRecordAdapter.java        # 抓拍记录列表
└── ...
```

## 开发环境

- Android Studio
- JDK 8
- Gradle 6.x
- Android SDK 30
- 最低支持 Android 5.0（API 21）

## 构建方式

### Debug 包

```bash
./gradlew assembleDebug
```

### Release 包

```bash
./gradlew assembleRelease
```

默认输出目录：

```text
app/build/outputs/apk/release/
```

## GitHub Actions 自动发布

仓库已经内置 GitHub Actions 工作流：

- 工作流文件：`.github/workflows/android-release.yml`
- 触发方式：
  - 推送 tag：`v*`
  - 手动触发：`Actions -> Android Release -> Run workflow`

### 自动发布规则

- 如果配置了签名 Secrets：
  - 自动构建签名 release APK
  - 推送 `v*` tag 时自动发布到 GitHub Releases
- 如果没有配置签名 Secrets：
  - 自动构建 debug APK
  - 推送 `v*` tag 时自动发布 debug APK 到 GitHub Releases
  - 同时也会作为 Actions Artifact 上传

### 需要配置的 GitHub Secrets

在仓库 `Settings -> Secrets and variables -> Actions` 中添加：

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

其中：

- `KEYSTORE_BASE64` 为 keystore 文件的 base64 编码内容

示例（本地生成 base64）：

```bash
base64 -i your.keystore | pbcopy
```

然后将复制结果粘贴到 GitHub Secret 中。

### 发布流程建议

1. 配置好上面的 Secrets
2. 提交代码并推送
3. 创建并推送 tag：

```bash
git tag v1.0.0
git push origin v1.0.0
```

4. GitHub Actions 会自动：
   - 构建 APK
   - 创建/更新 GitHub Release
   - 上传 APK 到 Releases

### 关于 debug 签名

如果你暂时没有配置签名 Secrets，工作流会直接使用 debug 构建：

- 输出文件：`app-debug.apk`
- 可用于测试安装
- 不适合作为正式长期发布包

建议：

- 先用 debug 自动发布跑通流程
- 后续再切换到正式签名 release APK

## 签名说明

如果你准备将项目开源，**不要提交你自己的 keystore、密码和私有签名配置**。

建议做法：

- 使用本地 `keystore.properties` 管理签名信息
- 或在 CI / 环境变量中注入签名配置
- 开源仓库中只保留示例配置，不保留真实证书

本项目当前已经改为从仓库根目录的 `keystore.properties` 读取签名信息。

你可以复制示例文件：

```bash
cp keystore.properties.example keystore.properties
```

然后填写：

```properties
storeFile=/absolute/path/to/your.keystore
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

如果本地不存在 `keystore.properties`，则：

- `assembleDebug` 仍可正常构建
- `assembleRelease` 会生成未签名 release 包

## 运行说明

1. 安装 APK 到 Android 手机
2. 授予摄像头权限
3. 启动 APP
4. 查看页面顶部显示的 HTTP 地址
5. 在同一网络内通过浏览器访问：

```text
http://手机IP:8888/
```

## 注意事项

- 首次使用需要摄像头权限
- 某些机型对 Camera2 行为存在差异
- 远程抓拍依赖手机保持前台运行和网络可达
- 经过外网转发访问时，建议使用相对路径资源
- 长时间运行时请注意系统省电策略

## 后续可扩展方向

- HTTPS 支持
- Basic Auth / Token 鉴权
- 多分辨率预设
- 连续抓拍
- 视频流预览
- 上传到对象存储/服务器
- 记录自动清理策略

## License

本项目使用 **MIT License** 开源。

详见根目录下的 [LICENSE](./LICENSE) 文件。
