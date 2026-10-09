# Sets the minimum version of CMake required to build your native library.
# This ensures that a certain set of CMake features is available to
# your build.
cmake_minimum_required(VERSION 3.21.1)

project(MyTV)

# Specifies a path to native header files.
include_directories(src/main/cpp/include)

if (IS_SO_BUILD)
    # Specifies a library name, specifies whether the library is STATIC or
    # SHARED, and provides relative paths to the source code. You can
    # define multiple libraries by adding multiple add_library() commands,
    # and CMake builds them for you. When you build your app, Gradle
    # automatically packages shared libraries with your APK.
    add_library( # Specifies the name of the library.
            native

            # Sets the library as a shared library.
            SHARED

            # Provides a relative path to your source file(s).
            src/main/cpp/native.c
            src/main/cpp/aes.c
    )

    # 设置编译输出路径
    set_target_properties(
            native
            PROPERTIES
            LIBRARY_OUTPUT_DIRECTORY
            ${CMAKE_SOURCE_DIR}/src/main/cpp/${ANDROID_ABI}
    )
else ()
    add_library(
            nothing
            SHARED
            src/main/cpp/nothing.c)

    add_library(native
            SHARED
            IMPORTED)

    set_target_properties( # Specifies the target library.
            native

            # Specifies the parameter you want to define.
            PROPERTIES IMPORTED_LOCATION

            # Provides the path to the library you want to import.
            ${CMAKE_SOURCE_DIR}/src/main/cpp/${ANDROID_ABI}/libnative.so)
endif ()

#add_library(libssl
#        STATIC
#        IMPORTED)
#
#set_target_properties( # Specifies the target library.
#        libssl
#
#        # Specifies the parameter you want to define.
#        PROPERTIES IMPORTED_LOCATION
#
#        # Provides the path to the library you want to import.
#        ${CMAKE_SOURCE_DIR}/src/main/cpp/${ANDROID_ABI}/libssl.so)
#
#add_library(libcrypto
#        SHARED
#        IMPORTED)
#
#set_target_properties( # Specifies the target library.
#        libcrypto
#
#        # Specifies the parameter you want to define.
#        PROPERTIES IMPORTED_LOCATION
#
#        # Provides the path to the library you want to import.
#        ${CMAKE_SOURCE_DIR}/src/main/cpp/${ANDROID_ABI}/libcrypto.so)

add_library(libssl
        STATIC
        IMPORTED)

set_target_properties( # Specifies the target library.
        libssl

        # Specifies the parameter you want to define.
        PROPERTIES IMPORTED_LOCATION

        # Provides the path to the library you want to import.
        ${CMAKE_SOURCE_DIR}/src/main/cpp/${ANDROID_ABI}/libssl.a)

add_library(libcrypto
        STATIC
        IMPORTED)

set_target_properties( # Specifies the target library.
        libcrypto

        # Specifies the parameter you want to define.
        PROPERTIES IMPORTED_LOCATION

        # Provides the path to the library you want to import.
        ${CMAKE_SOURCE_DIR}/src/main/cpp/${ANDROID_ABI}/libcrypto.a)

find_library( # Defines the name of the path variable that stores the
        # location of the NDK library.
        log-lib

        # Specifies the name of the NDK library that
        # CMake needs to locate.
        log)

if (IS_SO_BUILD)
    # Links your native library against one or more other native libraries.
    target_link_libraries( # Specifies the target library.
            native
            libssl
            libcrypto
            ${log-lib})
else ()
    target_link_libraries( # Specifies the target library.
            nothing
            libssl
            libcrypto
            native
            ${log-lib})
endif ()


# ============================================================================
# mp2dec —— 内置 MPEG-1/2 Layer II（MP2）软件解码器
#
# 为什么需要它：
#   IPTV 聚合源里 MP2 音频非常普遍（实测 CCTV9/安徽卫视的源就是
#   MP2 256kbps/48kHz）。media3 的 TS 解析器把它标成 `audio/mpeg-L2`，
#   而 Android CDD 从不要求支持该格式 —— AOSP 自带的 MP3 软解
#   （pvmp3）只有 Layer III，本机硬解清单里也只有 ac3/eac3。
#   结果是音频渲染器被整个禁用：画面正常、无声、且不抛任何错误。
#
# 方案：内置 libmad（纯 C，支持 Layer I/II/III）+ JNI 包装，
#       在 Kotlin 侧经自定义 media3 DecoderAudioRenderer 接入。
#
# 这是一个完全独立的 target，与 libnative.so（AES 加密，静态 JNI 注册）
# 没有任何链接关系，两者可以各自独立构建/替换。
# ============================================================================
set(LIBMAD_DIR ${CMAKE_SOURCE_DIR}/src/main/cpp/thirdparty/libmad)

add_library(mp2dec SHARED
        ${LIBMAD_DIR}/bit.c
        ${LIBMAD_DIR}/decoder.c
        ${LIBMAD_DIR}/fixed.c
        ${LIBMAD_DIR}/frame.c
        ${LIBMAD_DIR}/huffman.c
        ${LIBMAD_DIR}/layer12.c
        ${LIBMAD_DIR}/layer3.c
        ${LIBMAD_DIR}/stream.c
        ${LIBMAD_DIR}/synth.c
        ${LIBMAD_DIR}/timer.c
        ${LIBMAD_DIR}/version.c
        src/main/cpp/mp2dec_jni.c)

# HAVE_CONFIG_H 让 libmad 各 .c 包含我们手写的 config.h（FPM_DEFAULT 纯 C 定点）
target_compile_definitions(mp2dec PRIVATE HAVE_CONFIG_H=1)

target_include_directories(mp2dec PRIVATE ${LIBMAD_DIR})

target_link_libraries(mp2dec ${log-lib})