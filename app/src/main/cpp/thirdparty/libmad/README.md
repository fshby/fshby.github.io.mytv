# libmad（内置 MPEG 音频软解）

来源：**libmad 0.15.1b**（https://sourceforge.net/projects/mad/）
许可：**GNU GPL v2**（各源文件头部保留了原始版权声明）

## 为什么内置

IPTV 聚合源里 **MPEG-1 Layer II（MP2）** 音频非常普遍。media3 的 TS 解析器会把
它标成 `audio/mpeg-L2`，而 Android CDD 从不要求支持该格式：

- AOSP 自带的 MP3 软解（pvmp3）**只实现 Layer III**，喂 Layer II 会直接报
  `supported=NO_UNSUPPORTED_TYPE`；
- 本项目目标机型（Hi3798/Android 9）的硬解音频清单里只有 `ac3/eac3`；
- media3 的 `MediaCodecUtil` 也没有 `mpeg-L2 → audio/mpeg` 的别名映射。

结果就是**音频渲染器被整个禁用**：画面正常、无声、且不抛任何错误。libmad 补齐
了这块能力，由 `mp2dec_jni.c` 包装，经 Kotlin 侧的自定义 media3 渲染器接入
（见 `com.lizongying.mytv.player`）。

## 相对上游的本地改动

1. **`mad.h`**：删除上游硬编码的 `# define FPM_INTEL`。那是 x86 专用内联定点
   汇编写法，`fixed.h` 会优先匹配 `FPM_INTEL` 分支，在 ARM/ARM64 上直接编译失败。
   改由 `config.h` 选择 `FPM_DEFAULT`（纯 C 32 位定点），两 ABI 共用一份配置。
2. **`config.h`**：上游由 autoconf 生成，交叉编译需自备，故手写。要点：不定义
   `HAVE_MALLOC_H`（bionic 没有该头文件，malloc 声明在 `stdlib.h`）。
3. 未纳入构建的仅有 `minimad.c`（示例程序）。

## 接入要点

- 编译目标 `mp2dec` 定义在 `app/CMakeLists.txt`，**独立于** `libnative.so`
  （AES 加密，静态 JNI 注册），两者互不影响。
- JNI 入口类名必须与 `mp2dec_jni.c` 中的符号一致：
  `com.lizongying.mytv.player.Mp2DecoderNative`。
- 输出固定为 **16-bit 交错小端 PCM**；每帧样本数 Layer II 为 1152
  （MPEG-2 LSF 为 576），由 `nativeDecode` 的 `meta` 回传。
