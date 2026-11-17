cmake_minimum_required(VERSION 3.22.1)

project(pocketpty C)

add_library(pocketpty SHARED pty.c)

# Alinhamento de 16 KB: exigido por aparelhos novos com páginas de memória de 16 KB.
target_link_options(pocketpty PRIVATE -Wl,-z,max-page-size=16384)
