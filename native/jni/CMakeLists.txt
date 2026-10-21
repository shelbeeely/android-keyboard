cmake_minimum_required(VERSION 3.22)
project(latinime)

# ------------------------------------------------------------------
# Build options
# ------------------------------------------------------------------
option(EXT_BUILD        "External build" OFF)
option(EXT_BUILD_ASAN   "External build with ASAN" OFF)
option(FLAG_DBG         "Enable debug build" OFF)
option(FLAG_DO_PROFILE  "Enable profiling build" OFF)
option(WHISTLE          "Enable Whistle voice input (links the prebuilt Needle engine)" ON)


# ------------------------------------------------------------------
# Global build settings
# ------------------------------------------------------------------
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_ANDROID_STL_TYPE c++_static)

# We always use optimization because GGML would otherwise run at an unusable speed
set(CMAKE_C_FLAGS_RELEASE   "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")

if(EXT_BUILD)
    set(CMAKE_C_FLAGS_DEBUG   "-O0 -ggdb")
    set(CMAKE_CXX_FLAGS_DEBUG "-O0 -ggdb")
    find_package(JNI REQUIRED)
else()
    set(CMAKE_C_FLAGS_DEBUG   "-O1 -ggdb")
    set(CMAKE_CXX_FLAGS_DEBUG "-O1 -ggdb")
endif()

add_compile_options(
        -Wall -Wextra -Weffc++ -Wformat=2 -Wcast-qual -Wcast-align
        -Wwrite-strings -Wfloat-equal -Wpointer-arith -Winit-self
        -Wredundant-decls -Woverloaded-virtual -Wsign-promo
        -Wno-system-headers -Wno-deprecated-copy
        -Wno-unused-parameter -Wno-unused-function
        -Wno-vla-cxx-extension
        -fexceptions
        -DHAVE_PTHREAD
)

if(EXT_BUILD_ASAN)
    add_compile_options(-fsanitize=address)
    add_link_options(-fsanitize=address)
endif()

# Enable NEON unconditionally (same as LOCAL_ARM_NEON := true)
if(${ANDROID_ABI} MATCHES "armeabi-v7a")
    add_compile_options(-mfpu=neon)
endif()

# ------------------------------------------------------------------
# Source files
# ------------------------------------------------------------------
include(${CMAKE_CURRENT_SOURCE_DIR}/NativeFileList.cmake)

# ------------------------------------------------------------------
# Static library: libjni_latinime_common_static
# ------------------------------------------------------------------
add_library(jni_latinime_common_static STATIC ${LATIN_IME_CORE_SRC_FILES})

target_include_directories(jni_latinime_common_static PRIVATE ${LATIN_IME_CORE_INCLUDE})
if(EXT_BUILD)
    target_include_directories(jni_latinime_common_static PRIVATE ${JNI_INCLUDE_DIRS})
endif()

if(FLAG_DO_PROFILE)
    message(STATUS "Making profiling version of native library")
    target_compile_definitions(jni_latinime_common_static PRIVATE FLAG_DO_PROFILE)
    target_compile_options(jni_latinime_common_static PRIVATE -funwind-tables)
elseif(FLAG_DBG)
    message(STATUS "Making debug version of native library")
    target_compile_definitions(jni_latinime_common_static PRIVATE FLAG_DBG)
endif()

# ------------------------------------------------------------------
# Shared library: libjni_latinime.so
# ------------------------------------------------------------------
if(NOT EXT_BUILD)
    add_library(jni_latinime SHARED ${LATIN_IME_JNI_SRC_FILES})

    target_include_directories(jni_latinime PRIVATE ${LATIN_IME_JNI_INCLUDE})

    target_link_libraries(jni_latinime
            jni_latinime_common_static
            log
            dl)

    # --------------------------------------------------------------
    # Whistle speech recognition (https://huggingface.co/Cactus-Compute/whistle)
    #
    # Cactus only publishes the Needle engine as a prebuilt static library, so it is fetched at
    # a pinned revision and checked against known hashes. Set NEEDLE_DIR to a folder holding
    # libneedle.a and needle.h to build offline, or WHISTLE=OFF to leave Whistle out.
    # --------------------------------------------------------------
    set(NEEDLE_REVISION "d6c7d6a2e9655505e0f8e23339356dd1705900eb")
    set(NEEDLE_HEADER_SHA256 "a27dac4b3239eead91d124b7f7dbce5495c13019903fd817b68c16d39b055a99")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(NEEDLE_PLATFORM "android-arm64")
        set(NEEDLE_LIB_SHA256 "7aa6de6132a22bf45f5e903425feb66f159ad46f47fa558921bc4fd02c3a2fa0")
    elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
        set(NEEDLE_PLATFORM "android-armv7")
        set(NEEDLE_LIB_SHA256 "9f559a287b201c213e5c753d3cd1076a1849c73f94b445c85d11e4969422a135")
    endif()

    if(WHISTLE AND NEEDLE_PLATFORM)
        if(NOT NEEDLE_DIR)
            set(NEEDLE_DIR "${CMAKE_BINARY_DIR}/needle/${NEEDLE_PLATFORM}")
            set(NEEDLE_URL "https://huggingface.co/Cactus-Compute/needle3/resolve/${NEEDLE_REVISION}/${NEEDLE_PLATFORM}")
            foreach(NEEDLE_FILE IN ITEMS libneedle.a needle.h)
                if(NEEDLE_FILE STREQUAL "needle.h")
                    set(NEEDLE_FILE_SHA256 ${NEEDLE_HEADER_SHA256})
                else()
                    set(NEEDLE_FILE_SHA256 ${NEEDLE_LIB_SHA256})
                endif()
                file(DOWNLOAD "${NEEDLE_URL}/${NEEDLE_FILE}" "${NEEDLE_DIR}/${NEEDLE_FILE}"
                        EXPECTED_HASH SHA256=${NEEDLE_FILE_SHA256}
                        TLS_VERIFY ON)
            endforeach()
        endif()

        add_library(needle STATIC IMPORTED)
        set_target_properties(needle PROPERTIES IMPORTED_LOCATION "${NEEDLE_DIR}/libneedle.a")

        target_include_directories(jni_latinime PRIVATE ${NEEDLE_DIR})
        target_compile_definitions(jni_latinime PRIVATE HAS_NEEDLE)
        target_link_libraries(jni_latinime needle m)
        message(STATUS "Whistle enabled for ${ANDROID_ABI} using ${NEEDLE_DIR}")
    else()
        message(STATUS "Whistle disabled for ${ANDROID_ABI}")
    endif()
endif()