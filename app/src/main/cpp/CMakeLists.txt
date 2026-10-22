cmake_minimum_required(VERSION 3.22.1)

project(shelld-client C)

add_library(shelld-client SHARED shelld-client.c)

find_library(log-lib log)

target_link_libraries(shelld-client ${log-lib})
