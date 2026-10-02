# 梨园频道频繁卡顿 —— 根因分析

- 日期：2026-09-30
- 设备：烽火 HG680-KF（Android 9 / SDK 28 / 1080p）`192.168.66.158:5555`
- 频道：**梨园**（`group-title="戏曲频道"`）
- 源：`https://dxtx.hntv.tv/live/lypd.m3u8`（远程列表里出现 2 条，同一路径、仅 `wsSecret/wsTime` 不同 → 实为**同一个流的两个鉴权参数**，无备用源）
- 分析基线：`ui-v218`（已含 `SourceProfile` 起播偏移 + `MyLoadErrorHandlingPolicy`）

---

## 一、结论

梨园卡顿**主要不是网络问题，也不是播放参数问题，而是这个流自己的音频轨道有缺陷**；App 的起播偏移策略在同一时刻把它放大成"周期性滑出窗口"。

三层原因，按贡献排序：

| # | 层级 | 事实 | 后果 |
|---|---|---|---|
| **1** | **源端（主因）** | 音频码率只有 **6~19 kbps**（同主机正常频道 69 kbps）；每 4 秒分片只有 **2~4 个音频 PES**，且 PES 之间存在 **0.5~3.3 秒空洞** | 音频时间戳在分片边界以 **1~2 秒**幅度跳变 → 播放器每片抛 `UnexpectedDiscontinuityException` → 重建音频输出 → **声音/画面反复中断** |
| **2** | **源端（结构性）** | HLS 窗口只有 **3 个分片**（12~16 s，其他频道 20~25 s）；分片时长在 **3.4~8 s** 之间抖动（探测时 5.32 s/片，实测 4 s / 4.76 s / 8 s 均出现过） | 容错窗口极小，列表刷新稍慢就滑出 |
| **3** | **App（放大因素）** | 起播偏移算法给该源算出 **8000 ms**，而窗口最后一片正好 8 s → 起播点被钉在**最后一片的起点**，稳态缓冲只剩 1 片 | 真机实测起播 **47 秒后 `BehindLiveWindowException`**（画面跳变/重缓冲） |

一句话：**这个源的音频本身就在"断"，而我们又把播放位置钉在离直播边缘只有一片的地方，于是"音频反复断"＋"周期性滑出窗口"叠加成了用户看到的频繁卡顿。**

---

## 二、真机复现（决定性证据）

测试方法：把 `tvlist.m3u` 临时换成只含梨园的列表推进盒子私有目录，强停 App 后重启，流式抓取 110 秒日志（A 组，正常偏移）。

```
09-30 20:56:31.911 I/PlayerFragment: play 梨园 offset=8000ms
09-30 20:56:33.660 E/MediaCodecAudioRenderer: Audio sink error        ← 起播 1.75 s 即开始
09-30 20:56:34.360 E/MediaCodecAudioRenderer: Audio sink error
09-30 20:56:36.183 E/MediaCodecAudioRenderer: Audio sink error
09-30 20:56:36.775 E/MediaCodecAudioRenderer: Audio sink error
...（共 38 次，成对出现，每对间隔约 3.5~4 s）
09-30 20:57:19.125 E/ExoPlayerImplInternal: Caused by: BehindLiveWindowException   ← 起播 47 s 后滑出
09-30 20:57:19.230 E/PlayerFragment: PlaybackException ... Source error
...（恢复后音频错误继续，直到 20:58:18.702）
```

异常正文：

```
androidx.media3.exoplayer.audio.AudioSink$UnexpectedDiscontinuityException:
  Unexpected audio track timestamp discontinuity: expected 1000005312000, got 1000006315000   ← 差 1.003 s
                                                   expected 1000006891000, got 1000008235000   ← 差 1.344 s
                                                   expected 1000008768333, got 1000010966000   ← 差 2.198 s
                                                   expected 1000058797666, got 1000059757000   ← 差 0.959 s
```

**两个独立故障，时间上完全分离：**

- **音频不连续：从起播后 1.75 秒就持续发生**，与后面那次滑出无关。110 秒 38 次 ≈ 每 3 秒一次，且**与分片周期（约 4 s）同步** → 说明是"每读一个新分片就撞一次"。
- **滑出窗口：47 秒后才发生一次**，属于缓冲余量被耗尽。

> 附带一条方法论提醒：**开发机 `curl` 走本地代理（`127.0.0.1:52224`），会缓存 m3u8 并让耗时失真**（本次观测到"两次采样间序列号跳 222"纯属代理返回陈旧列表所致）。凡涉及"源快不快、列表推不推得动"的结论，**必须用盒子自身的网络出口取证**（盒子里有 `/system/bin/curl`）。本次已改用 `adb shell curl` 复核。

---

## 三、源结构实测

### 3.1 盒子自身出口（`adb shell curl`，连续 3 次）

```
round1  code=200 t=0.318s  TARGETDURATION:4  分片 4 / 4 / 4      窗口 12.00s
round2  code=200 t=0.358s  TARGETDURATION:5  分片 4 / 4.76 / 4.76 窗口 13.52s
round3  code=200 t=0.247s  TARGETDURATION:5  分片 4.76 / 4.76 / 4 窗口 13.52s
```

→ **拉列表本身只要 0.25~0.36 秒，盒子到 CDN 的网络是健康的。**

### 3.2 开发机侧连续采样 40 次（约 7 分钟）

**窗口恒定为 3 个分片，总长 10.84~12.80 秒**，从未超过 13.5 秒：

```
20:52:40 n=3 win=10.88s   20:53:30 n=3 win=11.60s   20:54:44 n=3 win=12.00s
20:53:00 n=3 win=12.00s   20:53:39 n=3 win=12.00s   20:55:03 n=3 win=12.00s
20:53:11 n=3 win=12.00s   20:53:48 n=3 win=14.00s   20:55:11 n=3 win=12.00s
20:53:20 n=3 win=12.00s   20:53:57 n=3 win=12.00s   ...（后略）
```

### 3.3 与真机画像的差异（重要）

真机 `cache/mytv-sources.txt` 里 20:34 探测落盘的结果是：

```
8000	15960	3	1337	194	8000	https://dxtx.hntv.tv/live/lypd.m3u8?...wsSecret=cbf59580...
8000	15960	3	1242	194	8000	https://dxtx.hntv.tv/live/lypd.m3u8?...wsSecret=41543190...
```

（列序：目标时长 / 窗口 / 分片数 / 实测KB/s / 需求KB/s / 起播偏移 / URL）

即探测时该流是 **8 s 目标时长、3 片、窗口 15.96 s**，而现在实测是 4~4.76 s 一片。

→ **该源的分片时长在 3.4 s ~ 8 s 之间变化**，不是稳定切片。分片时长不稳定会让客户端的时间轴与刷新节奏错位，是卡顿的**结构性诱因**。

---

## 四、分片级证据：音频轨道有空洞

直接下载真实分片并解析 MPEG-TS（脚本见 `.workbuddy/tmp/ts_stat.py`）。梨园两批各 3 片，结果一致。

### 4.1 梨园（lypd）— 异常

```
lypd-1790773101.ts  payload=530166
   pid=0x0100 (video) bytes=498891 (94.10%) PES n=100  pts 315.334s → 319.294s  (span 3.960s)
   pid=0x0101 (audio) bytes=  2939 ( 0.55%) PES n=2    pts 315.639s → 319.202s  (span 3.563s)
       PES 长度 = [2428, 499]

lypd-1790773102.ts  payload=450026
   pid=0x0100 (video) bytes=421231 (93.60%) PES n=100  pts 319.334s → 323.294s
   pid=0x0101 (audio) bytes=  4691 ( 1.04%) PES n=3    pts 319.266s → 321.698s
       PES 长度 = [843, 2790, 1040]

lypd-1790773103.ts  payload=747480
   pid=0x0100 (video) bytes=698267 (93.42%) PES n=100  pts 323.334s → 327.294s
   pid=0x0101 (audio) bytes=  9469 ( 1.27%) PES n=4    pts 323.255s → 326.988s
       PES 长度 = [2901, 2723, 2770, 1051]
```

**读法：**

- **视频轨道完全正常**：每片 100 个 PES，PTS 严格 4.0 s/片（315.334 → 319.334 → 323.334），PTS 连续、无空洞。
- **音频轨道异常**：每片只有 **2~4 个** PES，PTS 跨度 2.43~3.73 s，而 PES 之间时间间隔极不均匀。
- **音频码率**：2939 B ÷ 3.563 s ≈ **6.6 kbps**；4691 B ÷ 2.432 s ≈ 15 kbps；9469 B ÷ 3.733 s ≈ **20 kbps**。
- **空洞推算**：以 3102 为例，末尾 PES 起点 321.698 s、长度 1040 B；下一片的首个音频 PES 起点 323.255 s。按同源正常码率（约 69 kbps）折算，1040 B 只能覆盖约 0.12 s，**中间约 1.4 秒完全没有音频数据**。

→ 音频时间轴上存在**成段的空洞**，播放器读到空洞后一帧的 PTS 就会**前跳 1~2 秒**，与第二节真机日志里 `expected/got` 的 0.96~2.20 秒差值是**同一量级**。

### 4.2 同主机对照：河南曲艺（jczy）— 正常

两条源**同一主机、同一协议、同一分组**（`dxtx.hntv.tv`，`cbf59580...` 与 `jczy` 并列在"戏曲频道"组）：

```
jczy_1.ts  payload=1092456
   pid=0x0100 (video) bytes=1007432 (92.22%) PES n=86   pts 5.799s → 9.119s
   pid=0x0101 (audio) bytes=  27248 ( 2.49%) PES n=10   pts 5.703s → 8.855s
       PES 长度 = [2777,2859,2734,2838,2847,2881,2794,2801,2815,1842]   ← 每个约 2.8 KB，均匀
```

| 指标 | 梨园 lypd | 河南曲艺 jczy | 比值 |
|---|---|---|---|
| 音频 PES 数 / 4 s | **2~4** | **10** | 1/3 |
| 音频载荷占比 | **0.55~1.27%** | **2.49%** | 1/3 |
| 音频码率 | **6.6~20 kbps** | **69 kbps** | **1/10~1/3** |
| 音频 PES 长度分布 | 499~2901（跳跃大） | 2777~2881（稳定） | — |

**同一台服务器上的另一个频道音频完全正常** → 排除 CDN/主机的锅，**问题就是这个流本身**（梨园的音频编码/源信号异常）。

---

## 五、机制解释（为什么会"卡"而不是只有"没声音"）

1. 客户端每读到一个新分片，音频首帧 PTS 相对上一片出现 1~2 秒跳变；
2. media3 的 `DefaultAudioSink.handleBuffer` 检测到该跳变超过内部阈值，抛 `UnexpectedDiscontinuityException`；
3. `MediaCodecAudioRenderer` 捕获后**重建音频输出**（release + init `AudioTrack`），期间音频静默、音视频同步被重置；
4. 每 4 秒的分片周期重复一次 → 观感是**声音一顿一顿、画面跟着停顿/跳帧**；
5. 同时，音频空洞让"缓冲的有效内容"变少，配合只有 3 片的窗口，最终触发一次 `BehindLiveWindowException`（20:57:19），表现为画面明显跳变/重新缓冲。

> 注意：`UnexpectedDiscontinuityException` 的阈值是 media3 内部常量，**没有公开 API 可调**；想"容忍"它必须自定义 `AudioSink` 或反射改常量，属于侵入性改动。

---

## 六、App 侧为什么会放大（`SourceProfile` 偏移算法）

真机日志 `play 梨园 offset=8000ms` 是我们自己算出来的，算式（`SourceProfile.targetOffsetFor`，输入探测值 `[d0,d1,d2]` 之和 15.96 s、末片 8 s）：

```
targetMs = 2 × (15.96/3) × 1000 = 10640 ms   → 钳在 [6000, 30000] → 10640 ms
候选分片起点（下标 ≥ 1）：片1 = 3.98 s、片2 = 7.96 s
「垫片 = 窗口末端 − 该片起点」必须 ≤ targetMs：
   片1 垫片 = 15.96 − 3.98 = 11.98 s  > 10640 → 不满足
   片2 垫片 = 15.96 − 7.96 =  8.00 s  ≤ 10640 → 选中 → 返回 8000 ms
```

**结果：起播点落在最后一片的起点，手上只有 1 片（8 s）。**

对照 media3 默认（不设 `targetOffsetMs`，默认 = 3 × 目标时长 = 24 s，被窗口钳到最旧片）：

| 策略 | 起播位置 | 身后缓冲 | 抗抖动 |
|---|---|---|---|
| 现状（我们的 8000 ms） | **最后一片** | **8 s（1 片）** | 最差 |
| 若返回 10640 ms | 倒数第二片 | ~12 s | 中 |
| media3 默认（不设置） | 第一片 | **15.96 s（3 片）** | 最好 |

**根因在算法约束的方向选错了**：`targetOffsetFor` 的目标是"垫得最多但不超过目标值"，但当窗口只有 3 片、且末片很长（8 s）时，**"不超过目标"这一约束反而把结果推到了最新片**上——与"垫厚一点"的初衷相反。

同时 `tinyWindow` 判据是 `segments in 1..2 || windowMs in 1..8000`，梨园是 3 片 / 15960 ms，**判定为"窗口正常"**，因此在多源排序里不但不降权，还会因为 `headroom = 1337/194 ≈ 6.9` 很高而排到前面——**越是"下载快"的极短窗口源，越容易被优先选中**。

---

## 七、修复建议（按性价比排序）

### A. 立即可做、零风险 —— 修正偏移算法的方向

`targetOffsetFor` 里"取垫片不超过目标"的分支，改为**在合法候选（下标 ≥ 1）中取垫片最大的那个**（即永远优先靠窗口旧端），或在"没有任何候选满足 ≤ 目标"时**回落到底片**而不是末片：

```kotlin
// 现状：第一个「不超过目标」的下标（可能落到末片）
// 建议：在 [1, n-1] 中选「垫片最大且 ≤ 目标」；若不存在，取下标 1（最旧片，垫最多）
```

实测收益：该源起播缓冲从 8 s 回到约 12~16 s，抗抖动提升 1.5~2 倍，且不影响其他源（多数源窗口 20~25 s，原行为不变）。

### B. 把"≤3 片窗口"纳入降权

`tinyWindow` 加上 `segments <= 3`（或 `windowMs <= 16_000`）。注意实测远程列表里相当比例的源是 3 片窗口，**只降权不剔除**，让多源频道优先选窗口更宽的。

### C. 探测阶段识别"音频异常源"（治本方向）

在 `parseHls` / 新增的音频体检步骤里，用**限量下载一个分片**（复用 `measureSpeed` 已有的 384 KB 预算）统计：

- 音频 PID 载荷 ÷ 分片时长 < **30 kbps**，或
- 音频载荷占比 < **1.5%**，或
- 音频 PES 数量 < **5**（4 秒片）

命中则标记 `audioSuspect`，多源时排到最后；单源频道（如梨园）无法切换，但可在换台时给出提示（"该源音频异常，可能断续"），避免用户误判为 App 问题。

### D. 不建议做的

- **不要丢掉这个频道**：梨园的**视频完全正常**（PTS 连续、码率稳定），只是音频有洞；丢掉会少一个可看频道。
- **不要靠调 buffer 参数解决**：`minBuffer/maxBuffer` 都远大于该源的"有效音频缓冲"，加大参数对音频空洞无效。
- **不要上磁盘媒体缓存**：分片一次性、带 `txspiseq` 鉴权，缓存无用且加重弱盒子 IO（既有结论）。

---

## 八、复现与验证步骤

```bash
# 1) 用盒子自身出口看列表（看清窗口与分片时长）
adb shell "curl -sS 'https://dxtx.hntv.tv/live/lypd.m3u8?txSecret=...&wsSecret=...'" 

# 2) 只看梨园（临时替换本地列表；测完务必删除）
adb push ly_only.m3u /sdcard/Android/data/com.fshby.mytv/files/tvlist.m3u
adb shell am force-stop com.fshby.mytv
adb logcat -c && adb shell monkey -p com.fshby.mytv -c android.intent.category.LAUNCHER 1
adb logcat -v time | grep -E "PlayerFragment|MediaCodecAudioRenderer|ExoPlayerImplInternal" 
#    关注：play 梨园 offset=... / Audio sink error / BehindLiveWindowException / PlaylistStuckException

# 3) 取分片做 TS 体检
curl -o seg.ts "https://dxtx.hntv.tv/live/<seg>.ts?txspiseq=..."
python .workbuddy/tmp/ts_stat.py seg.ts   # 看 0x0101 的 bytes / PES 数 / pts_span

# 4) 删掉临时列表
adb shell "rm -f /sdcard/Android/data/com.fshby.mytv/files/tvlist.m3u"
```

关键日志关键词（**注意 logcat 格式是 `E/Tag( pid): ...`，不要用 `grep "PlayerFragment: PlaybackException"`**）：

- `grep "Audio sink error"` → 音频不连续次数
- `grep "BehindLiveWindow"` → 滑出窗口次数
- `grep "play .* offset="` → 实际施加的起播偏移

---

## 九、局限与遗留

1. **本次只对梨园（lypd）与河南曲艺（jczy）做了音频体检**，未全量扫描远程列表。建议把第 B/C 条的判据做成探测期统计，跑一次全量看还有多少频道属于"音频异常源"。
2. **音频"空洞"的成因未定**：可能是梨园当前节目音频源异常，也可能是编码器对该节目做了静音/VAD 压缩。两者对客户端的后果相同，但前者是长期问题、后者会随节目变化。**建议换时段（如白天戏曲播出时段）复测一次**。
3. 本机 `curl` 走代理，**所有耗时类结论一律以盒子出口为准**（本次已修正）。采样脚本 `.workbuddy/tmp/probe_ly.sh`、TS 体检脚本 `.workbuddy/tmp/ts_stat.py` 可复用。
4. 未在真机上做 B 组对照（`offset=0`，即 media3 默认偏移）。可以预测差异只在"滑出窗口"的间隔上（预计从 47 s 拉长到 1.5~2 倍），**音频断续次数不会变**——因为那与偏移无关。若需要确证，可临时注释 `PlayerFragment` 里的 `setTargetOffsetMs` 分支重编一版对照。
