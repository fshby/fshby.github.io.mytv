# 切台后前几个分片卡顿 —— 根因分析

分析日期：2026-09-30　分支：`ui-v218`　分析对象：`PlayerFragment` 的起播参数 + 远程列表 407 个源
方法：media3 1.3.1 源码核对（`HlsMediaSource` / `DefaultHlsPlaylistTracker` / `DefaultLoadErrorHandlingPolicy` / `DefaultLivePlaybackSpeedControl`）+ 对远程列表 407 个源做 HLS 结构与分片吞吐实测

---

## 一、结论

不是 App 逻辑 bug，也不是「源太差」这么简单，而是**两个事实相乘**：

1. **起播位置被我们显式钉在直播边缘前 3 秒**（`setTargetOffsetMs(3000)`），
   而这个位置会被 ExoPlayer **回退到分片起点**，因此起播瞬间手上只有 **1 个分片的已发布内容**（2s~10s）。
   ExoPlayer 对 HLS 直播的**默认**目标是 `3 × 目标时长`（5s 分片 → 15s，10s 分片 → 30s）——我们把安全余量砍掉了 5~10 倍。

2. **分片级不可用是常态**：实测 209 个可用源里，最新分片经常 502、窗口尾部最旧分片经常 404、部分源单个分片要 3~40 秒才下完（只有实时码率需求的 0.25~0.9 倍）。

两条叠加：起播时几乎没有缓冲，而紧接着要拉的那 1~2 个分片恰好是「刚发布、CDN 还没热」的那几个 → **在第一片与第二片的边界上必然断流**。

「只有前几个分片卡，之后就正常」是 ExoPlayer 的**自愈**行为：
`DefaultLivePlaybackSpeedControl.notifyRebuffer()` 每发生一次重缓冲，就把有效目标偏移 **+500ms**（`DEFAULT_TARGET_LIVE_OFFSET_INCREMENT_ON_REBUFFER_MS`），逐次抬高，抬到缓冲能撑住的位置后才稳定下来。**也就是说卡顿本身就是播放器在学习「到底该落后多少秒」。**

---

## 二、起播位置是怎么算出来的（源码事实）

`HlsMediaSource`（media3 1.3.1）：

```java
// 1) 优先级：媒体项显式设置的 targetOffsetMs 最高，直接覆盖 playlist 推导值
if (liveConfiguration.targetOffsetMs != C.TIME_UNSET) {
  targetLiveOffsetUs = Util.msToUs(liveConfiguration.targetOffsetMs);   // ← 我们传 3000
} else {
  targetLiveOffsetUs = getTargetLiveOffsetUs(playlist, liveEdgeOffsetUs);
}
// 2) 钳制在 [liveEdgeOffset, 窗口时长+liveEdgeOffset] 内
targetLiveOffsetUs = Util.constrainValue(targetLiveOffsetUs, liveEdgeOffsetUs,
                                         playlist.durationUs + liveEdgeOffsetUs);

// 3) 默认值（未显式设置时）
private static long getTargetLiveOffsetUs(HlsMediaPlaylist playlist, long liveEdgeOffsetUs) {
  if (playlist.startOffsetUs != C.TIME_UNSET)          targetOffsetUs = playlist.durationUs - playlist.startOffsetUs;
  else if (serverControl.partHoldBackUs != C.TIME_UNSET && ...) targetOffsetUs = serverControl.partHoldBackUs;
  else if (serverControl.holdBackUs != C.TIME_UNSET)    targetOffsetUs = serverControl.holdBackUs;
  else                                                  targetOffsetUs = 3 * playlist.targetDurationUs;  // RFC 8216 §4.4.3.8
  return targetOffsetUs + liveEdgeOffsetUs;
}

// 4) 起播点：先算 liveEdge - targetOffset，再回退到「最近的靠前分片起点」
private long getLiveWindowDefaultStartPositionUs(HlsMediaPlaylist playlist, long liveEdgeOffsetUs) {
  long startPositionUs = playlist.durationUs + liveEdgeOffsetUs
                         - Util.msToUs(liveConfiguration.targetOffsetMs);
  if (playlist.preciseStart) return startPositionUs;      // 带 EXT-X-START:PRECISE=YES 时不回退（缓冲只有 3s）
  ...
  return findClosestPrecedingSegment(playlist.segments, startPositionUs).relativeStartTimeUs;
}
```

代入实测数据：

| 源类型 | 目标时长 | 窗口 | 起播点 = 窗口尾 −3s，回退到分片起点 | 起播可用缓冲 |
|---|---|---|---|---|
| CCTV-1（204.12.221.218 等） | 10s | 50s（5 片） | 47s → 回退到第 4 片起点 40s | **10s** |
| 常见卫视（5s 片） | 5s | 25s（5 片） | 22s → 回退到第 5 片起点 20s | **5s** |
| 北京卫视 / 湖南卫视（cnr.cn） | 3s | **6s（2 片）** | 3s → 正好落在最后一片起点 | **3s** |
| 金鹰卡通 | 2s | 6s（3 片） | 4s → 第 3 片起点 | **2s** |

对照：若用 ExoPlayer 默认（3×目标时长），5s 片会从边缘后 15s 起播、10s 片从 30s 起播，缓冲厚 3 倍以上。

---

## 三、实测数据

### 1) 源侧结构（远程列表 407 个唯一 URL）

| 指标 | 结果 |
|---|---|
| 能解析出 m3u8 的源 | **209 / 407（51%）**（其余 200 个是 302 到 JSON 报错页的伪活源） |
| 独立主机 | 87 |
| 带时间签名 token（`?tm=&key=` 之类） | 21 / 209 |
| 是 master playlist（要多下钻一次） | 27 / 209 |
| 目标时长 ≤3s | 33 |
| **分片数 ≤3 片** | **93（44%）** |
| 窗口 ≤8s / ≤12s | 18 / 48 |
| 窗口中位数 | 25s |

分片数分布：3 片 85 个、5 片 56 个、2 片 8 个、4 片 12 个、6 片 16 个、其余更长。
→ **近一半的源整个窗口只装 2~3 个分片**，任何一次分片下载抖动都没有第二片可以垫。

### 2) 分片吞吐 vs 实时码率需求（不同主机各取 1 个源，完整下载一片）

| 频道 | 片长 | 分片大小 | 下载耗时 | 下载速率 | 实时需求 | 富余倍数 |
|---|---|---|---|---|---|---|
| CCTV-1 | 10s | 5.0 MB | 40s（超时截断） | 126 KB/s | 504 KB/s | **0.25x** |
| CCTV-1 | 10s | 11.1 MB | 23.9s | 467 KB/s | 1113 KB/s | **0.42x** |
| TVBS新闻台 | 10s | 2.0 MB | 20.3s | 100 KB/s | 204 KB/s | **0.49x** |
| 动作电影 | 9s | 9.7 MB | 16.6s | 584 KB/s | 1076 KB/s | **0.54x** |
| 凤凰中文 | 5s | 2.7 MB | 5.6s | 491 KB/s | 549 KB/s | **0.89x** |
| 海峡卫视 | 4s | 180 KB | 2.9s | 61 KB/s | 45 KB/s | 1.36x |
| 延边卫视 | 9.7s | 2.2 MB | 0.37s | 5963 KB/s | 229 KB/s | 26x |
| CCTV9 | 187s | 9.5 MB | 0.3s | 31 MB/s | 51 KB/s | 617x |

→ 源端吞吐差异达 **3 个数量级**（61 KB/s ~ 31 MB/s）。落在 0.9x~1.5x 这一档的源（凤凰、海峡等）**正是"能播但起播必卡"的那一批**：实时播放时下载刚好追不上，只有靠缓冲垫着，而我们的起播缓冲恰好只有 1 片。

### 3) 分片级失败率（同源连续观测）

- 最新分片：12 次请求 11 次 200，其中 4 次被 12s 超时截断（= 下载不完）
- 稍旧分片：24 次请求 22 次 200，2 次 404 / 2 次 502
- 某 CCTV-1 源连续 6 次观测：**首片（窗口最旧分片）5 次 404**——已被 CDN 清掉但仍在 playlist 里
- 海南卫视：最新片 502、次新片 502（该源当前整体不可用）
- 动作电影：playlist 里出现 0 时长分片；TVBS 新闻台分片时长列表里也有一个 0

→ 分片级失败率约 **15%~20%**，且**集中在窗口两端**（最旧=已被清理、最新=刚发布未就绪），中间的分片最可靠。起播点贴边缘，等于每次都优先去撞最不可靠的那一端。

---

## 四、事故链（为什么表现为"卡一下、再卡一下、然后好了"）

1. **起播**：`setMediaItem` → `prepare()`。冷启动开销叠加：新主机 DNS（`DnsCache` 首次未命中）+ TCP + 可能 1~3 次 302（`RedirectMemory` 首次无记忆）+ 27/209 源还要多下钻一层 master playlist；实测 playlist TTFB 44ms ~ 2.6s。
2. **门槛低**：`bufferForPlaybackMs = 2000` → 只要 2 秒数据就开始播。此时手上只有第 1 片的一部分，而第 1 片整体要 3~40 秒才下得完。
3. **撞边缘**：播到第 1 片末尾，正好到达直播边缘。下一片刚发布。
4. **发现滞后**：`DefaultHlsPlaylistTracker` 拿到新快照后要等 **1×目标时长**才再次刷新（无新快照时等 0.5×目标时长）→ 每次发现新分片都可能滞后 1 个目标时长（5s 源 = 5s，10s 源 = 10s）。
5. **分片不可用**：404 / 502 / 慢。`DefaultLoadErrorHandlingPolicy` 对 `InvalidResponseCodeException`（含 404/502）**仍然重试**，退避 `min((errorCount-1)×1000, 5000)`，最多 3 次 → **单片坏 = 约 3 秒冻结**（不是立刻报错）。
6. **升级为整源重建**：3 次仍失败才抛到 `onPlayerError` → 我们静默重试 500ms 后 `prepare()`。`prepare()` 会重新拉 playlist、按 `liveEdge-3s` 重新定位、重新初始化解码器 → **又回到同一位置**，很可能再撞一次；连续失败才 `rotateToNextSource()` 换源（又一次完整重建）。
7. **自愈**：每次重缓冲 `notifyRebuffer()` 把有效目标偏移 +500ms。卡 5 次就多落后 2.5s，卡到某个程度缓冲刚好能撑住 → 看起来"前几个分片卡，后面就好了"。

补充：窗口只有 2 片（6s）的源还有第二重风险——playlist 若 `3.5 × 目标时长` 内没有变化会抛 `PlaylistStuckException`（`DEFAULT_PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT = 3.5`）。3s 片 → 10.5s；5s 片 → 17.5s。**这个 17.5s 与此前记录的「凤凰系列源每 ~17 秒卡一次」周期高度吻合**，可作为那条结论的补充线索。

---

## 五、次要叠加因素（App 侧）

| 项 | 影响 |
|---|---|
| `bufferForPlaybackAfterRebufferMs = 1500` | 重缓冲后只补 1.5s 就恢复播放，几乎必然再抖一次；而偏移每次只 +500ms，恢复很慢 |
| 多源排序用 playlist 建连+首字节 | 与真正的分片吞吐无关。实测同列表内 61 KB/s ↔ 31 MB/s 差 500 倍，"最快源"可能只是 playlist 快 |
| 探测只验 playlist 内容（`isMediaPayload`） | 不看分片。0.25x 的源照样判活进列表 |
| `maxPlaybackSpeed = 1.08` | 落后时会加速追边缘，把安全余量推回危险区（不过它同时也是 `notifyRebuffer` 生效的前提，不能去掉） |
| `minBufferMs = 15000` > 多数源窗口（中位 25s，44% 只有 2~3 片） | 加载器永远达不到 15s，会持续满速抢带宽（弱盒子上与首帧、探测争抢） |

---

## 六、修复建议（按性价比排序）

1. **按源动态设置 `targetOffsetMs`（最关键，且几乎零成本）**
   `targetOffset = clamp(2 × 目标时长, 6s, min(0.5 × 窗口, 30s))`
   - 5s 片 → 10s；10s 片 → 20s；3s 片 → 6s（受 6s 窗口上限约束只能到 ~3s）
   - **不会增加起播等待**：起播门槛仍是 `bufferForPlaybackMs=2000`，抬高偏移只是让播放点更靠后（等于直接用"已经发布好"的内容当缓冲）
   - 代价：画面比直播边缘晚 10~20s；内存略增
   - 落地方式：`probeUrl` 阶段顺手解析 `#EXT-X-TARGETDURATION` / 分片数 / 窗口长度，随 `TV` 带到 `PlayerFragment`
2. **自定义 `LoadErrorHandlingPolicy`，把分片级失败消化在加载器内**
   - 对 `InvalidResponseCodeException` 用固定短退避（300~500ms × 4~6 次），别用 0/1/2s 那套长退避（一片坏 = 3s 冻结）
   - 分片级错误不要触发 `prepare()`（整源重建代价太大）；只有 playlist 级错误才重建
3. **`bufferForPlaybackAfterRebufferMs` 1500 → 3000~5000**，避免"补 1.5s 就恢复 → 再抖"的循环
4. **多源排序改用分片真实吞吐**：probe 时取 playlist 倒数第 2 片做一次限量下载（512KB 或计时 1.5s），用 KB/s 排序；顺带把 <0.9x 的源剔除或降权
5. **窗口 ≤2 片 / ≤8s 的源降权**（实测 18 个，含北京卫视、湖南卫视这类知名频道）：这类源无论怎么调都只剩 ≤1 片余量，且容易触发 `PlaylistStuckException`
6. 可选：`minBufferMs` 15s → 8s，减少短窗口源上的无意义满速拉流

---

## 七、复现与验证

- 抓远程列表：`curl -sL https://mytemple.fshby.cc/cn_all_staue.m3u8 -o cn_all.m3u8`
- 结构扫描：`python hls_all.py`（输出 `hls_all.json`：目标时长 / 分片数 / 窗口 / TTFB / 跳转数 / token）
- 吞吐实测：`python seg_thr.py`（完整下载最新片与次新片，算「下载速率 ÷ 实时码率需求」）
- 真机验证：`adb logcat -s PlayerFragment TVSource`，观察 `PlaybackException`、`transient retry`、`failover` 的出现时机是否集中在换台后 5~20 秒内

---

## 八、落地实现记录（2026-09-30，全部 5 条已实现，assembleDebug 通过）

### 8.1 新增文件

| 文件 | 作用 |
|---|---|
| `api/SourceProfile.kt` | 源画像：`targetDurationMs` / `windowMs` / `segments` / `kbps` / `demandKbps` / `targetOffsetMs`，派生 `tinyWindow`（≤2 片或 ≤8s）与 `headroom`（速率÷需求）。`SourceProfiles` 对象持有内存表 + `cache/mytv-sources.txt`（7 列 `\t`，URL 最后）。落盘的意义：命中「列表没变 + 结论新鲜 → 整段跳过探测」的那次启动，起播偏移依然有效。纯函数 `targetOffsetFor(durs)` 可直接单测。 |
| `api/MyLoadErrorHandlingPolicy.kt` | 实现 media3 1.3.1 `LoadErrorHandlingPolicy` 三个抽象方法。分片级 404/502/超时/IO → **固定 300ms × 4**（默认策略是 0/1/2s → 单片坏冻结约 3s）；playlist 级递增 400ms × 3；`ParserException`/`FileNotFoundException`/明文流量直接判致命；`getFallbackSelectionFor` 返回 null（IPTV 单地址源无位置可回退）。 |

### 8.2 改动

- **`TVSource`**：`probeUrl` 拆成 `probeHead`（复用 4096B 预读，多数 playlist 一次拿全，不额外增加往返）→ `parseHls`（抽 `#EXT-X-TARGETDURATION` / `#EXT-X-BITRATE` / 分片列表；master playlist 按 BANDWIDTH 下钻一层）→ `measureSpeed`（限量 384KB、1s 预算、1.6s 读超时，下「倒数第 2 片」；从 `Content-Range` 取全长推实时码率需求）→ 排序（小窗口降权 → 富余倍数 → 实测速率 → 建连耗时）→ 保留前 2 条并写画像。`probeHead` 的耗时不再参与排序。
- **`PlayerFragment`**：`.setTargetOffsetMs(SourceProfiles.get(url)?.targetOffsetMs)`，画像未知（返回 0）时**不设置**，交回 media3 默认 `3 × 目标时长`；`.setLoadErrorHandlingPolicy(MyLoadErrorHandlingPolicy())`；`minBufferMs` 15s→8s、`maxBufferMs` 45s→30s、`bufferForPlaybackAfterRebufferMs` 1500→3000。
- **`MyTVApplication`** 进程启动即预加载画像；**`MainFragment`** 在 `filterAlive` 后 `SourceProfiles.save(...)`（顺带裁掉已不在列表的条目）。

### 8.3 两条必须先核实的 media3 行为（都已按源码确认）

1. **`targetOffsetMs` 会不会被默认 min/max offset 钳回去？不会。**
   `MediaItem.LiveConfiguration` 的 `targetOffsetMs/minOffsetMs/maxOffsetMs` 默认全是 `C.TIME_UNSET`；且 `HlsMediaSource.updateLiveConfiguration()` 重建配置时**只写 `targetOffsetMs` 与 `min/maxPlaybackSpeed`**（`minOffsetMs/maxOffsetMs` 被丢弃），该配置经 `SinglePeriodTimeline` → `Timeline.Window.liveConfiguration`，而 `ExoPlayerImplInternal.updatePlaybackSpeedSettingsForNewPeriod()` 喂给 `DefaultLivePlaybackSpeedControl` 的正是它。
   → **只设 `targetOffsetMs` 就能同时决定「起播位置」和「追赶目标」**，不会出现「起播在 20s 处、播放器却按 5s 追」的分裂。
2. **偏移必须按真实分片边界算。** `getLiveWindowDefaultStartPositionUs()` 会把「窗口 − 偏移」**回退到最近的分片起点**，所以毫秒数只是近似。分片时长不均匀时，`2 × 目标时长` 这种时间式算法会被这一步取整推到窗口最旧片（实测 209 个源里 17 个）。

### 8.4 最终算法与实测回算

```
targetOffsetFor(durs):
  1. 剔掉 0 时长分片（实测确有：动作电影、TVBS 新闻台）——否则「身后留一片」的约束会被架空
  2. 目标垫片 = clamp(2 × 平均分片时长, 6s, 30s)
  3. 在「下标 ≥ 1」（身后至少留 1 整片）内，取垫片不超过目标值的那个分片起点
  4. 连最后一片都超过目标（分片本身很长）时退到倒数第 1 片
```

用实测 209 个可用源回算（偏移按毫秒截断、定位按 media3 的整数微秒模型）：

| 指标 | 旧（固定 3000ms） | 新（按源画像） |
|---|---|---|
| 起播可垫内容 中位 / 均值 | 6.0s / 7.15s | **10.0s / 11.25s** |
| 「至少垫 1 片」的源 | 92 / 209 | **170 / 209** |
| 「至少垫 2 片」的源 | 4 / 209 | **52 / 209** |
| **贴在窗口最旧片**（身后 < 1 片，窗口一滑就被挤出 → 反复 BehindLiveWindow） | **6** | **0** |
| 垫片 > 60s（延迟过大） | 1 | 1（CCTV9 的 187s 分片源仍是 187s，未被垫成几分钟） |

第 3 行那 6 个「贴最旧片」的源正是 **北京卫视 / 湖南卫视 / 甘肃经济 / 甘肃都市 / 福建新闻 / 福建经济**（都是 2 × 2.987s 的 6s 窗口）——固定 3000ms 恰好把起播点钉在窗口起点，这也解释了这份报告第五节里「北京卫视、湖南卫视这类知名频道」为何格外不稳。

### 8.5 未完成

真机验证。设备在线（`192.168.1.3:5555`），但已安装的 `com.fshby.mytv` 与本机 debug 签名不一致，覆盖安装报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；需先卸载（会清空应用数据与 push 进去的 tvlist）再装。已在与用户确认。验证时看这几条日志：
- `TVSource: probe done` / `SourceProfiles: save N source profiles`
- `PlayerFragment: play <频道> offset=<N>ms`（应随源不同而不同，而不是恒为 3000）
- `PlayerFragment: transient retry` / `failover` 的出现时机是否从换台后 5~20s 内移开

---

## 九、复查补丁与验证清单（2026-09-30 收尾）

### 9.1 一处会漏频道的回归（已修）

改造后的 `probeTV` 要求候选源必须能 `parseHls` 解析出 HLS 结构。这顺手把另一类源排除掉了：**过得了存活校验（`probeHead` 已确认返回真实媒体）但解析不出 playlist 的源**——直连 MPEG-TS / 渐进式媒体流。它们的后果不是「播得不够顺」，而是**整条频道消失**（若该频道的源全属这类，`alive.isEmpty()` 直接把它从列表里剔除）。

修法：
- 这类源收进 `fallbacks`，只在 HLS 名额（`PROBE_SOURCES_ENOUGH = 2`）有剩时按建连耗时补在末尾当备用；
- 一个 HLS 源都没有时，退回「按建连耗时排序」的老办法保住频道（行为与改造前一致）；
- HLS 源永远排在直连流前面，画像只下发给 HLS 源。

### 9.2 编译与产物

| 项 | 结果 |
|---|---|
| `assembleDebug -PIS_SO_BUILD=false` | BUILD SUCCESSFUL in 1m21s，零告警 |
| `assembleRelease -PIS_SO_BUILD=false` | BUILD SUCCESSFUL in 4m28s |
| release APK | `app/build/outputs/apk/release/app-release.apk`，11,315,783 B，md5 `3abf5684d0e8283544926f689b914f98` |
| 版本 | versionCode 33751043 / versionName `2.3.0-3-g1407651` / minSdk 21 |
| 签名 | `apksigner verify --print-certs` → MD5 `39234ab2930f0280f8ae2a53f5c20361` = `keystore/mytv-release.keystore` |
| dex | `classes3.dex` 命中 `MyLoadErrorHandlingPolicy` / `SourceProfile`，新类确实打进包 |

**签名这一条有关键价值**：release 包与盒子上已装的 `com.fshby.mytv` 同密钥 → **可直接覆盖安装，无需卸载、不会清数据**；此前卡住的 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 只是「拿 debug 包去覆盖 release 包」导致的，改用 release 包即解。

### 9.3 真机验证为什么没做完

不是代码或构建问题：**盒子端的网络调试被关了**。
- `192.168.1.2` ping 通（TTL=128）但 5555 **actively refused（10061）**；`192.168.1.3` 直接超时；
- `adb kill-server` / `start-server` 重启无效，`adb devices` 为空列表。

按既往经验，5555 拒连只能人到盒子上把网络调试重新打开，本机无法远程拉起。

**恢复后一条命令验证**：
```bash
adb connect 192.168.1.2:5555 && adb install -r app/build/outputs/apk/release/app-release.apk
```
看三条日志即可判定改动是否生效：
1. `TVSource: probe done` —— 探测完成（含新加的吞吐实测）；
2. `SourceProfiles: save N source profiles` —— 画像已落盘；
3. `PlayerFragment: play <频道> offset=<N>ms` —— **应随源不同而不同**（不再恒为 3000ms；画像未知的源显示 `media3默认`）。这一条是本次改动是否真正生效的判定标志。

---

## 十、真机验证结果（2026-09-30 晚，烽火 HG680-KF / Android 9 / 192.168.66.158:5555）

### 10.1 意外发现的真正拦路虎：盒子系统时钟停在 2009 年

首轮启动日志给出的根因：

```
Caused by: java.security.cert.CertificateException: Unacceptable certificate: CN=YR1, O=Let's Encrypt, C=US
Caused by: java.security.cert.CertificateNotYetValidException:
    Certificate not valid until Wed Sep 03 08:00:00 GMT+08:00 2025
    (compared to Thu Jan 01 00:04:36 GMT+08:00 2009)
```

盒子 `date` 显示 `Thu Jan 1 00:11:34 CST 2009`（`auto_time=1` 但 NTP 不通）。远程列表在 Vercel 上、用 Let's Encrypt 短周期证书（实测 `notBefore=2026-08-09`、`notAfter=2026-11-07`，**只有 3 个月有效期**），于是证书被判「尚未生效」→ **列表根本下载不下来**，App 退回内置央视频表 → 屏幕上全是「认证状态错误」的频道，本次所有优化也无从生效（内置/接口拉流频道 `pid` 非空，按设计不参与探测）。

修法与证据：`adb root` 后 `adb shell "date $(date +%m%d%H%M%Y.%S)"`（**Android 的 date 不支持 `-s`**，位置参数格式为 `MMDDhhmmCCYY.ss`）→ 时钟拨到 2026-09-30 → 立刻 `TVSource: fetch ok 89046 bytes`。

> 这不是本次改动引入的问题，但**是用户端真实会遇到的一类故障**：出厂或长期断网的盒子时钟常停在 2000 年代，而 Vercel + Let's Encrypt 的证书窗口只有 3 个月，任何方向偏差都会让远程列表失效。建议的缓解（详见 10.4）。

### 10.2 冷启动全链路实测

```
19:22:58 I/TVList  : load -> 4 groups / 50 channels        ← 内置表先行（首屏不等网络）
19:23:04 I/TVSource: fetch ok 89046 bytes                  ← 远程列表（时钟拨正后）
19:23:05 I/PlayerFragment: play CCTV1 offset=media3默认     ← 此刻还没有画像，故意不覆盖 media3 默认
19:23:39 I/TVSource: probe 20/262                          ← 起播后 20s 才开始探测
19:24:56 I/TVSource: probe done: alive 256/262, dropped 6  ← 探测全程约 77s
19:24:57 I/SourceProfiles: save 349 source profiles        ← 画像落盘
19:24:57 I/PlayerFragment: play CCTV1 offset=20000ms       ← 列表重建后起播即用真实偏移
```

**新增的吞吐实测没有拖慢探测**：262 个频道 77 秒（并发 6），平均约 0.3s/频道——因为死源连接失败很快返回，1s 预算的吞吐实测只作用在真正是 HLS 的候选上。

### 10.3 换台压测与按源偏移

9 次换台，偏移随源变化（判定标志通过）：

| 频道 | offset |
|---|---|
| CCTV1 / CCTV2 | 20000 ms |
| 黑龙江新闻法治 / 黑龙江少儿 | 14019 ms |
| 黑龙江都市 / 黑龙江文体 / 黑龙江影视 | 10000 ms |
| 甘南县综合 / 哈尔滨资讯 | 6021 ms |

- **FATAL = 0**
- **BehindLiveWindow = 0**（这是最关键的一条：短窗口源以前必然周期性触发）
- `PlaybackException = 3`，每一次都紧跟 `transient retry #1`，没有一次升级到换源或弹「播放错误」——说明分片级失败大部分已被 300ms 短退避在分片层消化，漏到播放器层的也被静默重试接住。
- 二次启动：`load cache -> 36 groups (632ms)` + `fetch not modified (304)` → 整段跳过解析与探测，**而偏移依然生效**（来自落盘的 349 条画像），这正是把画像落盘的设计目的。
- 安装：release 包与已装包同密钥，`adb install -r` 直接 `Success`，不需要卸载。

### 10.4 问题记录：盒子时钟错误导致 HTTPS 列表不可用（缓解方案见第十一节）

App 无法修改系统时钟（无 root 权限），`Utils.init()` 的淘宝时间只能用于业务签名，救不了 TLS。三种可选缓解：

1. **随包内置一份频道列表快照（assets）作最后兜底**：在线列表失败且无缓存时使用，至少给出可播的 IPTV 源，而不是只有不可播的内置央视频源。成本低、离线也受益。
2. **服务器再加一条不跳转 HTTPS 的纯 HTTP 列表地址**：当前 `http://mytemple.fshby.cc/cn_all_staue.m3u8` 会 308 跳到 HTTPS（Vercel 行为），绕不过证书校验；需要另用一个可控主机的 80 端口直出。
3. **时钟严重偏斜时上屏提示**「盒子时间不准（20xx 年），请在系统设置里校准时间」：成本最低，把自助解决路径交给用户。

推荐 1 + 3 组合（既不削弱 TLS 安全，又能让这类盒子开箱可用）。**两条均已实现并真机验证，见第十一节。**

### 10.5 本机调试经验（写脚本必读）

- **换台键是 `KEYCODE_DPAD_DOWN/UP`，不是 LEFT/RIGHT**（后者只挪焦点，不切台）。
- **`adb logcat` 流式抓取会被掐断**：盒子上 `wrapperjni`（Frida 检测）/`pcapcm4`（抓包）等安全组件持续刷屏，叠加 adb server 被回收，长时间「边跑边写文件」的脚本会中途停更并挂死（`verify3.sh` 跑了 23 分钟仍未结束，只能强制终止）。可靠做法：`adb logcat -c` → 短窗口（≤8min）内联流式抓取并用 `TAG:V '*:S'` 过滤 → 抓完立即 dump；或者干脆用**文件快照**验证（root 下直接读 `/data/data/com.fshby.mytv/cache/*`）。
- **每次 Bash 调用之间 adb server 会被回收**（新调用总打印 "daemon not running"）→ 每条命令都要 `adb start-server && adb connect <ip>:5555` 重来；跨步骤的长流程必须写在**一个**脚本里一次跑完。
- release 包 `run-as` 报 `package not debuggable` → 读私有文件要用 `adb root`（本盒子 adbd 可提权）或改装 debug 包。



---

## 十一、兜底方案落地（第 10.4 节的 1 + 3，已实现并真机验证）

第 10.4 节把「时钟错误导致 HTTPS 列表整体不可用」列为待决策项，本节记录选中方案的实现与实测。

### 11.1 方案 1：随包内置频道列表快照

| 项 | 内容 |
|---|---|
| 资产 | `app/src/main/assets/tvlist.snapshot.m3u`（89 KB / 387 条 EXTINF，随版本发布刷新） |
| 生成 | 直接取远程 `cn_all_staue.m3u8` 原文，头部加 4 行 `#` 注释（生成时间 / 来源）。解析器只看 `#EXTINF`，注释无副作用 |
| 代码 | `TVSource.SNAPSHOT_ASSET` + `TVSource.loadSnapshot(context)`（`assets.open(...)` → `parse(text, "")`，不参与探测） |
| 优先级 | `TVSource.loadSync`：**本地文件 > 上次远程缓存 > 内置快照**；三者都没有才回退内置表 |
| 为什么值得 | 内置央视频源在当前环境下**全部不可播**（接口失效 + 签名门禁）。若没有快照，任何「拉不到远程列表」的盒子（首次安装、离线、时钟错）打开就是一屏「认证状态错误」 |

不在快照上跑 `filterAlive`：快照只在离线/异常时生效，此时网络本就不可用，探测只会把所有源判死。联网后远程列表会正常覆盖它。

### 11.2 方案 3：时钟偏斜检测与上屏提示

**判据一（零成本）**：`TVSource.fetch` 失败时遍历异常链，命中 `CertificateException` / `CertPathValidatorException` / `SSLHandshakeException` / `SSLPeerUnverifiedException` 即标记 `FetchResult.certError`。

**判据二（可量化）**：`TVSource.clockSkewMs()` —— 用**不跟随重定向的纯 HTTP 请求**读响应 `Date` 头，与 `System.currentTimeMillis()` 相减。

> 为什么必须走 HTTP：业务域名用的是 Let's Encrypt **3 个月期**证书，时钟一偏，请求**在 TLS 握手阶段就失败**，拿不到任何响应头。「HTTP 请求不过 TLS，`Date` 头照样可读」是时钟坏掉时唯一还可信的途径。为此单独建了 `.followRedirects(false)` 的 `clockClient`——重定向目标基本都是 HTTPS，跟随等于又把请求掐死一次。

**提示策略**：`MainFragment.warnClockIfSuspected(certError)` 在远程拉取失败后调用，`|skew| > 24h` 或证书异常成立时弹 `Toast`：

> 频道列表更新失败：系统时间比标准时间慢约 17 天。请在「设置 → 日期和时间」中校准后重新打开应用。

- 偏斜量写成「N 天 / N 小时 / N 分钟」，直接告诉用户差多少；
- 两次提示最小间隔 6 小时（周期刷新每 30 分钟失败一次，不能反复弹）；
- 两条判据都不成立时（例如只是断网）**不提示**，避免误报。

### 11.3 真机验证（烽火 HG680-KF / 192.168.66.158）

| 场景 | 构造 | 实测日志 | 结论 |
|---|---|---|---|
| **A. 时钟错 + 无缓存** | `adb root` 后 `date 0101000009`（2009-01-01），删 `cache/tvlist.cache*` 与 `mytv-sources.txt` | `load snapshot -> 37 groups (622ms)`<br>`TVList: load -> 36 groups / 261 channels`<br>`fetch error (cert=true)`<br>`clock skew suspected: skewMs=-560032017260 certError=true` | **快照兜底生效**（261 个 IPTV 频道，而不是央视频死源）；证书异常被识别；偏斜 −5.6×10¹¹ ms ≈ **−17.8 年**，提示条件成立 |
| **B. 时钟正常 + 无缓存** | 恢复时间后清缓存重启 | `load snapshot -> 37 groups (549ms)`<br>`fetch ok 89046 bytes`<br>`remote list unchanged`<br>`probe done: alive 256/262, dropped 6`<br>写 `tvlist.cache` 85749 B + `mytv-sources.txt` 28260 B | 快照先顶上首屏，远程拉到后**内容与快照一致 → 直接复用不重建**，随后照常探测与落盘 |
| **C. 有缓存重启** | 探测完成后直接重启 | `load cache -> 36 groups (502ms)`<br>`play 梨园 offset=8000ms`<br>`fetch not modified (304)` | **不再读快照**，走缓存秒开 + 304 整段跳过；偏移仍来自落盘画像 |

三个场景 **FATAL = 0**。观察到的一个正面副作用：场景 B 中 `TVList: remote list unchanged` —— 因为快照与远程列表同源同内容，快照兜底在联网后是**零成本交接**，不会引起一次全列表重建。

### 11.4 遗留

- 快照里的时间签名型地址（`?key=` 18 条 / `?t=` 2 条）在长期离线后可能失效，属可接受损失（占比 5%）。
- 未做：把「缓存 vs 快照哪个更新」做比较选择（当前固定缓存优先）。若将来出现「缓存长期未更新且死源占比高」的反馈，可比较 `tvlist.cache.meta` 的 `ts` 与快照注释里的生成时间，取新者。
