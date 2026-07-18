project(npatch)
cmake_minimum_required(VERSION 3.31.1)

set(CMAKE_CXX_STANDARD 23)

# VERSION_NAME needs to be a quoted string for C/C++
if(DEFINED NPATCH_VERSION_NAME)
    add_compile_definitions(VERSION_NAME="${NPATCH_VERSION_NAME}")
endif()

add_subdirectory(${CORE_ROOT} native)

# xHook (PLT/GOT hooking) — used by bypass_sig to redirect openat without inline-patching
# libc's executable segment (which native anti-tamper detectors flag). Third-party C code,
# built with warnings disabled.
set(XHOOK_DIR ${CMAKE_CURRENT_SOURCE_DIR}/external/xhook)
add_library(xhook STATIC
        ${XHOOK_DIR}/xh_core.c
        ${XHOOK_DIR}/xh_elf.c
        ${XHOOK_DIR}/xh_util.c
        ${XHOOK_DIR}/xh_log.c
        ${XHOOK_DIR}/xh_version.c
        ${XHOOK_DIR}/xhook.c)
target_include_directories(xhook PUBLIC ${XHOOK_DIR})
target_compile_options(xhook PRIVATE -std=c11 -w -O2 -fvisibility=hidden)
target_link_libraries(xhook log)

aux_source_directory(src SRC_LIST)
aux_source_directory(src/jni SRC_LIST)
set(SRC_LIST ${SRC_LIST} api/patch_main.cpp)
set(SRC_LIST ${SRC_LIST} src/jni/bypass_sig.cpp)
set(SRC_LIST ${SRC_LIST} src/jni/bypass_svc.cpp)

add_library(${PROJECT_NAME} SHARED ${SRC_LIST})

target_include_directories(${PROJECT_NAME} PUBLIC include)
target_include_directories(${PROJECT_NAME} PRIVATE src ${XHOOK_DIR})

target_link_libraries(${PROJECT_NAME} native log xhook)

if (DEFINED DEBUG_SYMBOLS_PATH)
    set(DEBUG_SYMBOLS_PATH ${DEBUG_SYMBOLS_PATH}/${API})
    message(STATUS "Debug symbols will be placed at ${DEBUG_SYMBOLS_PATH}")
    add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E make_directory ${DEBUG_SYMBOLS_PATH}/${ANDROID_ABI}
            COMMAND ${CMAKE_OBJCOPY} --only-keep-debug $<TARGET_FILE:${PROJECT_NAME}>
            ${DEBUG_SYMBOLS_PATH}/${ANDROID_ABI}/${PROJECT_NAME}.debug
            COMMAND ${CMAKE_STRIP} --strip-all $<TARGET_FILE:${PROJECT_NAME}>
            COMMAND ${CMAKE_OBJCOPY} --add-gnu-debuglink ${DEBUG_SYMBOLS_PATH}/${ANDROID_ABI}/${PROJECT_NAME}.debug
            $<TARGET_FILE:${PROJECT_NAME}>)
endif()