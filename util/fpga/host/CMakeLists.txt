cmake_minimum_required(VERSION 3.5.1 FATAL_ERROR)
include($ENV{TAPASCO_HOME_RUNTIME}/cmake/Tapasco.cmake NO_POLICY_SCOPE)
project (simple_sum)

if(NOT TARGET tapasco)
    find_package(Tapasco REQUIRED)
endif()

find_package(Threads)

add_executable(tapascoriscv_scaiev_host tapascoriscv_scaiev_host.cpp)
set_tapasco_defaults(tapascoriscv_scaiev_host)
target_link_libraries(tapascoriscv_scaiev_host PRIVATE tapasco)


install(TARGETS tapascoriscv_scaiev_host
        ARCHIVE  DESTINATION bin/
        LIBRARY  DESTINATION bin/
        RUNTIME  DESTINATION bin/)
