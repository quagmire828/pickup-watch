# 取件码助手 v2.0 — 架构与构建指南

## 项目概述

专为 **小米手机（Xiaomi HyperOS） + 小米 Vela 穿戴设备（如红米 Watch 6）** 打造的跨端取件码聚合与手表同步工具。
采用小米官方 **`@system.interconnect`（手表端） + `xms-wearable-lib_1.4`（手机端）** 原生蓝牙跨端通信方案，双端统一包名与签名证书，无需开手机热点、无需暴露本地 HTTP 端口，支持双向实时同步与手表端离线缓存。

## 核心架构特性

- **双端统一包名与同源签名**：双端包名统一为 `com.pickupwatch`，共用同一套 RSA 2048 证书（手机端 `.jks` 与手表端 `.pem` 同源派生），通过小米穿戴底层签名校验。
- **手机端 Android 应用（`app/`）**：
  - 集成小米穿戴官方 SDK（`xms-wearable-lib_1.4_release.aar`），通过小米运动健康底层蓝牙通道与手表双向收发数据。
  - `SmsReceiver` 实时捕获新到快递短信，毫秒级解析并推送到手表 + 触发手表通知提醒。
  - `WorkManager` 每日 `07:00 / 13:00 / 18:00` 定时回溯扫描最近 7 天收件箱兜底，并自动清理 7 天前已取记录。
  - `PickupDatabase` Room 单例 + `Flow` 响应式观察，状态变化自动同步手表。
- **手表端 Vela 快应用（`watch/pickup-codes/`）**：
  - 针对红米 Watch 6（`432 × 514` AMOLED 窄边方屏，`designWidth: 432`）深度适配纯黑高对比大字号卡片界面。
  - 支持 **`@system.storage` 本地持久化缓存**：即使下楼忘带手机，抬腕也能秒开查看已缓存的取件码。
  - 支持 **手表端直接标记「已取」**（二次点击防误触 + 短震动反馈）：在线时实时同步回手机数据库，离线时记入待同步队列并在重连后自动补发。
  - 支持 **手表端点击「扫描刷新」** 远程触发手机重新扫描短信收件箱。

## 目录结构

```text
pickup-watch/
├── app/
│   ├── libs/
│   │   └── xms-wearable-lib_1.4_release.aar  ← 小米穿戴第三方应用通信官方 SDK
│   └── src/main/java/com/pickupwatch/
│       ├── bridge/
│       │   ├── WatchBridge.kt                ← 小米穿戴 Interconnect 双向蓝牙通信管理
│       │   └── WatchBridgeService.kt         ← 蓝牙监听前台守护服务
│       ├── receiver/
│       │   └── SmsReceiver.kt                ← 新短信实时监听解析与手表推送
│       ├── scanner/
│       │   ├── PickupParser.kt               ← 取件码解析引擎（移植自 pain505/1, MIT）
│       │   └── SmsScanner.kt                 ← 短信收件箱回溯扫描
│       ├── data/
│       │   └── PickupDatabase.kt             ← Room 单例数据库 + Flow 实时数据流
│       ├── worker/
│       │   ├── ScanWorker.kt                 ← WorkManager 每日 7:00/13:00/18:00 定时扫描
│       │   └── BootReceiver.kt               ← 开机自启注册
│       ├── notify/
│       │   └── PickupNotify.kt               ← 手机通知栏汇总提醒
│       └── ui/
│           ├── MainActivity.kt               ← 手机主界面（实时状态栏 + 扫描 + 同步唤醒手表）
│           └── PickupAdapter.kt              ← 列表卡片适配器
└── watch/pickup-codes/
    ├── package.json                          ← Vela 工具链配置 (aiot-toolkit 2.0.5)
    └── src/
        ├── manifest.json                     ← Vela 清单 (com.pickupwatch, designWidth: 432)
        ├── app.ux                            ← 应用生命周期入口
        ├── common/logo.png                   ← 192×192 应用图标
        └── pages/index/index.ux              ← 手表端主页面（离线缓存 + 蓝牙同步 + 二次确认已取）
```

## 本地签名与编译说明

> 出于安全考虑，签名私钥（`keystore/` 与 `watch/pickup-codes/sign/`）已通过 `.gitignore` 排除，不纳入公开版本库。首次克隆仓库构建时请先生成一套你自己的双端同源证书：

### 1. 生成双端同源签名证书
```bash
mkdir -p keystore watch/pickup-codes/sign/debug watch/pickup-codes/sign/release

# 1) 生成 Android JKS 密钥库
keytool -genkeypair -v -keystore keystore/pickupwatch.jks -storetype JKS \
  -alias pickupwatch -keyalg RSA -keysize 2048 -sigalg SHA256withRSA -validity 10000 \
  -storepass YOUR_PASSWORD -keypass YOUR_PASSWORD \
  -dname "CN=PickupWatch, OU=Dev, O=PickupWatch, L=City, ST=State, C=86"

# 2) 写入 keystore/keystore.properties
cat > keystore/keystore.properties <<EOF
storeFile=keystore/pickupwatch.jks
storePassword=YOUR_PASSWORD
keyAlias=pickupwatch
keyPassword=YOUR_PASSWORD
EOF

# 3) 导出 Vela RPK 所需的 certificate.pem 与 private.pem
keytool -importkeystore -srckeystore keystore/pickupwatch.jks -srcstoretype JKS -srcstorepass YOUR_PASSWORD \
  -destkeystore keystore/pickupwatch.p12 -deststoretype PKCS12 -deststorepass YOUR_PASSWORD -noprompt
openssl pkcs12 -nodes -in keystore/pickupwatch.p12 -passin pass:YOUR_PASSWORD -nocerts | openssl pkey > keystore/private.pem
openssl pkcs12 -nodes -in keystore/pickupwatch.p12 -passin pass:YOUR_PASSWORD -clcerts -nokeys | openssl x509 > keystore/certificate.pem

cp keystore/certificate.pem keystore/private.pem watch/pickup-codes/sign/debug/
cp keystore/certificate.pem keystore/private.pem watch/pickup-codes/sign/release/
```

### 2. 编译手机端 APK
```bash
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

### 3. 编译手表端 RPK（需 Node.js >= 20）
```bash
cd watch/pickup-codes
npm install
npx aiot release
# 产物：watch/pickup-codes/dist/com.pickupwatch.release.2.0.1.rpk
```

## 安装与权限设置（小米澎湃 OS）

1. 手机安装 APK 后授予「读取短信」「接收短信」「通知」权限。
2. 进入手机「设置 → 应用设置 → 取件码助手 → 权限管理」，务必开启 **「通知类短信」** 读取权限，并将省电策略设为 **「无限制 / 允许自启动」**。
3. 首次打开时允许小米运动健康的穿戴设备管理与通知授权。
4. 使用「表盘自定义工具」将编译出的 `.rpk` 安装到手表即可双向同步。
