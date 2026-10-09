/*
 * libmad - MPEG audio decoder library
 * 本地手写 config.h —— 上游由 autoconf 生成，交叉编译到 Android 需自备。
 * 通过 CMake 的 -DHAVE_CONFIG_H 启用（各 .c 里 `#ifdef HAVE_CONFIG_H` 包含本文件）。
 *
 * 取值依据 Android NDK / bionic 的实际头文件情况，并刻意不启用任何
 * 架构相关优化分支，保证 armeabi-v7a 与 arm64-v8a 用同一份配置。
 */

# ifndef LIBMAD_CONFIG_H
# define LIBMAD_CONFIG_H

/* ------------------------------------------------------------------
 * 定点数学实现选择
 *
 * 保持 FPM_DEFAULT（纯 C 32 位定点）。曾有 FPM_ARM / FPM_INTEL /
 * FPM_MIPS 等带内联汇编的加速分支，但：
 *   - FPM_INTEL 的汇编是 x86 的，在 ARM 上根本编不过；
 *   - FPM_ARM 的汇编是 ARMv4/v5 的 32 位写法，arm64-v8a 不适用；
 * 而 libmad 的 C 实现本来就快，ARM 上解 48kHz 立体声 MP2 的
 * CPU 占用在个位数百分比，没有必要冒风险引入汇编分支。
 * ------------------------------------------------------------------ */
# define FPM_DEFAULT 1

/* ------------------------------------------------------------------
 * 标准头文件存在性（bionic 全部具备）
 * 注意：Android bionic 没有 malloc.h，malloc 声明在 stdlib.h 里，
 * 因此这里不定义 HAVE_MALLOC_H。
 * ------------------------------------------------------------------ */
# define HAVE_ASSERT_H 1
# define HAVE_ERRNO_H 1
# define HAVE_FCNTL_H 1
# define HAVE_LIMITS_H 1
# define HAVE_MEMORY_H 1
# define HAVE_STDLIB_H 1
# define HAVE_STRING_H 1
# define HAVE_SYS_TYPES_H 1
# define HAVE_UNISTD_H 1

/* 让 libmad 用上 __attribute__（clang 支持） */
# define HAVE___ATTRIBUTE__ 1

/* 基本整数宽度 */
# define SIZEOF_INT 4
# define SIZEOF_LONG 4
# define SIZEOF_LONG_LONG 8

/* libmad 用 MAD_F_MLX 的二操作数形式（32 位平台上不存在 long long 乘加指令） */
# undef OPT_SSO

# endif /* LIBMAD_CONFIG_H */
