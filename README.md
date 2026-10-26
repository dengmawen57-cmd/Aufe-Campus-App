# 安财校园通（Aufé Campus App）
安徽财经大学校园数据聚合 App：一次登录（VPN + 本科教务系统），查看**课程表**、**选课**、**一卡通**、**宿舍电费**、**成绩查询**、**第二课堂成绩单**与**培养方案**，并提供**今日课程 / 一卡通余额桌面小卡片**与**上课通知提醒**。

## 下载安装

- **APK**：`安财校园通-v1.5.1.apk`（约 16 MB，Android 7.0+ / minSdk 24）
- 安装时如提示"未知来源应用"，请允许安装
- 首次打开需要登录：VPN 账号/密码 + 教务系统账号/密码（默认两者相同，可取消勾选分别填写）
- 应用图标：蓝底（#0B5FBF）+ 白色学士帽（自适应图标，各 Android 版本一致呈现）

## 功能分区

| 页面 | 内容 | 数据来源 |
|------|------|----------|
| 登录页 | VPN 门户 + 统一身份认证 + 教务系统一次完成，内嵌 WebView 四步进度展示；**登录成功后自动预热 OkHttp 静默通道** | VPN/CAS |
| 课表 | 周网格课表（周一~周日 × 第1~11节，按周次过滤，今日列高亮）+ 课程明细卡片；**左侧节次列显示具体时间**（如"第1节 08:00-08:45"）；**顶部信息栏内嵌一卡通余额条**（点击进一卡通页，获取失败可点击重试）；点击课程块/卡片弹出底部详情卡；支持切换第 1~20 周 | 本科综合教务系统 |
| 选课 | 方案内选课 / 自由选课 / 已选课程三个页签；关键词搜索；多选购物车；确认弹窗后提交；非选课阶段显示提示 | 本科综合教务系统 |
| 一卡通 | **校园卡余额**（独立加载，约 0.1 秒立即可见）+ **消费明细**（按日期范围查询，默认本月，支持近 7 天 / 近 3 个月快捷选择；收支分色，显示每笔后余额、商户、终端号） | 一卡通 WEB 系统 |
| 成绩单 | 第二课堂总分、评定、个人信息；**学分明细按课组可展开**（分数圆标 + 组名 + "X 项记录"，展开显示每条记录的类别/项目/分值，即分数具体来源）；工作履历、获奖信息两个记录区 | 爱安财（第二课堂平台） |
| 成绩查询 | **本科教务系统全部成绩**：已修学分/课程门数/通过门数/学期数汇总，按学期分组展示每门课的学分、绩点、成绩徽章、课程属性、考试类型、未通过原因，支持分页加载更多 | 本科综合教务系统 |
| 宿舍电费 | **楼栋 → 楼层 → 房间三级下拉选择**（选择后自动记忆，下次进入自动定位）；**剩余电量**（购电 + 补助拆分）；**用电记录**（时间/度数/电表）与**缴费记录**（时间/金额/类型） | 后勤电费系统（ISIM） |
| 我的 | 用户信息、数据概览、**一卡通**、**宿舍电费**、**上课提醒**、培养方案入口、成绩查询入口、关于应用（含制作信息与反馈邮箱）、退出登录 | — |
| 培养方案 | 方案总览（总门数/学分/课时）+ 课组树（已通过/需修学分进度条、缺修门数提醒） | 本科综合教务系统 |
| 桌面小卡片 | **① 今日课程**：显示当天日期与当天全部课程（节次 + 地点）；课表加载成功后自动刷新；每 30 分钟定时刷新；点击打开 App。**② 一卡通余额**（v1.4.1 新增）：显示校园卡余额与更新时间；点击卡片直达一卡通页，点"刷新"实时查询余额；课表/一卡通页加载余额时自动同步 | 本科综合教务系统 / 一卡通 |
| 上课提醒 | 后台定时检查课表，**上课前按设定的分钟数（5/10/15/20/30/60 分钟可选）发送系统通知**；同一课程同一天只提醒一次；重启手机后自动恢复 | 本科综合教务系统 |

## 技术实现

- **原生 Android**：Kotlin + Jetpack Compose + Material 3，安财蓝主题
- **双通道登录**：
  - **主通道 WebView**：自动完成深信服 SSL VPN 门户登录 → CAS 统一身份认证 → 教务/爱安财 SSO，Cookie 导出给 OkHttp 持久化，重启 App 自动恢复会话
  - **静默通道 OkHttp**（v1.4.0 新增）：`VpnConnection` 实现双级登录——① VPN 门户 RSA 加密密码（`por/login_auth.csp` 取 TwfID/RSA 公钥/CSRF 码 → `por/login_psw.csp` 提交）② CAS 统一认证 DES 加密密码（取 lt/execution → POST）。会话过期时后台自动重登，用户无感
  - 加密用 Android 自带 `javax.crypto`（RSA/ECB/PKCS1Padding、DESede/ECB/PKCS7Padding），与 BouncyCastle 结果一致但**不引入额外依赖**（省约 2MB）
- **Cookie 分层隔离**（v1.4.0 引入、v1.5.1 修正）：
  - VPN 主域下发的全局凭据（`TWFID` / `CASTGC` / `sinforsid`）提升为 `domain=vpn2.aufe.edu.cn`，所有子域共享——解决一卡通请求不带 TWFID 返回登录页的问题
  - 各子域的 `JSESSIONID` 等**保持 hostOnly 并按来源 host 分桶**，同名互不覆盖——否则先访问一卡通/电费会覆盖教务的 JSESSIONID，导致选课接口 HTTP 500（v1.5.1 修复）
  - 请求时合并顺序：本 host → VPN 主域 → 其他子域（仅补充不重名）
- **会话失效双保险**：① 请求读到登录页 → 抛 `SessionExpiredException`，UI 明确提示；② 任意请求用 `peekBody` 嗅探到登录页 → 回调 `onSessionExpired` 触发静默重连；另有每 4 分钟心跳探活。静默重连也失败时才回登录页
- **一卡通**：查询前先 `GET /casLogin.jsp` 用 VPN 会话换取一卡通自身 SSO Cookie（此前缺失此步导致余额为空）；余额与消费明细**分开加载**（余额 0.1s 立即可见，明细走 150 秒慢速客户端）
- **宿舍电费**（v1.5.0 新增）：主机 `hqkd-aufe-edu-cn.vpn2.aufe.edu.cn` **无 :8118 端口**（与其它系统不同）；`GET /go?openid=学号&sn=sn` 建立会话 → `GET /about` 取楼栋 → `/about/floors/{码}`、`/about/rooms/{码}` 取楼层房间（返回 JS 对象字面量，键无引号，需补引号后 `org.json` 解析）→ `POST /about/rebinding` 绑定房间 → 并发拉 `/use/record`（用电+余额）与 `/pay/record`（缴费）。余额用"上下文窗口"策略解析（数值行上下 3 行找"购电/补助"），比固定选择器更耐页面微调
- **网络层**：OkHttp 4.12 + 持久化 CookieJar，全部走 VPN 子域代理端点；双客户端（默认 30 秒读超时 / 慢速 150 秒）
- **上课提醒**：AlarmManager `setInexactRepeating`（RTC_WAKEUP，2 分钟周期）→ BroadcastReceiver → NotificationCompat 通知；按 `日期|课程号|节次` 去重；监听 `BOOT_COMPLETED` 开机自恢复；Android 13+ 运行时申请 `POST_NOTIFICATIONS`
- **架构**：单 Activity + Navigation Compose；CampusViewModel（StateFlow）驱动各页面状态；Api → Repository → ViewModel → Screen 分层

## 本次修复的 Bug（v1.4.0）

| # | 问题 | 根因 | 修复 |
|---|------|------|------|
| 1 | 选课提交后课表里没有新选的课 | `submitSelection()` 中 `kcms` 参数误用 `names.map { it.code }`，把课程名按**字符**取 Unicode 码点，发出 `39640,31561,...` 乱码 | 改为 `kcms = "$names,"`（课程名字符串本身 + 尾逗号） |
| 2 | 一卡通余额一直显示 ¥0.00 | ① VPN 的 TWFID Cookie 是 hostOnly，不会发给 `ykt-*` 子域 → 返回登录页；② 查询前未调用 `casLogin.jsp` 换取一卡通 SSO Cookie；③ 解析失败时静默返回 0.0 | ① Cookie 提升到 vpn2 主域共享；② `YktApi.initSession()` 初始化 SSO；③ 解析失败抛明确异常，UI 显示错误而非 ¥0.00 |
| 3 | 会话过期后各页面表现不一致 | 登录页检测只在 `getText/postFormText` 中做，其它请求路径漏检 | `AufeClient` 统一 `execute()` 入口 + `peekBody` 嗅探 + 过期回调，两条路径互补 |
| 4 | 会话过期必须手动重新登录 | 无后台重连能力 | 新增 OkHttp 双级登录 + 心跳 + 静默重连，过期自动重登 |
| 5 | 选课中心报 **HTTP 500**（v1.5.1 修复） | v1.4.0 把所有 `*.vpn2` 子域 Cookie 塞进同一桶，教务 / 一卡通 / 电费各自的 `JSESSIONID` 同名互相覆盖——先看一卡通或刷新余额小卡片后，教务的 JSESSIONID 被电费的顶掉，选课接口即 500 | Cookie 改为**按来源 host 分桶**，子域 Cookie 保持 hostOnly（仅 VPN 主域的 TWFID 等跨域共享）；升级时一次性清理来源不明的 JSESSIONID 并保留登录凭据（无需重登）；另加"首次 500 → 访问教务入口页刷新会话 → 自动重试一次" |

## 源码结构

```
AufeCampusApp/
├── app/src/main/java/cn/aufe/campus/
│   ├── CampusApp.kt                 # Application（持有 AuthManager）
│   ├── MainActivity.kt              # NavHost（login → main → plan → scoreQuery → ykt）
│   ├── auth/                        # AuthManager（会话/静默重连/心跳）+ LoginController（WebView 状态机）
│   ├── data/
│   │   ├── model/Models.kt          # 全部数据类（含 ScoreRecord / CardBalance / TransactionRecord）
│   │   ├── net/                     # Endpoints + AufeClient（会话嗅探）+ CookieStore（VPN 子域共享）
│   │   │                            # + VpnCrypto（RSA/DES）+ VpnConnection（OkHttp 双级登录）
│   │   ├── api/                     # JwApi / YktApi / DektApi
│   │   └── CampusRepository.kt      # 挂起函数仓库
│   ├── util/ScheduleUtils.kt        # 节次时间表 / 周次解析（课表、小卡片、提醒共用）
│   ├── widget/                      # ScheduleWidget + WidgetDataStore + ClassReminderReceiver
│   └── ui/                          # login / main / schedule / select / ykt / score / plan / profile
└── app/build.gradle.kts             # AGP 8.1.4 + Kotlin 1.9.22 + Compose BOM 2024.02.00
```

## 构建方式

```bash
# 环境要求：JDK 17 + Android SDK（platforms;android-34、build-tools;34.0.0）

# 1) 在本机项目根目录创建 local.properties，写入你的 SDK 路径（源码包已排除此文件，避免路径冲突）
#    Windows:  sdk.dir=D\:\\Android\\Sdk       （用 Android Studio 打开项目也会自动生成）
#    macOS:    sdk.dir=/Users/你的用户名/Library/Android/sdk
#    Linux:    sdk.dir=/home/你的用户名/Android/Sdk

cd AufeCampusApp
# 2) 构建
./gradlew assembleDebug        # macOS / Linux
gradlew.bat assembleDebug      # Windows（或在 Android Studio 中直接 Run）

# 产物：app/build/outputs/apk/debug/app-debug.apk
```

首次构建需联网下载依赖（Gradle 8.5 / AGP 8.1.4 / Kotlin 1.9.22 / Compose BOM 2024.02.00 / OkHttp 4.12），国内网络建议为 Gradle 配置镜像源（如阿里云 maven）以加速。直接用 Android Studio（Iguana 及以上）"File → Open" 打开项目根目录即可自动识别并构建。

## 使用说明与注意事项

1. **登录**：输入学号与密码（VPN 与教务系统默认同账号，不同则取消勾选分别输入）。登录过程约 10~30 秒，四步进度实时显示。**勾选"记住密码"后**，App 才能在会话过期时后台静默重登。
2. **会话保持**：Cookie 保存在本机，重开 App 无需重复登录。若会话过期：有保存密码 → 后台自动重登（无感）；无保存密码或重登失败 → 提示并回登录页。
3. **一卡通**：入口有三处——**课表页顶部余额条**（点击进一卡通页）、**"我的 → 一卡通"**、**桌面余额小卡片**。余额**立即显示**；消费明细需点"查询消费明细"手动触发（学校该接口响应约数十秒，已配置 150 秒超时）。支持自定义起止日期，或用"近 7 天 / 本月 / 近 3 个月"快捷填充。
   - **添加余额小卡片**：桌面长按 → 小部件 → 选择"安财校园通"（列表中两个：今日课程 / 一卡通余额）。卡片上的"刷新"会实时查询余额；系统每 30 分钟也会自动重绘。
4. **宿舍电费**：从"我的 → 宿舍电费"进入。第一次需依次选择**楼栋 → 楼层 → 房间**（后一级依赖前一级），选完自动查询剩余电量、用电记录与缴费记录；选择会被记住，下次打开自动定位到上次的房间。如列表为空，先确认已登录且 VPN 会话有效（电费系统需复用 VPN 会话）。
4. **第二课堂成绩单**：每个课组显示"分数圆标 + 组名 + X 项记录"，点击可展开/收起；**有记录的课组默认展开**。工作履历、获奖信息为独立记录区。
5. **课表**：左侧节次列标注上下课时间（第 1 节 08:00-08:45，第 11 节 20:50-21:35）；点击课程块或下方卡片弹出详情卡；周次按 2026 秋季学期（2026-09-07 开学）推算，可手动切换。
6. **成绩查询**：从"我的 → 成绩查询"进入；按学期分组，顶部汇总为学分来源明细。
7. **上课提醒**：从"我的 → 上课提醒"进入，开关并选择提前分钟数（默认 10 分钟）。Android 13+ 首次开启会申请通知权限。
8. **桌面小卡片**：桌面长按 → 小部件 → "安财校园通"；打开课表页时自动同步数据。
9. **关于应用**：注明本应用由26信工1班邓马文和mawenbot共同完成，可一键发邮件至反馈邮箱 **1964689817@qq.com**。
10. **数据安全**：所有凭据与 Cookie 仅存储于本机 SharedPreferences，不经过任何第三方服务器。

## 已知限制

- 选课提交参数按教务系统现行规则构造（dealType 2=方案内 / 5=自由），如学校升级选课接口需同步调整 `JwApi.submitSelection`
- VPN 门户若改版（RSA 参数名变化），OkHttp 静默通道会失效，**但 WebView 主通道不受影响**，仅退化为"过期需手动重登"
- 一卡通消费明细为服务端慢接口（数十秒），已配 150 秒读超时，仍需耐心等待
- 桌面小卡片只展示"今天"的课程，暂不支持在小卡片上切换周次
- 爱安财成绩单依赖登录时获取的 token，token 过期后需重新登录
- 上课提醒依赖课表数据已加载（打开一次课表页即写入本地）
- 电费楼栋/楼层/房间列表依赖系统返回的 JS 字面量格式，若学校改版导致列表为空，需按真机返回修正 `IsimApi.parseCodeName` 的正则
- 电费余额解析采用上下文窗口策略，如显示 0.00 但实际有电量，可抓取 `/use/record` 页面调整 `parseBalance` 的关键词与窗口大小
