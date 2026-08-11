cmake_minimum_required(VERSION 3.20)
project(chonk_bne_harness C)

if(NOT WIN32)
    message(FATAL_ERROR "Use cmake/mingw32.cmake; the harness tools are Windows binaries")
endif()

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)

add_library(bne-trace SHARED src/tracer.c)
set_target_properties(bne-trace PROPERTIES
    PREFIX ""
    OUTPUT_NAME "bne-trace")
target_compile_definitions(bne-trace PRIVATE
    WIN32_LEAN_AND_MEAN
    _WIN32_WINNT=0x0501)
target_compile_options(bne-trace PRIVATE -Wall -Wextra -Werror)

add_executable(bne-inject src/injector.c)
target_compile_definitions(bne-inject PRIVATE
    WIN32_LEAN_AND_MEAN
    _WIN32_WINNT=0x0501)
target_compile_options(bne-inject PRIVATE -Wall -Wextra -Werror)
set_target_properties(bne-inject PROPERTIES OUTPUT_NAME "bne-inject")

add_executable(bne-smoke-host src/smoke_host.c)
target_compile_definitions(bne-smoke-host PRIVATE
    WIN32_LEAN_AND_MEAN
    _WIN32_WINNT=0x0501)
target_compile_options(bne-smoke-host PRIVATE -Wall -Wextra -Werror)
set_target_properties(bne-smoke-host PROPERTIES OUTPUT_NAME "bne-smoke-host")

add_executable(bne-ui src/ui.c)
target_compile_definitions(bne-ui PRIVATE
    WIN32_LEAN_AND_MEAN
    _WIN32_WINNT=0x0501)
target_compile_options(bne-ui PRIVATE -Wall -Wextra -Werror)
set_target_properties(bne-ui PROPERTIES OUTPUT_NAME "bne-ui")

add_executable(bne-mpq-probe src/mpq_probe.c)
target_compile_definitions(bne-mpq-probe PRIVATE
    WIN32_LEAN_AND_MEAN
    _WIN32_WINNT=0x0501)
target_compile_options(bne-mpq-probe PRIVATE -Wall -Wextra -Werror)
set_target_properties(bne-mpq-probe PROPERTIES OUTPUT_NAME "bne-mpq-probe")

enable_testing()
if(NOT CMAKE_CROSSCOMPILING)
    add_test(NAME tracer-load
        COMMAND bne-smoke-host.exe $<TARGET_FILE:bne-trace>)
endif()
