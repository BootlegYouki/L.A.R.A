cmake_minimum_required(VERSION 3.31.6)
project("lara-llm" VERSION 1.0.0 LANGUAGES C CXX)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# llama.cpp b11938 lives in third_party/ (tools/fetch_llama.sh); it is never committed.
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)
if(NOT EXISTS ${LLAMA_SRC}/CMakeLists.txt)
    message(FATAL_ERROR "llama.cpp missing: run experiments/mobile-slm-rag/tools/fetch_llama.sh")
endif()

# libomp aborts at its first parallel region under the emulator's ARM translation (kmp_affinity assertion);
# ggml's own thread pool does the same work without it.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
add_subdirectory(${LLAMA_SRC} build-llama)

add_library(lara-llm SHARED lara_llm.cpp)
target_include_directories(lara-llm PRIVATE ${LLAMA_SRC}/common ${LLAMA_SRC}/include ${LLAMA_SRC}/ggml/include)
target_link_libraries(lara-llm llama llama-common android log)
